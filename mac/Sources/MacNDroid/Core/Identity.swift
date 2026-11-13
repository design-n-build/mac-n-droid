import AppKit
import CoreImage.CIFilterBuiltins
import Foundation

/// The phone this Mac is paired with.
struct PairedPhone: Codable, Equatable {
    var id: String
    var name: String
    /// Its own key (nil: an older app that still uses the invite key).
    var key: Data?
    /// The phone said it saved `key`; until then the invite key stays valid for it.
    var confirmed: Bool
}

/// This Mac's identity and pairing secrets.
struct Identity: Codable {
    var macId: String
    /// The invite key in the QR code. Replaced once a phone has paired with it, so the code works once.
    var key: Data
    var phone: PairedPhone?

    private static var url: URL {
        // MACNDROID_CONFIG: a separate folder, for tests (as in the Linux app).
        let dir = ProcessInfo.processInfo.environment["MACNDROID_CONFIG"].map { URL(fileURLWithPath: $0, isDirectory: true) }
            ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("MacNDroid", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("identity.json")
    }

    static func load() -> Identity {
        if let data = try? Data(contentsOf: url),
           let id = try? JSONDecoder().decode(Identity.self, from: data) {
            return id
        }
        let id = Identity(macId: UUID().uuidString.lowercased(), key: .random(32), phone: nil)
        id.save()
        return id
    }

    static func reset() -> Identity {
        var id = load()
        id.key = .random(32)
        id.phone = nil
        id.save()
        return id
    }

    func save() {
        guard let data = try? JSONEncoder().encode(self) else { return }
        try? data.write(to: Self.url, options: .atomic)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: Self.url.path)
    }

    func pairingURI(port: UInt16, hosts: [String], name: String) -> String {
        var c = URLComponents()
        c.scheme = "macndroid"
        c.host = "pair"
        c.queryItems = [
            .init(name: "v", value: "1"),
            .init(name: "id", value: macId),
            .init(name: "k", value: key.base64URL),
            .init(name: "p", value: String(port)),
            .init(name: "h", value: hosts.joined(separator: ",")),
            .init(name: "n", value: name),
        ]
        return c.string ?? ""
    }
}

enum Net {
    /// IPv4 addresses of active, non-loopback interfaces (Wi-Fi / Ethernet first).
    static func localIPv4() -> [String] {
        var result: [(String, String)] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return [] }
        defer { freeifaddrs(ifaddr) }
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let ifa = ptr.pointee
            let flags = Int32(ifa.ifa_flags)
            guard let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET),
                  flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0 else { continue }
            let name = String(cString: ifa.ifa_name)
            guard name.hasPrefix("en") || name.hasPrefix("bridge") else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count),
                           nil, 0, NI_NUMERICHOST) == 0 {
                result.append((name, String(cString: host)))
            }
        }
        return result.sorted { $0.0 < $1.0 }.map(\.1)
    }

    static var computerName: String {
        #if DEBUG
        if let demoComputerName { return demoComputerName }
        #endif
        return Host.current().localizedName ?? "Mac"
    }
    #if DEBUG
    /// Store screenshots show a sample name instead of this Mac's.
    static var demoComputerName: String?
    #endif
}

enum QRCode {
    static func image(for string: String, size: CGFloat = 512) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scale = size / output.extent.width
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let rep = NSCIImageRep(ciImage: scaled)
        let img = NSImage(size: rep.size)
        img.addRepresentation(rep)
        return img
    }
}
