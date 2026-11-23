using System.Diagnostics;
using Microsoft.Win32;

namespace MacNDroid;

/// <summary>Which browser to add the extension to, and its store. Chrome-based browsers (Chrome, Brave, Opera,
/// Vivaldi…) share the Chrome Web Store, Edge has its own, and Firefox-based ones share Firefox Add-ons. Worked out
/// from what the browser is built on, so new browsers work without a list.</summary>
public sealed record Browser(string Exe, string Name, string Store)
{
    public const string ChromeWebStore = "https://chromewebstore.google.com/detail/mac-n-droid/ddiakkecgnhdkmfcagclppckfbchmbga";
    public const string EdgeAddOns = "https://microsoftedge.microsoft.com/addons/detail/apnollpkjjokneagmeopfjpmoaodpkab";
    public const string FirefoxAddOns = "https://addons.mozilla.org/firefox/addon/mac-n-droid/";

    static readonly Dictionary<string, string> ShortNames = new(StringComparer.OrdinalIgnoreCase)
    {
        ["chrome.exe"] = "Chrome", ["msedge.exe"] = "Edge", ["brave.exe"] = "Brave", ["firefox.exe"] = "Firefox",
        ["opera.exe"] = "Opera", ["vivaldi.exe"] = "Vivaldi", ["zen.exe"] = "Zen", ["librewolf.exe"] = "LibreWolf",
    };

    /// <summary>The default browser if the extension works in it, else Edge (every Windows PC has it).</summary>
    public static Browser Preferred => Default ?? Edge ?? new Browser("", "Edge", EdgeAddOns);

    /// <summary>The default browser, when the extension works in it.</summary>
    public static Browser? Default
    {
        get
        {
            try
            {
                using var choice = Registry.CurrentUser.OpenSubKey(
                    @"Software\Microsoft\Windows\Shell\Associations\UrlAssociations\https\UserChoice");
                if (choice?.GetValue("ProgId") is not string progId) return null;
                using var cmd = Registry.ClassesRoot.OpenSubKey($@"{progId}\shell\open\command");
                return cmd?.GetValue(null) is string command ? At(ExeFrom(command)) : null;
            }
            catch (Exception e) when (e is System.Security.SecurityException or UnauthorizedAccessException or IOException) { return null; }
        }
    }

    static Browser? Edge
    {
        get
        {
            using var key = Registry.LocalMachine.OpenSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\msedge.exe");
            return key?.GetValue(null) is string exe ? At(exe) : null;
        }
    }

    static string ExeFrom(string command) =>
        command.StartsWith('"') ? command[1..Math.Max(1, command.IndexOf('"', 1))] : command.Split(' ')[0];

    /// <summary>Firefox-based browsers ship xul.dll; Chrome-based ones a *_elf.dll (chrome_elf, msedge_elf…) next to
    /// the browser or in its version folder.</summary>
    static Browser? At(string exe)
    {
        if (!File.Exists(exe)) return null;
        var dir = Path.GetDirectoryName(exe)!;
        var file = Path.GetFileName(exe);
        string store;
        if (File.Exists(Path.Combine(dir, "xul.dll"))) store = FirefoxAddOns;
        else if (HasElf(dir))
            store = file.Equals("msedge.exe", StringComparison.OrdinalIgnoreCase) ? EdgeAddOns : ChromeWebStore;
        else return null;
        var product = FileVersionInfo.GetVersionInfo(exe).ProductName?.Replace(" Browser", "").Trim();
        var name = ShortNames.GetValueOrDefault(file) ?? (string.IsNullOrEmpty(product) ? "your browser" : product);
        return new Browser(exe, name, store);
    }

    static bool HasElf(string dir)
    {
        try
        {
            return Directory.EnumerateFiles(dir, "*_elf.dll").Any()
                || Directory.EnumerateDirectories(dir).Any(d => Directory.EnumerateFiles(d, "*_elf.dll").Any());
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { return false; }
    }

    /// <summary>Opens the extension's page in this browser's store, in this browser.</summary>
    public void Open()
    {
        try
        {
            if (Exe.Length > 0) Process.Start(new ProcessStartInfo(Exe, $"\"{Store}\"") { UseShellExecute = false });
            else Process.Start(new ProcessStartInfo(Store) { UseShellExecute = true });
        }
        catch (Exception e) when (e is System.ComponentModel.Win32Exception or InvalidOperationException) { }
    }
}
