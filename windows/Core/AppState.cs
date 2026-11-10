using System.Text.Json.Nodes;

namespace MacNDroid.Core;

public sealed record PhoneNotification(string Key, string Pkg, string App, string Title, string Text, DateTimeOffset Time,
                                       bool IsCall, IReadOnlyList<NotifAction> Actions);

public sealed record NotifAction(int Index, string Title, bool IsReply, bool OpensOnPhone);

public sealed class Clip
{
    public string Id { get; } = Guid.NewGuid().ToString("N");
    public required string Kind { get; init; }  // text | image
    public string Text { get; init; } = "";
    public byte[] Png { get; init; } = [];
    public bool FromPhone { get; init; }
    public DateTimeOffset Time { get; } = DateTimeOffset.Now;
}

public sealed class Transfer
{
    public required string Id { get; init; }
    public required string Name { get; init; }
    public long Size { get; init; }
    public bool ToPhone { get; init; }
    public required string Path { get; init; }
    public long Bytes { get; set; }
    public string State { get; set; } = "active";  // active | done | failed
}

/// <summary>What the computer does that the core can't: clipboard, desktop notifications, opening links.</summary>
public interface IPlatform
{
    void SetClipboard(string? text, byte[]? png);
    /// <summary>A phone notification (key set) or a local message (key null).</summary>
    void ShowNotification(string title, string body, string? key);
    void WithdrawNotification(string key);
    void OpenUrl(string url);
    string DownloadsFolder { get; }
}

/// <summary>Everything the window shows, and what happens with each message from the phone. Runs on the UI thread
/// (the server's dispatch hands every callback to it).</summary>
public sealed class AppState
{
    const int Chunk = 256 * 1024;
    const int HistoryLimit = 20;
    readonly IPlatform _platform;
    readonly Dictionary<string, (FileStream file, string path, bool clip)> _incoming = new();
    readonly List<string> _received = new();
    CancellationTokenSource? _receivedTimer;

    public Identity Identity { get; } = Identity.Load();
    public Server Server { get; }
    public string Name { get; } = Net.ComputerName;
    public Session? Active { get; private set; }
    public JsonObject? Phone { get; private set; }
    public string LastPhoneName { get; set; } = "";
    public int? Battery { get; private set; }
    public bool Charging { get; private set; }
    public bool Ringing { get; private set; }
    public string LastError { get; private set; } = "";
    public List<PhoneNotification> Notifications { get; } = new();
    public Dictionary<string, byte[]> Icons { get; } = new();
    public List<Clip> Clips { get; } = new();
    public List<Transfer> Transfers { get; } = new();
    public bool MirrorNotifications { get; set; } = true;

    public event Action? StatusChanged, NotificationsChanged, ClipsChanged, TransfersChanged, PairingChanged;

    public AppState(IPlatform platform, Action<Action> dispatch, string osName)
    {
        _platform = platform;
        Server = new Server(Identity, Name, osName, dispatch);
        Server.Authenticated += OnConnected;
        Server.Message += OnMessage;
        Server.SessionClosed += OnClosed;
    }

    public void Start() => Server.Start();

    public bool Connected => Active != null;
    public string PhoneName => (string?)Phone?["name"] is { Length: > 0 } n ? n : LastPhoneName is { Length: > 0 } l ? l : "your phone";
    public List<string> Addresses => Net.LocalIPv4();
    public string QrText => Identity.QrText(Server.Port, Addresses, Name);
    public string PairingLink => Identity.PairingUri(Server.Port, Addresses, Name);

    public void ResetPairing()
    {
        Identity.Reset();
        Active?.Close("Pairing reset");
        PairingChanged?.Invoke();
    }

    public void RefreshAddresses() => PairingChanged?.Invoke();

    // MARK: Connection

    void OnConnected(Session s)
    {
        if (Active != null && Active != s) Active.Close("Replaced by a new connection");
        Active = s;
        Phone = s.Device;
        LastPhoneName = (string?)s.Device["name"] ?? "";
        LastError = "";
        StatusChanged?.Invoke();
        PushHistory();
    }

    void OnClosed(Session s, string? reason)
    {
        if (reason?.Contains("Pair it again") == true)
        {
            LastError = reason;
            StatusChanged?.Invoke();
        }
        if (Active != s) return;
        Active = null;
        Battery = null;
        Ringing = false;
        foreach (var n in Notifications) _platform.WithdrawNotification(n.Key);
        Notifications.Clear();
        foreach (var (id, f) in _incoming)
        {
            f.file.Dispose();
            try { File.Delete(f.path); } catch (IOException) { }
            Update(id, state: "failed");
        }
        _incoming.Clear();
        foreach (var t in Transfers.Where(t => t.State == "active")) t.State = "failed";
        StatusChanged?.Invoke();
        NotificationsChanged?.Invoke();
        TransfersChanged?.Invoke();
    }

    public void Send(JsonObject msg)
    {
        var s = Active;
        if (s != null) _ = s.SendAsync(msg);
    }

    // MARK: Messages from the phone

    void OnMessage(Session s, JsonObject m)
    {
        if (s != Active) return;
        switch ((string?)m["t"])
        {
            case "battery":
                Battery = (int?)m["level"];
                Charging = (bool?)m["charging"] ?? false;
                StatusChanged?.Invoke();
                break;
            case "notif":
                ReceiveNotification(m);
                break;
            case "notif_removed":
                var key = (string?)m["key"] ?? "";
                Notifications.RemoveAll(n => n.Key == key);
                _platform.WithdrawNotification(key);
                NotificationsChanged?.Invoke();
                break;
            case "clip":
                if ((string?)m["text"] is { } text)
                {
                    _platform.SetClipboard(text, null);
                    Record(new Clip { Kind = "text", Text = text, FromPhone = true });
                    if ((bool?)m["explicit"] == true)
                        _platform.ShowNotification($"Copied from {PhoneName}", text.Length > 120 ? text[..120] : text, null);
                }
                break;
            case "ring_stopped":
                Ringing = false;
                StatusChanged?.Invoke();
                break;
            case "open_url":
                if ((string?)m["url"] is { } url && (url.StartsWith("http://") || url.StartsWith("https://")))
                    _platform.OpenUrl(url);
                break;
            case "clip_get":
                if (FindClip(m) is { } c1)
                {
                    if (c1.Kind == "text") Send(new JsonObject { ["t"] = "clip", ["text"] = c1.Text, ["explicit"] = true });
                    else SendPng(c1.Png);
                }
                break;
            case "clip_use":
                if (FindClip(m) is { } c2) UseClip(c2);
                break;
            case "clip_clear":
                ClearHistory();
                break;
            case "file_start" or "file_chunk" or "file_end":
                ReceiveFile((string)m["t"]!, m);
                break;
            case "ping":
                Send(new JsonObject { ["t"] = "pong" });
                break;
        }
    }

    void ReceiveNotification(JsonObject m)
    {
        if ((string?)m["key"] is not { Length: > 0 } key) return;
        var pkg = (string?)m["pkg"] ?? "";
        if ((string?)m["icon"] is { } icon)
        {
            try { Icons[pkg] = Convert.FromBase64String(icon); } catch (FormatException) { }
        }
        var actions = (m["actions"] as JsonArray ?? new JsonArray()).OfType<JsonObject>().Select(a =>
            new NotifAction((int?)a["i"] ?? 0, (string?)a["title"] ?? "", (bool?)a["reply"] ?? false, (bool?)a["opens"] ?? false)).ToList();
        long ms = (long?)m["time"] ?? DateTimeOffset.Now.ToUnixTimeMilliseconds();
        var n = new PhoneNotification(key, pkg, (string?)m["app"] ?? pkg, (string?)m["title"] ?? "", (string?)m["text"] ?? "",
                                      DateTimeOffset.FromUnixTimeMilliseconds(ms).ToLocalTime(), (bool?)m["call"] ?? false, actions);
        bool update = Notifications.Any(x => x.Key == key);
        Notifications.RemoveAll(x => x.Key == key);
        Notifications.Insert(0, n);
        if (Notifications.Count > 200) Notifications.RemoveRange(200, Notifications.Count - 200);
        NotificationsChanged?.Invoke();
        if ((bool?)m["silent"] != true && !update && MirrorNotifications)
            _platform.ShowNotification(n.Title is { Length: > 0 } t ? t : n.App, n.Text, key);
    }

    public PhoneNotification? Notification(string key) => Notifications.FirstOrDefault(n => n.Key == key);

    public void Dismiss(string key)
    {
        Send(new JsonObject { ["t"] = "notif_dismiss", ["key"] = key });
        Notifications.RemoveAll(n => n.Key == key);
        _platform.WithdrawNotification(key);
        NotificationsChanged?.Invoke();
    }

    public void Action(string key, int i, string? text = null)
    {
        var msg = new JsonObject { ["t"] = "notif_action", ["key"] = key, ["i"] = i };
        if (text != null) msg["text"] = text;
        Send(msg);
    }

    // MARK: Phone actions

    public void Ring()
    {
        Ringing = !Ringing;
        Send(new JsonObject { ["t"] = Ringing ? "ring" : "ring_stop" });
        StatusChanged?.Invoke();
    }

    public void OpenOnPhone(string url) => Send(new JsonObject { ["t"] = "open_url", ["url"] = url });

    // MARK: Clipboard

    /// <summary>Something was copied on this computer.</summary>
    public void LocalCopy(string? text, byte[]? png)
    {
        if (text != null)
        {
            if (string.IsNullOrWhiteSpace(text) || (Clips.FirstOrDefault() is { Kind: "text" } last && last.Text == text)) return;
            Record(new Clip { Kind = "text", Text = text });
            Send(new JsonObject { ["t"] = "clip", ["text"] = text, ["explicit"] = false });
        }
        else if (png is { Length: > 0 })
        {
            if (Clips.FirstOrDefault() is { Kind: "image" } last && last.Png.AsSpan().SequenceEqual(png)) return;
            Record(new Clip { Kind = "image", Png = png });
            SendPng(png);
        }
    }

    public void SendClipboardNow(string? text, byte[]? png)
    {
        if (!string.IsNullOrEmpty(text)) Send(new JsonObject { ["t"] = "clip", ["text"] = text, ["explicit"] = true });
        else if (png is { Length: > 0 }) SendPng(png);
    }

    void SendPng(byte[] png)
    {
        var path = System.IO.Path.Combine(System.IO.Path.GetTempPath(), $"macndroid-clip-{Guid.NewGuid():N}.png");
        File.WriteAllBytes(path, png);
        SendFile(path, clip: true);
    }

    void Record(Clip c)
    {
        Clips.RemoveAll(x => x.Kind == c.Kind && x.Text == c.Text && x.Png.AsSpan().SequenceEqual(c.Png));
        Clips.Insert(0, c);
        if (Clips.Count > HistoryLimit) Clips.RemoveRange(HistoryLimit, Clips.Count - HistoryLimit);
        ClipsChanged?.Invoke();
        PushHistory();
    }

    Clip? FindClip(JsonObject m) => Clips.FirstOrDefault(c => c.Id == (string?)m["id"]);

    public void UseClip(Clip c) => _platform.SetClipboard(c.Kind == "text" ? c.Text : null, c.Kind == "image" ? c.Png : null);

    public void ClearHistory()
    {
        Clips.Clear();
        ClipsChanged?.Invoke();
        PushHistory();
    }

    void PushHistory()
    {
        var items = new JsonArray();
        foreach (var c in Clips)
        {
            var item = new JsonObject
            {
                ["id"] = c.Id, ["kind"] = c.Kind, ["from"] = c.FromPhone ? "phone" : "mac",
                ["time"] = c.Time.ToUnixTimeMilliseconds(),
            };
            if (c.Kind == "text")
            {
                item["text"] = c.Text.Length > 2000 ? c.Text[..2000] : c.Text;
                item["truncated"] = c.Text.Length > 2000;
            }
            items.Add(item);
        }
        Send(new JsonObject { ["t"] = "clip_history", ["items"] = items });
    }

    // MARK: Files

    public void SendFiles(IEnumerable<string> paths)
    {
        foreach (var p in paths.Where(File.Exists)) SendFile(p);
    }

    void SendFile(string path, bool clip = false)
    {
        var s = Active;
        if (s == null) return;
        var id = Guid.NewGuid().ToString("N");
        var size = new FileInfo(path).Length;
        var name = System.IO.Path.GetFileName(path);
        if (!clip)
        {
            Transfers.Insert(0, new Transfer { Id = id, Name = name, Size = size, ToPhone = true, Path = path });
            TransfersChanged?.Invoke();
        }
        _ = Task.Run(async () =>
        {
            var start = new JsonObject { ["t"] = "file_start", ["id"] = id, ["name"] = name, ["size"] = size, ["mime"] = Mime.Of(name) };
            if (clip) start["clip"] = true;
            bool ok = await s.SendAsync(start);
            long sent = 0;
            var last = DateTime.MinValue;
            try
            {
                await using var f = File.OpenRead(path);
                var buf = new byte[Chunk];
                int n;
                while (ok && (n = await f.ReadAsync(buf)) > 0)
                {
                    ok = await s.SendAsync(new JsonObject { ["t"] = "file_chunk", ["id"] = id, ["data"] = Convert.ToBase64String(buf, 0, n) });
                    sent += n;
                    if ((DateTime.Now - last).TotalMilliseconds > 100)
                    {
                        last = DateTime.Now;
                        long progress = sent;
                        Server.Dispatch(() => Update(id, progress));
                    }
                }
            }
            catch (IOException) { ok = false; }
            ok = ok && await s.SendAsync(new JsonObject { ["t"] = "file_end", ["id"] = id });
            if (clip) { try { File.Delete(path); } catch (IOException) { } return; }
            long total = sent;
            Server.Dispatch(() =>
            {
                Update(id, total, ok ? "done" : "failed");
                if (!Transfers.Any(t => t.ToPhone && t.State == "active"))
                    _platform.ShowNotification(ok ? $"Sent to {PhoneName}" : "Couldn't send", name, null);
            });
        });
    }

    void Update(string id, long? bytes = null, string? state = null)
    {
        var t = Transfers.FirstOrDefault(x => x.Id == id);
        if (t == null) return;
        if (bytes != null) t.Bytes = bytes.Value;
        if (state != null)
        {
            t.State = state;
            if (state == "done") t.Bytes = Math.Max(t.Bytes, t.Size);
        }
        TransfersChanged?.Invoke();
    }

    static string UniquePath(string folder, string name)
    {
        name = System.IO.Path.GetFileName(name);
        foreach (var c in System.IO.Path.GetInvalidFileNameChars()) name = name.Replace(c, '-');
        if (name.Trim().Length == 0) name = "file";
        var path = System.IO.Path.Combine(folder, name);
        var stem = System.IO.Path.GetFileNameWithoutExtension(name);
        var ext = System.IO.Path.GetExtension(name);
        for (int i = 2; File.Exists(path); i++) path = System.IO.Path.Combine(folder, $"{stem} {i}{ext}");
        return path;
    }

    void ReceiveFile(string t, JsonObject m)
    {
        if ((string?)m["id"] is not { } id) return;
        switch (t)
        {
            case "file_start":
                bool clip = (bool?)m["clip"] == true;
                var folder = clip ? System.IO.Path.GetTempPath() : _platform.DownloadsFolder;
                Directory.CreateDirectory(folder);
                var path = UniquePath(folder, clip ? $"macndroid-clip-{id}.png" : (string?)m["name"] ?? "file");
                _incoming[id] = (File.Create(path), path, clip);
                if (!clip)
                {
                    Transfers.Insert(0, new Transfer { Id = id, Name = System.IO.Path.GetFileName(path), Size = (long?)m["size"] ?? 0, Path = path });
                    TransfersChanged?.Invoke();
                }
                break;
            case "file_chunk":
                if (!_incoming.TryGetValue(id, out var entry)) return;
                try { entry.file.Write(Convert.FromBase64String((string?)m["data"] ?? "")); }
                catch (FormatException) { return; }
                Update(id, entry.file.Position);
                break;
            case "file_end":
                if (!_incoming.Remove(id, out var done)) return;
                done.file.Dispose();
                if (done.clip)
                {
                    var png = File.ReadAllBytes(done.path);
                    File.Delete(done.path);
                    _platform.SetClipboard(null, png);
                    Record(new Clip { Kind = "image", Png = png, FromPhone = true });
                    _platform.ShowNotification($"Image copied from {PhoneName}", "Ready to paste", null);
                    return;
                }
                Update(id, state: "done");
                _received.Add(done.path);
                ScheduleReceivedSummary();
                break;
        }
    }

    /// <summary>Several files in a row get one notification, after the last one.</summary>
    void ScheduleReceivedSummary()
    {
        _receivedTimer?.Cancel();
        var cts = _receivedTimer = new CancellationTokenSource();
        _ = Task.Delay(800, cts.Token).ContinueWith(task =>
        {
            if (task.IsCanceled) return;
            Server.Dispatch(() =>
            {
                if (cts.IsCancellationRequested || _received.Count == 0) return;
                var files = _received.ToList();
                _received.Clear();
                var body = files.Count == 1 ? System.IO.Path.GetFileName(files[0]) : $"{files.Count} files in Downloads";
                _platform.ShowNotification($"Received from {PhoneName}", body, null);
            });
        });
    }
}

static class Mime
{
    static readonly Dictionary<string, string> Types = new(StringComparer.OrdinalIgnoreCase)
    {
        [".jpg"] = "image/jpeg", [".jpeg"] = "image/jpeg", [".png"] = "image/png", [".gif"] = "image/gif",
        [".webp"] = "image/webp", [".heic"] = "image/heic", [".mp4"] = "video/mp4", [".mov"] = "video/quicktime",
        [".mp3"] = "audio/mpeg", [".m4a"] = "audio/mp4", [".pdf"] = "application/pdf", [".txt"] = "text/plain",
        [".zip"] = "application/zip", [".apk"] = "application/vnd.android.package-archive",
        [".doc"] = "application/msword", [".docx"] = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        [".xlsx"] = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        [".pptx"] = "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    };

    public static string Of(string name) => Types.GetValueOrDefault(Path.GetExtension(name), "application/octet-stream");
}
