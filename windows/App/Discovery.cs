using System.Runtime.InteropServices;

namespace MacNDroid;

/// <summary>Advertises _macndroid._tcp with Windows' own mDNS (DnsServiceRegister, Windows 10 1809+), so the phone
/// can find this PC again when its address changes. Without it the phone still uses the addresses in the QR code.</summary>
public sealed class Discovery : IDisposable
{
    [StructLayout(LayoutKind.Sequential)]
    struct RegisterRequest
    {
        public uint Version;
        public uint InterfaceIndex;
        public IntPtr ServiceInstance;
        public IntPtr Callback;
        public IntPtr Context;
        public IntPtr Credentials;
        public int UnicastEnabled;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct Cancel { public IntPtr Reserved; }

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    delegate void Completed(uint status, IntPtr context, IntPtr instance);

    [DllImport("dnsapi.dll", CharSet = CharSet.Unicode)]
    static extern IntPtr DnsServiceConstructInstance(string serviceName, string hostName, IntPtr ip4, IntPtr ip6,
        ushort port, ushort priority, ushort weight, uint propertiesCount, string[] keys, string[] values);

    [DllImport("dnsapi.dll")]
    static extern uint DnsServiceRegister(ref RegisterRequest request, ref Cancel cancel);

    [DllImport("dnsapi.dll")]
    static extern uint DnsServiceDeRegister(ref RegisterRequest request, ref Cancel cancel);

    [DllImport("dnsapi.dll")]
    static extern void DnsServiceFreeInstance(IntPtr instance);

    const uint RequestPending = 9506;
    static readonly Completed Callback = (_, _, instance) => { if (instance != IntPtr.Zero) DnsServiceFreeInstance(instance); };
    RegisterRequest _request;
    IntPtr _instance;
    bool _registered;

    public void Start(string name, int port, string pcId)
    {
        try
        {
            var host = Environment.MachineName + ".local";
            _instance = DnsServiceConstructInstance($"{name}._macndroid._tcp.local", host, IntPtr.Zero, IntPtr.Zero,
                (ushort)port, 0, 0, 1, ["id"], [pcId]);
            if (_instance == IntPtr.Zero) return;
            _request = new RegisterRequest
            {
                Version = 1, ServiceInstance = _instance,
                Callback = Marshal.GetFunctionPointerForDelegate(Callback),
            };
            var cancel = new Cancel();
            _registered = DnsServiceRegister(ref _request, ref cancel) == RequestPending;
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException) { }
    }

    public void Dispose()
    {
        if (!_registered) return;
        var cancel = new Cancel();
        DnsServiceDeRegister(ref _request, ref cancel);
        _registered = false;
    }
}
