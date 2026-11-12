using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace MacNDroid.Core;

/// <summary>Link to the Mac n Droid browser extension (PROTOCOL.md, "Browser extension link"): a small WebSocket
/// server on 127.0.0.1:47809. It accepts only browser extensions (by their Origin), never web pages, and never
/// anything from the network. Callbacks run through <c>dispatch</c> (the UI thread).</summary>
public sealed class BrowserBridge(Action<Action> dispatch)
{
    public static int Port => int.TryParse(Environment.GetEnvironmentVariable("MACNDROID_BRIDGE_PORT"), out var p) ? p : 47809;
    static readonly string[] Origins = ["chrome-extension://", "moz-extension://", "extension://", "safari-web-extension://"];
    const string Guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    sealed class Client(TcpClient tcp)
    {
        public readonly TcpClient Tcp = tcp;
        public readonly NetworkStream Stream = tcp.GetStream();
        public readonly SemaphoreSlim Lock = new(1, 1);
    }

    readonly Dictionary<int, Client> _clients = new();
    int _nextId;
    TcpListener? _listener;
    JsonObject _status = new() { ["t"] = "status", ["connected"] = false, ["phone"] = "" };

    public event Action<int, JsonObject>? Message;
    public event Action<int>? Closed;
    public bool Listening => _listener != null;

    public bool Start()
    {
        try
        {
            var l = new TcpListener(IPAddress.Loopback, Port);
            l.Start();
            _listener = l;
            _ = AcceptAsync(l);
            return true;
        }
        catch (SocketException) { return false; }
    }

    public void SetStatus(bool connected, string phone)
    {
        _status = new JsonObject { ["t"] = "status", ["connected"] = connected, ["phone"] = phone };
        List<Client> all;
        lock (_clients) all = _clients.Values.ToList();
        foreach (var c in all) _ = SendAsync(c, _status);
    }

    public void Send(int client, JsonObject msg)
    {
        Client? c;
        lock (_clients) _clients.TryGetValue(client, out c);
        if (c != null) _ = SendAsync(c, msg);
    }

    static async Task SendAsync(Client c, JsonObject msg)
    {
        var data = Json.Bytes((JsonObject)msg.DeepClone());
        var head = new List<byte> { 0x81 };
        if (data.Length < 126) head.Add((byte)data.Length);
        else if (data.Length < 65536) { head.Add(126); head.Add((byte)(data.Length >> 8)); head.Add((byte)data.Length); }
        else { head.Add(127); for (int i = 7; i >= 0; i--) head.Add((byte)((long)data.Length >> (8 * i))); }
        await c.Lock.WaitAsync();
        try
        {
            await c.Stream.WriteAsync(head.ToArray());
            await c.Stream.WriteAsync(data);
        }
        catch (Exception e) when (e is IOException or ObjectDisposedException or SocketException) { }
        finally { c.Lock.Release(); }
    }

    async Task AcceptAsync(TcpListener l)
    {
        while (true)
        {
            TcpClient tcp;
            try { tcp = await l.AcceptTcpClientAsync(); }
            catch (Exception e) when (e is SocketException or ObjectDisposedException) { return; }
            _ = ServeAsync(tcp);
        }
    }

    async Task ServeAsync(TcpClient tcp)
    {
        var client = new Client(tcp);
        int id;
        try
        {
            using (var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10)))
            {
                var head = new List<byte>();
                var one = new byte[1];
                while (!EndsWithBlankLine(head))
                {
                    if (await client.Stream.ReadAsync(one, timeout.Token) == 0 || head.Count > 16384) throw new IOException();
                    head.Add(one[0]);
                }
                var lines = Encoding.Latin1.GetString(head.ToArray()).Split("\r\n");
                var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                foreach (var line in lines.Skip(1))
                {
                    int i = line.IndexOf(':');
                    if (i > 0) headers[line[..i].Trim()] = line[(i + 1)..].Trim();
                }
                var origin = headers.GetValueOrDefault("Origin", "");
                var key = headers.GetValueOrDefault("Sec-WebSocket-Key", "");
                if (!lines[0].StartsWith("GET ") || key.Length == 0 || !Origins.Any(origin.StartsWith)
                    || !headers.GetValueOrDefault("Upgrade", "").Contains("websocket", StringComparison.OrdinalIgnoreCase))
                {
                    await client.Stream.WriteAsync(Encoding.ASCII.GetBytes("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"));
                    tcp.Close();
                    return;
                }
                var accept = Convert.ToBase64String(SHA1.HashData(Encoding.ASCII.GetBytes(key + Guid)));
                await client.Stream.WriteAsync(Encoding.ASCII.GetBytes(
                    $"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {accept}\r\n\r\n"));
            }
            lock (_clients) { id = ++_nextId; _clients[id] = client; }
        }
        catch (Exception e) when (e is IOException or OperationCanceledException or SocketException)
        {
            tcp.Close();
            return;
        }
        await SendAsync(client, _status);
        try { await ReadLoopAsync(client, id); }
        catch (Exception e) when (e is IOException or OperationCanceledException or SocketException or ObjectDisposedException) { }
        lock (_clients) _clients.Remove(id);
        tcp.Close();
        dispatch(() => Closed?.Invoke(id));
    }

    static bool EndsWithBlankLine(List<byte> b) =>
        b.Count >= 4 && b[^4] == '\r' && b[^3] == '\n' && b[^2] == '\r' && b[^1] == '\n';

    async Task ReadLoopAsync(Client c, int id)
    {
        var s = c.Stream;
        var message = new MemoryStream();
        var h = new byte[2];
        while (true)
        {
            // The extension pings every 20 seconds while connected.
            using var idle = new CancellationTokenSource(TimeSpan.FromSeconds(120));
            await s.ReadExactlyAsync(h, idle.Token);
            bool fin = (h[0] & 0x80) != 0;
            int opcode = h[0] & 0x0F;
            long n = h[1] & 0x7F;
            if (n == 126) { var b = new byte[2]; await s.ReadExactlyAsync(b, idle.Token); n = (b[0] << 8) | b[1]; }
            else if (n == 127) { var b = new byte[8]; await s.ReadExactlyAsync(b, idle.Token); n = 0; foreach (var x in b) n = (n << 8) | x; }
            if (n > 8 * 1024 * 1024 || (h[1] & 0x80) == 0) throw new IOException("Bad frame");  // browsers always mask
            var mask = new byte[4];
            await s.ReadExactlyAsync(mask, idle.Token);
            var data = new byte[n];
            await s.ReadExactlyAsync(data, idle.Token);
            for (int i = 0; i < data.Length; i++) data[i] ^= mask[i & 3];
            switch (opcode)
            {
                case 0x8: return;
                case 0x9:
                    await c.Lock.WaitAsync();
                    try { await s.WriteAsync(new byte[] { 0x8A, (byte)Math.Min(data.Length, 125) }); await s.WriteAsync(data.AsMemory(0, Math.Min(data.Length, 125))); }
                    finally { c.Lock.Release(); }
                    continue;
                case 0x0 or 0x1 or 0x2:
                    message.Write(data);
                    if (!fin) continue;
                    JsonObject? msg = null;
                    try { msg = JsonNode.Parse(message.ToArray()) as JsonObject; } catch (System.Text.Json.JsonException) { }
                    message.SetLength(0);
                    if (msg != null) dispatch(() => Message?.Invoke(id, msg));
                    continue;
            }
        }
    }
}
