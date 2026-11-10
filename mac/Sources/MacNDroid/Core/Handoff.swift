import AppKit
import Carbon

/// Link handoff between the Mac and the phone.
///
/// Mac → phone: the tab you're looking at in Safari, Chrome, Arc, Edge, Brave or Vivaldi
/// (File → Send Browser Tab to Phone, ⇧⌘L), or any link via right-click → Services → Open on Phone.
/// Phone → Mac: Share → Open on Mac on the phone opens the link in the Mac's default browser.
enum Handoff {
    private static let safariFamily: Set<String> = ["com.apple.Safari", "com.apple.SafariTechnologyPreview", "com.kagi.kagimacOS"]

    /// Every app that can open web pages (Safari, Chrome, Arc, Edge, Brave, Firefox…).
    static var browsers: Set<String> {
        guard let probe = URL(string: "https://example.com") else { return [] }
        return Set(NSWorkspace.shared.urlsForApplications(toOpen: probe).compactMap { Bundle(url: $0)?.bundleIdentifier })
    }

    /// The browser you used last (the menu bar popover itself becomes the front app when clicked).
    private static var lastBrowser: String?
    private static var observer: Any?

    static func start() {
        let known = browsers
        if let front = NSWorkspace.shared.frontmostApplication?.bundleIdentifier, known.contains(front) { lastBrowser = front }
        observer = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didActivateApplicationNotification, object: nil, queue: .main) { n in
            let app = n.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication
            if let id = app?.bundleIdentifier, id != Bundle.main.bundleIdentifier, browsers.contains(id) { lastBrowser = id }
        }
    }

    enum TabResult { case url(URL), noBrowser, unsupported(String), denied(String), failed }

    /// Asks the browser for its current tab. The first time, macOS asks permission to control it.
    static func currentTab(completion: @escaping (TabResult) -> Void) {
        let known = browsers
        let runningApps = NSWorkspace.shared.runningApplications
        let running = Set(runningApps.compactMap(\.bundleIdentifier))
        let front = NSWorkspace.shared.frontmostApplication?.bundleIdentifier
        guard let bundle = [front, lastBrowser].compactMap({ $0 }).first(where: { known.contains($0) && running.contains($0) })
            ?? runningApps.first(where: { $0.activationPolicy == .regular && known.contains($0.bundleIdentifier ?? "") })?.bundleIdentifier
        else { completion(.noBrowser); return }
        let name = runningApps.first { $0.bundleIdentifier == bundle }?.localizedName ?? "your browser"
        let chromium = "tell application id \"\(bundle)\" to return URL of active tab of front window"
        let safari = "tell application id \"\(bundle)\" to return URL of front document"
        // Chromium browsers (Chrome, Arc, Edge, Brave…) use one wording, Safari another.
        let scripts = safariFamily.contains(bundle) ? [safari] : [chromium, safari]
        func attempt(_ i: Int) {
            AppleScriptRunner.run(scripts[i]) { result in
                switch result {
                case .success(let desc):
                    if let s = desc.stringValue, let url = URL(string: s), isWebLink(url) { completion(.url(url)) } else { completion(.failed) }
                case .failure(let e) where e.code == -1743:   // errAEEventNotPermitted
                    completion(.denied(name))
                case .failure:
                    if i + 1 < scripts.count { attempt(i + 1) } else { completion(.unsupported(name)) }
                }
            }
        }
        attempt(0)
    }

    static func isWebLink(_ url: URL) -> Bool {
        ["http", "https"].contains(url.scheme?.lowercased() ?? "") && !(url.host ?? "").isEmpty
    }

    /// First web link in some text.
    static func findLink(_ text: String) -> URL? {
        let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
        let range = NSRange(text.startIndex..., in: text)
        return detector?.matches(in: text, range: range).compactMap(\.url).first(where: isWebLink)
    }
}

/// A keyboard shortcut that works in every app (Carbon hot keys need no permission).
enum GlobalHotKey {
    private static var handlers: [UInt32: () -> Void] = [:]
    private static var installed = false

    static func register(id: UInt32, keyCode: Int, modifiers: Int, handler: @escaping () -> Void) {
        if !installed {
            installed = true
            var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
            InstallEventHandler(GetApplicationEventTarget(), { _, event, _ in
                var hk = EventHotKeyID()
                GetEventParameter(event, EventParamName(kEventParamDirectObject), EventParamType(typeEventHotKeyID),
                                  nil, MemoryLayout<EventHotKeyID>.size, nil, &hk)
                let id = hk.id
                DispatchQueue.main.async { GlobalHotKey.handlers[id]?() }
                return noErr
            }, 1, &spec, nil, nil)
        }
        handlers[id] = handler
        var ref: EventHotKeyRef?
        RegisterEventHotKey(UInt32(keyCode), UInt32(modifiers), EventHotKeyID(signature: OSType(0x4D4E4421), id: id),
                            GetApplicationEventTarget(), 0, &ref)
    }
}

/// Runs AppleScript off the main thread, one at a time.
enum AppleScriptRunner {
    private static let queue = DispatchQueue(label: "app.macndroid.applescript", qos: .userInitiated)

    static func run(_ source: String, completion: ((Result<NSAppleEventDescriptor, ScriptError>) -> Void)? = nil) {
        queue.async {
            var error: NSDictionary?
            let result = NSAppleScript(source: source)?.executeAndReturnError(&error)
            let outcome: Result<NSAppleEventDescriptor, ScriptError> = if let result, error == nil {
                .success(result)
            } else {
                .failure(ScriptError(code: (error?[NSAppleScript.errorNumber] as? Int) ?? -1))
            }
            if let completion { DispatchQueue.main.async { completion(outcome) } }
        }
    }

    struct ScriptError: Error, Equatable { let code: Int }

    /// Whether macOS already lets us control `bundle`, without asking. Call off the main thread.
    static func isAllowed(_ bundle: String) -> Bool {
        let target = NSAppleEventDescriptor(bundleIdentifier: bundle)
        guard let desc = target.aeDesc else { return false }
        return AEDeterminePermissionToAutomateTarget(desc, typeWildCard, typeWildCard, false) == noErr
    }
}

/// Right-click → Services → "Open on Phone" (links and text) and "Send to Phone" (files in Finder).
final class ServiceProvider: NSObject {
    @objc func openOnPhone(_ pboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        let url = (pboard.readObjects(forClasses: [NSURL.self], options: nil) as? [URL])?.first(where: Handoff.isWebLink)
            ?? pboard.string(forType: .string).flatMap(Handoff.findLink)
        guard let url else { error.pointee = "There's no web link to open." as NSString; return }
        DispatchQueue.main.async { AppState.shared.sendLink(url) }
    }

    @objc func sendToPhone(_ pboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        let urls = (pboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL]) ?? []
        guard !urls.isEmpty else { return }
        DispatchQueue.main.async { AppState.shared.sendFiles(urls) }
    }
}
