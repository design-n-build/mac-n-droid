import AppKit
import Carbon
import Combine
import Foundation
import ServiceManagement

/// Single source of truth for the UI. Mutated on the main thread only.
final class AppState: ObservableObject {
    static let shared = AppState()

    enum Status: Equatable {
        case starting
        case waiting
        case connected
        case error(String)
    }

    @Published private(set) var status: Status = .starting
    @Published private(set) var phone: PhoneInfo?
    @Published private(set) var battery: Int?
    @Published private(set) var charging = false
    @Published private(set) var notifications: [PhoneNotification] = []
    @Published private(set) var icons: [String: NSImage] = [:]
    @Published private(set) var transfers: [Transfer] = []
    @Published private(set) var pairingURI = ""
    @Published private(set) var addresses: [String] = []
    @Published private(set) var port: UInt16 = 0
    @Published private(set) var lastError: String?
    @Published private(set) var lastPhoneName: String? = UserDefaults.standard.string(forKey: Prefs.lastPhoneName)
    @Published var isRinging = false

    enum MirrorPhase { case idle, waiting, live, denied, stopped }
    @Published private(set) var mirror: MirrorPhase = .idle
    @Published private(set) var mirrorSize: CGSize = .zero
    @Published private(set) var controlUnavailable = false
    let mirrorRenderer = MirrorRenderer()
    private let mirrorQueue = DispatchQueue(label: "app.macndroid.mirror", qos: .userInteractive)

    @Published private(set) var live: [LiveActivity] = []
    /// Every media app on the phone: playing ones first (most recently started on top), then paused.
    @Published private(set) var players: [NowPlaying] = []
    @Published private(set) var playerArt: [String: NSImage] = [:]
    /// The phone's media volume, 0…100 (nil until the phone reports it; older phones don't).
    @Published private(set) var phoneVolume: Int?
    var nowPlaying: NowPlaying? { players.first }
    var mediaArt: NSImage? { nowPlaying.flatMap { playerArt[$0.id] } }

    @Published private(set) var clipHistory: [ClipItem] = []
    private let clipStore = ClipHistoryStore()
    private var lastNotifAt = Date.distantPast
    private var burstCount = 0
    private var clipPushWork: DispatchWorkItem?

    @Published private(set) var smsState: SmsAvailability = .unknown
    @Published private(set) var smsThreads: [SmsThread] = []
    @Published private(set) var smsMessages: [String: [SmsMessage]] = [:]
    @Published private(set) var contactResults: [PhoneContact] = []
    @Published private(set) var contactsAllowed = true
    private var contactQuery = ""
    /// After a new message, select its conversation once it shows up.
    @Published var smsSelectAddress: String?
    /// The conversation open in Messages, refreshed when texts arrive.
    var openSmsThread: String?

    /// The phone's photos and videos (Photos pane).
    let gallery = GalleryModel()
    /// File transfers that are photos opened from the Photos pane: transfer id → gallery id.
    private var galleryIncoming: [String: String] = [:]
    private let macMedia = MacMediaMonitor()
    let browserBridge = BrowserBridge()
    private var phoneWantsMacMedia = false
    private var macMediaArt: (track: String, jpeg: Data)?

    @Published private(set) var knownApps: [String: KnownApp] = AppState.loadKnownApps()
    @Published private(set) var mutedApps: Set<String> = Set(UserDefaults.standard.stringArray(forKey: Prefs.mutedApps) ?? [])
    /// Injected like `openMainWindow`.
    var openMirrorWindow: (() -> Void)?
    /// Set to jump the main window to a pane (e.g. clicking a notch notification).
    @Published var requestedPane: Pane?
    /// Injected by a always-alive SwiftUI view so non-view code can open the main window.
    var openMainWindow: (() -> Void)?

    var isConnected: Bool { status == .connected }
    var hasPairedBefore: Bool { lastPhoneName != nil }

    private var identity = Identity.load()
    private lazy var server = Server(identity: identity)
    private var active: PhoneConnection?
    private let notifier = Notifier()
    private let leftBehind = LeftBehindMonitor()
    private let clipboard = ClipboardMonitor()
    /// `folder`: the name of a folder that arrives zipped and is unpacked when complete.
    private var incoming: [String: (handle: FileHandle, url: URL, isClip: Bool, folder: String?)] = [:]
    private let fileQueue = DispatchQueue(label: "app.macndroid.files")
    private var pingTimer: Timer?
    private var started = false

    // MARK: Lifecycle

    func start() {
        guard !started else { return }
        started = true
        Prefs.registerDefaults()
        notifier.setUp()
        notifier.onAction = { [weak self] key, i, text in self?.performAction(key: key, index: i, text: text) }
        notifier.onDismiss = { [weak self] key in self?.dismissOnPhone(key) }
        notifier.onCopyOTP = { [weak self] code in self?.copyToMac(code) }
        notifier.onReveal = { url in NSWorkspace.shared.activateFileViewerSelecting([url]) }

        clipboard.onChange = { [weak self] text in
            guard let self, Prefs.bool(Prefs.syncClipboard) else { return }
            self.active?.send(["t": "clip", "text": text, "explicit": false])
            self.recordClip { $0.addText(text, fromPhone: false) }
        }
        clipboard.onImage = { [weak self] png in
            guard let self, Prefs.bool(Prefs.syncClipboard) else { return }
            self.sendClipboardImage(png)
            self.recordClip { $0.addImage(png, fromPhone: false) }
        }
        clipHistory = clipStore.items
        Handoff.start()
        // ⌃⌥P from inside any browser: open this page on the phone.
        GlobalHotKey.register(id: 1, keyCode: kVK_ANSI_P, modifiers: controlKey | optionKey) { [weak self] in
            guard let self, self.isConnected else { return }
            self.sendBrowserTab()
        }
        macMedia.onChange = { [weak self] _ in self?.pushMacMedia(art: false) }
        SystemVolume.shared.onChange = { [weak self] in self?.pushMacVolume() }
        macMedia.onArt = { [weak self] jpeg in
            guard let self, let id = self.macMedia.current?.id else { return }
            self.macMediaArt = (id, jpeg)
            self.pushMacMedia(art: true)
        }
        macMedia.start()
        browserBridge.onMedia = { [weak self] key, t in self?.macMedia.setBrowser(key, t) }
        browserBridge.onArt = { [weak self] key, jpeg in self?.macMedia.setBrowserArt(key, jpeg) }
        browserBridge.onSendURL = { [weak self] url in self?.sendLink(url) }
        browserBridge.onSendFiles = { [weak self] urls in self?.sendFiles(urls) }
        browserBridge.onSendText = { [weak self] text in
            guard let self, let conn = self.active else { return }
            conn.send(["t": "clip", "text": text, "explicit": true])
            if NotchStyle.isOn { NotchController.shared.flash(symbol: "doc.on.clipboard.fill", text: "Copied to phone") }
        }
        macMedia.browserCommand = { [weak self] cmd, key in self?.browserBridge.command(cmd, key: key) }
        browserBridge.start()
        BrowserExtension.install()
        NotchController.shared.state = self
        loadSavedIcons()
        LiveBarController.shared.state = self
        NotchDropController.shared.state = self
        NotchDropController.shared.start()
        clipboard.start()

        server.onReady = { [weak self] port in DispatchQueue.main.async { self?.listenerReady(port) } }
        server.onFailed = { [weak self] err in DispatchQueue.main.async { self?.status = .error(err) } }
        server.onNewConnection = { [weak self] pc in self?.wire(pc) }
        server.start()

        pingTimer = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { [weak self] _ in
            self?.active?.send(["t": "ping"])
            self?.refreshAddresses()
        }
        leftBehind.isConnected = { [weak self] in self?.isConnected ?? false }
        leftBehind.phoneName = { [weak self] in self?.phone?.name ?? self?.lastPhoneName ?? "your phone" }
        let ws = NSWorkspace.shared.notificationCenter
        ws.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in
            self?.refreshAddresses()
            self?.leftBehind.didWake()
        }
        // Tell the phone first, so closing the lid and walking off with the phone doesn't alarm it.
        ws.addObserver(forName: NSWorkspace.willSleepNotification, object: nil, queue: .main) { [weak self] _ in
            self?.active?.send(["t": "bye", "reason": "sleep"])
            self?.leftBehind.willSleep()
        }
        NotificationCenter.default.addObserver(forName: NSApplication.willTerminateNotification, object: nil, queue: .main) { [weak self] _ in
            self?.active?.send(["t": "bye", "reason": "quit"])
        }
    }

    func postLeftBehindAlert(title: String, body: String) {
        notifier.postLocal(title: title, body: body)
    }

    private func listenerReady(_ port: UInt16) {
        self.port = port
        if status != .connected { status = .waiting }
        refreshAddresses(force: true)
    }

    private func refreshAddresses(force: Bool = false) {
        let ips = Net.localIPv4()
        guard force || ips != addresses else { return }
        addresses = ips
        pairingURI = identity.pairingURI(port: port, hosts: ips, name: Net.computerName)
    }

    func resetPairing() {
        identity = Identity.reset()
        server.identity = identity
        active?.cancel()
        UserDefaults.standard.removeObject(forKey: Prefs.lastPhoneName)
        lastPhoneName = nil
        refreshAddresses(force: true)
    }

    // MARK: Connection

    private func wire(_ pc: PhoneConnection) {
        pc.onAuthenticated = { [weak self, weak pc] msg in
            DispatchQueue.main.async {
                guard let self, let pc else { return }
                if let old = self.active, old.id != pc.id { old.cancel() }
                self.active = pc
                self.phoneConnected(msg)
            }
        }
        pc.onMessage = { [weak self] msg in
            if let t = msg["t"] as? String, t == "mirror_cfg" || t == "mirror_frame" {
                self?.mirrorQueue.async { self?.handleMirrorMessage(t, msg) }
                return
            }
            if let t = msg["t"] as? String, t.hasPrefix("file_") {
                // Ordered, off-main file I/O.
                self?.fileQueue.async { self?.handleFileMessage(t, msg) }
                return
            }
            DispatchQueue.main.async { self?.handle(msg) }
        }
        pc.onClose = { [weak self, weak pc] reason in
            DispatchQueue.main.async {
                guard let self, let pc else { return }
                if self.active?.id == pc.id {
                    self.active = nil
                    self.phoneDisconnected()
                }
                if let reason, reason.contains("Pair it again") { self.lastError = reason }
            }
        }
    }

    private func phoneConnected(_ msg: Message) {
        let info = PhoneInfo(
            name: msg["name"] as? String ?? "Android phone",
            model: msg["model"] as? String ?? "",
            os: msg["os"] as? String ?? "")
        phone = info
        lastPhoneName = info.name
        lastError = nil
        UserDefaults.standard.set(info.name, forKey: Prefs.lastPhoneName)
        status = .connected
        browserBridge.setStatus(connected: true, phone: info.name)
        leftBehind.linked()
        LiveBarController.shared.update()
        pushClipHistory()
        if smsState != .unknown { refreshSms() }
        gallery.send = { [weak self] in self?.active?.send($0) }
        gallery.phoneConnected()
    }

    private func phoneDisconnected() {
        if status == .connected { leftBehind.lost() }
        browserBridge.setStatus(connected: false, phone: lastPhoneName ?? "")
        status = .waiting
        battery = nil
        isRinging = false
        notifications.removeAll()
        notifier.removeAllPhoneNotifications()
        NotchController.shared.hide()
        live.removeAll()
        players.removeAll()
        playerArt.removeAll()
        phoneVolume = nil
        phoneWantsMacMedia = false
        if smsState == .loading { smsState = .ready }
        gallery.phoneDisconnected()
        if mirror != .idle { mirror = .stopped }
        mirrorQueue.async { self.mirrorRenderer.reset() }
        LiveBarController.shared.update()
        fileQueue.async {
            let partial = self.incoming
            self.incoming.removeAll()
            self.galleryIncoming.removeAll()
            for (_, file) in partial {
                try? file.handle.close()
                try? FileManager.default.removeItem(at: file.url)
            }
            DispatchQueue.main.async {
                for id in partial.keys { self.updateTransfer(id) { $0.state = .failed } }
                for t in self.transfers where t.state == .active && t.direction == .toPhone {
                    self.updateTransfer(t.id) { $0.state = .failed }
                }
            }
        }
    }

    // MARK: Messages from phone

    private func handle(_ msg: Message) {
        guard let t = msg["t"] as? String else { return }
        switch t {
        case "battery":
            battery = msg["level"] as? Int
            charging = msg["charging"] as? Bool ?? false
        case "notif":
            receiveNotification(msg)
        case "notif_removed":
            if let key = msg["key"] as? String {
                notifications.removeAll { $0.id == key }
                notifier.remove(key)
                NotchController.shared.remove(key)
            }
        case "clip":
            if let text = msg["text"] as? String {
                clipboard.write(text)
                recordClip { $0.addText(text, fromPhone: true) }
                if msg["explicit"] as? Bool == true {
                    if NotchStyle.isOn {
                        NotchController.shared.flash(symbol: "doc.on.clipboard.fill", text: "Copied from \(phone?.name ?? "phone")")
                    } else {
                        notifier.postLocal(title: "Copied from \(phone?.name ?? "phone")", body: String(text.prefix(120)))
                    }
                }
            }
        case "ring_stopped":
            isRinging = false
        case "mirror_stopped":
            if mirror != .idle { mirror = (msg["reason"] as? String) == "denied" ? .denied : .stopped }
            mirrorQueue.async { self.mirrorRenderer.reset() }
        case "input_unavailable":
            controlUnavailable = true
        case "live":
            receiveLive(msg)
        case "live_end":
            if let key = msg["key"] as? String {
                live.removeAll { $0.id == key }
                LiveBarController.shared.update()
            }
        case "media":
            receiveMedia(msg)
        case "media_end":
            if let id = msg["id"] as? String { removePlayer(id); LiveBarController.shared.update() }
        case "open_url":
            if let s = msg["url"] as? String, let url = URL(string: s), Handoff.isWebLink(url) {
                NSWorkspace.shared.open(url)
                if NotchStyle.isOn {
                    NotchController.shared.flash(symbol: "safari.fill", text: "Opened \(url.host ?? "link") from \(phone?.name ?? "phone")")
                }
            }
        case "clip_get":
            if let id = msg["id"] as? String, let item = clipHistory.first(where: { $0.id == id }) {
                if let text = item.text { active?.send(["t": "clip", "text": text, "explicit": true]) }
                else if let png = clipStore.png(item) { sendClipboardImage(png) }
            }
        case "clip_use":
            if let id = msg["id"] as? String, let item = clipHistory.first(where: { $0.id == id }) {
                useClip(item)
                if NotchStyle.isOn { NotchController.shared.flash(symbol: "doc.on.clipboard.fill", text: "Copied from history") }
            }
        case "clip_clear":
            clearClipHistory()
        case "mac_media_want":
            phoneWantsMacMedia = msg["on"] as? Bool ?? false
            if phoneWantsMacMedia { pushMacMedia(art: true) }
        case "media_limited":
            // Android 17 mutes music started from here on a locked phone unless Mac n Droid may act in the
            // background there (its "clipboard and controls" accessibility service).
            if Date().timeIntervalSince(lastMediaLimitedNote) > 180 {
                lastMediaLimitedNote = Date()
                let name = phone?.name ?? "your phone"
                notifier.postLocal(title: "\(name) may play this silently",
                                   body: "Android only lets Mac n Droid start music on a locked phone while Mac n Droid clipboard and controls is on. Turn it on in the phone's Accessibility settings, or press play on the phone.")
            }
        case "volume":
            if let v = (msg["vol"] as? NSNumber)?.intValue {
                phoneVolume = min(max(v, 0), 100)
                LiveBarController.shared.update()
            }
        case "mac_volume_set":
            if let v = (msg["vol"] as? NSNumber)?.intValue, SystemVolume.shared.isAdjustable {
                SystemVolume.shared.volume = v
            }
        case "mac_media_cmd":
            if let cmd = msg["cmd"] as? String { macMedia.command(cmd) }
        case "gallery", "gallery_thumb", "gallery_access":
            gallery.handle(msg)
        case "sms_threads":
            smsThreads = (msg["threads"] as? [Message] ?? []).compactMap(SmsThread.init)
            smsState = .ready
        case "sms_thread":
            if let id = msg["id"] as? String {
                let fresh = (msg["messages"] as? [Message] ?? []).compactMap(SmsMessage.init)
                // Keep a just-sent text visible until the phone's copy shows up.
                let waiting = (smsMessages[id] ?? []).filter { m in
                    m.id.hasPrefix("local-") && !fresh.contains { $0.outgoing && $0.body == m.body }
                }
                smsMessages[id] = fresh + waiting
            }
        case "sms_changed":
            refreshSms()
        case "sms_sent":
            if msg["ok"] as? Bool == false, let id = msg["id"] as? String {
                for (tid, list) in smsMessages {
                    if let i = list.firstIndex(where: { $0.id == id }) { smsMessages[tid]?[i].failed = true; smsMessages[tid]?[i].pending = false }
                }
            } else {
                refreshSms()
            }
        case "contacts":
            if (msg["q"] as? String) == contactQuery {
                contactsAllowed = msg["permission"] as? Bool ?? true
                contactResults = (msg["results"] as? [Message] ?? []).compactMap { m in
                    guard let number = m["number"] as? String else { return nil }
                    return PhoneContact(name: m["name"] as? String ?? "", number: number, label: m["label"] as? String ?? "")
                }
            }
        case "sms_unavailable":
            smsState = (msg["reason"] as? String) == "permission" ? .needsPermission : .off
        case "ping":
            active?.send(["t": "pong"])
        default:
            break
        }
    }

    private static let senderID = try! NSRegularExpression(pattern: #"^[A-Z]{2}-[A-Z0-9]{3,9}(-[A-Z])?$"#)

    private func receiveNotification(_ msg: Message) {
        guard let key = msg["key"] as? String else { return }
        let pkg = msg["pkg"] as? String ?? ""
        var iconData: Data?
        if let b64 = msg["icon"] as? String, let data = Data(base64Encoded: b64), let img = NSImage(data: data) {
            icons[pkg] = img
            iconData = data
        }
        let category = AppCategory(rawValue: msg["cat"] as? String ?? "") ?? knownApps[pkg]?.category ?? .other
        remember(pkg: pkg, name: msg["app"] as? String ?? pkg, category: category, iconPNG: iconData)
        guard !isMuted(pkg) else { return }
        let title = msg["title"] as? String ?? ""
        let text = msg["text"] as? String ?? ""
        // Texts from business sender IDs ("AD-ICICIB-S") can't be answered, though apps offer Reply.
        let noReplySender = Self.senderID.firstMatch(in: title, range: NSRange(title.startIndex..., in: title)) != nil
        let actions = (msg["actions"] as? [Message] ?? []).compactMap { a -> NotifAction? in
            guard let i = a["i"] as? Int, let title = a["title"] as? String,
                  !title.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
            if noReplySender, a["reply"] as? Bool == true { return nil }
            return NotifAction(id: i, title: title, isReply: a["reply"] as? Bool ?? false,
                               opensOnPhone: a["opens"] as? Bool ?? false)
        }
        let n = PhoneNotification(
            id: key, pkg: pkg, app: msg["app"] as? String ?? pkg,
            title: title, text: text,
            time: Date(timeIntervalSince1970: (msg["time"] as? Double ?? Date().timeIntervalSince1970 * 1000) / 1000),
            isCall: msg["call"] as? Bool ?? false,
            actions: actions,
            otp: OTP.detect(title + " " + text),
            category: category)
        let isUpdate = notifications.contains { $0.id == key }
        notifications.removeAll { $0.id == key }
        notifications.insert(n, at: 0)
        if notifications.count > 200 { notifications.removeLast(notifications.count - 200) }

        if let otp = n.otp, !isUpdate, Prefs.bool(Prefs.autoCopyOTP) { copyToMac(otp) }
        guard Prefs.bool(Prefs.mirrorNotifications), msg["silent"] as? Bool != true else { return }
        // Calls get the panel with Answer / Decline buttons even when notch notifications are off,
        // because macOS hides notification buttons behind "Options".
        let callPanel = n.isCall && Prefs.bool(Prefs.callPanel)
        // A burst (many arriving within a couple of seconds) chimes once and shows only the first few;
        // the rest are in the Notifications list. Calls always alert.
        let now = Date()
        let inBurst = now.timeIntervalSince(lastNotifAt) < 2.5
        lastNotifAt = now
        burstCount = inBurst ? burstCount + 1 : 1
        let quiet = inBurst && !n.isCall
        if quiet && burstCount > 3 { return }
        let sound = Prefs.bool(Prefs.sounds) && Prefs.bool(Prefs.notificationSound) && !isUpdate && !quiet
        if NotchStyle.isOn || callPanel {
            // Notch only: macOS shows its own banner for any posted notification while another app is
            // in front, so posting as well would show everything twice. History stays in this app.
            if !isUpdate || n.isCall { NotchController.shared.show(n, icon: icons[pkg]) }
            if sound { Feedback.notify() }
        } else {
            notifier.post(n, sound: sound, icon: icons[pkg])
        }
    }

    // MARK: Live activities & media

    private func receiveLive(_ msg: Message) {
        guard let a = LiveActivity(msg) else { return }
        if let b64 = msg["icon"] as? String, let data = Data(base64Encoded: b64), let img = NSImage(data: data) {
            icons[a.pkg] = img
        }
        // An incoming call that was answered turns into a live call: drop the ringing notification.
        if notifications.contains(where: { $0.id == a.id }) {
            notifications.removeAll { $0.id == a.id }
            notifier.remove(a.id)
            NotchController.shared.remove(a.id)
        }
        live.removeAll { $0.id == a.id }
        live.append(a)
        live.sort { $0.priority > $1.priority }
        LiveBarController.shared.update()
    }

    private func receiveMedia(_ msg: Message) {
        guard let np = NowPlaying(msg) else {
            // Old phones send one player without an id; "inactive" then means nothing is playing.
            if let id = msg["id"] as? String { removePlayer(id) } else { players.removeAll(); playerArt.removeAll() }
            LiveBarController.shared.update()
            return
        }
        // A new cover replaces the old one; an empty or unreadable one never erases it.
        if let art = msg["art"] as? String, !art.isEmpty, let img = Data(base64Encoded: art).flatMap(NSImage.init(data:)) {
            playerArt[np.id] = img
        }
        if let b64 = msg["icon"] as? String, let img = Data(base64Encoded: b64).flatMap(NSImage.init(data:)) {
            icons[np.pkg] = img
        }
        players.removeAll { $0.id == np.id }
        players.append(np)
        sortPlayers()
        LiveBarController.shared.update()
    }

    private func removePlayer(_ id: String) {
        players.removeAll { $0.id == id }
        playerArt[id] = nil
    }

    private func sortPlayers() {
        players.sort { a, b in
            if a.playing != b.playing { return a.playing }
            return (a.since ?? a.positionAt) > (b.since ?? b.positionAt)
        }
    }

    /// `id` = which player (defaults to the top one).
    func mediaCommand(_ cmd: String, id: String? = nil, position: Double = 0) {
        guard let target = id ?? nowPlaying?.id else { return }
        active?.send(["t": "media_cmd", "cmd": cmd, "pos": Int(position), "id": target])
        // Feel instant; the phone confirms a moment later.
        if cmd == "toggle", let i = players.firstIndex(where: { $0.id == target }) {
            var np = players[i]
            np.position = np.position(at: Date())
            np.positionAt = Date()
            np.playing.toggle()
            np.since = np.playing ? Date() : nil
            players[i] = np
            sortPlayers()
            LiveBarController.shared.update()
        }
    }

    // MARK: App filters

    private static func loadKnownApps() -> [String: KnownApp] {
        guard let data = UserDefaults.standard.data(forKey: Prefs.knownApps),
              let list = try? JSONDecoder().decode([KnownApp].self, from: data) else { return [:] }
        return Dictionary(uniqueKeysWithValues: list.map { ($0.pkg, $0) })
    }

    private static var iconDir: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("MacNDroid/AppIcons", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Logos saved from earlier sessions, so filters show them before the app's next notification.
    private func loadSavedIcons() {
        for pkg in knownApps.keys where icons[pkg] == nil {
            if let img = NSImage(contentsOf: Self.iconDir.appendingPathComponent("\(pkg).png")) { icons[pkg] = img }
        }
    }

    private func remember(pkg: String, name: String, category: AppCategory, iconPNG: Data?) {
        let entry = KnownApp(pkg: pkg, name: name, category: category)
        if knownApps[pkg] != entry {
            knownApps[pkg] = entry
            if let data = try? JSONEncoder().encode(Array(knownApps.values)) {
                UserDefaults.standard.set(data, forKey: Prefs.knownApps)
            }
        }
        if let iconPNG { try? iconPNG.write(to: Self.iconDir.appendingPathComponent("\(pkg).png")) }
    }

    func isMuted(_ pkg: String) -> Bool { mutedApps.contains(pkg) }

    func setMuted(_ pkg: String, _ muted: Bool) {
        if muted { mutedApps.insert(pkg) } else { mutedApps.remove(pkg) }
        UserDefaults.standard.set(Array(mutedApps), forKey: Prefs.mutedApps)
        if muted {
            for n in notifications where n.pkg == pkg {
                notifier.remove(n.id)
                NotchController.shared.remove(n.id)
            }
            notifications.removeAll { $0.pkg == pkg }
        }
    }

    func setMutedAll(_ muted: Bool) {
        if muted { knownApps.keys.forEach { setMuted($0, true) } } else {
            mutedApps.removeAll()
            UserDefaults.standard.set([String](), forKey: Prefs.mutedApps)
        }
    }

    // MARK: Screen mirroring

    func showMirrorWindow() {
        openMirrorWindow?()
        NSApp.activate(ignoringOtherApps: true)
    }

    func startMirroring() {
        guard isConnected else { mirror = .stopped; return }
        controlUnavailable = false
        mirror = .waiting
        active?.send(["t": "mirror_start"])
    }

    func stopMirroring() {
        guard mirror != .idle else { return }
        mirror = .idle
        active?.send(["t": "mirror_stop"])
        mirrorQueue.async { self.mirrorRenderer.reset() }
    }

    func sendInput(_ m: Message) { active?.send(m) }
    func sendKey(_ key: String) { active?.send(["t": "input", "key": key]) }

    /// Runs on `mirrorQueue`.
    private func handleMirrorMessage(_ t: String, _ msg: Message) {
        guard let b64 = msg["d"] as? String, let data = Data(base64Encoded: b64) else { return }
        if t == "mirror_cfg" {
            if mirrorRenderer.onNeedKeyFrame == nil {
                mirrorRenderer.onNeedKeyFrame = { [weak self] in self?.active?.send(["t": "mirror_key"]) }
            }
            guard let size = mirrorRenderer.configure(data) else { return }
            DispatchQueue.main.async {
                guard self.mirror != .idle else { return }
                self.mirrorSize = size
                self.mirror = .live
            }
        } else {
            mirrorRenderer.frame(data, isKey: msg["k"] as? Bool == true)
        }
    }

    // MARK: Actions sent to phone

    func performAction(key: String, index: Int, text: String?) {
        var m: Message = ["t": "notif_action", "key": key, "i": index]
        if let text { m["text"] = text }
        active?.send(m)
        // Say where it went, so "Reply" in Gmail doesn't look like it did nothing.
        if text == nil, let n = notifications.first(where: { $0.id == key }),
           let a = n.actions.first(where: { $0.id == index }), a.opensOnPhone {
            let where_ = "\(n.app) on \(phone?.name ?? "your phone")"
            if mirror == .live { showMirrorWindow() }
            if NotchStyle.isOn {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                    NotchController.shared.flash(symbol: "arrow.up.forward.app.fill", text: "Opened \(where_)")
                }
            }
        }
    }

    func dismissOnPhone(_ key: String) {
        active?.send(["t": "notif_dismiss", "key": key])
        notifications.removeAll { $0.id == key }
        notifier.remove(key)
        NotchController.shared.remove(key)
    }

    func showNotificationsWindow() {
        requestedPane = .notifications
        openMainWindow?()
        NSApp.activate(ignoringOtherApps: true)
    }

    func clearAllNotifications() {
        for n in notifications { active?.send(["t": "notif_dismiss", "key": n.id]) }
        notifications.removeAll()
        notifier.removeAllPhoneNotifications()
    }

    func ringPhone() {
        active?.send(["t": "ring"])
        isRinging = true
    }

    func stopRinging() {
        active?.send(["t": "ring_stop"])
        isRinging = false
    }

    func sendClipboardNow() {
        if let text = NSPasteboard.general.string(forType: .string), !text.isEmpty {
            active?.send(["t": "clip", "text": text, "explicit": true])
        } else if let png = ClipboardMonitor.currentImagePNG() {
            sendClipboardImage(png)
        }
    }

    /// Images on the clipboard travel as a file transfer flagged `clip`, then land on the other clipboard.
    private func sendClipboardImage(_ png: Data) {
        guard let conn = active else { return }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("macndroid-clip-\(UUID().uuidString).png")
        guard (try? png.write(to: url)) != nil else { return }
        fileQueue.async {
            self.stream(url, id: UUID().uuidString, size: Int64(png.count), over: conn, clip: true)
            try? FileManager.default.removeItem(at: url)
        }
    }

    func copyToMac(_ text: String) {
        clipboard.write(text)
    }

    func launchAtLogin(_ on: Bool) {
        do {
            if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
        } catch {
            lastError = "Couldn't change login item: \(error.localizedDescription)"
        }
    }

    // MARK: Clipboard history

    private func recordClip(_ change: (ClipHistoryStore) -> Void) {
        change(clipStore)
        clipHistory = clipStore.items
        pushClipHistory()
    }

    func clipImage(_ item: ClipItem) -> NSImage? { clipStore.image(item) }

    /// Puts a history item back on the Mac clipboard (and the phone's, if clipboard sync is on).
    func useClip(_ item: ClipItem) {
        if let text = item.text {
            clipboard.write(text)
            if Prefs.bool(Prefs.syncClipboard) { active?.send(["t": "clip", "text": text, "explicit": false]) }
        } else if let png = clipStore.png(item) {
            clipboard.writeImage(png)
        }
        clipStore.touch(item.id)
        clipHistory = clipStore.items
        pushClipHistory()
    }

    func sendClipToPhone(_ item: ClipItem) {
        if let text = item.text { active?.send(["t": "clip", "text": text, "explicit": true]) }
        else if let png = clipStore.png(item) { sendClipboardImage(png) }
    }

    func removeClip(_ item: ClipItem) {
        recordClip { $0.remove(item.id) }
    }

    func clearClipHistory() {
        recordClip { $0.clear() }
    }

    /// The phone shows the same list. Sent shortly after a change so bursts go as one message.
    private func pushClipHistory() {
        clipPushWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, let conn = self.active else { return }
            let items: [Message] = self.clipHistory.map { item in
                var m: Message = ["id": item.id, "kind": item.kind.rawValue, "from": item.fromPhone ? "phone" : "mac",
                                  "time": Int64(item.time.timeIntervalSince1970 * 1000)]
                if let text = item.text {
                    m["text"] = String(text.prefix(20_000))
                    if text.count > 20_000 { m["truncated"] = true }
                } else if let png = self.clipStore.png(item), let thumb = MacMediaMonitor.jpeg(png, max: 360) {
                    m["thumb"] = thumb.base64EncodedString()
                }
                return m
            }
            conn.send(["t": "clip_history", "items": items])
        }
        clipPushWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4, execute: work)
    }

    // MARK: Link handoff

    func sendLink(_ url: URL) {
        guard Handoff.isWebLink(url), let conn = active else { return }
        conn.send(["t": "open_url", "url": url.absoluteString])
        if NotchStyle.isOn {
            NotchController.shared.flash(symbol: "arrow.up.forward.app.fill", text: "Opening \(url.host ?? "link") on \(phone?.name ?? "phone")")
        }
    }

    /// File → Send Browser Tab to Phone.
    func sendBrowserTab() {
        Handoff.currentTab { [weak self] result in
            guard let self else { return }
            switch result {
            case .url(let url): self.sendLink(url)
            case .noBrowser: self.handoffProblem("Open a page in your browser first.")
            case .unsupported(let name): self.handoffProblem("\(name) doesn't share its current tab. Right-click a link → Services → Open on Phone instead.")
            case .denied(let name): self.handoffProblem("Allow Mac n Droid to control \(name) in System Settings → Privacy & Security → Automation.")
            case .failed: self.handoffProblem("There's no web page open in the front window.")
            }
        }
    }

    private func handoffProblem(_ text: String) {
        if NotchStyle.isOn {
            NotchController.shared.flash(symbol: "exclamationmark.triangle.fill", text: "Couldn't send the tab")
        }
        notifier.postLocal(title: "Couldn't send the tab", body: text)
    }

    // MARK: Music on the Mac → phone

    /// Changes the phone's media volume (the phone reports the new level back).
    func setPhoneVolume(_ v: Int) {
        let v = min(max(v, 0), 100)
        phoneVolume = v
        active?.send(["t": "volume_set", "vol": v])
    }

    private var lastMediaLimitedNote = Date.distantPast
    private var volumeBeforeMute: Int?
    /// The speaker icon next to the slider: mutes the phone, and the next click brings the sound back.
    func togglePhoneMute() {
        guard let v = phoneVolume else { return }
        if v > 0 { volumeBeforeMute = v; setPhoneVolume(0) }
        else { setPhoneVolume(volumeBeforeMute ?? 50); volumeBeforeMute = nil }
    }

    private var lastMacVolume: (Int, Bool)?
    /// The Mac's own volume, for the phone's "Music on your Mac" controls.
    private func pushMacVolume() {
        guard phoneWantsMacMedia, let conn = active, macMedia.current != nil else { return }
        let sv = SystemVolume.shared
        let now = (sv.volume, sv.muted)
        if let last = lastMacVolume, last == now { return }
        lastMacVolume = now
        conn.send(["t": "mac_volume", "vol": now.0, "muted": now.1, "fixed": !sv.isAdjustable])
    }

    private func pushMacMedia(art: Bool) {
        guard phoneWantsMacMedia, let conn = active else { return }
        guard let t = macMedia.current else { conn.send(["t": "mac_media_end"]); return }
        let sv = SystemVolume.shared
        lastMacVolume = (sv.volume, sv.muted)
        var m: Message = ["t": "mac_media", "app": t.app, "title": t.title, "artist": t.artist, "album": t.album,
                          "playing": t.playing, "dur": t.duration, "pos": t.position,
                          "vol": sv.volume, "muted": sv.muted, "fixed": !sv.isAdjustable]
        if art, let a = macMediaArt, a.track == t.id { m["art"] = a.jpeg.base64EncodedString() }
        conn.send(m)
    }

    // MARK: Messages (SMS)

    func refreshSms() {
        guard let conn = active else { return }
        if smsState == .unknown || smsState == .off || smsState == .needsPermission { smsState = .loading }
        conn.send(["t": "sms_threads"])
        if let id = openSmsThread { conn.send(["t": "sms_thread", "id": id]) }
    }

    func loadSmsThread(_ id: String) {
        openSmsThread = id
        active?.send(["t": "sms_thread", "id": id])
    }

    func searchContacts(_ q: String) {
        contactQuery = q
        if q.trimmingCharacters(in: .whitespaces).isEmpty { contactResults = []; return }
        active?.send(["t": "contacts_search", "q": q])
    }

    /// `threadId` nil = a new conversation; it shows up in the list once the phone has sent it.
    func sendSms(to address: String, text: String, threadId: String?) {
        let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty, !address.isEmpty || threadId != nil, let conn = active else { return }
        let localId = "local-" + UUID().uuidString
        if let tid = threadId ?? smsThreads.first(where: { SmsThread.numberKey($0.address) == SmsThread.numberKey(address) })?.id {
            smsMessages[tid, default: []].append(SmsMessage(id: localId, body: body, date: Date(), outgoing: true, pending: true))
            if threadId == nil { smsSelectAddress = address }
        } else {
            smsSelectAddress = address
        }
        conn.send(["t": "sms_send", "address": address, "text": body, "id": localId, "thread": threadId ?? ""])
    }

    // MARK: Files → phone

    func chooseFilesToSend() {
        NSApp.activate(ignoringOtherApps: true)
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = true
        panel.prompt = "Send"
        panel.message = "Choose files or folders to send to \(phone?.name ?? "your phone")"
        if panel.runModal() == .OK { sendFiles(panel.urls) }
    }

    func sendFiles(_ urls: [URL]) {
        guard let conn = active else { return }
        for url in urls {
            let id = UUID().uuidString
            let peer = phone?.name ?? "phone"
            if (try? url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true {
                sendFolder(url, id: id, peer: peer, over: conn)
                continue
            }
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
            transfers.insert(Transfer(id: id, name: url.lastPathComponent, size: size, direction: .toPhone,
                                      localURL: url, peer: peer), at: 0)
            fileQueue.async { self.stream(url, id: id, size: size, over: conn) }
        }
    }

    /// A folder travels as one zip (`folder: true`); the phone unpacks it into a folder again.
    /// An older phone app just saves the zip.
    private func sendFolder(_ folder: URL, id: String, peer: String, over conn: PhoneConnection) {
        let name = folder.lastPathComponent
        transfers.insert(Transfer(id: id, name: name, size: 0, direction: .toPhone, localURL: folder, peer: peer), at: 0)
        // Zip off the file queue so incoming messages keep flowing meanwhile.
        DispatchQueue.global(qos: .userInitiated).async {
            guard let zip = Self.zipFolder(folder) else {
                DispatchQueue.main.async {
                    self.updateTransfer(id) { $0.state = .failed }
                    Feedback.play(.failed)
                }
                return
            }
            let size = (try? zip.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
            DispatchQueue.main.async { self.updateTransfer(id) { $0.size = size } }
            self.fileQueue.async {
                self.stream(zip, id: id, size: size, over: conn, sendName: name + ".zip", folder: true, label: name)
                try? FileManager.default.removeItem(at: zip)
            }
        }
    }

    /// Zips a folder (top-level folder included) into a temporary file.
    private static func zipFolder(_ folder: URL) -> URL? {
        var result: URL?
        var error: NSError?
        NSFileCoordinator().coordinate(readingItemAt: folder, options: .forUploading, error: &error) { zip in
            let dest = FileManager.default.temporaryDirectory.appendingPathComponent("macndroid-\(UUID().uuidString).zip")
            if (try? FileManager.default.copyItem(at: zip, to: dest)) != nil { result = dest }
        }
        return result
    }

    /// Unpacks a folder received as a zip into Downloads. Returns the new folder.
    private func unpackFolder(_ zip: URL, name: String) -> URL? {
        let fm = FileManager.default
        let downloads = fm.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
        let tmp = downloads.appendingPathComponent(".macndroid-\(UUID().uuidString)", isDirectory: true)
        defer { try? fm.removeItem(at: tmp); try? fm.removeItem(at: zip) }
        guard (try? fm.createDirectory(at: tmp, withIntermediateDirectories: true)) != nil else { return nil }
        // ditto drops "../" parts, so nothing lands outside the temporary folder.
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/ditto")
        p.arguments = ["-x", "-k", zip.path, tmp.path]
        guard (try? p.run()) != nil else { return nil }
        p.waitUntilExit()
        guard p.terminationStatus == 0 else { return nil }
        let items = ((try? fm.contentsOfDirectory(at: tmp, includingPropertiesForKeys: [.isDirectoryKey])) ?? [])
            .filter { $0.lastPathComponent != "__MACOSX" }
        // Usually the zip holds one top-level folder; otherwise the temporary folder becomes it.
        let isDir = items.count == 1 && (try? items[0].resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true
        let source = isDir ? items[0] : tmp
        let dest = uniqueURL(in: downloads, name: name)
        guard (try? fm.moveItem(at: source, to: dest)) != nil else { return nil }
        return dest
    }

    /// Streams a file in 256 KiB chunks, waiting for each to be sent (simple flow control).
    private func stream(_ url: URL, id: String, size: Int64, over conn: PhoneConnection, clip: Bool = false,
                        sendName: String? = nil, folder: Bool = false, label: String? = nil) {
        guard let handle = try? FileHandle(forReadingFrom: url) else {
            DispatchQueue.main.async { self.updateTransfer(id) { $0.state = .failed } }
            return
        }
        defer { try? handle.close() }
        let mime = folder ? "application/zip" : url.mimeType
        let sema = DispatchSemaphore(value: 0)
        var ok = true
        var start: Message = ["t": "file_start", "id": id, "name": sendName ?? url.lastPathComponent, "size": size, "mime": mime]
        if clip { start["clip"] = true }
        if folder { start["folder"] = true }
        conn.send(start) { ok = $0; sema.signal() }
        sema.wait()
        var sent: Int64 = 0
        var lastReport = Date.distantPast
        while ok, let chunk = try? handle.read(upToCount: 256 * 1024), !chunk.isEmpty {
            conn.send(["t": "file_chunk", "id": id, "data": chunk.base64EncodedString()]) { ok = $0; sema.signal() }
            sema.wait()
            sent += Int64(chunk.count)
            if Date().timeIntervalSince(lastReport) > 0.1 {
                lastReport = Date()
                let s = sent
                DispatchQueue.main.async { self.updateTransfer(id) { $0.bytes = s } }
            }
        }
        if ok { conn.send(["t": "file_end", "id": id]) { ok = $0; sema.signal() }; sema.wait() }
        let final = sent
        DispatchQueue.main.async {
            self.updateTransfer(id) { $0.bytes = final; $0.state = ok ? .done : .failed }
            guard !clip else { return }
            self.sentBatch.append((label ?? url.lastPathComponent, ok))
            // One sound and one message for a batch, once the last file is through.
            guard !self.transfers.contains(where: { $0.direction == .toPhone && $0.state == .active }) else { return }
            let batch = self.sentBatch
            self.sentBatch.removeAll()
            let failed = batch.filter { !$0.ok }.count
            let phone = self.phone?.name ?? "phone"
            Feedback.play(failed == 0 ? .sent : .failed)
            if NotchStyle.isOn {
                let text = failed > 0
                    ? (batch.count == 1 ? "Couldn't send \(batch[0].name)" : "Couldn't send \(failed) of \(batch.count) files")
                    : (batch.count == 1 ? "Sent \(batch[0].name) to \(phone)" : "Sent \(batch.count) files to \(phone)")
                NotchController.shared.flash(symbol: failed == 0 ? "checkmark.circle.fill" : "exclamationmark.triangle.fill", text: text)
            }
        }
    }

    // MARK: Files ← phone

    /// Runs on `fileQueue`.
    private func handleFileMessage(_ t: String, _ msg: Message) {
        guard let id = msg["id"] as? String else { return }
        switch t {
        case "file_start": startIncomingFile(id, msg)
        case "file_chunk": receiveChunk(id, msg)
        case "file_end": finishIncomingFile(id)
        default: break
        }
    }

    private func startIncomingFile(_ id: String, _ msg: Message) {
        let rawName = msg["name"] as? String ?? "file"
        let name = (rawName as NSString).lastPathComponent.replacingOccurrences(of: ":", with: "-")
        let size = (msg["size"] as? NSNumber)?.int64Value ?? 0
        let isClip = msg["clip"] as? Bool == true
        // A photo or video opened from the Photos pane: into the cache, quietly, then Quick Look.
        if let gid = msg["gallery"] as? String, msg["view"] as? Bool == true {
            let url = GalleryModel.cacheDir.appendingPathComponent(gallery.cacheFileName(gid, name) + ".part")
            FileManager.default.createFile(atPath: url.path, contents: nil)
            guard let handle = try? FileHandle(forWritingTo: url) else { return }
            incoming[id] = (handle, url, false, nil)
            galleryIncoming[id] = gid
            return
        }
        let folder: String? = msg["folder"] as? Bool == true
            ? { let n = (name as NSString).deletingPathExtension; return n.isEmpty ? "Folder" : n }() : nil
        let dir = isClip || folder != nil ? FileManager.default.temporaryDirectory
            : FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
        let url = uniqueURL(in: dir, name: isClip ? "macndroid-clip-\(id).png" : folder != nil ? "macndroid-folder-\(id).zip" : name)
        FileManager.default.createFile(atPath: url.path, contents: nil)
        guard let handle = try? FileHandle(forWritingTo: url) else { return }
        incoming[id] = (handle, url, isClip, folder)
        guard !isClip else { return }
        let peer = phone?.name ?? "phone"
        DispatchQueue.main.async {
            self.transfers.insert(Transfer(id: id, name: folder ?? url.lastPathComponent, size: size, direction: .fromPhone,
                                           localURL: folder == nil ? url : nil, peer: peer), at: 0)
        }
    }

    private var lastProgressReport = Date.distantPast

    private func receiveChunk(_ id: String, _ msg: Message) {
        guard let file = incoming[id], let b64 = msg["data"] as? String,
              let data = Data(base64Encoded: b64) else { return }
        try? file.handle.write(contentsOf: data)
        guard Date().timeIntervalSince(lastProgressReport) > 0.1 else { return }
        lastProgressReport = Date()
        let written = (try? file.handle.offset()).map(Int64.init) ?? 0
        DispatchQueue.main.async { self.updateTransfer(id) { $0.bytes = written } }
    }

    private func finishIncomingFile(_ id: String) {
        guard let file = incoming.removeValue(forKey: id) else { return }
        try? file.handle.close()
        if let gid = galleryIncoming.removeValue(forKey: id) {
            let final = file.url.deletingPathExtension()
            try? FileManager.default.removeItem(at: final)
            try? FileManager.default.moveItem(at: file.url, to: final)
            DispatchQueue.main.async { self.gallery.arrived(gid, url: final) }
            return
        }
        if file.isClip {
            let data = try? Data(contentsOf: file.url)
            try? FileManager.default.removeItem(at: file.url)
            DispatchQueue.main.async {
                guard let data else { return }
                self.clipboard.writeImage(data)
                self.recordClip { $0.addImage(data, fromPhone: true) }
                if NotchStyle.isOn {
                    NotchController.shared.flash(symbol: "photo.on.rectangle", text: "Image copied from \(self.phone?.name ?? "phone")")
                }
            }
            return
        }
        if let folder = file.folder {
            let result = unpackFolder(file.url, name: folder)
            DispatchQueue.main.async {
                self.updateTransfer(id) {
                    $0.state = result == nil ? .failed : .done
                    $0.bytes = max($0.bytes, $0.size)
                    $0.localURL = result
                    if let result { $0.name = result.lastPathComponent }
                }
                if let result {
                    self.receivedBatch.append(result)
                    self.scheduleReceivedSummary()
                } else {
                    Feedback.play(.failed)
                }
            }
            return
        }
        DispatchQueue.main.async {
            self.updateTransfer(id) { $0.state = .done; $0.bytes = max($0.bytes, $0.size) }
            self.receivedBatch.append(file.url)
            self.scheduleReceivedSummary()
        }
    }

    private var sentBatch: [(name: String, ok: Bool)] = []
    private var receivedBatch: [URL] = []
    private var receivedWork: DispatchWorkItem?

    /// Several files in a row (e.g. a few photos) get one sound and one message, after the last one.
    private func scheduleReceivedSummary() {
        receivedWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            if self.transfers.contains(where: { $0.direction == .fromPhone && $0.state == .active }) {
                self.scheduleReceivedSummary()
                return
            }
            let urls = self.receivedBatch
            self.receivedBatch.removeAll()
            guard let first = urls.first else { return }
            let phone = self.phone?.name ?? "phone"
            let text = urls.count == 1 ? "\(first.lastPathComponent) from \(phone)" : "\(urls.count) files from \(phone)"
            Feedback.play(.received)
            if NotchStyle.isOn {
                NotchController.shared.flash(symbol: "arrow.down.circle.fill", text: text)
            } else {
                self.notifier.postLocal(title: "Received from \(phone)",
                                        body: urls.count == 1 ? first.lastPathComponent : "\(urls.count) files in Downloads",
                                        fileURL: urls.count == 1 ? first : nil)
            }
        }
        receivedWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8, execute: work)
    }

    private func updateTransfer(_ id: String, _ change: (inout Transfer) -> Void) {
        guard let i = transfers.firstIndex(where: { $0.id == id }) else { return }
        change(&transfers[i])
    }

    func clearFinishedTransfers() {
        transfers.removeAll { $0.state != .active }
    }

    private func uniqueURL(in dir: URL, name: String) -> URL {
        var url = dir.appendingPathComponent(name.isEmpty ? "file" : name)
        let base = url.deletingPathExtension().lastPathComponent
        let ext = url.pathExtension
        var n = 2
        while FileManager.default.fileExists(atPath: url.path) {
            url = dir.appendingPathComponent(ext.isEmpty ? "\(base) \(n)" : "\(base) \(n).\(ext)")
            n += 1
        }
        return url
    }
}

import UniformTypeIdentifiers

extension URL {
    var mimeType: String {
        UTType(filenameExtension: pathExtension)?.preferredMIMEType ?? "application/octet-stream"
    }
}

#if DEBUG
// MARK: Sample data for store screenshots (debug builds only, see Debug/StoreShots.swift)

extension AppState {
    func loadStoreDemo() {
        phoneVolume = 70
        if CommandLine.arguments.contains("--live") {
            let now = Date().timeIntervalSince1970 * 1000
            live = [LiveActivity(["key": "t", "pkg": "com.google.android.deskclock", "app": "Clock", "kind": "timer",
                                  "title": "Timer", "text": "Pasta", "when": now + 263_000])!,
                    LiveActivity(["key": "r", "pkg": "demo.food", "app": "Food delivery", "kind": "status",
                                  "title": "Order on the way", "text": "Arriving in 12 min", "short": "12 min"])!]
        }
        let now = Date()
        func ago(_ m: Double) -> Date { now.addingTimeInterval(-m * 60) }
        status = .connected
        phone = PhoneInfo(name: "Phone", model: "Android phone", os: "Android 16")
        lastPhoneName = "Phone"
        battery = 82
        charging = false
        func notif(_ id: String, _ pkg: String, _ app: String, _ title: String, _ text: String, _ m: Double,
                   _ cat: AppCategory, reply: Bool = false, otp: String? = nil) -> PhoneNotification {
            var n = PhoneNotification(id: id, pkg: pkg, app: app, title: title, text: text, time: ago(m), isCall: false,
                                      actions: reply ? [NotifAction(id: 0, title: "Reply", isReply: true)] : [], otp: otp)
            n.category = cat
            return n
        }
        notifications = [
            notif("1", "demo.messages", "Messages", "Maya", "Landing at 6:40. Can you pick me up from Terminal 2?", 2, .messages, reply: true),
            notif("2", "demo.bank", "Bank", "Your sign-in code", "Use 482913 to sign in. Don't share this code with anyone.", 6, .finance, otp: "482913"),
            notif("3", "demo.mail", "Mail", "Design review moved to 4 PM", "Hi team, the review is now at 4 PM in Room 3. Agenda attached.", 18, .email),
            notif("4", "demo.food", "Food delivery", "Your order is on its way", "Arriving in about 12 minutes.", 25, .shopping),
            notif("5", "demo.calendar", "Calendar", "Yoga class", "Tomorrow, 7:00 to 8:00 AM", 41, .work),
            notif("6", "demo.chat", "Chat", "Weekend trip 🏕️", "Jake: I booked the cabin for Saturday!", 55, .social, reply: true),
        ]
        knownApps = Dictionary(uniqueKeysWithValues: notifications.map { ($0.pkg, KnownApp(pkg: $0.pkg, name: $0.app, category: $0.category)) })
        clipHistory = [
            ClipItem(id: "c1", kind: .text, text: "https://maps.example.com/terminal-2-pickup", fromPhone: true, time: ago(1)),
            ClipItem(id: "c2", kind: .text, text: "482913", fromPhone: true, time: ago(6)),
            ClipItem(id: "c3", kind: .text, text: "Booking ref: KX7-29Q · Cabin by the lake, Sat–Sun", fromPhone: false, time: ago(22)),
            ClipItem(id: "c4", kind: .text, text: "Let's keep the header simple: logo, one line, one button.", fromPhone: false, time: ago(48)),
            ClipItem(id: "c5", kind: .text, text: "221B Lake View Road, Apartment 4", fromPhone: true, time: ago(95)),
        ]
        transfers = [
            Transfer(id: "t1", name: "Screenshot 2026-11-10 at 9.41.png", size: 1_840_000, direction: .fromPhone, bytes: 1_840_000, state: .done, date: ago(1)),
            Transfer(id: "t2", name: "Trip photos.zip", size: 248_000_000, direction: .toPhone, bytes: 151_000_000, state: .active, date: ago(0)),
            Transfer(id: "t3", name: "Boarding pass.pdf", size: 320_000, direction: .toPhone, bytes: 320_000, state: .done, date: ago(30)),
            Transfer(id: "t4", name: "IMG_2041.jpg", size: 4_200_000, direction: .fromPhone, bytes: 4_200_000, state: .done, date: ago(64)),
            Transfer(id: "t5", name: "Invoice November.pdf", size: 210_000, direction: .toPhone, bytes: 210_000, state: .done, date: ago(130)),
        ]
        func ms(_ d: Date) -> Double { d.timeIntervalSince1970 * 1000 }
        players = [NowPlaying(["active": true, "pkg": "demo.music", "id": "p1", "app": "Music", "title": "Golden Hour",
                               "artist": "The Sunsets", "album": "Long Drive", "duration": 214_000.0, "playing": true,
                               "position": 81_000.0, "since": ms(ago(3)), "canPrev": true, "canNext": true, "canSeek": true])].compactMap { $0 }
        smsState = .ready
        smsThreads = [
            ["id": "s1", "address": "+1 555 0142", "name": "Maya", "snippet": "Landing at 6:40. Can you pick me up from Terminal 2?", "date": ms(ago(2)), "unread": 1],
            ["id": "s2", "address": "+1 555 0178", "name": "Jake", "snippet": "See you Saturday!", "date": ms(ago(35)), "out": true],
            ["id": "s3", "address": "+1 555 0109", "name": "Mom", "snippet": "Call me when you're free ❤️", "date": ms(ago(180))],
            ["id": "s4", "address": "+1 555 0163", "name": "Emma", "snippet": "Sent you the slides", "date": ms(ago(60 * 26))],
            ["id": "s5", "address": "+1 555 0120", "name": "Daniel", "snippet": "Haha that's perfect", "date": ms(ago(60 * 50)), "out": true],
        ].compactMap { SmsThread($0) }
        smsMessages["s1"] = [
            SmsMessage(id: "m1", body: "Are you still okay to pick me up tonight?", date: ago(40), outgoing: false),
            SmsMessage(id: "m2", body: "Of course! Send me your flight details.", date: ago(38), outgoing: true),
            SmsMessage(id: "m3", body: "Flight 284, should be on time 🤞", date: ago(20), outgoing: false),
            SmsMessage(id: "m4", body: "Landing at 6:40. Can you pick me up from Terminal 2?", date: ago(2), outgoing: false),
        ]
        smsSelectAddress = "+1 555 0142"
        Net.demoComputerName = "MacBook Air"
        addresses = ["192.168.1.24"]
        port = 47800
        pairingURI = "macndroid://pair?v=1&id=3f2a9c4e-demo&k=" + Data.random(32).base64URL + "&p=47800&h=192.168.1.24&n=MacBook%20Air"
        let palette: [(String, String, NSColor, NSColor)] = [
            ("demo.messages", "message.fill", .systemBlue, .systemTeal), ("demo.bank", "building.columns.fill", .systemIndigo, .systemPurple),
            ("demo.mail", "envelope.fill", .systemRed, .systemOrange), ("demo.food", "takeoutbag.and.cup.and.straw.fill", .systemOrange, .systemYellow),
            ("demo.calendar", "calendar", .systemPink, .systemRed), ("demo.chat", "bubble.left.and.bubble.right.fill", .systemGreen, .systemMint),
            ("demo.music", "music.note", .systemPink, .systemPurple),
        ]
        for (pkg, symbol, a, b) in palette { icons[pkg] = Self.demoIcon(symbol, a, b) }
        playerArt["p1"] = Self.demoArt()
    }

    /// Re-lists conversations so the Messages page opens the first one (it selects on change).
    func storeDemoReselectThread() {
        let t = smsThreads
        smsSelectAddress = nil
        smsThreads = []
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
            self.smsSelectAddress = t.first?.address
            self.smsThreads = t
        }
    }

    /// Puts a sent file first, so the Files page switches to its Sent tab.
    func storeDemoShowSent() {
        let sent = transfers.filter { $0.direction == .toPhone }
        transfers = sent + transfers.filter { $0.direction == .fromPhone }
    }

    private static func demoIcon(_ symbol: String, _ a: NSColor, _ b: NSColor) -> NSImage {
        NSImage(size: NSSize(width: 96, height: 96), flipped: false) { r in
            NSGradient(starting: a, ending: b)?.draw(in: NSBezierPath(ovalIn: r), angle: -60)
            let cfg = NSImage.SymbolConfiguration(pointSize: 44, weight: .semibold).applying(.init(paletteColors: [.white]))
            if let img = NSImage(systemSymbolName: symbol, accessibilityDescription: nil)?.withSymbolConfiguration(cfg) {
                let s = img.size
                img.draw(in: NSRect(x: (r.width - s.width) / 2, y: (r.height - s.height) / 2, width: s.width, height: s.height))
            }
            return true
        }
    }

    private static func demoArt() -> NSImage {
        NSImage(size: NSSize(width: 400, height: 400), flipped: false) { r in
            NSGradient(colors: [NSColor(red: 1, green: 0.62, blue: 0.27, alpha: 1), NSColor(red: 0.93, green: 0.29, blue: 0.43, alpha: 1),
                                NSColor(red: 0.36, green: 0.2, blue: 0.62, alpha: 1)])?.draw(in: r, angle: -70)
            NSColor(white: 1, alpha: 0.9).setFill()
            NSBezierPath(ovalIn: NSRect(x: 130, y: 150, width: 140, height: 140)).fill()
            NSColor(red: 0.25, green: 0.12, blue: 0.4, alpha: 0.55).setFill()
            NSBezierPath(rect: NSRect(x: 0, y: 0, width: 400, height: 150)).fill()
            return true
        }
    }
}
#endif
