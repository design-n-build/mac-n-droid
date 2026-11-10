using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.Windows.AppLifecycle;

namespace MacNDroid;

public static class Program
{
    [STAThread]
    static int Main(string[] args)
    {
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
