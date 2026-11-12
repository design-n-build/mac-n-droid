namespace MacNDroid.Core;

/// <summary>"Did you leave your phone behind?" — the same rule as the Mac app (mac/…/LeftBehind.swift).
/// The computer can't measure distance to the phone, so it uses the network: when the phone stops answering
/// *and* this computer is now on a different network (another router, or none) than when they were last
/// connected, the computer has moved and the phone hasn't. Usually it was asleep in a bag, so the check runs
/// after waking. No network at all (Wi-Fi off, router restarting) isn't evidence of moving.</summary>
public sealed class LeftBehind(Func<bool> isConnected, Func<string> phoneName, Func<bool> enabled,
                               Func<string> networkSignature, Action<string, string> alert, Action<Action> dispatch)
{
    string? _networkWhenLinked;
    bool _connectedAtSleep, _alerted;
    CancellationTokenSource? _timer;

    public void Linked()
    {
        _timer?.Cancel();
        _alerted = false;
        Task.Run(() => { var sig = networkSignature(); dispatch(() => _networkWhenLinked = sig); });
    }

    public void Lost() => Schedule(TimeSpan.FromSeconds(45));

    public void WillSleep()
    {
        _connectedAtSleep = isConnected();
        _timer?.Cancel();
    }

    public void DidWake()
    {
        // Give Wi-Fi and the phone time to reconnect after waking.
        if (_connectedAtSleep) Schedule(TimeSpan.FromSeconds(60));
        _connectedAtSleep = false;
    }

    void Schedule(TimeSpan after)
    {
        _timer?.Cancel();
        var cts = _timer = new CancellationTokenSource();
        _ = Task.Delay(after, cts.Token).ContinueWith(t => { if (!t.IsCanceled) dispatch(Run); });
    }

    void Run()
    {
        var before = _networkWhenLinked;
        if (!enabled() || _alerted || isConnected() || before == null) return;
        Task.Run(() =>
        {
            var now = networkSignature();
            dispatch(() =>
            {
                if (now is "none" or "unknown" || now == before || isConnected() || _alerted) return;
                _alerted = true;
                var name = phoneName();
                alert($"Did you leave {name} behind?", $"This computer is on a different network now and can't reach {name}.");
            });
        });
    }
}

public static class NetworkInfo
{
    /// <summary>The default route's interface, gateway and DNS suffix: changes when the computer joins another
    /// network. (Windows adds the router's hardware address, from ARP, in the app.)</summary>
    public static string Signature()
    {
        try
        {
            foreach (var ni in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up
                    || ni.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Loopback) continue;
                var props = ni.GetIPProperties();
                var gw = props.GatewayAddresses.Select(g => g.Address)
                    .FirstOrDefault(a => a.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork && !a.Equals(System.Net.IPAddress.Any));
                if (gw != null) return $"{ni.Id}|{gw}|{props.DnsSuffix}";
            }
            return "none";
        }
        catch (System.Net.NetworkInformation.NetworkInformationException) { return "unknown"; }
    }
}
