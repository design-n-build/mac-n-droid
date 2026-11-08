import QuickLook
import QuickLookThumbnailing
import SwiftUI

struct FilesView: View {
    @EnvironmentObject private var state: AppState
    @State private var preview: URL?
    @State private var tab: Transfer.Direction = .fromPhone

    private var received: [Transfer] { state.transfers.filter { $0.direction == .fromPhone } }
    private var sent: [Transfer] { state.transfers.filter { $0.direction == .toPhone } }
    private var shown: [Transfer] { tab == .fromPhone ? received : sent }

    private var previewable: [URL] {
        shown.filter { $0.state == .done }.compactMap(\.localURL)
    }

    var body: some View {
        VStack(spacing: 0) {
            DropZone()
                .padding([.horizontal, .top], 20)
            Picker("Show", selection: $tab) {
                Text(received.isEmpty ? "Received" : "Received (\(received.count))").tag(Transfer.Direction.fromPhone)
                Text(sent.isEmpty ? "Sent" : "Sent (\(sent.count))").tag(Transfer.Direction.toPhone)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .fixedSize()
            .padding(.vertical, 14)
            if shown.isEmpty {
                Spacer()
                ContentUnavailableView(
                    tab == .fromPhone ? "Nothing Received Yet" : "Nothing Sent Yet",
                    systemImage: tab == .fromPhone ? "tray.and.arrow.down" : "tray.and.arrow.up",
                    description: Text(tab == .fromPhone
                        ? "Use Share → Send to Mac on your phone. Files are saved to your Downloads folder."
                        : "Drop files above, or drag them onto Mac n Droid in the Dock."))
                Spacer()
            } else {
                List(shown) { t in
                    TransferRow(transfer: t) { preview = t.localURL }
                }
                .listStyle(.inset)
            }
        }
        .quickLookPreview($preview, in: previewable)
        .onChange(of: state.transfers.first?.id) { _, _ in
            if let newest = state.transfers.first { tab = newest.direction }
        }
        .navigationTitle("Files")
        .toolbar {
            ToolbarItemGroup {
                Button {
                    NSWorkspace.shared.open(FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0])
                } label: {
                    Label("Downloads Folder", systemImage: "folder")
                }
                .help("Open the Downloads folder, where received files are saved")
                Button {
                    state.clearFinishedTransfers()
                } label: {
                    Label("Clear List", systemImage: "list.bullet.rectangle")
                }
                .help("Remove finished items from this list (files are not deleted)")
                .disabled(state.transfers.allSatisfy { $0.state == .active })
            }
        }
        .toolbarRole(.editor)
        .labelStyle(.titleAndIcon)
    }
}

struct DropZone: View {
    @EnvironmentObject private var state: AppState

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: "arrow.up.doc")
                .font(.system(size: 34, weight: .light))
                .foregroundStyle(.tint)
            Text(state.isConnected ? "Drop files here to send to \(state.phone?.name ?? "your phone")" : "Connect your phone to send files")
                .font(.headline)
            Button("Choose Files…") { state.chooseFilesToSend() }
                .disabled(!state.isConnected)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 28)
        .background {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(.separator, style: StrokeStyle(lineWidth: 1.5, dash: [7, 5]))
        }
    }
}

struct TransferRow: View {
    let transfer: Transfer
    let onPreview: () -> Void

    private var canOpen: Bool { transfer.state == .done && transfer.localURL != nil }

    var body: some View {
        HStack(spacing: 14) {
            Button(action: onPreview) {
                FileThumbnail(url: transfer.localURL, ready: transfer.state == .done, size: 52)
            }
            .buttonStyle(.plain)
            .disabled(!canOpen)
            .help("Quick Look")

            VStack(alignment: .leading, spacing: 4) {
                Text(transfer.name).font(.body.weight(.medium)).lineLimit(1).truncationMode(.middle)
                switch transfer.state {
                case .active:
                    ProgressView(value: transfer.progress).progressViewStyle(.linear)
                    Text("\(transfer.direction == .toPhone ? "Sending" : "Receiving") · \(transfer.bytes.fileSize) of \(transfer.size.fileSize)")
                        .font(.caption).foregroundStyle(.secondary)
                case .done:
                    Text(subtitle).font(.caption).foregroundStyle(.secondary)
                case .failed:
                    Text("Transfer failed").font(.caption).foregroundStyle(.red)
                }
            }
            Spacer()
            if canOpen, let url = transfer.localURL {
                Button("Open") { NSWorkspace.shared.open(url) }
                Button("Show in Finder") { NSWorkspace.shared.activateFileViewerSelecting([url]) }
            }
        }
        .controlSize(.small)
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .onTapGesture(count: 2) { if canOpen, let url = transfer.localURL { NSWorkspace.shared.open(url) } }
        .contextMenu {
            if canOpen, let url = transfer.localURL {
                Button("Open") { NSWorkspace.shared.open(url) }
                Button("Quick Look", action: onPreview)
                Button("Show in Finder") { NSWorkspace.shared.activateFileViewerSelecting([url]) }
            }
        }
    }

    private var subtitle: String {
        let verb = transfer.direction == .toPhone ? "Sent to" : "Received from"
        return "\(verb) \(transfer.peer) · \(transfer.size.fileSize) · \(transfer.date.formatted(date: .omitted, time: .shortened))"
    }
}

/// Quick Look thumbnail (photo, video frame, PDF page…) with the Finder icon as fallback.
struct FileThumbnail: View {
    let url: URL?
    var ready = true
    let size: CGFloat
    @State private var image: NSImage?
    @State private var isIcon = true

    var body: some View {
        ZStack {
            if let image {
                Image(nsImage: image)
                    .resizable()
                    .aspectRatio(contentMode: isIcon ? .fit : .fill)
            } else {
                Image(systemName: "doc").font(.title2).foregroundStyle(.secondary)
            }
        }
        .frame(width: size, height: size)
        .background(isIcon ? Color.clear : Color.black.opacity(0.05))
        .clipShape(RoundedRectangle(cornerRadius: isIcon ? 0 : 8, style: .continuous))
        .overlay {
            if !isIcon {
                RoundedRectangle(cornerRadius: 8, style: .continuous).strokeBorder(.separator, lineWidth: 0.5)
            }
        }
        .task(id: "\(url?.path ?? "")|\(ready)") { await load() }
    }

    private func load() async {
        guard let url else { return }
        if image == nil {
            image = NSWorkspace.shared.icon(forFile: url.path)
            isIcon = true
        }
        guard ready else { return }
        let request = QLThumbnailGenerator.Request(
            fileAt: url, size: CGSize(width: size, height: size),
            scale: NSScreen.main?.backingScaleFactor ?? 2, representationTypes: .thumbnail)
        if let rep = try? await QLThumbnailGenerator.shared.generateBestRepresentation(for: request) {
            image = rep.nsImage
            isIcon = false
        }
    }
}
