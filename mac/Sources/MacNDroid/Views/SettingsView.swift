import ServiceManagement
import SwiftUI

struct SettingsView: View {
    var body: some View {
        TabView {
            GeneralSettings().tabItem { Label("General", systemImage: "gearshape") }
            SyncSettings().tabItem { Label("Sync", systemImage: "arrow.triangle.2.circlepath") }
            AppFilterSettings().tabItem { Label("Apps", systemImage: "square.grid.2x2") }
            AboutSettings().tabItem { Label("About", systemImage: "info.circle") }
        }
        .frame(width: 520, height: 460)
    }
}

private struct GeneralSettings: View {
    @EnvironmentObject private var state: AppState
    @State private var launchAtLogin = SMAppService.mainApp.status == .enabled
    @AppStorage(Prefs.showInDock) private var showInDock = true

    var body: some View {
        Form {
            Toggle("Open at login", isOn: $launchAtLogin)
                .onChange(of: launchAtLogin) { _, on in state.launchAtLogin(on) }
            Section {
                Toggle("Show in Dock", isOn: $showInDock)
                    .onChange(of: showInDock) { _, _ in DockVisibility.apply() }
            } footer: {
                Text("When off, Mac n Droid lives only in the menu bar and keeps running in the background. Closing its window never quits it.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            LabeledContent("Received files", value: "Downloads")
            LabeledContent("Paired phone", value: state.lastPhoneName ?? "None")
        }
        .formStyle(.grouped)
    }
}

private struct SyncSettings: View {
    @AppStorage(Prefs.mirrorNotifications) private var mirrorNotifications = true
    @AppStorage(Prefs.notificationSound) private var notificationSound = true
    @AppStorage(Prefs.syncClipboard) private var syncClipboard = true
    @AppStorage(Prefs.autoCopyOTP) private var autoCopyOTP = false
    @AppStorage(Prefs.notchStyle) private var notchStyle = NotchStyle.compact.rawValue
    @AppStorage(Prefs.liveActivities) private var liveActivities = true
    @AppStorage(Prefs.transferSounds) private var transferSounds = true
    @AppStorage(Prefs.callPanel) private var callPanel = true

    private var hasNotch: Bool { NSScreen.screens.contains { $0.safeAreaInsets.top > 0 } }

    var body: some View {
        Form {
            Section("Notifications") {
                Toggle("Show phone notifications on this Mac", isOn: $mirrorNotifications)
                Picker(hasNotch ? "Notch notifications" : "Top-of-screen notifications", selection: $notchStyle) {
                    ForEach(NotchStyle.allCases) { Text($0.title).tag($0.rawValue) }
                }
                .disabled(!mirrorNotifications)
                .help("Small, Compact and Large show phone notifications only in the notch (no macOS banners). Off uses regular macOS banners and Notification Center. Either way, they're listed in Mac n Droid's Notifications page.")
                Toggle("Show Answer and Decline buttons for calls", isOn: $callPanel)
                    .disabled(!mirrorNotifications)
                    .help("Incoming calls drop down from the top of the screen with Answer and Decline buttons, even when notch notifications are off.")
                Toggle("Play sound", isOn: $notificationSound).disabled(!mirrorNotifications)
                Toggle("Copy verification codes automatically", isOn: $autoCopyOTP)
            }
            Section {
                Toggle("Show live activities next to the notch", isOn: $liveActivities)
                    .onChange(of: liveActivities) { _, _ in LiveBarController.shared.update() }
            } header: {
                Text("Live Activities")
            } footer: {
                Text("A small glance in the menu bar for timers, stopwatches, calls, rides and deliveries, downloads and music playing on your phone. Click it for details and controls.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("Files") {
                Toggle("Play a sound when files are sent or received", isOn: $transferSounds)
            }
            Section {
                Toggle("Sync clipboard automatically", isOn: $syncClipboard)
            } header: {
                Text("Clipboard")
            } footer: {
                Text("Text you copy on this Mac is sent to your phone. Passwords copied from password managers are never sent.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
    }
}

private struct AboutSettings: View {
    var body: some View {
        VStack(spacing: 10) {
            Image(nsImage: NSApp.applicationIconImage)
                .resizable()
                .frame(width: 72, height: 72)
            Text("Mac n Droid").font(.title2.weight(.semibold))
            Text("Version \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "dev")")
                .foregroundStyle(.secondary)
            Text("Free, private continuity between your Android phone and Mac.")
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding()
    }
}

/// Choose which phone apps can show notifications on this Mac, grouped by kind.
private struct AppFilterSettings: View {
    @EnvironmentObject private var state: AppState
    @State private var search = ""

    private var apps: [KnownApp] {
        state.knownApps.values
            .filter { search.isEmpty || $0.name.localizedCaseInsensitiveContains(search) }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    var body: some View {
        VStack(spacing: 0) {
            if state.knownApps.isEmpty {
                ContentUnavailableView("No Apps Yet", systemImage: "square.grid.2x2",
                                       description: Text("Apps appear here after they send a notification from your phone."))
            } else {
                Form {
                    Section {
                        TextField("Search apps", text: $search)
                        HStack {
                            Text("\(state.knownApps.count - state.mutedApps.intersection(state.knownApps.keys).count) of \(state.knownApps.count) apps can show notifications")
                                .foregroundStyle(.secondary)
                            Spacer()
                            Button("Allow All") { state.setMutedAll(false) }
                            Button("Allow None") { state.setMutedAll(true) }
                        }
                    }
                    ForEach(AppCategory.allCases) { c in
                        let list = apps.filter { $0.category == c }
                        if !list.isEmpty {
                            Section {
                                ForEach(list) { app in
                                    Toggle(isOn: Binding(get: { !state.isMuted(app.pkg) },
                                                         set: { state.setMuted(app.pkg, !$0) })) {
                                        HStack(spacing: 10) {
                                            AppIcon(image: state.icons[app.pkg]).scaleEffect(0.75).frame(width: 24, height: 24)
                                            Text(app.name)
                                        }
                                    }
                                }
                            } header: {
                                Label(c.title, systemImage: c.symbol)
                            }
                        }
                    }
                }
                .formStyle(.grouped)
            }
        }
    }
}
