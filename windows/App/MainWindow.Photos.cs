using MacNDroid.Core;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace MacNDroid;

/// <summary>The phone's photos and videos (as on the Mac): a grid, newest first, by album. Click to open in the
/// default app; select several to save them to Downloads. Nothing is copied until you open or save it.
/// Built once; tiles are added as pages arrive and thumbnails filled in as they come.</summary>
public sealed partial class MainWindow
{
    Grid? _photos;
    readonly GridView _photoGrid = new()
    {
        SelectionMode = ListViewSelectionMode.None, IsItemClickEnabled = true, Padding = new Thickness(24, 0, 24, 24),
    };
    readonly ContentControl _photosBody = new() { HorizontalContentAlignment = HorizontalAlignment.Stretch, VerticalContentAlignment = VerticalAlignment.Stretch };
    readonly TextBlock _photosCount = new() { VerticalAlignment = VerticalAlignment.Center };
    readonly ComboBox _photosKind = new() { MinWidth = 110 };
    readonly ComboBox _photosAlbum = new() { MinWidth = 170, PlaceholderText = "All albums" };
    readonly Button _photosSelect = new() { Content = "Select" };
    readonly Button _photosSave = new() { Content = "Save to Downloads", Visibility = Visibility.Collapsed };
    readonly InfoBar _photosPartial = new() { IsClosable = false, Severity = InfoBarSeverity.Informational, Margin = new Thickness(24, 0, 24, 8) };
    readonly Dictionary<string, (Image image, ProgressRing ring)> _tiles = new();
    readonly List<string> _tileIds = new();
    bool _photosSelecting, _fillingAlbums;
    ScrollViewer? _photoScroll;

    void BuildPhotosOnce()
    {
        if (_photos != null) return;
        var g = _state.Gallery;
        _photos = new Grid();
        _photos.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _photos.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _photos.RowDefinitions.Add(new RowDefinition());

        var bar = new Grid { Padding = new Thickness(36, 12, 24, 16), ColumnSpacing = 8 };
        bar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        bar.ColumnDefinitions.Add(new ColumnDefinition());
        bar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var titles = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12 };
        titles.Children.Add(new TextBlock { Text = "Photos", Style = Style("TitleTextBlockStyle") });
        _photosCount.Foreground = Brush("TextFillColorSecondaryBrush");
        titles.Children.Add(_photosCount);
        bar.Children.Add(titles);

        foreach (var k in new[] { "All", "Photos", "Videos" }) _photosKind.Items.Add(k);
        _photosKind.SelectedIndex = 0;
        _photosKind.SelectionChanged += (_, _) => ApplyPhotoFilter();
        _photosAlbum.SelectionChanged += (_, _) => { if (!_fillingAlbums) ApplyPhotoFilter(); };
        _photosSelect.Click += (_, _) => SetPhotoSelecting(!_photosSelecting);
        _photosSave.Style = Style("AccentButtonStyle");
        _photosSave.Click += (_, _) =>
        {
            var ids = _photoGrid.SelectedItems.OfType<FrameworkElement>().Select(t => t.Tag as string).ToHashSet();
            _state.Gallery.Save(_state.Gallery.Items.Where(i => ids.Contains(i.Id)), App.Current.Platform.DownloadsFolder);
            SetPhotoSelecting(false);
        };
        var refresh = IconButton("", "Refresh", () => _state.Gallery.Reload(), true);
        var tools = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        foreach (var e in new UIElement[] { _photosKind, _photosAlbum, _photosSave, _photosSelect, refresh }) tools.Children.Add(e);
        Grid.SetColumn(tools, 2);
        bar.Children.Add(tools);
        _photos.Children.Add(bar);

        _photosPartial.ActionButton = PlainButton("Allow on phone", () => _state.Gallery.AskForAccess());
        Grid.SetRow(_photosPartial, 1);
        _photos.Children.Add(_photosPartial);

        _photoGrid.ItemClick += (_, e) =>
        {
            if (_photosSelecting) return;
            if ((e.ClickedItem as FrameworkElement)?.Tag is string id && g.Items.FirstOrDefault(i => i.Id == id) is { } item) g.Open(item);
        };
        _photoGrid.SelectionChanged += (_, _) => UpdatePhotoCount();
        _photoGrid.Loaded += (_, _) =>
        {
            if (_photoScroll != null) return;
            _photoScroll = FindChild<ScrollViewer>(_photoGrid);
            if (_photoScroll != null)
                _photoScroll.ViewChanged += (_, _) =>
                {
                    if (g.HasMore && !g.Loading && _photoScroll.VerticalOffset > _photoScroll.ScrollableHeight - 800) g.LoadMore();
                };
        };
        Grid.SetRow(_photosBody, 2);
        _photos.Children.Add(_photosBody);

        g.Changed += () => { if (_current == "photos") UpdatePhotos(); };
        g.Appended += first => { if (_current == "photos") AddTiles(first); };
        g.ThumbArrived += id =>
        {
            if (_tiles.TryGetValue(id, out var t) && g.Thumbs.TryGetValue(id, out var bytes)) t.image.Source = Bitmap(bytes);
        };
    }

    void ShowPhotos()
    {
        BuildPhotosOnce();
        SetContent(_photos!);
        if (_state.Connected) _state.Gallery.LoadIfNeeded();
        UpdatePhotos();
    }

    void ApplyPhotoFilter()
    {
        var kind = _photosKind.SelectedIndex switch { 1 => "photos", 2 => "videos", _ => "all" };
        var album = _photosAlbum.SelectedIndex > 0 ? _state.Gallery.Albums.ElementAtOrDefault(_photosAlbum.SelectedIndex - 1)?.Id : null;
        _state.Gallery.SetFilter(kind, album);
    }

    void SetPhotoSelecting(bool on)
    {
        _photosSelecting = on;
        _photoGrid.SelectionMode = on ? ListViewSelectionMode.Multiple : ListViewSelectionMode.None;
        _photosSelect.Content = on ? "Done" : "Select";
        _photosSave.Visibility = on ? Visibility.Visible : Visibility.Collapsed;
        UpdatePhotoCount();
    }

    void UpdatePhotoCount()
    {
        var g = _state.Gallery;
        var selected = _photoGrid.SelectedItems.Count;
        _photosSave.IsEnabled = selected > 0;
        _photosCount.Text = _photosSelecting && selected > 0 ? $"{selected} selected"
            : g.Total > 0 ? $"{g.Total} item{(g.Total == 1 ? "" : "s")}" : "";
    }

    void UpdatePhotos()
    {
        var g = _state.Gallery;
        var st = _state;
        bool ready = st.Connected && g.Access is not ("none" or "off") && g.Items.Count > 0;
        _photosKind.IsEnabled = _photosAlbum.IsEnabled = _photosSelect.IsEnabled = ready || (st.Connected && g.Access is "full" or "photos" or "partial");

        // Albums (first page only); "All albums" first.
        var names = new[] { "All albums" }.Concat(g.Albums.Select(a => $"{a.Name} ({a.Count})")).ToList();
        if (!_photosAlbum.Items.OfType<string>().SequenceEqual(names))
        {
            _fillingAlbums = true;
            var albumIndex = g.Album == null ? 0 : g.Albums.FindIndex(a => a.Id == g.Album) + 1;
            _photosAlbum.Items.Clear();
            foreach (var n in names) _photosAlbum.Items.Add(n);
            _photosAlbum.SelectedIndex = Math.Max(albumIndex, 0);
            _fillingAlbums = false;
        }

        _photosPartial.IsOpen = st.Connected && g.Access is "partial" or "photos";
        _photosPartial.Message = g.Access == "photos"
            ? "Videos aren't shared yet. Allow videos on your phone to see them here."
            : "Showing only the photos you chose on your phone. To show all of them, allow all photos and videos.";

        UIElement? status = null;
        if (!st.Connected)
            status = Empty("", "Phone not connected", "Open Mac n Droid on your phone, on the same Wi-Fi.");
        else if (g.Access == "off")
            status = Empty("", "Photos are turned off on your phone", $"To see them here, turn on Photos on PC on the Home tab of Mac n Droid on {st.PhoneName}.");
        else if (g.Access == "none")
            status = Empty("", "See your phone's photos here",
                $"Allow Mac n Droid to use photos and videos on {st.PhoneName}. Photos are sent only to this PC, only when you open them.",
                AccentButton("Ask on phone", () => g.AskForAccess()));
        else if (g.Items.Count == 0)
            status = g.Loading || g.Access == "unknown"
                ? new ProgressRing { IsActive = true, Width = 40, Height = 40, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center }
                : Empty("", "No photos or videos", g.Album == null ? "" : "This album is empty.");

        if (status != null)
        {
            _photosBody.Content = status;
            ClearTiles();
        }
        else
        {
            if (_tileIds.Count > g.Items.Count || !_tileIds.SequenceEqual(g.Items.Take(_tileIds.Count).Select(i => i.Id))) ClearTiles();
            if (_photosBody.Content != _photoGrid) _photosBody.Content = _photoGrid;
            AddTiles(_tileIds.Count);
        }
        foreach (var (id, t) in _tiles) t.ring.IsActive = g.Opening.Contains(id);
        UpdatePhotoCount();
    }

    void ClearTiles()
    {
        _photoGrid.Items.Clear();
        _tiles.Clear();
        _tileIds.Clear();
    }

    void AddTiles(int from)
    {
        var g = _state.Gallery;
        if (_photosBody.Content != _photoGrid) { UpdatePhotos(); return; }
        for (int i = Math.Max(from, _tileIds.Count); i < g.Items.Count; i++)
        {
            var item = g.Items[i];
            _photoGrid.Items.Add(PhotoTile(item));
            _tileIds.Add(item.Id);
            g.WantThumb(item.Id);
        }
        UpdatePhotoCount();
    }

    FrameworkElement PhotoTile(GalleryItem item)
    {
        var image = new Image { Stretch = Stretch.UniformToFill };
        if (_state.Gallery.Thumbs.TryGetValue(item.Id, out var bytes)) image.Source = Bitmap(bytes);
        var ring = new ProgressRing { IsActive = false, Width = 28, Height = 28 };
        var tile = new Grid
        {
            Width = 132, Height = 132, Tag = item.Id, Background = Brush("ControlFillColorSecondaryBrush"),
            Children = { new FontIcon { Glyph = item.IsVideo ? "" : "", Foreground = Brush("TextFillColorTertiaryBrush") }, image, ring },
        };
        if (item.IsVideo)
            tile.Children.Add(new TextBlock
            {
                Text = Duration(item.Duration), Foreground = new SolidColorBrush(Microsoft.UI.Colors.White), FontSize = 12,
                FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, Margin = new Thickness(6),
                HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Bottom,
            });
        ToolTipService.SetToolTip(tile, $"{item.Name} · {item.Date:g}");
        var menu = new MenuFlyout();
        var open = new MenuFlyoutItem { Text = "Open" };
        open.Click += (_, _) => _state.Gallery.Open(item);
        var save = new MenuFlyoutItem { Text = "Save to Downloads" };
        save.Click += (_, _) => _state.Gallery.Save([item], App.Current.Platform.DownloadsFolder);
        menu.Items.Add(open);
        menu.Items.Add(save);
        tile.ContextFlyout = menu;
        _tiles[item.Id] = (image, ring);
        return tile;
    }

    static string Duration(double seconds)
    {
        var s = (int)Math.Round(seconds);
        return s >= 3600 ? $"{s / 3600}:{s / 60 % 60:00}:{s % 60:00}" : $"{s / 60}:{s % 60:00}";
    }

    static T? FindChild<T>(DependencyObject parent) where T : DependencyObject
    {
        for (int i = 0; i < VisualTreeHelper.GetChildrenCount(parent); i++)
        {
            var child = VisualTreeHelper.GetChild(parent, i);
            if (child is T t) return t;
            if (FindChild<T>(child) is { } found) return found;
        }
        return null;
    }
}
