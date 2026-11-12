// End-to-end test of the Windows app's core, without any window, so it runs on any computer. The phone is
// tools/fake_phone.py:
//   MACNDROID_CONFIG=/tmp/x MACNDROID_PORT=47901 dotnet run --project windows/CoreTest -- 10
//   python3 tools/fake_phone.py --identity /tmp/x/identity.json --port 47901 --video clip.h264
// When the phone connects it does what a person would do in the window, acts as the browser extension, then
// checks what happened and exits with 1 on a failure.
using System.Collections.Concurrent;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using MacNDroid.Core;

// Never the real extension link: a Mac n Droid app already running on this computer owns 47809.
if (Environment.GetEnvironmentVariable("MACNDROID_BRIDGE_PORT") == null) Environment.SetEnvironmentVariable("MACNDROID_BRIDGE_PORT", "47829");

var queue = new BlockingCollection<Action>();
var platform = new ConsolePlatform();
var state = new AppState(platform, queue.Add, "windows");
var sink = new CountingSink();
state.MirrorSink = sink;
state.Server.SessionClosed += (_, reason) => Console.WriteLine($"session closed: {reason ?? "(no reason)"}");
var checks = new List<(string name, bool ok)>();
void Check(string name, bool ok) => checks.Add((name, ok));

bool started = false;
WsClient? ext = null;
state.StatusChanged += () =>
{
    if (!state.Connected || started) return;
    started = true;
    state.RefreshSms();
    state.SearchContacts("pri");
    state.StartMirror();
    var folder = Path.Combine(Path.GetTempPath(), "mnd-" + Guid.NewGuid().ToString("N"), "From computer");
    Directory.CreateDirectory(Path.Combine(folder, "sub"));
    File.WriteAllText(Path.Combine(folder, "a.txt"), new string('a', 5000));
    File.WriteAllText(Path.Combine(folder, "sub", "b.txt"), new string('b', 300));
    File.WriteAllText(Path.Combine(folder, ".DS_Store"), "x");
    var single = Path.Combine(Path.GetTempPath(), "mnd-note-" + Guid.NewGuid().ToString("N") + ".txt");
    File.WriteAllText(single, "hello phone\n");
    state.SendFiles([folder, single]);
    state.OpenOnPhone("https://example.com/from-computer");
    _ = Task.Delay(1500).ContinueWith(_ => queue.Add(Later));
};

void Later()
{
    state.OpenSmsThread("1");
    state.SendSms("", "On my way", "1");
    state.MediaCommand("toggle");
    state.Action("0|com.whatsapp|1", 0, "Yes!");
    Check("web page refused by extension link", new WsClient("https://evil.example").StatusLine.Contains("403"));
    ext = new WsClient("chrome-extension://abcdefghijklmnop");
    Check("extension accepted", ext.StatusLine.Contains("101"));
    var status = ext.Receive();
    Check("extension told the phone is connected", (bool?)status?["connected"] == true && ((string?)status?["phone"])?.Length > 0);
    ext.Send(new JsonObject { ["t"] = "media", ["tab"] = 7, ["site"] = "YouTube", ["title"] = "Browser Video", ["artist"] = "",
                              ["album"] = "", ["playing"] = true, ["dur"] = 300, ["pos"] = 12, ["canNext"] = true });
    ext.Send(new JsonObject { ["t"] = "send_url", ["url"] = "https://example.com/from-extension" });
    _ = Task.Delay(800).ContinueWith(_ => queue.Add(() =>
    {
        state.ComputerMediaCommand("pause");
        var cmd = ext.Receive();
        Check("phone's pause reaches the browser tab", (string?)cmd?["t"] == "cmd" && (int?)cmd?["tab"] == 7 && (string?)cmd?["cmd"] == "pause");
    }));
}

state.Start();
Check("extension link listening on its test port", state.Bridge.Listening);
Console.WriteLine($"port {state.Server.Port}");
var seconds = args.Length > 0 ? int.Parse(args[0]) : 10;
var until = DateTime.Now.AddSeconds(seconds);
while (DateTime.Now < until)
{
    if (queue.TryTake(out var work, 200)) work();
}

// Checked while the phone is still connected (disconnecting clears battery, notifications and media).
var dl = platform.DownloadsFolder;
Check("battery", state.Battery == 76);
Check("notifications", state.Notifications.Count == 2);
Check("call notification", state.Notifications.Any(n => n.IsCall));
Check("live activity", state.Live.ContainsKey("timer1"));
Check("phone media", state.NowPlaying?.Title == "Test Song");
Check("clipboard from phone", state.Clips.FirstOrDefault()?.Text == "Hello from the fake phone");
Check("file from phone", File.Exists(Path.Combine(dl, "from-phone-test.txt")) && new FileInfo(Path.Combine(dl, "from-phone-test.txt")).Length == 620000);
var got = Path.Combine(dl, "Holiday photos");
Check("folder from phone unpacked", File.Exists(Path.Combine(got, "beach.txt")) && File.Exists(Path.Combine(got, "day 2", "hill.txt")));
Check("folder can't escape Downloads", !File.Exists(Path.Combine(dl, "escape.txt")) && !File.Exists(Path.Combine(Path.GetDirectoryName(dl)!, "escape.txt"))
      && !Directory.EnumerateDirectories(dl, "__MACOSX", SearchOption.AllDirectories).Any());
Check("texts loaded", state.SmsThreads.Count == 2 && state.SmsMessages.GetValueOrDefault("1")?.Count >= 2);
Check("contact search", state.Contacts.FirstOrDefault()?.Name == "Priya");
var toPhone = state.Transfers.Where(t => t.ToPhone).ToList();
Check("transfers to phone done: " + string.Join(", ", toPhone.Select(t => $"{t.DisplayName}={t.State}")), toPhone.Count == 2 && toPhone.All(t => t.State == "done"));
if (Environment.GetEnvironmentVariable("MIRROR_VIDEO") != null)
    Check($"screen mirroring frames handed to the decoder ({sink.Frames}, {sink.KeyFrames} key)", sink.Frames > 10 && sink.KeyFrames > 0 && sink.Configs > 0);
foreach (var (name, ok) in checks) Console.WriteLine((ok ? "✓ " : "✗ ") + name);
return checks.All(c => c.ok) ? 0 : 1;

sealed class ConsolePlatform : IPlatform
{
    public string DownloadsFolder { get; } = Environment.GetEnvironmentVariable("MACNDROID_DOWNLOADS") ?? Path.Combine(Identity.Folder, "Downloads");
    public void SetClipboard(string? text, byte[]? png) => Console.WriteLine($"set clipboard: {text ?? $"{png?.Length} bytes PNG"}");
    public void ShowNotification(string title, string body, string? key) => Console.WriteLine($"notify: {title} — {body}");
    public void WithdrawNotification(string key) { }
    public void OpenUrl(string url) => Console.WriteLine($"open: {url}");
    public void Alert(string title, string body) => Console.WriteLine($"alert: {title} — {body}");
    public string NetworkSignature() => NetworkInfo.Signature();
}

/// <summary>Stands in for the video decoder: counts what arrives, the way the app's decoder would get it.</summary>
sealed class CountingSink : IMirrorSink
{
    public int Configs, Frames, KeyFrames;
    public void Config(JsonObject m) => Configs++;
    public void Frame(JsonObject m) { Frames++; if ((bool?)m["k"] == true) KeyFrames++; }
    public void Reset() { }
}

/// <summary>A browser extension (or a web page, with a web origin) connecting to the extension link.</summary>
sealed class WsClient
{
    readonly NetworkStream _s;
    readonly List<byte> _buf = new();
    public string StatusLine { get; }

    public WsClient(string origin)
    {
        var tcp = new TcpClient("127.0.0.1", BrowserBridge.Port) { ReceiveTimeout = 3000 };
        _s = tcp.GetStream();
        var key = Convert.ToBase64String(RandomNumberGenerator.GetBytes(16));
        _s.Write(Encoding.ASCII.GetBytes($"GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                                         $"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\nOrigin: {origin}\r\n\r\n"));
        var chunk = new byte[4096];
        while (true)
        {
            var text = Encoding.Latin1.GetString(_buf.ToArray());
            int end = text.IndexOf("\r\n\r\n", StringComparison.Ordinal);
            if (end >= 0) { StatusLine = text[..text.IndexOf("\r\n", StringComparison.Ordinal)]; _buf.RemoveRange(0, end + 4); break; }
            int n;
            try { n = _s.Read(chunk); } catch (IOException) { n = 0; }
            if (n == 0) { StatusLine = ""; break; }
            _buf.AddRange(chunk.AsSpan(0, n).ToArray());
        }
    }

    byte[] Exact(int n)
    {
        var chunk = new byte[65536];
        while (_buf.Count < n)
        {
            int r = _s.Read(chunk);
            if (r == 0) throw new IOException("closed");
            _buf.AddRange(chunk.AsSpan(0, r).ToArray());
        }
        var o = _buf.GetRange(0, n).ToArray();
        _buf.RemoveRange(0, n);
        return o;
    }

    public void Send(JsonObject m)
    {
        var data = Encoding.UTF8.GetBytes(m.ToJsonString());
        var mask = RandomNumberGenerator.GetBytes(4);
        var head = data.Length < 126 ? new byte[] { 0x81, (byte)(0x80 | data.Length) }
                                     : new byte[] { 0x81, 0x80 | 126, (byte)(data.Length >> 8), (byte)data.Length };
        for (int i = 0; i < data.Length; i++) data[i] ^= mask[i & 3];
        _s.Write(head); _s.Write(mask); _s.Write(data);
    }

    public JsonObject? Receive()
    {
        try
        {
            var h = Exact(2);
            int n = h[1] & 0x7F;
            if (n == 126) { var b = Exact(2); n = (b[0] << 8) | b[1]; }
            return JsonNode.Parse(Exact(n)) as JsonObject;
        }
        catch (IOException) { return null; }
    }
}
