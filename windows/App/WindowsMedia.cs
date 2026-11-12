using MacNDroid.Core;
using Windows.Graphics.Imaging;
using Windows.Media.Control;
using Windows.Storage.Streams;

namespace MacNDroid;

/// <summary>What's playing on this PC, for the phone's "Music on your computer": read from Windows' media controls
/// (the ones in the volume flyout), which Spotify, browsers, Media Player and most other players report to.
/// The most recently started player that's playing wins. Events arrive on any thread and are handed to the UI
/// thread through <c>dispatch</c>.</summary>
public sealed class WindowsMedia(Action<Action> dispatch) : IComputerMedia
{
    sealed class Entry
    {
        public required GlobalSystemMediaTransportControlsSession Session;
        public ComputerTrack? Track;
        public DateTimeOffset? Since;
        public DateTimeOffset Touched = DateTimeOffset.Now;
        public string ArtKey = "";
        public byte[]? Art;
    }

    GlobalSystemMediaTransportControlsSessionManager? _manager;
    readonly Dictionary<string, Entry> _entries = new();
    public event Action? Changed;

    public async void Start()
    {
        try { _manager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync(); }
        catch (Exception) { return; }  // Older Windows or the service is off: music on this PC isn't shown.
        _manager.SessionsChanged += (_, _) => dispatch(Resync);
        Resync();
    }

    void Resync()
    {
        if (_manager == null) return;
        IReadOnlyList<GlobalSystemMediaTransportControlsSession> sessions;
        try { sessions = _manager.GetSessions(); } catch (Exception) { return; }
        var ids = sessions.Select(s => s.SourceAppUserModelId).ToHashSet();
        foreach (var gone in _entries.Keys.Where(k => !ids.Contains(k)).ToList()) _entries.Remove(gone);
        foreach (var s in sessions)
        {
            if (_entries.ContainsKey(s.SourceAppUserModelId)) continue;
            var e = new Entry { Session = s };
            _entries[s.SourceAppUserModelId] = e;
            s.MediaPropertiesChanged += (_, _) => dispatch(() => _ = Read(e));
            s.PlaybackInfoChanged += (_, _) => dispatch(() => _ = Read(e));
            s.TimelinePropertiesChanged += (_, _) => dispatch(() => _ = Read(e));
            _ = Read(e);
        }
        Changed?.Invoke();
    }

    static string AppName(string aumid)
    {
        var id = aumid.ToLowerInvariant();
        if (id.Contains("spotify")) return "Spotify";
        if (id.Contains("chrome")) return "Chrome";
        if (id.Contains("msedge")) return "Edge";
        if (id.Contains("firefox") || id == "308046b0af4a39cb") return "Firefox";
        if (id.Contains("zunemusic") || id.Contains("zunevideo")) return "Media Player";
        if (id.Contains("vlc")) return "VLC";
        var name = aumid.Split('!').Last().Split('\\').Last();
        if (name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) name = name[..^4];
        return name.Length > 0 ? char.ToUpper(name[0]) + name[1..] : "Music";
    }

    async Task Read(Entry e)
    {
        try
        {
            var s = e.Session;
            var props = await s.TryGetMediaPropertiesAsync();
            var playback = s.GetPlaybackInfo();
            var timeline = s.GetTimelineProperties();
            bool playing = playback.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;
            if (playing && e.Since == null) e.Since = DateTimeOffset.Now;
            if (!playing) e.Since = null;
            e.Touched = DateTimeOffset.Now;
            var title = props?.Title ?? "";
            var key = $"{s.SourceAppUserModelId}|{title}|{props?.Artist}";
            double pos = timeline.Position.TotalSeconds;
            if (playing && timeline.LastUpdatedTime > DateTimeOffset.MinValue)
                pos += Math.Max(0, (DateTimeOffset.Now - timeline.LastUpdatedTime).TotalSeconds);
            if (props?.Thumbnail != null && e.ArtKey != key)
            {
                e.ArtKey = key;
                e.Art = await Jpeg(props.Thumbnail);
            }
            e.Track = title.Length == 0 ? null : new ComputerTrack
            {
                Key = key, App = AppName(s.SourceAppUserModelId), Title = title, Artist = props?.Artist ?? "",
                Album = props?.AlbumTitle ?? "", Playing = playing,
                Duration = Math.Max(0, (timeline.EndTime - timeline.StartTime).TotalSeconds), Position = Math.Max(0, pos),
                Since = e.Since, Art = e.ArtKey == key ? e.Art : null,
            };
            Changed?.Invoke();
        }
        catch (Exception) { /* The player went away while being read. */ }
    }

    /// <summary>Artwork as a JPEG at most 512 pixels on its longer side.</summary>
    static async Task<byte[]?> Jpeg(IRandomAccessStreamReference reference)
    {
        try
        {
            using var stream = await reference.OpenReadAsync();
            var decoder = await BitmapDecoder.CreateAsync(stream);
            double scale = Math.Min(1.0, 512.0 / Math.Max(decoder.PixelWidth, decoder.PixelHeight));
            var transform = new BitmapTransform
            {
                ScaledWidth = (uint)Math.Max(1, decoder.PixelWidth * scale), ScaledHeight = (uint)Math.Max(1, decoder.PixelHeight * scale),
                InterpolationMode = BitmapInterpolationMode.Fant,
            };
            var bitmap = await decoder.GetSoftwareBitmapAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Ignore, transform,
                                                               ExifOrientationMode.RespectExifOrientation, ColorManagementMode.DoNotColorManage);
            using var output = new InMemoryRandomAccessStream();
            var encoder = await BitmapEncoder.CreateAsync(BitmapEncoder.JpegEncoderId, output);
            encoder.SetSoftwareBitmap(bitmap);
            await encoder.FlushAsync();
            var bytes = new byte[output.Size];
            using var reader = new DataReader(output.GetInputStreamAt(0));
            await reader.LoadAsync((uint)output.Size);
            reader.ReadBytes(bytes);
            return bytes;
        }
        catch (Exception) { return null; }
    }

    Entry? Best()
    {
        var withTrack = _entries.Values.Where(e => e.Track != null).ToList();
        return withTrack.Where(e => e.Track!.Playing).OrderByDescending(e => e.Since ?? DateTimeOffset.MinValue).FirstOrDefault()
               ?? withTrack.OrderByDescending(e => e.Touched).FirstOrDefault();
    }

    public ComputerTrack? Current() => Best()?.Track;

    public async void Command(string cmd)
    {
        if (Best()?.Session is not { } s) return;
        try
        {
            _ = cmd switch
            {
                "play" => await s.TryPlayAsync(),
                "pause" => await s.TryPauseAsync(),
                "toggle" => await s.TryTogglePlayPauseAsync(),
                "next" => await s.TrySkipNextAsync(),
                "prev" => await s.TrySkipPreviousAsync(),
                _ => false,
            };
        }
        catch (Exception) { }
    }
}
