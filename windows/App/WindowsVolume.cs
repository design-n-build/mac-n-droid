using System.Runtime.InteropServices;
using MacNDroid.Core;
using Microsoft.UI.Dispatching;

namespace MacNDroid;

/// <summary>The PC's speaker volume (the default output device), through Windows Core Audio. The phone shows it with
/// "Music on your PC" and can set it. Checked twice a second for changes made elsewhere (keyboard, Quick Settings),
/// since the change callback would need a COM object of our own.</summary>
sealed class WindowsVolume : IComputerVolume
{
    int _vol = -1;
    bool _muted;
    bool _adjustable;

    public event Action? Changed;

    public WindowsVolume(DispatcherQueue dispatcher)
    {
        Read();
        var timer = dispatcher.CreateTimer();
        timer.Interval = TimeSpan.FromMilliseconds(500);
        timer.Tick += (_, _) => { if (Read()) Changed?.Invoke(); };
        timer.Start();
    }

    public int Volume
    {
        get => Math.Max(_vol, 0);
        set
        {
            var ep = Endpoint();
            if (ep == null) return;
            try
            {
                var ctx = Guid.Empty;
                ep.SetMasterVolumeLevelScalar(Math.Clamp(value, 0, 100) / 100f, ref ctx);
                if (value > 0) ep.SetMute(false, ref ctx);
            }
            catch (COMException) { }
            finally { Marshal.ReleaseComObject(ep); }
            if (Read()) Changed?.Invoke();
        }
    }

    public bool Muted => _muted;
    public bool Adjustable => _adjustable;

    /// <summary>Reads the current level; true if it changed.</summary>
    bool Read()
    {
        var ep = Endpoint();
        int vol = 0; bool muted = false, ok = ep != null;
        if (ep != null)
        {
            try
            {
                ep.GetMasterVolumeLevelScalar(out var level);
                ep.GetMute(out muted);
                vol = (int)Math.Round(level * 100);
            }
            catch (COMException) { ok = false; }
            finally { Marshal.ReleaseComObject(ep); }
        }
        if (vol == _vol && muted == _muted && ok == _adjustable) return false;
        (_vol, _muted, _adjustable) = (vol, muted, ok);
        return true;
    }

    /// <summary>The default playback device's volume control, or null without one.</summary>
    static IAudioEndpointVolume? Endpoint()
    {
        object? enumerator = null;
        try
        {
            enumerator = Activator.CreateInstance(Type.GetTypeFromCLSID(new Guid("BCDE0395-E52F-467C-8E3D-C4579291692E"))!);
            if (((IMMDeviceEnumerator)enumerator!).GetDefaultAudioEndpoint(0 /* eRender */, 1 /* eMultimedia */, out var device) != 0) return null;
            try
            {
                var iid = typeof(IAudioEndpointVolume).GUID;
                return device.Activate(ref iid, 1 /* CLSCTX_INPROC_SERVER */, IntPtr.Zero, out var o) == 0 ? (IAudioEndpointVolume)o : null;
            }
            finally { Marshal.ReleaseComObject(device); }
        }
        catch (COMException) { return null; }
        finally { if (enumerator != null) Marshal.ReleaseComObject(enumerator); }
    }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDeviceEnumerator
    {
        [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
        [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDevice
    {
        [PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object instance);
    }

    [ComImport, Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioEndpointVolume
    {
        void RegisterControlChangeNotify(IntPtr notify);
        void UnregisterControlChangeNotify(IntPtr notify);
        void GetChannelCount(out uint count);
        void SetMasterVolumeLevel(float levelDb, ref Guid context);
        void SetMasterVolumeLevelScalar(float level, ref Guid context);
        void GetMasterVolumeLevel(out float levelDb);
        void GetMasterVolumeLevelScalar(out float level);
        void SetChannelVolumeLevel(uint channel, float levelDb, ref Guid context);
        void SetChannelVolumeLevelScalar(uint channel, float level, ref Guid context);
        void GetChannelVolumeLevel(uint channel, out float levelDb);
        void GetChannelVolumeLevelScalar(uint channel, out float level);
        void SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, ref Guid context);
        void GetMute([MarshalAs(UnmanagedType.Bool)] out bool mute);
    }
}
