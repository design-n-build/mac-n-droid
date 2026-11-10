import SwiftUI

struct MenuBarLabel: View {
    @EnvironmentObject private var state: AppState
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        label.onAppear {
            // The menu bar label lives as long as the app, so it can open windows for non-view code.
            state.openMainWindow = { openWindow(id: "main") }
            state.openMirrorWindow = { openWindow(id: "mirror") }
        }
    }

    @ViewBuilder private var label: some View {
        if state.isConnected, let b = state.battery {
            HStack(spacing: 3) {
                MenuBarGlyph()
                Text("\(b)%").monospacedDigit()
            }
        } else {
            MenuBarGlyph().opacity(state.isConnected ? 1 : 0.55)
        }
    }
}

/// The app logo as a template image, so macOS tints it for light/dark menu bars.
struct MenuBarGlyph: View {
    private static let image: NSImage? = {
        guard let img = NSImage(named: "MenuBarIcon") else { return nil }
        img.isTemplate = true
        img.size = NSSize(width: 18, height: 18)
        return img
    }()

    var body: some View {
        if let image = Self.image { Image(nsImage: image) } else { Image(systemName: "smartphone") }
    }
}

/// Control Center–style popover from the menu bar.
struct MenuBarView: View {
    @EnvironmentObject private var state: AppState
    @Environment(\.openWindow) private var openWindow
    @AppStorage(Prefs.syncClipboard) private var syncClipboard = true
    @AppStorage(Prefs.notchStyle) private var notchStyle = NotchStyle.compact.rawValue
    private var notchOn: Bool { notchStyle != NotchStyle.off.rawValue }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header

            if state.isConnected {
                HStack(spacing: 8) {
                    Tile(symbol: state.isRinging ? "speaker.slash.fill" : "speaker.wave.3.fill",
                         title: state.isRinging ? "Stop" : "Ring", active: state.isRinging) {
                        state.isRinging ? state.stopRinging() : state.ringPhone()
                    }
                    Tile(symbol: "square.and.arrow.up", title: "Send File") { state.chooseFilesToSend() }
                    Tile(symbol: "doc.on.clipboard", title: "Clipboard", active: syncClipboard) {
                        syncClipboard.toggle()
                    }
                    Tile(symbol: "rectangle.topthird.inset.filled", title: "Notch", active: notchOn) {
                        let d = UserDefaults.standard
                        if notchOn {
                            d.set(notchStyle, forKey: Prefs.lastNotchStyle)
                            notchStyle = NotchStyle.off.rawValue
                        } else {
                            notchStyle = d.string(forKey: Prefs.lastNotchStyle) ?? NotchStyle.compact.rawValue
                        }
                    }
                    .help(notchOn ? "Notifications drop down from the notch. Click to use regular banners."
                                  : "Notifications appear as regular banners. Click to show them in the notch.")
                }

                Button {
                    state.showMirrorWindow()
                } label: {
                    Label("Mirror Screen", systemImage: "rectangle.on.rectangle")
                        .frame(maxWidth: .infinity)
                }
                .controlSize(.large)

                ForEach(state.players.prefix(3)) { np in
                    NowPlayingCard(nowPlaying: np, art: state.playerArt[np.id], compact: true)
                }

                if !state.notifications.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Notifications").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                        ForEach(state.notifications.prefix(3)) { n in
                            MiniNotification(notification: n)
                        }
                    }
                }
            }

            Divider()

            VStack(spacing: 2) {
                MenuRow(title: "Open Mac n Droid") {
                    openWindow(id: "main")
                    NSApp.activate(ignoringOtherApps: true)
                }
                SettingsLink {
                    Text("Settings…").frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(MenuRowStyle())
                if Updater.shared.isAvailable {
                    MenuRow(title: "Check for Updates…") { Updater.shared.checkForUpdates() }
                }
                MenuRow(title: "Quit Mac n Droid") { NSApp.terminate(nil) }
            }
        }
        .padding(14)
        .frame(width: 320)
    }

    private var header: some View {
        HStack(spacing: 10) {
            Image(systemName: "smartphone")
                .font(.title2)
                .foregroundStyle(state.isConnected ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 1) {
                Text(state.phone?.name ?? state.lastPhoneName ?? "No phone").font(.headline)
                Text(state.isConnected ? "Connected" : (state.hasPairedBefore ? "Not connected" : "Not paired"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if state.isConnected, let b = state.battery {
                Label("\(b)%", systemImage: state.batterySymbol)
                    .symbolRenderingMode(.hierarchical)
                    .monospacedDigit()
            }
        }
    }
}

private struct Tile: View {
    let symbol: String
    let title: String
    var active = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: symbol)
                    .font(.system(size: 15, weight: .medium))
                    .frame(width: 34, height: 34)
                    .foregroundStyle(active ? Color.white : Color.primary)
                    .background(active ? AnyShapeStyle(Color.accentColor) : AnyShapeStyle(.quaternary), in: Circle())
                Text(title).font(.caption)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
    }
}

private struct MiniNotification: View {
    @EnvironmentObject private var state: AppState
    let notification: PhoneNotification

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            AppIcon(image: state.icons[notification.pkg], isCall: notification.isCall)
                .scaleEffect(0.8)
                .frame(width: 26, height: 26)
            VStack(alignment: .leading, spacing: 1) {
                Text(notification.title.isEmpty ? notification.app : notification.title)
                    .font(.callout.weight(.medium)).lineLimit(1)
                Text(notification.text).font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
            Spacer(minLength: 0)
            if let otp = notification.otp {
                Button(otp) { state.copyToMac(otp) }
                    .controlSize(.small)
                    .help("Copy code")
            }
        }
        .padding(8)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

private struct MenuRow: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title).frame(maxWidth: .infinity, alignment: .leading)
        }
        .buttonStyle(MenuRowStyle())
    }
}

/// Mimics a native menu item: highlight on hover.
struct MenuRowStyle: ButtonStyle {
    @State private var hovering = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
            .background(
                RoundedRectangle(cornerRadius: 5, style: .continuous)
                    .fill(hovering || configuration.isPressed ? Color.primary.opacity(0.1) : .clear))
            .onHover { hovering = $0 }
    }
}
