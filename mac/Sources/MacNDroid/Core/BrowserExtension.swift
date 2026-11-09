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
}
