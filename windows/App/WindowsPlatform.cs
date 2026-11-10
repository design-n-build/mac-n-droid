using System.Security.Cryptography;
using System.Text;
using MacNDroid.Core;
using Microsoft.Windows.AppNotifications;
using Microsoft.Windows.AppNotifications.Builder;
using Windows.ApplicationModel.DataTransfer;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;

namespace MacNDroid;

/// <summary>Clipboard, Windows notifications (with Reply boxes) and opening links.</summary>
public sealed class WindowsPlatform : IPlatform
{
    public AppState? State { get; set; }
    string? _lastText;
    byte[]? _lastPng;

    public string DownloadsFolder => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");

    // MARK: Clipboard

    public void SetClipboard(string? text, byte[]? png)
    {
        var package = new DataPackage();
        if (text != null)
        {
            _lastText = text;
            package.SetText(text);
        }
        else if (png != null)
        {
            _lastPng = png;
            var stream = new InMemoryRandomAccessStream();
            using (var writer = new DataWriter(stream.GetOutputStreamAt(0)))
            {
                writer.WriteBytes(png);
                writer.StoreAsync().AsTask().Wait();
                writer.DetachStream();
            }
            package.SetBitmap(RandomAccessStreamReference.CreateFromStream(stream));
        }
        try
        {
            Clipboard.SetContent(package);
            Clipboard.Flush();  // Stays on the clipboard after Mac n Droid quits.
        }
        catch (Exception) { /* Another app has the clipboard open; skip this one. */ }
    }

    /// <summary>Reads the clipboard: text, or an image as PNG bytes.</summary>
    public static async Task<(string? text, byte[]? png)> ReadClipboardAsync()
    {
        try
        {
            var content = Clipboard.GetContent();
            if (content.Contains(StandardDataFormats.Text)) return (await content.GetTextAsync(), null);
            if (content.Contains(StandardDataFormats.Bitmap))
            {
                var reference = await content.GetBitmapAsync();
                using var source = await reference.OpenReadAsync();
                var decoder = await BitmapDecoder.CreateAsync(source);
                var bitmap = await decoder.GetSoftwareBitmapAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied);
                using var output = new InMemoryRandomAccessStream();
                var encoder = await BitmapEncoder.CreateAsync(BitmapEncoder.PngEncoderId, output);
                encoder.SetSoftwareBitmap(bitmap);
                await encoder.FlushAsync();
                var bytes = new byte[output.Size];
                using var reader = new DataReader(output.GetInputStreamAt(0));
                await reader.LoadAsync((uint)output.Size);
                reader.ReadBytes(bytes);
                return (null, bytes);
            }
        }
        catch (Exception) { /* Clipboard busy or in a format we don't send. */ }
        return (null, null);
    }

    /// <summary>Called when the clipboard changes; our own changes (from the phone) aren't sent back.</summary>
    public async void ClipboardChanged(Settings settings)
    {
        if (State == null || !settings.SyncClipboard) return;
        var (text, png) = await ReadClipboardAsync();
        if (text != null && text == _lastText) return;
        if (png != null && _lastPng != null && png.AsSpan().SequenceEqual(_lastPng)) return;
        State.LocalCopy(text, png);
    }

    // MARK: Notifications

    static string Tag(string key) => Convert.ToHexString(SHA1.HashData(Encoding.UTF8.GetBytes(key)))[..32];

    public void ShowNotification(string title, string body, string? key)
    {
        var builder = new AppNotificationBuilder().AddText(title).AddText(body);
        if (key != null && State?.Notification(key) is { } n)
        {
            builder.AddArgument("action", "show").AddArgument("page", "notifications");
            if (State.Icons.TryGetValue(n.Pkg, out var png))
            {
                var dir = Path.Combine(Path.GetTempPath(), "Mac n Droid", "icons");
                Directory.CreateDirectory(dir);
                var file = Path.Combine(dir, Tag(n.Pkg) + ".png");
                if (!File.Exists(file)) File.WriteAllBytes(file, png);
                builder.SetAppLogoOverride(new Uri(file), AppNotificationImageCrop.Circle);
            }
            var reply = n.Actions.FirstOrDefault(a => a.IsReply && !a.OpensOnPhone);
            if (reply != null)
            {
                builder.AddTextBox("reply", "Type a reply", "");
                builder.AddButton(new AppNotificationButton("Reply")
                    .AddArgument("action", "reply").AddArgument("key", key).AddArgument("i", reply.Index.ToString())
                    .SetInputId("reply"));
            }
            foreach (var a in n.Actions.Where(a => !a.IsReply).Take(reply != null ? 2 : 3))
                builder.AddButton(new AppNotificationButton(a.Title)
                    .AddArgument("action", "notif_action").AddArgument("key", key).AddArgument("i", a.Index.ToString()));
            if (n.IsCall) builder.SetScenario(AppNotificationScenario.IncomingCall);
            builder.SetTag(Tag(key)).SetGroup("phone");
        }
        else
        {
            builder.AddArgument("action", "show").AddArgument("page", title.StartsWith("Received") ? "files" : "overview");
        }
        try { AppNotificationManager.Default.Show(builder.BuildNotification()); }
        catch (Exception) { /* Notifications are off for Mac n Droid in Windows Settings. */ }
    }

    public async void WithdrawNotification(string key)
    {
        try { await AppNotificationManager.Default.RemoveByTagAndGroupAsync(Tag(key), "phone"); }
        catch (Exception) { }
    }

    public void OpenUrl(string url) => _ = Windows.System.Launcher.LaunchUriAsync(new Uri(url));
}
