import AppKit
import SwiftUI

@main
struct MacNDroidApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var state = AppState.shared

    var body: some Scene {
        Window("Mac n Droid", id: "main") {
            MainView()
                .environmentObject(state)
                .frame(minWidth: 760, minHeight: 500)
        }
        .defaultSize(width: 900, height: 600)
        .commands {
            CommandGroup(after: .appInfo) { CheckForUpdatesButton() }
            CommandGroup(after: .newItem) {
                Button("Send Files to Phone…") { state.chooseFilesToSend() }
                    .keyboardShortcut("o", modifiers: [.command, .shift])
                    .disabled(!state.isConnected)
                Button("Send Clipboard to Phone") { state.sendClipboardNow() }
                    .keyboardShortcut("v", modifiers: [.command, .shift])
                    .disabled(!state.isConnected)
                Button("Send Browser Tab to Phone") { state.sendBrowserTab() }
                    .keyboardShortcut("p", modifiers: [.control, .option])
                    .disabled(!state.isConnected)
                Button("Ring Phone") { state.ringPhone() }
                    .keyboardShortcut("r", modifiers: [.command, .shift])
                    .disabled(!state.isConnected)
            }
        }

        Window("Phone Screen", id: "mirror") {
            MirrorWindowView()
                .environmentObject(state)
        }
        .defaultSize(width: 380, height: 820)
        .windowResizability(.contentMinSize)
        .commands {
            CommandGroup(after: .windowArrangement) {
                Button("Mirror Phone Screen") { state.showMirrorWindow() }
                    .keyboardShortcut("m", modifiers: [.command, .shift])
                    .disabled(!state.isConnected)
                KeepOnTopToggle()
            }
            PhoneCommands(state: state)
        }

        MenuBarExtra {
            MenuBarView().environmentObject(state)
        } label: {
            MenuBarLabel().environmentObject(state)
        }
        .menuBarExtraStyle(.window)

        Settings {
            SettingsView().environmentObject(state)
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    private static var noNap: NSObjectProtocol?

    func applicationDidFinishLaunching(_ notification: Notification) {
        #if DEBUG
        if VideoShots.active { return }
        #endif
        DockVisibility.apply()
        // Opt out of App Nap for good (see NSAppSleepDisabled in Info.plist): a napping app handles the phone's
        // messages late and its notch animations may not draw at all while its own windows are hidden.
        Self.noNap = ProcessInfo.processInfo.beginActivity(options: .userInitiatedAllowingIdleSystemSleep,
                                                           reason: "Shows phone notifications as they arrive")
        AppState.shared.start()
        _ = Updater.shared
        // Right-click → Services → "Open on Phone" / "Send to Phone".
        NSApp.servicesProvider = ServiceProvider()
        NSUpdateDynamicServices()
    }

    /// Files dropped on the Dock icon (or "Open With") go straight to the phone.
    func application(_ application: NSApplication, open urls: [URL]) {
        AppState.shared.sendFiles(urls)
    }

    /// New windows pick up "Keep Windows on Top".
    private var keyObserver: Any?

    func applicationWillFinishLaunching(_ notification: Notification) {
        #if DEBUG
        StoreShots.runIfRequested()
        VideoShots.runIfRequested()
        #endif
        keyObserver = NotificationCenter.default.addObserver(forName: NSWindow.didBecomeKeyNotification, object: nil, queue: .main) { _ in
            WindowPin.apply()
        }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
}

private struct KeepOnTopToggle: View {
    @AppStorage(Prefs.keepOnTop) private var on = false

    var body: some View {
        Toggle("Keep Windows on Top", isOn: Binding(get: { on }, set: { _ in WindowPin.toggle() }))
            .keyboardShortcut("t", modifiers: [.command, .option])
    }
}
