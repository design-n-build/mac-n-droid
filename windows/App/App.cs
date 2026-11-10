using H.NotifyIcon;
using MacNDroid.Core;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.Windows.AppLifecycle;
using Microsoft.Windows.AppNotifications;

namespace MacNDroid;

/// <summary>Keeps running in the notification area (system tray) after the window is closed, like the Mac app
/// in the menu bar, so notifications and files keep arriving.</summary>
public sealed class App : Application
{
    public static new App Current { get; private set; } = null!;
    readonly bool _startHidden;
    DispatcherQueue _dispatcher = null!;
    TaskbarIcon? _tray;
    MainWindow? _window;
    readonly Discovery _discovery = new();

    public Settings Settings { get; } = Settings.Load();
    public WindowsPlatform Platform { get; } = new();
    public AppState State { get; private set; } = null!;

    public App(bool startHidden)
    {
        Current = this;
        _startHidden = startHidden;
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        Resources.MergedDictionaries.Add(new XamlControlsResources());
        _dispatcher = DispatcherQueue.GetForCurrentThread();

        State = new AppState(Platform, work => _dispatcher.TryEnqueue(() => work()), "windows")
        {
            LastPhoneName = Settings.LastPhoneName,
            MirrorNotifications = Settings.MirrorNotifications,
        };
        Platform.State = State;
        State.StatusChanged += () =>
        {
            UpdateTray();
            if (State.Phone != null && State.PhoneName != Settings.LastPhoneName)
            {
                Settings.LastPhoneName = State.PhoneName;
                Settings.Save();
            }
        };
        State.Start();
        _discovery.Start(State.Name, State.Server.Port, State.Identity.PcId);

        AppNotificationManager.Default.NotificationInvoked += (_, e) => _dispatcher.TryEnqueue(() => OnNotification(e));
        AppNotificationManager.Default.Register();
        Windows.ApplicationModel.DataTransfer.Clipboard.ContentChanged += (_, _) =>
            _dispatcher.TryEnqueue(() => Platform.ClipboardChanged(Settings));
        AppInstance.GetCurrent().Activated += (_, _) => _dispatcher.TryEnqueue(() => ShowWindow());

        CreateTray();
        _window = new MainWindow(State);
        if (!_startHidden) ShowWindow();
    }

    public void ShowWindow(string? page = null)
    {
        _window ??= new MainWindow(State);
        _window.Activate();
        _window.AppWindow.Show();
        if (page != null) _window.Navigate(page);
    }

    public void Quit()
    {
        _discovery.Dispose();
        State.Server.Stop();
        _tray?.Dispose();
        AppNotificationManager.Default.Unregister();
        _window?.CloseForReal();
        Exit();
    }

    void OnNotification(AppNotificationActivatedEventArgs e)
    {
        string? Arg(IDictionary<string, string> d, string k) => d.TryGetValue(k, out var v) ? v : null;
        var a = e.Arguments;
        var key = Arg(a, "key");
        _ = int.TryParse(Arg(a, "i"), out var i);
        switch (Arg(a, "action"))
        {
            case "reply" when key != null:
                var text = Arg(e.UserInput, "reply")?.Trim();
                if (!string.IsNullOrEmpty(text)) State.Action(key, i, text);
                break;
            case "notif_action" when key != null:
                State.Action(key, i);
                break;
            default:
                ShowWindow(Arg(a, "page") ?? "overview");
                break;
        }
    }

    // MARK: Notification area icon

    MenuFlyoutItem? _statusItem, _ringItem;

    void CreateTray()
    {
        var menu = new MenuFlyout();
        _statusItem = new MenuFlyoutItem { IsEnabled = false };
        _ringItem = Item("Ring phone", "", () => State.Ring());
        menu.Items.Add(_statusItem);
        menu.Items.Add(new MenuFlyoutSeparator());
        menu.Items.Add(_ringItem);
        menu.Items.Add(Item("Send files…", "", () => { ShowWindow("files"); _window?.ChooseFiles(); }));
        menu.Items.Add(Item("Send clipboard", "", async () =>
        {
            var (text, png) = await WindowsPlatform.ReadClipboardAsync();
            State.SendClipboardNow(text, png);
        }));
        menu.Items.Add(new MenuFlyoutSeparator());
        menu.Items.Add(Item("Open Mac n Droid", "", () => ShowWindow()));
        menu.Items.Add(Item("Settings", "", () => ShowWindow("settings")));
        menu.Items.Add(Item("Quit Mac n Droid", null, Quit));

        _tray = new TaskbarIcon
        {
            ToolTipText = "Mac n Droid",
            IconSource = new BitmapImage(new Uri(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico"))),
            ContextFlyout = menu,
            ContextMenuMode = ContextMenuMode.PopupMenu,
            NoLeftClickDelay = true,
            LeftClickCommand = new Command(() => ShowWindow()),
        };
        _tray.ForceCreate();
        UpdateTray();
    }

    static MenuFlyoutItem Item(string text, string? glyph, Action action)
    {
        var item = new MenuFlyoutItem { Text = text };
        if (glyph != null) item.Icon = new FontIcon { Glyph = glyph };
        item.Click += (_, _) => action();
        return item;
    }

    void UpdateTray()
    {
        if (_statusItem == null || _tray == null) return;
        var battery = State.Battery is { } b ? $" · {b}%" : "";
        _statusItem.Text = State.Connected ? $"{State.PhoneName} · Connected{battery}" : $"Waiting for {State.PhoneName}";
        _tray.ToolTipText = State.Connected ? $"Mac n Droid · {State.PhoneName}{battery}" : "Mac n Droid · Not connected";
        if (_ringItem != null)
        {
            _ringItem.Text = State.Ringing ? "Stop ringing" : "Ring phone";
            _ringItem.IsEnabled = State.Connected;
        }
    }
}

sealed class Command(Action action) : System.Windows.Input.ICommand
{
    public event EventHandler? CanExecuteChanged { add { } remove { } }
    public bool CanExecute(object? parameter) => true;
    public void Execute(object? parameter) => action();
}
