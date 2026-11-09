import AppKit
import SwiftUI

// MARK: - Shared top-of-screen panel plumbing

/// A borderless panel that can take keyboard focus (for Reply) without activating the app.
final class TopPanel: NSPanel {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }
}

/// Where the notch is, on the built-in display (or the main screen's menu bar if there's no notch).
struct NotchGeometry {
    let screen: NSScreen
    let notchSize: CGSize      // height 0 when there's no notch
    let barHeight: CGFloat     // menu bar height

    var hasNotch: Bool { notchSize.height > 0 }

    static func current() -> NotchGeometry {
        let screen = NSScreen.screens.first { $0.safeAreaInsets.top > 0 } ?? NSScreen.main ?? NSScreen.screens[0]
        let f = screen.frame
        let h = screen.safeAreaInsets.top
        if h > 0, let l = screen.auxiliaryTopLeftArea, let r = screen.auxiliaryTopRightArea {
            return NotchGeometry(screen: screen, notchSize: CGSize(width: f.width - l.width - r.width, height: h), barHeight: h)
        }
        return NotchGeometry(screen: screen, notchSize: CGSize(width: 0, height: 0),
                             barHeight: max(24, f.maxY - screen.visibleFrame.maxY))
    }
}

/// Hosts a SwiftUI view in a transparent panel pinned to the top center of the screen. Clicks pass
/// through everywhere except the visible shape (`hitSize`), so the panel never blocks the menu bar
/// or windows underneath.
final class TopPanelHost<Content: View> {
    private(set) var panel: TopPanel?
    private var tracker: Timer?
    private let size: CGSize
    private let level: NSWindow.Level
    private let makeContent: () -> Content
    var hitSize: () -> CGSize = { .zero }
    var onHoverChange: (Bool) -> Void = { _ in }
    private var inside = false

    init(size: CGSize, level: NSWindow.Level, content: @escaping () -> Content) {
        self.size = size
        self.level = level
        self.makeContent = content
    }

    var isVisible: Bool { panel?.isVisible == true }

    func show(geometry g: NotchGeometry, fade: Bool = false) {
        let p = ensurePanel()
        let f = g.screen.frame
        p.setFrame(NSRect(x: f.midX - size.width / 2, y: f.maxY - size.height, width: size.width, height: size.height), display: false)
        p.ignoresMouseEvents = true
        if fade {
            p.alphaValue = 0
            p.orderFrontRegardless()
            NSAnimationContext.runAnimationGroup { ctx in
                ctx.duration = 0.18
                p.animator().alphaValue = 1
            }
        } else {
            p.alphaValue = 1
            p.orderFrontRegardless()
        }
        startTracking()
    }

    func hide() {
        stopTracking()
        panel?.orderOut(nil)
        if inside { inside = false; onHoverChange(false) }
    }

    func makeKey() { panel?.makeKey() }

    /// Puts the text cursor in the first text field, unless one is already being typed in.
    func focusTextInput() {
        guard let p = panel, let root = p.contentView else { return }
        if !p.isKeyWindow { p.makeKey() }
        if let editing = p.firstResponder as? NSTextView, editing.isEditable { return }
        func find(_ v: NSView) -> NSView? {
            if let t = v as? NSTextField, t.isEditable { return t }
            if let t = v as? NSTextView, t.isEditable { return t }
            for sub in v.subviews { if let f = find(sub) { return f } }
            return nil
        }
        if let field = find(root) { p.makeFirstResponder(field) }
    }

    private func ensurePanel() -> TopPanel {
        if let panel { return panel }
        let p = TopPanel(contentRect: NSRect(origin: .zero, size: size),
                         styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        p.isFloatingPanel = true
        p.level = level
        p.backgroundColor = .clear
        p.isOpaque = false
        p.hasShadow = false
        p.hidesOnDeactivate = false
        p.isMovable = false
        p.becomesKeyOnlyIfNeeded = true
        // canJoinAllApplications: Stage Manager otherwise hides the notch whenever another app's stage is up.
        p.collectionBehavior = [.canJoinAllSpaces, .canJoinAllApplications, .stationary, .fullScreenAuxiliary, .ignoresCycle]
        let host = NSHostingView(rootView: makeContent())
        host.frame = NSRect(origin: .zero, size: size)
        host.autoresizingMask = [.width, .height]
        p.contentView = host
        panel = p
        return p
    }

    /// 30 Hz mouse check: only the visible shape takes clicks/hover.
    private func startTracking() {
        guard tracker == nil else { return }
        let t = Timer(timeInterval: 1.0 / 30, repeats: true) { [weak self] _ in self?.track() }
        RunLoop.main.add(t, forMode: .common)
        tracker = t
    }

    private func stopTracking() {
        tracker?.invalidate()
        tracker = nil
    }

    private func track() {
        guard let p = panel else { return }
        let s = hitSize()
        let rect = NSRect(x: p.frame.midX - s.width / 2, y: p.frame.maxY - s.height, width: s.width, height: s.height)
        let now = rect.contains(NSEvent.mouseLocation)
        if p.ignoresMouseEvents == now { p.ignoresMouseEvents = !now }
        if now != inside { inside = now; onHoverChange(now) }
    }
}

private let smooth = Animation.smooth(duration: 0.32)

// MARK: - Notifications in the notch

/// Phone notifications that grow out of the notch in the chosen style (Small, Compact or Large).
///
/// Bursts: each notification stays up for at least `minDwell` before the next replaces it, and nothing
/// replaces what you're reading (hovering) or replying to. Hovering shows a pager, and a swipe or
/// scroll on the notch goes through recent notifications.
final class NotchController {
    static let shared = NotchController()
    weak var state: AppState?

    private let model = NotchModel()
    private lazy var host = TopPanelHost(size: CGSize(width: 560, height: 220), level: .statusBar + 1) { [model] in
        NotchView(model: model)
    }
    private var hideWork: DispatchWorkItem?
    /// Bumped by every present/hide so a stale collapse can never hide something newer.
    private var generation = 0
    private var shownAt = Date.distantPast
    private var pending: (PhoneNotification, NSImage?)?
    private var pendingWork: DispatchWorkItem?
    private var scrollMonitor: Any?
    private var scrollAccum: CGFloat = 0
    private var pagedThisGesture = false
    private var lastWheelPage = Date.distantPast
    private static let minDwell: TimeInterval = 1.4

    init() {
        host.hitSize = { [weak self] in
            guard let self, self.model.expanded else { return .zero }
            return self.model.visibleSize
        }
        host.onHoverChange = { [weak self] hovering in
            guard let self else { return }
            self.updatePager()
            withAnimation(.easeOut(duration: 0.15)) { self.model.hovering = hovering }
            if hovering {
                self.hideWork?.cancel()
            } else if !self.model.replying {
                if let (n, icon) = self.pending { self.display(n, icon: icon) } else { self.scheduleHide(after: 2.5) }
            }
        }
        model.onOpen = { [weak self] in
            self?.hide()
            self?.state?.showNotificationsWindow()
        }
        model.onCopy = { [weak self] code in
            self?.state?.copyToMac(code)
            self?.scheduleHide(after: 1.2)
        }
        model.onAction = { [weak self] key, index in
            self?.state?.performAction(key: key, index: index, text: nil)
            self?.hide()
        }
        model.onBeginReply = { [weak self] in
            self?.hideWork?.cancel()
            self?.host.makeKey()
            // The field appears with the grow animation; put the cursor in it as soon as it exists.
            for delay in [0.05, 0.18, 0.4] {
                DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                    guard let self, self.model.replying else { return }
                    self.host.focusTextInput()
                }
            }
        }
        model.onReply = { [weak self] key, index, text in
            self?.state?.performAction(key: key, index: index, text: text)
            self?.flash(symbol: "arrowshape.turn.up.left.fill", text: "Reply sent")
        }
        model.onClose = { [weak self] in self?.hide() }
        model.onTurnOff = { [weak self] in
            NotchStyle.current = .off
            self?.hide()
        }
        model.onPage = { [weak self] delta in self?.page(delta) }
    }

    /// True while a notification panel is on screen (the live bar appears under it without fading).
    var isCovering: Bool { host.isVisible }

    private var current: PhoneNotification? {
        if case .notification(let n, _) = model.content { return n }
        return nil
    }

    func show(_ n: PhoneNotification, icon: NSImage?) {
        let callPanel = n.isCall && Prefs.bool(Prefs.callPanel)
        guard callPanel || NotchStyle.isOn else { return }
        if !n.isCall, model.expanded, let cur = current {
            // Reading, replying or a call on screen: don't yank it away. The pager shows there's more.
            if model.hovering || model.replying || (cur.isCall && cur.id != n.id) {
                pending = model.hovering || model.replying ? (n, icon) : nil
                updatePager()
                return
            }
            // Burst: let the current one be read first; the newest waiting one comes next.
            let wait = shownAt.addingTimeInterval(Self.minDwell).timeIntervalSinceNow
            if wait > 0, cur.id != n.id {
                pending = (n, icon)
                pendingWork?.cancel()
                let work = DispatchWorkItem { [weak self] in
                    guard let self, let (n, icon) = self.pending, !self.model.hovering, !self.model.replying else { return }
                    self.display(n, icon: icon)
                }
                pendingWork = work
                DispatchQueue.main.asyncAfter(deadline: .now() + wait, execute: work)
                updatePager()
                return
            }
        }
        display(n, icon: icon)
    }

    private func display(_ n: PhoneNotification, icon: NSImage?, direction: CGFloat = 1) {
        pending = nil
        pendingWork?.cancel()
        let callPanel = n.isCall && Prefs.bool(Prefs.callPanel)
        let style: NotchStyle = callPanel ? .large : NotchStyle.current
        guard style != .off else { return }
        present { m in
            m.style = style
            m.replying = false
            m.replyText = ""
            m.direction = direction
            m.content = .notification(n, icon)
        }
        shownAt = Date()
        updatePager()
        let ringing = n.isCall && n.actions.contains { $0.title.lowercased().contains("answer") }
        scheduleHide(after: ringing ? 60 : (style == .small ? 4 : 6))
    }

    /// A short status pill, e.g. "Image copied from Pixel 8".
    func flash(symbol: String, text: String) {
        if model.expanded, model.hovering || model.replying || current?.isCall == true { return }
        present { m in
            m.style = .small
            m.replying = false
            m.direction = 1
            m.content = .status(symbol, text)
        }
        model.page = nil
        scheduleHide(after: 2.2)
    }

    func remove(_ key: String) {
        if pending?.0.id == key { pending = nil }
        if current?.id == key { hide() } else { updatePager() }
    }

    // MARK: Browsing

    /// Newest first, the same list as the Notifications page.
    private var browsable: [PhoneNotification] { state?.notifications ?? [] }

    private func updatePager() {
        guard let cur = current, !cur.isCall, model.style != .small else { model.page = nil; return }
        let list = browsable
        guard list.count > 1, let i = list.firstIndex(where: { $0.id == cur.id }) else { model.page = nil; return }
        model.page = NotchModel.Page(index: i, total: list.count)
    }

    /// +1 = older, -1 = newer.
    private func page(_ delta: Int) {
        guard let cur = current, !model.replying else { return }
        let list = browsable
        guard let i = list.firstIndex(where: { $0.id == cur.id }) else { return }
        let j = i + delta
        guard list.indices.contains(j) else { return }
        let n = list[j]
        if pending?.0.id == n.id { pending = nil }
        withAnimation(smooth) {
            model.direction = CGFloat(delta)
            model.copied = false
            model.content = .notification(n, state?.icons[n.pkg])
        }
        shownAt = Date()
        updatePager()
        if !model.hovering { scheduleHide(after: 6) }
    }

    /// Two-finger swipe (either direction) or a mouse wheel over the notch pages through notifications.
    private func installScrollMonitor() {
        guard scrollMonitor == nil else { return }
        scrollMonitor = NSEvent.addLocalMonitorForEvents(matching: .scrollWheel) { [weak self] e in
            guard let self, let p = self.host.panel, e.window === p, self.model.page != nil else { return e }
            if e.hasPreciseScrollingDeltas {
                if e.phase == .began { self.scrollAccum = 0; self.pagedThisGesture = false }
                guard e.momentumPhase == [], !self.pagedThisGesture else { return nil }
                let d = abs(e.scrollingDeltaX) > abs(e.scrollingDeltaY) ? -e.scrollingDeltaX : -e.scrollingDeltaY
                self.scrollAccum += d
                if abs(self.scrollAccum) > 36 {
                    self.pagedThisGesture = true
                    self.page(self.scrollAccum > 0 ? 1 : -1)
                }
            } else if abs(e.deltaY) > 0.1, Date().timeIntervalSince(self.lastWheelPage) > 0.3 {
                self.lastWheelPage = Date()
                self.page(e.deltaY < 0 ? 1 : -1)
            }
            return nil
        }
    }

    // MARK: Showing and hiding

    func hide() {
        hideWork?.cancel()
        pendingWork?.cancel()
        pending = nil
        guard host.isVisible else { return }
        generation += 1
        let gen = generation
        let live = LiveBarController.shared
        // Shrink back into the live activity's shape (or the bare notch) so the hand-off is seamless.
        model.rest = live.wouldShow ? live.model.collapsedSize : nil
        withAnimation(.smooth(duration: 0.28)) { model.expanded = false }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.32) { [weak self] in
            guard let self, self.generation == gen else { return }
            live.setSuspended(false)
            // Let the live bar draw first, then remove the notification panel on top of it.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
                guard self.generation == gen else { return }
                self.host.hide()
                self.model.content = nil
                self.model.replying = false
                self.model.page = nil
            }
        }
    }

    private func present(_ update: @escaping (NotchModel) -> Void) {
        generation += 1
        let gen = generation
        let g = NotchGeometry.current()
        model.geometry = g
        installScrollMonitor()
        let live = LiveBarController.shared
        if model.expanded {
            withAnimation(smooth) { update(model) }
        } else {
            // Start from exactly what's on screen: the live activity (if any) or the bare notch.
            if !host.isVisible { model.rest = live.isShowing ? live.model.collapsedSize : nil }
            update(model)
            if !host.isVisible { host.show(geometry: g) }
            DispatchQueue.main.async {
                guard self.generation == gen else { return }
                live.setSuspended(true)
                withAnimation(smooth) { self.model.expanded = true }
            }
        }
    }

    private func scheduleHide(after seconds: TimeInterval) {
        hideWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, !self.model.hovering, !self.model.replying else { return }
            self.hide()
        }
        hideWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: work)
    }
}

final class NotchModel: ObservableObject {
    enum Content {
        case notification(PhoneNotification, NSImage?)
        case status(String, String)

        var id: String {
            switch self {
            case .notification(let n, _): "n:" + n.id
            case .status(_, let t): "s:" + t
            }
        }
    }

    @Published var content: Content?
    @Published var style: NotchStyle = .compact
    @Published var expanded = false
    @Published var hovering = false
    @Published var copied = false
    @Published var replying = false
    @Published var replyText = ""
    struct Page: Equatable { let index: Int; let total: Int }
    /// Position in recent notifications, shown while hovering (nil when there's nothing to page through).
    @Published var page: Page?
    /// Which way the last change went (+1 older / -1 newer), for the slide direction.
    var direction: CGFloat = 1
    var onPage: (Int) -> Void = { _ in }
    var geometry = NotchGeometry.current()
    /// Resting shape to grow from / shrink into: the live activity's size while one is showing.
    var rest: CGSize?

    var onOpen: () -> Void = {}
    var onCopy: (String) -> Void = { _ in }
    var onAction: (String, Int) -> Void = { _, _ in }
    var onBeginReply: () -> Void = {}
    var onReply: (String, Int, String) -> Void = { _, _, _ in }
    var onClose: () -> Void = {}
    var onTurnOff: () -> Void = {}

    var notchSize: CGSize { geometry.hasNotch ? geometry.notchSize : CGSize(width: 0, height: 0) }
    var barHeight: CGFloat { geometry.hasNotch ? geometry.notchSize.height : geometry.barHeight }

    /// Collapsed: exactly the hardware notch (invisible against it).
    var collapsedSize: CGSize { rest ?? (geometry.hasNotch ? geometry.notchSize : CGSize(width: 120, height: 0)) }

    static let smallEar: CGFloat = 150

    var expandedSize: CGSize {
        switch (style, content) {
        case (_, nil): return collapsedSize
        case (.small, _), (.off, _):
            return geometry.hasNotch
                ? CGSize(width: notchSize.width + Self.smallEar * 2, height: barHeight)
                : CGSize(width: 380, height: barHeight)
        case (.compact, _):
            return CGSize(width: max(notchSize.width + 140, 380), height: barHeight + 62)
        case (.large, .notification(let n, _)):
            let extra: CGFloat = replying ? 150 : (hasButtons(n) ? 112 : 78)
            return CGSize(width: 480, height: barHeight + extra)
        case (.large, .status):
            return CGSize(width: max(notchSize.width + 160, 360), height: barHeight + 44)
        }
    }

    var visibleSize: CGSize { expanded ? expandedSize : collapsedSize }

    func buttonActions(_ n: PhoneNotification) -> [NotifAction] {
        Array(n.actions.filter { !$0.isReply }.prefix(2))
    }

    func hasButtons(_ n: PhoneNotification) -> Bool {
        n.otp != nil || !buttonActions(n).isEmpty || n.actions.contains(where: \.isReply)
    }
}

struct NotchView: View {
    @ObservedObject var model: NotchModel
    @FocusState private var replyFocused: Bool

    private var isSmall: Bool { model.style == .small || model.style == .off }

    var body: some View {
        let size = model.visibleSize
        let radii = shapeRadii
        VStack(spacing: 0) {
            ZStack(alignment: .top) {
                NotchShape(topRadius: radii.top, bottomRadius: radii.bottom)
                    .fill(Color.black)
                    .shadow(color: .black.opacity(model.expanded && !isSmall ? 0.35 : 0), radius: 14, y: 6)
                if model.expanded, let content = model.content {
                    ZStack(alignment: .top) {
                        // Swapping one notification for another crossfades with a short slide, in
                        // place, instead of collapsing and growing again.
                        Group {
                            if isSmall { smallBody(content) } else { dropBody(content) }
                        }
                        .id(content.id)
                        .transition(.asymmetric(
                            insertion: .opacity.combined(with: .offset(y: 8 * model.direction)),
                            removal: .opacity.combined(with: .offset(y: -6 * model.direction))))
                    }
                    .transition(.opacity.animation(.easeOut(duration: 0.18).delay(0.08)))
                }
            }
            .frame(width: size.width, height: size.height)
            .contentShape(NotchShape(topRadius: radii.top, bottomRadius: radii.bottom))
            .contextMenu {
                Button("Close") { model.onClose() }
                Divider()
                Button("Stop Showing Notifications in the Notch") { model.onTurnOff() }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .environment(\.colorScheme, .dark)
    }

    private var shapeRadii: (top: CGFloat, bottom: CGFloat) {
        guard model.expanded else {
            // Match the live bar's corners when resting on it.
            if model.rest != nil { return model.geometry.hasNotch ? (8, 12) : (0, model.barHeight / 2) }
            return (6, 10)
        }
        if isSmall { return model.geometry.hasNotch ? (8, 12) : (0, model.barHeight / 2) }
        return (22, 28)
    }

    // MARK: Small: content sits in the menu bar on either side of the notch.

    @ViewBuilder
    private func smallBody(_ content: NotchModel.Content) -> some View {
        let (icon, symbol, left, right): (NSImage?, String?, String, String) = {
            switch content {
            case .notification(let n, let img): return (img, n.isCall ? "phone.fill" : nil, n.app, n.title.isEmpty ? n.text : n.title)
            case .status(let s, let t): return (nil, s, "", t)
            }
        }()
        HStack(spacing: 0) {
            HStack(spacing: 6) {
                if let icon {
                    Image(nsImage: icon).resizable().interpolation(.high).frame(width: 18, height: 18)
                } else if let symbol {
                    Image(systemName: symbol).font(.system(size: 12, weight: .semibold)).foregroundStyle(.green)
                }
                if model.geometry.hasNotch {
                    Text(left).font(.system(size: 12, weight: .medium)).foregroundStyle(.white.opacity(0.75)).lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .padding(.leading, 14)
            .frame(width: model.geometry.hasNotch ? NotchModel.smallEar : nil)
            if model.geometry.hasNotch { Color.clear.frame(width: model.notchSize.width) }
            HStack {
                Text(right).font(.system(size: 12, weight: .semibold)).foregroundStyle(.white).lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.leading, model.geometry.hasNotch ? 10 : 0)
            .padding(.trailing, 14)
            .frame(width: model.geometry.hasNotch ? NotchModel.smallEar : nil)
        }
        .frame(height: model.barHeight)
        .contentShape(Rectangle())
        .onTapGesture { if case .notification = content { model.onOpen() } }
    }

    // MARK: Compact / Large: drops down from the notch.

    @ViewBuilder
    private func dropBody(_ content: NotchModel.Content) -> some View {
        Group {
            switch content {
            case .notification(let n, let icon): notificationBody(n, icon: icon)
            case .status(let symbol, let text): statusBody(symbol, text)
            }
        }
        .padding(.top, model.barHeight + (model.geometry.hasNotch ? 6 : 10))
        .padding(.horizontal, 40)
    }

    @ViewBuilder
    private func notificationBody(_ n: PhoneNotification, icon: NSImage?) -> some View {
        let large = model.style == .large
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 12) {
                AppIcon(image: icon, isCall: n.isCall)
                    .frame(width: large ? 38 : 30, height: large ? 38 : 30)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(n.app).font(.caption.weight(.medium)).foregroundStyle(.white.opacity(0.6))
                        Spacer(minLength: 0)
                        if model.hovering, let page = model.page, !model.replying {
                            NotchPager(page: page) { model.onPage($0) }
                        } else {
                            Text(n.isCall ? "Incoming call" : Self.age(n.time))
                                .font(.caption).foregroundStyle(.white.opacity(0.45))
                        }
                    }
                    Text(n.title.isEmpty ? n.app : n.title)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                    if !n.text.isEmpty {
                        Text(n.text)
                            .font(.system(size: 13))
                            .foregroundStyle(.white.opacity(0.78))
                            .lineLimit(large ? 2 : 1)
                    }
                }
            }
            .contentShape(Rectangle())
            .onTapGesture { model.onOpen() }

            if large && model.replying, let reply = n.actions.first(where: \.isReply) {
                replyField(n, reply)
            } else if large && model.hasButtons(n) {
                buttons(n)
            }
        }
    }

    static func age(_ t: Date) -> String {
        let s = -t.timeIntervalSinceNow
        if s < 60 { return "now" }
        if s < 3600 { return "\(Int(s / 60))m ago" }
        if s < 86400 { return "\(Int(s / 3600))h ago" }
        return t.formatted(.dateTime.month(.abbreviated).day())
    }

    private func buttons(_ n: PhoneNotification) -> some View {
        HStack(spacing: 8) {
            if let otp = n.otp {
                NotchButton(title: model.copied ? "Copied" : "Copy \(otp)",
                            symbol: model.copied ? "checkmark" : "doc.on.doc", prominent: true) {
                    model.copied = true
                    model.onCopy(otp)
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { model.copied = false }
                }
            }
            if n.actions.contains(where: \.isReply) {
                NotchButton(title: "Reply", symbol: "arrowshape.turn.up.left.fill") {
                    withAnimation(smooth) { model.replying = true }
                    model.onBeginReply()
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { replyFocused = true }
                }
            }
            ForEach(model.buttonActions(n)) { a in
                let lower = a.title.lowercased()
                let answer = lower.contains("answer")
                let decline = lower.contains("decline") || lower.contains("hang")
                NotchButton(title: a.displayTitle,
                            symbol: answer ? "phone.fill" : (decline ? "phone.down.fill" : (a.opensOnPhone ? "arrow.up.forward.app" : nil)),
                            tint: answer ? .green : (decline ? .red : nil)) {
                    model.onAction(n.id, a.id)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.leading, 50)
    }

    private func replyField(_ n: PhoneNotification, _ reply: NotifAction) -> some View {
        VStack(alignment: .trailing, spacing: 8) {
            TextField("Reply to \(n.title.isEmpty ? n.app : n.title)", text: $model.replyText, axis: .vertical)
                .textFieldStyle(.plain)
                .lineLimit(1...3)
                .font(.system(size: 13))
                .foregroundStyle(.white)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color.white.opacity(0.12), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .focused($replyFocused)
                .onAppear { replyFocused = true }
                .onSubmit { send(n, reply) }
                .onExitCommand { withAnimation(smooth) { model.replying = false } }
            HStack(spacing: 8) {
                NotchButton(title: "Cancel") { withAnimation(smooth) { model.replying = false } }
                NotchButton(title: "Send", symbol: "paperplane.fill", prominent: true) { send(n, reply) }
                    .disabled(model.replyText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(.leading, 50)
    }

    private func send(_ n: PhoneNotification, _ reply: NotifAction) {
        let text = model.replyText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        model.replying = false
        model.replyText = ""
        model.onReply(n.id, reply.id, text)
    }

    private func statusBody(_ symbol: String, _ text: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: symbol).foregroundStyle(.green)
            Text(text).font(.system(size: 13, weight: .medium)).foregroundStyle(.white).lineLimit(1)
        }
        .frame(maxWidth: .infinity)
    }
}

/// "‹ 2 of 5 ›": newer on the left, older on the right.
struct NotchPager: View {
    let page: NotchModel.Page
    let go: (Int) -> Void

    var body: some View {
        HStack(spacing: 2) {
            arrow("chevron.left", -1, enabled: page.index > 0, help: "Newer")
            Text("\(page.index + 1) of \(page.total)")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.white.opacity(0.6))
                .fixedSize()
            arrow("chevron.right", 1, enabled: page.index < page.total - 1, help: "Older")
        }
        .transition(.opacity)
    }

    private func arrow(_ symbol: String, _ delta: Int, enabled: Bool, help: String) -> some View {
        Button { go(delta) } label: {
            Image(systemName: symbol)
                .font(.system(size: 10, weight: .bold))
                .frame(width: 18, height: 16)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(.white.opacity(enabled ? 0.85 : 0.25))
        .disabled(!enabled)
        .help(help)
    }
}

struct NotchButton: View {
    let title: String
    var symbol: String?
    var prominent = false
    var tint: Color?
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 5) {
                if let symbol { Image(systemName: symbol).font(.system(size: 11, weight: .semibold)) }
                Text(title).font(.system(size: 12, weight: .semibold)).lineLimit(1)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .foregroundStyle(.white)
            .background(background, in: Capsule())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
    }

    private var background: Color {
        if let tint { return tint.opacity(hover ? 1 : 0.85) }
        if prominent { return Color.accentColor.opacity(hover ? 1 : 0.85) }
        return Color.white.opacity(hover ? 0.24 : 0.14)
    }
}

// MARK: - Live activities beside the notch

/// A small, always-there glance beside the notch: timer, stopwatch, call, ride/delivery ETA, music.
/// Never large: a click only opens a slim strip with the details and controls.
final class LiveBarController {
    static let shared = LiveBarController()
    weak var state: AppState?

    let model = LiveBarModel()
    private lazy var host = TopPanelHost(size: CGSize(width: 520, height: 120), level: .statusBar) { [model] in
        LiveBarView(model: model)
    }
    private var suspended = false
    private var pausedSince: Date?
    private var expireWork: DispatchWorkItem?
    /// Paused music stays (with a play button) for this long.
    private let pausedLinger: TimeInterval = 15 * 60

    init() {
        host.hitSize = { [weak self] in self?.model.visibleSize ?? .zero }
        model.onCommand = { [weak self] cmd in self?.state?.mediaCommand(cmd, id: self?.model.media?.id) }
        model.onSelectPlayer = { [weak self] id in
            self?.selectedPlayer = id
            self?.update()
        }
    }

    /// Player picked in the switcher; sticks until it goes away or another one starts playing.
    private var selectedPlayer: String?
    private var lastTopPlayer: String?

    /// Hidden while a notification is showing in the notch.
    func setSuspended(_ on: Bool) {
        suspended = on
        update()
    }

    var isShowing: Bool { host.isVisible }
    /// Whether the live bar would be on screen if nothing were covering it.
    private(set) var wouldShow = false

    func update() {
        guard let state else { return }
        model.geometry = NotchGeometry.current()
        let enabled = Prefs.bool(Prefs.liveActivities) && state.isConnected
        let activity = state.live.first
        // A newly started player takes over; otherwise keep the one picked in the switcher.
        if let top = state.players.first(where: \.playing)?.id, top != lastTopPlayer {
            lastTopPlayer = top
            if selectedPlayer != top { selectedPlayer = nil }
        }
        if let sel = selectedPlayer, !state.players.contains(where: { $0.id == sel }) { selectedPlayer = nil }
        var media = selectedPlayer.flatMap { id in state.players.first { $0.id == id } } ?? state.nowPlaying
        if let np = media {
            if np.playing {
                pausedSince = nil
                expireWork?.cancel()
            } else {
                let since = pausedSince ?? Date()
                pausedSince = since
                if Date().timeIntervalSince(since) > pausedLinger {
                    media = nil
                } else if expireWork == nil || expireWork!.isCancelled {
                    let work = DispatchWorkItem { [weak self] in self?.expireWork = nil; self?.update() }
                    expireWork = work
                    DispatchQueue.main.asyncAfter(deadline: .now() + pausedLinger - Date().timeIntervalSince(since) + 1, execute: work)
                }
            }
        } else {
            pausedSince = nil
            expireWork?.cancel()
        }
        withAnimation(smooth) {
            model.activity = activity
            model.media = activity == nil ? media : nil
            model.mediaArt = media.flatMap { state.playerArt[$0.id] }
            model.players = state.players
            model.playerArt = state.playerArt
            model.playerIcons = state.icons
            model.icon = activity.flatMap { state.icons[$0.pkg] }
            model.extraCount = max(0, state.live.count - 1) + (activity != nil && media != nil ? 1 : 0)
            if activity == nil && media == nil { model.expanded = false }
        }
        wouldShow = enabled && (activity != nil || media != nil)
        let shouldShow = wouldShow && !suspended
        if shouldShow, !host.isVisible { host.show(geometry: model.geometry, fade: !NotchController.shared.isCovering) }
        if !shouldShow, host.isVisible { host.hide() }
    }
}

final class LiveBarModel: ObservableObject {
    @Published var activity: LiveActivity?
    @Published var media: NowPlaying?
    @Published var mediaArt: NSImage?
    /// All players on the phone, for switching in the detail strip.
    @Published var players: [NowPlaying] = []
    @Published var playerArt: [String: NSImage] = [:]
    @Published var playerIcons: [String: NSImage] = [:]
    var onSelectPlayer: (String) -> Void = { _ in }
    @Published var icon: NSImage?
    @Published var extraCount = 0
    @Published var expanded = false
    var geometry = NotchGeometry.current()
    var onCommand: (String) -> Void = { _ in }

    static let ear: CGFloat = 78

    var barHeight: CGFloat { geometry.hasNotch ? geometry.notchSize.height : geometry.barHeight }

    var collapsedSize: CGSize {
        geometry.hasNotch
            ? CGSize(width: geometry.notchSize.width + Self.ear * 2, height: barHeight)
            : CGSize(width: 150, height: barHeight)
    }

    var showsSwitcher: Bool { media != nil && players.count > 1 }

    var visibleSize: CGSize {
        guard expanded else { return collapsedSize }
        return CGSize(width: max(collapsedSize.width, 340), height: barHeight + 44 + (showsSwitcher ? 34 : 0))
    }
}

struct LiveBarView: View {
    @ObservedObject var model: LiveBarModel

    var body: some View {
        let size = model.visibleSize
        let radii: (CGFloat, CGFloat) = model.geometry.hasNotch ? (8, model.expanded ? 18 : 12) : (0, model.barHeight / 2)
        VStack(spacing: 0) {
            ZStack(alignment: .top) {
                NotchShape(topRadius: radii.0, bottomRadius: radii.1).fill(Color.black)
                TimelineView(.periodic(from: .now, by: 1)) { ctx in
                    VStack(spacing: 0) {
                        glanceRow(now: ctx.date)
                        if model.expanded {
                            detailRow(now: ctx.date).transition(.opacity)
                            if model.showsSwitcher { switcher.transition(.opacity) }
                        }
                    }
                }
            }
            .frame(width: size.width, height: size.height)
            .contentShape(Rectangle())
            .onTapGesture { withAnimation(smooth) { model.expanded.toggle() } }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .environment(\.colorScheme, .dark)
    }

    @ViewBuilder private var leading: some View {
        if let a = model.activity {
            if let icon = model.icon {
                Image(nsImage: icon).resizable().interpolation(.high).frame(width: 18, height: 18)
            } else {
                Image(systemName: a.symbol).font(.system(size: 12, weight: .semibold)).foregroundStyle(tint(a))
            }
        } else if model.media != nil {
            if let art = model.mediaArt {
                Image(nsImage: art).resizable().aspectRatio(contentMode: .fill)
                    .frame(width: 18, height: 18).clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
            } else {
                Image(systemName: "music.note").font(.system(size: 12, weight: .semibold)).foregroundStyle(.pink)
            }
        }
    }

    @ViewBuilder private func trailing(now: Date) -> some View {
        if let a = model.activity {
            HStack(spacing: 4) {
                if let f = a.fraction {
                    ProgressRing(fraction: f).frame(width: 12, height: 12)
                }
                Text(a.glance(at: now))
                    .font(.system(size: 12, weight: .semibold).monospacedDigit())
                    .foregroundStyle(tint(a).opacity(a.isPaused ? 0.55 : 1))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                if model.extraCount > 0 {
                    Circle().fill(.white.opacity(0.5)).frame(width: 4, height: 4)
                }
            }
        } else if let m = model.media {
            PlayPauseGlance(playing: m.playing) { model.onCommand("toggle") }
        }
    }

    private func glanceRow(now: Date) -> some View {
        HStack(spacing: 0) {
            HStack { leading; Spacer(minLength: 0) }
                .padding(.leading, 12)
                .frame(width: model.geometry.hasNotch ? LiveBarModel.ear : 60)
            if model.geometry.hasNotch {
                Color.clear.frame(width: model.geometry.notchSize.width)
            } else {
                Spacer(minLength: 0)
            }
            HStack { Spacer(minLength: 0); trailing(now: now) }
                .padding(.trailing, 12)
                .frame(width: model.geometry.hasNotch ? LiveBarModel.ear : 80)
        }
        .frame(width: model.visibleSize.width, height: model.barHeight)
    }

    /// The slim strip under the glance after a click: what it is, plus controls for music.
    @ViewBuilder private func detailRow(now: Date) -> some View {
        HStack(spacing: 10) {
            if let a = model.activity {
                VStack(alignment: .leading, spacing: 1) {
                    Text(a.title.isEmpty ? a.app : a.title).font(.system(size: 12, weight: .semibold)).lineLimit(1)
                    Text(a.text.isEmpty ? a.app : a.text).font(.system(size: 11)).foregroundStyle(.white.opacity(0.6)).lineLimit(1)
                }
                Spacer(minLength: 0)
            } else if let m = model.media {
                VStack(alignment: .leading, spacing: 1) {
                    Text(m.title).font(.system(size: 12, weight: .semibold)).lineLimit(1)
                    Text(m.artist.isEmpty ? m.app : m.artist).font(.system(size: 11)).foregroundStyle(.white.opacity(0.6)).lineLimit(1)
                }
                Spacer(minLength: 0)
                MediaButton(symbol: "backward.fill") { model.onCommand("prev") }.disabled(!m.canPrev)
                MediaButton(symbol: m.playing ? "pause.fill" : "play.fill") { model.onCommand("toggle") }
                MediaButton(symbol: "forward.fill") { model.onCommand("next") }.disabled(!m.canNext)
            }
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 22)
        .frame(height: 40)
    }

    /// One chip per music app on the phone; click to control that one here.
    private var switcher: some View {
        HStack(spacing: 8) {
            ForEach(model.players) { p in
                let selected = p.id == model.media?.id
                Button { model.onSelectPlayer(p.id) } label: {
                    HStack(spacing: 5) {
                        Group {
                            if let img = model.playerIcons[p.pkg] ?? model.playerArt[p.id] {
                                Image(nsImage: img).resizable().aspectRatio(contentMode: .fill)
                            } else {
                                Image(systemName: "music.note").font(.system(size: 9))
                            }
                        }
                        .frame(width: 16, height: 16)
                        .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
                        Text(p.app).font(.system(size: 11, weight: .medium)).lineLimit(1)
                        if p.playing { Image(systemName: "speaker.wave.2.fill").font(.system(size: 8)) }
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .foregroundStyle(.white)
                    .background(Color.white.opacity(selected ? 0.22 : 0.08), in: Capsule())
                }
                .buttonStyle(.plain)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 22)
        .frame(height: 30)
    }

    private func tint(_ a: LiveActivity) -> Color {
        switch a.kind {
        case .call: .green
        case .timer: .orange
        case .stopwatch: .orange
        case .progress: .blue
        case .status: .white
        }
    }
}

private struct MediaButton: View {
    let symbol: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 13, weight: .semibold)).frame(width: 24, height: 24)
        }
        .buttonStyle(.plain)
    }
}

private struct ProgressRing: View {
    let fraction: Double
    var body: some View {
        ZStack {
            Circle().stroke(.white.opacity(0.25), lineWidth: 2)
            Circle().trim(from: 0, to: fraction).stroke(.blue, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
    }
}

/// Bars while playing (hover shows pause), a play button while paused. Click toggles playback.
private struct PlayPauseGlance: View {
    let playing: Bool
    let toggle: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: toggle) {
            Group {
                if playing && !hover {
                    EqualizerBars()
                } else {
                    Image(systemName: playing ? "pause.fill" : "play.fill")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.white)
                }
            }
            .frame(width: 22, height: 18)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
        .help(playing ? "Pause" : "Play")
    }
}

/// Little "now playing" bars.
private struct EqualizerBars: View {
    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 12)) { ctx in
            let t = ctx.date.timeIntervalSinceReferenceDate
            HStack(alignment: .bottom, spacing: 2) {
                ForEach(0..<4) { i in
                    let v = 0.35 + 0.65 * abs(sin(t * (2.2 + Double(i) * 0.7) + Double(i)))
                    RoundedRectangle(cornerRadius: 1).fill(.pink).frame(width: 2.5, height: 12 * v)
                }
            }
        }
    }
}

// MARK: - Shape

/// Notch silhouette: the top corners flare outward into the menu bar (like the hardware notch),
/// the bottom corners are rounded. Curves are cubic so they blend smoothly into the straight edges.
struct NotchShape: Shape {
    var topRadius: CGFloat
    var bottomRadius: CGFloat

    var animatableData: AnimatablePair<CGFloat, CGFloat> {
        get { AnimatablePair(topRadius, bottomRadius) }
        set { topRadius = newValue.first; bottomRadius = newValue.second }
    }

    func path(in r: CGRect) -> Path {
        let t = min(topRadius, r.width / 4, r.height / 2)
        let b = min(bottomRadius, r.height - t, (r.width - 2 * t) / 2)
        let k: CGFloat = 0.62   // > 0.55 gives a softer, "continuous" looking corner
        var p = Path()
        p.move(to: CGPoint(x: r.minX, y: r.minY))
        p.addCurve(to: CGPoint(x: r.minX + t, y: r.minY + t),
                   control1: CGPoint(x: r.minX + t * k, y: r.minY),
                   control2: CGPoint(x: r.minX + t, y: r.minY + t * (1 - k)))
        p.addLine(to: CGPoint(x: r.minX + t, y: r.maxY - b))
        p.addCurve(to: CGPoint(x: r.minX + t + b, y: r.maxY),
                   control1: CGPoint(x: r.minX + t, y: r.maxY - b * (1 - k)),
                   control2: CGPoint(x: r.minX + t + b * (1 - k), y: r.maxY))
        p.addLine(to: CGPoint(x: r.maxX - t - b, y: r.maxY))
        p.addCurve(to: CGPoint(x: r.maxX - t, y: r.maxY - b),
                   control1: CGPoint(x: r.maxX - t - b * (1 - k), y: r.maxY),
                   control2: CGPoint(x: r.maxX - t, y: r.maxY - b * (1 - k)))
        p.addLine(to: CGPoint(x: r.maxX - t, y: r.minY + t))
        p.addCurve(to: CGPoint(x: r.maxX, y: r.minY),
                   control1: CGPoint(x: r.maxX - t, y: r.minY + t * (1 - k)),
                   control2: CGPoint(x: r.maxX - t * k, y: r.minY))
        p.closeSubpath()
        return p
    }
}
