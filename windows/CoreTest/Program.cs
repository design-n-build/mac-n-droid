// Runs the Windows app's core without any window, so it can be tested on any computer with tools/FakePhone.java:
//   MACNDROID_CONFIG=/tmp/x MACNDROID_PORT=47901 dotnet run --project windows/CoreTest -- 12
//   java -Didentity=/tmp/x/identity.json tools/FakePhone.java 127.0.0.1 47901 --full
using System.Collections.Concurrent;
using MacNDroid.Core;

var queue = new BlockingCollection<Action>();
var platform = new ConsolePlatform();
var state = new AppState(platform, queue.Add, "windows");
state.StatusChanged += () => Console.WriteLine($"status: phone={state.PhoneName} battery={state.Battery} connected={state.Connected}");
state.NotificationsChanged += () => Console.WriteLine($"notifications: {state.Notifications.Count}");
state.ClipsChanged += () => Console.WriteLine($"clips: {state.Clips.Count}");
state.TransfersChanged += () =>
{
    var t = state.Transfers.FirstOrDefault();
    if (t != null && t.State != "active") Console.WriteLine($"transfer: {t.Name} {t.State} {t.Bytes}");
};
state.Start();
Console.WriteLine($"port {state.Server.Port}");
Console.WriteLine($"qr {state.QrText[..80]}");
var seconds = args.Length > 0 ? int.Parse(args[0]) : 10;
var until = DateTime.Now.AddSeconds(seconds);
while (DateTime.Now < until)
{
    if (queue.TryTake(out var work, 200)) work();
}

sealed class ConsolePlatform : IPlatform
{
    public string DownloadsFolder { get; } = Path.Combine(Identity.Folder, "Downloads");
    public void SetClipboard(string? text, byte[]? png) => Console.WriteLine($"set clipboard: {text ?? $"{png?.Length} bytes PNG"}");
    public void ShowNotification(string title, string body, string? key) => Console.WriteLine($"notify: {title} — {body}");
    public void WithdrawNotification(string key) => Console.WriteLine($"withdraw: {key}");
    public void OpenUrl(string url) => Console.WriteLine($"open: {url}");
}
