import AppKit
import SwiftUI

/// "Phone Screen" window: live view of the phone you can click, scroll and type into.
struct MirrorWindowView: View {
    @EnvironmentObject private var state: AppState
    @AppStorage(Prefs.keepOnTop) private var keepOnTop = false

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                Color.black
                MirrorSurface(renderer: state.mirrorRenderer, videoSize: state.mirrorSize) { state.sendInput($0) }
                    .opacity(state.mirror == .live ? 1 : 0)
                if state.mirror != .live { placeholder }
            }
            .clipShape(RoundedRectangle(cornerRadius: MirrorControlBar.screenCorner, style: .continuous))
            .overlay(alignment: .bottom) {
                if state.mirror == .live && state.controlUnavailable {
                    Label("To control your phone from here, turn on Mac n Droid clipboard and controls on your phone. Tap the notification there to open the setting.",
                          systemImage: "hand.tap")
                        .font(.callout)
                        .padding(10)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                        .padding(12)
                }
            }
            .padding([.horizontal, .top], MirrorControlBar.screenPadding)
            // The controls sit under the screen, never on top of it.
            MirrorControlBar()
        }
        .background(Color(white: 0.09))
        .ignoresSafeArea(edges: .top)
        .navigationTitle(state.phone?.name ?? "Phone")
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                Button { state.sendKey("notifications") } label: { Label("Notifications", systemImage: "bell") }
                    .help("Open notifications (⇧⌘N)")
                    .disabled(state.mirror != .live)
                Button { state.sendKey("quick_settings") } label: { Label("Quick Settings", systemImage: "switch.2") }
                    .help("Open Quick Settings (⌥⌘N)")
                    .disabled(state.mirror != .live)
                Menu {
                    Button("Take Screenshot") { state.sendKey("screenshot") }
                    Divider()
                    Button("Volume Up") { state.sendKey("volume_up") }
                    Button("Volume Down") { state.sendKey("volume_down") }
                    Divider()
                    Button("Lock Phone") { state.sendKey("lock") }
                } label: {
                    Label("More", systemImage: "ellipsis.circle")
                }
                .help("More phone controls")
                .disabled(state.mirror != .live)
                Button { WindowPin.toggle() } label: {
                    Label("Keep on Top", systemImage: keepOnTop ? "pin.fill" : "pin")
                }
                .help(keepOnTop ? "Stop keeping windows on top" : "Keep this window above other apps")
            }
        }
        .focusedSceneValue(\.mirrorLive, state.mirror == .live)
        .onAppear { state.startMirroring() }
        .onDisappear { state.stopMirroring() }
        .frame(minWidth: 300, minHeight: 480)
    }

    @ViewBuilder private var placeholder: some View {
        VStack(spacing: 14) {
            switch state.mirror {
            case .waiting:
                ProgressView().controlSize(.large)
                Text("Tap Start on \(state.phone?.name ?? "your phone")").font(.title3.weight(.semibold))
                Text("Android asks every time before sharing your screen.")
                    .foregroundStyle(.secondary)
            case .denied:
                Image(systemName: "hand.raised").font(.system(size: 36))
                Text("Mirroring was declined on your phone").font(.title3.weight(.semibold))
                Button("Try Again") { state.startMirroring() }
            case .stopped, .idle, .live:
                Image(systemName: "rectangle.on.rectangle.slash").font(.system(size: 36))
                Text(state.isConnected ? "Mirroring stopped" : "Phone not connected").font(.title3.weight(.semibold))
                if state.isConnected { Button("Start Again") { state.startMirroring() } }
            }
        }
        .multilineTextAlignment(.center)
        .foregroundStyle(.white)
        .padding(24)
    }
}

/// The controls under the phone screen: Back, Home, Recent, Type and Stop, in one floating bar.
struct MirrorControlBar: View {
    @EnvironmentObject private var state: AppState
    @State private var typing = false
    @State private var text = ""
    static let screenPadding: CGFloat = 10
    static let screenCorner: CGFloat = 22
    /// Space the bar takes under the screen (bar plus its margins).
    static let height: CGFloat = 78

    private var live: Bool { state.mirror == .live }

    var body: some View {
        HStack(spacing: 2) {
            item("Back", "chevron.backward", help: "Back (Esc, ⌘[ or right-click)") { state.sendKey("back") }
            item("Home", "house", help: "Home (⇧⌘H or middle-click)") { state.sendKey("home") }
            item("Recent", "square.on.square", help: "Recent apps (⇧⌘A)") { state.sendKey("recents") }
            item("Type", "keyboard", help: "Type on your phone. You can also just type while this window is in front.") { typing.toggle() }
                .popover(isPresented: $typing, arrowEdge: .bottom) { typePanel }
            if live {
                item("Stop", "stop.circle.fill", tint: .red, help: "Stop mirroring") { state.stopMirroring() }
            } else {
                item("Start", "play.circle.fill", tint: .green, help: "Start mirroring") { state.startMirroring() }
                    .disabled(!state.isConnected || state.mirror == .waiting)
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(.regularMaterial, in: Capsule())
        .overlay(Capsule().strokeBorder(.white.opacity(0.08)))
        .environment(\.colorScheme, .dark)
        .padding(.top, 10)
        .padding(.bottom, 12)
        .frame(maxWidth: .infinity)
        .frame(height: Self.height)
    }

    private func item(_ title: String, _ symbol: String, tint: Color? = nil, help: String,
                      action: @escaping () -> Void) -> some View {
        let needsLive = title != "Start"
        return Button(action: action) {
            VStack(spacing: 3) {
                Image(systemName: symbol)
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(tint ?? .primary)
                    .frame(height: 20)
                Text(title)
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(tint ?? .secondary)
            }
            .frame(width: 52, height: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(MirrorBarButtonStyle())
        .disabled(needsLive && !live)
        .accessibilityLabel(title)
        .help(help)
    }

    private var typePanel: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Type on \(state.phone?.name ?? "your phone")").font(.headline)
            HStack {
                TextField("Text", text: $text)
                    .textFieldStyle(.roundedBorder)
                    .frame(width: 240)
                    .onSubmit(sendText)
                Button("Send", action: sendText).disabled(text.isEmpty)
            }
            HStack(spacing: 8) {
                Button { state.sendKey("backspace") } label: { Label("Delete", systemImage: "delete.backward") }
                Button { state.sendKey("enter") } label: { Label("Return", systemImage: "return") }
                Spacer()
                Button { if let s = NSPasteboard.general.string(forType: .string) { state.sendInput(["t": "input", "text": s]) } } label: {
                    Label("Paste", systemImage: "doc.on.clipboard")
                }
            }
            .controlSize(.small)
            Text("Tap a text field on the phone first. Return in the box sends the text.")
                .font(.caption).foregroundStyle(.secondary)
        }
        .padding(14)
    }

    private func sendText() {
        guard !text.isEmpty else { return }
        state.sendInput(["t": "input", "text": text])
        text = ""
    }
}

private struct MirrorBarButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(Capsule().fill(.white.opacity(configuration.isPressed ? 0.16 : 0)))
            .opacity(enabled ? 1 : 0.35)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

struct MirrorLiveKey: FocusedValueKey { typealias Value = Bool }
extension FocusedValues {
    /// True while the Phone Screen window is focused and showing the phone (enables the Phone menu).
    var mirrorLive: Bool? {
        get { self[MirrorLiveKey.self] }
        set { self[MirrorLiveKey.self] = newValue }
    }
}

/// The Phone menu: navigation and system actions, with shortcuts, while the Phone Screen window is in front.
struct PhoneCommands: Commands {
    @FocusedValue(\.mirrorLive) private var live
    let state: AppState

    var body: some Commands {
        CommandMenu("Phone") {
            item("Back", "back", "[", [.command])
            item("Home", "home", "h", [.command, .shift])
            item("Recent Apps", "recents", "a", [.command, .shift])
            Divider()
            item("Notifications", "notifications", "n", [.command, .shift])
            item("Quick Settings", "quick_settings", "n", [.command, .option])
            Divider()
            item("Volume Up", "volume_up", .upArrow, [.command])
            item("Volume Down", "volume_down", .downArrow, [.command])
            Divider()
            item("Take Screenshot", "screenshot", "s", [.command, .option])
            item("Lock Phone", "lock", "l", [.command, .option])
        }
    }

    private func item(_ title: String, _ key: String, _ shortcut: KeyEquivalent, _ mods: EventModifiers) -> some View {
        Button(title) { state.sendKey(key) }
            .keyboardShortcut(shortcut, modifiers: mods)
            .disabled(live != true)
    }
}

/// Hosts the video layer and turns mouse/keyboard input into phone gestures.
struct MirrorSurface: NSViewRepresentable {
    let renderer: MirrorRenderer
    let videoSize: CGSize
    let send: (Message) -> Void

    func makeNSView(context: Context) -> MirrorNSView {
        let v = MirrorNSView(renderer: renderer)
        v.send = send
        return v
    }

    func updateNSView(_ v: MirrorNSView, context: Context) {
        v.send = send
        v.videoSize = videoSize
    }
}

final class MirrorNSView: NSView {
    private let renderer: MirrorRenderer
    var send: (Message) -> Void = { _ in }
    var videoSize: CGSize = .zero {
        didSet { if videoSize != oldValue { fitWindow() } }
    }

    private var points: [[Double]] = []
    private var downAt = Date()
    private var lastPoint = CGPoint.zero
    private var scrollAccum = CGSize.zero
    private var scrollOrigin: [Double]?
    private var scrollWork: DispatchWorkItem?

    init(renderer: MirrorRenderer) {
        self.renderer = renderer
        super.init(frame: .zero)
        wantsLayer = true
        layer?.backgroundColor = NSColor.black.cgColor
        layer?.addSublayer(renderer.layer)
    }

    required init?(coder: NSCoder) { fatalError() }

    override var acceptsFirstResponder: Bool { true }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override func layout() {
        super.layout()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        renderer.layer.frame = bounds
        CATransaction.commit()
    }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        window?.makeFirstResponder(self)
        fitWindow()
    }

    /// Lock the window to the phone's aspect ratio and give it a sensible first size.
    private func fitWindow() {
        guard let window, videoSize.width > 0, videoSize.height > 0 else { return }
        let pad = MirrorControlBar.screenPadding
        let bar = MirrorControlBar.height + pad
        let screenH = (window.screen ?? NSScreen.main)?.visibleFrame.height ?? 900
        let currentH = window.contentLayoutRect.height - bar
        let h = min(max(currentH, 560), screenH - 80 - bar)
        // Wide enough for the control bar's five buttons.
        let w = max(h * videoSize.width / videoSize.height + pad * 2, 300)
        // Phone + control bar. The bar is a fixed height, so the ratio drifts a little as the
        // window is resized; the video is aspect-fit on black, so that only shows as thin black edges.
        window.contentAspectRatio = CGSize(width: w, height: h + bar)
        var frame = window.frameRect(forContentRect: NSRect(x: 0, y: 0, width: w, height: h + bar))
        frame.origin = NSPoint(x: window.frame.minX, y: window.frame.maxY - frame.height)
        window.setFrame(frame, display: true, animate: false)
    }

    /// Rect the video actually occupies (aspect fit).
    private var videoRect: CGRect {
        guard videoSize.width > 0, videoSize.height > 0 else { return bounds }
        let scale = min(bounds.width / videoSize.width, bounds.height / videoSize.height)
        let w = videoSize.width * scale, h = videoSize.height * scale
        return CGRect(x: (bounds.width - w) / 2, y: (bounds.height - h) / 2, width: w, height: h)
    }

    /// 0…1 coordinates with the origin at the phone's top-left.
    private func normalized(_ event: NSEvent) -> [Double] {
        let p = convert(event.locationInWindow, from: nil)
        let r = videoRect
        let x = min(max((p.x - r.minX) / r.width, 0), 1)
        let y = min(max(1 - (p.y - r.minY) / r.height, 0), 1)
        return [Double(x), Double(y)]
    }

    // MARK: Mouse → touch

    override func mouseDown(with event: NSEvent) {
        window?.makeFirstResponder(self)
        points = [normalized(event)]
        downAt = Date()
        lastPoint = event.locationInWindow
    }

    override func mouseDragged(with event: NSEvent) {
        let p = event.locationInWindow
        guard hypot(p.x - lastPoint.x, p.y - lastPoint.y) > 3 else { return }
        lastPoint = p
        points.append(normalized(event))
    }

    override func mouseUp(with event: NSEvent) {
        guard !points.isEmpty else { return }
        points.append(normalized(event))
        let elapsed = Date().timeIntervalSince(downAt) * 1000
        let moved = points.count > 2
        // Quick clicks become crisp taps; holds become long-presses; drags keep their speed (flings work).
        let dur = moved ? max(elapsed, 80) : (elapsed < 350 ? 50 : elapsed)
        send(["t": "input", "p": Self.thin(points, max: 40), "dur": Int(min(dur, 10_000))])
        points = []
    }

    override func rightMouseDown(with event: NSEvent) { send(["t": "input", "key": "back"]) }
    /// Middle button (wheel click) = Home, as in other phone mirroring tools.
    override func otherMouseDown(with event: NSEvent) {
        if event.buttonNumber == 2 { send(["t": "input", "key": "home"]) } else { super.otherMouseDown(with: event) }
    }

    override func scrollWheel(with event: NSEvent) {
        if scrollOrigin == nil { scrollOrigin = normalized(event) }
        let scale: CGFloat = event.hasPreciseScrollingDeltas ? 1 : 12
        scrollAccum.width += event.scrollingDeltaX * scale
        scrollAccum.height += event.scrollingDeltaY * scale
        scrollWork?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.flushScroll() }
        scrollWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.06, execute: work)
    }

    private func flushScroll() {
        guard let o = scrollOrigin else { return }
        let r = videoRect
        // Content follows the fingers, the same as on the phone (macOS already applies "natural" scrolling).
        let dx = Double(scrollAccum.width / max(r.width, 1)), dy = Double(scrollAccum.height / max(r.height, 1))
        scrollAccum = .zero
        scrollOrigin = nil
        let clampD = { (v: Double) in min(max(v, -0.6), 0.6) }
        let end = [min(max(o[0] + clampD(dx), 0), 1), min(max(o[1] + clampD(dy), 0.02), 0.98)]
        guard abs(end[0] - o[0]) + abs(end[1] - o[1]) > 0.005 else { return }
        send(["t": "input", "p": [o, end], "dur": 120])
    }

    private static func thin(_ pts: [[Double]], max: Int) -> [[Double]] {
        guard pts.count > max else { return pts }
        let step = Double(pts.count - 1) / Double(max - 1)
        return (0..<max).map { pts[Int((Double($0) * step).rounded())] }
    }

    // MARK: Keyboard → phone

    override func keyDown(with event: NSEvent) {
        let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        if flags.contains(.command) {
            if event.charactersIgnoringModifiers == "v", let text = NSPasteboard.general.string(forType: .string) {
                send(["t": "input", "text": text])
            } else {
                super.keyDown(with: event)
            }
            return
        }
        switch event.keyCode {
        case 51, 117: send(["t": "input", "key": "backspace"])
        case 36, 76: send(["t": "input", "key": "enter"])
        case 53: send(["t": "input", "key": "back"])
        default:
            guard let chars = event.characters, !chars.isEmpty,
                  chars.unicodeScalars.allSatisfy({ !CharacterSet.controlCharacters.contains($0) && $0.value < 0xF700 })
            else { return }
            send(["t": "input", "text": chars])
        }
    }
}
