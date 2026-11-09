import SwiftUI

struct NotificationsView: View {
    @EnvironmentObject private var state: AppState
    @State private var search = ""
    @State private var group: AppCategory?

    private var filtered: [PhoneNotification] {
        let inGroup = group.map { g in state.notifications.filter { $0.category == g } } ?? state.notifications
        guard !search.isEmpty else { return inGroup }
        return inGroup.filter {
            $0.title.localizedCaseInsensitiveContains(search)
                || $0.text.localizedCaseInsensitiveContains(search)
                || $0.app.localizedCaseInsensitiveContains(search)
        }
    }

    var body: some View {
        Group {
            if state.notifications.isEmpty {
                ContentUnavailableView(
                    "No Notifications",
                    systemImage: "bell.slash",
                    description: Text(state.isConnected
                        ? "New notifications from your phone will appear here."
                        : "Connect your phone to see its notifications."))
            } else if filtered.isEmpty {
                ContentUnavailableView.search(text: search)
            } else {
                // A plain stack instead of List: List kept a stale height for a row added at the top,
                // which cut the newest notification in half.
                ScrollView {
                    let groups = AppCategory.allCases.filter { c in filtered.contains { $0.category == c } }
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(groups) { c in
                            if group == nil && groups.count > 1 {
                                Label(c.title, systemImage: c.symbol)
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(.secondary)
                                    .padding(.top, 18)
                                    .padding(.bottom, 4)
                            }
                            let rows = filtered.filter { $0.category == c }
                            ForEach(rows) { n in
                                NotificationRow(notification: n, compact: false)
                                    .padding(.vertical, 10)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                if n.id != rows.last?.id { Divider().padding(.leading, 48) }
                            }
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.bottom, 16)
                }
            }
        }
        .navigationTitle("Notifications")
        .searchable(text: $search, placement: .toolbar)
        .toolbar {
            ToolbarItem {
                Menu {
                    Picker("Show", selection: $group) {
                        Label("All Apps", systemImage: "square.grid.2x2").tag(AppCategory?.none)
                        Divider()
                        ForEach(AppCategory.allCases.filter { c in state.notifications.contains { $0.category == c } }) { c in
                            let count = state.notifications.filter { $0.category == c }.count
                            Label("\(c.title) (\(count))", systemImage: c.symbol).tag(AppCategory?.some(c))
                        }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                } label: {
                    Label(group?.title ?? "All Apps", systemImage: group?.symbol ?? "line.3.horizontal.decrease.circle")
                        .labelStyle(.titleAndIcon)
                }
                .help("Show one kind of app")
            }
            ToolbarItem {
                Button("Clear All", systemImage: "trash") { state.clearAllNotifications() }
                    .disabled(state.notifications.isEmpty)
                    .help("Dismiss all notifications on your phone")
            }
        }
    }
}

struct NotificationRow: View {
    @EnvironmentObject private var state: AppState
    let notification: PhoneNotification
    let compact: Bool
    @State private var replyText = ""
    @State private var replying = false
    @FocusState private var replyFocused: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            AppIcon(image: state.icons[notification.pkg], isCall: notification.isCall)
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(notification.app).font(.caption).foregroundStyle(.secondary)
                    Spacer()
                    RelativeTime(date: notification.time)
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
                if !notification.title.isEmpty {
                    Text(notification.title).font(.headline).lineLimit(1)
                }
                if !notification.text.isEmpty {
                    Text(notification.text)
                        .foregroundStyle(.secondary)
                        .lineLimit(compact ? 2 : 6)
                        .textSelection(.enabled)
                }
                if !compact { actionBar }
                if replying {
                    HStack {
                        TextField("Reply", text: $replyText)
                            .textFieldStyle(.roundedBorder)
                            .focused($replyFocused)
                            .onAppear { DispatchQueue.main.async { replyFocused = true } }
                            .onExitCommand { replying = false }
                            .onSubmit(sendReply)
                        Button("Send", action: sendReply)
                            .buttonStyle(.borderedProminent)
                            .disabled(replyText.isEmpty)
                    }
                    .padding(.top, 4)
                }
            }
        }
        .contextMenu {
            Button("Copy Text") { state.copyToMac([notification.title, notification.text].filter { !$0.isEmpty }.joined(separator: "\n")) }
            if let otp = notification.otp { Button("Copy Code \(otp)") { state.copyToMac(otp) } }
            Divider()
            Button("Dismiss on Phone") { state.dismissOnPhone(notification.id) }
            Button("Hide Notifications from \(notification.app)") { state.setMuted(notification.pkg, true) }
        }
    }

    @ViewBuilder private var actionBar: some View {
        let hasButtons = notification.otp != nil || !notification.actions.isEmpty
        if hasButtons {
            HStack(spacing: 8) {
                if let otp = notification.otp {
                    Button("Copy \(otp)", systemImage: "doc.on.doc") { state.copyToMac(otp) }
                        .buttonStyle(.borderedProminent)
                }
                ForEach(notification.actions) { a in
                    Button {
                        if a.isReply { replying.toggle() } else {
                            state.performAction(key: notification.id, index: a.id, text: nil)
                        }
                    } label: {
                        if a.opensOnPhone { Label(a.displayTitle, systemImage: "arrow.up.forward.app") } else { Text(a.title) }
                    }
                    .help(a.opensOnPhone ? "Opens \(notification.app) on your phone" : "")
                    .tint(a.title.lowercased().contains("decline") ? .red : nil)
                }
                Spacer()
                Button("Dismiss", systemImage: "xmark") { state.dismissOnPhone(notification.id) }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.borderless)
                    .help("Dismiss on phone")
            }
            .controlSize(.small)
            .padding(.top, 4)
        }
    }

    private func sendReply() {
        guard let reply = notification.actions.first(where: \.isReply), !replyText.isEmpty else { return }
        state.performAction(key: notification.id, index: reply.id, text: replyText)
        replyText = ""
        replying = false
    }
}

/// "12 sec ago" while fresh (live), then "22 min ago", "2 hr ago", then a date. Seconds only under 2 minutes.
struct RelativeTime: View {
    let date: Date

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { ctx in
            Text(Self.label(for: date, now: ctx.date))
                .monospacedDigit()
        }
    }

    static func label(for date: Date, now: Date) -> String {
        let s = max(0, Int(now.timeIntervalSince(date)))
        switch s {
        case ..<5: return "now"
        case ..<60: return "\(s) sec ago"
        case ..<120: return "1 min \(s - 60) sec ago"
        case ..<3600: return "\(s / 60) min ago"
        case ..<86_400:
            let h = s / 3600, m = (s % 3600) / 60
            return m == 0 ? "\(h) hr ago" : "\(h) hr \(m) min ago"
        default:
            return date.formatted(.dateTime.month(.abbreviated).day().hour().minute())
        }
    }
}

struct AppIcon: View {
    let image: NSImage?
    var isCall = false

    var body: some View {
        Group {
            if let image {
                Image(nsImage: image).resizable().interpolation(.high)
            } else {
                Image(systemName: isCall ? "phone.fill" : "app.badge")
                    .font(.system(size: 16))
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(.quaternary, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
            }
        }
        .frame(width: 32, height: 32)
    }
}
