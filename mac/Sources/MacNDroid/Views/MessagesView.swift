import SwiftUI

/// Read and reply to the phone's texts (SMS).
struct MessagesView: View {
    @EnvironmentObject private var state: AppState
    @State private var selection: String?
    @State private var composing = false

    var body: some View {
        Group {
            if !state.isConnected {
                ContentUnavailableView("Phone Not Connected", systemImage: "message",
                                       description: Text("Your texts show up here while \(state.lastPhoneName ?? "your phone") is connected."))
            } else {
                switch state.smsState {
                case .off, .needsPermission:
                    ContentUnavailableView {
                        Label("Turn On Messages on Your Phone", systemImage: "message.badge")
                    } description: {
                        Text(state.smsState == .off
                             ? "On \(state.phone?.name ?? "your phone"), open Mac n Droid and turn on Messages on Mac."
                             : "On \(state.phone?.name ?? "your phone"), open Mac n Droid → Messages on Mac and allow access to SMS.")
                    } actions: {
                        Button("Check Again") { state.refreshSms() }
                    }
                case .unknown, .loading where state.smsThreads.isEmpty:
                    ProgressView("Loading messages…").frame(maxWidth: .infinity, maxHeight: .infinity)
                default:
                    HStack(spacing: 0) {
                        threadList.frame(width: 300)
                        Divider()
                        Group {
                            if let id = selection, let thread = state.smsThreads.first(where: { $0.id == id }) {
                                ConversationView(thread: thread).id(id)
                            } else {
                                ContentUnavailableView("No Conversation Selected", systemImage: "message",
                                                       description: Text("Chat (RCS) messages appear from when they arrive while Mac n Droid is running. Older chats and pictures stay on the phone."))
                            }
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                }
            }
        }
        .navigationTitle("Messages")
        .toolbar {
            ToolbarItem {
                Button("New Message", systemImage: "square.and.pencil") { composing = true }
                    .disabled(!state.isConnected || state.smsState != .ready)
                    .help("New message")
            }
            ToolbarItem {
                Button("Refresh", systemImage: "arrow.clockwise") { state.refreshSms() }
                    .disabled(!state.isConnected)
                    .help("Refresh")
            }
        }
        .sheet(isPresented: $composing) { NewMessageSheet() }
        .onAppear { state.refreshSms() }
        .onDisappear { state.openSmsThread = nil }
        .onChange(of: selection) { _, id in if let id { state.loadSmsThread(id) } }
        .onChange(of: state.smsThreads) { _, threads in
            // Jump to a conversation started from New Message once the phone lists it.
            guard let addr = state.smsSelectAddress,
                  let t = threads.first(where: { SmsThread.numberKey($0.address) == SmsThread.numberKey(addr) }) else { return }
            selection = t.id
            state.smsSelectAddress = nil
        }
    }

    private var threadList: some View {
        List(state.smsThreads, selection: $selection) { t in
            HStack(alignment: .top, spacing: 10) {
                Avatar(name: t.name, size: 36)
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(t.displayName).font(.headline.weight(t.unread > 0 ? .bold : .semibold)).lineLimit(1)
                        Spacer(minLength: 4)
                        Text(Self.shortDate(t.date)).font(.caption).foregroundStyle(.secondary)
                    }
                    Text((t.lastIsOutgoing ? "You: " : "") + t.snippet)
                        .font(.callout)
                        .foregroundStyle(t.unread > 0 ? .primary : .secondary)
                        .lineLimit(2)
                }
            }
            .padding(.vertical, 3)
            .tag(t.id)
        }
        .listStyle(.inset)
    }

    static func shortDate(_ d: Date) -> String {
        let cal = Calendar.current
        if cal.isDateInToday(d) { return d.formatted(date: .omitted, time: .shortened) }
        if cal.isDateInYesterday(d) { return "Yesterday" }
        if let days = cal.dateComponents([.day], from: d, to: Date()).day, days < 7 { return d.formatted(.dateTime.weekday(.wide)) }
        return d.formatted(date: .numeric, time: .omitted)
    }
}

/// Initials in a gray circle, like Messages.
private struct Avatar: View {
    let name: String
    let size: CGFloat

    var body: some View {
        let initials = name.split(separator: " ").prefix(2).compactMap(\.first).map(String.init).joined()
        ZStack {
            Circle().fill(LinearGradient(colors: [Color(white: 0.62), Color(white: 0.5)], startPoint: .top, endPoint: .bottom))
            if initials.isEmpty || !(initials.first?.isLetter ?? false) {
                Image(systemName: "person.fill").font(.system(size: size * 0.45)).foregroundStyle(.white)
            } else {
                Text(initials.uppercased()).font(.system(size: size * 0.38, weight: .medium)).foregroundStyle(.white)
            }
        }
        .frame(width: size, height: size)
    }
}

private struct ConversationView: View {
    @EnvironmentObject private var state: AppState
    let thread: SmsThread
    @State private var draft = ""
    @FocusState private var focused: Bool

    private var messages: [SmsMessage] { state.smsMessages[thread.id] ?? [] }

    var body: some View {
        VStack(spacing: 0) {
            VStack(spacing: 2) {
                Text(thread.displayName).font(.headline)
                if !thread.name.isEmpty { Text(thread.address).font(.caption).foregroundStyle(.secondary) }
            }
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity)
            Divider()
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 6) {
                        ForEach(Array(messages.enumerated()), id: \.element.id) { i, m in
                            if i == 0 || m.date.timeIntervalSince(messages[i - 1].date) > 3600 {
                                Text(m.date.formatted(date: .abbreviated, time: .shortened))
                                    .font(.caption2).foregroundStyle(.secondary)
                                    .padding(.top, 8)
                            }
                            Bubble(message: m)
                        }
                        Color.clear.frame(height: 1).id("bottom")
                    }
                    .padding(12)
                }
                .onAppear { proxy.scrollTo("bottom") }
                .onChange(of: messages.count) { _, _ in withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo("bottom") } }
            }
            Divider()
            if thread.canReply { composer } else {
                Text(thread.address.isEmpty
                     ? "To reply here, the chat needs to be in your phone's notifications. Reply from Messages on the phone this time."
                     : "\(thread.displayName) doesn't accept replies.")
                    .multilineTextAlignment(.center)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
                    .padding(14)
            }
        }
        .onAppear { if thread.canReply { DispatchQueue.main.async { focused = true } } }
    }

    private var composer: some View {
            HStack(alignment: .bottom, spacing: 8) {
                TextField("Text Message", text: $draft, axis: .vertical)
                    .textFieldStyle(.plain)
                    .lineLimit(1...5)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 7)
                    .overlay(Capsule().strokeBorder(.separator))
                    .focused($focused)
                    .onSubmit(send)
                Button(action: send) {
                    Image(systemName: "arrow.up.circle.fill").font(.system(size: 24))
                }
                .buttonStyle(.plain)
                .foregroundStyle(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? AnyShapeStyle(.tertiary) : AnyShapeStyle(Color.accentColor))
                .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .help("Send")
            }
            .padding(10)
    }

    private func send() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        state.sendSms(to: thread.address, text: text, threadId: thread.id)
        draft = ""
    }
}

private struct Bubble: View {
    let message: SmsMessage

    var body: some View {
        HStack {
            if message.outgoing { Spacer(minLength: 60) }
            VStack(alignment: message.outgoing ? .trailing : .leading, spacing: 2) {
                Text(message.body)
                    .textSelection(.enabled)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 7)
                    .foregroundStyle(message.outgoing ? .white : .primary)
                    .background(message.outgoing ? AnyShapeStyle(Color.green) : AnyShapeStyle(.quaternary),
                                in: RoundedRectangle(cornerRadius: 17, style: .continuous))
                    .opacity(message.pending ? 0.6 : 1)
                if message.failed {
                    Label("Not Delivered", systemImage: "exclamationmark.circle.fill")
                        .font(.caption2).foregroundStyle(.red)
                } else if message.pending {
                    Text("Sending…").font(.caption2).foregroundStyle(.secondary)
                }
            }
            if !message.outgoing { Spacer(minLength: 60) }
        }
    }
}

private struct NewMessageSheet: View {
    @EnvironmentObject private var state: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var recipient: PhoneContact?
    @State private var text = ""
    @State private var searchWork: DispatchWorkItem?
    @FocusState private var focus: Field?
    private enum Field { case to, message }

    /// A typed number works too, without picking a contact.
    private var address: String {
        if let recipient { return recipient.number }
        return SmsThread.canText(query) ? query.trimmingCharacters(in: .whitespaces) : ""
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 6) {
                Text("To:").foregroundStyle(.secondary)
                if let r = recipient {
                    HStack(spacing: 4) {
                        Text(r.name.isEmpty ? r.number : r.name)
                        Button { recipient = nil; focus = .to } label: { Image(systemName: "xmark.circle.fill") }
                            .buttonStyle(.plain).foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(Color.accentColor.opacity(0.2), in: Capsule())
                    .help(r.number)
                    Spacer()
                } else {
                    TextField("Name or number", text: $query)
                        .textFieldStyle(.plain)
                        .focused($focus, equals: .to)
                        .onChange(of: query) { _, q in search(q) }
                        .onSubmit { if let first = state.contactResults.first { pick(first) } else if !address.isEmpty { focus = .message } }
                }
            }
            .padding(12)
            Divider()
            if recipient == nil && !query.isEmpty {
                if !state.contactsAllowed {
                    Text("Allow Contacts for Mac n Droid on your phone to search by name.")
                        .font(.callout).foregroundStyle(.secondary).padding(12)
                }
                List(state.contactResults) { c in
                    Button { pick(c) } label: {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(c.name.isEmpty ? c.number : c.name)
                            Text([c.label, c.number].filter { !$0.isEmpty }.joined(separator: " · "))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                .listStyle(.plain)
                .frame(height: 200)
            } else {
                TextField("Text Message", text: $text, axis: .vertical)
                    .textFieldStyle(.plain)
                    .lineLimit(4...10)
                    .focused($focus, equals: .message)
                    .padding(12)
            }
            Divider()
            HStack {
                Spacer()
                Button("Cancel") { dismiss() }.keyboardShortcut(.cancelAction)
                Button("Send") {
                    state.sendSms(to: address, text: text, threadId: nil)
                    dismiss()
                }
                .keyboardShortcut(.defaultAction)
                .disabled(address.isEmpty || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .padding(12)
        }
        .frame(width: 420)
        .onAppear { focus = .to }
        .onDisappear { state.searchContacts("") }
    }

    private func pick(_ c: PhoneContact) {
        recipient = c
        query = ""
        focus = .message
    }

    private func search(_ q: String) {
        searchWork?.cancel()
        let work = DispatchWorkItem { state.searchContacts(q) }
        searchWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2, execute: work)
    }
}
