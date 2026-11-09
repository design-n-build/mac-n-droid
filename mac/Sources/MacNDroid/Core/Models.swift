import AppKit
import Foundation

struct PhoneInfo: Equatable {
    var name: String
    var model: String
    var os: String
}

struct NotifAction: Hashable, Identifiable {
    let id: Int
    let title: String
    let isReply: Bool
    /// The action opens an app screen on the phone instead of acting in the background, like Gmail's
    /// Reply (Gmail doesn't allow replying from a notification, even on the phone).
    var opensOnPhone = false

    /// "Reply" that can't take text here reads "Reply on Phone", so the button says what it does.
    var displayTitle: String {
        opensOnPhone && !isReply && title.lowercased().hasPrefix("reply") ? "\(title) on Phone" : title
    }
}

struct PhoneNotification: Identifiable, Equatable {
    let id: String          // Android notification key
    let pkg: String
    let app: String
    let title: String
    let text: String
    let time: Date
    let isCall: Bool
    let actions: [NotifAction]
    let otp: String?
    var category: AppCategory = .other
}

/// How apps are grouped on the Mac (sent by the phone, which knows each app's type).
enum AppCategory: String, CaseIterable, Identifiable, Codable {
    case messages, calls, email, social, shopping, finance, media, travel, work, news, games, system, other
    var id: String { rawValue }

    var title: String {
        switch self {
        case .messages: "Messages"
        case .calls: "Calls"
        case .email: "Email"
        case .social: "Social"
        case .shopping: "Shopping & Food"
        case .finance: "Banking & Payments"
        case .media: "Music & Video"
        case .travel: "Travel & Rides"
        case .work: "Work & Productivity"
        case .news: "News"
        case .games: "Games"
        case .system: "System"
        case .other: "Other"
        }
    }

    var symbol: String {
        switch self {
        case .messages: "message"
        case .calls: "phone"
        case .email: "envelope"
        case .social: "person.2"
        case .shopping: "bag"
        case .finance: "indianrupeesign.circle"
        case .media: "play.rectangle"
        case .travel: "car"
        case .work: "briefcase"
        case .news: "newspaper"
        case .games: "gamecontroller"
        case .system: "gearshape"
        case .other: "square.grid.2x2"
        }
    }
}

/// An app that has sent notifications, remembered across launches (name, group, logo) so it can be filtered.
struct KnownApp: Codable, Identifiable, Equatable {
    let pkg: String
    var name: String
    var category: AppCategory
    var id: String { pkg }
}

struct Transfer: Identifiable, Equatable {
    enum Direction { case toPhone, fromPhone }
    enum State: Equatable { case active, done, failed }
    let id: String
    let name: String
    let size: Int64
    let direction: Direction
    var bytes: Int64 = 0
    var state: State = .active
    var localURL: URL?
    var peer: String = "phone"
    var date = Date()

    var progress: Double { size > 0 ? min(1, Double(bytes) / Double(size)) : (state == .done ? 1 : 0) }
}

enum Prefs {
    static let syncClipboard = "syncClipboard"
    static let mirrorNotifications = "mirrorNotifications"
    static let notificationSound = "notificationSound"
    static let autoCopyOTP = "autoCopyOTP"
    static let notchNotifications = "notchNotifications"
    static let transferSounds = "transferSounds"
    static let lastPhoneName = "lastPhoneName"
    static let showInDock = "showInDock"
    static let callPanel = "callPanel"
    static let notchStyle = "notchStyle"
    static let lastNotchStyle = "lastNotchStyle"
    static let liveActivities = "liveActivities"
    static let mutedApps = "mutedApps"
    static let knownApps = "knownApps"
    static let keepOnTop = "keepOnTop"
    static let leftBehind = "leftBehind"
    static let dropOnNotch = "dropOnNotch"

    static func registerDefaults() {
        // 0.6 and earlier had an on/off switch; keep "off" for people who turned it off.
        let d = UserDefaults.standard
        if d.string(forKey: notchStyle) == nil, d.object(forKey: notchNotifications) != nil {
            d.set(d.bool(forKey: notchNotifications) ? NotchStyle.compact.rawValue : NotchStyle.off.rawValue, forKey: notchStyle)
        }
        UserDefaults.standard.register(defaults: [
            notchStyle: NotchStyle.compact.rawValue,
            liveActivities: true,
            syncClipboard: true,
            mirrorNotifications: true,
            notificationSound: true,
            autoCopyOTP: false,
            notchNotifications: true,
            transferSounds: true,
            showInDock: true,
            callPanel: true,
            leftBehind: true,
            dropOnNotch: true,
        ])
    }

    static func bool(_ key: String) -> Bool { UserDefaults.standard.bool(forKey: key) }
}

/// "Keep Windows on Top": the main and Phone Screen windows float above other apps (and stay put
/// when Stage Manager switches stages), so the mirrored phone can sit beside whatever you're doing.
enum WindowPin {
    static var isOn: Bool { Prefs.bool(Prefs.keepOnTop) }

    static func toggle() {
        UserDefaults.standard.set(!isOn, forKey: Prefs.keepOnTop)
        apply()
    }

    static func apply() {
        for w in NSApp.windows where ["main", "mirror"].contains(where: { w.identifier?.rawValue.hasPrefix($0) == true }) {
            w.level = isOn ? .floating : .normal
            if isOn { w.collectionBehavior.insert(.canJoinAllApplications) } else { w.collectionBehavior.remove(.canJoinAllApplications) }
        }
    }
}

/// Dock icon on/off. Off = menu bar only (no Dock icon, not in ⌘-Tab); the app keeps running.
enum DockVisibility {
    static func apply() {
        let policy: NSApplication.ActivationPolicy = Prefs.bool(Prefs.showInDock) ? .regular : .accessory
        guard NSApp.activationPolicy() != policy else { return }
        let hadKeyWindow = NSApp.keyWindow != nil
        NSApp.setActivationPolicy(policy)
        // Switching policy can drop the app to the background; keep an open window in front.
        if hadKeyWindow { DispatchQueue.main.async { NSApp.activate(ignoringOtherApps: true) } }
    }
}

/// Finds one-time codes ("Your code is 482913", "G-482913", "482 913 is your OTP").
///
/// A candidate must stand on its own (not glued to letters, "@", "/", currency, etc., so the
/// "1234" in "name1234@gmail.com" doesn't count) and sit near a real code keyword.
enum OTP {
    private static let keyword = try! NSRegularExpression(
        pattern: #"\b(otp|code|codes|passcode|verification|verify|one[- ]time|2fa|two[- ]factor|authenticat\w*|pin|security code|login code|sign[- ]in code|kod|código)\b"#,
        options: [.caseInsensitive])
    private static let candidate = try! NSRegularExpression(
        pattern: #"(?<![\w@.\-/#₹$€£+])(?:G-)?(\d{3}[- ]\d{3}|\d{4,8})(?![\w@\-/:%]|[.,]\d)"#)

    static func detect(_ text: String) -> String? {
        let ns = text as NSString
        let full = NSRange(location: 0, length: ns.length)
        let keywords = keyword.matches(in: text, range: full).map(\.range)
        guard !keywords.isEmpty else { return nil }
        var best: (code: String, distance: Int)?
        for m in candidate.matches(in: text, range: full) {
            let r = m.range(at: 1)
            let code = ns.substring(with: r).filter(\.isNumber)
            if code.count == 4, let n = Int(code), (1900...2099).contains(n) { continue }   // years
            // Distance to the nearest keyword, in characters.
            let distance = keywords.map { k -> Int in
                if k.upperBound <= r.location { return r.location - k.upperBound }
                if r.upperBound <= k.location { return k.location - r.upperBound }
                return 0
            }.min() ?? .max
            guard distance <= 60 else { continue }
            if best == nil || distance < best!.distance { best = (code, distance) }
        }
        return best?.code
    }
}

/// Subtle system sounds for file transfers.
enum Feedback {
    enum Kind { case sent, received, failed }

    /// The soft tap for a phone notification shown in the notch.
    static func notify() {
        guard let url = Bundle.main.url(forResource: "mnd_tap", withExtension: "wav"),
              let sound = NSSound(contentsOf: url, byReference: true) else { return }
        sound.volume = 0.7
        sound.play()
    }

    static func play(_ kind: Kind) {
        guard Prefs.bool(Prefs.transferSounds) else { return }
        // Mac n Droid's own sounds (tools/make-sounds.py), not system alerts.
        let file: String = switch kind {
        case .sent: "mnd_sent"
        case .received: "mnd_received"
        case .failed: "mnd_tap"
        }
        guard let url = Bundle.main.url(forResource: file, withExtension: "wav"),
              let sound = NSSound(contentsOf: url, byReference: true) else { return }
        sound.volume = 0.4
        sound.play()
    }
}

extension Int64 {
    var fileSize: String { ByteCountFormatter.string(fromByteCount: self, countStyle: .file) }
}
