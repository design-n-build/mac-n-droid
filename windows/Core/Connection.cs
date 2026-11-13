using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace MacNDroid.Core;

public static class Json
{
    public static readonly JsonSerializerOptions Options = new() { Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping };
    public static byte[] Bytes(JsonObject o) => Encoding.UTF8.GetBytes(o.ToJsonString(Options));
}

/// <summary>One encrypted session with a phone (see PROTOCOL.md). The computer is the server.</summary>
public sealed class Session
{
    const int MaxFrame = 8 * 1024 * 1024;
    readonly TcpClient _client;
    readonly NetworkStream _stream;
    readonly Server _server;
    readonly SemaphoreSlim _sendLock = new(1, 1);
    SessionCipher? _cipher;
    int _closed;

    /// <summary>Admitted: its messages are handled. <see cref="Keyed"/>: its device info decrypted, waiting to be admitted.</summary>
    public bool Authenticated { get; private set; }
    public bool Keyed { get; private set; }
    public bool Closed => _closed != 0;
    public string PhoneId { get; private set; } = "";
    /// <summary>It connected with its own device key (not the QR's invite key).</summary>
    public bool UsedDeviceKey { get; private set; }
    /// <summary>An older app: no device keys, no approval screen.</summary>
    public bool Legacy { get; private set; } = true;
    volatile int _waitSeconds = 10;
    public JsonObject Device { get; private set; } = new();

    internal Session(TcpClient client, Server server)
    {
        _client = client;
        _stream = client.GetStream();
        _server = server;
    }

    internal async Task RunAsync()
    {
        string? reason = null;
        try
        {
            var header = new byte[4];
            while (!Closed)
            {
                // The handshake has to finish within 10 seconds (longer while a new phone waits for approval);
                // after that phones ping every 20 seconds.
                var token = new CancellationTokenSource(TimeSpan.FromSeconds(Authenticated ? 90 : _waitSeconds)).Token;
                await _stream.ReadExactlyAsync(header, token);
                int len = (int)BinaryPrimitives.ReadUInt32BigEndian(header);
                if (len > MaxFrame) throw new IOException("Frame too large");
                var payload = new byte[len];
                await _stream.ReadExactlyAsync(payload, token);
                await HandleAsync(payload);
            }
        }
        catch (Exception e) when (e is IOException or OperationCanceledException or SocketException
                                       or CryptographicException or JsonException or InvalidOperationException)
        {
            reason = e is CryptographicException
                ? (Authenticated ? "Decryption failed" : "A phone with an old pairing code tried to connect. Pair it again.")
                : e is EndOfStreamException ? "Disconnected" : e.Message;
        }
        Close(reason);
    }

    async Task HandleAsync(byte[] payload)
    {
        if (_cipher == null)
        {
            var hello = JsonNode.Parse(payload)?.AsObject() ?? throw new IOException("Bad hello");
            var nonce = Convert.FromBase64String((string?)hello["nonce"] ?? "");
            if ((string?)hello["t"] != "hello" || nonce.Length != 16) throw new IOException("Bad hello");
            var id = _server.Identity;
            PhoneId = (string?)hello["phoneId"] ?? "";
            var use = (string?)hello["use"];
            Legacy = use == null;
            // Which key it holds: its own device key (must be the paired phone), or the QR's invite key.
            byte[] baseKey;
            if (use == "device")
            {
                if (id.Phone is not { Key: { } k } p || p.Id != PhoneId) throw new IOException("A phone that's no longer paired tried to connect.");
                baseKey = k;
                UsedDeviceKey = true;
            }
            else baseKey = id.Key;
            var pcNonce = RandomNumberGenerator.GetBytes(16);
            var reply = new JsonObject
            {
                ["t"] = "hello", ["v"] = 1, ["macId"] = id.PcId,
                ["nonce"] = Convert.ToBase64String(pcNonce), ["name"] = _server.Name, ["os"] = _server.OsName,
            };
            if ((string?)hello["eph"] is { Length: > 0 } ephText)
            {
                // Forward secrecy: a one-time key exchange mixed into the session key.
                var peer = Convert.FromBase64String(ephText);
                using var eph = new Ephemeral();
                var mine = eph.Public;
                _cipher = new SessionCipher(baseKey, nonce, pcNonce, peer, mine, eph.Shared(peer));
                reply["v"] = 2;
                reply["eph"] = Convert.ToBase64String(mine);
            }
            else _cipher = new SessionCipher(baseKey, nonce, pcNonce);
            await WriteAsync(Json.Bytes(reply));
            return;
        }
        var msg = JsonNode.Parse(_cipher.Open(payload))?.AsObject() ?? throw new IOException("Bad message");
        if (!Authenticated)
        {
            if (Keyed) return;  // Waiting to be admitted: nothing else counts yet.
            if ((string?)msg["t"] != "device") throw new IOException("Expected device info");
            Keyed = true;
            Device = msg;
            _server.Dispatch(() => _server.RaiseDevice(this));
            return;
        }
        _server.Dispatch(() => _server.RaiseMessage(this, msg));
    }

    /// <summary>Let it in: <c>welcome</c> (with its new device key, if any), then normal messages.</summary>
    public async void Admit(byte[]? deviceKey)
    {
        if (Closed || !Keyed || Authenticated) return;
        Authenticated = true;
        var welcome = new JsonObject { ["t"] = "welcome", ["name"] = _server.Name };
        if (deviceKey != null) welcome["deviceKey"] = Convert.ToBase64String(deviceKey);
        await SendAsync(welcome);
        _server.Dispatch(() => _server.RaiseAuthenticated(this));
    }

    /// <summary>A new phone waiting for approval: tell it, and give it time.</summary>
    public async void AwaitApproval(string by, int seconds)
    {
        if (Closed || !Keyed) return;
        _waitSeconds = seconds + 10;
        await SendSealedAsync(new JsonObject { ["t"] = "approval", ["by"] = by });
    }

    public async void Deny(string reason)
    {
        if (Keyed) await SendSealedAsync(new JsonObject { ["t"] = "denied" });
        await Task.Delay(300);
        Close(reason);
    }

    /// <summary>Thread-safe. Completes once the frame is handed to the network, so callers get flow control.</summary>
    public Task<bool> SendAsync(JsonObject msg) => Authenticated ? SendSealedAsync(msg) : Task.FromResult(false);

    async Task<bool> SendSealedAsync(JsonObject msg)
    {
        if (Closed || _cipher == null) return false;
        try
        {
            await _sendLock.WaitAsync();
            try { await WriteAsync(_cipher.Seal(Json.Bytes(msg))); }
            finally { _sendLock.Release(); }
            return true;
        }
        catch (Exception e) when (e is IOException or SocketException or ObjectDisposedException)
        {
            Close("Send failed");
            return false;
        }
    }

    async Task WriteAsync(byte[] payload)
    {
        var frame = new byte[payload.Length + 4];
        BinaryPrimitives.WriteUInt32BigEndian(frame, (uint)payload.Length);
        payload.CopyTo(frame, 4);
        await _stream.WriteAsync(frame);
    }

    public void Close(string? reason = null)
    {
        if (Interlocked.Exchange(ref _closed, 1) != 0) return;
        try { _client.Close(); } catch (SocketException) { }
        _server.Dispatch(() => _server.RaiseClosed(this, reason));
    }
}

/// <summary>TCP listener on port 47800 (or any free port). Callbacks run through <see cref="Dispatch"/>.</summary>
public sealed class Server(Identity identity, string name, string osName, Action<Action> dispatch)
{
    public const int DefaultPort = 47800;
    TcpListener? _listener;

    public Identity Identity { get; } = identity;
    public string Name { get; } = name;
    public string OsName { get; } = osName;
    public int Port { get; private set; }
    public Action<Action> Dispatch { get; } = dispatch;

    /// <summary>A phone proved it holds a key: <see cref="Session.Admit"/> it, ask for approval, or deny it.
    /// Without a handler, every such phone is admitted.</summary>
    public event Action<Session>? Device;
    public event Action<Session>? Authenticated;
    public event Action<Session, JsonObject>? Message;
    public event Action<Session, string?>? SessionClosed;

    internal void RaiseDevice(Session s) { if (Device != null) Device(s); else s.Admit(null); }
    internal void RaiseAuthenticated(Session s) => Authenticated?.Invoke(s);
    internal void RaiseMessage(Session s, JsonObject m) => Message?.Invoke(s, m);
    internal void RaiseClosed(Session s, string? r) => SessionClosed?.Invoke(s, r);

    public int Start()
    {
        int preferred = int.TryParse(Environment.GetEnvironmentVariable("MACNDROID_PORT"), out var p) ? p : DefaultPort;
        try
        {
            _listener = new TcpListener(IPAddress.Any, preferred);
            _listener.Start();
        }
        catch (SocketException)
        {
            _listener = new TcpListener(IPAddress.Any, 0);  // Port busy: any free port (the QR code carries it).
            _listener.Start();
        }
        Port = ((IPEndPoint)_listener.LocalEndpoint).Port;
        _ = AcceptLoopAsync();
        return Port;
    }

    async Task AcceptLoopAsync()
    {
        while (_listener != null)
        {
            TcpClient client;
            try { client = await _listener.AcceptTcpClientAsync(); }
            catch (Exception e) when (e is SocketException or ObjectDisposedException) { return; }
            client.NoDelay = true;
            client.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.KeepAlive, true);
            _ = Task.Run(() => new Session(client, this).RunAsync());
        }
    }

    public void Stop()
    {
        _listener?.Stop();
        _listener = null;
    }
}
