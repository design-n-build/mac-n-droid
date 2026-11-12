import QuickLook
import SwiftUI

/// The phone's photos and videos: a grid, newest first, by album. Click to open in Quick Look; save originals
/// to Downloads. Nothing is copied until you open or save it.
struct PhotosView: View {
    @EnvironmentObject private var state: AppState
    @ObservedObject var gallery: GalleryModel
    @State private var selecting = false
    @State private var selection: Set<String> = []
    @AppStorage("photosTileSize") private var tile: Double = 120

    var body: some View {
        content
            .navigationTitle("Photos")
            .navigationSubtitle(subtitle)
            .toolbar { toolbar }
            .quickLookPreview($gallery.preview)
            .onAppear { if state.isConnected { gallery.loadIfNeeded() } }
            .onChange(of: state.isConnected) { _, on in if on { gallery.loadIfNeeded() } }
    }

    private var subtitle: String {
        guard state.isConnected, gallery.access != .none, gallery.access != .off, gallery.total > 0 else { return "" }
        let n = gallery.total
        return selecting && !selection.isEmpty ? "\(selection.count) selected" : "\(n) item\(n == 1 ? "" : "s")"
    }

    @ViewBuilder private var content: some View {
        if !state.isConnected {
            ContentUnavailableView("Phone not connected", systemImage: "iphone.slash",
                                   description: Text("Open Mac n Droid on your phone, on the same Wi-Fi."))
        } else if gallery.access == .off {
            ContentUnavailableView("Photos are turned off on your phone", systemImage: "photo.badge.exclamationmark",
                                   description: Text("To see them here, turn on Photos on Mac on the Home tab of Mac n Droid on \(state.phone?.name ?? "your phone")."))
        } else if gallery.access == .none {
            ContentUnavailableView {
                Label("See your phone's photos here", systemImage: "photo.on.rectangle.angled")
            } description: {
                Text("Allow Mac n Droid to use photos and videos on \(state.phone?.name ?? "your phone"). Photos are sent only to this Mac, only when you open them.")
            } actions: {
                Button("Ask on Phone") { gallery.askForAccess() }
                    .buttonStyle(.borderedProminent)
            }
        } else if gallery.items.isEmpty {
            if gallery.loading || gallery.access == .unknown {
                ProgressView().controlSize(.large).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ContentUnavailableView("No photos or videos", systemImage: "photo",
                                       description: Text(gallery.album == nil ? "" : "This album is empty."))
            }
        } else {
            VStack(spacing: 0) {
                if gallery.access == .partial || gallery.access == .photos {
                    HStack {
                        Label(gallery.access == .photos
                              ? "Videos aren't shared yet. Allow videos on your phone to see them here."
                              : "Showing only the photos you chose on your phone. To show all of them, allow all photos and videos.",
                              systemImage: "info.circle")
                            .font(.callout).foregroundStyle(.secondary)
                        Spacer()
                        Button(gallery.access == .photos ? "Allow Videos" : "Allow All") { gallery.askForAccess() }
                            .controlSize(.small)
                    }
                    .padding(.horizontal, 16).padding(.vertical, 8)
                    .background(.bar)
                }
                grid
            }
        }
    }

    private var grid: some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: tile, maximum: tile * 1.6), spacing: 3)], spacing: 3) {
                ForEach(gallery.items) { item in
                    PhotoTile(item: item, thumb: gallery.thumbs[item.id], loading: gallery.opening.contains(item.id),
                              selected: selection.contains(item.id))
                        .onAppear {
                            gallery.wantThumb(item.id)
                            if item.id == gallery.items.last?.id, gallery.hasMore { gallery.loadMore() }
                        }
                        .onTapGesture { tap(item) }
                        .contextMenu {
                            Button("Open") { gallery.open(item) }
                            Button("Save to Downloads") { gallery.save(selectionContaining(item)) }
                        }
                        .help("\(item.name) · \(item.date.formatted(date: .abbreviated, time: .shortened))")
                }
            }
            .padding(3)
            if gallery.loading && !gallery.items.isEmpty { ProgressView().padding() }
        }
    }

    private func tap(_ item: GalleryItem) {
        if selecting {
            if selection.contains(item.id) { selection.remove(item.id) } else { selection.insert(item.id) }
        } else {
            gallery.open(item)
        }
    }

    private func selectionContaining(_ item: GalleryItem) -> [GalleryItem] {
        guard selecting, selection.contains(item.id) else { return [item] }
        return gallery.items.filter { selection.contains($0.id) }
    }

    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .primaryAction) {
            Picker("Show", selection: $gallery.kind) {
                ForEach(GalleryModel.Kind.allCases) { Text($0.title).tag($0) }
            }
            .pickerStyle(.segmented)
            .disabled(!state.isConnected || gallery.access == .none || gallery.access == .off)

            Menu {
                Button { gallery.album = nil } label: {
                    if gallery.album == nil { Label("All Albums", systemImage: "checkmark") } else { Text("All Albums") }
                }
                Divider()
                ForEach(gallery.albums) { a in
                    Button { gallery.album = a.id } label: {
                        if gallery.album == a.id { Label("\(a.name) (\(a.count))", systemImage: "checkmark") }
                        else { Text("\(a.name) (\(a.count))") }
                    }
                }
            } label: {
                Label(gallery.albums.first { $0.id == gallery.album }?.name ?? "All Albums", systemImage: "rectangle.stack")
            }
            .help("Albums")
            .disabled(gallery.albums.isEmpty)

            Slider(value: $tile, in: 80...220) { Text("Size") }
                .frame(width: 90)
                .help("Thumbnail size")

            if selecting {
                Button("Save \(selection.count) to Downloads") {
                    gallery.save(gallery.items.filter { selection.contains($0.id) })
                    selection.removeAll(); selecting = false
                }
                .disabled(selection.isEmpty)
                Button("Done") { selection.removeAll(); selecting = false }
            } else {
                Button("Select") { selecting = true }
                    .disabled(gallery.items.isEmpty)
                Button { gallery.reload() } label: { Label("Refresh", systemImage: "arrow.clockwise") }
                    .help("Refresh")
                    .disabled(!state.isConnected)
            }
        }
    }
}

private struct PhotoTile: View {
    let item: GalleryItem
    let thumb: NSImage?
    let loading: Bool
    let selected: Bool

    var body: some View {
        Color(nsColor: .quaternaryLabelColor)
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                if let thumb {
                    Image(nsImage: thumb).resizable().scaledToFill()
                } else {
                    Image(systemName: item.isVideo ? "video" : "photo").foregroundStyle(.tertiary)
                }
            }
            .clipped()
            .overlay(alignment: .bottomTrailing) {
                if item.isVideo {
                    Text(Self.duration(item.duration))
                        .font(.caption2.weight(.semibold).monospacedDigit())
                        .foregroundStyle(.white)
                        .shadow(radius: 2)
                        .padding(5)
                }
            }
            .overlay {
                if loading { ProgressView().controlSize(.small).padding(8).background(.ultraThinMaterial, in: Circle()) }
            }
            .overlay(alignment: .topTrailing) {
                if selected {
                    Image(systemName: "checkmark.circle.fill")
                        .symbolRenderingMode(.palette)
                        .foregroundStyle(.white, Color.accentColor)
                        .font(.title3)
                        .padding(5)
                }
            }
            .overlay { if selected { Rectangle().strokeBorder(Color.accentColor, lineWidth: 3) } }
            .contentShape(Rectangle())
            .accessibilityLabel(item.isVideo ? "Video \(item.name)" : "Photo \(item.name)")
    }

    static func duration(_ t: TimeInterval) -> String {
        let s = Int(t.rounded())
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
    }
}
