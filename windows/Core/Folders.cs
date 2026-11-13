using System.IO.Compression;

namespace MacNDroid.Core;

/// <summary>Folders travel as one zip of the folder, with the folder itself at the top (PROTOCOL.md, "folder: true").</summary>
public static class Folders
{
    static readonly HashSet<string> Skip = new(StringComparer.OrdinalIgnoreCase) { ".DS_Store", "__MACOSX", "Thumbs.db", "desktop.ini" };

    /// <summary>Zips <paramref name="folder"/> into a temporary file and returns its path.</summary>
    public static string Zip(string folder)
    {
        folder = Path.GetFullPath(folder).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        var name = Path.GetFileName(folder);
        var dir = Path.Combine(Path.GetTempPath(), "macndroid-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        var zipPath = Path.Combine(dir, name + ".zip");
        using var zip = ZipFile.Open(zipPath, ZipArchiveMode.Create);
        zip.CreateEntry(name + "/");
        Add(zip, folder, name);
        return zipPath;
    }

    static void Add(ZipArchive zip, string dir, string prefix)
    {
        foreach (var d in Directory.GetDirectories(dir).OrderBy(x => x, StringComparer.Ordinal))
        {
            var n = Path.GetFileName(d);
            if (Skip.Contains(n) || new DirectoryInfo(d).Attributes.HasFlag(FileAttributes.ReparsePoint)) continue;
            zip.CreateEntry($"{prefix}/{n}/");
            Add(zip, d, $"{prefix}/{n}");
        }
        foreach (var f in Directory.GetFiles(dir).OrderBy(x => x, StringComparer.Ordinal))
        {
            var n = Path.GetFileName(f);
            if (Skip.Contains(n)) continue;
            try { zip.CreateEntryFromFile(f, $"{prefix}/{n}", CompressionLevel.Fastest); }
            catch (IOException) { }            // in use: skip it rather than fail the folder
            catch (UnauthorizedAccessException) { }
        }
    }

    /// <summary>Path parts of a zip entry, without anything that could point outside the target folder.</summary>
    static string[]? SafeParts(string entry)
    {
        var parts = entry.Replace('\\', '/').Split('/').Where(p => p is not ("" or "." or "..")).ToArray();
        if (parts.Length == 0 || parts.Any(Skip.Contains)) return null;
        var bad = Path.GetInvalidFileNameChars();
        return parts.Select(p => new string(p.Select(c => bad.Contains(c) ? '-' : c).ToArray())).ToArray();
    }

    /// <summary>
    /// Marks received files as downloaded from the internet ("Mark of the Web"), so Windows SmartScreen and
    /// Office check them before they run or open. A folder marks every file inside it.
    /// </summary>
    public static void MarkOfTheWeb(string path)
    {
        if (!OperatingSystem.IsWindows()) return;
        var files = Directory.Exists(path) ? Directory.EnumerateFiles(path, "*", SearchOption.AllDirectories) : new[] { path };
        foreach (var f in files)
        {
            try { File.WriteAllText(f + ":Zone.Identifier", "[ZoneTransfer]\r\nZoneId=3\r\nHostUrl=about:internet\r\n"); }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException or NotSupportedException) { }
        }
    }

    /// <summary>Unpacks a received folder zip into <paramref name="downloads"/> and returns the folder, or null.</summary>
    public static string? Unpack(string zipPath, string downloads, string name)
    {
        var work = Path.Combine(downloads, ".macndroid-" + Guid.NewGuid().ToString("N"));
        try
        {
            Directory.CreateDirectory(work);
            var root = Path.GetFullPath(work) + Path.DirectorySeparatorChar;
            using (var zip = ZipFile.OpenRead(zipPath))
            {
                foreach (var e in zip.Entries)
                {
                    if (SafeParts(e.FullName) is not { } parts) continue;
                    var target = Path.GetFullPath(Path.Combine(work, Path.Combine(parts)));
                    if (!target.StartsWith(root, StringComparison.OrdinalIgnoreCase)) continue;
                    if (e.FullName.EndsWith('/') || e.FullName.EndsWith('\\')) { Directory.CreateDirectory(target); continue; }
                    Directory.CreateDirectory(Path.GetDirectoryName(target)!);
                    e.ExtractToFile(target, overwrite: true);
                }
            }
            var items = Directory.GetFileSystemEntries(work);
            // The name comes from the other device: keep only a plain folder name, so it can't point elsewhere.
            var baseName = Path.GetFileName(name.Replace('\\', '/').TrimEnd('/'));
            if (baseName.EndsWith(".zip", StringComparison.OrdinalIgnoreCase)) baseName = baseName[..^4];
            foreach (var c in Path.GetInvalidFileNameChars()) baseName = baseName.Replace(c, '-');
            baseName = baseName.Trim().TrimStart('.');
            if (baseName.Length == 0) baseName = "Folder";
            string src = work;
            if (items.Length == 1 && Directory.Exists(items[0])) { src = items[0]; baseName = Path.GetFileName(src); }
            var dest = Path.Combine(downloads, baseName);
            for (int i = 2; Directory.Exists(dest) || File.Exists(dest); i++) dest = Path.Combine(downloads, $"{baseName} {i}");
            Directory.Move(src, dest);
            if (Directory.Exists(work)) Directory.Delete(work, true);
            return dest;
        }
        catch (Exception e) when (e is IOException or InvalidDataException or UnauthorizedAccessException)
        {
            try { if (Directory.Exists(work)) Directory.Delete(work, true); } catch (IOException) { }
            return null;
        }
        finally
        {
            try { File.Delete(zipPath); } catch (IOException) { }
        }
    }
}
