import Cocoa
import UniformTypeIdentifiers

/// "Share → Send to Phone" in any app (Finder, Photos, Safari, Preview…).
///
/// Share extensions run in a sandbox, separate from Mac n Droid, so this hands what you shared to the
/// running app over its local link on this Mac (ws://127.0.0.1:47809, the same one the browser
/// extension uses), and the app sends it to the phone. Files are streamed in pieces, so large videos
/// don't need to fit in memory. Web links open on the phone; other text goes to its clipboard.
final class ShareViewController: NSViewController {
    private let icon = NSImageView()
    private let heading = NSTextField(labelWithString: "Sending to your phone…")
    private let detail = NSTextField(labelWithString: "")
    private let progress = NSProgressIndicator()
    private let button = NSButton(title: "Cancel", target: nil, action: nil)
    private var socket: URLSessionWebSocketTask?
    private var task: Task<Void, Never>?

    override func loadView() {
        let v = NSView(frame: NSRect(x: 0, y: 0, width: 340, height: 132))
        icon.image = NSImage(systemSymbolName: "smartphone", accessibilityDescription: nil)?
            .withSymbolConfiguration(.init(pointSize: 24, weight: .regular))
        icon.contentTintColor = NSColor(srgbRed: 0x0D / 255, green: 0xA4 / 255, blue: 0x57 / 255, alpha: 1)
        heading.font = .systemFont(ofSize: 13, weight: .semibold)
        detail.font = .systemFont(ofSize: 11)
        detail.textColor = .secondaryLabelColor
        detail.lineBreakMode = .byTruncatingMiddle
        progress.style = .bar
        progress.isIndeterminate = true
        progress.minValue = 0
        progress.maxValue = 1
        button.bezelStyle = .push
        button.keyEquivalent = "\u{1b}"
        button.target = self
        button.action = #selector(close)
        for sub in [icon, heading, detail, progress, button] as [NSView] {
            sub.translatesAutoresizingMaskIntoConstraints = false
            v.addSubview(sub)
        }
        NSLayoutConstraint.activate([
            icon.leadingAnchor.constraint(equalTo: v.leadingAnchor, constant: 20),
            icon.topAnchor.constraint(equalTo: v.topAnchor, constant: 20),
            icon.widthAnchor.constraint(equalToConstant: 32),
            heading.leadingAnchor.constraint(equalTo: icon.trailingAnchor, constant: 12),
            heading.trailingAnchor.constraint(equalTo: v.trailingAnchor, constant: -20),
            heading.topAnchor.constraint(equalTo: v.topAnchor, constant: 20),
            detail.leadingAnchor.constraint(equalTo: heading.leadingAnchor),
            detail.trailingAnchor.constraint(equalTo: heading.trailingAnchor),
            detail.topAnchor.constraint(equalTo: heading.bottomAnchor, constant: 3),
            progress.leadingAnchor.constraint(equalTo: heading.leadingAnchor),
            progress.trailingAnchor.constraint(equalTo: heading.trailingAnchor),
            progress.topAnchor.constraint(equalTo: detail.bottomAnchor, constant: 10),
            button.trailingAnchor.constraint(equalTo: v.trailingAnchor, constant: -20),
            button.bottomAnchor.constraint(equalTo: v.bottomAnchor, constant: -16),
        ])
        view = v
        preferredContentSize = v.frame.size
    }

    override func viewDidAppear() {
        super.viewDidAppear()
        progress.startAnimation(nil)
        guard task == nil else { return }
        task = Task { await run() }
    }

    @objc private func close() {
        task?.cancel()
        socket?.cancel(with: .goingAway, reason: nil)
        if button.title == "Done" {
            extensionContext?.completeRequest(returningItems: nil)
        } else {
            extensionContext?.cancelRequest(withError: NSError(domain: NSCocoaErrorDomain, code: NSUserCancelledError))
        }
    }

    // MARK: Sending

    private enum Item { case file(URL, name: String), link(URL), text(String) }

    private func run() async {
        let items = await collect()
        guard !items.isEmpty else { return fail("There's nothing here that can be sent.") }
        guard let (ws, phone) = await connect() else {
            return fail("Open Mac n Droid on this Mac, then try again.")
        }
        socket = ws
        guard let phone else { return fail("Your phone isn't connected to Mac n Droid.") }
        heading.stringValue = "Sending to \(phone)…"
        do {
            var files = 0
            for item in items {
                try Task.checkCancellation()
                switch item {
                case .link(let url):
                    detail.stringValue = url.host() ?? url.absoluteString
                    try await send(ws, ["t": "send_url", "url": url.absoluteString])
                case .text(let text):
                    detail.stringValue = String(text.prefix(80))
                    try await send(ws, ["t": "send_text", "text": text])
                case .file(let url, let name):
                    files += 1
                    try await stream(url, name: name, over: ws)
                }
            }
            if files > 0 { try await send(ws, ["t": "files_done"]) }
            // Give the app a moment to receive the last piece before the connection closes.
            try await Task.sleep(for: .milliseconds(300))
            done(files > 1 ? "Sent \(files) files to \(phone)" : "Sent to \(phone)")
        } catch is CancellationError {
        } catch {
            fail("Couldn't finish sending. Check that Mac n Droid is still open.")
        }
    }

    private func stream(_ url: URL, name: String, over ws: URLSessionWebSocketTask) async throws {
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        detail.stringValue = name
        try await send(ws, ["t": "file_begin", "name": name, "size": size])
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var sent = 0
        progress.isIndeterminate = size == 0
        while let chunk = try handle.read(upToCount: 512 * 1024), !chunk.isEmpty {
            try Task.checkCancellation()
            try await ws.send(.data(chunk))
            sent += chunk.count
            if size > 0 { progress.doubleValue = Double(sent) / Double(size) }
        }
        try await send(ws, ["t": "file_end"])
    }

    private func send(_ ws: URLSessionWebSocketTask, _ m: [String: Any]) async throws {
        let data = try JSONSerialization.data(withJSONObject: m)
        try await ws.send(.string(String(decoding: data, as: UTF8.self)))
    }

    /// Connects to the running app and reads its status: the phone's name, or nil when no phone is connected.
    /// Starts Mac n Droid if it isn't running.
    private func connect() async -> (URLSessionWebSocketTask, String?)? {
        for attempt in 0..<12 {
            if Task.isCancelled { return nil }
            var req = URLRequest(url: URL(string: "ws://127.0.0.1:47809/")!)
            req.setValue("macndroid-share://share", forHTTPHeaderField: "Origin")
            let ws = URLSession.shared.webSocketTask(with: req)
            ws.resume()
            if case .string(let s)? = try? await ws.receive(),
               let m = try? JSONSerialization.jsonObject(with: Data(s.utf8)) as? [String: Any], m["t"] as? String == "status" {
                let phone = (m["phone"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "your phone"
                return (ws, m["connected"] as? Bool == true ? phone : nil)
            }
            ws.cancel()
            if attempt == 0, let app = NSWorkspace.shared.urlForApplication(withBundleIdentifier: "app.macndroid.mac") {
                heading.stringValue = "Opening Mac n Droid…"
                let config = NSWorkspace.OpenConfiguration()
                config.activates = false
                _ = try? await NSWorkspace.shared.openApplication(at: app, configuration: config)
            }
            try? await Task.sleep(for: .milliseconds(500))
        }
        return nil
    }

    /// What was shared: files (Finder, Photos, Preview…), a web link (Safari), or text.
    private func collect() async -> [Item] {
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        var out: [Item] = []
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        for p in providers {
            if p.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier),
               let url = await loadURL(p, UTType.fileURL), url.isFileURL {
                out.append(.file(url, name: url.lastPathComponent))
            } else if let type = [UTType.image, .movie, .pdf, .data].first(where: { p.hasItemConformingToTypeIdentifier($0.identifier) }),
                      !p.hasItemConformingToTypeIdentifier(UTType.url.identifier) || type != .data,
                      let url = await copyFile(p, type, into: tmp) {
                out.append(.file(url, name: url.lastPathComponent))
            } else if p.hasItemConformingToTypeIdentifier(UTType.url.identifier), let url = await loadURL(p, UTType.url) {
                if url.isFileURL { out.append(.file(url, name: url.lastPathComponent)) }
                else if ["http", "https"].contains(url.scheme?.lowercased() ?? "") { out.append(.link(url)) }
            } else if p.hasItemConformingToTypeIdentifier(UTType.plainText.identifier), let text = await loadText(p) {
                if let url = URL(string: text.trimmingCharacters(in: .whitespacesAndNewlines)),
                   ["http", "https"].contains(url.scheme?.lowercased() ?? "") { out.append(.link(url)) }
                else { out.append(.text(text)) }
            }
        }
        return out
    }

    private func loadURL(_ p: NSItemProvider, _ type: UTType) async -> URL? {
        await withCheckedContinuation { c in
            p.loadItem(forTypeIdentifier: type.identifier) { item, _ in
                switch item {
                case let u as URL: c.resume(returning: u)
                case let d as Data: c.resume(returning: URL(dataRepresentation: d, relativeTo: nil))
                case let s as String: c.resume(returning: URL(string: s))
                default: c.resume(returning: nil)
                }
            }
        }
    }

    private func loadText(_ p: NSItemProvider) async -> String? {
        await withCheckedContinuation { c in
            p.loadItem(forTypeIdentifier: UTType.plainText.identifier) { item, _ in
                switch item {
                case let s as String: c.resume(returning: s)
                case let s as NSAttributedString: c.resume(returning: s.string)
                case let d as Data: c.resume(returning: String(data: d, encoding: .utf8))
                default: c.resume(returning: nil)
                }
            }
        }
    }

    /// Photos and other apps hand over a temporary file that's deleted when the callback returns: keep a copy.
    private func copyFile(_ p: NSItemProvider, _ type: UTType, into dir: URL) async -> URL? {
        let suggested = p.suggestedName
        return await withCheckedContinuation { c in
            _ = p.loadFileRepresentation(forTypeIdentifier: type.identifier) { url, _ in
                guard let url else { return c.resume(returning: nil) }
                try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                let dest = dir.appendingPathComponent(suggested.map { name in
                    url.pathExtension.isEmpty || name.hasSuffix("." + url.pathExtension) ? name : name + "." + url.pathExtension
                } ?? url.lastPathComponent)
                c.resume(returning: (try? FileManager.default.copyItem(at: url, to: dest)) != nil ? dest : nil)
            }
        }
    }

    // MARK: Result

    private func done(_ text: String) {
        progress.stopAnimation(nil)
        progress.isIndeterminate = false
        progress.doubleValue = 1
        heading.stringValue = text
        detail.stringValue = ""
        button.title = "Done"
        button.keyEquivalent = "\r"
        // Close by itself, like AirDrop.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) { [weak self] in
            self?.extensionContext?.completeRequest(returningItems: nil)
        }
    }

    private func fail(_ text: String) {
        progress.stopAnimation(nil)
        progress.isHidden = true
        heading.stringValue = "Couldn't send"
        detail.stringValue = text
        detail.lineBreakMode = .byWordWrapping
        detail.maximumNumberOfLines = 2
        button.title = "Close"
    }
}
