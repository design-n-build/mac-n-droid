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
/// like Windows 11's own apps. Same pages as the Mac app: Overview, Notifications, Clipboard, Files, Pair a phone.</summary>
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
            SendFiles(items.OfType<StorageFile>().Select(f => f.Path));
        };
        Content = root;

        foreach (var change in new Action<Action>[] {
                     h => state.StatusChanged += h, h => state.NotificationsChanged += h, h => state.ClipsChanged += h,
                     h => state.TransfersChanged += h, h => state.PairingChanged += h })
            change(Refresh);
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
        var actions = new Grid { ColumnSpacing = 8 };
        var quick = new (string text, string glyph, Action click)[]
        {
            (_state.Ringing ? "Stop ringing" : "Ring phone", _state.Ringing ? "" : "", _state.Ring),
            ("Send files", "", ChooseFiles),
            ("Send clipboard", "", SendClipboard),
            ("Open link on phone", "", OpenLinkDialog),
        };
        for (int i = 0; i < quick.Length; i++)
        {
            actions.ColumnDefinitions.Add(new ColumnDefinition());
            var (text, glyph, click) = quick[i];
            var content = new StackPanel { Spacing = 8, Padding = new Thickness(0, 12, 0, 12) };
            content.Children.Add(new FontIcon { Glyph = glyph, FontSize = 20 });
            content.Children.Add(new TextBlock { Text = text, HorizontalAlignment = HorizontalAlignment.Center, TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center });
            var b = new Button { Content = content, HorizontalAlignment = HorizontalAlignment.Stretch, HorizontalContentAlignment = HorizontalAlignment.Center };
            b.Click += (_, _) => click();
            Grid.SetColumn(b, i);
            actions.Children.Add(b);
        }
        body.Children.Add(actions);

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
            SetPage(Empty("", "Send files", "Drop files on this window to send them to your phone. Files from your phone are saved in Downloads.",
                AccentButton("Send files…", ChooseFiles)), scroll: false);
            return;
        }
        var page = PagePanel("Files", out var body);
        var send = AccentButton("Send files…", ChooseFiles);
        send.Margin = new Thickness(0, 0, 0, 8);
        body.Children.Add(send);
        foreach (var t in _state.Transfers.Take(50))
        {
            var direction = t.ToPhone ? "To your phone" : "From your phone";
            var detail = t.State switch
            {
                "active" => $"{direction} · {Size(t.Bytes)} of {Size(t.Size)}",
                "failed" => $"{direction} · Didn't finish",
                _ => $"{direction} · {Size(t.Size)}",
            };
            var card = new SettingsCard { Header = t.Name, Description = detail, HeaderIcon = Glyph(t.ToPhone ? "" : "") };
            if (t.State == "active" && t.Size > 0)
                card.Content = new ProgressBar { Value = 100.0 * t.Bytes / t.Size, Width = 160 };
            else if (t.State == "done" && !t.ToPhone)
            {
                var path = t.Path;
                card.Content = PlainButton("Show in folder", () => System.Diagnostics.Process.Start("explorer.exe", $"/select,\"{path}\""));
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
        body.Children.Add(SectionHeader("Clipboard"));
        body.Children.Add(Toggle("Sync clipboard automatically",
            "Text and images you copy on this PC are sent to your phone, and the last 20 copies from both are kept in Clipboard history.",
            app.Settings.SyncClipboard, on => { app.Settings.SyncClipboard = on; app.Settings.Save(); }));
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
