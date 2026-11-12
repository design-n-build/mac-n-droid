using System.Runtime.InteropServices.WindowsRuntime;
using System.Text.Json.Nodes;
using System.Threading.Channels;
using MacNDroid.Core;
using Microsoft.UI.Input;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.Media.Core;
using Windows.Media.MediaProperties;
using Windows.Media.Playback;
using Windows.System;

namespace MacNDroid;

/// <summary>Phone screen mirroring: the phone's H.264 (mirror_cfg / mirror_frame in PROTOCOL.md) is fed to Windows'
/// own decoder through a MediaStreamSource and shown in a MediaPlayerElement. Clicks and drags become touches,
/// the right button is Back, the middle button is Home, and typing goes to the phone.</summary>
public sealed class MirrorSurface : Grid, IMirrorSink
{
    readonly AppState _state;
    readonly MediaPlayerElement _video = new()
    {
        AreTransportControlsEnabled = false, Stretch = Stretch.Uniform, IsHitTestVisible = false,
    };
    readonly InputLayer _input;
    MediaPlayer? _player;
    MediaStreamSource? _source;
    Channel<(byte[] data, bool key, TimeSpan at)>? _frames;
    byte[] _cfg = [];
    int _w, _h;
    bool _waitingForKey = true;
    readonly System.Diagnostics.Stopwatch _clock = new();

    public MirrorSurface(AppState state)
    {
        _state = state;
        Background = new SolidColorBrush(Microsoft.UI.Colors.Black);
        CornerRadius = new CornerRadius(12);
        _input = new InputLayer(this);
        Children.Add(_video);
        Children.Add(_input);
    }

    public void FocusInput() => _input.Focus(FocusState.Programmatic);
    internal void Send(JsonObject input) => _state.SendInput(input);

    /// <summary>Where the picture is inside this element (letterboxed).</summary>
    internal Windows.Foundation.Rect VideoRect()
    {
        double aw = ActualWidth, ah = ActualHeight;
        if (_w <= 0 || _h <= 0 || aw <= 0 || ah <= 0) return new(0, 0, aw, ah);
        double scale = Math.Min(aw / _w, ah / _h);
        double vw = _w * scale, vh = _h * scale;
        return new((aw - vw) / 2, (ah - vh) / 2, vw, vh);
    }

    // MARK: IMirrorSink (UI thread)

    public void Config(JsonObject m)
    {
        try { _cfg = Convert.FromBase64String((string?)m["d"] ?? ""); } catch (FormatException) { _cfg = []; }
        int w = (int?)m["w"] ?? 0, h = (int?)m["h"] ?? 0;
        if (_source != null && (w != _w || h != _h)) Teardown();  // Rotated: a new stream with the new size.
        _w = w; _h = h;
        _waitingForKey = true;
    }

    public void Frame(JsonObject m)
    {
        byte[] data;
        try { data = Convert.FromBase64String((string?)m["d"] ?? ""); } catch (FormatException) { return; }
        bool key = (bool?)m["k"] == true;
        if (_waitingForKey)
        {
            if (!key || _cfg.Length == 0 || _w <= 0) return;
            _waitingForKey = false;
        }
        if (_source == null) Build();
        if (key && !data.AsSpan().StartsWith(_cfg)) data = [.. _cfg, .. data];
        // Keep latency low: if the decoder falls behind, drop to the next key frame.
        if (!_frames!.Writer.TryWrite((data, key, _clock.Elapsed)))
        {
            _waitingForKey = true;
            _state.RequestKeyFrame();
        }
    }

    public void Reset()
    {
        Teardown();
        _waitingForKey = true;
    }

    void Build()
    {
        var props = VideoEncodingProperties.CreateH264();
        props.Width = (uint)_w;
        props.Height = (uint)_h;
        var source = new MediaStreamSource(new VideoStreamDescriptor(props))
        {
            BufferTime = TimeSpan.Zero, CanSeek = false, IsLive = true,
        };
        var frames = Channel.CreateBounded<(byte[], bool, TimeSpan)>(new BoundedChannelOptions(45) { SingleReader = true });
        source.Starting += (_, e) => e.Request.SetActualStartPosition(TimeSpan.Zero);
        source.SampleRequested += async (_, e) =>
        {
            var deferral = e.Request.GetDeferral();
            try
            {
                var (data, key, at) = await frames.Reader.ReadAsync();
                var sample = MediaStreamSample.CreateFromBuffer(data.AsBuffer(), at);
                sample.KeyFrame = key;
                e.Request.Sample = sample;
            }
            catch (ChannelClosedException) { e.Request.Sample = null; }  // Stopped: end of stream.
            finally { deferral.Complete(); }
        };
        _frames = frames;
        _source = source;
        _clock.Restart();
        _player = new MediaPlayer { RealTimePlayback = true, IsMuted = true, AutoPlay = true };
        _player.MediaFailed += (_, _) => DispatcherQueue.TryEnqueue(() => { Teardown(); _waitingForKey = true; _state.RequestKeyFrame(); });
        _player.Source = MediaSource.CreateFromMediaStreamSource(source);
        _video.SetMediaPlayer(_player);
        _player.Play();
    }

    void Teardown()
    {
        _frames?.Writer.TryComplete();
        _frames = null;
        if (_player != null)
        {
            _video.SetMediaPlayer(null);
            _player.Source = null;
            _player.Dispose();
            _player = null;
        }
        _source = null;
    }
}

/// <summary>Takes the mouse and keyboard over the picture and turns them into touches and keys for the phone.</summary>
sealed class InputLayer : ContentControl
{
    readonly MirrorSurface _surface;
    readonly List<double[]> _points = new();
    DateTime _downAt;
    Windows.Foundation.Point _last;
    bool _down;
    double[]? _scrollOrigin;
    double _scrollX, _scrollY;
    DispatcherTimer? _scrollTimer;

    public InputLayer(MirrorSurface surface)
    {
        _surface = surface;
        IsTabStop = true;
        Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent);
        HorizontalAlignment = HorizontalAlignment.Stretch;
        VerticalAlignment = VerticalAlignment.Stretch;
        HorizontalContentAlignment = HorizontalAlignment.Stretch;
        VerticalContentAlignment = VerticalAlignment.Stretch;
        UseSystemFocusVisuals = false;
        PointerPressed += OnPressed;
        PointerMoved += OnMoved;
        PointerReleased += OnReleased;
        PointerWheelChanged += OnWheel;
        KeyDown += OnKey;
        CharacterReceived += OnChar;
    }

    double[] Norm(Windows.Foundation.Point p)
    {
        var r = _surface.VideoRect();
        return [Math.Clamp((p.X - r.X) / Math.Max(r.Width, 1), 0, 1), Math.Clamp((p.Y - r.Y) / Math.Max(r.Height, 1), 0, 1)];
    }

    void OnPressed(object sender, PointerRoutedEventArgs e)
    {
        Focus(FocusState.Pointer);
        var pt = e.GetCurrentPoint(this);
        if (pt.Properties.IsRightButtonPressed) { _surface.Send(new JsonObject { ["key"] = "back" }); return; }
        if (pt.Properties.IsMiddleButtonPressed) { _surface.Send(new JsonObject { ["key"] = "home" }); return; }
        CapturePointer(e.Pointer);
        _down = true;
        _points.Clear();
        _points.Add(Norm(pt.Position));
        _downAt = DateTime.Now;
        _last = pt.Position;
    }

    void OnMoved(object sender, PointerRoutedEventArgs e)
    {
        if (!_down) return;
        var p = e.GetCurrentPoint(this).Position;
        if (Math.Sqrt(Math.Pow(p.X - _last.X, 2) + Math.Pow(p.Y - _last.Y, 2)) <= 3) return;
        _last = p;
        _points.Add(Norm(p));
    }

    void OnReleased(object sender, PointerRoutedEventArgs e)
    {
        if (!_down) return;
        _down = false;
        ReleasePointerCapture(e.Pointer);
        _points.Add(Norm(e.GetCurrentPoint(this).Position));
        double elapsed = (DateTime.Now - _downAt).TotalMilliseconds;
        bool moved = _points.Count > 2;
        // Quick clicks become crisp taps; holds become long presses; drags keep their speed (flings work).
        double dur = moved ? Math.Max(elapsed, 80) : (elapsed < 350 ? 50 : elapsed);
        var arr = new JsonArray();
        foreach (var p in Thin(_points, 40)) arr.Add(new JsonArray(p[0], p[1]));
        _surface.Send(new JsonObject { ["p"] = arr, ["dur"] = (int)Math.Min(dur, 10_000) });
        _points.Clear();
    }

    void OnWheel(object sender, PointerRoutedEventArgs e)
    {
        var pt = e.GetCurrentPoint(this);
        _scrollOrigin ??= Norm(pt.Position);
        // Content follows the fingers: wheel down moves the page up, like swiping up.
        if (pt.Properties.IsHorizontalMouseWheel) _scrollX -= pt.Properties.MouseWheelDelta / 3.0;
        else _scrollY += pt.Properties.MouseWheelDelta / 3.0;
        _scrollTimer ??= new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(60) };
        _scrollTimer.Stop();
        _scrollTimer.Tick -= FlushScroll;
        _scrollTimer.Tick += FlushScroll;
        _scrollTimer.Start();
        e.Handled = true;
    }

    void FlushScroll(object? sender, object e)
    {
        _scrollTimer?.Stop();
        if (_scrollOrigin is not { } o) return;
        var r = _surface.VideoRect();
        double dx = _scrollX / Math.Max(r.Width, 1), dy = _scrollY / Math.Max(r.Height, 1);
        _scrollX = _scrollY = 0;
        _scrollOrigin = null;
        static double C(double v) => Math.Clamp(v, -0.6, 0.6);
        double[] end = [Math.Clamp(o[0] + C(dx), 0, 1), Math.Clamp(o[1] + C(dy), 0.02, 0.98)];
        if (Math.Abs(end[0] - o[0]) + Math.Abs(end[1] - o[1]) <= 0.005) return;
        _surface.Send(new JsonObject { ["p"] = new JsonArray(new JsonArray(o[0], o[1]), new JsonArray(end[0], end[1])), ["dur"] = 120 });
    }

    async void OnKey(object sender, KeyRoutedEventArgs e)
    {
        var ctrl = InputKeyboardSource.GetKeyStateForCurrentThread(VirtualKey.Control).HasFlag(Windows.UI.Core.CoreVirtualKeyStates.Down);
        if (ctrl && e.Key == VirtualKey.V)
        {
            e.Handled = true;
            var (text, _) = await WindowsPlatform.ReadClipboardAsync();
            if (!string.IsNullOrEmpty(text)) _surface.Send(new JsonObject { ["text"] = text });
            return;
        }
        string? key = e.Key switch
        {
            VirtualKey.Back or VirtualKey.Delete => "backspace",
            VirtualKey.Enter => "enter",
            VirtualKey.Escape => "back",
            _ => null,
        };
        if (key != null) { _surface.Send(new JsonObject { ["key"] = key }); e.Handled = true; }
    }

    void OnChar(UIElement sender, CharacterReceivedRoutedEventArgs e)
    {
        var c = e.Character;
        if (char.IsControl(c)) return;
        _surface.Send(new JsonObject { ["text"] = c.ToString() });
        e.Handled = true;
    }

    static List<double[]> Thin(List<double[]> pts, int max)
    {
        if (pts.Count <= max) return pts;
        double step = (pts.Count - 1) / (double)(max - 1);
        return Enumerable.Range(0, max).Select(i => pts[(int)Math.Round(i * step)]).ToList();
    }
}
