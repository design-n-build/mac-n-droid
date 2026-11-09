import Foundation
import Network

/// Connection to the Mac n Droid browser extension (Helium, Chrome, Edge, Brave, Arc…).
///
/// The extension reports music and videos playing in browser tabs and asks to send pages to the
/// phone; the app sends back play/pause/skip commands. It listens only on 127.0.0.1 (this Mac) and
/// accepts only browser extensions, never web pages.
final class BrowserBridge {
    static let port: UInt16 = 47809

    struct Track {
        let key: String       // "connection:tab"
        let tab: Int
        let site: String
        let title: String
        let artist: String
        let album: String
        let playing: Bool
        let duration: Double
        let position: Double
        let canNext: Bool
    }

    var onMedia: ((String, Track?) -> Void)?
    var onArt: ((String, Data) -> Void)?
    var onSendURL: ((URL) -> Void)?
    private var status: [String: Any] = ["t": "status", "connected": false, "phone": ""]

    private let queue = DispatchQueue(label: "app.macndroid.browser")
    private var listener: NWListener?
    private var connections: [ObjectIdentifier: NWConnection] = [:]

    func start() {
        let ws = NWProtocolWebSocket.Options()
        ws.autoReplyPing = true
        ws.maximumMessageSize = 4 * 1024 * 1024
        // Only extensions: web pages send an http(s) Origin and can't fake it.
        ws.setClientRequestHandler(queue) { _, headers in
            let origin = headers.first { $0.name.lowercased() == "origin" }?.value ?? ""
            let ok = ["chrome-extension://", "moz-extension://", "safari-web-extension://"].contains { origin.hasPrefix($0) }
            return NWProtocolWebSocket.Response(status: ok ? .accept : .reject, subprotocol: nil)
        }
        let params = NWParameters.tcp
        params.defaultProtocolStack.applicationProtocols.insert(ws, at: 0)
        params.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: Self.port)!)
        params.allowLocalEndpointReuse = true
        guard let l = try? NWListener(using: params) else { return }
        l.newConnectionHandler = { [weak self] c in self?.accept(c) }
        l.stateUpdateHandler = { [weak self] state in
            if case .failed = state {
                // Port busy (another copy running) or the network stack reset: try again shortly.
                self?.listener?.cancel()
                self?.queue.asyncAfter(deadline: .now() + 10) { self?.start() }
            }
        }
        listener = l
        l.start(queue: queue)
    }

    /// Phone connected or not, and its name, for the extension's menu and popup.
    func setStatus(connected: Bool, phone: String) {
        queue.async {
            self.status = ["t": "status", "connected": connected, "phone": phone]
            for c in self.connections.values { self.send(self.status, to: c) }
        }
    }

    func command(_ cmd: String, key: String) {
        let parts = key.split(separator: ":")
        guard parts.count == 2, let tab = Int(parts[1]) else { return }
        queue.async {
            guard let c = self.connections.first(where: { "\($0.key.hashValue)" == parts[0] })?.value else { return }
            self.send(["t": "cmd", "tab": tab, "cmd": cmd], to: c)
        }
    }

    private func accept(_ c: NWConnection) {
        let id = ObjectIdentifier(c)
        c.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.send(self.status, to: c)
            case .failed, .cancelled:
                self.connections[id] = nil
                let prefix = "\(id.hashValue):"
                DispatchQueue.main.async { self.onMedia?(prefix, nil) }   // all of this browser's tabs
            default: break
            }
        }
        connections[id] = c
        c.start(queue: queue)
        receive(c)
    }

    private func receive(_ c: NWConnection) {
        c.receiveMessage { [weak self] data, _, _, error in
            guard let self else { return }
            if let data, let m = try? JSONSerialization.jsonObject(with: data) as? [String: Any] { self.handle(m, from: c) }
            if error == nil { self.receive(c) } else { c.cancel() }
        }
    }

    private func handle(_ m: [String: Any], from c: NWConnection) {
        let key = "\(ObjectIdentifier(c).hashValue):\(m["tab"] as? Int ?? -1)"
        switch m["t"] as? String {
        case "media":
            guard let tab = m["tab"] as? Int, let title = m["title"] as? String, !title.isEmpty else { return }
            let t = Track(key: key, tab: tab, site: m["site"] as? String ?? "Browser", title: title,
                          artist: m["artist"] as? String ?? "", album: m["album"] as? String ?? "",
                          playing: m["playing"] as? Bool ?? false,
                          duration: (m["dur"] as? NSNumber)?.doubleValue ?? 0,
                          position: (m["pos"] as? NSNumber)?.doubleValue ?? 0,
                          canNext: m["canNext"] as? Bool ?? false)
            let art = (m["art"] as? String).flatMap { Data(base64Encoded: $0) }.flatMap { MacMediaMonitor.jpeg($0) }
            DispatchQueue.main.async {
                self.onMedia?(key, t)
                if let art { self.onArt?(key, art) }
            }
        case "media_end":
            DispatchQueue.main.async { self.onMedia?(key, nil) }
        case "send_url":
            if let s = m["url"] as? String, let url = URL(string: s) {
                DispatchQueue.main.async { self.onSendURL?(url) }
            }
        default:
            break
        }
    }

    private func send(_ m: [String: Any], to c: NWConnection) {
        guard let data = try? JSONSerialization.data(withJSONObject: m) else { return }
        let meta = NWProtocolWebSocket.Metadata(opcode: .text)
        let ctx = NWConnection.ContentContext(identifier: "msg", metadata: [meta])
        c.send(content: data, contentContext: ctx, isComplete: true, completion: .contentProcessed { _ in })
    }
}
