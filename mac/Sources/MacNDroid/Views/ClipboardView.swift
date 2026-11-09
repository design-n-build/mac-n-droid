import SwiftUI

/// Clipboard history: the last 20 things copied on this Mac or the phone.
struct ClipboardView: View {
    @EnvironmentObject private var state: AppState
    @AppStorage(Prefs.syncClipboard) private var syncClipboard = true
    @State private var confirmClear = false

    var body: some View {
        Group {
            if state.clipHistory.isEmpty {
                ContentUnavailableView {
                    Label("Nothing Copied Yet", systemImage: "doc.on.clipboard")
                } description: {
                    Text(syncClipboard
                         ? "Text and images you copy on this Mac or on \(state.phone?.name ?? "your phone") appear here. The last 20 are kept."
                         : "Turn on clipboard sync to keep a history of what you copy on both devices.")
                } actions: {
                    if !syncClipboard { Button("Turn On Clipboard Sync") { syncClipboard = true } }
                }
            } else {
                List {
                    Section {
                        ForEach(state.clipHistory) { item in
                            ClipRow(item: item)
                        }
                    } footer: {
                        Text("Double-click to copy. The phone shows the same list in its Clipboard tab.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle("Clipboard")
        .toolbar {
            ToolbarItem {
                Button("Clear History", systemImage: "trash") { confirmClear = true }
                    .disabled(state.clipHistory.isEmpty)
                    .help("Clear clipboard history")
            }
        }
        .confirmationDialog("Clear clipboard history?", isPresented: $confirmClear) {
            Button("Clear History", role: .destructive) { state.clearClipHistory() }
        } message: {
            Text("This removes the list on this Mac and on your phone. What's on the clipboard now stays.")
        }
    }
}

private struct ClipRow: View {
    @EnvironmentObject private var state: AppState
    let item: ClipItem
    @State private var copied = false

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Group {
                if item.kind == .image, let img = state.clipImage(item) {
                    Image(nsImage: img).resizable().aspectRatio(contentMode: .fill)
                        .frame(width: 64, height: 48)
                        .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                } else {
                    Image(systemName: "text.alignleft")
                        .foregroundStyle(.secondary)
                        .frame(width: 20)
                }
            }
            VStack(alignment: .leading, spacing: 4) {
                if let text = item.text {
                    Text(text).lineLimit(3).textSelection(.enabled)
                } else {
                    Text("Image").foregroundStyle(.secondary)
                }
                HStack(spacing: 4) {
                    Image(systemName: item.fromPhone ? "smartphone" : "laptopcomputer")
                    Text(item.fromPhone ? (state.phone?.name ?? state.lastPhoneName ?? "Phone") : "This Mac")
                    Text("·")
                    RelativeTime(date: item.time)
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            Button(copied ? "Copied" : "Copy", systemImage: copied ? "checkmark" : "doc.on.doc") { copy() }
                .labelStyle(.iconOnly)
                .buttonStyle(.borderless)
                .help("Copy on this Mac")
            Button("Send to Phone", systemImage: "arrow.up.forward.app") { state.sendClipToPhone(item) }
                .labelStyle(.iconOnly)
                .buttonStyle(.borderless)
                .disabled(!state.isConnected)
                .help("Copy on \(state.phone?.name ?? "your phone")")
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .onTapGesture(count: 2) { copy() }
        .contextMenu {
            Button("Copy") { copy() }
            Button("Copy on \(state.phone?.name ?? "Phone")") { state.sendClipToPhone(item) }.disabled(!state.isConnected)
            if let text = item.text, let url = Handoff.findLink(text) {
                Button("Open Link on \(state.phone?.name ?? "Phone")") { state.sendLink(url) }.disabled(!state.isConnected)
            }
            Divider()
            Button("Remove from History") { state.removeClip(item) }
        }
    }

    private func copy() {
        state.useClip(item)
        copied = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { copied = false }
    }
}
