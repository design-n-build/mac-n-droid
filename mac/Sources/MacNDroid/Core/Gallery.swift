import AppKit
import Foundation

/// One photo or video on the phone (`gallery` in PROTOCOL.md).
struct GalleryItem: Identifiable, Hashable {
    let id: String
    let isVideo: Bool
    let name: String
    let date: Date
    let size: Int64
    let width: Int
    let height: Int
    let duration: TimeInterval

    init?(_ m: Message) {
        guard let id = m["id"] as? String else { return nil }
        self.id = id
        isVideo = m["v"] as? Bool ?? false
        name = m["name"] as? String ?? "Photo"
        date = Date(timeIntervalSince1970: ((m["date"] as? NSNumber)?.doubleValue ?? 0) / 1000)
        size = (m["size"] as? NSNumber)?.int64Value ?? 0
        width = (m["w"] as? NSNumber)?.intValue ?? 0
        height = (m["h"] as? NSNumber)?.intValue ?? 0
        duration = ((m["dur"] as? NSNumber)?.doubleValue ?? 0) / 1000
    }
}

struct GalleryAlbum: Identifiable, Hashable {
    let id: String
    let name: String
    let count: Int
}

/// The phone's photo and video library, loaded a page at a time. Thumbnails are asked for as cells appear;
/// the full file only when you open or save one. Nothing is kept on disk except files you opened (in Caches).
final class GalleryModel: ObservableObject {
    enum Access: String { case unknown, full, photos, partial, none, off }
    enum Kind: String, CaseIterable, Identifiable {
        case all, photos, videos
        var id: String { rawValue }
        var title: String { self == .all ? "All" : self == .photos ? "Photos" : "Videos" }
    }

    @Published private(set) var items: [GalleryItem] = []
    @Published private(set) var albums: [GalleryAlbum] = []
    @Published private(set) var total = 0
    @Published private(set) var access: Access = .unknown
    @Published private(set) var loading = false
    @Published var kind: Kind = .all { didSet { if kind != oldValue { reload() } } }
    @Published var album: String? { didSet { if album != oldValue { reload() } } }
    @Published private(set) var thumbs: [String: NSImage] = [:]
    /// Items being fetched to open.
    @Published private(set) var opening: Set<String> = []
    /// Set when an opened file has arrived; the view shows it in Quick Look.
    @Published var preview: URL?

    var send: (Message) -> Void = { _ in }
    var connected = false
    private var requestedOffset: Int?
    private var wantedThumbs: [String] = []
    private var askedThumbs: Set<String> = []
    private var thumbWork: DispatchWorkItem?
    private static let pageSize = 120

    static let cacheDir: URL = {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("app.macndroid/Gallery", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    var hasMore: Bool { items.count < total }

    // MARK: Requests

    func reload() {
        items = []
        total = 0
        requestedOffset = nil
        loadMore()
    }

    func loadIfNeeded() {
        if items.isEmpty && !loading { reload() }
    }

    func loadMore() {
        guard connected else { return }
        let offset = items.count
        guard requestedOffset != offset else { return }
        requestedOffset = offset
        loading = true
        var msg: Message = ["t": "gallery_list", "offset": offset, "limit": Self.pageSize, "kind": kind.rawValue]
        if let album { msg["album"] = album }
        send(msg)
    }

    func askForAccess() { send(["t": "gallery_ask"]) }

    /// A cell came on screen: batch up its thumbnail request.
    func wantThumb(_ id: String) {
        guard thumbs[id] == nil, !askedThumbs.contains(id) else { return }
        askedThumbs.insert(id)
        wantedThumbs.append(id)
        thumbWork?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.flushThumbs() }
        thumbWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05, execute: work)
    }

    private func flushThumbs() {
        guard connected, !wantedThumbs.isEmpty else { return }
        let scale = NSScreen.main?.backingScaleFactor ?? 2
        while !wantedThumbs.isEmpty {
            let batch = Array(wantedThumbs.prefix(40))
            wantedThumbs.removeFirst(batch.count)
            send(["t": "gallery_thumbs", "ids": batch, "size": Int(160 * scale)])
        }
    }

    /// Opens in Quick Look (fetching the original first, unless it was opened before).
    func open(_ item: GalleryItem) {
        if let cached = cachedURL(item) { preview = cached; return }
        guard connected, !opening.contains(item.id) else { return }
        opening.insert(item.id)
        send(["t": "gallery_get", "id": item.id, "mode": "view"])
    }

    /// Saves originals to Downloads, through the normal transfer list.
    func save(_ list: [GalleryItem]) {
        for item in list {
            if let cached = cachedURL(item) { copyToDownloads(cached, name: item.name); continue }
            send(["t": "gallery_get", "id": item.id, "mode": "save"])
        }
    }

    private func copyToDownloads(_ url: URL, name: String) {
        let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
        var dest = downloads.appendingPathComponent(name)
        let base = (name as NSString).deletingPathExtension, ext = (name as NSString).pathExtension
        var n = 2
        while FileManager.default.fileExists(atPath: dest.path) {
            dest = downloads.appendingPathComponent(ext.isEmpty ? "\(base) \(n)" : "\(base) \(n).\(ext)")
            n += 1
        }
        try? FileManager.default.copyItem(at: url, to: dest)
        NSWorkspace.shared.activateFileViewerSelecting([dest])
    }

    func cacheFileName(_ id: String, _ name: String) -> String {
        "\(AppState.safeID(id) ?? "item")-\(AppState.safeFileName(name))"
    }

    private func cachedURL(_ item: GalleryItem) -> URL? {
        let url = Self.cacheDir.appendingPathComponent(cacheFileName(item.id, item.name))
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    // MARK: From the phone

    func handle(_ msg: Message) {
        switch msg["t"] as? String {
        case "gallery":
            access = Access(rawValue: msg["access"] as? String ?? "") ?? .none
            if access == .off { forget(); loading = false; return }
            // Ignore pages for a filter that's no longer showing.
            guard (msg["kind"] as? String ?? "all") == kind.rawValue, (msg["album"] as? String) == album else { return }
            let offset = (msg["offset"] as? NSNumber)?.intValue ?? 0
            guard offset == items.count else { loading = false; return }
            let page = (msg["items"] as? [Message] ?? []).compactMap(GalleryItem.init)
            items.append(contentsOf: page)
            total = (msg["total"] as? NSNumber)?.intValue ?? items.count
            if let a = msg["albums"] as? [Message] {
                albums = a.compactMap { m in
                    guard let id = m["id"] as? String else { return nil }
                    return GalleryAlbum(id: id, name: m["name"] as? String ?? "Album", count: (m["count"] as? NSNumber)?.intValue ?? 0)
                }
            }
            loading = false
        case "gallery_thumb":
            guard let id = msg["id"] as? String else { return }
            if let b64 = msg["d"] as? String, let data = Data(base64Encoded: b64), let img = NSImage(data: data) {
                thumbs[id] = img
            }
        case "gallery_access":
            let new = Access(rawValue: msg["access"] as? String ?? "") ?? .none
            if new == .off { access = new; forget(); return }
            if new != access || items.isEmpty { access = new; reload() }
        default: break
        }
    }

    /// Turned off on the phone: drop everything shown and the originals opened before.
    private func forget() {
        items = []; albums = []; total = 0; thumbs = [:]; askedThumbs = []; opening = []; preview = nil
        requestedOffset = nil
        let fm = FileManager.default
        for url in (try? fm.contentsOfDirectory(at: Self.cacheDir, includingPropertiesForKeys: nil)) ?? [] {
            try? fm.removeItem(at: url)
        }
    }

    /// An original asked for with "view" has arrived (called on the main queue).
    func arrived(_ id: String, url: URL) {
        opening.remove(id)
        preview = url
    }

    func phoneConnected() {
        connected = true
        askedThumbs = Set(thumbs.keys)
        if !items.isEmpty || access != .unknown { reload() }
    }

    func phoneDisconnected() {
        connected = false
        loading = false
        opening.removeAll()
        requestedOffset = nil
        wantedThumbs.removeAll()
    }
}
