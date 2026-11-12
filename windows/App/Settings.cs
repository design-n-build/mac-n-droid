using System.Text.Json;
using MacNDroid.Core;
using Microsoft.Win32;

namespace MacNDroid;

/// <summary>Preferences, saved next to identity.json in %AppData%\Mac n Droid.</summary>
public sealed class Settings
{
    public bool MirrorNotifications { get; set; } = true;
    public bool SyncClipboard { get; set; } = true;
    public bool Sounds { get; set; } = true;
    public bool LeftBehind { get; set; } = true;
    public string LastPhoneName { get; set; } = "";

    static string FilePath => Path.Combine(Identity.Folder, "settings.json");

    public static Settings Load()
    {
        try { return JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath)) ?? new(); }
        catch (Exception e) when (e is IOException or JsonException or UnauthorizedAccessException) { return new(); }
    }

    public void Save()
    {
        try { File.WriteAllText(FilePath, JsonSerializer.Serialize(this)); }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { }
    }

    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

    /// <summary>Starts with Windows (Settings → Apps → Startup lists it too).</summary>
    public static bool OpenAtLogin
    {
        get => Registry.CurrentUser.OpenSubKey(RunKey)?.GetValue("Mac n Droid") != null;
        set
        {
            using var key = Registry.CurrentUser.CreateSubKey(RunKey);
            if (value) key.SetValue("Mac n Droid", $"\"{Environment.ProcessPath}\" --background");
            else key.DeleteValue("Mac n Droid", false);
        }
    }
}
