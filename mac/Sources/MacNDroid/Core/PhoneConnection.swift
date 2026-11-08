import Foundation
import Network

typealias Message = [String: Any]

/// One encrypted session with a phone. All work happens on `queue`.
final class PhoneConnection {
    let id = UUID()
    private let conn: NWConnection
    private let queue: DispatchQueue
    private let identity: Identity
    private var buffer = Data()
    private var cipher: SessionCipher?
    private var authenticated = false
    private var closed = false
    /// Keeps the session alive from accept until close (nothing else owns it during the handshake).
    private var selfRetain: PhoneConnection?

    var onAuthenticated: ((Message) -> Void)?
    var onMessage: ((Message) -> Void)?
    var onClose: ((_ reason: String?) -> Void)?

    private static let maxFrame = 8 * 1024 * 1024

    init(_ conn: NWConnection, identity: Identity, queue: DispatchQueue) {
        self.conn = conn
        self.identity = identity
        self.queue = queue
    }

    func start() {
        selfRetain = self
        conn.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed(let e): self?.close("Connection failed: \(e.localizedDescription)")
            case .cancelled: self?.close(nil)
            default: break
            }
        }
        conn.start(queue: queue)
        receive()
        // A phone that never completes the handshake is dropped.
        queue.asyncAfter(deadline: .now() + 10) { [weak self] in
            guard let self, !self.authenticated else { return }
            self.close("Handshake timed out")
        }
    }

    func cancel() { queue.async { self.close(nil) } }

    private func close(_ reason: String?) {
        guard !closed else { return }
        closed = true
        NSLog("MacNDroid: connection closed (authenticated=%d): %@", authenticated ? 1 : 0, reason ?? "cancelled")
        conn.cancel()
        onClose?(reason)
        selfRetain = nil
    }

    private func receive() {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 1 << 20) { [weak self] data, _, complete, error in
            guard let self, !self.closed else { return }
            if let data, !data.isEmpty {
                self.buffer.append(data)
                self.drain()
            }
            if complete || error != nil {
                self.close(error.map { "Disconnected: \($0.localizedDescription)" })
            } else {
                self.receive()
            }
        }
    }

    private func drain() {
        while buffer.count >= 4, !closed {
            let s = buffer.startIndex
            let len = buffer[s..<s + 4].reduce(0) { ($0 << 8) | Int($1) }
            guard len <= Self.maxFrame else { return close("Frame too large") }
            guard buffer.count >= 4 + len else { return }
            let payload = Data(buffer[(s + 4)..<(s + 4 + len)])
            buffer = Data(buffer[(s + 4 + len)...])
            handle(payload)
        }
    }

    private func handle(_ payload: Data) {
        if cipher == nil {
            guard let msg = Self.decode(payload), msg["t"] as? String == "hello",
                  let nonceStr = msg["nonce"] as? String, let phoneNonce = Data(base64Encoded: nonceStr),
                  phoneNonce.count == 16 else { return close("Bad hello") }
            let macNonce = Data.random(16)
            cipher = SessionCipher(pairingKey: identity.key, phoneNonce: phoneNonce, macNonce: macNonce)
            let reply: Message = [
                "t": "hello", "v": 1, "macId": identity.macId,
                "nonce": macNonce.base64EncodedString(), "name": Net.computerName,
            ]
            if let data = try? JSONSerialization.data(withJSONObject: reply) { write(data) }
            return
        }
        guard let plain = try? cipher!.open(payload) else {
            return close(authenticated ? "Decryption failed (\(payload.count) bytes)" : "A phone with an old pairing code tried to connect. Pair it again.")
        }
        guard let msg = Self.decode(plain) else {
            NSLog("MacNDroid: undecodable JSON: %@", String(decoding: plain.prefix(300), as: UTF8.self))
            return close(authenticated ? "Decryption failed" : "A phone with an old pairing code tried to connect. Pair it again.")
        }
        if !authenticated {
            guard msg["t"] as? String == "device" else { return close("Expected device info") }
            authenticated = true
            onAuthenticated?(msg)
            send(["t": "welcome", "name": Net.computerName])
            return
        }
        onMessage?(msg)
    }

    /// Thread-safe. `completion` fires on the connection queue once the bytes are handed to the network stack.
    func send(_ msg: Message, completion: ((Bool) -> Void)? = nil) {
        queue.async {
            guard !self.closed, self.authenticated, self.cipher != nil,
                  let data = try? JSONSerialization.data(withJSONObject: msg),
                  let sealed = try? self.cipher!.seal(data) else {
                completion?(false)
                return
            }
            self.write(sealed, completion: completion)
        }
    }

    private func write(_ payload: Data, completion: ((Bool) -> Void)? = nil) {
        var frame = Data(capacity: payload.count + 4)
        withUnsafeBytes(of: UInt32(payload.count).bigEndian) { frame.append(contentsOf: $0) }
        frame.append(payload)
        conn.send(content: frame, completion: .contentProcessed { error in completion?(error == nil) })
    }

    private static func decode(_ data: Data) -> Message? {
        (try? JSONSerialization.jsonObject(with: data)) as? Message
    }
}

/// TCP listener + Bonjour advertisement. Keeps a single active phone session.
final class Server {
    let queue = DispatchQueue(label: "app.macndroid.server")
    private var listener: NWListener?
    private(set) var port: UInt16 = 0
    var identity: Identity

    var onReady: ((UInt16) -> Void)?
    var onFailed: ((String) -> Void)?
    var onNewConnection: ((PhoneConnection) -> Void)?

    init(identity: Identity) { self.identity = identity }

    func start(preferredPort: UInt16 = 47800) {
        let params = NWParameters.tcp
        params.allowLocalEndpointReuse = true
        if let tcp = params.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
            tcp.enableKeepalive = true
            tcp.keepaliveIdle = 15
            tcp.noDelay = true
        }
        let listener: NWListener
        do {
            listener = try NWListener(using: params, on: NWEndpoint.Port(rawValue: preferredPort)!)
        } catch {
            guard let any = try? NWListener(using: params) else {
                onFailed?("Couldn't open a network port: \(error.localizedDescription)")
                return
            }
            listener = any
        }
        let txt = NWTXTRecord(["id": identity.macId])
        listener.service = NWListener.Service(name: Net.computerName, type: "_macndroid._tcp", txtRecord: txt)
        listener.stateUpdateHandler = { [weak self, weak listener] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.port = listener?.port?.rawValue ?? 0
                self.onReady?(self.port)
            case .failed(let e):
                if preferredPort != 0 {
                    listener?.cancel()
                    self.start(preferredPort: 0)
                } else {
                    self.onFailed?(e.localizedDescription)
                }
            default: break
            }
        }
        listener.newConnectionHandler = { [weak self] nw in
            guard let self else { return }
            let pc = PhoneConnection(nw, identity: self.identity, queue: self.queue)
            self.onNewConnection?(pc)
            pc.start()
        }
        listener.start(queue: queue)
        self.listener = listener
    }

    func restart() {
        listener?.cancel()
        listener = nil
        start()
    }
}
