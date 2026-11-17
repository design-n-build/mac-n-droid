using System.Runtime.InteropServices.WindowsRuntime;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;

namespace MacNDroid;

/// <summary>For CI only (MACNDROID_SHOTS=folder): waits for the phone (tools/fake_phone.py), opens every page at a
/// normal and a wide window size, saves each as a PNG, then quits. The pictures are the build's "screenshots"
/// artifact, so layout can be checked without a Windows PC.</summary>
static class Screenshots
{
    static readonly string[] Pages = ["overview", "notifications", "messages", "screen", "clipboard", "files", "pair", "extension", "settings"];
    static readonly (string name, int w, int h)[] Sizes = [("normal", 1100, 760), ("wide", 1600, 900)];

    public static async void Run(App app, MainWindow window, string folder)
    {
        try
        {
            Directory.CreateDirectory(folder);
            for (var i = 0; i < 60 && !app.State.Connected; i++) await Task.Delay(500);
            await Task.Delay(3000);   // let the phone's notifications, music and clipboard arrive
            foreach (var (name, w, h) in Sizes)
            {
                window.AppWindow.Resize(new Windows.Graphics.SizeInt32(w, h));
                var n = 1;
                foreach (var page in Pages)
                {
                    window.Navigate(page);
                    await Task.Delay(1200);
                    await Save((UIElement)window.Content, Path.Combine(folder, $"{name}-{n++:00}-{page}.png"));
                }
            }
        }
        catch (Exception e) { Program.WriteCrash(e); }
        app.Quit();
    }

    static async Task Save(UIElement element, string path)
    {
        var bitmap = new RenderTargetBitmap();
        await bitmap.RenderAsync(element);
        var pixels = (await bitmap.GetPixelsAsync()).ToArray();
        using var stream = new InMemoryRandomAccessStream();
        var encoder = await BitmapEncoder.CreateAsync(BitmapEncoder.PngEncoderId, stream);
        encoder.SetPixelData(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied,
            (uint)bitmap.PixelWidth, (uint)bitmap.PixelHeight, 96, 96, pixels);
        await encoder.FlushAsync();
        stream.Seek(0);
        using var file = File.Create(path);
        await stream.AsStreamForRead().CopyToAsync(file);
    }
}
