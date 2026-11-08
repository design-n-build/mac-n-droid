import SwiftUI

struct OverviewView: View {
    @EnvironmentObject private var state: AppState
    @Binding var selection: Pane?
    @AppStorage(Prefs.syncClipboard) private var syncClipboard = true
    @AppStorage(Prefs.mirrorNotifications) private var mirrorNotifications = true
    @AppStorage(Prefs.autoCopyOTP) private var autoCopyOTP = false
    @AppStorage(Prefs.notchStyle) private var notchStyle = NotchStyle.compact.rawValue
    @State private var showWebcamHelp = false

    var body: some View {
        if let phone = state.phone, state.isConnected {
            Form {
                Section {
                    HStack(spacing: 16) {
                        Image(systemName: "smartphone")
                            .font(.system(size: 44, weight: .light))
                            .foregroundStyle(.tint)
                            .frame(width: 56)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(phone.name).font(.title2.weight(.semibold))
                            Text([phone.model, phone.os].filter { !$0.isEmpty }.joined(separator: " · "))
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        if let b = state.battery {
                            Label("\(b)%", systemImage: state.batterySymbol)
                                .font(.title3.monospacedDigit())
                                .symbolRenderingMode(.hierarchical)
                                .foregroundStyle(b <= 15 && !state.charging ? .red : .primary)
                                .help(state.charging ? "Charging" : "Battery")
                        }
                    }
                    .padding(.vertical, 6)
                }

                Section("Quick Actions") {
                    HStack(spacing: 10) {
                        QuickAction(title: state.isRinging ? "Stop Ringing" : "Ring Phone",
                                    symbol: state.isRinging ? "speaker.slash.fill" : "speaker.wave.3.fill") {
                            state.isRinging ? state.stopRinging() : state.ringPhone()
                        }
                        QuickAction(title: "Send Files", symbol: "square.and.arrow.up") { state.chooseFilesToSend() }
                        QuickAction(title: "Send Clipboard", symbol: "doc.on.clipboard") { state.sendClipboardNow() }
                        QuickAction(title: "Mirror Screen", symbol: "rectangle.on.rectangle") { state.showMirrorWindow() }
                        QuickAction(title: "Webcam", symbol: "web.camera") { showWebcamHelp = true }
                    }
                    .padding(.vertical, 4)
                }

                if !state.players.isEmpty {
                    Section("Now Playing") {
                        ForEach(state.players) { np in
                            NowPlayingCard(nowPlaying: np, art: state.playerArt[np.id], compact: false)
                        }
                    }
                }

                if !state.live.isEmpty {
                    Section("Live Activities") {
                        TimelineView(.periodic(from: .now, by: 1)) { ctx in
                            VStack(spacing: 10) {
                                ForEach(state.live) { a in
                                    HStack(spacing: 12) {
                                        AppIcon(image: state.icons[a.pkg])
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(a.title.isEmpty ? a.app : a.title).font(.headline).lineLimit(1)
                                            Text(a.text.isEmpty ? a.app : a.text).foregroundStyle(.secondary).lineLimit(1)
                                        }
                                        Spacer()
                                        Text(a.glance(at: ctx.date)).font(.title3.monospacedDigit().weight(.semibold))
                                    }
                                }
                            }
                        }
                    }
                }

                Section("Sync") {
                    Toggle("Mirror notifications", isOn: $mirrorNotifications)
                    Picker(NSScreen.screens.contains { $0.safeAreaInsets.top > 0 } ? "Notch notifications" : "Top-of-screen notifications", selection: $notchStyle) {
                        ForEach(NotchStyle.allCases) { Text($0.title).tag($0.rawValue) }
                    }
                    .disabled(!mirrorNotifications)
                    Toggle("Sync clipboard automatically", isOn: $syncClipboard)
                    Toggle("Copy verification codes automatically", isOn: $autoCopyOTP)
                }

                Section {
                    if state.notifications.isEmpty {
                        Text("No notifications").foregroundStyle(.secondary)
                    } else {
                        ForEach(state.notifications.prefix(5)) { n in
                            NotificationRow(notification: n, compact: true)
                        }
                    }
                } header: {
                    HStack {
                        Text("Recent Notifications")
                        Spacer()
                        if !state.notifications.isEmpty {
                            Button("Show All") { selection = .notifications }
                                .buttonStyle(.link)
                                .font(.callout)
                        }
                    }
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Overview")
            .sheet(isPresented: $showWebcamHelp) { WebcamHelp() }
        } else {
            DisconnectedView(selection: $selection)
                .navigationTitle("Overview")
        }
    }
}

struct QuickAction: View {
    let title: String
    let symbol: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: symbol)
                    .font(.title2)
                    .frame(height: 26)
                Text(title).font(.callout)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
        }
        .buttonStyle(.bordered)
        .controlSize(.large)
    }
}

struct DisconnectedView: View {
    @EnvironmentObject private var state: AppState
    @Binding var selection: Pane?

    var body: some View {
        ContentUnavailableView {
            Label(state.hasPairedBefore ? "\(state.lastPhoneName ?? "Phone") Isn't Connected" : "No Phone Paired",
                  systemImage: "smartphone")
        } description: {
            if case .error(let message) = state.status {
                Text(message)
            } else if let err = state.lastError {
                Text(err)
            } else if state.hasPairedBefore {
                Text("Make sure Mac n Droid is open on your phone and both devices are on the same Wi-Fi network.")
            } else {
                Text("Pair your Android phone to see its notifications, share your clipboard and send files.")
            }
        } actions: {
            Button(state.hasPairedBefore ? "Pair Again" : "Pair a Phone") { selection = .pair }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
        }
    }
}

/// Android 14+ phones can be a plain USB webcam, which macOS supports with no extra software.
struct WebcamHelp: View {
    @EnvironmentObject private var state: AppState
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Label("Use \(state.phone?.name ?? "your phone") as a Webcam", systemImage: "web.camera")
                .font(.title2.weight(.semibold))
            VStack(alignment: .leading, spacing: 10) {
                Text("1. Connect your phone to this Mac with a USB cable.")
                Text("2. On your phone, tap the \u{201C}Charging this device via USB\u{201D} notification and choose **Webcam**.")
                Text("3. In FaceTime, Zoom, Google Meet or Photo Booth, choose your phone as the camera (Video menu or settings).")
            }
            Text("Works on Android 14 and later (Pixel 6 and newer, and many others). The phone's camera app controls which lens is used. It doesn't work over Wi-Fi: macOS only lets signed system extensions add wireless cameras.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Spacer()
                Button("Open FaceTime") { NSWorkspace.shared.open(URL(fileURLWithPath: "/System/Applications/FaceTime.app")) }
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(24)
        .frame(width: 460)
    }
}

/// Album art, title, progress and transport controls for what's playing on the phone.
struct NowPlayingCard: View {
    @EnvironmentObject private var state: AppState
    let nowPlaying: NowPlaying
    let art: NSImage?
    let compact: Bool

    var body: some View {
        let np = nowPlaying
        TimelineView(.periodic(from: .now, by: 1)) { ctx in
            let pos = np.position(at: ctx.date)
            HStack(spacing: 12) {
                Group {
                    if let art {
                        Image(nsImage: art).resizable().aspectRatio(contentMode: .fill)
                    } else {
                        Image(systemName: "music.note").font(.title2).foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, maxHeight: .infinity).background(.quaternary)
                    }
                }
                .frame(width: compact ? 44 : 64, height: compact ? 44 : 64)
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))

                VStack(alignment: .leading, spacing: 3) {
                    Text(np.title).font(compact ? .callout.weight(.semibold) : .headline).lineLimit(1)
                    Text([np.artist, np.app].filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    if np.duration > 0 {
                        ProgressView(value: pos, total: np.duration).controlSize(.mini)
                        if !compact {
                            HStack {
                                Text(NowPlaying.format(pos))
                                Spacer()
                                Text("-" + NowPlaying.format(np.duration - pos))
                            }
                            .font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                        }
                    }
                }
                HStack(spacing: compact ? 2 : 8) {
                    Button { state.mediaCommand("prev", id: np.id) } label: { Image(systemName: "backward.fill") }
                        .disabled(!np.canPrev)
                    Button { state.mediaCommand("toggle", id: np.id) } label: {
                        Image(systemName: np.playing ? "pause.fill" : "play.fill").frame(width: 18)
                    }
                    Button { state.mediaCommand("next", id: np.id) } label: { Image(systemName: "forward.fill") }
                        .disabled(!np.canNext)
                }
                .buttonStyle(.borderless)
                .font(compact ? .body : .title3)
            }
            .padding(compact ? 8 : 0)
            .background(compact ? AnyShapeStyle(.quaternary.opacity(0.5)) : AnyShapeStyle(.clear),
                        in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
    }
}
