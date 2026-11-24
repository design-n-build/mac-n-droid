namespace MacNDroid.Core;

/// <summary>Moving what's playing to the other device: the address that plays the same thing there. The exact page
/// when it's known (a browser tab with the extension 0.14.5+, a phone that reports it), at the same spot where the
/// site supports it (YouTube's <c>t=</c>). Otherwise a search for the title and artist in the app it was playing in,
/// or YouTube Music. Same rules as the Mac (MediaHandoff.swift).</summary>
public static class MediaHandoff
{
    /// <summary>Something playing on the phone → a page to open on this computer.</summary>
    public static string? ComputerUrl(Player p, string? url)
    {
        if (url is { } u && u.StartsWith("https://")) return WithTime(u, p.Now / 1000);
        return Search(p.Pkg, p.Title, p.Artist);
    }

    const string YouTube = "com.google.android.youtube", YouTubeMusic = "com.google.android.apps.youtube.music";
    static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(6) };

    /// <summary>Like <see cref="ComputerUrl"/>, but for YouTube and YouTube Music finds the video itself (its id from
    /// the phone, or the top search result for the title and channel) and opens it at the same spot.</summary>
    public static async Task<string?> ResolveComputerUrlAsync(Player p, string? url, string? mediaId)
    {
        bool music = p.Pkg == YouTubeMusic;
        if (url != null || (p.Pkg != YouTube && !music)) return ComputerUrl(p, url);
        var seconds = p.Now / 1000;
        string Watch(string id) => WithTime($"https://{(music ? "music" : "www")}.youtube.com/watch?v={id}", seconds);
        if (mediaId is { Length: 11 } m && m.All(c => char.IsAsciiLetterOrDigit(c) || c is '-' or '_')) return Watch(m);
        try
        {
            var q = Uri.EscapeDataString(string.Join(" ", new[] { p.Title, p.Artist }.Where(s => s.Length > 0)));
            using var req = new HttpRequestMessage(HttpMethod.Get, "https://www.youtube.com/results?search_query=" + q);
            req.Headers.TryAddWithoutValidation("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36");
            req.Headers.TryAddWithoutValidation("Accept-Language", "en");
            var html = await (await Http.SendAsync(req)).Content.ReadAsStringAsync();
            var match = System.Text.RegularExpressions.Regex.Match(html, "\"videoId\":\"([A-Za-z0-9_-]{11})\"");
            if (match.Success) return Watch(match.Groups[1].Value);
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException) { }
        return ComputerUrl(p, url);
    }

    /// <summary>Something playing on this computer → a page for the phone to open.</summary>
    public static string? PhoneUrl(ComputerTrack t)
    {
        if (t.Url is { } u && u.StartsWith("https://")) return WithTime(u, t.Position);
        return Search(t.App, t.Title, t.Artist);
    }

    /// <summary>The same page, starting where it is now (YouTube and YouTube Music understand <c>t</c>).</summary>
    public static string WithTime(string url, double seconds)
    {
        if (seconds < 5 || !Uri.TryCreate(url, UriKind.Absolute, out var u)) return url;
        var host = u.Host.ToLowerInvariant();
        if (!host.EndsWith("youtube.com") && host != "youtu.be") return url;
        var query = string.Join("&", u.Query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries)
                                            .Where(kv => !kv.StartsWith("t=")).Append($"t={(int)seconds}s"));
        return new UriBuilder(u) { Query = query }.Uri.AbsoluteUri;
    }

    /// <summary>Two addresses are the same page if they only differ in the start time or the part after "#".</summary>
    public static bool SamePage(string a, string b)
    {
        static string? Key(string s)
        {
            if (!Uri.TryCreate(s, UriKind.Absolute, out var u)) return null;
            var q = string.Join("&", u.Query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries).Where(kv => !kv.StartsWith("t=")));
            var host = u.Host.ToLowerInvariant() == "m.youtube.com" ? "www.youtube.com" : u.Host.ToLowerInvariant();
            return $"{u.Scheme}://{host}{u.AbsolutePath}?{q}";
        }
        return Key(a) is { } ka && ka == Key(b);
    }

    static string? Search(string app, string title, string artist)
    {
        var query = string.Join(" ", new[] { title, artist }.Where(s => s.Length > 0));
        if (query.Length == 0) return null;
        var q = Uri.EscapeDataString(query);
        var a = app.ToLowerInvariant();
        if (a.Contains("spotify")) return "https://open.spotify.com/search/" + q;
        if (a == "com.google.android.youtube" || a == "youtube" || a.EndsWith(".youtube")) return "https://www.youtube.com/results?search_query=" + q;
        return "https://music.youtube.com/search?q=" + q;
    }
}
