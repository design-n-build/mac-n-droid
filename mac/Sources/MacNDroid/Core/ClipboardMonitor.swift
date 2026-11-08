import AppKit

/// Watches the Mac pasteboard for new text or images. Skips content marked concealed by password managers.
final class ClipboardMonitor {
    var onChange: ((String) -> Void)?
    var onImage: ((Data) -> Void)?
    private var lastChange = NSPasteboard.general.changeCount
    private var timer: Timer?

    private static let concealed = NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")
    private static let transient = NSPasteboard.PasteboardType("org.nspasteboard.TransientType")
    private static let maxImageBytes = 20 * 1024 * 1024

    func start() {
        timer = Timer.scheduledTimer(withTimeInterval: 0.6, repeats: true) { [weak self] _ in self?.poll() }
    }

    private func poll() {
        let pb = NSPasteboard.general
        guard pb.changeCount != lastChange else { return }
        lastChange = pb.changeCount
        let types = pb.types ?? []
        guard !types.contains(Self.concealed), !types.contains(Self.transient) else { return }
        // Text wins: apps like Office also attach a picture of copied text.
        if let text = pb.string(forType: .string), !text.isEmpty {
            if text.utf8.count < 1_000_000 { onChange?(text) }
        } else if let png = Self.currentImagePNG() {
            onImage?(png)
        }
    }

    /// The pasteboard image as PNG, ignoring Finder file copies (which carry the file's icon).
    static func currentImagePNG() -> Data? {
        let pb = NSPasteboard.general
        let types = pb.types ?? []
        guard !types.contains(.fileURL) else { return nil }
        let data: Data?
        if let png = pb.data(forType: .png) {
            data = png
        } else if let tiff = pb.data(forType: .tiff), let rep = NSBitmapImageRep(data: tiff) {
            data = rep.representation(using: .png, properties: [:])
        } else {
            data = nil
        }
        guard let data, data.count <= maxImageBytes else { return nil }
        return data
    }

    /// Writes without echoing the change back to the phone.
    func write(_ text: String) {
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(text, forType: .string)
        lastChange = pb.changeCount
    }

    /// Accepts PNG, JPEG, WebP… from the phone; publishes PNG + TIFF so every app can paste it.
    func writeImage(_ data: Data) {
        let pb = NSPasteboard.general
        pb.clearContents()
        if let image = NSImage(data: data) {
            pb.writeObjects([image])
            if let tiff = image.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff),
               let png = rep.representation(using: .png, properties: [:]) {
                pb.setData(png, forType: .png)
            }
        }
        lastChange = pb.changeCount
    }
}
