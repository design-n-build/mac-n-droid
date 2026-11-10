import AppKit

/// The browser extension ships inside the app and is copied to
/// ~/Library/Application Support/MacNDroid/Browser Extension, where browsers can load it
/// ("Load unpacked"). Updated copies replace it when the app updates; the browser picks them up on restart.
enum BrowserExtension {
    static var folder: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("MacNDroid/Browser Extension", isDirectory: true)
    }

    private static var bundled: URL? { Bundle.main.url(forResource: "BrowserExtension", withExtension: nil) }

    static func install() {
        guard let src = bundled else { return }
        let fm = FileManager.default
        let version = { (dir: URL) -> String? in
            (try? Data(contentsOf: dir.appendingPathComponent("manifest.json")))
                .flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }?["version"] as? String
        }
        guard version(folder) != version(src) else { return }
        try? fm.createDirectory(at: folder.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? fm.removeItem(at: folder)
        try? fm.copyItem(at: src, to: folder)
    }

    static func reveal() {
        install()
        NSWorkspace.shared.activateFileViewerSelecting([folder])
    }

    // MARK: Stores

    /// Each store's page for the extension. Until it's published there (and its link is filled in here),
    /// the store's search for "Mac n Droid" opens instead.
    static let chromeWebStore = URL(string: "https://chromewebstore.google.com/search/Mac%20n%20Droid")!
    static let edgeAddOns = URL(string: "https://microsoftedge.microsoft.com/addons/search/Mac%20n%20Droid")!
    static let firefoxAddOns = URL(string: "https://addons.mozilla.org/firefox/search/?q=Mac%20n%20Droid")!

    /// Chrome-based browsers share the Chrome Web Store (Edge has its own store too); Firefox-based ones
    /// (Firefox, Zen, LibreWolf…) share Firefox Add-ons.
    enum Family { case chrome, firefox }

    private static let chromeBased = [
        "com.google.Chrome", "com.microsoft.edgemac", "com.brave.Browser", "company.thebrowser.Browser",
        "com.vivaldi.Vivaldi", "com.operasoftware.Opera", "net.imput.helium", "org.chromium.Chromium",
    ]
    private static let firefoxBased = [
        "org.mozilla.firefox", "org.mozilla.firefoxdeveloperedition", "org.mozilla.nightly", "app.zen-browser.zen",
        "org.mozilla.librewolf", "net.waterfox.waterfox", "one.ablaze.floorp",
    ]

    private static func ids(_ f: Family) -> [String] { f == .chrome ? chromeBased : firefoxBased }

    /// The family of the default browser, so its button can be the default one.
    static var defaultFamily: Family {
        firefoxBased.contains(defaultBrowserID ?? "") ? .firefox : .chrome
    }

    private static var defaultBrowserID: String? {
        NSWorkspace.shared.urlForApplication(toOpen: URL(string: "https://example.com")!)
            .flatMap { Bundle(url: $0)?.bundleIdentifier }
    }

    /// Opens the family's extension store in the browser the person most likely uses: one of that family
    /// that's open right now, else the default browser if it's in the family, else the best-known one installed.
    static func open(_ family: Family) {
        let ws = NSWorkspace.shared
        let list = ids(family)
        let running = ws.runningApplications.compactMap(\.bundleIdentifier).filter { list.contains($0) }
        let pick = running.first(where: { $0 == defaultBrowserID }) ?? running.first
            ?? (list.contains(defaultBrowserID ?? "") ? defaultBrowserID : nil)
            ?? list.first { ws.urlForApplication(withBundleIdentifier: $0) != nil }
        let store = family == .firefox ? firefoxAddOns : (pick == "com.microsoft.edgemac" ? edgeAddOns : chromeWebStore)
        if let pick, let app = ws.urlForApplication(withBundleIdentifier: pick) {
            ws.open([store], withApplicationAt: app, configuration: NSWorkspace.OpenConfiguration())
        } else {
            ws.open(store)
        }
    }
}
