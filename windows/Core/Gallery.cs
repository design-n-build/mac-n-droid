using System.Text.Json.Nodes;

namespace MacNDroid.Core;

/// <summary>One photo or video on the phone (<c>gallery</c> in PROTOCOL.md).</summary>
public sealed record GalleryItem(string Id, bool IsVideo, string Name, DateTimeOffset Date, long Size, int Width, int Height, double Duration);

public sealed record GalleryAlbum(string Id, string Name, int Count);

/// <summary>The phone's photo and video library, loaded a page at a time, as on the Mac (Gallery.swift). Thumbnails
/// are asked for as tiles are made; the full file only when you open or save one. Opened files are kept in
/// %LOCALAPPDATA%\Mac n Droid\Gallery so opening one again is instant; they are deleted if Photos is turned off.</summary>
public sealed class GalleryModel
{
    const int PageSize = 120;
    readonly Action<JsonObject> _send;
    readonly Action<Action> _dispatch;
    int? _requestedOffset;
    readonly List<string> _wantedThumbs = new();
    readonly HashSet<string> _askedThumbs = new();
    bool _flushQueued;

    public GalleryModel(Action<JsonObject> send, Action<Action> dispatch)
    {
        _send = send;
        _dispatch = dispatch;
    }

    public List<GalleryItem> Items { get; } = new();
    public List<GalleryAlbum> Albums { get; private set; } = new();
    public int Total { get; private set; }
    /// <summary>unknown | full | photos | partial | none | off</summary>
    public string Access { get; private set; } = "unknown";
    public bool Loading { get; private set; }
    public string Kind { get; private set; } = "all";
    public string? Album { get; private set; }
    public Dictionary<string, byte[]> Thumbs { get; } = new();
    public HashSet<string> Opening { get; } = new();
    public bool Connected { get; private set; }
    public bool HasMore => Items.Count < Total;

    /// <summary>The list, filter or access changed (rebuild the grid).</summary>
    public event Action? Changed;
    /// <summary>Items were added to the end of the list (append tiles); the argument is the first new index.</summary>
    public event Action<int>? Appended;
    public event Action<string>? ThumbArrived;
    /// <summary>A file opened with "view" has arrived: open it with the default app.</summary>
    public event Action<string>? Opened;

    public static string CacheDir
    {
        get
        {
            var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Mac n Droid", "Gallery");
            Directory.CreateDirectory(dir);
            return dir;
        }
    }

    public void SetFilter(string kind, string? album)
    {
        if (kind == Kind && album == Album) return;
        Kind = kind;
        Album = album;
        Reload();
    }

    public void Reload()
    {
        Items.Clear();
        Total = 0;
        _requestedOffset = null;
        Changed?.Invoke();
        LoadMore();
    }

    public void LoadIfNeeded()
    {
        if (Items.Count == 0 && !Loading) Reload();
    }

    public void LoadMore()
    {
        if (!Connected) return;
        var offset = Items.Count;
        if (_requestedOffset == offset) return;
        _requestedOffset = offset;
        Loading = true;
        var msg = new JsonObject { ["t"] = "gallery_list", ["offset"] = offset, ["limit"] = PageSize, ["kind"] = Kind };
        if (Album != null) msg["album"] = Album;
        _send(msg);
    }

    public void AskForAccess() => _send(new JsonObject { ["t"] = "gallery_ask" });

    /// <summary>A tile was made: batch up its thumbnail request.</summary>
    public void WantThumb(string id)
    {
        if (Thumbs.ContainsKey(id) || !_askedThumbs.Add(id)) return;
        _wantedThumbs.Add(id);
        if (_flushQueued) return;
        _flushQueued = true;
        _ = Task.Delay(50).ContinueWith(_ => _dispatch(FlushThumbs));
    }

    void FlushThumbs()
    {
        _flushQueued = false;
        if (!Connected) return;
        while (_wantedThumbs.Count > 0)
        {
            var batch = _wantedThumbs.Take(40).ToList();
            _wantedThumbs.RemoveRange(0, batch.Count);
            _send(new JsonObject { ["t"] = "gallery_thumbs", ["ids"] = new JsonArray(batch.Select(x => (JsonNode)x).ToArray()), ["size"] = 280 });
        }
    }

    /// <summary>Opens with the default app (fetching the original first, unless it was opened before).</summary>
    public void Open(GalleryItem item)
    {
        if (CachedPath(item) is { } cached) { Opened?.Invoke(cached); return; }
        if (!Connected || !Opening.Add(item.Id)) return;
        Changed?.Invoke();
        _send(new JsonObject { ["t"] = "gallery_get", ["id"] = item.Id, ["mode"] = "view" });
    }

    /// <summary>Saves originals to Downloads, through the normal transfer list.</summary>
    public void Save(IEnumerable<GalleryItem> list, string downloads)
    {
        foreach (var item in list)
        {
            if (CachedPath(item) is { } cached)
            {
                Directory.CreateDirectory(downloads);
                var dest = Path.Combine(downloads, SafeName(item.Name));
                var stem = Path.GetFileNameWithoutExtension(dest);
                var ext = Path.GetExtension(dest);
                for (int n = 2; File.Exists(dest); n++) dest = Path.Combine(downloads, $"{stem} {n}{ext}");
                try { File.Copy(cached, dest); } catch (IOException) { }
                continue;
            }
            _send(new JsonObject { ["t"] = "gallery_get", ["id"] = item.Id, ["mode"] = "save" });
        }
    }

    public static string SafeName(string name)
    {
        var bad = Path.GetInvalidFileNameChars();
        var clean = new string(Path.GetFileName(name).Select(c => bad.Contains(c) ? '_' : c).ToArray()).Trim();
        return clean.Length == 0 || clean is "." or ".." ? "photo" : clean;
    }

    public static string CacheFileName(string id, string name) =>
        new string(id.Where(c => char.IsAsciiLetterOrDigit(c) || c is '-' or '_').Take(60).ToArray()) + "-" + SafeName(name);

    string? CachedPath(GalleryItem item)
    {
        var path = Path.Combine(CacheDir, CacheFileName(item.Id, item.Name));
        return File.Exists(path) ? path : null;
    }

    // MARK: From the phone

    public void Handle(JsonObject m)
    {
        switch ((string?)m["t"])
        {
            case "gallery":
                Access = (string?)m["access"] ?? "none";
                if (Access == "off") { Forget(); Loading = false; Changed?.Invoke(); return; }
                // Ignore pages for a filter that's no longer showing.
                if (((string?)m["kind"] ?? "all") != Kind || (string?)m["album"] != Album) return;
                int offset = (int)Num(m["offset"]);
                if (offset != Items.Count) { Loading = false; Changed?.Invoke(); return; }
                var first = Items.Count;
                foreach (var node in m["items"]?.AsArray() ?? new JsonArray())
                    if (node is JsonObject o && (string?)o["id"] is { } id)
                        Items.Add(new GalleryItem(id, (bool?)o["v"] == true, (string?)o["name"] ?? "Photo",
                            DateTimeOffset.FromUnixTimeMilliseconds((long)Num(o["date"])).ToLocalTime(), (long)Num(o["size"]),
                            (int)Num(o["w"]), (int)Num(o["h"]), Num(o["dur"]) / 1000));
                Total = m["total"] != null ? (int)Num(m["total"]) : Items.Count;
                if (m["albums"] is JsonArray albums)
                    Albums = albums.OfType<JsonObject>().Where(a => (string?)a["id"] != null)
                        .Select(a => new GalleryAlbum((string)a["id"]!, (string?)a["name"] ?? "Album", (int)Num(a["count"]))).ToList();
                Loading = false;
                if (first == 0) Changed?.Invoke(); else Appended?.Invoke(first);
                break;
            case "gallery_thumb":
                if ((string?)m["id"] is not { } tid || (string?)m["d"] is not { } d) return;
                try { Thumbs[tid] = Convert.FromBase64String(d); } catch (FormatException) { return; }
                ThumbArrived?.Invoke(tid);
                break;
            case "gallery_access":
                var access = (string?)m["access"] ?? "none";
                if (access == "off") { Access = access; Forget(); Changed?.Invoke(); return; }
                if (access != Access || Items.Count == 0) { Access = access; Reload(); }
                break;
        }
    }

    /// <summary>Turned off on the phone: drop everything shown and the originals opened before.</summary>
    void Forget()
    {
        Items.Clear(); Albums = new(); Total = 0; Thumbs.Clear(); _askedThumbs.Clear(); Opening.Clear();
        _requestedOffset = null;
        foreach (var f in Directory.EnumerateFiles(CacheDir)) try { File.Delete(f); } catch (IOException) { }
    }

    /// <summary>An original asked for with "view" has arrived.</summary>
    public void Arrived(string id, string path)
    {
        Opening.Remove(id);
        Changed?.Invoke();
        Opened?.Invoke(path);
    }

    public void PhoneConnected()
    {
        Connected = true;
        _askedThumbs.Clear();
        foreach (var k in Thumbs.Keys) _askedThumbs.Add(k);
        if (Items.Count > 0 || Access != "unknown") Reload();
    }

    public void PhoneDisconnected()
    {
        Connected = false;
        Loading = false;
        Opening.Clear();
        _requestedOffset = null;
        _wantedThumbs.Clear();
        Changed?.Invoke();
    }

    static double Num(JsonNode? n)
    {
        try { return n?.GetValue<double>() ?? 0; } catch (Exception e) when (e is FormatException or InvalidOperationException) { return 0; }
    }
}
