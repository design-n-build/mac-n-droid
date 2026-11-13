import SwiftUI
import UniformTypeIdentifiers

enum Pane: String, CaseIterable, Identifiable {
    case overview, notifications, messages, clipboard, files, photos, pair
    var id: String { rawValue }

    var title: String {
        switch self {
        case .overview: "Overview"
        case .notifications: "Notifications"
        case .messages: "Messages"
        case .clipboard: "Clipboard"
        case .files: "Files"
        case .photos: "Photos"
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
        case .photos: "photo.on.rectangle.angled"
        case .pair: "qrcode"
        }
    }
}

struct MainView: View {
    @EnvironmentObject private var state: AppState
    @State private var selection: Pane?
    @State private var dropTargeted = false
    @State private var showBrowserSheet = false
    @AppStorage("welcomeSeen") private var welcomeSeen = false
    /// `open -a "Mac n Droid" --args --preview-first-run` plays Welcome → Connected without changing anything.
    /// 1 Welcome, 2 Connected, 3 pairing.
    @State private var preview: Int = CommandLine.arguments.contains("--preview-first-run") ? 1 : 0
    @AppStorage("pairLater") private var pairLater = false

    init(initialPane: Pane = .overview) { _selection = State(initialValue: initialPane) }

    var body: some View {
        // During first launch the sidebar layout isn't loaded at all, so macOS has no sidebar button or page title
        // to show; the first-launch screens fill the window on one solid background.
        ZStack {
            if let step = firstRunStep {
                Color(nsColor: .windowBackgroundColor).ignoresSafeArea()
                firstRunScreen(step)
                    .id(step)
                    .transition(.opacity)
            } else {
                appLayout.transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.35), value: firstRunStep)
        .toolbar(firstRunStep == nil ? .automatic : .hidden, for: .windowToolbar)
        .onDrop(of: [.fileURL], isTargeted: $dropTargeted) { providers in
            guard state.isConnected else { return false }
            loadURLs(providers) { state.sendFiles($0) }
            return true
        }
        .onAppear {
            if !state.hasPairedBefore { selection = .pair }
            if let p = state.requestedPane { selection = p; state.requestedPane = nil }
        }
        // A phone that scans the code while Pair a Phone is open gets the Connected moment too (not only the first one).
        .onChange(of: state.isConnected) { _, on in
            if on && selection == .pair { state.justPaired = true }
        }
        .onChange(of: state.requestedPane) { _, p in
            if let p { selection = p; state.requestedPane = nil }
        }
    }

    private var appLayout: some View {
        NavigationSplitView {
            List(selection: $selection) {
                Section(state.phone?.name ?? "Phone") {
                    ForEach([Pane.overview, .notifications, .messages, .clipboard, .files, .photos]) { pane in
                        Label(pane.title, systemImage: pane.symbol)
                            .badge(pane == .notifications ? state.notifications.count : 0)
                            .tag(pane)
                    }
                }
                Section("Setup") {
                    Label(Pane.pair.title, systemImage: Pane.pair.symbol).tag(Pane.pair)
                    Button {
                        showBrowserSheet = true
                    } label: {
                        Label("Browser Extension", systemImage: "puzzlepiece.extension")
                    }
                    .buttonStyle(.plain)
                    .help("Control browser music and videos from your phone, and open pages on it")
                }
            }
            .navigationSplitViewColumnWidth(min: 190, ideal: 210)
            .safeAreaInset(edge: .bottom) {
                VStack(spacing: 10) {
                    SidebarLive()
                    ConnectionFooter()
                }
                .padding(12)
            }
            .sheet(isPresented: $showBrowserSheet) { BrowserExtensionSheet() }
        } detail: {
            Group {
                switch selection ?? .overview {
                case .overview: OverviewView(selection: $selection)
                case .notifications: NotificationsView()
                case .messages: MessagesView()
                case .clipboard: ClipboardView()
                case .files: FilesView()
                case .photos: PhotosView(gallery: state.gallery)
                case .pair: PairView()
                }
            }
            .overlay {
                if dropTargeted && state.isConnected {
                    DropHint(phoneName: state.phone?.name ?? "phone")
                }
            }
        }
    }
}

private enum FirstRunStep: Hashable { case welcome, pair, connected }

private extension MainView {
    /// Worked out before anything is drawn, so the app never flashes in between steps.
    var firstRunStep: FirstRunStep? {
        switch preview {
        case 1: return .welcome
        case 3: return .pair
        case 2: return .connected
        default: break
        }
        if state.justPaired { return .connected }
        if state.hasPairedBefore { return nil }
        if !welcomeSeen { return .welcome }
        if !pairLater { return .pair }      // also picks up here if the app was quit halfway through
        return nil
    }

    @ViewBuilder func firstRunScreen(_ step: FirstRunStep) -> some View {
        let previewing = preview != 0
        switch step {
        case .welcome:
            WelcomeView {
                if previewing { preview = 3 } else { welcomeSeen = true; selection = .pair }
            }
        case .pair:
            OnboardingPairView(
                onLater: { if previewing { preview = 0 } else { pairLater = true } },
                onPreviewScan: previewing ? { preview = 2 } : nil)
        case .connected:
            ConnectedView(phoneName: state.phone?.name ?? state.lastPhoneName ?? "Your phone") {
                if previewing { preview = 0 } else { state.justPaired = false; selection = .overview }
            }
        }
    }
}

/// What's live on the phone, always in view at the bottom of the sidebar: music with its controls and the
/// phone's volume, then timers, stopwatches, calls, rides and deliveries.
struct SidebarLive: View {
    @EnvironmentObject private var state: AppState

    var body: some View {
        if state.isConnected, !state.players.isEmpty || !state.live.isEmpty {
            TimelineView(.periodic(from: .now, by: 1)) { ctx in
                VStack(spacing: 8) {
                    ForEach(state.players.prefix(2)) { np in player(np, now: ctx.date) }
                    ForEach(state.live.sorted { $0.priority > $1.priority }) { a in activity(a, now: ctx.date) }
                }
            }
            .transition(.opacity)
        }
    }

    private func player(_ np: NowPlaying, now: Date) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Group {
                    if let art = state.playerArt[np.id] ?? state.icons[np.pkg] {
                        Image(nsImage: art).resizable().aspectRatio(contentMode: .fill)
                    } else {
                        Image(systemName: "music.note").foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, maxHeight: .infinity).background(.quaternary)
                    }
                }
                .frame(width: 36, height: 36)
                .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
                VStack(alignment: .leading, spacing: 1) {
                    Text(np.title).font(.callout.weight(.semibold)).lineLimit(1)
                    Text(np.artist.isEmpty ? np.app : np.artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            if np.duration > 0 {
                ProgressView(value: min(np.position(at: now), np.duration), total: np.duration)
                    .progressViewStyle(.linear).controlSize(.mini)
            }
            HStack(spacing: 0) {
                Button { state.mediaCommand("prev", id: np.id) } label: { Image(systemName: "backward.fill").frame(maxWidth: .infinity) }
                    .disabled(!np.canPrev)
                Button { state.mediaCommand("toggle", id: np.id) } label: {
                    Image(systemName: np.playing ? "pause.fill" : "play.fill").font(.title3).frame(maxWidth: .infinity)
                }
                Button { state.mediaCommand("next", id: np.id) } label: { Image(systemName: "forward.fill").frame(maxWidth: .infinity) }
                    .disabled(!np.canNext)
            }
            .buttonStyle(.borderless)
            if let v = state.phoneVolume {
                PhoneVolumeSlider(volume: v, set: { state.setPhoneVolume($0) }, mute: { state.togglePhoneMute() })
            }
        }
        .padding(10)
        .background(.quaternary.opacity(0.6), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    private func activity(_ a: LiveActivity, now: Date) -> some View {
        HStack(spacing: 8) {
            Group {
                if let icon = state.icons[a.pkg] {
                    Image(nsImage: icon).resizable().interpolation(.high)
                } else {
                    Image(systemName: a.symbol).foregroundStyle(.orange)
                }
            }
            .frame(width: 22, height: 22)
            VStack(alignment: .leading, spacing: 1) {
                Text(a.title.isEmpty ? a.app : a.title).font(.callout.weight(.medium)).lineLimit(1)
                if !a.text.isEmpty { Text(a.text).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
            }
            Spacer(minLength: 4)
            Text(a.glance(at: now)).font(.callout.monospacedDigit().weight(.semibold))
                .foregroundStyle(a.kind == .timer || a.kind == .stopwatch ? AnyShapeStyle(.orange) : AnyShapeStyle(.primary))
                .opacity(a.isPaused ? 0.55 : 1)
                .lineLimit(1)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(.quaternary.opacity(0.6), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
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
