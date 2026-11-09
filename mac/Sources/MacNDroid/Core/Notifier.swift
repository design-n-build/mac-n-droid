import AppKit
import UserNotifications

/// Bridges phone notifications into native macOS notifications (Notification Center).
final class Notifier: NSObject, UNUserNotificationCenterDelegate {
    private let center = UNUserNotificationCenter.current()
    private var categories: [String: UNNotificationCategory] = [:]
    private let lock = NSLock()

    var onAction: ((_ key: String, _ actionIndex: Int, _ text: String?) -> Void)?
    var onDismiss: ((_ key: String) -> Void)?
    var onCopyOTP: ((_ code: String) -> Void)?
    var onReveal: ((_ url: URL) -> Void)?

    static let fileCategory = "file.received"

    func setUp() {
        center.delegate = self
        center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
        register(UNNotificationCategory(
            identifier: Self.fileCategory,
            actions: [UNNotificationAction(identifier: "reveal", title: "Show in Finder")],
            intentIdentifiers: []
        ))
    }

    private func register(_ category: UNNotificationCategory) {
        lock.lock()
        let isNew = categories[category.identifier] == nil
        categories[category.identifier] = category
        let all = Set(categories.values)
        lock.unlock()
        if isNew { center.setNotificationCategories(all) }
    }

    /// Category for a given set of actions, so each Android action becomes a native button.
    private func category(for n: PhoneNotification) -> String {
        var actions: [UNNotificationAction] = []
        if let otp = n.otp {
            actions.append(UNNotificationAction(identifier: "otp", title: "Copy \(otp)"))
        }
        for a in n.actions.prefix(4) {
            if a.isReply {
                actions.append(UNTextInputNotificationAction(
                    identifier: "a\(a.id)", title: a.title,
                    textInputButtonTitle: "Send", textInputPlaceholder: "Message"))
            } else {
                actions.append(UNNotificationAction(
                    identifier: "a\(a.id)", title: a.displayTitle,
                    options: a.title.lowercased().contains("decline") ? [.destructive] : []))
            }
        }
        let signature = actions.map { "\($0.identifier)=\($0.title)" }.joined(separator: "|")
        let id = "phone." + String(signature.hashValue, radix: 36)
        register(UNNotificationCategory(
            identifier: id, actions: actions, intentIdentifiers: [],
            options: [.customDismissAction]))
        return id
    }

    func post(_ n: PhoneNotification, sound: Bool, icon: NSImage? = nil) {
        let content = UNMutableNotificationContent()
        content.title = n.title.isEmpty ? n.app : n.title
        content.subtitle = n.title.isEmpty ? "" : n.app
        content.body = n.text
        content.threadIdentifier = n.pkg
        content.userInfo = ["key": n.id, "otp": n.otp ?? "", "call": n.isCall]
        content.categoryIdentifier = category(for: n)
        if sound { content.sound = UNNotificationSound(named: UNNotificationSoundName("mnd_tap.wav")) }
        if n.isCall { content.interruptionLevel = .timeSensitive }
        // The posting app's logo appears as the banner thumbnail.
        if let icon, let attachment = Self.attachment(for: icon) { content.attachments = [attachment] }
        // Give a freshly registered category a moment to land before posting.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
            self.center.add(UNNotificationRequest(identifier: n.id, content: content, trigger: nil))
        }
    }

    private static func attachment(for icon: NSImage) -> UNNotificationAttachment? {
        guard let tiff = icon.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff),
              let png = rep.representation(using: .png, properties: [:]) else { return nil }
        // The system moves the file into its own store, so write a fresh copy each time.
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("icon-\(UUID().uuidString).png")
        guard (try? png.write(to: url)) != nil else { return nil }
        return try? UNNotificationAttachment(identifier: "icon", url: url)
    }

    func remove(_ key: String) {
        center.removeDeliveredNotifications(withIdentifiers: [key])
        center.removePendingNotificationRequests(withIdentifiers: [key])
    }

    func removeAllPhoneNotifications() {
        center.getDeliveredNotifications { list in
            let ids = list.map(\.request.identifier).filter { !$0.hasPrefix("file.") && !$0.hasPrefix("local.") }
            self.center.removeDeliveredNotifications(withIdentifiers: ids)
        }
    }

    func postLocal(title: String, body: String, fileURL: URL? = nil) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        if let fileURL {
            content.categoryIdentifier = Self.fileCategory
            content.userInfo = ["file": fileURL.path]
        }
        let id = (fileURL != nil ? "file." : "local.") + UUID().uuidString
        center.add(UNNotificationRequest(identifier: id, content: content, trigger: nil))
    }

    // MARK: UNUserNotificationCenterDelegate

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        // Phone notifications show in the notch instead of a banner when that style is on.
        let isPhone = notification.request.content.userInfo["key"] != nil
        let isFile = notification.request.content.userInfo["file"] != nil
        if isFile {
            completionHandler(NotchStyle.isOn ? [.list] : [.banner, .list])
        } else if isPhone, NotchStyle.isOn
                    || (notification.request.content.userInfo["call"] as? Bool == true && Prefs.bool(Prefs.callPanel)) {
            completionHandler([.list, .sound])
        } else {
            completionHandler([.banner, .list, .sound])
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        let action = response.actionIdentifier
        if let path = info["file"] as? String {
            let url = URL(fileURLWithPath: path)
            DispatchQueue.main.async { self.onReveal?(url) }
        } else if let key = info["key"] as? String {
            DispatchQueue.main.async {
                switch action {
                case "otp":
                    if let otp = info["otp"] as? String, !otp.isEmpty { self.onCopyOTP?(otp) }
                case UNNotificationDismissActionIdentifier:
                    self.onDismiss?(key)
                case let a where a.hasPrefix("a"):
                    let text = (response as? UNTextInputNotificationResponse)?.userText
                    if let i = Int(a.dropFirst()) { self.onAction?(key, i, text) }
                default:
                    NSApp.activate(ignoringOtherApps: true)
                }
            }
        }
        completionHandler()
    }
}
