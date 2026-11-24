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
    public string State { get; set; } = "active";  // active | paused | done | failed
    /// <summary>Why it failed, when there's something useful to say ("Not enough space on Pixel 8").</summary>
    public string Note { get; set; } = "";
    public bool Folder { get; init; }
    public string ShownName { get; set; } = "";
    public string DisplayName => ShownName.Length > 0 ? ShownName : Name;
}

/// <summary>Something playing on the phone (<c>media</c> in PROTOCOL.md).</summary>
public sealed record Player(string Id, string Pkg, string App, string Title, string Artist, string Album, double Duration,
                            bool Playing, double Position, DateTimeOffset PositionAt, double Speed, DateTimeOffset? Since,
                            bool CanPrev, bool CanNext, bool CanSeek)
{
    /// <summary>The exact page for this (phones 0.14.5+, when the app shares it), for Play on PC.</summary>
    public string? Url { get; init; }
    /// <summary>The app's id for what's playing (YouTube Music shares the video id), phones 0.14.5+.</summary>
    public string? MediaId { get; init; }

    /// <summary>Current position in ms.</summary>
    public double Now
    {
        get
        {
            if (!Playing) return Position;
            var p = Position + (DateTimeOffset.Now - PositionAt).TotalMilliseconds * (Speed == 0 ? 1 : Speed);
            return Duration > 0 ? Math.Min(p, Duration) : p;
        }
    }
}

/// <summary>Something playing on this computer, for the phone's "Music on your computer" (<c>mac_media</c>).</summary>
public sealed class ComputerTrack
{
    public required string Key { get; init; }
    public string App { get; init; } = "";
    public string Title { get; init; } = "";
    public string Artist { get; init; } = "";
    public string Album { get; init; } = "";
    public bool Playing { get; init; }
    public double Duration { get; init; }  // seconds
    public double Position { get; init; }  // seconds
    public DateTimeOffset? Since { get; init; }
    public (int client, int tab)? Tab { get; init; }
    /// <summary>A browser tab's address, when the extension reports it (0.14.5+).</summary>
    public string? Url { get; init; }
    public byte[]? Art { get; set; }
}

/// <summary>What's playing in the computer's own players (Windows: the media controls every app reports to).</summary>
public interface IComputerMedia
{
    ComputerTrack? Current();
    event Action? Changed;
    void Command(string cmd);
}

/// <summary>This computer's output volume, which the phone can show and set ("Music on your PC").</summary>
public interface IComputerVolume
{
    /// <summary>0–100.</summary>
    int Volume { get; set; }
    bool Muted { get; }
    /// <summary>False when the output can't be changed (or there is no output device).</summary>
    bool Adjustable { get; }
    event Action? Changed;
}

/// <summary>Decodes and shows the phone's screen (mirror_cfg / mirror_frame).</summary>
public interface IMirrorSink
{
    void Config(JsonObject m);
    void Frame(JsonObject m);
    void Reset();
}

public sealed class SmsThread
{
    public required string Id { get; init; }
    public string Address { get; init; } = "";
    public string Name { get; init; } = "";
    public string Snippet { get; init; } = "";
    public DateTimeOffset Date { get; init; }
    public int Unread { get; init; }
    public bool LastIsOutgoing { get; init; }
    public bool? Replyable { get; init; }
    public string DisplayName => Name.Length > 0 ? Name : Address;
    public bool CanReply => Replyable ?? CanText(Address);

    /// <summary>Banks and shops send from names like "AD-ICICIB-S" that can't receive texts.</summary>
    public static bool CanText(string address) => !address.Any(char.IsLetter) && address.Count(char.IsDigit) >= 3;
    /// <summary>Last 10 digits, to match "+91 98480 12345" with "9848012345".</summary>
    public static string NumberKey(string s) { var d = new string(s.Where(char.IsDigit).ToArray()); return d.Length > 10 ? d[^10..] : d; }
}

public sealed class SmsMessage
{
    public required string Id { get; init; }
    public string Body { get; init; } = "";
    public DateTimeOffset Date { get; init; }
    public bool Outgoing { get; init; }
    public bool Failed { get; set; }
    public bool Pending { get; set; }
}

public sealed record PhoneContact(string Name, string Number, string Label);

/// <summary>What the computer does that the core can't: clipboard, desktop notifications, opening links.</summary>
public interface IPlatform
{
    void SetClipboard(string? text, byte[]? png);
    /// <summary>A phone notification (key set) or a local message (key null).</summary>
    void ShowNotification(string title, string body, string? key);
    void WithdrawNotification(string key);
    void OpenUrl(string url);
    string DownloadsFolder { get; }
    /// <summary>"Did you leave your phone behind?": an urgent notification (and a chime when sounds are on).</summary>
    void Alert(string title, string body);
    /// <summary>Identifies the current network (router), for the left-behind alert. "none" without a network.</summary>
    string NetworkSignature();
}

/// <summary>Everything the window shows, and what happens with each message from the phone. Runs on the UI thread
/// (the server's dispatch hands every callback to it).</summary>
public sealed class AppState
{
    const int Chunk = 256 * 1024;
    const int HistoryLimit = 20;
    readonly IPlatform _platform;
    readonly Dictionary<string, (FileStream file, string path, bool clip, string? folder, string? gallery)> _incoming = new();
    /// <summary>The size announced for each file being received.</summary>
    readonly Dictionary<string, long> _incomingSize = new();
    /// <summary>Receives cut off by a dropped connection: the part already here, kept to continue it.</summary>
    readonly Dictionary<string, (string path, long size, string? folder)> _pausedIn = new();
    /// <summary>Sends cut off by a dropped connection: sent again with the same id when the phone is back.</summary>
    readonly Dictionary<string, (string path, bool cleanup, Transfer? existing)> _pausedOut = new();
    /// <summary>Answers to our own <c>file_start</c> with <c>ready</c>: offset to continue from, or -1 - free bytes if refused.</summary>
    readonly System.Collections.Concurrent.ConcurrentDictionary<string, TaskCompletionSource<long>> _fileReplies = new();
    /// <summary>Room kept free beyond the file itself, so a transfer never fills the disk.</summary>
    const long SpaceMargin = 200L * 1024 * 1024;
    /// <summary>The answer a waiting send gets when the connection drops before the phone replied.</summary>
    const long Dropped = long.MinValue;
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
    public bool LeftBehindAlerts { get; set; } = true;

    // Phone media, live activities, Messages, mirroring
    public List<Player> Players { get; } = new();
    public Dictionary<string, byte[]> PlayerArt { get; } = new();
    public Dictionary<string, JsonObject> Live { get; } = new();
    public string SmsState { get; private set; } = "unknown";  // unknown | loading | ready | off | permission
    public List<SmsThread> SmsThreads { get; private set; } = new();
    public Dictionary<string, List<SmsMessage>> SmsMessages { get; } = new();
    public string? SmsOpen { get; private set; }
    public string SmsSelectAddress { get; set; } = "";
    public List<PhoneContact> Contacts { get; private set; } = new();
    public string ContactQuery { get; private set; } = "";
    public bool ContactsAllowed { get; private set; } = true;
    public string Mirror { get; private set; } = "idle";  // idle | starting | live | denied | stopped
    public IMirrorSink? MirrorSink { get; set; }
    public bool ControlUnavailable { get; private set; }

    // Music on this computer, for the phone
    public bool PhoneWantsMedia { get; private set; }
    /// <summary>The phone's media volume (0–100), or null until the phone reports it.</summary>
    public int? PhoneVolume { get; private set; }
    /// <summary>The phone has paused sharing (it stays connected but sends nothing).</summary>
    public bool SharingPaused { get; private set; }
    public DateTimeOffset? PausedUntil { get; private set; }
    public GalleryModel Gallery { get; }
    public IComputerVolume? ComputerVolume { get; set; }
    public IComputerMedia? ComputerMedia { get; set; }
    readonly Dictionary<(int, int), ComputerTrack> _browserTracks = new();
    (string, bool, string, string)? _sentTrack;
    string _pushedArtKey = "";
    bool _byeSent;

    public BrowserBridge Bridge { get; }
    public LeftBehind LeftBehind { get; }

    public event Action? StatusChanged, NotificationsChanged, ClipsChanged, TransfersChanged, PairingChanged,
                         MediaChanged, LiveChanged, SmsChanged, ContactsChanged, MirrorChanged;

    public AppState(IPlatform platform, Action<Action> dispatch, string osName)
    {
        _platform = platform;
        Server = new Server(Identity, Name, osName, dispatch);
        Server.Device += Decide;
        Server.Authenticated += OnConnected;
        Server.Message += OnMessage;
        Server.SessionClosed += OnClosed;
        Gallery = new GalleryModel(Send, dispatch);
        Bridge = new BrowserBridge(dispatch);
        Bridge.Message += OnBridgeMessage;
        Bridge.Closed += OnBridgeClosed;
        LeftBehind = new LeftBehind(() => Connected, () => PhoneName, () => LeftBehindAlerts,
                                    () => _platform.NetworkSignature(), (t, b) => _platform.Alert(t, b), dispatch);
    }

    public void Start()
    {
        Server.Start();
        Bridge.Start();
        if (ComputerMedia != null) ComputerMedia.Changed += () => PushComputerMedia();
        if (ComputerVolume != null) ComputerVolume.Changed += PushComputerVolume;
        _ = MediaTickAsync();
    }

    async Task MediaTickAsync()
    {
        while (true)
        {
            await Task.Delay(TimeSpan.FromSeconds(15));
            Server.Dispatch(() => { if (PhoneWantsMedia) PushComputerMedia(periodic: true); });
        }
    }

    public bool Connected => Active != null;
    public string PhoneName => (string?)Phone?["name"] is { Length: > 0 } n ? n : LastPhoneName is { Length: > 0 } l ? l : "your phone";
    public List<string> Addresses => Net.LocalIPv4();
    public string QrText => Identity.QrText(Server.Port, Addresses, Name);
    public string PairingLink => Identity.PairingUri(Server.Port, Addresses, Name);

    /// <summary>The phone app's build number (Android versionCode); 0 before 0.14.5.</summary>
    public int PhoneApp => Phone?["app"] is JsonValue v && v.TryGetValue<int>(out var n) ? n : 0;
    /// <summary>0.14.5 and later: forget messages, timer buttons, Play on.</summary>
    public bool PhoneIsCurrent => PhoneApp >= 37;
    public bool PhoneHas(string cap) => Phone?["caps"] is JsonArray a && a.Any(x => (string?)x == cap);

    /// <summary>The phone was forgotten (here or on the phone): the app shows the pairing step again.</summary>
    public event Action? PhoneForgotten;

    /// <summary>Forget the paired phone: a new pairing code, everything this PC kept from it removed, and the pairing
    /// step again. <paramref name="tellPhone"/> is false when the phone itself asked to unpair.</summary>
    public void ForgetPhone(bool tellPhone = true)
    {
        if (_pending is { } p) ResolveApproval(p.Id, false);
        if (Active is { } old)
        {
            // Newer phones say "Not paired with this PC anymore"; older ones find their key no longer works.
            if (tellPhone && PhoneIsCurrent) _ = old.SendAsync(new JsonObject { ["t"] = "unpaired", ["reason"] = "forgotten" });
            _ = Task.Delay(500).ContinueWith(_ => old.Close("Forgotten"));
        }
        Identity.Reset();
        Phone = null;
        LastPhoneName = "";
        LastError = "";
        ForgetPhoneData();
        PhoneForgotten?.Invoke();
        PairingChanged?.Invoke();
        StatusChanged?.Invoke();
    }

    /// <summary>Everything kept from the paired phone: clipboard history, files, notifications, texts, photos and
    /// unfinished transfers. Used when a different phone pairs, or this one is forgotten.</summary>
    void ForgetPhoneData()
    {
        Clips.Clear();
        Transfers.RemoveAll(t => t.State != "active");
        foreach (var n in Notifications) _platform.WithdrawNotification(n.Key);
        Notifications.Clear();
        Icons.Clear();
        SmsThreads = new();
        SmsMessages.Clear();
        SmsOpen = null;
        SmsState = "unknown";
        Contacts = new();
        Gallery.ForgetPhone();
        foreach (var (path, _, _) in _pausedIn.Values) TryDelete(path);
        foreach (var (path, cleanup, _) in _pausedOut.Values) if (cleanup) TryDelete(path);
        _pausedIn.Clear();
        _pausedOut.Clear();
        ClipsChanged?.Invoke();
        TransfersChanged?.Invoke();
        NotificationsChanged?.Invoke();
        SmsChanged?.Invoke();
    }

    static void TryDelete(string path) { try { File.Delete(path); } catch (Exception e) when (e is IOException or UnauthorizedAccessException) { } }

    public void RefreshAddresses() => PairingChanged?.Invoke();

    // MARK: Pairing: single-use codes and approving new phones

    const int ApprovalSeconds = 120;
    sealed record Pending(string Id, Session Session, string Name);
    Pending? _pending;

    /// <summary>A different phone scanned the code: (request id, new phone, paired phone) to ask about. An empty id
    /// closes the question (answered elsewhere or timed out).</summary>
    public event Action<string, string, string>? ApprovalRequested;

    /// <summary>The QR code has been used: make a new one, so screenshots and copied links of it stop working.</summary>
    void ReplaceInviteKey()
    {
        Identity.ReplaceInviteKey();
        PairingChanged?.Invoke();
    }

    void Decide(Session s)
    {
        var name = (string?)s.Device["name"] is { Length: > 0 } n ? n : "Android phone";
        if (s.UsedDeviceKey)
        {
            s.Admit(null);
            if (Identity.Phone is { Confirmed: false }) ConfirmPhoneKey();
            return;
        }
        if (Identity.Phone == null || Identity.Phone.Id == s.PhoneId) Pair(s, name);
        else if (s.Legacy)
        {
            s.Deny("An older Mac n Droid app tried to pair");
            LastError = $"{name} tried to pair, but its Mac n Droid app is out of date. Update it, then scan the code again.";
            ReplaceInviteKey();
            StatusChanged?.Invoke();
        }
        else AskApproval(s, name);
    }

    void Pair(Session s, string name)
    {
        // A different phone than before: start clean.
        if (Identity.Phone is { } prev && prev.Id != s.PhoneId) ForgetPhoneData();
        if (s.Legacy)
        {
            // An older app keeps using the invite key, so it can't be replaced until that phone updates.
            Identity.Phone = new PairedPhone { Id = s.PhoneId, Name = name, Key = null, Confirmed = true };
            Identity.Save();
            s.Admit(null);
            return;
        }
        var key = System.Security.Cryptography.RandomNumberGenerator.GetBytes(32);
        // Saved before it's sent: if the phone keeps it but the reply is lost, its next connection still works.
        Identity.Phone = new PairedPhone { Id = s.PhoneId, Name = name, Key = key, Confirmed = false };
        Identity.Save();
        s.Admit(key);
    }

    void ConfirmPhoneKey()
    {
        if (Identity.Phone is not { Confirmed: false } p) return;
        p.Confirmed = true;
        Identity.Save();
        ReplaceInviteKey();
    }

    void AskApproval(Session s, string name)
    {
        if (_pending is { } old) ResolveApproval(old.Id, false);
        var paired = Identity.Phone?.Name is { Length: > 0 } pn ? pn : PhoneName;
        var id = Guid.NewGuid().ToString("N");
        _pending = new Pending(id, s, name);
        s.AwaitApproval(paired, ApprovalSeconds);
        _ = Active?.SendAsync(new JsonObject { ["t"] = "approve_request", ["id"] = id, ["name"] = name });
        _ = Task.Delay(TimeSpan.FromSeconds(ApprovalSeconds)).ContinueWith(_ => Server.Dispatch(() => ResolveApproval(id, false)));
        ApprovalRequested?.Invoke(id, name, paired);
    }

    /// <summary>From this PC, the paired phone or the time limit; only the first answer counts.</summary>
    public void ResolveApproval(string id, bool allow)
    {
        if (_pending is not { } p || p.Id != id) return;
        _pending = null;
        ApprovalRequested?.Invoke("", "", "");
        if (allow)
        {
            if (Active is { } old)
            {
                _ = old.SendAsync(new JsonObject { ["t"] = "unpaired", ["by"] = p.Name });
                _ = Task.Delay(500).ContinueWith(_ => old.Close("Replaced by a new phone"));
            }
            Pair(p.Session, p.Name);
        }
        else
        {
            p.Session.Deny("Not allowed");
            ReplaceInviteKey();
        }
    }

    // MARK: Connection

    void OnConnected(Session s)
    {
        if (Active != null && Active != s) Active.Close("Replaced by a new connection");
        Active = s;
        Phone = s.Device;
        LastPhoneName = (string?)s.Device["name"] ?? "";
        LastError = "";
        ControlUnavailable = false;
        StatusChanged?.Invoke();
        PushHistory();
        Bridge.SetStatus(true, PhoneName);
        LeftBehind.Linked();
        if (SmsState != "unknown") RefreshSms();
        Gallery.PhoneConnected();
        if (PhoneHas(Caps.Files2)) ResumeTransfers();
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
        Players.Clear();
        PlayerArt.Clear();
        Live.Clear();
        PhoneWantsMedia = false;
        _sentTrack = null;
        _sentVolume = null;
        PhoneVolume = null;
        SharingPaused = false;
        PausedUntil = null;
        Gallery.PhoneDisconnected();
        if (Mirror != "idle") { Mirror = "stopped"; MirrorSink?.Reset(); MirrorChanged?.Invoke(); }
        Bridge.SetStatus(false, "");
        if (!_byeSent) LeftBehind.Lost();
        _byeSent = false;
        foreach (var n in Notifications) _platform.WithdrawNotification(n.Key);
        Notifications.Clear();
        // A phone that can continue transfers: keep what arrived so far; sends pause by themselves (one waiting
        // for the phone's answer is told the connection dropped).
        bool resumable = PhoneHas(Caps.Files2);
        foreach (var id in _fileReplies.Keys.ToList())
            if (_fileReplies.TryRemove(id, out var waiting)) waiting.TrySetResult(Dropped);
        foreach (var (id, f) in _incoming)
        {
            f.file.Dispose();
            if (resumable && !f.clip && f.gallery == null && _incomingSize.GetValueOrDefault(id) > 0)
            {
                _pausedIn[id] = (f.path, _incomingSize[id], f.folder);
                Update(id, state: "paused");
                continue;
            }
            TryDelete(f.path);
            Update(id, state: "failed");
        }
        _incoming.Clear();
        _incomingSize.Clear();
        if (!resumable) foreach (var t in Transfers.Where(t => t.State == "active")) t.State = "failed";
        StatusChanged?.Invoke();
        NotificationsChanged?.Invoke();
        TransfersChanged?.Invoke();
        MediaChanged?.Invoke();
        LiveChanged?.Invoke();
    }

    public void Send(JsonObject msg)
    {
        var s = Active;
        if (s != null) _ = s.SendAsync(msg);
    }

    /// <summary>Before sleeping or quitting: the phone then knows the disconnect is expected (no left-behind alert).
    /// Waits up to a second for it to go out.</summary>
    public void SayBye(string reason)
    {
        var s = Active;
        if (s == null) return;
        _byeSent = true;
        // On a pool thread: waiting here on the UI thread would block the send's own continuation.
        Task.Run(() => s.SendAsync(new JsonObject { ["t"] = "bye", ["reason"] = reason })).Wait(TimeSpan.FromSeconds(1));
    }

    // MARK: Messages from the phone

    void OnMessage(Session s, JsonObject m)
    {
        if (s != Active) return;
        switch ((string?)m["t"])
        {
            case "key_saved":
                ConfirmPhoneKey();
                break;
            case "unpair":
                // Unpaired on the phone: forget it here too, and show the pairing code.
                ForgetPhone(tellPhone: false);
                break;
            case "play_on_phone":
                PlayOnPhone();
                break;
            case "play_on_computer":
                if ((Players.FirstOrDefault(x => x.Id == (string?)m["id"]) ?? NowPlaying) is { } pl) PlayOnComputer(pl);
                break;
            case "file_ready" or "file_refuse":
                if ((string?)m["id"] is { } rid && _fileReplies.TryRemove(rid, out var tcs))
                    tcs.TrySetResult((string?)m["t"] == "file_refuse" ? -1 - (long)Num(m["free"]) : (long)Num(m["offset"]));
                break;
            case "approve":
                ResolveApproval((string?)m["id"] ?? "", (bool?)m["allow"] == true);
                break;
            case "paused":
                SharingPaused = (bool?)m["on"] == true;
                double until = Num(m["until"]);
                PausedUntil = SharingPaused && until > 0 ? DateTimeOffset.FromUnixTimeMilliseconds((long)until).ToLocalTime() : null;
                StatusChanged?.Invoke();
                break;
            case "volume":
                PhoneVolume = Math.Clamp((int)Num(m["vol"], 50), 0, 100);
                MediaChanged?.Invoke();
                break;
            case "mac_volume_set":
                if (ComputerVolume is { Adjustable: true } cv) cv.Volume = Math.Clamp((int)Num(m["vol"], 50), 0, 100);
                break;
            case "media_limited":
                // Android 17 mutes music started from here on a locked phone unless Mac n Droid may act in the
                // background there (its "clipboard and controls" accessibility service).
                if (DateTimeOffset.Now - _lastMediaLimitedNote > TimeSpan.FromMinutes(3))
                {
                    _lastMediaLimitedNote = DateTimeOffset.Now;
                    _platform.ShowNotification($"{PhoneName} may play this silently",
                        "Android only lets Mac n Droid start music on a locked phone while Mac n Droid clipboard and controls is on. Turn it on in the phone's Accessibility settings, or press play on the phone.", null);
                }
                break;
            case "gallery" or "gallery_thumb" or "gallery_access":
                Gallery.Handle(m);
                break;
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
            case "media":
                ReceiveMedia(m);
                break;
            case "media_end":
                RemovePlayer((string?)m["id"] ?? "");
                break;
            case "live":
                if ((string?)m["key"] is { Length: > 0 } lk)
                {
                    Live[lk] = m;
                    if ((string?)m["icon"] is { } li && (string?)m["pkg"] is { } lp)
                        try { Icons[lp] = Convert.FromBase64String(li); } catch (FormatException) { }
                    LiveChanged?.Invoke();
                }
                break;
            case "live_end":
                if (Live.Remove((string?)m["key"] ?? "")) LiveChanged?.Invoke();
                break;
            case "mac_media_want":
                PhoneWantsMedia = (bool?)m["on"] ?? false;
                _sentTrack = null;
                _pushedArtKey = "";
                PushComputerMedia();
                break;
            case "mac_media_cmd":
                ComputerMediaCommand((string?)m["cmd"] ?? "");
                break;
            case "sms_threads":
                SmsThreads = (m["threads"] as JsonArray ?? new JsonArray()).OfType<JsonObject>()
                    .Where(x => (string?)x["id"] is { Length: > 0 }).Select(x => new SmsThread
                    {
                        Id = (string)x["id"]!, Address = (string?)x["address"] ?? "", Name = (string?)x["name"] ?? "",
                        Snippet = (string?)x["snippet"] ?? "", Date = Ms(x["date"]), Unread = (int?)x["unread"] ?? 0,
                        LastIsOutgoing = (bool?)x["out"] ?? false, Replyable = (bool?)x["reply"],
                    }).ToList();
                SmsState = "ready";
                SmsChanged?.Invoke();
                break;
            case "sms_thread":
                if ((string?)m["id"] is { Length: > 0 } tid)
                {
                    var fresh = (m["messages"] as JsonArray ?? new JsonArray()).OfType<JsonObject>().Select(x => new SmsMessage
                    {
                        Id = (string?)x["id"] ?? "", Body = (string?)x["body"] ?? "", Date = Ms(x["date"]),
                        Outgoing = (bool?)x["out"] ?? false, Failed = (bool?)x["failed"] ?? false, Pending = (bool?)x["pending"] ?? false,
                    }).ToList();
                    // Keep a just-sent text visible until the phone's copy shows up.
                    var waiting = SmsMessages.GetValueOrDefault(tid, new()).Where(x => x.Id.StartsWith("local-")
                        && !fresh.Any(f => f.Outgoing && f.Body == x.Body)).ToList();
                    SmsMessages[tid] = fresh.Concat(waiting).ToList();
                    SmsChanged?.Invoke();
                }
                break;
            case "sms_changed":
                RefreshSms();
                break;
            case "sms_sent":
                if ((bool?)m["ok"] == false)
                {
                    foreach (var list in SmsMessages.Values)
                        foreach (var x in list.Where(x => x.Id == (string?)m["id"])) { x.Failed = true; x.Pending = false; }
                    SmsChanged?.Invoke();
                }
                else RefreshSms();
                break;
            case "sms_unavailable":
                SmsState = (string?)m["reason"] == "permission" ? "permission" : "off";
                SmsChanged?.Invoke();
                break;
            case "contacts":
                if ((string?)m["q"] == ContactQuery)
                {
                    ContactsAllowed = (bool?)m["permission"] ?? true;
                    Contacts = (m["results"] as JsonArray ?? new JsonArray()).OfType<JsonObject>()
                        .Where(c => (string?)c["number"] is { Length: > 0 })
                        .Select(c => new PhoneContact((string?)c["name"] ?? "", (string)c["number"]!, (string?)c["label"] ?? "")).ToList();
                    ContactsChanged?.Invoke();
                }
                break;
            case "mirror_cfg":
                MirrorSink?.Config(m);
                if (Mirror == "starting") { Mirror = "live"; MirrorChanged?.Invoke(); }
                break;
            case "mirror_frame":
                MirrorSink?.Frame(m);
                break;
            case "mirror_stopped":
                if (Mirror != "idle")
                {
                    Mirror = (string?)m["reason"] == "denied" ? "denied" : "stopped";
                    MirrorSink?.Reset();
                    MirrorChanged?.Invoke();
                }
                break;
            case "input_unavailable":
                ControlUnavailable = true;
                MirrorChanged?.Invoke();
                break;
            case "ping":
                Send(new JsonObject { ["t"] = "pong" });
                break;
        }
    }

    static DateTimeOffset Ms(JsonNode? n)
    {
        double ms = 0;
        try { ms = n?.GetValue<double>() ?? 0; } catch (Exception e) when (e is FormatException or InvalidOperationException) { }
        return DateTimeOffset.FromUnixTimeMilliseconds((long)ms).ToLocalTime();
    }

    static double Num(JsonNode? n, double fallback = 0)
    {
        try { return n?.GetValue<double>() ?? fallback; } catch (Exception e) when (e is FormatException or InvalidOperationException) { return fallback; }
    }

    // MARK: Media playing on the phone

    void ReceiveMedia(JsonObject m)
    {
        var id = (string?)m["id"] ?? (string?)m["pkg"] ?? "";
        if ((bool?)m["active"] != true || (string?)m["title"] is not { } title)
        {
            // Old phones send one player without an id; inactive then means nothing is playing.
            if (m["id"] != null) RemovePlayer(id);
            else { Players.Clear(); PlayerArt.Clear(); MediaChanged?.Invoke(); }
            return;
        }
        if ((string?)m["art"] is { } art) try { PlayerArt[id] = Convert.FromBase64String(art); } catch (FormatException) { }
        if ((string?)m["icon"] is { } ic && (string?)m["pkg"] is { } pk) try { Icons[pk] = Convert.FromBase64String(ic); } catch (FormatException) { }
        double since = Num(m["since"]);
        var p = new Player(id, (string?)m["pkg"] ?? "", (string?)m["app"] ?? "", title, (string?)m["artist"] ?? "",
                           (string?)m["album"] ?? "", Num(m["duration"]), (bool?)m["playing"] ?? false, Num(m["position"]),
                           m["positionAt"] != null ? Ms(m["positionAt"]) : DateTimeOffset.Now, Num(m["speed"], 1),
                           since > 0 ? DateTimeOffset.FromUnixTimeMilliseconds((long)since) : null,
                           (bool?)m["canPrev"] ?? true, (bool?)m["canNext"] ?? true, (bool?)m["canSeek"] ?? false)
            { Url = (string?)m["url"], MediaId = (string?)m["mediaId"] };
        Players.RemoveAll(x => x.Id == id);
        Players.Add(p);
        Players.Sort((a, b) => a.Playing != b.Playing ? (a.Playing ? -1 : 1) : (b.Since ?? b.PositionAt).CompareTo(a.Since ?? a.PositionAt));
        MediaChanged?.Invoke();
    }

    void RemovePlayer(string id)
    {
        Players.RemoveAll(x => x.Id == id);
        PlayerArt.Remove(id);
        MediaChanged?.Invoke();
    }

    public Player? NowPlaying => Players.FirstOrDefault();

    public void MediaCommand(string cmd, string? id = null, double positionMs = 0)
    {
        if ((id ?? NowPlaying?.Id) is { } target)
            Send(new JsonObject { ["t"] = "media_cmd", ["cmd"] = cmd, ["pos"] = (long)positionMs, ["id"] = target });
    }

    public void Volume(bool up) => SendInput(new JsonObject { ["key"] = up ? "volume_up" : "volume_down" });

    /// <summary>Sets the phone's media volume (the phone reports the new level back).</summary>
    public void SetPhoneVolume(int v)
    {
        PhoneVolume = Math.Clamp(v, 0, 100);
        Send(new JsonObject { ["t"] = "volume_set", ["vol"] = PhoneVolume });
    }

    int? _volumeBeforeMute;
    DateTimeOffset _lastMediaLimitedNote = DateTimeOffset.MinValue;
    /// <summary>The speaker button next to the slider: mutes the phone, and the next click brings the sound back.</summary>
    public void TogglePhoneMute()
    {
        if (PhoneVolume is not { } v) return;
        if (v > 0) { _volumeBeforeMute = v; SetPhoneVolume(0); }
        else { SetPhoneVolume(_volumeBeforeMute ?? 50); _volumeBeforeMute = null; }
        MediaChanged?.Invoke();
    }

    (int vol, bool muted)? _sentVolume;
    /// <summary>This PC's own volume, for the phone's "Music on your PC" controls.</summary>
    void PushComputerVolume()
    {
        if (!PhoneWantsMedia || Active == null || ComputerVolume is not { } cv || CurrentComputerTrack() == null) return;
        var now = (cv.Volume, cv.Muted);
        if (_sentVolume == now) return;
        _sentVolume = now;
        Send(new JsonObject { ["t"] = "mac_volume", ["vol"] = now.Item1, ["muted"] = now.Item2, ["fixed"] = !cv.Adjustable });
    }

    // MARK: Music on this computer, for the phone

    void OnBridgeMessage(int client, JsonObject m)
    {
        switch ((string?)m["t"])
        {
            case "media" when m["tab"] is JsonValue tv && tv.TryGetValue<int>(out var tab):
                var key = (client, tab);
                bool playing = (bool?)m["playing"] ?? false;
                _browserTracks.TryGetValue(key, out var old);
                DateTimeOffset? since = playing ? (old?.Since ?? DateTimeOffset.Now) : null;
                byte[]? art = old?.Art;
                if ((string?)m["art"] is { } a) try { art = Convert.FromBase64String(a); } catch (FormatException) { }
                _browserTracks[key] = new ComputerTrack
                {
                    Key = $"tab|{client}|{tab}|{(string?)m["title"]}", App = (string?)m["site"] ?? "Browser",
                    Title = (string?)m["title"] ?? "", Artist = (string?)m["artist"] ?? "", Album = (string?)m["album"] ?? "",
                    Playing = playing, Duration = Num(m["dur"]), Position = Num(m["pos"]), Since = since, Tab = key, Art = art,
                    Url = (string?)m["url"],
                };
                PushComputerMedia();
                break;
            case "media_end" when m["tab"] is JsonValue tv2 && tv2.TryGetValue<int>(out var tab2):
                _browserTracks.Remove((client, tab2));
                PushComputerMedia();
                break;
            case "send_url":
                if ((string?)m["url"] is { } url && (url.StartsWith("http://") || url.StartsWith("https://"))) OpenOnPhone(url);
                break;
        }
    }

    void OnBridgeClosed(int client)
    {
        foreach (var k in _browserTracks.Keys.Where(k => k.Item1 == client).ToList()) _browserTracks.Remove(k);
        PushComputerMedia();
    }

    /// <summary>The newest playing source on this computer (a player, or a browser tab via the extension).</summary>
    public ComputerTrack? CurrentComputerTrack()
    {
        var sys = ComputerMedia?.Current();
        var all = _browserTracks.Values.Where(t => t.Title.Length > 0).ToList();
        if (sys is { Title.Length: > 0 }) all.Add(sys);
        var playing = all.Where(t => t.Playing).OrderByDescending(t => t.Since ?? DateTimeOffset.MinValue).FirstOrDefault();
        return playing ?? sys ?? all.LastOrDefault();
    }

    void PushComputerMedia(bool periodic = false)
    {
        if (!PhoneWantsMedia || Active == null) return;
        var t = CurrentComputerTrack();
        if (t == null)
        {
            if (_sentTrack != null) { _sentTrack = null; Send(new JsonObject { ["t"] = "mac_media_end" }); }
            return;
        }
        var sig = (t.Key, t.Playing, t.Title, t.Artist);
        if (!periodic && sig == _sentTrack) return;
        if (periodic && !t.Playing) return;
        _sentTrack = sig;
        var m = new JsonObject
        {
            ["t"] = "mac_media", ["app"] = t.App, ["title"] = t.Title, ["artist"] = t.Artist, ["album"] = t.Album,
            ["playing"] = t.Playing, ["dur"] = t.Duration, ["pos"] = t.Position,
        };
        if (ComputerVolume is { } cv)
        {
            m["vol"] = cv.Volume; m["muted"] = cv.Muted; m["fixed"] = !cv.Adjustable;
            _sentVolume = (cv.Volume, cv.Muted);
        }
        var artKey = t.Key + (t.Art != null ? "+art" : "");
        if (t.Art is { Length: > 0 } art && artKey != _pushedArtKey)
        {
            m["art"] = Convert.ToBase64String(art);
            _pushedArtKey = artKey;
        }
        Send(m);
    }

    public void ComputerMediaCommand(string cmd)
    {
        var t = CurrentComputerTrack();
        if (t?.Tab is { } tab) Bridge.Send(tab.client, new JsonObject { ["t"] = "cmd", ["tab"] = tab.tab, ["cmd"] = cmd });
        else ComputerMedia?.Command(cmd);
        _ = Task.Delay(400).ContinueWith(_ => Server.Dispatch(() => PushComputerMedia()));
    }

    // MARK: Messages (SMS)

    public void RefreshSms()
    {
        if (Active == null) return;
        if (SmsState is "unknown" or "off" or "permission") { SmsState = "loading"; SmsChanged?.Invoke(); }
        Send(new JsonObject { ["t"] = "sms_threads" });
        if (SmsOpen != null) Send(new JsonObject { ["t"] = "sms_thread", ["id"] = SmsOpen });
    }

    public void OpenSmsThread(string? id)
    {
        SmsOpen = id;
        if (id != null) Send(new JsonObject { ["t"] = "sms_thread", ["id"] = id });
    }

    public SmsThread? FindSmsThread(string? id) => SmsThreads.FirstOrDefault(t => t.Id == id);

    public void SearchContacts(string q)
    {
        ContactQuery = q;
        if (string.IsNullOrWhiteSpace(q)) { Contacts = new(); ContactsChanged?.Invoke(); return; }
        Send(new JsonObject { ["t"] = "contacts_search", ["q"] = q });
    }

    /// <summary><paramref name="threadId"/> null = a new conversation; it shows up once the phone has sent it.</summary>
    public void SendSms(string address, string text, string? threadId)
    {
        var body = text.Trim();
        if (body.Length == 0 || Active == null || (address.Length == 0 && threadId == null)) return;
        var local = "local-" + Guid.NewGuid().ToString("N");
        var key = SmsThread.NumberKey(address);
        var tid = threadId ?? SmsThreads.FirstOrDefault(t => key.Length > 0 && SmsThread.NumberKey(t.Address) == key)?.Id;
        if (tid != null)
        {
            if (!SmsMessages.TryGetValue(tid, out var list)) SmsMessages[tid] = list = new();
            list.Add(new SmsMessage { Id = local, Body = body, Date = DateTimeOffset.Now, Outgoing = true, Pending = true });
        }
        if (threadId == null) SmsSelectAddress = address;
        Send(new JsonObject { ["t"] = "sms_send", ["address"] = address, ["text"] = body, ["id"] = local, ["thread"] = threadId ?? "" });
        SmsChanged?.Invoke();
    }

    // MARK: Screen mirroring

    public void StartMirror()
    {
        if (Active == null) return;
        Mirror = "starting";
        ControlUnavailable = false;
        Send(new JsonObject { ["t"] = "mirror_start" });
        MirrorChanged?.Invoke();
    }

    public void StopMirror()
    {
        if (Mirror == "idle") return;
        Mirror = "idle";
        Send(new JsonObject { ["t"] = "mirror_stop" });
        MirrorSink?.Reset();
        MirrorChanged?.Invoke();
    }

    public void RequestKeyFrame() => Send(new JsonObject { ["t"] = "mirror_key" });

    public void SendInput(JsonObject input)
    {
        input["t"] = "input";
        Send(input);
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

    /// <summary>Opens a page on the phone. The page that's playing here continues there from the same spot, and
    /// stops here, so it doesn't play on both.</summary>
    public void OpenOnPhone(string url)
    {
        var link = url;
        if (CurrentComputerTrack() is { Playing: true, Url: { } playing } t && MediaHandoff.SamePage(playing, url))
        {
            link = MediaHandoff.WithTime(url, t.Position);
            ComputerMediaCommand("pause");
        }
        Send(new JsonObject { ["t"] = "open_url", ["url"] = link });
    }

    /// <summary>Play on phone: the phone opens what's playing here (same YouTube spot), and it stops here.</summary>
    public void PlayOnPhone()
    {
        if (Active == null || CurrentComputerTrack() is not { } t || MediaHandoff.PhoneUrl(t) is not { } url) return;
        Send(new JsonObject { ["t"] = "open_url", ["url"] = url });
        if (t.Playing) ComputerMediaCommand("pause");
    }

    /// <summary>Play on PC: open what the phone is playing here, and pause it on the phone.</summary>
    public async void PlayOnComputer(Player p)
    {
        if (p.Playing) MediaCommand("pause", p.Id);
        // Android doesn't say which YouTube video is playing: find it, so the video opens instead of a search.
        if (await MediaHandoff.ResolveComputerUrlAsync(p, p.Url, p.MediaId) is { } url) Server.Dispatch(() => _platform.OpenUrl(url));
    }

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
        foreach (var p in paths)
        {
            if (Directory.Exists(p)) SendFolder(p);
            else if (File.Exists(p)) SendFile(p);
        }
    }

    /// <summary>Zipped first (in the background), then sent like a file with <c>folder: true</c>.</summary>
    void SendFolder(string folder)
    {
        if (Active == null) return;
        var id = Guid.NewGuid().ToString("N");
        var shown = System.IO.Path.GetFileName(System.IO.Path.TrimEndingDirectorySeparator(folder));
        var t = new Transfer { Id = id, Name = shown, ToPhone = true, Path = folder, Folder = true };
        Transfers.Insert(0, t);
        TransfersChanged?.Invoke();
        _ = Task.Run(() =>
        {
            string? zip = null;
            try { zip = Folders.Zip(folder); } catch (Exception e) when (e is IOException or UnauthorizedAccessException) { }
            Server.Dispatch(() =>
            {
                if (zip == null) { Update(id, state: "failed"); return; }
                SendFile(zip, existing: t, cleanup: true);
            });
        });
    }

    void SendFile(string path, bool clip = false, Transfer? existing = null, bool cleanup = false, string? resumeId = null)
    {
        var s = Active;
        if (s == null) { if (existing != null) Update(existing.Id, state: "failed"); return; }
        var id = resumeId ?? existing?.Id ?? Guid.NewGuid().ToString("N");
        var size = new FileInfo(path).Length;
        var name = System.IO.Path.GetFileName(path);
        bool folder = existing?.Folder == true;
        if (resumeId != null) Update(id, state: "active");
        else if (existing != null)
        {
            Transfers.Remove(existing);
            Transfers.Insert(0, new Transfer { Id = id, Name = name, ShownName = existing.Name, Size = size, ToPhone = true, Path = existing.Path, Folder = true });
            TransfersChanged?.Invoke();
        }
        else if (!clip)
        {
            Transfers.Insert(0, new Transfer { Id = id, Name = name, Size = size, ToPhone = true, Path = path });
            TransfersChanged?.Invoke();
        }
        var shownName = existing?.Name ?? Transfers.FirstOrDefault(t => t.Id == id)?.DisplayName ?? name;
        // A phone that understands it says whether it has room and where to continue (PROTOCOL.md, files2).
        bool ask = !clip && PhoneHas(Caps.Files2);
        var answer = ask ? _fileReplies[id] = new TaskCompletionSource<long>(TaskCreationOptions.RunContinuationsAsynchronously) : null;
        _ = Task.Run(async () =>
        {
            var start = new JsonObject { ["t"] = "file_start", ["id"] = id, ["name"] = name, ["size"] = size,
                                         ["mime"] = folder ? "application/zip" : Mime.Of(name) };
            if (clip) start["clip"] = true;
            if (folder) start["folder"] = true;
            if (ask) start["ready"] = true;
            bool ok = await s.SendAsync(start);
            bool dropped = !ok;
            long sent = 0, refusedFree = -1;
            if (ok && answer != null)
            {
                var done = await Task.WhenAny(answer.Task, Task.Delay(TimeSpan.FromSeconds(20)));
                _fileReplies.TryRemove(id, out _);
                if (done != answer.Task) ok = false;
                else if (answer.Task.Result == Dropped) { ok = false; dropped = true; }
                else if (answer.Task.Result < 0) { refusedFree = -1 - answer.Task.Result; ok = false; }
                else sent = Math.Min(answer.Task.Result, size);
            }
            var last = DateTime.MinValue;
            try
            {
                await using var f = File.OpenRead(path);
                f.Seek(sent, SeekOrigin.Begin);
                var buf = new byte[Chunk];
                int n;
                while (ok && (n = await f.ReadAsync(buf)) > 0)
                {
                    ok = await s.SendAsync(new JsonObject { ["t"] = "file_chunk", ["id"] = id, ["data"] = Convert.ToBase64String(buf, 0, n) });
                    if (!ok) { dropped = true; break; }
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
            if (ok) { ok = await s.SendAsync(new JsonObject { ["t"] = "file_end", ["id"] = id }); dropped = !ok; }
            bool paused = !ok && dropped && ask;
            if ((clip || cleanup) && !paused) TryDelete(path);
            if (clip) return;
            long total = sent;
            Server.Dispatch(() =>
            {
                if (paused) { _pausedOut[id] = (path, cleanup, existing); Update(id, total, "paused"); return; }
                if (refusedFree >= 0 && Transfers.FirstOrDefault(t => t.Id == id) is { } tr)
                    tr.Note = $"Not enough space on {PhoneName} ({Size(refusedFree)} free)";
                Update(id, total, ok ? "done" : "failed");
                if (!Transfers.Any(t => t.ToPhone && t.State == "active"))
                    _platform.ShowNotification(ok ? $"Sent to {PhoneName}" : "Couldn't send",
                        refusedFree >= 0 ? $"There isn't enough space on {PhoneName} for {shownName}." : shownName, null);
            });
        });
    }

    /// <summary>The phone is back: send what was cut off, with the same ids, so it continues where it stopped.</summary>
    void ResumeTransfers()
    {
        var list = _pausedOut.ToList();
        _pausedOut.Clear();
        foreach (var (id, o) in list) SendFile(o.path, existing: o.existing, cleanup: o.cleanup, resumeId: id);
    }

    static string Size(long bytes) => bytes >= 1L << 30 ? $"{bytes / (double)(1L << 30):0.#} GB"
        : bytes >= 1L << 20 ? $"{bytes / (double)(1L << 20):0.#} MB" : $"{Math.Max(1, bytes / 1024)} KB";

    static long? FreeSpace(string dir)
    {
        try { return new DriveInfo(System.IO.Path.GetPathRoot(System.IO.Path.GetFullPath(dir))!).AvailableFreeSpace; }
        catch (Exception e) when (e is IOException or ArgumentException or UnauthorizedAccessException) { return null; }
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
        name = System.IO.Path.GetFileName(name.Replace('\\', '/'));
        foreach (var c in System.IO.Path.GetInvalidFileNameChars()) name = name.Replace(c, '-');
        name = name.Trim().TrimStart('.');
        if (name.Length == 0) name = "file";
        var path = System.IO.Path.Combine(folder, name);
        var stem = System.IO.Path.GetFileNameWithoutExtension(name);
        var ext = System.IO.Path.GetExtension(name);
        for (int i = 2; File.Exists(path); i++) path = System.IO.Path.Combine(folder, $"{stem} {i}{ext}");
        return path;
    }

    void ReceiveFile(string t, JsonObject m)
    {
        // The id ends up in file names: letters, digits, "-" and "_" only.
        if ((string?)m["id"] is not { } rawId) return;
        var id = new string(rawId.Where(c => char.IsAsciiLetterOrDigit(c) || c == '-' || c == '_').Take(80).ToArray());
        if (id.Length == 0) return;
        switch (t)
        {
            case "file_start":
                bool clip = (bool?)m["clip"] == true;
                bool isFolder = (bool?)m["folder"] == true && !clip;
                var name = (string?)m["name"] ?? "file";
                // A photo opened from Photos goes to its cache and opens when it arrives, not to Downloads.
                if ((string?)m["gallery"] is { } galleryId && (bool?)m["view"] == true && !clip && !isFolder)
                {
                    var cachePath = System.IO.Path.Combine(GalleryModel.CacheDir, GalleryModel.CacheFileName(galleryId, name));
                    _incoming[id] = (File.Create(cachePath), cachePath, false, null, galleryId);
                    break;
                }
                var dir = clip || isFolder ? System.IO.Path.GetTempPath() : _platform.DownloadsFolder;
                Directory.CreateDirectory(dir);
                long size = (long)Num(m["size"]);
                bool ask = (bool?)m["ready"] == true;
                // The same transfer again after the connection dropped: carry on from what's already here.
                if (!clip && _pausedIn.Remove(id, out var part) && part.size == size && File.Exists(part.path))
                {
                    var fs = new FileStream(part.path, FileMode.Append, FileAccess.Write);
                    _incoming[id] = (fs, part.path, false, part.folder, null);
                    _incomingSize[id] = size;
                    if (ask) Send(new JsonObject { ["t"] = "file_ready", ["id"] = id, ["offset"] = fs.Position });
                    Update(id, fs.Position, "active");
                    break;
                }
                // Not enough room: say so instead of filling the disk.
                if (!clip && size > 0 && FreeSpace(dir) is { } free && size + SpaceMargin > free)
                {
                    if (ask) Send(new JsonObject { ["t"] = "file_refuse", ["id"] = id, ["reason"] = "space", ["free"] = free });
                    Transfers.Insert(0, new Transfer { Id = id, Name = name, Size = size, Path = "", State = "failed",
                                                       Note = $"Not enough space on this PC ({Size(free)} free)" });
                    TransfersChanged?.Invoke();
                    _platform.ShowNotification($"Couldn't receive {name}",
                        $"There isn't enough space on this PC: it needs {Size(size)} and {Size(free)} is free.", null);
                    break;
                }
                var path = UniquePath(dir, clip ? $"macndroid-clip-{id}.png" : isFolder ? $"macndroid-folder-{id}.zip" : name);
                _incoming[id] = (File.Create(path), path, clip, isFolder ? name : null, null);
                _incomingSize[id] = size;
                if (ask) Send(new JsonObject { ["t"] = "file_ready", ["id"] = id, ["offset"] = 0 });
                if (!clip)
                {
                    var shown = isFolder ? (name.EndsWith(".zip", StringComparison.OrdinalIgnoreCase) ? name[..^4] : name) : System.IO.Path.GetFileName(path);
                    Transfers.Insert(0, new Transfer { Id = id, Name = shown, Size = (long)Num(m["size"]), Path = path, Folder = isFolder });
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
                _incomingSize.Remove(id);
                done.file.Dispose();
                if (done.gallery is { } gid) { Gallery.Arrived(gid, done.path); return; }
                if (done.clip)
                {
                    var png = File.ReadAllBytes(done.path);
                    File.Delete(done.path);
                    _platform.SetClipboard(null, png);
                    Record(new Clip { Kind = "image", Png = png, FromPhone = true });
                    _platform.ShowNotification($"Image copied from {PhoneName}", "Ready to paste", null);
                    return;
                }
                if (done.folder is { } folderName)
                {
                    var downloads = _platform.DownloadsFolder;
                    _ = Task.Run(() =>
                    {
                        Directory.CreateDirectory(downloads);
                        var outDir = Folders.Unpack(done.path, downloads, folderName);
                        Server.Dispatch(() =>
                        {
                            var tr = Transfers.FirstOrDefault(x => x.Id == id);
                            if (outDir == null || tr == null) { Update(id, state: "failed"); return; }
                            Folders.MarkOfTheWeb(outDir);
                            Transfers[Transfers.IndexOf(tr)] = new Transfer { Id = id, Name = System.IO.Path.GetFileName(outDir), Size = tr.Size, Bytes = tr.Size, Path = outDir, Folder = true, State = "done" };
                            TransfersChanged?.Invoke();
                            _received.Add(outDir);
                            ScheduleReceivedSummary();
                        });
                    });
                    return;
                }
                Update(id, state: "done");
                Folders.MarkOfTheWeb(done.path);
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
                var body = files.Count == 1 ? System.IO.Path.GetFileName(files[0]) : $"{files.Count} items in Downloads";
                _platform.ShowNotification($"Received from {PhoneName}", body, null);
            });
        });
    }
}

/// <summary>Features a device announces in <c>device</c> / <c>welcome</c> (PROTOCOL.md, v0.14.5 additions).</summary>
public static class Caps
{
    /// <summary>Resumable transfers and the free-space check.</summary>
    public const string Files2 = "files2";
    /// <summary>Understands <c>play_on_phone</c> / <c>play_on_computer</c> from the phone's own buttons.</summary>
    public const string PlayOn = "play_on";
    public static readonly string[] Mine = [Files2, PlayOn];
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
