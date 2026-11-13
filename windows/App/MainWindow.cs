using CommunityToolkit.WinUI.Controls;
using MacNDroid.Core;
using Microsoft.UI.Text;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using QRCoder;
using Windows.ApplicationModel.DataTransfer;
using Windows.Storage;
using Windows.Storage.Pickers;
using Windows.Storage.Streams;

namespace MacNDroid;

/// <summary>The main window, built from stock WinUI 3 parts (NavigationView, Mica, Settings-style cards) so it looks
/// like Windows 11's own apps. Same pages as the Mac app: Overview, Notifications, Messages, Phone screen, Clipboard,
/// Files, Pair a phone and Settings.</summary>
public sealed class MainWindow : Window
{
    readonly AppState _state;
    readonly NavigationView _nav = new();
    readonly Grid _content = new();
    readonly Dictionary<string, FrameworkElement> _pages = new();
    readonly InfoBadge _badge = new() { Visibility = Visibility.Collapsed };
    bool _quitting;
    string _current = "overview";

    public MainWindow(AppState state)
    {
        _state = state;
        Title = "Mac n Droid";
        SystemBackdrop = new MicaBackdrop();
        ExtendsContentIntoTitleBar = true;
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"));
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1100, 760));
        AppWindow.TitleBar.PreferredHeightOption = TitleBarHeightOption.Tall;
        // Closing hides the window; Mac n Droid keeps running in the notification area.
        AppWindow.Closing += (_, e) =>
        {
            if (_quitting) return;
            e.Cancel = true;
            AppWindow.Hide();
        };

        var titleBar = new Grid { Height = 48, Padding = new Thickness(16, 0, 0, 0) };
        var titleRow = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12, VerticalAlignment = VerticalAlignment.Center };
        titleRow.Children.Add(new Image { Width = 16, Height = 16, Source = new BitmapImage(new Uri(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.png"))) });
        titleRow.Children.Add(new TextBlock { Text = "Mac n Droid", Style = Style("CaptionTextBlockStyle"), VerticalAlignment = VerticalAlignment.Center });
        titleBar.Children.Add(titleRow);
        SetTitleBar(titleBar);

        _nav.PaneDisplayMode = NavigationViewPaneDisplayMode.Left;
        _nav.IsBackButtonVisible = NavigationViewBackButtonVisible.Collapsed;
        _nav.IsSettingsVisible = true;
        _nav.OpenPaneLength = 260;
        _nav.MenuItems.Add(NavItem("Overview", "", "overview"));
        var notifications = NavItem("Notifications", "", "notifications");
        notifications.InfoBadge = _badge;
        _nav.MenuItems.Add(notifications);
        _nav.MenuItems.Add(NavItem("Messages", "\uE8BD", "messages"));
        _nav.MenuItems.Add(NavItem("Phone screen", "\uE7F4", "screen"));
        _nav.MenuItems.Add(NavItem("Clipboard", "", "clipboard"));
        _nav.MenuItems.Add(NavItem("Files", "", "files"));
        _nav.MenuItems.Add(new NavigationViewItemHeader { Content = "Setup" });
        _nav.MenuItems.Add(NavItem("Pair a phone", "", "pair"));
        _nav.SelectionChanged += (_, e) =>
        {
            var tag = e.IsSettingsSelected ? "settings" : (e.SelectedItem as NavigationViewItem)?.Tag as string;
            if (tag != null) Show(tag);
        };
        _nav.Content = _content;

        var root = new Grid();
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition());
        root.Children.Add(titleBar);
        Grid.SetRow(_nav, 1);
        root.Children.Add(_nav);
        // Drop files anywhere on the window to send them.
        root.AllowDrop = true;
        root.DragOver += (_, e) =>
        {
            if (!e.DataView.Contains(StandardDataFormats.StorageItems)) return;
            e.AcceptedOperation = DataPackageOperation.Copy;
            e.DragUIOverride.Caption = $"Send to {_state.PhoneName}";
        };
        root.Drop += async (_, e) =>
        {
            if (!e.DataView.Contains(StandardDataFormats.StorageItems)) return;
            var items = await e.DataView.GetStorageItemsAsync();
            SendFiles(items.Select(i => i.Path));
        };
        Content = root;

        foreach (var change in new Action<Action>[] {
                     h => state.StatusChanged += h, h => state.NotificationsChanged += h, h => state.ClipsChanged += h,
                     h => state.TransfersChanged += h, h => state.PairingChanged += h })
            change(Refresh);
        state.MediaChanged += () => { if (_current == "overview") Refresh(); };
        state.LiveChanged += () => { if (_current == "overview") Refresh(); };
        state.SmsChanged += () => { if (_current == "messages") UpdateMessages(); };
        state.ContactsChanged += UpdateContacts;
        state.MirrorChanged += () => { if (_current == "screen") UpdateScreen(); };
        state.ApprovalRequested += ShowApproval;
        _mirror = new MirrorSurface(state);
        state.MirrorSink = _mirror;
        // Addresses change when the network does.
        var timer = DispatcherQueue.CreateTimer();
        timer.Interval = TimeSpan.FromSeconds(30);
        timer.Tick += (_, _) => Refresh();
        timer.Start();

        Navigate(string.IsNullOrEmpty(state.LastPhoneName) ? "pair" : "overview");
    }

    public void CloseForReal()
    {
        _quitting = true;
        Close();
    }

    static NavigationViewItem NavItem(string text, string glyph, string tag) =>
        new() { Content = text, Icon = new FontIcon { Glyph = glyph }, Tag = tag };

    static Style Style(string key) => (Style)Application.Current.Resources[key];
    static Brush Brush(string key) => (Brush)Application.Current.Resources[key];

    public void Navigate(string page)
    {
        if (page == "settings") _nav.SelectedItem = _nav.SettingsItem;
        else _nav.SelectedItem = _nav.MenuItems.OfType<NavigationViewItem>().FirstOrDefault(i => (string)i.Tag == page);
        Show(page);
    }

    void Show(string page)
    {
        _current = page;
        if (page == "messages") _state.RefreshSms();
        Refresh();
    }

    // MARK: Building blocks

    static StackPanel PagePanel(string title, out StackPanel body)
    {
        var outer = new StackPanel { Padding = new Thickness(36, 12, 36, 36), MaxWidth = 1000, HorizontalAlignment = HorizontalAlignment.Stretch };
        outer.Children.Add(new TextBlock { Text = title, Style = Style("TitleTextBlockStyle"), Margin = new Thickness(0, 0, 0, 20) });
        body = new StackPanel { Spacing = 4 };
        outer.Children.Add(body);
        return outer;
    }

    static TextBlock SectionHeader(string text) =>
        new() { Text = text, Style = Style("BodyStrongTextBlockStyle"), Margin = new Thickness(0, 24, 0, 8) };

    static FontIcon Glyph(string glyph) => new() { Glyph = glyph };

    static IconElement AppIcon(byte[]? png)
    {
        if (png == null) return Glyph("");
        var bitmap = new BitmapImage();
        var stream = new InMemoryRandomAccessStream();
        using (var writer = new DataWriter(stream.GetOutputStreamAt(0)))
        {
            writer.WriteBytes(png);
            writer.StoreAsync().AsTask().Wait();
            writer.DetachStream();
        }
        bitmap.SetSource(stream);
        return new ImageIcon { Source = bitmap };
    }

    static string Ago(DateTimeOffset t)
    {
        var s = DateTimeOffset.Now - t;
        if (s.TotalMinutes < 1) return "Now";
        if (s.TotalHours < 1) return $"{(int)s.TotalMinutes} min ago";
        return t.ToString("t");
    }

    static string Size(long n) =>
        n < 1000 ? $"{n} bytes" : n < 1_000_000 ? $"{n / 1000.0:0.0} KB" : n < 1_000_000_000 ? $"{n / 1e6:0.0} MB" : $"{n / 1e9:0.0} GB";

    static UIElement Empty(string glyph, string title, string text, Button? action = null)
    {
        var p = new StackPanel { Spacing = 8, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, MaxWidth = 420 };
        p.Children.Add(new FontIcon { Glyph = glyph, FontSize = 48, Foreground = Brush("TextFillColorTertiaryBrush"), Margin = new Thickness(0, 0, 0, 8) });
        p.Children.Add(new TextBlock { Text = title, Style = Style("SubtitleTextBlockStyle"), HorizontalAlignment = HorizontalAlignment.Center, TextAlignment = TextAlignment.Center });
        p.Children.Add(new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center, Foreground = Brush("TextFillColorSecondaryBrush") });
        if (action != null)
        {
            action.HorizontalAlignment = HorizontalAlignment.Center;
            action.Margin = new Thickness(0, 12, 0, 0);
            p.Children.Add(action);
        }
        return p;
    }

    static Button AccentButton(string text, Action click)
    {
        var b = new Button { Content = text, Style = Style("AccentButtonStyle") };
        b.Click += (_, _) => click();
        return b;
    }

    static Button PlainButton(string text, Action click)
    {
        var b = new Button { Content = text };
        b.Click += (_, _) => click();
        return b;
    }

    void SetPage(UIElement element, bool scroll = true)
    {
        _content.Children.Clear();
        _content.Children.Add(scroll ? new ScrollViewer { Content = element } : element);
    }

    // MARK: Pages

    void Refresh()
    {
        var count = _state.Notifications.Count;
        _badge.Value = count;
        _badge.Visibility = count > 0 ? Visibility.Visible : Visibility.Collapsed;
        switch (_current)
        {
            case "overview": BuildOverview(); break;
            case "notifications": BuildNotifications(); break;
            case "messages": ShowMessages(); break;
            case "screen": ShowScreen(); break;
            case "clipboard": BuildClipboard(); break;
            case "files": BuildFiles(); break;
            case "pair": BuildPair(); break;
            case "settings": BuildSettings(); break;
        }
    }

    void BuildOverview()
    {
        if (!_state.Connected)
        {
            var paired = !string.IsNullOrEmpty(_state.LastPhoneName);
            SetPage(Empty("",
                paired ? $"Waiting for {_state.PhoneName}" : "Pair your phone",
                paired ? "Make sure Mac n Droid is open on your phone and both devices are on the same Wi-Fi network."
                       : "Scan a code with the Mac n Droid app on your Android phone to get started.",
                paired ? null : AccentButton("Pair a phone", () => Navigate("pair"))), scroll: false);
            if (!string.IsNullOrEmpty(_state.LastError))
                ((StackPanel)((Grid)_content).Children[0]).Children.Add(new InfoBar
                    { IsOpen = true, Severity = InfoBarSeverity.Warning, Message = _state.LastError, IsClosable = false });
            return;
        }
        var page = PagePanel("Overview", out var body);
        var model = string.Join(" · ", new[] { (string?)_state.Phone?["model"], (string?)_state.Phone?["os"] }.Where(s => !string.IsNullOrEmpty(s)));
        var phone = new SettingsCard { Header = _state.PhoneName, Description = model, HeaderIcon = Glyph("") };
        if (_state.Battery is { } battery)
            phone.Content = new TextBlock { Text = (_state.Charging ? "  " : "") + $"{battery}%", Style = Style("SubtitleTextBlockStyle"), FontFamily = new FontFamily("Segoe UI Variable, Segoe Fluent Icons") };
        body.Children.Add(phone);

        body.Children.Add(SectionHeader("Quick actions"));
        var actions = new Grid { ColumnSpacing = 8, RowSpacing = 8 };
        var quick = new (string text, string glyph, Action click)[]
        {
            (_state.Ringing ? "Stop ringing" : "Ring phone", _state.Ringing ? "" : "", _state.Ring),
            ("Send files", "", ChooseFiles),
            ("Send clipboard", "", SendClipboard),
            ("Open link on phone", "", OpenLinkDialog),
            ("Messages", "\uE8BD", () => Navigate("messages")),
            ("Phone screen", "\uE7F4", () => Navigate("screen")),
        };
        for (int c = 0; c < 3; c++) actions.ColumnDefinitions.Add(new ColumnDefinition());
        for (int r = 0; r < (quick.Length + 2) / 3; r++) actions.RowDefinitions.Add(new RowDefinition());
        for (int i = 0; i < quick.Length; i++)
        {
            var (text, glyph, click) = quick[i];
            var content = new StackPanel { Spacing = 8, Padding = new Thickness(0, 12, 0, 12) };
            content.Children.Add(new FontIcon { Glyph = glyph, FontSize = 20 });
            content.Children.Add(new TextBlock { Text = text, HorizontalAlignment = HorizontalAlignment.Center, TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center });
            var b = new Button { Content = content, HorizontalAlignment = HorizontalAlignment.Stretch, HorizontalContentAlignment = HorizontalAlignment.Center };
            b.Click += (_, _) => click();
            Grid.SetColumn(b, i % 3);
            Grid.SetRow(b, i / 3);
            actions.Children.Add(b);
        }
        body.Children.Add(actions);
        AddOnYourPhone(body);

        var header = new Grid { Margin = new Thickness(0, 24, 0, 8) };
        header.Children.Add(new TextBlock { Text = "Recent notifications", Style = Style("BodyStrongTextBlockStyle"), VerticalAlignment = VerticalAlignment.Center });
        if (_state.Notifications.Count > 0)
        {
            var all = new HyperlinkButton { Content = "Show all", HorizontalAlignment = HorizontalAlignment.Right, Padding = new Thickness(4) };
            all.Click += (_, _) => Navigate("notifications");
            header.Children.Add(all);
        }
        body.Children.Add(header);
        if (_state.Notifications.Count == 0)
            body.Children.Add(new SettingsCard { Header = "No notifications", HeaderIcon = Glyph("") });
        foreach (var n in _state.Notifications.Take(5))
            body.Children.Add(new SettingsCard
            {
                Header = string.IsNullOrEmpty(n.Title) ? n.App : n.Title, Description = n.Text,
                HeaderIcon = AppIcon(_state.Icons.GetValueOrDefault(n.Pkg)),
                Content = new TextBlock { Text = Ago(n.Time), Foreground = Brush("TextFillColorSecondaryBrush") },
            });
        SetPage(page);
    }

    void BuildNotifications()
    {
        if (_state.Notifications.Count == 0)
        {
            SetPage(Empty("", "No notifications", "Notifications from your phone appear here."), scroll: false);
            return;
        }
        var page = PagePanel("Notifications", out var body);
        foreach (var n in _state.Notifications)
        {
            var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            foreach (var a in n.Actions.Take(3))
            {
                var label = a.IsReply && a.OpensOnPhone ? "Reply on phone" : a.Title;
                var key = n.Key;
                var index = a.Index;
                buttons.Children.Add(PlainButton(label, a.IsReply && !a.OpensOnPhone
                    ? () => ReplyDialog(key, index)
                    : () => _state.Action(key, index)));
            }
            var dismiss = new Button { Content = new FontIcon { Glyph = "", FontSize = 12 }, Style = Style("SubtleButtonStyle") };
            ToolTipService.SetToolTip(dismiss, "Dismiss");
            var dismissKey = n.Key;
            dismiss.Click += (_, _) => _state.Dismiss(dismissKey);
            buttons.Children.Add(dismiss);
            body.Children.Add(new SettingsCard
            {
                Header = string.IsNullOrEmpty(n.Title) ? n.App : n.Title,
                Description = $"{n.Text}\n{n.App} · {Ago(n.Time)}",
                HeaderIcon = AppIcon(_state.Icons.GetValueOrDefault(n.Pkg)),
                Content = buttons,
            });
        }
        SetPage(page);
    }

    void BuildClipboard()
    {
        if (_state.Clips.Count == 0)
        {
            SetPage(Empty("", "Nothing copied yet", "The last 20 things you copy on this PC or your phone appear here, on both."), scroll: false);
            return;
        }
        var page = PagePanel("Clipboard", out var body);
        var top = new Grid { Margin = new Thickness(0, 0, 0, 8) };
        top.Children.Add(new TextBlock { Text = "Select an item to copy it again.", Foreground = Brush("TextFillColorSecondaryBrush"), VerticalAlignment = VerticalAlignment.Center });
        var clear = PlainButton("Clear history", ConfirmClear);
        clear.HorizontalAlignment = HorizontalAlignment.Right;
        top.Children.Add(clear);
        body.Children.Add(top);
        foreach (var c in _state.Clips)
        {
            var who = c.FromPhone ? "From your phone" : "From this PC";
            var card = new SettingsCard
            {
                Header = c.Kind == "text" ? c.Text.Trim().Length > 300 ? c.Text.Trim()[..300] : c.Text.Trim() : "Image",
                Description = $"{who} · {Ago(c.Time)}",
                HeaderIcon = c.Kind == "text" ? Glyph("") : AppIcon(c.Png),
                IsClickEnabled = true,
                ActionIcon = Glyph(""),
            };
            var clip = c;
            card.Click += (_, _) => _state.UseClip(clip);
            body.Children.Add(card);
        }
        SetPage(page);
    }

    void BuildFiles()
    {
        if (_state.Transfers.Count == 0)
        {
            SetPage(Empty("", "Send files", "Drop files or folders on this window to send them to your phone. Files from your phone are saved in Downloads.",
                AccentButton("Send files…", ChooseFiles)), scroll: false);
            return;
        }
        var page = PagePanel("Files", out var body);
        var sendRow = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Margin = new Thickness(0, 0, 0, 8) };
        sendRow.Children.Add(AccentButton("Send files…", ChooseFiles));
        sendRow.Children.Add(PlainButton("Send folder…", ChooseFolder));
        body.Children.Add(sendRow);
        foreach (var t in _state.Transfers.Take(50))
        {
            var direction = t.ToPhone ? "To your phone" : "From your phone";
            var detail = t.State switch
            {
                "active" when t.Folder && t.Size == 0 => $"{direction} · Preparing folder…",
                "active" => $"{direction} · {Size(t.Bytes)} of {Size(t.Size)}",
                "failed" => $"{direction} · Didn't finish",
                _ => $"{direction} · {Size(t.Size)}",
            };
            var card = new SettingsCard { Header = t.DisplayName, Description = detail, HeaderIcon = Glyph(t.Folder ? "\uE8B7" : t.ToPhone ? "" : "") };
            if (t.State == "active" && t.Size > 0)
                card.Content = new ProgressBar { Value = 100.0 * t.Bytes / t.Size, Width = 160 };
            else if (t.State == "done" && !t.ToPhone)
            {
                var path = t.Path;
                card.Content = t.Folder
                    ? PlainButton("Open folder", () => System.Diagnostics.Process.Start("explorer.exe", $"\"{path}\""))
                    : PlainButton("Show in folder", () => System.Diagnostics.Process.Start("explorer.exe", $"/select,\"{path}\""));
            }
            body.Children.Add(card);
        }
        SetPage(page);
    }

    void BuildPair()
    {
        var page = PagePanel("Pair a phone", out var body);
        var top = new Grid { ColumnSpacing = 32, Margin = new Thickness(0, 0, 0, 8) };
        top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        top.ColumnDefinitions.Add(new ColumnDefinition());
        var qr = new Border
        {
            Background = new SolidColorBrush(Microsoft.UI.Colors.White), CornerRadius = new CornerRadius(8), Padding = new Thickness(12),
            Child = new Image { Width = 240, Height = 240, Source = Qr(_state.QrText) },
            VerticalAlignment = VerticalAlignment.Top,
        };
        top.Children.Add(qr);
        var steps = new StackPanel { Spacing = 12, VerticalAlignment = VerticalAlignment.Center };
        steps.Children.Add(new TextBlock { Text = "Pair your Android phone", Style = Style("SubtitleTextBlockStyle") });
        string[] lines =
        [
            "No app yet? Scan this code with your phone's camera to get Mac n Droid from Google Play.",
            "Open Mac n Droid and tap Scan QR code.",
            "Point your phone at this code.",
        ];
        for (int i = 0; i < lines.Length; i++)
            steps.Children.Add(new TextBlock { Text = $"{i + 1}.  {lines[i]}", TextWrapping = TextWrapping.Wrap });
        steps.Children.Add(new TextBlock
        {
            Text = "Both devices need to be on the same Wi-Fi network. Everything stays on your network and is end-to-end encrypted.",
            TextWrapping = TextWrapping.Wrap, Foreground = Brush("TextFillColorSecondaryBrush"),
        });
        Grid.SetColumn(steps, 1);
        top.Children.Add(steps);
        body.Children.Add(top);

        body.Children.Add(SectionHeader("Connection"));
        body.Children.Add(new SettingsCard { Header = "This PC", Content = _state.Name });
        body.Children.Add(new SettingsCard { Header = "Address", Content = _state.Addresses.Count > 0 ? string.Join(", ", _state.Addresses) : "No network" });
        body.Children.Add(new SettingsCard { Header = "Port", Content = _state.Server.Port.ToString() });
        body.Children.Add(new SettingsCard { Header = "Status", Content = _state.Connected ? $"Connected to {_state.PhoneName}" : "Waiting for phone" });

        body.Children.Add(SectionHeader("Pairing"));
        var copy = new SettingsCard { Header = "Copy pairing link", IsClickEnabled = true, ActionIcon = Glyph("") };
        copy.Click += (_, _) =>
        {
            var p = new DataPackage();
            p.SetText(_state.PairingLink);
            Clipboard.SetContent(p);
        };
        body.Children.Add(copy);
        body.Children.Add(new SettingsCard
        {
            Header = "Reset pairing",
            Description = "Creates a new code. Phones paired with the old code are disconnected and must scan again.",
            Content = PlainButton("Reset…", ConfirmReset),
        });
        SetPage(page);
    }

    void BuildSettings()
    {
        var app = App.Current;
        var page = PagePanel("Settings", out var body);
        body.Children.Add(SectionHeader("Notifications"));
        body.Children.Add(Toggle("Show phone notifications on this PC", null, app.Settings.MirrorNotifications, on =>
        {
            app.Settings.MirrorNotifications = on;
            _state.MirrorNotifications = on;
            app.Settings.Save();
        }));
        body.Children.Add(Toggle("Play sounds", "Sounds for notifications from your phone and for the left-behind alert.",
            app.Settings.Sounds, on => { app.Settings.Sounds = on; app.Platform.Sounds = on; app.Settings.Save(); }));
        body.Children.Add(SectionHeader("Clipboard"));
        body.Children.Add(Toggle("Sync clipboard automatically",
            "Text and images you copy on this PC are sent to your phone, and the last 20 copies from both are kept in Clipboard history.",
            app.Settings.SyncClipboard, on => { app.Settings.SyncClipboard = on; app.Settings.Save(); }));
        body.Children.Add(SectionHeader("Phone left behind"));
        body.Children.Add(Toggle("Alert me if I leave my phone behind",
            "An alert if this PC moves to a different network while your phone stays behind (it can't measure distance, only Wi-Fi). "
            + "Your phone alerts you the other way round, when you leave the Wi-Fi network without it.",
            app.Settings.LeftBehind, on => { app.Settings.LeftBehind = on; _state.LeftBehindAlerts = on; app.Settings.Save(); }));
        body.Children.Add(SectionHeader("Startup"));
        body.Children.Add(Toggle("Open at sign-in",
            "Mac n Droid keeps running in the notification area after you close its window, so notifications and files keep arriving.",
            Settings.OpenAtLogin, on => Settings.OpenAtLogin = on));
        body.Children.Add(SectionHeader("About"));
        var version = typeof(App).Assembly.GetName().Version;
        body.Children.Add(new SettingsCard
        {
            Header = "Mac n Droid", Description = $"Version {version?.ToString(3)} · Free and open source (GPL-3.0)",
            HeaderIcon = new ImageIcon { Source = new BitmapImage(new Uri(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.png"))) },
            Content = new HyperlinkButton { Content = "GitHub", NavigateUri = new Uri("https://github.com/santosh-mudragada-1/mac-n-droid") },
        });
        SetPage(page);
    }

    static SettingsCard Toggle(string header, string? description, bool value, Action<bool> changed)
    {
        var toggle = new ToggleSwitch { IsOn = value };
        toggle.Toggled += (_, _) => changed(toggle.IsOn);
        return new SettingsCard { Header = header, Description = description!, Content = toggle };
    }

    static BitmapImage Qr(string text)
    {
        using var gen = new QRCodeGenerator();
        using var data = gen.CreateQrCode(text, QRCodeGenerator.ECCLevel.M);
        var png = new PngByteQRCode(data).GetGraphic(10);
        var bitmap = new BitmapImage();
        var stream = new InMemoryRandomAccessStream();
        using (var writer = new DataWriter(stream.GetOutputStreamAt(0)))
        {
            writer.WriteBytes(png);
            writer.StoreAsync().AsTask().Wait();
            writer.DetachStream();
        }
        bitmap.SetSource(stream);
        return bitmap;
    }

    // MARK: On your phone (music and live activities)

    void AddOnYourPhone(StackPanel body)
    {
        if (_state.Players.Count == 0 && _state.Live.Count == 0) return;
        body.Children.Add(SectionHeader("On your phone"));
        foreach (var p in _state.Players.Take(3))
        {
            var controls = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 4 };
            var id = p.Id;
            controls.Children.Add(IconButton("\uE892", "Previous", () => _state.MediaCommand("prev", id), p.CanPrev));
            controls.Children.Add(IconButton(p.Playing ? "\uE769" : "\uE768", p.Playing ? "Pause" : "Play", () => _state.MediaCommand("toggle", id), true));
            controls.Children.Add(IconButton("\uE893", "Next", () => _state.MediaCommand("next", id), p.CanNext));
            body.Children.Add(new SettingsCard
            {
                Header = p.Title,
                Description = string.Join(" · ", new[] { p.Artist, p.App }.Where(x => x.Length > 0)),
                HeaderIcon = AppIcon(_state.PlayerArt.GetValueOrDefault(p.Id) ?? _state.Icons.GetValueOrDefault(p.Pkg)),
                Content = controls,
            });
        }
        foreach (var a in _state.Live.Values.Take(4))
        {
            var kind = (string?)a["kind"] ?? "";
            var card = new SettingsCard
            {
                Header = (string?)a["title"] is { Length: > 0 } t ? t : (string?)a["app"] ?? "",
                Description = (string?)a["text"] is { Length: > 0 } x ? x : (string?)a["short"] ?? "",
                HeaderIcon = AppIcon(_state.Icons.GetValueOrDefault((string?)a["pkg"] ?? "")),
            };
            double max = (double?)a["max"] ?? 0, progress = (double?)a["progress"] ?? 0;
            if (kind == "progress" && max > 0) card.Content = new ProgressBar { Value = 100 * progress / max, Width = 160 };
            else if (kind is "call" or "timer" or "stopwatch" && (double?)a["when"] is { } when)
                card.Content = new TextBlock { Text = Chrono(when, kind == "timer", (double?)a["paused"]), Foreground = Brush("TextFillColorSecondaryBrush") };
            body.Children.Add(card);
        }
    }

    static string Chrono(double whenMs, bool countdown, double? pausedMs)
    {
        double nowMs = DateTimeOffset.Now.ToUnixTimeMilliseconds();
        double secs = pausedMs is >= 0 ? pausedMs.Value / 1000 : (countdown ? whenMs - nowMs : nowMs - whenMs) / 1000;
        var t = TimeSpan.FromSeconds(Math.Max(0, (int)secs));
        return t.TotalHours >= 1 ? t.ToString(@"h\:mm\:ss") : t.ToString(@"m\:ss");
    }

    static Button IconButton(string glyph, string tip, Action click, bool enabled)
    {
        var b = new Button { Content = new FontIcon { Glyph = glyph, FontSize = 14 }, Style = Style("SubtleButtonStyle"), IsEnabled = enabled };
        ToolTipService.SetToolTip(b, tip);
        b.Click += (_, _) => click();
        return b;
    }

    // MARK: Messages (built once, so typing isn't lost when texts arrive)

    Grid? _messages;
    readonly ListView _threadList = new() { SelectionMode = ListViewSelectionMode.Single };
    readonly StackPanel _bubbles = new() { Spacing = 6, Padding = new Thickness(20, 16, 20, 16) };
    readonly ScrollViewer _bubbleScroll = new();
    readonly TextBox _smsBox = new() { PlaceholderText = "Text message", AcceptsReturn = false };
    readonly Grid _smsBar = new() { ColumnSpacing = 8, Padding = new Thickness(16, 8, 16, 16) };
    readonly TextBlock _cantReply = new() { Text = "You can't reply to this sender.", Margin = new Thickness(16), HorizontalAlignment = HorizontalAlignment.Center };
    readonly Grid _convo = new();
    readonly ContentControl _messagesStatus = new() { HorizontalContentAlignment = HorizontalAlignment.Stretch, VerticalContentAlignment = VerticalAlignment.Stretch };
    bool _fillingThreads;

    void BuildMessagesOnce()
    {
        if (_messages != null) return;
        _messages = new Grid();
        _messages.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(320) });
        _messages.ColumnDefinitions.Add(new ColumnDefinition());
        var left = new Grid { Padding = new Thickness(8, 8, 0, 8) };
        left.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        left.RowDefinitions.Add(new RowDefinition());
        var top = new Grid { Padding = new Thickness(12, 4, 12, 8) };
        top.Children.Add(new TextBlock { Text = "Messages", Style = Style("SubtitleTextBlockStyle"), VerticalAlignment = VerticalAlignment.Center });
        var compose = IconButton("\uE710", "New message", NewMessageDialog, true);
        compose.HorizontalAlignment = HorizontalAlignment.Right;
        top.Children.Add(compose);
        left.Children.Add(top);
        Grid.SetRow(_threadList, 1);
        left.Children.Add(_threadList);
        _threadList.SelectionChanged += (_, _) =>
        {
            if (_fillingThreads) return;
            var id = (_threadList.SelectedItem as FrameworkElement)?.Tag as string;
            if (id != _state.SmsOpen) _state.OpenSmsThread(id);
            ShowThread(id);
        };
        _messages.Children.Add(left);

        _convo.RowDefinitions.Add(new RowDefinition());
        _convo.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _bubbleScroll.Content = _bubbles;
        _convo.Children.Add(_bubbleScroll);
        _smsBar.ColumnDefinitions.Add(new ColumnDefinition());
        _smsBar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        _smsBox.KeyDown += (_, e) => { if (e.Key == Windows.System.VirtualKey.Enter) { e.Handled = true; SendSms(); } };
        _smsBar.Children.Add(_smsBox);
        var send = AccentButton("Send", SendSms);
        Grid.SetColumn(send, 1);
        _smsBar.Children.Add(send);
        var bottom = new StackPanel();
        bottom.Children.Add(_smsBar);
        _cantReply.Foreground = Brush("TextFillColorSecondaryBrush");
        bottom.Children.Add(_cantReply);
        Grid.SetRow(bottom, 1);
        _convo.Children.Add(bottom);
        Grid.SetColumn(_convo, 1);
        _messages.Children.Add(_convo);
    }

    void ShowMessages()
    {
        BuildMessagesOnce();
        UpdateMessages();
    }

    void UpdateMessages()
    {
        BuildMessagesOnce();
        var st = _state;
        string? title = null, text = null;
        if (!st.Connected) { title = "Messages"; text = $"Connect {st.PhoneName} to read and send texts."; }
        else if (st.SmsState == "off") { title = "Turn on Messages"; text = "On your phone, open Mac n Droid and turn on Messages, then allow access to text messages."; }
        else if (st.SmsState == "permission") { title = "Allow SMS access"; text = "Mac n Droid on your phone needs access to text messages. Open it on your phone to allow it."; }
        else if (st.SmsState is "unknown" or "loading" && st.SmsThreads.Count == 0) { title = "Loading messages…"; text = ""; }
        if (title != null)
        {
            _messagesStatus.Content = Empty("\uE8BD", title, text ?? "");
            SetContent(_messagesStatus);
            return;
        }
        SetContent(_messages!);
        var selected = st.SmsOpen;
        if (st.SmsSelectAddress.Length > 0 && st.SmsThreads.FirstOrDefault(t => SmsThread.NumberKey(t.Address) == SmsThread.NumberKey(st.SmsSelectAddress)) is { } match)
        {
            selected = match.Id;
            st.SmsSelectAddress = "";
            st.OpenSmsThread(selected);
        }
        _fillingThreads = true;
        _threadList.Items.Clear();
        foreach (var t in st.SmsThreads)
        {
            var row = new Grid { Tag = t.Id, Padding = new Thickness(4, 8, 4, 8) };
            row.ColumnDefinitions.Add(new ColumnDefinition());
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var names = new StackPanel { Spacing = 2 };
            names.Children.Add(new TextBlock { Text = t.DisplayName, TextTrimming = TextTrimming.CharacterEllipsis, FontWeight = t.Unread > 0 ? FontWeights.SemiBold : FontWeights.Normal });
            names.Children.Add(new TextBlock { Text = (t.LastIsOutgoing ? "You: " : "") + t.Snippet, TextTrimming = TextTrimming.CharacterEllipsis, MaxLines = 2, TextWrapping = TextWrapping.Wrap, Foreground = Brush("TextFillColorSecondaryBrush"), Style = Style("CaptionTextBlockStyle") });
            row.Children.Add(names);
            var when = new TextBlock { Text = ShortTime(t.Date), Foreground = Brush("TextFillColorTertiaryBrush"), Style = Style("CaptionTextBlockStyle"), Margin = new Thickness(8, 0, 0, 0) };
            Grid.SetColumn(when, 1);
            row.Children.Add(when);
            _threadList.Items.Add(row);
            if (t.Id == selected) _threadList.SelectedItem = row;
        }
        _fillingThreads = false;
        ShowThread(st.FindSmsThread(selected) != null ? selected : null);
    }

    void SetContent(UIElement element)
    {
        if (_content.Children.Count == 1 && _content.Children[0] == element) return;
        _content.Children.Clear();
        if (element is FrameworkElement { Parent: Panel parent }) parent.Children.Remove(element);
        _content.Children.Add(element);
    }

    static string ShortTime(DateTimeOffset t)
    {
        if (t.Date == DateTimeOffset.Now.Date) return t.ToString("t");
        if ((DateTimeOffset.Now - t).TotalDays < 6) return t.ToString("ddd");
        return t.ToString("d MMM");
    }

    void ShowThread(string? id)
    {
        var t = _state.FindSmsThread(id);
        _convo.Visibility = t == null ? Visibility.Collapsed : Visibility.Visible;
        if (t == null) return;
        _smsBar.Visibility = t.CanReply ? Visibility.Visible : Visibility.Collapsed;
        _cantReply.Visibility = t.CanReply ? Visibility.Collapsed : Visibility.Visible;
        _bubbles.Children.Clear();
        foreach (var m in _state.SmsMessages.GetValueOrDefault(t.Id) ?? new())
        {
            var bubble = new Border
            {
                CornerRadius = new CornerRadius(16), Padding = new Thickness(12, 7, 12, 7), MaxWidth = 480,
                HorizontalAlignment = m.Outgoing ? HorizontalAlignment.Right : HorizontalAlignment.Left,
                Background = m.Failed ? Brush("SystemFillColorCriticalBackgroundBrush")
                           : m.Outgoing ? Brush("AccentFillColorDefaultBrush") : Brush("CardBackgroundFillColorSecondaryBrush"),
                Child = new TextBlock
                {
                    Text = m.Body, TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true,
                    Foreground = m.Outgoing && !m.Failed ? Brush("TextOnAccentFillColorPrimaryBrush") : Brush("TextFillColorPrimaryBrush"),
                },
            };
            _bubbles.Children.Add(bubble);
            _bubbles.Children.Add(new TextBlock
            {
                Text = m.Failed ? "Not sent" : m.Pending ? "Sending…" : ShortTime(m.Date), Style = Style("CaptionTextBlockStyle"),
                Foreground = Brush("TextFillColorTertiaryBrush"), Margin = new Thickness(4, 0, 4, 6),
                HorizontalAlignment = m.Outgoing ? HorizontalAlignment.Right : HorizontalAlignment.Left,
            });
        }
        DispatcherQueue.TryEnqueue(() => _bubbleScroll.ChangeView(null, _bubbleScroll.ScrollableHeight + 1000, null, true));
    }

    void SendSms()
    {
        var id = _state.SmsOpen;
        var t = _state.FindSmsThread(id);
        var text = _smsBox.Text.Trim();
        if (t == null || text.Length == 0) return;
        _state.SendSms(t.Address, text, id);
        _smsBox.Text = "";
    }

    ListView? _contactResults;
    TextBox? _contactBox;

    async void NewMessageDialog()
    {
        if (!_state.Connected) { await Message("Your phone isn't connected", $"Connect {_state.PhoneName} first."); return; }
        var to = new TextBox { PlaceholderText = "Name or phone number", InputScope = new Microsoft.UI.Xaml.Input.InputScope { Names = { new(Microsoft.UI.Xaml.Input.InputScopeNameValue.TelephoneNumber) } } };
        var results = new ListView { SelectionMode = ListViewSelectionMode.None, IsItemClickEnabled = true, MaxHeight = 220, Visibility = Visibility.Collapsed };
        var body = new TextBox { PlaceholderText = "Text message", AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, MinHeight = 64 };
        to.TextChanged += (_, _) => _state.SearchContacts(to.Text);
        results.ItemClick += (_, e) =>
        {
            if ((e.ClickedItem as FrameworkElement)?.Tag is string number) { to.Text = number; results.Visibility = Visibility.Collapsed; body.Focus(FocusState.Programmatic); }
        };
        var panel = new StackPanel { Spacing = 8, MinWidth = 360 };
        panel.Children.Add(to);
        panel.Children.Add(results);
        panel.Children.Add(body);
        _contactResults = results;
        _contactBox = to;
        var d = new ContentDialog
        {
            Title = "New message", Content = panel, PrimaryButtonText = "Send", CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Primary, XamlRoot = Content.XamlRoot,
        };
        var result = await d.ShowAsync();
        _contactResults = null;
        if (result == ContentDialogResult.Primary && to.Text.Trim().Length > 0 && body.Text.Trim().Length > 0)
        {
            _state.SendSms(to.Text.Trim(), body.Text, null);
            Navigate("messages");
        }
    }

    void UpdateContacts()
    {
        if (_contactResults is not { } list || _contactBox is not { } box) return;
        list.Items.Clear();
        if (!_state.ContactsAllowed) list.Items.Add(new TextBlock { Text = "Contacts access is off on your phone", Foreground = Brush("TextFillColorSecondaryBrush") });
        foreach (var c in _state.Contacts.Take(6).Where(c => c.Number != box.Text.Trim()))
        {
            var row = new StackPanel { Tag = c.Number, Padding = new Thickness(0, 4, 0, 4) };
            row.Children.Add(new TextBlock { Text = c.Name.Length > 0 ? c.Name : c.Number });
            row.Children.Add(new TextBlock { Text = string.Join(" · ", new[] { c.Number, c.Label }.Where(x => x.Length > 0)), Style = Style("CaptionTextBlockStyle"), Foreground = Brush("TextFillColorSecondaryBrush") });
            list.Items.Add(row);
        }
        list.Visibility = list.Items.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    // MARK: Phone screen (built once; the video keeps playing while you use other pages)

    readonly MirrorSurface _mirror;
    Grid? _screen;
    readonly ContentControl _screenStatus = new() { HorizontalContentAlignment = HorizontalAlignment.Stretch, VerticalContentAlignment = VerticalAlignment.Stretch };
    readonly TextBlock _screenHint = new() { TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center };

    void ShowScreen() => UpdateScreen();

    void UpdateScreen()
    {
        var st = _state;
        if (st.Mirror == "live")
        {
            if (_screen == null)
            {
                _screen = new Grid { Padding = new Thickness(16), RowSpacing = 8 };
                _screen.RowDefinitions.Add(new RowDefinition());
                _screen.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                _screen.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                _screen.Children.Add(_mirror);
                _screenHint.Foreground = Brush("TextFillColorSecondaryBrush");
                Grid.SetRow(_screenHint, 1);
                _screen.Children.Add(_screenHint);
                var keys = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 4, HorizontalAlignment = HorizontalAlignment.Center };
                foreach (var (glyph, tip, key) in new[] { ("\uE72B", "Back", "back"), ("\uE80F", "Home", "home"), ("\uE7C4", "Recent apps", "recents"),
                                                          ("\uE993", "Volume down", "volume_down"), ("\uE995", "Volume up", "volume_up"), ("\uE72E", "Lock", "lock") })
                    keys.Children.Add(IconButton(glyph, tip, () => _state.SendInput(new System.Text.Json.Nodes.JsonObject { ["key"] = key }), true));
                var stop = PlainButton("Stop", _state.StopMirror);
                stop.Margin = new Thickness(12, 0, 0, 0);
                keys.Children.Add(stop);
                Grid.SetRow(keys, 2);
                _screen.Children.Add(keys);
            }
            _screenHint.Text = st.ControlUnavailable
                ? "Phone control is off. On your phone, turn on Mac n Droid clipboard and controls in Accessibility settings."
                : "Click to tap, drag to swipe. Right-click is Back. Type to enter text.";
            SetContent(_screen);
            _mirror.FocusInput();
            return;
        }
        var (title, text) = !st.Connected ? ("See your phone's screen", $"Connect {st.PhoneName} first.")
            : st.Mirror == "starting" ? ("Allow on your phone", "Your phone asks before sharing its screen. Tap Start on the phone.")
            : st.Mirror == "denied" ? ("Screen sharing declined", "Your phone didn't allow sharing its screen. Try again and tap Start.")
            : ("See your phone's screen", "Use your phone from this PC: click to tap, drag to swipe, and type to enter text. Your phone asks before sharing its screen.");
        var button = AccentButton(st.Mirror == "starting" ? "Waiting for your phone…" : "Show phone screen", _state.StartMirror);
        button.IsEnabled = st.Connected && st.Mirror != "starting";
        _screenStatus.Content = Empty("\uE7F4", title, text, button);
        SetContent(_screenStatus);
    }

    // MARK: Actions

    IntPtr Hwnd => WinRT.Interop.WindowNative.GetWindowHandle(this);

    public async void ChooseFiles()
    {
        if (!_state.Connected)
        {
            await Message("Your phone isn't connected", $"Connect {_state.PhoneName} first.");
            return;
        }
        var picker = new FileOpenPicker { CommitButtonText = "Send" };
        picker.FileTypeFilter.Add("*");
        WinRT.Interop.InitializeWithWindow.Initialize(picker, Hwnd);
        var files = await picker.PickMultipleFilesAsync();
        if (files.Count > 0) SendFiles(files.Select(f => f.Path));
    }

    public async void ChooseFolder()
    {
        if (!_state.Connected)
        {
            await Message("Your phone isn't connected", $"Connect {_state.PhoneName} first.");
            return;
        }
        var picker = new FolderPicker { CommitButtonText = "Send" };
        picker.FileTypeFilter.Add("*");
        WinRT.Interop.InitializeWithWindow.Initialize(picker, Hwnd);
        if (await picker.PickSingleFolderAsync() is { } folder) SendFiles([folder.Path]);
    }

    void SendFiles(IEnumerable<string> paths)
    {
        if (!_state.Connected) return;
        _state.SendFiles(paths);
        Navigate("files");
    }

    async void SendClipboard()
    {
        var (text, png) = await WindowsPlatform.ReadClipboardAsync();
        _state.SendClipboardNow(text, png);
    }

    async Task Message(string title, string text)
    {
        var d = new ContentDialog { Title = title, Content = text, CloseButtonText = "OK", XamlRoot = Content.XamlRoot };
        await d.ShowAsync();
    }

    async void ConfirmClear()
    {
        var d = new ContentDialog
        {
            Title = "Clear clipboard history?", Content = "This clears the list on this PC and on your phone.",
            PrimaryButtonText = "Clear", CloseButtonText = "Cancel", DefaultButton = ContentDialogButton.Close, XamlRoot = Content.XamlRoot,
        };
        if (await d.ShowAsync() == ContentDialogResult.Primary) _state.ClearHistory();
    }

    ContentDialog? _approval;

    /// <summary>Another phone scanned the code while one is paired: allow it? (The paired phone is asked too.)</summary>
    async void ShowApproval(string id, string name, string paired)
    {
        if (_approval is { } open) { _approval = null; open.Hide(); }
        if (id.Length == 0) return;
        var d = new ContentDialog
        {
            Title = $"Allow \u201c{name}\u201d to connect?",
            Content = $"It scanned this PC's pairing code. If you allow it, it replaces {paired}, which will be disconnected. "
                      + "Only allow a phone that's yours.",
            PrimaryButtonText = "Allow", CloseButtonText = "Don't allow", DefaultButton = ContentDialogButton.Close,
            XamlRoot = Content.XamlRoot,
        };
        _approval = d;
        Activate();
        ContentDialogResult r;
        try { r = await d.ShowAsync(); }
        catch (Exception) { return; }   // another dialog is open: the phone can still answer, or it times out
        if (_approval != d) return;   // answered on the phone, or timed out
        _approval = null;
        _state.ResolveApproval(id, r == ContentDialogResult.Primary);
    }

    async void ConfirmReset()
    {
        var d = new ContentDialog
        {
            Title = "Reset pairing?", Content = "All paired phones will need to scan the new QR code.",
            PrimaryButtonText = "Reset pairing", CloseButtonText = "Cancel", DefaultButton = ContentDialogButton.Close, XamlRoot = Content.XamlRoot,
        };
        if (await d.ShowAsync() == ContentDialogResult.Primary) _state.ResetPairing();
    }

    async void ReplyDialog(string key, int index)
    {
        var n = _state.Notification(key);
        var box = new TextBox { PlaceholderText = "Message", AcceptsReturn = false };
        var panel = new StackPanel { Spacing = 12 };
        if (n != null) panel.Children.Add(new TextBlock { Text = n.Text, TextWrapping = TextWrapping.Wrap, Foreground = Brush("TextFillColorSecondaryBrush") });
        panel.Children.Add(box);
        var d = new ContentDialog
        {
            Title = n != null ? $"Reply to {n.Title}" : "Reply", Content = panel, PrimaryButtonText = "Send", CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Primary, XamlRoot = Content.XamlRoot,
        };
        if (await d.ShowAsync() == ContentDialogResult.Primary && box.Text.Trim().Length > 0) _state.Action(key, index, box.Text.Trim());
    }

    async void OpenLinkDialog()
    {
        var box = new TextBox { PlaceholderText = "https://" };
        var d = new ContentDialog
        {
            Title = "Open link on phone", Content = box, PrimaryButtonText = "Open", CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Primary, XamlRoot = Content.XamlRoot,
        };
        if (await d.ShowAsync() != ContentDialogResult.Primary) return;
        var url = box.Text.Trim();
        if (url.Length == 0) return;
        if (!url.Contains("://")) url = "https://" + url;
        if (url.StartsWith("http://") || url.StartsWith("https://")) _state.OpenOnPhone(url);
    }
}
