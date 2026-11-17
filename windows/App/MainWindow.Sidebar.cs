using MacNDroid.Core;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;

namespace MacNDroid;

/// <summary>What's live on the phone, always in view at the bottom of the menu (as on the Mac): music with its
/// controls and the phone's volume, then timers, stopwatches, calls, rides and deliveries, then the connection.
/// Built once per player or activity and updated in place, so nothing flickers while music plays.</summary>
public sealed partial class MainWindow
{
    readonly StackPanel _sidebar = new() { Spacing = 8, Margin = new Thickness(4, 8, 4, 4) };
    readonly StackPanel _sidebarPlayers = new() { Spacing = 8 };
    readonly StackPanel _sidebarLive = new() { Spacing = 6 };
    readonly Ellipse _connDot = new() { Width = 8, Height = 8, VerticalAlignment = VerticalAlignment.Center };
    readonly TextBlock _connText = new() { VerticalAlignment = VerticalAlignment.Center };
    readonly Dictionary<string, PlayerCard> _playerCards = new();
    readonly Dictionary<string, LiveRow> _liveRows = new();
    bool _settingVolume;
    int? _pendingVolume;
    DateTimeOffset _lastVolumeSent = DateTimeOffset.MinValue;

    sealed class PlayerCard
    {
        public required Border Root;
        public required Image Art;
        public required FontIcon ArtPlaceholder;
        public required TextBlock Title, Artist;
        public required ProgressBar Bar;
        public required Button Prev, Toggle, Next;
        public required FontIcon ToggleIcon;
        public required Grid VolumeRow;
        public required Slider Volume;
        public required FontIcon VolumeIcon;
        public byte[]? ShownArt;
    }

    sealed class LiveRow
    {
        public required Border Root;
        public required TextBlock Title, Text, Glance;
        public required ContentControl Icon;
        public JsonLive Data = new("", "", "", "", "", "", 0, null, 0, 0);
    }

    sealed record JsonLive(string Key, string Pkg, string App, string Kind, string Title, string Text, double When, double? Paused, double Progress, double Max)
    {
        public string Short { get; init; } = "";
        public int Priority => Kind switch { "call" => 50, "timer" => 40, "stopwatch" => 30, "status" => 20, _ => 10 };
    }

    void BuildSidebar()
    {
        _sidebar.Children.Add(_sidebarPlayers);
        _sidebar.Children.Add(_sidebarLive);
        var conn = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Margin = new Thickness(12, 4, 0, 0) };
        _connText.Foreground = Brush("TextFillColorSecondaryBrush");
        _connText.Style = Style("CaptionTextBlockStyle");
        conn.Children.Add(_connDot);
        conn.Children.Add(_connText);
        _sidebar.Children.Add(conn);
        _nav.PaneFooter = _sidebar;
        _nav.PaneClosing += (_, _) => _sidebar.Visibility = Visibility.Collapsed;
        _nav.PaneOpening += (_, _) => _sidebar.Visibility = Visibility.Visible;

        // Progress bars, timers and stopwatches move every second without rebuilding anything.
        var tick = DispatcherQueue.CreateTimer();
        tick.Interval = TimeSpan.FromSeconds(1);
        tick.Tick += (_, _) => TickSidebar();
        tick.Start();
        UpdateSidebar();
    }

    void UpdateSidebar()
    {
        var st = _state;
        // Connection, as in the Mac's sidebar footer.
        (Windows.UI.Color color, string label) = st.Connected && st.SharingPaused ? (Microsoft.UI.Colors.Goldenrod, "Sharing paused")
            : st.Connected ? (Microsoft.UI.Colors.SeaGreen, $"Connected to {st.PhoneName}")
            : string.IsNullOrEmpty(st.LastPhoneName) ? (Microsoft.UI.Colors.Gray, "Not paired")
            : (Microsoft.UI.Colors.DarkOrange, $"Waiting for {st.PhoneName}");
        _connDot.Fill = new SolidColorBrush(color);
        _connText.Text = label;

        var players = st.Connected ? st.Players.Take(2).ToList() : new();
        foreach (var gone in _playerCards.Keys.Except(players.Select(p => p.Id)).ToList()) _playerCards.Remove(gone);
        var order = new List<UIElement>();
        foreach (var p in players)
        {
            if (!_playerCards.TryGetValue(p.Id, out var card)) _playerCards[p.Id] = card = NewPlayerCard(p.Id);
            FillPlayerCard(card, p, showVolume: p == players[0]);
            order.Add(card.Root);
        }
        SetChildren(_sidebarPlayers, order);

        var live = st.Connected ? st.Live.Values.Select(ToLive).Where(l => l.Key.Length > 0).OrderByDescending(l => l.Priority).Take(4).ToList() : new();
        foreach (var gone in _liveRows.Keys.Except(live.Select(l => l.Key)).ToList()) _liveRows.Remove(gone);
        var liveOrder = new List<UIElement>();
        foreach (var l in live)
        {
            if (!_liveRows.TryGetValue(l.Key, out var row)) _liveRows[l.Key] = row = NewLiveRow();
            row.Data = l;
            row.Title.Text = l.Title.Length > 0 ? l.Title : l.App;
            row.Text.Text = l.Text;
            row.Text.Visibility = l.Text.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
            if (row.Icon.Tag as string != l.Pkg)
            {
                row.Icon.Tag = l.Pkg;
                row.Icon.Content = _state.Icons.GetValueOrDefault(l.Pkg) is { } icon ? AppIcon(icon) : Glyph(LiveGlyph(l.Kind));
            }
            liveOrder.Add(row.Root);
        }
        SetChildren(_sidebarLive, liveOrder);
        TickSidebar();
    }

    /// <summary>Replaces a panel's children only when the list actually changed (no flicker otherwise).</summary>
    static void SetChildren(Panel panel, List<UIElement> items)
    {
        if (panel.Children.Count == items.Count && panel.Children.Zip(items).All(x => x.First == x.Second)) return;
        panel.Children.Clear();
        foreach (var e in items) panel.Children.Add(e);
    }

    void TickSidebar()
    {
        foreach (var p in _state.Players)
            if (_playerCards.TryGetValue(p.Id, out var card) && p.Duration > 0)
                card.Bar.Value = 100 * p.Now / p.Duration;
        foreach (var row in _liveRows.Values) row.Glance.Text = Glance(row.Data);
    }

    static JsonLive ToLive(System.Text.Json.Nodes.JsonObject a)
    {
        static double N(System.Text.Json.Nodes.JsonNode? n, double f = 0) { try { return n?.GetValue<double>() ?? f; } catch (Exception) { return f; } }
        double paused = N(a["paused"], -1);
        return new JsonLive((string?)a["key"] ?? "", (string?)a["pkg"] ?? "", (string?)a["app"] ?? "", (string?)a["kind"] ?? "status",
            (string?)a["title"] ?? "", (string?)a["text"] ?? "", N(a["when"]), paused >= 0 ? paused : null, N(a["progress"]), N(a["max"]))
            { Short = (string?)a["short"] ?? "" };
    }

    /// <summary>The few characters at the right of a live row: "4:59", "12:03", "45%" (as on the Mac).</summary>
    static string Glance(JsonLive l)
    {
        if (l.Kind is "timer" or "stopwatch" or "call" && (l.Paused != null || l.When > 0))
        {
            double nowMs = DateTimeOffset.Now.ToUnixTimeMilliseconds();
            double secs = l.Paused is { } p ? p / 1000 : Math.Abs(nowMs - l.When) / 1000;
            var t = TimeSpan.FromSeconds((int)secs);
            var text = t.TotalHours >= 1 ? t.ToString(@"h\:mm\:ss") : t.ToString(@"m\:ss");
            // A finished timer counts its overtime with a minus sign, as the phone's Clock does.
            return l.Kind == "timer" && l.Paused == null && nowMs > l.When ? "−" + text : text;
        }
        if (l.Kind == "progress" && l.Max > 0) return $"{(int)(100 * l.Progress / l.Max)}%";
        return l.Short.Length > 0 ? l.Short : "";
    }

    static string LiveGlyph(string kind) => kind switch
    {
        "call" => "", "timer" => "", "stopwatch" => "", "progress" => "", _ => "",
    };

    PlayerCard NewPlayerCard(string id)
    {
        var art = new Image { Stretch = Stretch.UniformToFill };
        var placeholder = new FontIcon { Glyph = "", FontSize = 16, Foreground = Brush("TextFillColorTertiaryBrush") };
        var artBox = new Border
        {
            Width = 36, Height = 36, CornerRadius = new CornerRadius(6), Background = Brush("ControlFillColorSecondaryBrush"),
            Child = new Grid { Children = { placeholder, art } },
        };
        var title = new TextBlock { FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis };
        var artist = new TextBlock { Style = Style("CaptionTextBlockStyle"), Foreground = Brush("TextFillColorSecondaryBrush"), TextTrimming = TextTrimming.CharacterEllipsis };
        var names = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Children = { title, artist } };
        var head = new Grid { ColumnSpacing = 10 };
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        head.ColumnDefinitions.Add(new ColumnDefinition());
        head.Children.Add(artBox);
        Grid.SetColumn(names, 1);
        head.Children.Add(names);

        var bar = new ProgressBar { Minimum = 0, Maximum = 100, Margin = new Thickness(0, 10, 0, 2) };
        var toggleIcon = new FontIcon { Glyph = "", FontSize = 18 };
        Button Control(UIElement content, string tip, string cmd)
        {
            var b = new Button { Content = content, Style = Style("SubtleButtonStyle"), HorizontalAlignment = HorizontalAlignment.Stretch };
            ToolTipService.SetToolTip(b, tip);
            b.Click += (_, _) => _state.MediaCommand(cmd, id);
            return b;
        }
        var prev = Control(new FontIcon { Glyph = "", FontSize = 14 }, "Previous", "prev");
        var toggle = Control(toggleIcon, "Play or pause", "toggle");
        var next = Control(new FontIcon { Glyph = "", FontSize = 14 }, "Next", "next");
        var controls = new Grid();
        for (int i = 0; i < 3; i++) controls.ColumnDefinitions.Add(new ColumnDefinition());
        Grid.SetColumn(toggle, 1);
        Grid.SetColumn(next, 2);
        controls.Children.Add(prev);
        controls.Children.Add(toggle);
        controls.Children.Add(next);

        // The phone's media volume: follows the phone's own buttons, and sets it while you drag.
        var volIcon = new FontIcon { Glyph = "", FontSize = 13 };
        var mute = new Button { Content = volIcon, Style = Style("SubtleButtonStyle"), Padding = new Thickness(6) };
        ToolTipService.SetToolTip(mute, "Mute or unmute the phone");
        mute.Click += (_, _) => _state.TogglePhoneMute();
        var slider = new Slider { Minimum = 0, Maximum = 100, StepFrequency = 1, VerticalAlignment = VerticalAlignment.Center, IsThumbToolTipEnabled = true };
        slider.ValueChanged += (_, e) =>
        {
            if (_settingVolume) return;
            volIcon.Glyph = VolumeGlyph((int)e.NewValue);
            SendVolumeSoon((int)e.NewValue);
        };
        var volRow = new Grid { ColumnSpacing = 4, Margin = new Thickness(0, 2, 0, 0) };
        volRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        volRow.ColumnDefinitions.Add(new ColumnDefinition());
        volRow.Children.Add(mute);
        Grid.SetColumn(slider, 1);
        volRow.Children.Add(slider);

        var root = new Border
        {
            Padding = new Thickness(10), CornerRadius = new CornerRadius(8),
            Background = Brush("CardBackgroundFillColorDefaultBrush"), BorderBrush = Brush("CardStrokeColorDefaultBrush"), BorderThickness = new Thickness(1),
            Child = new StackPanel { Children = { head, bar, controls, volRow } },
        };
        return new PlayerCard
        {
            Root = root, Art = art, ArtPlaceholder = placeholder, Title = title, Artist = artist, Bar = bar,
            Prev = prev, Toggle = toggle, Next = next, ToggleIcon = toggleIcon, VolumeRow = volRow, Volume = slider, VolumeIcon = volIcon,
        };
    }

    void FillPlayerCard(PlayerCard c, Player p, bool showVolume)
    {
        c.Title.Text = p.Title;
        c.Artist.Text = p.Artist.Length > 0 ? p.Artist : p.App;
        c.ToggleIcon.Glyph = p.Playing ? "" : "";
        c.Prev.IsEnabled = p.CanPrev;
        c.Next.IsEnabled = p.CanNext;
        c.Bar.Visibility = p.Duration > 0 ? Visibility.Visible : Visibility.Collapsed;
        var art = _state.PlayerArt.GetValueOrDefault(p.Id) ?? _state.Icons.GetValueOrDefault(p.Pkg);
        if (!ReferenceEquals(art, c.ShownArt))
        {
            c.ShownArt = art;
            c.Art.Source = art != null ? Bitmap(art) : null;
            c.ArtPlaceholder.Visibility = art == null ? Visibility.Visible : Visibility.Collapsed;
        }
        var vol = _state.PhoneVolume;
        c.VolumeRow.Visibility = showVolume && vol != null ? Visibility.Visible : Visibility.Collapsed;
        // Don't fight the slider while it's being dragged (its value is ahead of what the phone has reported).
        if (vol is { } v && _pendingVolume == null && (int)c.Volume.Value != v)
        {
            _settingVolume = true;
            c.Volume.Value = v;
            _settingVolume = false;
        }
        if (vol is { } v2 && _pendingVolume == null) c.VolumeIcon.Glyph = VolumeGlyph(v2);
    }

    static string VolumeGlyph(int v) => v == 0 ? "" : v < 34 ? "" : v < 67 ? "" : "";

    /// <summary>About ten updates a second while dragging, and always the last value.</summary>
    void SendVolumeSoon(int v)
    {
        var first = _pendingVolume == null;
        _pendingVolume = v;
        if (DateTimeOffset.Now - _lastVolumeSent > TimeSpan.FromMilliseconds(100)) { FlushVolume(); return; }
        if (!first) return;
        var t = DispatcherQueue.CreateTimer();
        t.Interval = TimeSpan.FromMilliseconds(120);
        t.IsRepeating = false;
        t.Tick += (_, _) => FlushVolume();
        t.Start();
    }

    void FlushVolume()
    {
        if (_pendingVolume is not { } v) return;
        _pendingVolume = null;
        _lastVolumeSent = DateTimeOffset.Now;
        _state.SetPhoneVolume(v);
    }

    LiveRow NewLiveRow()
    {
        var icon = new ContentControl { Width = 20, Height = 20, VerticalAlignment = VerticalAlignment.Center, HorizontalContentAlignment = HorizontalAlignment.Center };
        var title = new TextBlock { TextTrimming = TextTrimming.CharacterEllipsis, FontWeight = Microsoft.UI.Text.FontWeights.Medium };
        var text = new TextBlock { Style = Style("CaptionTextBlockStyle"), Foreground = Brush("TextFillColorSecondaryBrush"), TextTrimming = TextTrimming.CharacterEllipsis };
        var glance = new TextBlock { FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, VerticalAlignment = VerticalAlignment.Center, Foreground = Brush("AccentTextFillColorPrimaryBrush") };
        var grid = new Grid { ColumnSpacing = 10 };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition());
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.Children.Add(icon);
        var names = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Children = { title, text } };
        Grid.SetColumn(names, 1);
        grid.Children.Add(names);
        Grid.SetColumn(glance, 2);
        grid.Children.Add(glance);
        var root = new Border
        {
            Padding = new Thickness(10, 8, 10, 8), CornerRadius = new CornerRadius(8),
            Background = Brush("CardBackgroundFillColorDefaultBrush"), BorderBrush = Brush("CardStrokeColorDefaultBrush"), BorderThickness = new Thickness(1),
            Child = grid,
        };
        return new LiveRow { Root = root, Title = title, Text = text, Glance = glance, Icon = icon };
    }
}
