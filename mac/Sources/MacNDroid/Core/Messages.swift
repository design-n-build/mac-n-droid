import Foundation

/// A text (SMS) conversation on the phone.
struct SmsThread: Identifiable, Equatable {
    let id: String
    let address: String
    let name: String
    let snippet: String
    let date: Date
    let unread: Int
    let lastIsOutgoing: Bool

    var displayName: String { name.isEmpty ? address : name }

    /// Banks, shops and other businesses send from names like "AD-ICICIB-S" that can't receive texts.
    var canReply: Bool { replyable ?? SmsThread.canText(address) }
    /// Set by the phone for chat-only conversations: whether the chat's notification (and its Reply) is still there.
    let replyable: Bool?

    static func canText(_ address: String) -> Bool {
        !address.contains(where: \.isLetter) && address.filter(\.isNumber).count >= 3
    }

    /// Last 10 digits, to match "+91 98480 12345" with "9848012345".
    static func numberKey(_ s: String) -> String { String(s.filter(\.isNumber).suffix(10)) }

    init?(_ m: Message) {
        guard let id = m["id"] as? String else { return nil }
        self.id = id
        address = m["address"] as? String ?? ""
        name = m["name"] as? String ?? ""
        snippet = m["snippet"] as? String ?? ""
        date = Date(timeIntervalSince1970: ((m["date"] as? NSNumber)?.doubleValue ?? 0) / 1000)
        unread = m["unread"] as? Int ?? 0
        lastIsOutgoing = m["out"] as? Bool ?? false
        replyable = m["reply"] as? Bool
    }
}

struct SmsMessage: Identifiable, Equatable {
    let id: String
    let body: String
    let date: Date
    let outgoing: Bool
    var failed = false
    var pending = false

    init(id: String, body: String, date: Date, outgoing: Bool, failed: Bool = false, pending: Bool = false) {
        self.id = id; self.body = body; self.date = date; self.outgoing = outgoing; self.failed = failed; self.pending = pending
    }

    init?(_ m: Message) {
        guard let id = m["id"] as? String else { return nil }
        self.init(id: id, body: m["body"] as? String ?? "",
                  date: Date(timeIntervalSince1970: ((m["date"] as? NSNumber)?.doubleValue ?? 0) / 1000),
                  outgoing: m["out"] as? Bool ?? false,
                  failed: m["failed"] as? Bool ?? false,
                  pending: m["pending"] as? Bool ?? false)
    }
}

/// A phone contact, from searching in New Message.
struct PhoneContact: Identifiable, Hashable {
    let name: String
    let number: String
    let label: String
    var id: String { name + number }
}

/// Whether the phone lets the Mac read texts.
enum SmsAvailability: Equatable {
    case unknown, loading, ready
    /// "Messages on Mac" is off on the phone, or the phone hasn't been given SMS access.
    case off, needsPermission
}
