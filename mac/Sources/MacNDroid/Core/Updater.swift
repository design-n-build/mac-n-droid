import Combine
import Sparkle
import SwiftUI

/// Automatic updates (Sparkle). Checks once a day for a newer release on GitHub, shows what's new,
/// and installs it after you click Install. Updates are signed: the app only installs a download
/// signed with Mac n Droid's private update key.
final class Updater: ObservableObject {
    static let shared = Updater()

    private let controller: SPUStandardUpdaterController?
    @Published private(set) var canCheck = false

    private init() {
        // `swift run` builds have no feed or key; only the real app bundle updates itself.
        let info = Bundle.main.infoDictionary ?? [:]
        let configured = (info["SUFeedURL"] as? String)?.isEmpty == false && (info["SUPublicEDKey"] as? String)?.isEmpty == false
        if configured {
            let c = SPUStandardUpdaterController(startingUpdater: true, updaterDelegate: nil, userDriverDelegate: nil)
            controller = c
            c.updater.publisher(for: \.canCheckForUpdates).receive(on: DispatchQueue.main).assign(to: &$canCheck)
        } else {
            controller = nil
        }
    }

    var isAvailable: Bool { controller != nil }

    func checkForUpdates() {
        NSApp.activate(ignoringOtherApps: true)
        controller?.checkForUpdates(nil)
    }

    var automaticallyChecks: Bool {
        get { controller?.updater.automaticallyChecksForUpdates ?? false }
        set { controller?.updater.automaticallyChecksForUpdates = newValue; objectWillChange.send() }
    }

    var automaticallyDownloads: Bool {
        get { controller?.updater.automaticallyDownloadsUpdates ?? false }
        set { controller?.updater.automaticallyDownloadsUpdates = newValue; objectWillChange.send() }
    }
}

/// App menu → Check for Updates…
struct CheckForUpdatesButton: View {
    @ObservedObject private var updater = Updater.shared

    var body: some View {
        Button("Check for Updates…") { updater.checkForUpdates() }
            .disabled(!updater.canCheck)
    }
}
