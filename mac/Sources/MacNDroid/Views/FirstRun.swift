import AppKit
import SwiftUI

// First launch: Welcome (phone and Mac linked by electricity, features circling them) → Pair a Phone (rings pulse
// around the QR code while it waits) → Connected (the two come together, a ✓, and confetti over every screen).
// Everything still and confetti-free when Reduce Motion is on.

// MARK: Welcome

/// Store screenshots draw offscreen, where entrance animations never run: they show the end state instead.
enum FirstRunMotion { static var instant = false }

struct WelcomeView: View {
    var onStart: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var scheme
    @State private var appeared = Date()

    private static let features: [(String, String)] = [
        ("bell.fill", "Notifications"), ("message.fill", "Messages"), ("ellipsis.rectangle.fill", "Verification codes"),
        ("doc.on.clipboard.fill", "Clipboard"), ("folder.fill", "Files"), ("photo.fill", "Photos"),
        ("rectangle.on.rectangle", "Phone screen"), ("music.note", "Music"),
    ]

    var body: some View {
        ZStack {
            Color(nsColor: .windowBackgroundColor)
            DotGrid()
            VStack(spacing: 0) {
                GeometryReader { geo in
                    TimelineView(.animation(paused: reduceMotion)) { tl in
                        let t = reduceMotion ? 0 : tl.date.timeIntervalSince(appeared)
                        ZStack {
                            orbit(t: t, size: geo.size)
                            devices
                        }
                        .frame(width: geo.size.width, height: geo.size.height)
                    }
                }
                VStack(spacing: 6) {
                    Text("Welcome to Mac n Droid").font(.system(size: 26, weight: .semibold))
                    Text("Your Android phone and your Mac, together.").font(.title3).foregroundStyle(.secondary)
                    Button("Get Started", action: onStart)
                        .buttonStyle(.borderedProminent).controlSize(.large)
                        .keyboardShortcut(.defaultAction)
                        .padding(.top, 18)
                }
                .padding(.bottom, 48)
            }
        }
        .ignoresSafeArea()
    }

    private var deviceColor: Color { scheme == .dark ? Color(white: 0.84) : .accentColor }

    private var devices: some View {
        HStack(spacing: 0) {
            Image(systemName: "smartphone").resizable().scaledToFit().frame(width: 52, height: 64)
            ElectricLink().frame(width: 168, height: 70)
            Image(systemName: "laptopcomputer").resizable().scaledToFit().frame(width: 100, height: 60)
        }
        .foregroundStyle(deviceColor)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Your phone and your Mac, connected")
    }

    /// Eight features on a tilted ellipse. The orbit eases faster and slower, each chip floats on its own, and
    /// they arrive one by one with a soft spring.
    private func orbit(t: Double, size: CGSize) -> some View {
        let cx = size.width / 2, cy = size.height / 2
        let rx = min(size.width * 0.38, 320), ry = min(size.height * 0.4, 130)
        let spin = t * (2 * .pi / 80) + 0.35 * sin(t * 0.17)
        return ForEach(Array(Self.features.enumerated()), id: \.offset) { i, f in
            let a = spin + Double(i) / Double(Self.features.count) * 2 * .pi
            let depth = (sin(a) + 1) / 2
            let bob = reduceMotion ? 0 : sin(t * 0.9 + Double(i) * 1.3) * 3
            let k = reduceMotion ? 1 : min(1, max(0, (t - 0.25 - Double(i) * 0.09) / 0.7))
            let pop = k >= 1 ? 1 : 1 - pow(1 - k, 3) * cos(k * 4.2)
            FeatureChip(symbol: f.0, label: f.1)
                .scaleEffect((0.78 + depth * 0.22) * pop)
                .opacity((0.45 + depth * 0.55) * min(1, k * 1.5))
                .position(x: cx + rx * cos(a), y: cy + ry * sin(a) * 0.92 + bob)
                .zIndex(depth > 0.5 ? 3 : 1)
        }
    }
}

private struct FeatureChip: View {
    let symbol: String
    let label: String
    @State private var hover = false

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: 16, weight: .medium))
            .foregroundStyle(.secondary)
            .frame(width: 40, height: 40)
            .background(Circle().fill(Color(nsColor: .controlBackgroundColor)))
            .overlay(Circle().strokeBorder(Color.primary.opacity(0.08)))
            .shadow(color: .black.opacity(0.06), radius: 6, y: 3)
            .overlay(alignment: .bottom) {
                Text(label).font(.caption).foregroundStyle(.secondary).fixedSize()
                    .offset(y: 20).opacity(hover ? 1 : 0)
            }
            .onHover { hover = $0 }
            .help(label)
    }
}

/// A faint dot grid that drifts slowly back and forth, fading out towards the edges.
private struct DotGrid: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 30, paused: reduceMotion)) { tl in
            let t = tl.date.timeIntervalSinceReferenceDate
            let shift = reduceMotion ? 0 : (sin(t * 2 * .pi / 80) + 1) / 2 * 22
            Canvas { gc, size in
                let step: CGFloat = 22
                var y = -step + shift
                while y < size.height + step {
                    var x = -step + shift
                    while x < size.width + step {
                        gc.fill(Path(ellipseIn: CGRect(x: x - 1.1, y: y - 1.1, width: 2.2, height: 2.2)), with: .color(.primary.opacity(0.07)))
                        x += step
                    }
                    y += step
                }
            }
            .mask(RadialGradient(colors: [.black, .clear], center: .center, startRadius: 80, endRadius: 520))
        }
        .allowsHitTesting(false)
    }
}

// MARK: Electricity

/// Several glowing strands pinned at both ends, wobbling with smooth noise and a little crackle.
struct ElectricLink: View {
    var energy: Double = 1
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var scheme
    @State private var seeds: [(Double, Double, Double, Double)] = (0..<6).map { _ in
        (.random(in: 0...6.28), .random(in: 0...6.28), .random(in: 0...6.28), .random(in: 0.7...1.5))
    }

    var body: some View {
        TimelineView(.animation(paused: reduceMotion)) { tl in
            let t = tl.date.timeIntervalSinceReferenceDate
            Canvas { gc, size in
                let dark = scheme == .dark
                let colors: [Color] = dark
                    ? [.white, Color(red: 0.56, green: 0.71, blue: 1), Color(red: 0.72, green: 0.58, blue: 1), Color(red: 0.44, green: 0.83, blue: 1), Color(red: 0.62, green: 0.55, blue: 1), Color(red: 0.79, green: 0.72, blue: 1)]
                    : [Color(red: 0.31, green: 0.27, blue: 0.9), Color(red: 0.23, green: 0.51, blue: 0.96), Color(red: 0.55, green: 0.36, blue: 0.96), Color(red: 0.05, green: 0.65, blue: 0.91), Color(red: 0.39, green: 0.4, blue: 0.95), Color(red: 0.65, green: 0.55, blue: 0.98)]
                if dark { gc.blendMode = .plusLighter }
                let mid = size.height / 2
                let flicker = reduceMotion ? 1 : 0.8 + 0.2 * sin(t * 23) * sin(t * 7.3)
                for (i, s) in seeds.enumerated() {
                    let amp = reduceMotion ? 0 : energy * (6 + 7 * (0.5 + 0.5 * sin(t * 0.9 + Double(i) * 1.7)))
                    var p = Path()
                    var x: CGFloat = 0
                    while x <= size.width {
                        let u = Double(x / size.width)
                        let env = pow(sin(.pi * u), 0.7)
                        let n = sin(u * 7 + t * 3.1 * s.3 + s.0) * 0.55 + sin(u * 13 - t * 4.7 * s.3 + s.1) * 0.3
                            + sin(u * 23 + t * 7.9 + s.2) * 0.15 + (reduceMotion ? 0 : Double.random(in: -0.09...0.09))
                        let y = mid + CGFloat(env * amp * n)
                        if x == 0 { p.move(to: CGPoint(x: x, y: y)) } else { p.addLine(to: CGPoint(x: x, y: y)) }
                        x += 3
                    }
                    let c = colors[i % colors.count]
                    gc.drawLayer { layer in
                        layer.addFilter(.shadow(color: colors[min(i + 1, colors.count - 1)], radius: dark ? 5 : 2.5))
                        layer.stroke(p, with: .color(c.opacity((i == 0 ? 0.95 : 0.55) * flicker)), lineWidth: i == 0 ? 1.4 : 0.9)
                    }
                }
            }
        }
        .allowsHitTesting(false)
    }
}

// MARK: Pairing

/// Soft rings pulsing out from the QR code while the Mac waits for a phone.
struct WaitingRings: ViewModifier {
    var active: Bool
    var corner: CGFloat = 14
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content.background {
            if active && !reduceMotion {
                TimelineView(.animation) { tl in
                    let t = tl.date.timeIntervalSinceReferenceDate
                    ZStack {
                        ForEach(0..<3, id: \.self) { i in
                            let p = (t / 3 + Double(i) / 3).truncatingRemainder(dividingBy: 1)
                            let eased = 1 - pow(1 - p, 2)
                            RoundedRectangle(cornerRadius: corner + 6, style: .continuous)
                                .strokeBorder(Color.accentColor, lineWidth: 1.5)
                                .scaleEffect(1 + 0.18 * eased)
                                .opacity(0.5 * (1 - p))
                        }
                    }
                }
                .allowsHitTesting(false)
            }
        }
    }
}

// MARK: Onboarding pairing

/// Step 2 of first launch: the QR code, full window and centred, with only what's needed to pair. The
/// connection details stay on the in-app Pair a Phone page.
struct OnboardingPairView: View {
    var onLater: () -> Void
    /// Preview mode only: clicking the code pretends a phone scanned it.
    var onPreviewScan: (() -> Void)? = nil
    @EnvironmentObject private var state: AppState
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shown = FirstRunMotion.instant
    @State private var copied = false

    var body: some View {
        ZStack {
            Color(nsColor: .windowBackgroundColor)
            DotGrid()
            VStack(spacing: 0) {
                PairingQR(size: 232)
                    .modifier(WaitingRings(active: !state.isConnected))
                    .onTapGesture { onPreviewScan?() }
                    .scaleEffect(shown ? 1 : 0.9)
                    .opacity(shown ? 1 : 0)
                    .padding(.bottom, 40)
                VStack(spacing: 10) {
                    Text("Pair your Android phone").font(.system(size: 26, weight: .semibold))
                    Text("Open Mac n Droid on your phone, tap **Scan QR code** and point it at this code.")
                        .font(.title3).foregroundStyle(.secondary)
                    Text("No app yet? Scan the code with your phone's camera to get it from Google Play.")
                        .font(.callout).foregroundStyle(.secondary)
                }
                .multilineTextAlignment(.center)
                .frame(maxWidth: 460)
                .opacity(shown ? 1 : 0).offset(y: shown ? 0 : 8)
                HStack(spacing: 6) {
                    ProgressView().controlSize(.small)
                    Text("Waiting for your phone").foregroundStyle(.secondary)
                }
                .font(.callout)
                .padding(.top, 18)
                .opacity(shown ? 1 : 0)
                VStack(spacing: 10) {
                    Button(copied ? "Copied" : "Copy Pairing Link") {
                        state.copyToMac(state.pairingURI)
                        copied = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { copied = false }
                    }
                    .controlSize(.large)
                    .help("Send this link to your phone and open it there, if you can't scan the code")
                    Button("Set Up Later", action: onLater)
                        .buttonStyle(.link)
                }
                .padding(.top, 26)
                .opacity(shown ? 1 : 0).offset(y: shown ? 0 : 8)
                Text("Both devices need to be on the same Wi-Fi. Everything stays on your network and is end-to-end encrypted.")
                    .font(.caption).foregroundStyle(.tertiary)
                    .multilineTextAlignment(.center).frame(maxWidth: 420)
                    .padding(.top, 28)
                    .opacity(shown ? 1 : 0)
            }
            .padding(32)
        }
        .ignoresSafeArea()
        .onAppear {
            if reduceMotion || FirstRunMotion.instant { shown = true } else { withAnimation(.spring(response: 0.6, dampingFraction: 0.8).delay(0.05)) { shown = true } }
        }
    }
}

// MARK: Connected

struct ConnectedView: View {
    let phoneName: String
    var onContinue: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var scheme
    @State private var snapped = FirstRunMotion.instant
    @State private var lines = FirstRunMotion.instant
    @State private var check = FirstRunMotion.instant
    @State private var text = FirstRunMotion.instant

    private let green = Color(red: 0.05, green: 0.64, blue: 0.34)

    var body: some View {
        ZStack {
            Color(nsColor: .windowBackgroundColor)
            DotGrid()
            VStack(spacing: 0) {
                // Two identical tiles; the ✓ sits on the window's centre line, the same distance from each.
                HStack(spacing: 10) {
                    tile("smartphone", width: 44, height: 56).offset(x: snapped ? 0 : -40).opacity(snapped ? 1 : 0)
                    ZStack {
                        HStack {
                            Capsule().fill(green).frame(width: 33, height: 2).scaleEffect(x: lines ? 1 : 0, anchor: .leading)
                            Spacer()
                            Capsule().fill(green).frame(width: 33, height: 2).scaleEffect(x: lines ? 1 : 0, anchor: .trailing)
                        }
                        Circle().fill(green).frame(width: 34, height: 34)
                            .overlay(Image(systemName: "checkmark").font(.system(size: 15, weight: .bold)).foregroundStyle(.white))
                            .scaleEffect(check ? 1 : 0.01)
                    }
                    .frame(width: 120)
                    tile("laptopcomputer", width: 70, height: 41, nudge: 2).offset(x: snapped ? 0 : 40).opacity(snapped ? 1 : 0)
                }
                .padding(.bottom, 32)
                VStack(spacing: 8) {
                    Text("You're connected").font(.system(size: 26, weight: .semibold))
                    Text("\(phoneName) and this Mac now work together.").font(.title3).foregroundStyle(.secondary)
                    HStack(spacing: 12) {
                        // Just for fun: confetti again, as often as you like.
                        Button { ConfettiShow.play(force: true) } label: { Label("Play Confetti", systemImage: "party.popper") }
                            .controlSize(.large)
                        Button("Continue", action: onContinue)
                            .buttonStyle(.borderedProminent).controlSize(.large)
                            .keyboardShortcut(.defaultAction)
                    }
                    .padding(.top, 16)
                }
                .opacity(text ? 1 : 0).offset(y: text ? 0 : 8)
            }
        }
        .ignoresSafeArea()
        .onAppear(perform: play)
    }

    private func tile(_ symbol: String, width: CGFloat, height: CGFloat, nudge: CGFloat = 0) -> some View {
        RoundedRectangle(cornerRadius: 26, style: .continuous)
            .fill(Color(nsColor: .controlBackgroundColor))
            .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).strokeBorder(Color.primary.opacity(0.08)))
            .shadow(color: .black.opacity(0.06), radius: 9, y: 6)
            .frame(width: 96, height: 96)
            .overlay(
                Image(systemName: symbol).resizable().scaledToFit().frame(width: width, height: height)
                    .foregroundStyle(scheme == .dark ? Color(white: 0.84) : .accentColor).offset(y: nudge)
            )
    }

    private func play() {
        if reduceMotion || FirstRunMotion.instant { snapped = true; lines = true; check = true; text = true; return }
        withAnimation(.spring(response: 0.7, dampingFraction: 0.62)) { snapped = true }
        withAnimation(.easeInOut(duration: 0.4).delay(0.55)) { lines = true }
        withAnimation(.spring(response: 0.45, dampingFraction: 0.5).delay(0.85)) { check = true }
        withAnimation(.easeOut(duration: 0.5).delay(1.0)) { text = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.75) { ConfettiShow.play() }
    }
}

// MARK: Confetti over every screen

/// Confetti bursting from the bottom corners of every display, above all windows, ignoring the mouse.
/// Each play adds a burst to what's already falling, so pressing it again and again stays smooth and continuous.
enum ConfettiShow {
    private static var windows: [NSWindow] = []
    private static let store = ConfettiStore()

    /// `force`: the person asked for it (Play Confetti), so play even with Reduce Motion on.
    static func play(force: Bool = false) {
        guard force || !NSWorkspace.shared.accessibilityDisplayShouldReduceMotion else { return }
        if windows.isEmpty { openWindows() }
        store.add()
        // Close the windows once the last burst has finished falling.
        DispatchQueue.main.asyncAfter(deadline: .now() + ConfettiStore.lifetime + 0.2) {
            store.prune()
            if store.bursts.isEmpty { windows.forEach { $0.close() }; windows = [] }
        }
    }

    private static func openWindows() {
        windows = NSScreen.screens.map { screen in
            let w = NSWindow(contentRect: screen.frame, styleMask: .borderless, backing: .buffered, defer: false)
            w.isOpaque = false
            w.backgroundColor = .clear
            w.hasShadow = false
            w.ignoresMouseEvents = true
            w.level = .screenSaver
            w.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
            w.isReleasedWhenClosed = false
            w.contentView = NSHostingView(rootView: ConfettiField(store: store, size: screen.frame.size))
            w.setFrame(screen.frame, display: false)
            w.orderFrontRegardless()
            return w
        }
    }
}

/// The bursts in flight. Pieces are stored in screen-independent units, so every display draws the same burst
/// at its own size.
final class ConfettiStore: ObservableObject {
    static let lifetime: TimeInterval = 5
    /// Keeps the frame cheap if someone presses very fast: the oldest bursts make way.
    private static let maxBursts = 6

    struct Piece {
        let right: Bool
        let angle, speed, w, h, r, vr, tilt: Double
        let color: Color
        let round: Bool
    }
    struct Burst: Identifiable {
        let id = UUID()
        let start: Date
        let pieces: [Piece]
    }

    @Published private(set) var bursts: [Burst] = []

    private static let palette: [Color] = [
        Color(red: 0.05, green: 0.64, blue: 0.34), Color(red: 0.19, green: 0.82, blue: 0.35), .blue,
        Color(red: 0.35, green: 0.78, blue: 0.98), .yellow, .orange, .pink, .purple,
    ]

    func add() {
        prune()
        var pieces: [Piece] = []
        for side in 0..<2 {
            for _ in 0..<90 {
                pieces.append(Piece(
                    right: side == 1,
                    angle: (side == 1 ? -Double.pi * 0.62 : -Double.pi * 0.38) + .random(in: -0.28...0.28),
                    speed: .random(in: 0.9...1.8), w: .random(in: 7...13), h: .random(in: 4...9),
                    r: .random(in: 0...6), vr: .random(in: -0.17...0.17), tilt: .random(in: 0...6),
                    color: Self.palette.randomElement()!, round: .random(in: 0...1) < 0.25))
            }
        }
        bursts.append(Burst(start: Date(), pieces: pieces))
        if bursts.count > Self.maxBursts { bursts.removeFirst(bursts.count - Self.maxBursts) }
    }

    func prune(now: Date = Date()) {
        bursts.removeAll { now.timeIntervalSince($0.start) > Self.lifetime }
    }
}

private struct ConfettiField: View {
    @ObservedObject var store: ConfettiStore
    let size: CGSize

    var body: some View {
        TimelineView(.animation(paused: store.bursts.isEmpty)) { tl in
            let now = tl.date
            Canvas(rendersAsynchronously: true) { gc, _ in
                let W = size.width, H = size.height
                let drag = 0.985, g = H / 2600
                for burst in store.bursts {
                    // Simple ballistic motion with drag, worked out for the burst's age (no per-frame state).
                    let frames = now.timeIntervalSince(burst.start) * 60
                    guard frames >= 0 else { continue }
                    let decay = (1 - pow(drag, frames)) / (1 - drag)
                    let fall = g * (frames - decay) / (1 - drag)
                    let fade = max(0, min(1, (300 - frames) / 60))
                    guard fade > 0 else { continue }
                    for p in burst.pieces {
                        let sp = H / 55 * p.speed
                        let x = (p.right ? W + 10 : -10) + cos(p.angle) * sp * decay
                        let y = H * 0.95 + sin(p.angle) * sp * decay + fall
                        guard y < H + 60, x > -60, x < W + 60 else { continue }
                        var l = gc   // a copy: transforms apply to this piece only, with no offscreen layer
                        l.opacity = fade
                        l.translateBy(x: x, y: y)
                        l.rotate(by: .radians(p.r + p.vr * frames))
                        l.scaleBy(x: 1, y: max(0.15, abs(cos(p.tilt + 0.12 * frames))))
                        l.fill(p.round ? Path(ellipseIn: CGRect(x: -p.h / 2, y: -p.h / 2, width: p.h, height: p.h))
                                       : Path(CGRect(x: -p.w / 2, y: -p.h / 2, width: p.w, height: p.h)), with: .color(p.color))
                    }
                }
            }
        }
        .frame(width: size.width, height: size.height)
        .drawingGroup()   // render on the GPU
        .allowsHitTesting(false)
    }
}
