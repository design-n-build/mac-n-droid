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
