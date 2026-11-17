using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.Windows.AppLifecycle;

namespace MacNDroid;

public static class Program
{
    /// <summary>Where a crash is written down: %APPDATA%\Mac n Droid\crash.log (the newest at the end).</summary>
    public static string CrashLog => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Mac n Droid", "crash.log");

    public static void WriteCrash(Exception e)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(CrashLog)!);
            File.AppendAllText(CrashLog, $"{DateTime.Now:O}  Mac n Droid {typeof(Program).Assembly.GetName().Version}\n{e}\n\n");
        }
        catch { }
    }

    [STAThread]
    static int Main(string[] args)
    {
        AppDomain.CurrentDomain.UnhandledException += (_, e) => { if (e.ExceptionObject is Exception x) WriteCrash(x); };
        WinRT.ComWrappersSupport.InitializeComWrappers();
        // One copy at a time: opening the app again brings the running one forward.
        var main = AppInstance.FindOrRegisterForKey("main");
        if (!main.IsCurrent)
        {
            var activation = AppInstance.GetCurrent().GetActivatedEventArgs();
            var done = new ManualResetEvent(false);
            Task.Run(async () => { await main.RedirectActivationToAsync(activation); done.Set(); });
            done.WaitOne(TimeSpan.FromSeconds(5));
            return 0;
        }
        Application.Start(p =>
        {
            SynchronizationContext.SetSynchronizationContext(
                new DispatcherQueueSynchronizationContext(DispatcherQueue.GetForCurrentThread()));
            _ = new App(args.Contains("--background"));
        });
        return 0;
    }
}
