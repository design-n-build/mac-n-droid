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

    /// Each store's page for the extension.
    static let chromeWebStore = URL(string: "https://chromewebstore.google.com/detail/mac-n-droid/ddiakkecgnhdkmfcagclppckfbchmbga")!
    static let edgeAddOns = URL(string: "https://microsoftedge.microsoft.com/addons/detail/apnollpkjjokneagmeopfjpmoaodpkab")!
    static let firefoxAddOns = URL(string: "https://addons.mozilla.org/firefox/addon/mac-n-droid/")!

    /// Short names for the button ("Add to Chrome"); others use the app's own name.
    private static let shortNames = [
        "com.google.Chrome": "Chrome", "com.microsoft.edgemac": "Edge", "com.brave.Browser": "Brave",
        "company.thebrowser.Browser": "Arc", "org.mozilla.firefox": "Firefox", "app.zen-browser.zen": "Zen",
    ]

    /// A browser the extension works in, and the store it installs from.
    struct Browser {
        let app: URL
        let name: String
        let store: URL
    }

    /// Chrome-based browsers (Chrome, Brave, Arc, Helium…) share the Chrome Web Store, except Edge, which has its
    /// own; Firefox-based ones (Firefox, Zen, LibreWolf…) share Firefox Add-ons. Worked out from what the browser
    /// is built on, so new browsers work without a list: Chromium ones carry a "… Framework.framework" with
    /// helper apps, Firefox ones the XUL library. Safari, and apps built on Electron, aren't supported.
    private static func browser(at app: URL) -> Browser? {
        let fm = FileManager.default
        let id = Bundle(url: app)?.bundleIdentifier ?? ""
        let store: URL
        if fm.fileExists(atPath: app.appendingPathComponent("Contents/MacOS/XUL").path) {
            store = firefoxAddOns
        } else if let frameworks = try? fm.contentsOfDirectory(atPath: app.appendingPathComponent("Contents/Frameworks").path),
                  frameworks.contains(where: { name in
                      name.hasSuffix(" Framework.framework") && name != "Electron Framework.framework"
                          && fm.fileExists(atPath: app.appendingPathComponent("Contents/Frameworks/\(name)/Versions/Current/Helpers").path)
                  }) {
            store = id.hasPrefix("com.microsoft.edgemac") ? edgeAddOns : chromeWebStore
        } else {
            return nil
        }
        let name = shortNames[id] ?? fm.displayName(atPath: app.path).replacingOccurrences(of: ".app", with: "")
        return Browser(app: app, name: name, store: store)
    }

    private static let web = URL(string: "https://example.com")!

    /// The default browser, when the extension works in it.
    static var defaultIsSupported: Bool {
        NSWorkspace.shared.urlForApplication(toOpen: web).flatMap(browser(at:)) != nil
    }

    /// The browser to add the extension to: the default browser, or, if that's Safari or another browser the
    /// extension doesn't work in, a supported one that's open, else one that's installed.
    static var preferred: Browser? {
        let ws = NSWorkspace.shared
        if let b = ws.urlForApplication(toOpen: web).flatMap(browser(at:)) { return b }
        let all = ws.urlsForApplications(toOpen: web)
        let running = Set(ws.runningApplications.compactMap(\.bundleURL))
        return all.filter(running.contains).lazy.compactMap(browser(at:)).first
            ?? all.lazy.compactMap(browser(at:)).first
    }

    /// Opens the extension's page in that browser's store, in that browser.
    static func open(_ b: Browser) {
        NSWorkspace.shared.open([b.store], withApplicationAt: b.app, configuration: NSWorkspace.OpenConfiguration())
    }

    /// For "Using another browser?": each store, opened in the default browser.
    static let stores: [(name: String, url: URL)] = [
        ("Chrome Web Store (Chrome, Brave, Arc and other Chrome-based browsers)", chromeWebStore),
        ("Microsoft Edge Add-ons", edgeAddOns),
        ("Firefox Add-ons (Firefox, Zen and other Firefox-based browsers)", firefoxAddOns),
    ]
}
