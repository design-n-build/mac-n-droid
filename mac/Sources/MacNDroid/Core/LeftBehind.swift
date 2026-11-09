import AppKit

/// "You left your phone behind."
///
/// The Mac can't measure distance to the phone, so it uses the network instead. When the phone
/// stops answering *and* this Mac is now on a different network (a different router, or none)
/// than when they were last connected, the Mac has moved and the phone hasn't. Usually the Mac
/// was asleep in a bag, so the check runs shortly after it wakes.
///
/// It stays quiet when the Mac simply has no network (Wi-Fi turned off, router restarting), and when
/// the phone comes back on the same network.
///
/// The opposite case (walking away from an awake Mac with the phone) is the phone's job: it hears
/// its own Wi-Fi drop. The Mac tells the phone before it sleeps, so closing the lid stays quiet.
final class LeftBehindMonitor {
    var isConnected: () -> Bool = { false }
    var phoneName: () -> String = { "your phone" }

    private let queue = DispatchQueue(label: "app.macndroid.leftbehind")
    private var networkWhenLinked: String?
    private var connectedAtSleep = false
    private var check: DispatchWorkItem?
    private var alerted = false

    /// Phone connected: remember which network we're on.
    func linked() {
        check?.cancel()
        alerted = false
        queue.async {
            let sig = Self.networkSignature()
            DispatchQueue.main.async { self.networkWhenLinked = sig }
        }
    }

    /// Phone stopped answering while the Mac is awake.
    func lost() { schedule(after: 45) }

    func willSleep() {
        connectedAtSleep = isConnected()
        check?.cancel()
    }

    func didWake() {
        // Give Wi-Fi and the phone time to reconnect after waking.
        if connectedAtSleep { schedule(after: 60) }
        connectedAtSleep = false
    }

    private func schedule(after seconds: TimeInterval) {
        check?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.run() }
        check = work
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: work)
    }

    private func run() {
        guard Prefs.bool(Prefs.leftBehind), !alerted, !isConnected(), let before = networkWhenLinked else { return }
        queue.async {
            let now = Self.networkSignature()
            DispatchQueue.main.async {
                // "none" = Wi-Fi off or the router is down: not evidence that the Mac moved.
                guard now != "none", now != before, !self.isConnected(), !self.alerted else { return }
                self.alerted = true
                self.alert()
            }
        }
    }

    private func alert() {
        let name = phoneName()
        AppState.shared.postLeftBehindAlert(
            title: "You left \(name) behind",
            body: "This Mac moved to a different network and can't reach \(name) any more.")
        if let url = Bundle.main.url(forResource: "mnd_left_behind", withExtension: "wav"),
           let sound = NSSound(contentsOf: url, byReference: true) {
            sound.volume = 0.9
            sound.play()
        }
    }

    /// Identifies the current network by its router's hardware address (the IP alone is often the
    /// same 192.168.1.1 everywhere). Reading the Wi-Fi name would need Location access.
    static func networkSignature() -> String {
        let route = run("/sbin/route", ["-n", "get", "default"])
        func field(_ name: String) -> String? {
            route.split(separator: "\n").lazy
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .first { $0.hasPrefix(name + ":") }
                .map { String($0.dropFirst(name.count + 1)).trimmingCharacters(in: .whitespaces) }
        }
        guard let gateway = field("gateway") else { return "none" }
        let iface = field("interface") ?? ""
        let arp = run("/usr/sbin/arp", ["-n", gateway])
        let mac = arp.split(separator: " ").first { $0.split(separator: ":").count == 6 }.map(String.init) ?? ""
        return "\(iface)|\(gateway)|\(mac)"
    }

    private static func run(_ path: String, _ args: [String]) -> String {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: path)
        p.arguments = args
        let out = Pipe()
        p.standardOutput = out
        p.standardError = FileHandle.nullDevice
        guard (try? p.run()) != nil else { return "" }
        let data = out.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        return String(decoding: data, as: UTF8.self)
    }
}
