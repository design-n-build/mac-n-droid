import AppKit
import SwiftUI

/// Explains what the browser extension does, then sends people to their browser's extension store.
/// Shown from Settings → Sync → Browser and from the Overview until an extension has connected.
struct BrowserExtensionSheet: View {
    @Environment(\.dismiss) private var dismiss
    private let preferFirefox = BrowserExtension.defaultFamily == .firefox

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 14) {
                Image(nsImage: NSApp.applicationIconImage)
                    .resizable()
                    .frame(width: 56, height: 56)
                VStack(alignment: .leading, spacing: 3) {
                    Text("Add Mac n Droid to Your Browser")
                        .font(.title2.weight(.semibold))
                    Text("A small, free extension that lets your browser and your phone work together.")
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            VStack(alignment: .leading, spacing: 14) {
                Feature(symbol: "playpause.fill",
                        title: "Control browser music and videos from your phone",
                        detail: "YouTube, web players and podcasts appear in your phone's media controls. Pause, play and skip without walking back to your Mac.")
                Feature(symbol: "smartphone",
                        title: "Open the page you're reading on your phone",
                        detail: "Click the toolbar button and the page opens on your phone, ready to keep reading on the go.")
                Feature(symbol: "link",
                        title: "Open any link on your phone",
                        detail: "Right-click a link and choose Open Link on Your Phone.")
                Feature(symbol: "lock.shield",
                        title: "Stays on this Mac",
                        detail: "The extension talks only to Mac n Droid on this computer. It doesn't track what you browse and sends nothing to the internet.")
            }

            Text("Chrome, Edge, Brave, Arc and other Chrome-based browsers get it from the Chrome Web Store. Firefox and Firefox-based browsers get it from Firefox Add-ons. Once it's added, it connects by itself. Safari isn't supported yet.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            HStack {
                Spacer()
                Button("Not Now") { dismiss() }
                    .keyboardShortcut(.cancelAction)
                if preferFirefox {
                    Button("Add to Chrome-Based Browser") { open(.chrome) }
                    Button("Add to Firefox-Based Browser") { open(.firefox) }
                        .keyboardShortcut(.defaultAction)
                } else {
                    Button("Add to Firefox-Based Browser") { open(.firefox) }
                    Button("Add to Chrome-Based Browser") { open(.chrome) }
                        .keyboardShortcut(.defaultAction)
                }
            }
            .controlSize(.large)
        }
        .padding(24)
        .frame(width: 560)
    }

    private func open(_ family: BrowserExtension.Family) {
        BrowserExtension.open(family)
        dismiss()
    }

    private struct Feature: View {
        let symbol: String, title: String, detail: String
        var body: some View {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: symbol)
                    .font(.title3)
                    .foregroundStyle(.tint)
                    .frame(width: 28)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.headline)
                    Text(detail)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }
}

/// A gentle suggestion on the Overview until a browser extension has connected once.
struct BrowserExtensionTip: View {
    @AppStorage(Prefs.browserExtensionSeen) private var seen = false
    @AppStorage(Prefs.browserTipDismissed) private var dismissed = false
    @State private var showSheet = false

    var body: some View {
        if !seen && !dismissed {
            Section {
                HStack(spacing: 12) {
                    Image(systemName: "puzzlepiece.extension")
                        .font(.title2)
                        .foregroundStyle(.tint)
                        .frame(width: 32)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Add Mac n Droid to your browser").font(.headline)
                        Text("Control browser music and videos from your phone, and open pages and links on it.")
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("Learn More…") { showSheet = true }
                    Button {
                        dismissed = true
                    } label: {
                        Image(systemName: "xmark")
                    }
                    .buttonStyle(.borderless)
                    .help("Don't show this again. You can add it later in Settings → Sync → Browser.")
                }
                .padding(.vertical, 4)
            }
            .sheet(isPresented: $showSheet) { BrowserExtensionSheet() }
        }
    }
}
