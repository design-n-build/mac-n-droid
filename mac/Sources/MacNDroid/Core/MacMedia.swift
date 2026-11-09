import AppKit

/// What's playing on this Mac, for the phone's media player ("Music on your Mac").
///
/// Music and Spotify announce every play, pause and track change system-wide, so watching needs no
/// permission. Controlling them (and reading Music's artwork) uses AppleScript, which macOS asks
/// about once: "Mac n Droid wants to control Music".
///
/// Browsers and other players aren't included: macOS no longer lets apps read the system's
/// Now Playing info.
final class MacMediaMonitor {
    struct Track: Equatable {
        var bundle: String
        var app: String
        var id: String
        var title: String
        var artist: String
        var album: String
        var playing: Bool
        var duration: Double
        var position: Double
        /// When it last started playing (or changed): the newest playing one is what the phone controls.
        var since = Date()
        var isBrowser: Bool { bundle.hasPrefix("browser:") }
    }

    static let music = "com.apple.Music"
    static let spotify = "com.spotify.client"

    var onChange: ((Track?) -> Void)?
    var onArt: ((Data) -> Void)?
    private(set) var current: Track?
    /// Music or Spotify.
    private var native: Track?
    /// Tabs playing in browsers with the Mac n Droid extension, by "connection:tab".
    private var browser: [String: Track] = [:]
    private var browserArt: [String: Data] = [:]
    var browserCommand: ((String, String) -> Void)?
    private var artFor: String?
    private var started = false

    func start() {
        guard !started else { return }
        started = true
        let dnc = DistributedNotificationCenter.default()
        dnc.addObserver(forName: .init("com.apple.Music.playerInfo"), object: nil, queue: .main) { [weak self] n in
            self?.update(bundle: Self.music, app: "Music", info: n.userInfo ?? [:])
        }
        dnc.addObserver(forName: .init("com.spotify.client.PlaybackStateChanged"), object: nil, queue: .main) { [weak self] n in
            self?.update(bundle: Self.spotify, app: "Spotify", info: n.userInfo ?? [:])
        }
        NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didTerminateApplicationNotification, object: nil, queue: .main) { [weak self] n in
            let app = n.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication
            if let self, app?.bundleIdentifier == self.native?.bundle { self.native = nil; self.recompute() }
        }
    }

    private func update(bundle: String, app: String, info: [AnyHashable: Any]) {
        let state = info["Player State"] as? String ?? ""
        if state == "Stopped" {
            if native?.bundle == bundle { native = nil; recompute() }
            return
        }
        let title = info["Name"] as? String ?? native?.title ?? ""
        guard !title.isEmpty else { return }
        let playing = state == "Playing"
        // A paused app doesn't replace one that's playing.
        if let cur = native, cur.bundle != bundle, cur.playing, !playing { return }
        let durMs = (info["Total Time"] as? NSNumber ?? info["Duration"] as? NSNumber)?.doubleValue ?? 0
        let t = Track(
            bundle: bundle, app: app,
            id: (info["Persistent ID"] as? NSNumber)?.stringValue ?? info["Track ID"] as? String ?? title,
            title: title,
            artist: info["Artist"] as? String ?? "",
            album: info["Album"] as? String ?? "",
            playing: playing,
            duration: durMs / 1000,
            position: (info["Playback Position"] as? NSNumber)?.doubleValue ?? 0)
        let changedTrack = t.id != native?.id || t.bundle != native?.bundle
        var t2 = t
        t2.since = (!changedTrack && native?.playing == playing) ? (native?.since ?? Date()) : Date()
        native = t2
        recompute()
        if changedTrack { fetchArt(t2) }
    }

    /// From the browser extension. `nil` ends a tab (a key ending in ":" ends all of a browser's tabs).
    func setBrowser(_ key: String, _ b: BrowserBridge.Track?) {
        if let b {
            let old = browser[key]
            let changed = old?.title != b.title || old?.playing != b.playing
            browser[key] = Track(bundle: "browser:" + key, app: b.site, id: "\(key)|\(b.title)", title: b.title,
                                 artist: b.artist, album: b.album, playing: b.playing, duration: b.duration,
                                 position: b.position, since: changed ? Date() : (old?.since ?? Date()))
            if old?.title != b.title { browserArt[key] = nil }
        } else if key.hasSuffix(":") {
            browser = browser.filter { !$0.key.hasPrefix(key) }
        } else {
            browser[key] = nil
            browserArt[key] = nil
        }
        recompute()
    }

    func setBrowserArt(_ key: String, _ jpeg: Data) {
        browserArt[key] = jpeg
        if current?.bundle == "browser:" + key { onArt?(jpeg) }
    }

    /// Playing beats paused; among those, the one that started most recently.
    private func recompute() {
        let all = [native].compactMap { $0 } + Array(browser.values)
        let best = all.filter(\.playing).max { $0.since < $1.since } ?? all.max { $0.since < $1.since }
        let switched = best?.bundle != current?.bundle
        current = best
        onChange?(best)
        if switched, let b = best, b.isBrowser, let art = browserArt[String(b.bundle.dropFirst(8))] { onArt?(art) }
    }

    /// Artwork only when macOS already allows controlling the app, so watching never prompts.
    private func fetchArt(_ t: Track) {
        artFor = t.id
        DispatchQueue.global(qos: .utility).async {
            guard AppleScriptRunner.isAllowed(t.bundle) else { return }
            let source = t.bundle == Self.music
                ? "tell application id \"\(Self.music)\" to if (count of artworks of current track) > 0 then return raw data of artwork 1 of current track"
                : "tell application id \"\(Self.spotify)\" to return artwork url of current track"
            AppleScriptRunner.run(source) { [weak self] result in
                guard let self, self.artFor == t.id, case .success(let desc) = result else { return }
                if t.bundle == Self.music {
                    if let jpeg = Self.jpeg(desc.data) { self.onArt?(jpeg) }
                } else if let s = desc.stringValue, let url = URL(string: s), url.scheme == "https" {
                    URLSession.shared.dataTask(with: url) { data, _, _ in
                        guard let data, let jpeg = Self.jpeg(data) else { return }
                        DispatchQueue.main.async { if self.artFor == t.id { self.onArt?(jpeg) } }
                    }.resume()
                }
            }
        }
    }

    static func jpeg(_ data: Data, max: CGFloat = 300) -> Data? {
        guard let img = NSImage(data: data), let cg = img.cgImage(forProposedRect: nil, context: nil, hints: nil) else { return nil }
        let scale = min(1, max / CGFloat(Swift.max(cg.width, cg.height)))
        let w = Int(CGFloat(cg.width) * scale), h = Int(CGFloat(cg.height) * scale)
        guard w > 0, h > 0, let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                                               space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
        else { return nil }
        ctx.interpolationQuality = .high
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        guard let out = ctx.makeImage() else { return nil }
        return NSBitmapImageRep(cgImage: out).representation(using: .jpeg, properties: [.compressionFactor: 0.8])
    }

    /// From the phone: play, pause, toggle, next, prev. The first time, macOS asks for permission.
    func command(_ cmd: String) {
        guard let t = current else { return }
        if t.isBrowser { browserCommand?(cmd, String(t.bundle.dropFirst(8))); return }
        let verb: String = switch cmd {
        case "play": "play"
        case "pause": "pause"
        case "next": "next track"
        case "prev": "previous track"
        default: "playpause"
        }
        AppleScriptRunner.run("tell application id \"\(t.bundle)\" to \(verb)")
    }
}
