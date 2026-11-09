import SwiftUI
import UniformTypeIdentifiers

enum Pane: String, CaseIterable, Identifiable {
    case overview, notifications, messages, clipboard, files, pair
    var id: String { rawValue }

    var title: String {
        switch self {
        case .overview: "Overview"
        case .notifications: "Notifications"
        case .messages: "Messages"
        case .clipboard: "Clipboard"
        case .files: "Files"
        case .pair: "Pair a Phone"
        }
    }

    var symbol: String {
        switch self {
        case .overview: "smartphone"
        case .notifications: "bell.badge"
        case .messages: "message"
        case .clipboard: "doc.on.clipboard"
        case .files: "folder"
        case .pair: "qrcode"
        }
    }
}

struct MainView: View {
    @EnvironmentObject private var state: AppState
    @State private var selection: Pane? = .overview
    @State private var dropTargeted = false

    var body: some View {
        NavigationSplitView {
            List(selection: $selection) {
                Section(state.phone?.name ?? "Phone") {
                    ForEach([Pane.overview, .notifications, .messages, .clipboard, .files]) { pane in
                        Label(pane.title, systemImage: pane.symbol)
                            .badge(pane == .notifications ? state.notifications.count : 0)
                            .tag(pane)
                    }
                }
                Section("Setup") {
                    Label(Pane.pair.title, systemImage: Pane.pair.symbol).tag(Pane.pair)
                }
            }
            .navigationSplitViewColumnWidth(min: 190, ideal: 210)
            .safeAreaInset(edge: .bottom) { ConnectionFooter().padding(12) }
        } detail: {
            Group {
                switch selection ?? .overview {
                case .overview: OverviewView(selection: $selection)
                case .notifications: NotificationsView()
                case .messages: MessagesView()
                case .clipboard: ClipboardView()
                case .files: FilesView()
                case .pair: PairView()
                }
            }
            .overlay {
                if dropTargeted && state.isConnected {
                    DropHint(phoneName: state.phone?.name ?? "phone")
                }
            }
        }
        .onDrop(of: [.fileURL], isTargeted: $dropTargeted) { providers in
            guard state.isConnected else { return false }
            loadURLs(providers) { state.sendFiles($0) }
            return true
        }
        .onAppear {
            if !state.hasPairedBefore { selection = .pair }
            if let p = state.requestedPane { selection = p; state.requestedPane = nil }
        }
        .onChange(of: state.requestedPane) { _, p in
            if let p { selection = p; state.requestedPane = nil }
        }
    }
}

/// Sidebar footer showing live connection state.
struct ConnectionFooter: View {
    @EnvironmentObject private var state: AppState

    var body: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(color)
                .frame(width: 8, height: 8)
            Text(label)
                .font(.callout)
                .foregroundStyle(.secondary)
                .lineLimit(1)
            Spacer()
        }
    }

    private var color: Color {
        switch state.status {
        case .connected: .green
        case .waiting: .orange
        case .starting: .gray
        case .error: .red
        }
    }

    private var label: String {
        switch state.status {
        case .connected: "Connected"
        case .waiting: state.hasPairedBefore ? "Waiting for phone…" : "Not paired"
        case .starting: "Starting…"
        case .error: "Network error"
        }
    }
}

struct DropHint: View {
    let phoneName: String

    var body: some View {
        RoundedRectangle(cornerRadius: 16, style: .continuous)
            .strokeBorder(Color.accentColor, style: StrokeStyle(lineWidth: 3, dash: [10, 6]))
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay {
                Label("Drop to send to \(phoneName)", systemImage: "arrow.down.doc")
                    .font(.title2.weight(.semibold))
            }
            .padding(24)
            .allowsHitTesting(false)
    }
}

func loadURLs(_ providers: [NSItemProvider], completion: @escaping ([URL]) -> Void) {
    let group = DispatchGroup()
    var urls: [URL] = []
    let lock = NSLock()
    for p in providers {
        group.enter()
        _ = p.loadObject(ofClass: URL.self) { url, _ in
            if let url { lock.lock(); urls.append(url); lock.unlock() }
            group.leave()
        }
    }
    group.notify(queue: .main) { completion(urls) }
}

extension AppState {
    var batterySymbol: String {
        guard let b = battery else { return "battery.0percent" }
        if charging { return "battery.100percent.bolt" }
        switch b {
        case 88...: return "battery.100percent"
        case 63..<88: return "battery.75percent"
        case 38..<63: return "battery.50percent"
        case 13..<38: return "battery.25percent"
        default: return "battery.0percent"
        }
    }
}
