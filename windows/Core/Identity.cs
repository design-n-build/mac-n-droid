using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace MacNDroid.Core;

/// <summary>This computer's id and the pairing secret shared with phones (same format as the Mac app).</summary>
public sealed class Identity
{
    public const string PlayLink = "https://play.google.com/store/apps/details?id=app.macndroid#";

    [JsonPropertyName("macId")] public string PcId { get; set; } = "";
    /// <summary>The invite key in the QR code. Replaced once a phone has paired with it, so the code works once.</summary>
    [JsonPropertyName("key")] public byte[] Key { get; set; } = [];
    /// <summary>The paired phone, if any.</summary>
    [JsonPropertyName("phone")] public PairedPhone? Phone { get; set; }

    public static string Folder
    {
        get
        {
            var dir = Environment.GetEnvironmentVariable("MACNDROID_CONFIG")
                ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Mac n Droid");
            Directory.CreateDirectory(dir);
            return dir;
        }
    }

    static string FilePath => Path.Combine(Folder, "identity.json");

    public static Identity Load()
    {
        try
        {
            var id = JsonSerializer.Deserialize<Identity>(File.ReadAllText(FilePath));
            if (id is { Key.Length: 32 } && id.PcId != "") return id;
        }
        catch (Exception e) when (e is IOException or JsonException or UnauthorizedAccessException) { }
        var fresh = new Identity { PcId = Guid.NewGuid().ToString(), Key = RandomNumberGenerator.GetBytes(32) };
        fresh.Save();
        return fresh;
    }

    public void Reset()
    {
        Key = RandomNumberGenerator.GetBytes(32);
        Phone = null;
        Save();
    }

    public void ReplaceInviteKey()
    {
        Key = RandomNumberGenerator.GetBytes(32);
        Save();
    }

    public void Save()
    {
        var tmp = FilePath + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(this, Json.Options));
        File.Move(tmp, FilePath, overwrite: true);
    }

    static string B64Url(byte[] b) => Convert.ToBase64String(b).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    public string PairingUri(int port, IEnumerable<string> hosts, string name)
    {
        string E(string s) => Uri.EscapeDataString(s);
        return $"macndroid://pair?v=1&id={E(PcId)}&k={E(B64Url(Key))}&p={port}&h={E(string.Join(",", hosts))}&n={E(name)}&o=windows";
    }

    /// <summary>A Google Play link, so a phone without the app opens its store page. The pairing details follow
    /// the "#" (a fragment, never included in web requests).</summary>
    public string QrText(int port, IEnumerable<string> hosts, string name) => PlayLink + PairingUri(port, hosts, name);
}

/// <summary>The phone this computer is paired with. <see cref="Key"/> is its own key (null: an older app that still
/// uses the invite key); <see cref="Confirmed"/>: it said it saved that key.</summary>
public sealed class PairedPhone
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("name")] public string Name { get; set; } = "";
    [JsonPropertyName("key")] public byte[]? Key { get; set; }
    [JsonPropertyName("confirmed")] public bool Confirmed { get; set; }
}

public static class Net
{
    public static string ComputerName => Environment.MachineName;

    /// <summary>IPv4 addresses on the local network: Wi-Fi and Ethernet first, no virtual adapters.</summary>
    public static List<string> LocalIPv4()
    {
        var result = new List<(int rank, string ip)>();
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            if (ni.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;
            var desc = (ni.Description + " " + ni.Name).ToLowerInvariant();
            if (desc.Contains("virtual") || desc.Contains("vethernet") || desc.Contains("hyper-v") || desc.Contains("vmware")
                || desc.Contains("virtualbox") || desc.Contains("wsl") || desc.Contains("bridge")) continue;
            int rank = ni.NetworkInterfaceType == NetworkInterfaceType.Wireless80211 ? 0
                : ni.NetworkInterfaceType == NetworkInterfaceType.Ethernet ? 1 : 2;
            foreach (var a in ni.GetIPProperties().UnicastAddresses)
            {
                if (a.Address.AddressFamily != AddressFamily.InterNetwork || IPAddress.IsLoopback(a.Address)) continue;
                var s = a.Address.ToString();
                if (s.StartsWith("169.254.")) continue;
                result.Add((rank, s));
            }
        }
        return result.OrderBy(r => r.rank).Select(r => r.ip).Distinct().ToList();
    }
}
