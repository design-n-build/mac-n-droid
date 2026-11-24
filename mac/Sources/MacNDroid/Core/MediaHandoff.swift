import Foundation

/// Moving what's playing to the other device: the address that plays the same thing there.
///
/// The exact page when it's known (a browser tab with the extension 0.14.5+, a phone that reports it, a Spotify
/// track), at the same spot where the site supports it (YouTube's `t=`). Otherwise a search for the title and
/// artist in the app it was playing in, or YouTube Music.
enum MediaHandoff {
    /// Something playing on the phone → a page to open on this Mac.
    static func macURL(for np: NowPlaying, at now: Date = Date()) -> URL? {
        let seconds = np.position(at: now) / 1000
        if let s = np.url, let url = URL(string: s), url.scheme == "https" { return withTime(url, seconds) }
        return search(app: np.pkg, title: np.title, artist: np.artist)
    }

    static let youTube = "com.google.android.youtube"
    static let youTubeMusic = "com.google.android.apps.youtube.music"

    /// Like `macURL`, but for YouTube and YouTube Music finds the video itself (its id from the phone, or the top
    /// search result for the title and channel) and opens it at the same spot. Calls back on the main queue.
    static func resolveMacURL(for np: NowPlaying, completion: @escaping (URL?) -> Void) {
        let seconds = np.position(at: Date()) / 1000
        if np.url != nil { completion(macURL(for: np)); return }
        let music = np.pkg == youTubeMusic
        guard np.pkg == youTube || music else { completion(macURL(for: np)); return }
        func watch(_ id: String) -> URL? {
            withTime(URL(string: "https://\(music ? "music" : "www").youtube.com/watch?v=\(id)")!, seconds)
        }
        if let id = np.mediaId, isVideoID(id) { completion(watch(id)); return }
        firstVideoID(query: [np.title, np.artist].filter { !$0.isEmpty }.joined(separator: " ")) { id in
            DispatchQueue.main.async { completion(id.flatMap(watch) ?? macURL(for: np)) }
        }
    }

    static func isVideoID(_ s: String) -> Bool {
        s.count == 11 && s.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }
    }

    /// The first video in YouTube's results for `query` (from the results page), or nil.
    static func firstVideoID(query: String, completion: @escaping (String?) -> Void) {
        var c = URLComponents(string: "https://www.youtube.com/results")!
        c.queryItems = [URLQueryItem(name: "search_query", value: query)]
        guard let url = c.url, !query.isEmpty else { completion(nil); return }
        var req = URLRequest(url: url, timeoutInterval: 6)
        req.setValue("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15",
                     forHTTPHeaderField: "User-Agent")
        req.setValue("en", forHTTPHeaderField: "Accept-Language")
        URLSession.shared.dataTask(with: req) { data, _, _ in
            guard let data, let html = String(data: data, encoding: .utf8),
                  let r = html.range(of: #""videoId":"([A-Za-z0-9_-]{11})""#, options: .regularExpression) else {
                completion(nil); return
            }
            completion(String(html[r].dropFirst(11).dropLast(1)))
        }.resume()
    }

    /// Something playing on this Mac → a page for the phone to open (it opens in the matching app).
    static func phoneURL(for t: MacMediaMonitor.Track) -> URL? {
        if let s = t.url, let url = URL(string: s), url.scheme == "https" { return withTime(url, t.position) }
        if t.bundle == MacMediaMonitor.spotify, t.id.hasPrefix("spotify:track:") {
            return URL(string: "https://open.spotify.com/track/" + t.id.dropFirst("spotify:track:".count))
        }
        let app = t.bundle == MacMediaMonitor.spotify ? "spotify" : t.app.lowercased()
        return search(app: app, title: t.title, artist: t.artist)
    }

    /// The same page, starting where it is now (YouTube and YouTube Music understand `t`).
    static func withTime(_ url: URL, _ seconds: Double) -> URL {
        guard seconds >= 5, let host = url.host?.lowercased(),
              host.hasSuffix("youtube.com") || host == "youtu.be",
              var c = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return url }
        var items = (c.queryItems ?? []).filter { $0.name != "t" }
        items.append(URLQueryItem(name: "t", value: "\(Int(seconds))s"))
        c.queryItems = items
        return c.url ?? url
    }

    /// Two addresses are the same page if they only differ in the start time or the part after "#".
    static func samePage(_ a: URL, _ b: URL) -> Bool {
        func key(_ u: URL) -> String? {
            guard var c = URLComponents(url: u, resolvingAgainstBaseURL: false) else { return nil }
            c.fragment = nil
            c.queryItems = c.queryItems?.filter { $0.name != "t" }
            if c.queryItems?.isEmpty == true { c.queryItems = nil }
            c.host = c.host?.lowercased().replacingOccurrences(of: "m.youtube.com", with: "www.youtube.com")
            return c.string
        }
        return key(a) != nil && key(a) == key(b)
    }

    /// A search for the title and artist in the right service. `app` is an Android package, "spotify", or a
    /// site name from the browser extension.
    private static func search(app: String, title: String, artist: String) -> URL? {
        let query = [title, artist].filter { !$0.isEmpty }.joined(separator: " ")
        guard !query.isEmpty else { return nil }
        var c: URLComponents
        let a = app.lowercased()
        if a.contains("spotify") {
            c = URLComponents(string: "https://open.spotify.com/search/")!
            c.path += query
        } else if a == "com.google.android.youtube" || a == "youtube" || a.hasSuffix(".youtube") {
            c = URLComponents(string: "https://www.youtube.com/results")!
            c.queryItems = [URLQueryItem(name: "search_query", value: query)]
        } else {
            c = URLComponents(string: "https://music.youtube.com/search")!
            c.queryItems = [URLQueryItem(name: "q", value: query)]
        }
        return c.url
    }
}
