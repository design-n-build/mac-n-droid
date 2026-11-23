#if DEBUG
import AppKit
import SwiftUI

/// Stills of the real Mac views for the video series, with the series' demo data
/// (video/series/STORY.md, sections 2 and 5B).
///   tools/video/mac-shots.sh          → video/series/captures/mac/
/// Everything here is the app's own SwiftUI code; nothing is redrawn. Debug builds only.
///
/// Two ways of capturing:
///  - Notch, drop target, live bar, popover: drawn offscreen at any scale, on a transparent background.
///    Each notch image is the size of the real panel, so its top centre is the top centre of the screen.
///  - Windows (lists and sidebars only draw on a display): the window sits behind the desktop picture,
///    where it can't be seen, and macOS's own window capture photographs it at the display's scale.
enum VideoShots {
    private static var out = URL(fileURLWithPath: ".")
    private static var scale: CGFloat = 3
    private static var manifest: [[String: Any]] = []
    private static var only: [String]?
    private static var clock = Date()

    static func runIfRequested() {
        let args = CommandLine.arguments
        if let i = args.firstIndex(of: "--video-demo-files"), i + 1 < args.count {
            DemoFiles.write(to: URL(fileURLWithPath: args[i + 1], isDirectory: true))
            exit(0)
        }
        guard let i = args.firstIndex(of: "--video-shots"), i + 1 < args.count else { return }
        // Sample settings are written to this app's preferences: never the installed app's.
        guard Bundle.main.bundleIdentifier?.hasSuffix(".shots") == true else {
            print("Run this through tools/video/mac-shots.sh (it uses a separate app id, so your own settings stay untouched).")
            exit(2)
        }
        out = URL(fileURLWithPath: args[i + 1], isDirectory: true)
        active = true
        NSApp.setActivationPolicy(.prohibited)
        // Lists and sidebars only fill in once the app is running, so the capturing starts after launch.
        // The app's own window opens at launch like always: keep it invisible, then close it.
        hider = NotificationCenter.default.addObserver(forName: NSWindow.didUpdateNotification, object: nil, queue: .main) { note in
            guard let w = note.object as? NSWindow, !(w is KeyWindow), w.alphaValue != 0 else { return }
            w.alphaValue = 0
            w.ignoresMouseEvents = true
        }
        // A timer, not a main-queue block: work the views queue on the main queue must still run while this waits.
        Timer.scheduledTimer(withTimeInterval: 0.6, repeats: false) { _ in
            for w in NSApp.windows where !(w is KeyWindow) { w.alphaValue = 0; w.close() }
            RunLoop.main.run(until: Date().addingTimeInterval(0.3))
            run(args)
        }
    }

    private static var hider: NSObjectProtocol?
    static var active = false

    private static func run(_ args: [String]) {
        if let s = args.firstIndex(of: "--scale"), s + 1 < args.count, let v = Double(args[s + 1]) { scale = CGFloat(v) }
        if let o = args.firstIndex(of: "--only"), o + 1 < args.count { only = args[o + 1].split(separator: ",").map(String.init) }
        // The story's time of day (STORY.md: evening). Texts and files are dated from it, today.
        let hm = (args.firstIndex(of: "--time").flatMap { $0 + 1 < args.count ? args[$0 + 1] : nil } ?? "18:40").split(separator: ":").compactMap { Int($0) }
        clock = Calendar.current.date(bySettingHour: hm.first ?? 18, minute: hm.count > 1 ? hm[1] : 40, second: 0, of: Date()) ?? Date()
        let demo = args.firstIndex(of: "--demo-files").flatMap { $0 + 1 < args.count ? URL(fileURLWithPath: args[$0 + 1], isDirectory: true) : nil }
        try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)

        StoreShots.drawAsActive()
        let d = UserDefaults.standard
        d.set(true, forKey: Prefs.browserTipDismissed)
        d.set(true, forKey: Prefs.browserExtensionSeen)
        d.set(true, forKey: "welcomeSeen")
        d.set(NotchStyle.large.rawValue, forKey: Prefs.notchStyle)
        d.set(false, forKey: Prefs.autoCopyOTP)
        FirstRunMotion.instant = true

        let files = DemoFiles.stage(from: demo)
        let state = AppState.shared
        state.loadVideoDemo(files: files, clock: clock)

        notch(state, files)
        dropTarget()
        liveBar(state)
        for dark in [false, true] {
            NSApp.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
            let sfx = dark ? "-dark" : ""
            state.loadVideoDemo(files: files, clock: clock)
            popover(state, sfx)
            windows(state, sfx)
        }

        let info: [String: Any] = [
            "about": "Stills of the real Mac n Droid views (mac/Sources/MacNDroid/Debug/VideoShots.swift). Sizes are in points; pixels = points × scale.",
            "notch": ["width": NotchGeometry.current().notchSize.width, "height": NotchGeometry.current().notchSize.height,
                      "note": "Notch images are full panels: put the image's top centre on the screen's top centre."],
            "accent": NSColor.controlAccentColor.usingColorSpace(.sRGB).map { String(format: "#%02X%02X%02X", Int($0.redComponent * 255), Int($0.greenComponent * 255), Int($0.blueComponent * 255)) } ?? "",
            "clock": clock.formatted(date: .omitted, time: .shortened),
            "shots": manifest,
        ]
        if only == nil, let data = try? JSONSerialization.data(withJSONObject: info, options: [.prettyPrinted, .sortedKeys]) {
            try? data.write(to: out.appendingPathComponent("manifest.json"))
        }
        print("✓ \(manifest.count) captures in \(out.path)")
        exit(0)
    }

    // MARK: Notch

    /// The real panel is 560 × 220 (NotchController); every image uses that canvas.
    private static let notchPanel = CGSize(width: 560, height: 220)

    private static func notchModel(_ content: NotchModel.Content, style: NotchStyle = .large) -> NotchModel {
        let m = NotchModel()
        m.style = style
        m.expanded = true
        m.content = content
        return m
    }

    private static func notchShot(_ name: String, _ m: NotchModel, note: String, settle: TimeInterval = 0.5) {
        shot(name, NotchView(model: m).frame(width: notchPanel.width, height: notchPanel.height, alignment: .top),
             size: notchPanel, kind: "notch", note: note, settle: settle)
    }

    private static func notch(_ state: AppState, _ files: [String: URL]) {
        let msgIcon = state.icons["demo.messages"]
        let priya = AppState.videoPriya(at: Date())

        notchShot("notch/01-priya", notchModel(.notification(priya, msgIcon)), note: "Priya's text drops from the notch: Reply, Mark as read")
        let hover = notchModel(.notification(priya, msgIcon))
        hover.hovering = true
        hover.page = NotchModel.Page(index: 0, total: 3)
        notchShot("notch/02-priya-hover-pager", hover, note: "Pointer over it: the pager for recent notifications")

        let reply = notchModel(.notification(priya, msgIcon))
        reply.replying = true
        notchShot("notch/03-priya-reply-empty", reply, note: "Reply clicked: the field, Cancel and Send")
        // One frame per typed character, so the typing can be played back at any speed.
        let text = "Sounds good, see you there!"
        for n in 1...text.count {
            reply.replyText = String(text.prefix(n))
            notchShot(String(format: "notch/04-priya-reply-typing/%03d", n), reply, note: "Typing the reply, character \(n) of \(text.count)", settle: 0.25)
        }
        notchShot("notch/05-priya-reply-typed", reply, note: "The whole reply typed, Send ready")
        notchShot("notch/06-reply-sent", notchModel(.status("arrowshape.turn.up.left.fill", "Reply sent"), style: .small),
                  note: "After Send: the pill beside the notch")

        let code = AppState.videoCode(at: Date())
        let codeModel = notchModel(.notification(code, msgIcon))
        notchShot("notch/07-code", codeModel, note: "Northwind Bank: Copy 482913")
        codeModel.copied = true
        notchShot("notch/08-code-copied", codeModel, note: "After the click: Copied")

        let call = AppState.videoCall(at: Date())
        notchShot("notch/09-call", notchModel(.notification(call, state.icons["demo.phone"])), note: "Incoming call from Sam: Answer, Decline")

        if let menu = files["dinner-menu.jpg"] {
            notchShot("notch/10-file-dinner-menu", notchModel(.files([menu], "Pixel 8")), note: "dinner-menu.jpg arrived from the phone", settle: 1.8)
        }
        if let deck = files["Q4-deck.pdf"] {
            notchShot("notch/11-file-q4-deck", notchModel(.files([deck], "Pixel 8")), note: "Q4-deck.pdf arrived from the phone", settle: 1.8)
        }
        for (i, (symbol, text)) in [("doc.on.clipboard.fill", "Copied from Pixel 8"), ("photo.on.rectangle", "Image copied from Pixel 8"),
                                    ("doc.on.clipboard.fill", "Copied to phone")].enumerated() {
            notchShot("notch/1\(i + 2)-pill-\(text.lowercased().replacingOccurrences(of: " ", with: "-"))",
                      notchModel(.status(symbol, text), style: .small), note: "Pill beside the notch: \(text)")
        }
        notchShot("notch/15-priya-compact", notchModel(.notification(priya, msgIcon), style: .compact), note: "The Compact notch style, for comparison")
    }

    /// The drop target while dragging files to the notch (panel 560 × 260, NotchDropController).
    private static func dropTarget() {
        let panel = CGSize(width: 560, height: 260)
        func drop(_ name: String, note: String, _ set: (NotchDropModel) -> Void) {
            let m = NotchDropModel()
            m.phoneName = "Pixel 8"
            m.expanded = true
            set(m)
            shot(name, NotchDropView(model: m).frame(width: panel.width, height: panel.height, alignment: .top),
                 size: panel, kind: "notch", note: note)
        }
        drop("drop/01-open", note: "Dragging files near the notch: Drop to send to Pixel 8") { _ in }
        drop("drop/02-over", note: "Files held over it: the target lights up in the Mac's accent colour") { $0.targeted = true }
        drop("drop/03-sending-folder", note: "Let go: Sending holiday-photos") { $0.targeted = true; $0.sending = true; $0.sentText = "Sending holiday-photos" }
        drop("drop/04-link-open", note: "Dragging a link: Drop to open on Pixel 8") { $0.isLink = true }
    }

    /// Music beside the notch (panel 720 × 340, LiveBarController).
    private static func liveBar(_ state: AppState) {
        guard let np = state.nowPlaying else { return }
        let panel = CGSize(width: 720, height: 340)
        for (name, expanded, note) in [("live/01-music-collapsed", false, "Golden Hour Drive playing on the phone: cover and equalizer beside the notch"),
                                       ("live/02-music-expanded", true, "Clicked: title, progress, controls and the phone's volume")] {
            let m = LiveBarModel()
            m.items = [.media(np)]
            m.mediaArt = state.playerArt[np.id]
            m.playerArt = state.playerArt
            m.icons = state.icons
            m.players = [np]
            m.volume = 70
            m.expanded = expanded
            shot(name, LiveBarView(model: m).frame(width: panel.width, height: panel.height, alignment: .top),
                 size: panel, kind: "notch", note: note)
        }
    }

    // MARK: Menu bar

    private static func popover(_ state: AppState, _ sfx: String) {
        // The popover's glass panel belongs to macOS; here the content sits on the plain window colour.
        func pop(_ name: String, note: String) {
            shot(name + sfx, MenuBarView().environmentObject(state).background(Color(nsColor: .windowBackgroundColor)),
                 kind: "popover", note: note, clear: false)
        }
        pop("menubar/01-popover", note: "Menu bar popover: Ring, Send File, Clipboard, Notch, Mirror Screen, music, notifications")
        state.isRinging = true
        pop("menubar/02-popover-ringing", note: "After Ring: the tile turns into Stop")
        state.isRinging = false
        shot("menubar/03-icon" + sfx, MenuBarLabel().environmentObject(state).padding(6), kind: "menubar",
             note: "The menu bar item: logo and the phone's battery")
    }

    // MARK: Windows

    private static func windows(_ state: AppState, _ sfx: String) {
        let size = CGSize(width: 1000, height: 640)
        func main(_ pane: Pane, _ name: String, note: String, height: CGFloat = 640, after: (() -> Void)? = nil) {
            shot("window/" + name + sfx, MainView(initialPane: pane).environmentObject(state),
                 size: CGSize(width: size.width, height: height), kind: "window", note: note, titled: true, onScreen: true, after: after)
        }
        main(.overview, "01-overview", note: "Overview: Pixel 8, 82%, quick actions, music")
        main(.notifications, "02-notifications", note: "Notifications from the phone")
        main(.messages, "03-messages-priya", note: "Messages: the conversation with Priya", after: { state.videoDemoReselectThread() })
        state.videoDemoReplied()
        main(.messages, "04-messages-priya-replied", note: "Messages after the reply went out", after: { state.videoDemoReselectThread() })
        main(.clipboard, "05-clipboard", note: "Clipboard history, the Osteria Lucca address on top")
        main(.files, "06-files", note: "Files: dinner-menu.jpg and Q4-deck.pdf received", after: { RunLoop.main.run(until: Date().addingTimeInterval(1.5)) })
        main(.files, "06b-files-sent", note: "Files, Sent: the holiday-photos folder", after: { state.storeDemoShowSent() })

        let first = CGSize(width: 1000, height: 700)
        shot("window/07-pair-qr" + sfx, ZStack { Color(nsColor: .windowBackgroundColor); OnboardingPairView(onLater: {}) }.environmentObject(state),
             size: first, kind: "window", note: "Pairing: the QR code (a sample code, not a real key)", titled: true, onScreen: true)
        shot("window/08-connected" + sfx, ZStack { Color(nsColor: .windowBackgroundColor); ConnectedView(phoneName: "Pixel 8", onContinue: {}) }.environmentObject(state),
             size: first, kind: "window", note: "Right after pairing: connected", titled: true, onScreen: true)

        UserDefaults.standard.set(false, forKey: Prefs.autoCopyOTP)
        shot("window/09-settings-sync" + sfx, SettingsView(initialTab: 1).environmentObject(state),
             kind: "window", note: "Settings, Sync: Copy verification codes automatically (off)", titled: true, onScreen: true)
        UserDefaults.standard.set(true, forKey: Prefs.autoCopyOTP)
        shot("window/10-settings-sync-codes-on" + sfx, SettingsView(initialTab: 1).environmentObject(state),
             kind: "window", note: "The same, with automatic copying of codes on", titled: true, onScreen: true)
        UserDefaults.standard.set(false, forKey: Prefs.autoCopyOTP)

        // The Phone Screen window. The black area is where the phone's picture goes (a real phone capture).
        if sfx.isEmpty {
            shot("window/11-mirror", MirrorWindowView().environmentObject(state), size: CGSize(width: 380, height: 820),
                 kind: "window", note: "Phone Screen window while mirroring. Put a phone capture in the black area.", titled: true, onScreen: true,
                 after: { state.videoDemoMirror(CGSize(width: 1080, height: 2400)) })
            state.stopMirroring()
        }
    }

    // MARK: Capturing

    private static func shot<V: View>(_ name: String, _ view: V, size: CGSize? = nil, kind: String, note: String,
                                      titled: Bool = false, onScreen: Bool = false, clear: Bool = true,
                                      settle: TimeInterval = 0.6, after: (() -> Void)? = nil) {
        if let only, !only.contains(where: name.contains) { return }
        let url = out.appendingPathComponent(name + ".png")
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let host = NSHostingView(rootView: view.environment(\.controlActiveState, .key))
        let fit = size ?? host.fittingSize
        let style: NSWindow.StyleMask = titled ? [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView] : [.borderless]
        let w = KeyWindow(contentRect: CGRect(origin: .zero, size: fit), styleMask: style, backing: .buffered, defer: false)
        w.titlebarAppearsTransparent = titled
        w.isReleasedWhenClosed = false
        if clear && !titled { w.isOpaque = false; w.backgroundColor = .clear }
        w.contentView = host
        w.ignoresMouseEvents = true
        if onScreen {
            // On a display so lists and sidebars draw, but under the desktop picture: never visible.
            w.level = NSWindow.Level(rawValue: Int(CGWindowLevelForKey(.desktopWindow)) - 1)
            w.collectionBehavior = [.stationary, .ignoresCycle, .canJoinAllSpaces]
            let vf = NSScreen.main?.visibleFrame ?? .zero
            w.setFrame(CGRect(origin: CGPoint(x: vf.minX + 20, y: vf.minY + 20), size: w.frame.size), display: true)
        } else {
            w.setFrame(CGRect(origin: CGPoint(x: -20000, y: -20000), size: w.frame.size), display: true)
            w.alphaValue = 0
        }
        w.orderFrontRegardless()
        RunLoop.main.run(until: Date().addingTimeInterval(onScreen ? max(settle, 1.2) : settle))
        if let after { after(); RunLoop.main.run(until: Date().addingTimeInterval(1.6)) }
        host.layoutSubtreeIfNeeded()
        // Taken apart, not just hidden: a hidden window's views would still react to the next capture's data.
        defer { w.orderOut(nil); w.contentView = nil; w.close() }

        var how = "offscreen"
        var pixelScale = scale
        var points = (titled ? (w.contentView?.superview ?? host) : host).bounds.size
        if onScreen, windowCapture(w.windowNumber, to: url), let rep = NSImageRep(contentsOf: url), rep.pixelsWide > 0 {
            how = "window"
            pixelScale = w.backingScaleFactor
            points = CGSize(width: CGFloat(rep.pixelsWide) / pixelScale, height: CGFloat(rep.pixelsHigh) / pixelScale)
        } else {
            if onScreen { print("! \(name): the window capture isn't available here; drawn offscreen instead (lists and sidebars may be empty)") }
            let target = titled ? (w.contentView?.superview ?? host) : host
            let b = target.bounds
            let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(b.width * scale), pixelsHigh: Int(b.height * scale),
                                       bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                                       colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
            rep.size = b.size
            target.cacheDisplay(in: b, to: rep)
            try? rep.representation(using: .png, properties: [:])?.write(to: url)
        }
        manifest.append(["file": name + ".png", "kind": kind, "note": note, "width": points.width, "height": points.height,
                         "scale": pixelScale, "capture": how])
        print("✓ \(name).png  \(Int(points.width))×\(Int(points.height)) @\(Int(pixelScale))x  (\(how))")
    }

    /// macOS's own window capture (the same one ⇧⌘4 uses), without the shadow. It photographs one window
    /// wherever it is in the window stack.
    private static func windowCapture(_ id: Int, to url: URL) -> Bool {
        try? FileManager.default.removeItem(at: url)
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/sbin/screencapture")
        p.arguments = ["-x", "-o", "-l", String(id), url.path]
        p.standardError = FileHandle.nullDevice
        guard (try? p.run()) != nil else { return false }
        p.waitUntilExit()
        return p.terminationStatus == 0 && FileManager.default.fileExists(atPath: url.path)
    }
}

/// The series' demo files: invented, drawn here, and kept in video/series/demo/ so the Mac stills, the
/// fake phone and the Android stills all use the same ones.
enum DemoFiles {
    /// Copies the demo files into a folder called Downloads (the notch says "In Downloads").
    static func stage(from source: URL?) -> [String: URL] {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("mnd-video-demo/Downloads", isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let names = ["dinner-menu.jpg", "Q4-deck.pdf", "holiday-photos"]
        let cover = dir.deletingLastPathComponent().appendingPathComponent("golden-hour-drive.jpg")
        if let source, FileManager.default.fileExists(atPath: source.appendingPathComponent("dinner-menu.jpg").path) {
            for name in names { try? FileManager.default.copyItem(at: source.appendingPathComponent(name), to: dir.appendingPathComponent(name)) }
            try? FileManager.default.removeItem(at: cover)
            try? FileManager.default.copyItem(at: source.appendingPathComponent("golden-hour-drive.jpg"), to: cover)
        } else {
            write(to: dir)
            try? FileManager.default.removeItem(at: cover)
            try? FileManager.default.moveItem(at: dir.appendingPathComponent("golden-hour-drive.jpg"), to: cover)
        }
        var result: [String: URL] = ["golden-hour-drive.jpg": cover]
        for name in names where FileManager.default.fileExists(atPath: dir.appendingPathComponent(name).path) {
            result[name] = dir.appendingPathComponent(name)
        }
        return result
    }

    static func write(to dir: URL) {
        let fm = FileManager.default
        try? fm.createDirectory(at: dir.appendingPathComponent("holiday-photos", isDirectory: true), withIntermediateDirectories: true)
        save(menu(), jpeg: dir.appendingPathComponent("dinner-menu.jpg"))
        save(cover(), jpeg: dir.appendingPathComponent("golden-hour-drive.jpg"))
        let skies: [(NSColor, NSColor, NSColor)] = [
            (.init(red: 0.36, green: 0.67, blue: 0.93, alpha: 1), .init(red: 0.80, green: 0.91, blue: 0.98, alpha: 1), .init(red: 0.93, green: 0.84, blue: 0.62, alpha: 1)),
            (.init(red: 0.98, green: 0.62, blue: 0.36, alpha: 1), .init(red: 0.99, green: 0.84, blue: 0.60, alpha: 1), .init(red: 0.20, green: 0.36, blue: 0.52, alpha: 1)),
            (.init(red: 0.25, green: 0.55, blue: 0.80, alpha: 1), .init(red: 0.70, green: 0.87, blue: 0.95, alpha: 1), .init(red: 0.24, green: 0.52, blue: 0.33, alpha: 1)),
            (.init(red: 0.52, green: 0.42, blue: 0.74, alpha: 1), .init(red: 0.98, green: 0.72, blue: 0.62, alpha: 1), .init(red: 0.16, green: 0.22, blue: 0.38, alpha: 1)),
        ]
        for (i, s) in skies.enumerated() {
            save(landscape(s.0, s.1, s.2, sun: CGFloat(i) * 0.22 + 0.15), jpeg: dir.appendingPathComponent(String(format: "holiday-photos/IMG_%04d.jpg", 2041 + i)))
        }
        deck(to: dir.appendingPathComponent("Q4-deck.pdf"))
        print("✓ demo files in \(dir.path)")
    }

    private static func bitmap(_ w: Int, _ h: Int, _ draw: (CGRect) -> Void) -> NSBitmapImageRep {
        let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: w, pixelsHigh: h, bitsPerSample: 8, samplesPerPixel: 4,
                                   hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
        draw(CGRect(x: 0, y: 0, width: w, height: h))
        NSGraphicsContext.restoreGraphicsState()
        return rep
    }

    private static func save(_ rep: NSBitmapImageRep, jpeg url: URL) {
        try? rep.representation(using: .jpeg, properties: [.compressionFactor: 0.86])?.write(to: url)
    }

    private static func text(_ s: String, _ font: NSFont, _ color: NSColor, centeredAt y: CGFloat, in r: CGRect, kern: CGFloat = 0) {
        let a = NSAttributedString(string: s, attributes: [.font: font, .foregroundColor: color, .kern: kern])
        let size = a.size()
        a.draw(at: CGPoint(x: r.midX - size.width / 2, y: y))
    }

    /// A restaurant menu card, as if photographed on the phone.
    private static func menu() -> NSBitmapImageRep {
        bitmap(1200, 1600) { r in
            NSColor(red: 0.96, green: 0.94, blue: 0.89, alpha: 1).setFill(); r.fill()
            let ink = NSColor(red: 0.17, green: 0.15, blue: 0.13, alpha: 1)
            let soft = NSColor(red: 0.45, green: 0.40, blue: 0.35, alpha: 1)
            let serif = { (size: CGFloat, bold: Bool) in NSFont(name: bold ? "Georgia-Bold" : "Georgia", size: size) ?? .systemFont(ofSize: size) }
            NSColor(red: 0.62, green: 0.20, blue: 0.16, alpha: 1).setStroke()
            let frame = NSBezierPath(rect: r.insetBy(dx: 60, dy: 60)); frame.lineWidth = 3; frame.stroke()
            text("OSTERIA LUCCA", serif(84, true), ink, centeredAt: 1320, in: r, kern: 8)
            text("42 Elm Street", serif(34, false), soft, centeredAt: 1262, in: r, kern: 3)
            text("DINNER", serif(30, false), NSColor(red: 0.62, green: 0.20, blue: 0.16, alpha: 1), centeredAt: 1150, in: r, kern: 12)
            let dishes = [("Burrata, tomato, basil", "9"), ("Tagliatelle al ragù", "16"), ("Cacio e pepe", "14"), ("Mushroom risotto", "17"),
                          ("Sea bass, lemon, capers", "22"), ("Tiramisù", "8"), ("Panna cotta", "7")]
            for (i, d) in dishes.enumerated() {
                let y = 1010 - CGFloat(i) * 120
                NSAttributedString(string: d.0, attributes: [.font: serif(44, false), .foregroundColor: ink]).draw(at: CGPoint(x: 150, y: y))
                let price = NSAttributedString(string: d.1, attributes: [.font: serif(44, false), .foregroundColor: soft])
                price.draw(at: CGPoint(x: r.maxX - 150 - price.size().width, y: y))
                soft.withAlphaComponent(0.3).setFill()
                CGRect(x: 150, y: y - 26, width: r.width - 300, height: 1.5).fill()
            }
            text("Buon appetito", serif(36, false), soft, centeredAt: 130, in: r, kern: 2)
        }
    }

    /// Cover of "Golden Hour Drive" by Lumen Field: a low sun over a road.
    private static func cover() -> NSBitmapImageRep {
        bitmap(800, 800) { r in
            NSGradient(colors: [NSColor(red: 0.20, green: 0.11, blue: 0.36, alpha: 1), NSColor(red: 0.93, green: 0.33, blue: 0.38, alpha: 1),
                                NSColor(red: 1.0, green: 0.70, blue: 0.32, alpha: 1)])?.draw(in: r, angle: -90)
            NSColor(red: 1, green: 0.93, blue: 0.72, alpha: 0.95).setFill()
            NSBezierPath(ovalIn: CGRect(x: 270, y: 250, width: 260, height: 260)).fill()
            NSColor(red: 0.12, green: 0.07, blue: 0.22, alpha: 1).setFill()
            CGRect(x: 0, y: 0, width: 800, height: 300).fill()
            let road = NSBezierPath()
            road.move(to: CGPoint(x: 250, y: 0)); road.line(to: CGPoint(x: 385, y: 300)); road.line(to: CGPoint(x: 415, y: 300)); road.line(to: CGPoint(x: 550, y: 0)); road.close()
            NSColor(red: 0.22, green: 0.15, blue: 0.34, alpha: 1).setFill(); road.fill()
            NSColor(red: 1, green: 0.85, blue: 0.55, alpha: 0.9).setFill()
            for (y, w, h) in [(30.0, 14.0, 70.0), (140.0, 9.0, 46.0), (215.0, 5.0, 28.0), (262.0, 3.0, 16.0)] {
                CGRect(x: 400 - w / 2, y: y, width: w, height: h).fill()
            }
        }
    }

    private static func landscape(_ top: NSColor, _ horizon: NSColor, _ ground: NSColor, sun: CGFloat) -> NSBitmapImageRep {
        bitmap(1600, 1200) { r in
            NSGradient(starting: horizon, ending: top)?.draw(in: r, angle: 90)
            NSColor(white: 1, alpha: 0.85).setFill()
            NSBezierPath(ovalIn: CGRect(x: r.width * sun, y: 640, width: 170, height: 170)).fill()
            for (i, shade) in [0.75, 0.9, 1.0].enumerated() {
                let hill = NSBezierPath()
                let base = 520 - CGFloat(i) * 150
                hill.move(to: CGPoint(x: 0, y: 0)); hill.line(to: CGPoint(x: 0, y: base))
                hill.curve(to: CGPoint(x: r.maxX, y: base - 60 + CGFloat(i) * 50),
                           controlPoint1: CGPoint(x: 500 + CGFloat(i) * 180, y: base + 220 - CGFloat(i) * 60),
                           controlPoint2: CGPoint(x: 1050 - CGFloat(i) * 150, y: base - 160))
                hill.line(to: CGPoint(x: r.maxX, y: 0)); hill.close()
                (ground.blended(withFraction: 1 - shade, of: horizon) ?? ground).setFill()
                hill.fill()
            }
        }
    }

    private static func deck(to url: URL) {
        var box = CGRect(x: 0, y: 0, width: 960, height: 540)
        guard let ctx = CGContext(url as CFURL, mediaBox: &box, nil) else { return }
        let pages: [(String, String)] = [("Q4 Review", "Team update"), ("Highlights", "Three things that went well"), ("Next quarter", "What we do first")]
        for (title, sub) in pages {
            ctx.beginPDFPage(nil)
            NSGraphicsContext.saveGraphicsState()
            NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: false)
            NSColor(red: 0.07, green: 0.09, blue: 0.14, alpha: 1).setFill(); box.fill()
            // Centred, so the square thumbnail the app shows keeps the title.
            let t = NSAttributedString(string: title, attributes: [.font: NSFont.systemFont(ofSize: 76, weight: .bold), .foregroundColor: NSColor.white])
            let u = NSAttributedString(string: sub, attributes: [.font: NSFont.systemFont(ofSize: 28, weight: .regular), .foregroundColor: NSColor(white: 1, alpha: 0.65)])
            NSColor(red: 0.35, green: 0.62, blue: 1, alpha: 1).setFill()
            CGRect(x: box.midX - 32, y: 350, width: 64, height: 6).fill()
            t.draw(at: CGPoint(x: box.midX - t.size().width / 2, y: 240))
            u.draw(at: CGPoint(x: box.midX - u.size().width / 2, y: 190))
            NSGraphicsContext.restoreGraphicsState()
            ctx.endPDFPage()
        }
        ctx.closePDF()
    }
}
#endif
