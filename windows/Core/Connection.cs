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

    public bool Authenticated { get; private set; }
    public bool Closed => _closed != 0;
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
            using var handshake = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            var header = new byte[4];
            while (!Closed)
            {
                // The handshake has to finish within 10 seconds; after that phones ping every 20 seconds.
                var token = Authenticated ? new CancellationTokenSource(TimeSpan.FromSeconds(90)).Token : handshake.Token;
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
            var pcNonce = RandomNumberGenerator.GetBytes(16);
            _cipher = new SessionCipher(_server.Identity.Key, nonce, pcNonce);
            var reply = new JsonObject
            {
                ["t"] = "hello", ["v"] = 1, ["macId"] = _server.Identity.PcId,
                ["nonce"] = Convert.ToBase64String(pcNonce), ["name"] = _server.Name, ["os"] = _server.OsName,
            };
            await WriteAsync(Json.Bytes(reply));
            return;
        }
        var msg = JsonNode.Parse(_cipher.Open(payload))?.AsObject() ?? throw new IOException("Bad message");
        if (!Authenticated)
        {
            if ((string?)msg["t"] != "device") throw new IOException("Expected device info");
            Authenticated = true;
            Device = msg;
            await SendAsync(new JsonObject { ["t"] = "welcome", ["name"] = _server.Name });
            _server.Dispatch(() => _server.RaiseAuthenticated(this));
            return;
        }
        _server.Dispatch(() => _server.RaiseMessage(this, msg));
    }

    /// <summary>Thread-safe. Completes once the frame is handed to the network, so callers get flow control.</summary>
    public async Task<bool> SendAsync(JsonObject msg)
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

    public event Action<Session>? Authenticated;
    public event Action<Session, JsonObject>? Message;
    public event Action<Session, string?>? SessionClosed;

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
