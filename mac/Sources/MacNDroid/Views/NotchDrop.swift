import AppKit
import SwiftUI
import UniformTypeIdentifiers

/// Drag files (or a link) up to the notch to send them to the phone.
///
/// While you drag something from Finder, a browser or any app, moving it near the top center of the
/// screen opens a drop target that grows out of the notch. Let go there to send. It only exists
/// during a drag, so it never gets in the way of clicks.
///
/// Watching the mouse during a drag uses a global mouse monitor, which needs no permission.
final class NotchDropController {
    static let shared = NotchDropController()
    weak var state: AppState?

    let model = NotchDropModel()
    private var panel: TopPanel?
    private var monitors: [Any] = []
    private var dragStartCount = 0
    private var hideWork: DispatchWorkItem?
    private static let panelSize = CGSize(width: 560, height: 260)

    func start() {
        guard monitors.isEmpty else { return }
        let drag = NSPasteboard(name: .drag)
        if let m = NSEvent.addGlobalMonitorForEvents(matching: .leftMouseDown, handler: { [weak self] _ in
            self?.dragStartCount = drag.changeCount
        }) { monitors.append(m) }
        if let m = NSEvent.addGlobalMonitorForEvents(matching: .leftMouseDragged, handler: { [weak self] _ in
            self?.dragged()
        }) { monitors.append(m) }
        if let m = NSEvent.addGlobalMonitorForEvents(matching: .leftMouseUp, handler: { [weak self] _ in
            self?.mouseUp()
        }) { monitors.append(m) }
        model.onDrop = { [weak self] urls, link in self?.dropped(urls, link) }
    }

    private var isShowing: Bool { panel?.isVisible == true }

    private func dragged() {
        guard Prefs.bool(Prefs.dropOnNotch), let state, state.isConnected else { return }
        let pb = NSPasteboard(name: .drag)
        // A new drag session changes the drag pasteboard; plain mouse drags (selecting text, moving
        // windows) don't.
        guard pb.changeCount != dragStartCount else { return }
        let types = pb.types ?? []
        let hasFiles = types.contains(.fileURL)
        let hasLink = types.contains(.URL) || types.contains(NSPasteboard.PasteboardType("public.url"))
        guard hasFiles || hasLink else { return }

        let g = NotchGeometry.current()
        let f = g.screen.frame
        let p = NSEvent.mouseLocation
        // Opens while you're still well below the menu bar, so you never have to push into the top
        // edge of the screen (where macOS opens Mission Control during a drag).
        let near = f.contains(p) && p.y > f.maxY - 320 && abs(p.x - f.midX) < 380
        if near {
            show(g, files: hasFiles)
        } else if isShowing, p.y < f.maxY - 420 || abs(p.x - f.midX) > 520 {
            hide()
        }
    }

    private func mouseUp() {
        guard isShowing else { return }
        // The drop (if any) arrives around the same moment; give it a beat before collapsing.
        hideWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, !self.model.sending else { return }
            self.hide()
        }
        hideWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25, execute: work)
    }

    private func show(_ g: NotchGeometry, files: Bool) {
        hideWork?.cancel()
        model.phoneName = state?.phone?.name ?? "phone"
        model.isLink = !files
        guard !isShowing else { return }
        let p = ensurePanel()
        let f = g.screen.frame
        p.setFrame(NSRect(x: f.midX - Self.panelSize.width / 2, y: f.maxY - Self.panelSize.height,
                          width: Self.panelSize.width, height: Self.panelSize.height), display: false)
        let live = LiveBarController.shared
        model.geometry = g
        model.rest = live.isShowing ? live.model.collapsedSize : nil
        model.expanded = false
        model.sending = false
        model.targeted = false
        p.orderFrontRegardless()
        // Grow on the next frame, once the window is on screen, so the first frames aren't lost.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.016) {
            withAnimation(.smooth(duration: 0.32)) { self.model.expanded = true }
        }
    }

    func hide() {
        hideWork?.cancel()
        guard isShowing else { return }
        withAnimation(.smooth(duration: 0.26)) {
            model.expanded = false
            model.targeted = false
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
            guard let self, !self.model.expanded else { return }
            self.panel?.orderOut(nil)
            self.model.sending = false
        }
    }

    private func dropped(_ urls: [URL], _ link: URL?) {
        guard let state else { return }
        let files = urls.filter { !$0.hasDirectoryPath }
        withAnimation(.smooth(duration: 0.25)) {
            model.sending = true
            model.sentText = if !files.isEmpty {
                files.count == 1 ? "Sending \(files[0].lastPathComponent)" : "Sending \(files.count) files"
            } else if link != nil {
                "Opening on \(model.phoneName)"
            } else {
                "Folders can't be sent yet"
            }
        }
        if !files.isEmpty { state.sendFiles(files) } else if let link { state.sendLink(link) }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.1) { [weak self] in
            self?.model.sending = false
            self?.hide()
        }
    }

    private func ensurePanel() -> TopPanel {
        if let panel { return panel }
        let p = TopPanel(contentRect: NSRect(origin: .zero, size: Self.panelSize),
                         styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        p.isFloatingPanel = true
        p.level = .statusBar + 2
        p.backgroundColor = .clear
        p.isOpaque = false
        p.hasShadow = false
        p.hidesOnDeactivate = false
        p.isMovable = false
        p.collectionBehavior = [.canJoinAllSpaces, .canJoinAllApplications, .stationary, .fullScreenAuxiliary, .ignoresCycle]
        let host = NSHostingView(rootView: NotchDropView(model: model))
        host.frame = NSRect(origin: .zero, size: Self.panelSize)
        host.autoresizingMask = [.width, .height]
        p.contentView = host
        panel = p
        return p
    }
}

final class NotchDropModel: ObservableObject {
    @Published var expanded = false
    @Published var targeted = false
    @Published var sending = false
    @Published var sentText = ""
    @Published var phoneName = "phone"
    @Published var isLink = false
    var geometry = NotchGeometry.current()
    var rest: CGSize?
    var onDrop: ([URL], URL?) -> Void = { _, _ in }

    private var barHeight: CGFloat { geometry.hasNotch ? geometry.notchSize.height : geometry.barHeight }

    var size: CGSize {
        guard expanded else { return rest ?? (geometry.hasNotch ? geometry.notchSize : CGSize(width: 160, height: 0)) }
        let w: CGFloat = targeted ? 440 : 410
        return CGSize(width: w, height: barHeight + (targeted ? 150 : 140))
    }
}

struct NotchDropView: View {
    @ObservedObject var model: NotchDropModel

    var body: some View {
        let size = model.size
        let shape = NotchShape(topRadius: model.expanded ? 22 : 8, bottomRadius: model.expanded ? 30 : 12)
        VStack(spacing: 0) {
            ZStack(alignment: .top) {
                shape.fill(Color.black)
                // Content is clipped to the shape, so it never shows outside it while growing.
                content
                    .frame(width: size.width, height: size.height)
                    .opacity(model.expanded ? 1 : 0)
            }
            .frame(width: size.width, height: size.height)
            .clipShape(shape)
            .contentShape(shape)
            .onDrop(of: [.fileURL, .url], isTargeted: Binding(
                get: { model.targeted },
                set: { t in withAnimation(.smooth(duration: 0.2)) { model.targeted = t } })) { providers in
                load(providers)
                return true
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .environment(\.colorScheme, .dark)
    }

    private var content: some View {
        let bar = model.geometry.hasNotch ? model.geometry.notchSize.height : model.geometry.barHeight
        return VStack(spacing: 8) {
            Spacer(minLength: bar + 6)
            ZStack {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(style: StrokeStyle(lineWidth: 1.5, dash: model.sending ? [] : [5, 4]))
                    .foregroundStyle(model.targeted || model.sending ? Color.accentColor : .white.opacity(0.35))
                    .background(RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(Color.accentColor.opacity(model.targeted ? 0.18 : 0)))
                HStack(spacing: 10) {
                    Image(systemName: model.sending ? "checkmark.circle.fill"
                          : (model.isLink ? "safari" : (model.targeted ? "arrow.down.circle.fill" : "smartphone")))
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(model.targeted || model.sending ? Color.accentColor : .white)
                        .contentTransition(.symbolEffect(.replace))
                    Text(model.sending ? model.sentText
                         : (model.isLink ? "Drop to open on \(model.phoneName)" : "Drop to send to \(model.phoneName)"))
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                }
            }
            .padding(.horizontal, 30)
            .padding(.bottom, 14)
        }
    }

    private func load(_ providers: [NSItemProvider]) {
        let group = DispatchGroup()
        var files: [URL] = []
        var link: URL?
        let lock = NSLock()
        for p in providers {
            if p.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
                group.enter()
                _ = p.loadObject(ofClass: URL.self) { url, _ in
                    if let url, url.isFileURL { lock.lock(); files.append(url); lock.unlock() }
                    group.leave()
                }
            } else if p.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
                group.enter()
                _ = p.loadObject(ofClass: URL.self) { url, _ in
                    if let url, Handoff.isWebLink(url) { lock.lock(); link = link ?? url; lock.unlock() }
                    group.leave()
                }
            }
        }
        group.notify(queue: .main) { model.onDrop(files, link) }
    }
}
