import CryptoKit
import Foundation
import Network

typealias Message = [String: Any]

/// One encrypted session with a phone. All work happens on `queue`.
final class PhoneConnection {
    let id = UUID()
    private let conn: NWConnection
    private let queue: DispatchQueue
    private let identity: () -> Identity
    private var buffer = Data()
    private var cipher: SessionCipher?
    /// Admitted: the phone's messages are handled and it can be sent anything.
    private var authenticated = false
    /// The `device` frame decrypted: waiting to be admitted (or for approval of a new phone).
    private var keyed = false
    private var closed = false
    private var handshakeTimer: DispatchWorkItem?

    /// From the phone's hello.
    private(set) var phoneId = ""
    /// It connected with its own device key (not the QR's invite key).
    private(set) var usedDeviceKey = false
    /// An older app: no device keys, no approval screen.
    private(set) var isLegacy = true
    /// Keeps the session alive from accept until close (nothing else owns it during the handshake).
    private var selfRetain: PhoneConnection?

    /// The phone proved it has a key; decide whether to `admit` it, ask for approval, or `deny` it.
    var onDevice: ((Message) -> Void)?
    var onAuthenticated: ((Message) -> Void)?
    var onMessage: ((Message) -> Void)?
    var onClose: ((_ reason: String?) -> Void)?

    private static let maxFrame = 8 * 1024 * 1024

    init(_ conn: NWConnection, identity: @escaping () -> Identity, queue: DispatchQueue) {
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
        armTimeout(10)
    }

    private func armTimeout(_ seconds: TimeInterval) {
        handshakeTimer?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, !self.authenticated else { return }
            self.close("Handshake timed out")
        }
        handshakeTimer = work
        queue.asyncAfter(deadline: .now() + seconds, execute: work)
    }

    /// Let it in: `welcome` (with its new device key, if any), then normal messages.
    func admit(_ device: Message, deviceKey: Data?) {
        queue.async {
            guard !self.closed, self.keyed, !self.authenticated else { return }
            self.authenticated = true
            self.handshakeTimer?.cancel()
            var welcome: Message = ["t": "welcome", "name": Net.computerName]
            if let deviceKey { welcome["deviceKey"] = deviceKey.base64EncodedString() }
            self.sendSealed(welcome)
            self.onAuthenticated?(device)
        }
    }

    /// A new phone waiting for approval: tell it, and give it time.
    func awaitApproval(by paired: String, seconds: TimeInterval) {
        queue.async {
            guard !self.closed, self.keyed else { return }
            self.armTimeout(seconds + 5)
            self.sendSealed(["t": "approval", "by": paired])
        }
    }

    /// Turn it away (with a message it can show) and hang up.
    func deny(_ reason: String) {
        queue.async {
            guard !self.closed else { return }
            if self.keyed { self.sendSealed(["t": "denied"]) }
            self.queue.asyncAfter(deadline: .now() + 0.3) { self.close(reason) }
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
            let id = identity()
            phoneId = msg["phoneId"] as? String ?? ""
            let use = msg["use"] as? String
            isLegacy = use == nil
            // Which key it holds: its own device key (must be the paired phone), or the QR's invite key.
            let base: Data
            if use == "device" {
                guard let p = id.phone, p.id == phoneId, let k = p.key else {
                    return close("A phone that's no longer paired tried to connect.")
                }
                base = k
                usedDeviceKey = true
            } else {
                base = id.key
            }
            let macNonce = Data.random(16)
            var reply: Message = [
                "t": "hello", "v": 1, "macId": id.macId,
                "nonce": macNonce.base64EncodedString(), "name": Net.computerName,
            ]
            if let ephStr = msg["eph"] as? String, let phoneEph = Data(base64Encoded: ephStr),
               let peer = try? P256.KeyAgreement.PublicKey(x963Representation: phoneEph) {
                // Forward secrecy: a one-time key exchange mixed into the session key.
                let mine = P256.KeyAgreement.PrivateKey()
                guard let secret = try? mine.sharedSecretFromKeyAgreement(with: peer) else { return close("Bad key exchange") }
                let shared = secret.withUnsafeBytes { Data($0) }
                let macEph = mine.publicKey.x963Representation
                cipher = SessionCipher(pairingKey: base, phoneNonce: phoneNonce, macNonce: macNonce,
                                       phoneEph: phoneEph, macEph: macEph, shared: shared)
                reply["v"] = 2
                reply["eph"] = macEph.base64EncodedString()
            } else {
                cipher = SessionCipher(pairingKey: base, phoneNonce: phoneNonce, macNonce: macNonce)
            }
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
            // Before it's admitted, only its device info counts (anything else waits or is dropped).
            guard !keyed else { return }
            guard msg["t"] as? String == "device" else { return close("Expected device info") }
            keyed = true
            onDevice?(msg)
            return
        }
        onMessage?(msg)
    }

    private func sendSealed(_ msg: Message) {
        guard let data = try? JSONSerialization.data(withJSONObject: msg),
              let sealed = try? cipher?.seal(data) else { return }
        write(sealed)
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
    private let lock = NSLock()
    private var _identity: Identity
    /// Read on the network queue, changed on the main thread.
    var identity: Identity {
        get { lock.lock(); defer { lock.unlock() }; return _identity }
        set { lock.lock(); _identity = newValue; lock.unlock() }
    }

    var onReady: ((UInt16) -> Void)?
    var onFailed: ((String) -> Void)?
    var onNewConnection: ((PhoneConnection) -> Void)?

    init(identity: Identity) { _identity = identity }

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
            let pc = PhoneConnection(nw, identity: { [weak self] in self?.identity ?? Identity.load() }, queue: self.queue)
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
