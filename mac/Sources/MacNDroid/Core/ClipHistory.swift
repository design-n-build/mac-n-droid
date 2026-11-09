import AppKit

/// One thing copied on the Mac or the phone.
struct ClipItem: Identifiable, Codable, Equatable {
    enum Kind: String, Codable { case text, image }
    let id: String
    let kind: Kind
    var text: String?
    /// PNG file name inside the history folder.
    var imageFile: String?
    var fromPhone: Bool
    var time: Date
}

/// The last 20 things copied on either device (clipboard history).
///
/// Kept on this Mac in ~/Library/Application Support/MacNDroid/ClipHistory and shown on the phone.
/// Copies that password managers mark as private are never recorded.
final class ClipHistoryStore {
    static let limit = 20
    private(set) var items: [ClipItem] = []

    private let dir: URL = {
        let d = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("MacNDroid/ClipHistory", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()
    private var indexURL: URL { dir.appendingPathComponent("history.json") }

    init() {
        if let data = try? Data(contentsOf: indexURL),
           let list = try? JSONDecoder().decode([ClipItem].self, from: data) {
            items = list
        }
    }

    func addText(_ text: String, fromPhone: Bool) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        items.removeAll { $0.kind == .text && $0.text == text }
        items.insert(ClipItem(id: UUID().uuidString, kind: .text, text: text, fromPhone: fromPhone, time: Date()), at: 0)
        trim()
    }

    func addImage(_ png: Data, fromPhone: Bool) {
        let id = UUID().uuidString
        let file = "\(id).png"
        guard (try? png.write(to: dir.appendingPathComponent(file))) != nil else { return }
        // The same image again (by size and bytes) moves to the top instead of repeating.
        if let dup = items.first(where: { $0.kind == .image && self.png($0) == png }) { remove(dup.id) }
        items.insert(ClipItem(id: id, kind: .image, imageFile: file, fromPhone: fromPhone, time: Date()), at: 0)
        trim()
    }

    /// Copied again: move to the top.
    func touch(_ id: String) {
        guard let i = items.firstIndex(where: { $0.id == id }) else { return }
        var item = items.remove(at: i)
        item.time = Date()
        items.insert(item, at: 0)
        save()
    }

    func remove(_ id: String) {
        guard let i = items.firstIndex(where: { $0.id == id }) else { return }
        if let f = items[i].imageFile { try? FileManager.default.removeItem(at: dir.appendingPathComponent(f)) }
        items.remove(at: i)
        save()
    }

    func clear() {
        for item in items { if let f = item.imageFile { try? FileManager.default.removeItem(at: dir.appendingPathComponent(f)) } }
        items.removeAll()
        save()
    }

    func png(_ item: ClipItem) -> Data? {
        item.imageFile.flatMap { try? Data(contentsOf: dir.appendingPathComponent($0)) }
    }

    func image(_ item: ClipItem) -> NSImage? {
        item.imageFile.flatMap { NSImage(contentsOf: dir.appendingPathComponent($0)) }
    }

    private func trim() {
        while items.count > Self.limit {
            let old = items.removeLast()
            if let f = old.imageFile { try? FileManager.default.removeItem(at: dir.appendingPathComponent(f)) }
        }
        save()
    }

    private func save() {
        if let data = try? JSONEncoder().encode(items) { try? data.write(to: indexURL, options: .atomic) }
    }
}
