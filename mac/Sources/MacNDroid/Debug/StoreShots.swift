#if DEBUG
import AppKit
import SwiftUI

/// Renders the real app screens with sample data into PNGs, offscreen, for store listings.
///   .build/debug/MacNDroid --store-shots <folder> [light|dark]
/// Nothing is shown on screen and no network is started.
final class KeyWindow: NSWindow {
    override var isKeyWindow: Bool { true }
    override var isMainWindow: Bool { true }
    override var canBecomeKey: Bool { true }
    override var occlusionState: NSWindow.OcclusionState { .visible }
    @objc func _hasActiveAppearance() -> Bool { true }
    @objc func _hasKeyAppearance() -> Bool { true }
    @objc func _hasActiveAppearanceIgnoringKeyFocus() -> Bool { true }
    @objc func _hasActiveControls() -> Bool { true }
}

enum StoreShots {
    static var useLayer = CommandLine.arguments.contains("--layer")
    static var windowServer = CommandLine.arguments.contains("--ws")

    /// CGWindowListCreateImage is unavailable to Swift in the current SDK; an app may still capture its own windows.
    private typealias CaptureFn = @convention(c) (CGRect, UInt32, UInt32, UInt32) -> Unmanaged<CGImage>?
    static func capture(_ id: Int) -> CGImage? {
        guard let sym = dlsym(UnsafeMutableRawPointer(bitPattern: -2), "CGWindowListCreateImage") else { print("no symbol"); return nil }
        let f = unsafeBitCast(sym, to: CaptureFn.self)
        // optionIncludingWindow = 8, boundsIgnoreFraming = 1, bestResolution = 8
        return f(.null, 8, UInt32(id), 1 | 8)?.takeRetainedValue()
    }

    /// Draw controls in their active (coloured) state without activating the app.
    static func drawAsActive() {
        if !CommandLine.arguments.contains("--noswizzle"), let m = class_getInstanceMethod(NSApplication.self, #selector(getter: NSApplication.isActive)) {
            let block: @convention(block) (AnyObject) -> Bool = { _ in true }
            method_setImplementation(m, imp_implementationWithBlock(block))
        }
        for name in ["_isActive", "_isActiveApp", "isActiveApp"] {
            if let m = class_getInstanceMethod(NSApplication.self, NSSelectorFromString(name)) {
                let block: @convention(block) (AnyObject) -> Bool = { _ in true }
                method_setImplementation(m, imp_implementationWithBlock(block))
            }
        }
        for name in ["_hasActiveAppearance", "_hasKeyAppearance", "hasKeyAppearance", "hasMainAppearance", "_hasActiveAppearanceIgnoringKeyFocus", "_hasActiveControls"] {
            if let m = class_getInstanceMethod(NSWindow.self, NSSelectorFromString(name)) {
                let block: @convention(block) (AnyObject) -> Bool = { _ in true }
                method_setImplementation(m, imp_implementationWithBlock(block))
            }
        }
    }

    static func runIfRequested() {
        let args = CommandLine.arguments
        guard let i = args.firstIndex(of: "--store-shots"), i + 1 < args.count else { return }
        let out = URL(fileURLWithPath: args[i + 1], isDirectory: true)
        let dark = args.dropFirst(i + 2).first == "dark"
        try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
        NSApp.setActivationPolicy(CommandLine.arguments.contains("--accessory") ? .accessory : .prohibited)
        drawAsActive()
        NSApp.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
        UserDefaults.standard.set(true, forKey: Prefs.browserTipDismissed)
        UserDefaults.standard.set(true, forKey: Prefs.browserExtensionSeen)
        let state = AppState.shared
        state.loadStoreDemo()
        let suffix = dark ? "-dark" : ""

        if CommandLine.arguments.contains("--probe") {
            window(List { Text("Row one"); Text("Row two") }.frame(width: 300, height: 200), size: nil, titled: false, to: out.appendingPathComponent("probe-list.png"))
            window(ClipboardView().environmentObject(state).frame(width: 600, height: 500), size: nil, titled: false, to: out.appendingPathComponent("probe-clip.png"))
            window(ScrollView { VStack { Text("Row one"); Text("Row two") } }.frame(width: 300, height: 200), size: nil, titled: false, to: out.appendingPathComponent("probe-scroll.png"))
            exit(0)
        }
        if CommandLine.arguments.contains("--live") {
            if let np = state.nowPlaying {
                window(NowPlayingCard(nowPlaying: np, art: state.playerArt[np.id], compact: false).environmentObject(state)
                        .padding(16).frame(width: 720).background(Color(nsColor: .windowBackgroundColor)),
                       size: nil, titled: false, to: out.appendingPathComponent("live-card\(suffix).png"))
            }
            window(MainView(initialPane: .overview).environmentObject(state), size: CGSize(width: 1000, height: 760), titled: true,
                   to: out.appendingPathComponent("live-main\(suffix).png"))
            let now = Date().timeIntervalSince1970 * 1000
            let timer = LiveActivity(["key": "t", "pkg": "com.google.android.deskclock", "app": "Clock", "kind": "timer",
                                      "title": "Timer", "text": "Pasta", "when": now + 263_000])!
            let ride = LiveActivity(["key": "r", "pkg": "demo.food", "app": "Food delivery", "kind": "status",
                                     "title": "Order on the way", "text": "Arriving in 12 min", "short": "12 min"])!
            for (label, expanded, primary) in [("collapsed", false, nil), ("expanded", true, nil), ("expanded-timer", true, "a:t")] as [(String, Bool, String?)] {
                let m = LiveBarModel()
                m.items = [.media(state.nowPlaying!), .activity(timer), .activity(ride)]
                m.mediaArt = state.playerArt[state.nowPlaying!.id]
                m.icons = state.icons
                m.players = [state.nowPlaying!]
                m.volume = 70
                m.expanded = expanded
                m.primaryID = primary
                let sz = m.hitSize
                window(LiveBarView(model: m).frame(width: sz.width * 2 + 40, height: sz.height + 30, alignment: .top)
                        .background(Color(white: 0.85)), size: nil, titled: false,
                       to: out.appendingPathComponent("live-notch-\(label).png"))
            }
            exit(0)
        }
        let size = CGSize(width: 1000, height: 640)
        func main(_ pane: Pane, _ name: String, height: CGFloat = 640, after: (() -> Void)? = nil) {
            window(MainView(initialPane: pane).environmentObject(state)
                    .overlay(alignment: .topLeading) { if !windowServer { StoreSidebar(selected: pane).environmentObject(state).ignoresSafeArea() } },
                   size: CGSize(width: size.width, height: height), titled: true,
                   to: out.appendingPathComponent("mac-\(name)\(suffix).png"), after: after)
        }
        main(.overview, "01-overview")
        main(.overview, "01-overview-full", height: 1060)
        main(.notifications, "02-notifications")
        main(.messages, "03-messages", after: { state.storeDemoReselectThread() })
        main(.clipboard, "04-clipboard")
        main(.files, "05-files-received")
        main(.files, "05-files-sent", after: { state.storeDemoShowSent() })
        main(.pair, "06-pair-a-phone", height: 700)
        for (i, tab) in ["general", "sync", "apps", "about"].enumerated() {
            window(SettingsView(initialTab: i).environmentObject(state), size: nil, titled: true,
                   to: out.appendingPathComponent("mac-07-settings-\(tab)\(suffix).png"))
        }
        window(MenuBarView().environmentObject(state).frame(width: 340), size: nil, titled: false,
               to: out.appendingPathComponent("mac-08-menu-bar\(suffix).png"))
        window(BrowserExtensionSheet().environmentObject(state).background(Color(nsColor: .windowBackgroundColor)), size: nil, titled: false,
               to: out.appendingPathComponent("mac-09-browser-extension-sheet\(suffix).png"))
        let notchCases: [(NotchStyle, Int, String)] = [(.large, 0, "large-message"), (.compact, 0, "compact-message"),
                                                       (.small, 0, "small-message"), (.large, 1, "large-sign-in-code"), (.compact, 2, "compact-email")]
        for (style, index, label) in notchCases {
            let m = NotchModel()
            m.style = style
            m.expanded = true
            let n = state.notifications[index]
            m.content = .notification(n, state.icons[n.pkg])
            let sz = m.visibleSize
            window(NotchView(model: m).frame(width: sz.width + 60, height: sz.height + 40, alignment: .top), size: nil, titled: false,
                   to: out.appendingPathComponent("mac-10-notch-\(label)\(suffix).png"), clear: true)
        }

        // First launch: welcome, the pairing code, and the moment the phone connects.
        let first = CGSize(width: 1000, height: 700)
        FirstRunMotion.instant = true
        func firstRun<V: View>(_ v: V, _ name: String) {
            window(ZStack { Color(nsColor: .windowBackgroundColor); v }.environmentObject(state), size: first, titled: true,
                   to: out.appendingPathComponent("mac-11-first-run-\(name)\(suffix).png"))
        }
        firstRun(WelcomeView(onStart: {}), "1-welcome")
        firstRun(OnboardingPairView(onLater: {}), "2-pair")
        firstRun(ConnectedView(phoneName: "Phone", onContinue: {}), "3-connected")

        // A file from the phone, in the notch: Open and Show in Finder.
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("store-shots/Downloads", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let pdf = dir.appendingPathComponent("Boarding pass.pdf")
        let page = NSHostingView(rootView: Text("Boarding pass").font(.largeTitle).frame(width: 300, height: 400).background(.white))
        page.frame = CGRect(x: 0, y: 0, width: 300, height: 400)
        try? page.dataWithPDF(inside: page.bounds).write(to: pdf)
        for (label, urls) in [("one", [pdf]), ("several", [pdf, pdf, pdf])] {
            let m = NotchModel()
            m.style = .large
            m.expanded = true
            m.content = .files(urls, "Phone")
            let sz = m.visibleSize
            window(NotchView(model: m).frame(width: sz.width + 60, height: sz.height + 40, alignment: .top), size: nil, titled: false,
                   to: out.appendingPathComponent("mac-12-notch-file-\(label)\(suffix).png"), clear: true)
        }

        // Sharing paused on the phone.
        state.storeDemoPause(true)
        main(.overview, "13-sharing-paused")
        state.storeDemoPause(false)

        // Another phone scanned the code: allow it?
        let alert = NSAlert()
        alert.messageText = "Allow “Alex's phone” to connect?"
        alert.informativeText = "It scanned this Mac's pairing code. If you allow it, it replaces Phone, which will be "
            + "disconnected. Only allow a phone that's yours."
        alert.alertStyle = .warning
        alert.addButton(withTitle: "Allow")
        alert.addButton(withTitle: "Don't Allow")
        alert.layout()
        let aw = alert.window
        aw.setFrameOrigin(CGPoint(x: -20000, y: -20000))
        aw.alphaValue = 0
        aw.orderFrontRegardless()
        RunLoop.main.run(until: Date().addingTimeInterval(0.8))
        if let view = aw.contentView?.superview {
            let b = view.bounds
            let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(b.width * 2), pixelsHigh: Int(b.height * 2),
                                       bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                                       colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
            rep.size = b.size
            view.cacheDisplay(in: b, to: rep)
            try? rep.representation(using: .png, properties: [:])?.write(to: out.appendingPathComponent("mac-14-allow-new-phone\(suffix).png"))
            print("✓ mac-14-allow-new-phone\(suffix).png")
        }
        aw.orderOut(nil)
        exit(0)
    }

    private static func window<V: View>(_ view: V, size: CGSize?, titled: Bool, to url: URL, clear: Bool = false, after: (() -> Void)? = nil) {
        let host = NSHostingView(rootView: view.environment(\.controlActiveState, .key))
        let fit = size ?? host.fittingSize
        let style: NSWindow.StyleMask = titled ? [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView] : [.borderless]
        let w = (CommandLine.arguments.contains("--plain") ? NSWindow.self : KeyWindow.self).init(contentRect: CGRect(origin: .zero, size: fit), styleMask: style, backing: .buffered, defer: false)
        w.titlebarAppearsTransparent = titled
        if clear { w.isOpaque = false; w.backgroundColor = .clear }
        w.contentView = host
        if windowServer {
            // On a display (so lists fill in), but layered behind the desktop picture: never visible.
            w.level = NSWindow.Level(rawValue: Int(CGWindowLevelForKey(.desktopWindow)) - 1)
            w.collectionBehavior = [.stationary, .ignoresCycle, .canJoinAllSpaces]
            let vf = NSScreen.main?.visibleFrame ?? .zero
            w.setFrame(CGRect(origin: CGPoint(x: vf.minX + 20, y: vf.minY + 20), size: w.frame.size), display: true)
        } else {
            w.setFrame(CGRect(origin: CGPoint(x: -20000, y: -20000), size: w.frame.size), display: true)
        }
        w.ignoresMouseEvents = true
        if !windowServer { w.alphaValue = 0 }
        w.orderFrontRegardless()
        RunLoop.main.run(until: Date().addingTimeInterval(1.2))
        if let after { after(); RunLoop.main.run(until: Date().addingTimeInterval(1.2)) }
        host.layoutSubtreeIfNeeded()
        let target = titled ? (w.contentView?.superview ?? host) : host
        let b = target.bounds
        let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(b.width * 2), pixelsHigh: Int(b.height * 2),
                                   bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                                   colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
        rep.size = b.size
        if windowServer, let img = Self.capture(w.windowNumber) {
            let rep2 = NSBitmapImageRep(cgImage: img)
            try? rep2.representation(using: .png, properties: [:])?.write(to: url)
            w.orderOut(nil)
            print("✓ \(url.lastPathComponent) \(img.width)×\(img.height) (window server)")
            return
        }
        if useLayer, let layer = target.layer {
            NSGraphicsContext.saveGraphicsState()
            let ctx = NSGraphicsContext(bitmapImageRep: rep)!
            NSGraphicsContext.current = ctx
            layer.render(in: ctx.cgContext)
            NSGraphicsContext.restoreGraphicsState()
        } else {
            target.cacheDisplay(in: b, to: rep)
        }
        try? rep.representation(using: .png, properties: [:])?.write(to: url)
        w.orderOut(nil)
        print("✓ \(url.lastPathComponent) \(Int(b.width))×\(Int(b.height))")
    }
}

/// The sidebar as it looks on screen (the real List doesn't draw offscreen).
private struct StoreSidebar: View {
    @EnvironmentObject private var state: AppState
    let selected: Pane

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            header("Phone")
            ForEach([Pane.overview, .notifications, .messages, .clipboard, .files]) { row($0.title, $0.symbol, $0 == selected,
                                                                                         badge: $0 == .notifications ? state.notifications.count : 0) }
            header("Setup").padding(.top, 10)
            row(Pane.pair.title, Pane.pair.symbol, selected == .pair)
            row("Browser Extension", "puzzlepiece.extension", false)
            Spacer()
            HStack(spacing: 8) {
                Circle().fill(.green).frame(width: 8, height: 8)
                Text("Connected").font(.callout).foregroundStyle(.secondary)
                Spacer()
            }
            .padding(.horizontal, 12).padding(.bottom, 14)
        }
        .padding(.top, 52)
        .padding(.horizontal, 10)
        .frame(width: 202)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Color(nsColor: .windowBackgroundColor))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Color.primary.opacity(0.045)))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Color.primary.opacity(0.07))))
        .padding(8)
    }

    private func header(_ t: String) -> some View {
        Text(t).font(.system(size: 11, weight: .semibold)).foregroundStyle(.secondary).padding(.horizontal, 10).padding(.bottom, 2)
    }

    private func row(_ title: String, _ symbol: String, _ on: Bool, badge: Int = 0) -> some View {
        HStack(spacing: 8) {
            Image(systemName: symbol).foregroundStyle(.tint).frame(width: 20)
            Text(title)
            Spacer()
            if badge > 0 { Text("\(badge)").font(.callout).foregroundStyle(.secondary) }
        }
        .font(.system(size: 13))
        .padding(.horizontal, 10).frame(height: 30)
        .background(on ? RoundedRectangle(cornerRadius: 8, style: .continuous).fill(Color.primary.opacity(0.09)) : nil)
    }
}
#endif
