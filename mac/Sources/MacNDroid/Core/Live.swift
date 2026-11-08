import AppKit

/// An ongoing thing on the phone worth glancing at: a timer, stopwatch, call, ride/delivery, download.
struct LiveActivity: Identifiable, Equatable {
    enum Kind: String { case call, timer, stopwatch, progress, status }

    let id: String
    let pkg: String
    let app: String
    let kind: Kind
    let title: String
    let text: String
    let short: String
    /// Chronometer base: when a timer hits zero, or when a stopwatch / call started.
    let when: Date?
    /// Set when a stopwatch/timer is paused: the frozen elapsed (or remaining) time in seconds.
    let pausedSeconds: Double?
    let progress: Int
    let max: Int

    init?(_ msg: Message) {
        guard let key = msg["key"] as? String else { return nil }
        id = key
        pkg = msg["pkg"] as? String ?? ""
        app = msg["app"] as? String ?? pkg
        kind = Kind(rawValue: msg["kind"] as? String ?? "") ?? .status
        title = msg["title"] as? String ?? ""
        text = msg["text"] as? String ?? ""
        short = msg["short"] as? String ?? ""
        let w = (msg["when"] as? Double) ?? 0
        when = w > 0 ? Date(timeIntervalSince1970: w / 1000) : nil
        let p = (msg["paused"] as? Double) ?? -1
        pausedSeconds = p >= 0 ? p / 1000 : nil
        progress = msg["progress"] as? Int ?? 0
        max = msg["max"] as? Int ?? 0
    }

    /// Higher shows first in the menu bar.
    var priority: Int {
        switch kind {
        case .call: 50
        case .timer: 40
        case .stopwatch: 30
        case .status: 20
        case .progress: 10
        }
    }

    /// The few characters shown next to the notch: "4:59", "12:03", "45%", "5 min".
    func glance(at now: Date) -> String {
        switch kind {
        case .timer, .stopwatch, .call:
            if pausedSeconds != nil || when != nil {
                let secs = Int(pausedSeconds ?? abs(now.timeIntervalSince(when!)))
                let h = secs / 3600, m = (secs % 3600) / 60, s = secs % 60
                return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
            }
            return short.isEmpty ? (title.isEmpty ? text : title) : short
        case .progress:
            if max > 0 { return "\(Int((Double(progress) / Double(max) * 100).rounded()))%" }
            return short.isEmpty ? title : short
        case .status:
            if !short.isEmpty { return short }
            return Self.compact(title.isEmpty ? text : title)
        }
    }

    var isPaused: Bool { pausedSeconds != nil }

    var fraction: Double? { max > 0 ? min(1, Double(progress) / Double(max)) : nil }

    /// "Arriving in 5 min" → "5 min" when there's an obvious short form.
    static func compact(_ s: String) -> String {
        if let r = s.range(of: #"\d+\s?(min|mins|minutes|hr|hrs|h|m|sec|s)\b"#, options: [.regularExpression, .caseInsensitive]) {
            return String(s[r])
        }
        return s
    }

    var symbol: String {
        switch kind {
        case .call: "phone.fill"
        case .timer: "timer"
        case .stopwatch: "stopwatch"
        case .progress: "arrow.down.circle"
        case .status: "clock"
        }
    }
}

/// What the phone is playing (Spotify, YouTube Music, podcasts…).
struct NowPlaying: Equatable, Identifiable {
    var id: String
    /// When it started playing (most recent wins the menu bar); nil while paused.
    var since: Date?
    var pkg: String
    var app: String
    var title: String
    var artist: String
    var album: String
    var duration: Double      // ms, 0 if unknown (live streams)
    var playing: Bool
    var position: Double      // ms at `positionAt`
    var positionAt: Date
    var speed: Double
    var canPrev: Bool
    var canNext: Bool
    var canSeek: Bool

    init?(_ msg: Message) {
        guard msg["active"] as? Bool == true, let title = msg["title"] as? String else { return nil }
        pkg = msg["pkg"] as? String ?? ""
        id = msg["id"] as? String ?? pkg
        let s = (msg["since"] as? NSNumber)?.doubleValue ?? 0
        since = s > 0 ? Date(timeIntervalSince1970: s / 1000) : nil
        app = msg["app"] as? String ?? ""
        self.title = title
        artist = msg["artist"] as? String ?? ""
        album = msg["album"] as? String ?? ""
        duration = (msg["duration"] as? NSNumber)?.doubleValue ?? 0
        playing = msg["playing"] as? Bool ?? false
        position = (msg["position"] as? NSNumber)?.doubleValue ?? 0
        positionAt = Date(timeIntervalSince1970: ((msg["positionAt"] as? NSNumber)?.doubleValue ?? Date().timeIntervalSince1970 * 1000) / 1000)
        speed = (msg["speed"] as? NSNumber)?.doubleValue ?? 1
        canPrev = msg["canPrev"] as? Bool ?? true
        canNext = msg["canNext"] as? Bool ?? true
        canSeek = msg["canSeek"] as? Bool ?? false
    }

    /// Current position in ms, ticking locally between updates.
    func position(at now: Date) -> Double {
        let p = playing ? position + now.timeIntervalSince(positionAt) * 1000 * max(speed, 0) : position
        return duration > 0 ? min(max(p, 0), duration) : max(p, 0)
    }

    static func format(_ ms: Double) -> String {
        let s = Int(ms / 1000)
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
                         : String(format: "%d:%02d", s / 60, s % 60)
    }
}

/// How phone notifications appear around the notch.
enum NotchStyle: String, CaseIterable, Identifiable {
    case off, small, compact, large
    var id: String { rawValue }

    var title: String {
        switch self {
        case .off: "Off (use macOS banners)"
        case .small: "Small: beside the notch"
        case .compact: "Compact: title and message"
        case .large: "Large: with buttons and reply"
        }
    }

    static var current: NotchStyle {
        get { NotchStyle(rawValue: UserDefaults.standard.string(forKey: Prefs.notchStyle) ?? "") ?? .compact }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: Prefs.notchStyle) }
    }

    static var isOn: Bool { current != .off }
}
