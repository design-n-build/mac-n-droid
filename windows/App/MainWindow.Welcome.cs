using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using Windows.ApplicationModel.DataTransfer;
using Windows.Foundation;
using Windows.UI;

namespace MacNDroid;

// First launch, as on the Mac (FirstRun.swift): Welcome (phone and PC linked by electricity, features circling them)
// → Pair your phone (rings pulse around the QR code while it waits) → You're connected (the two come together, a ✓,
// and confetti). Everything is drawn from the time since the step opened, so it's still when Windows' animation
// effects are off, and the CI screenshots can show the end state.
public sealed partial class MainWindow
{
    readonly Grid _welcome = new() { Visibility = Visibility.Collapsed };
    readonly Canvas _confettiLayer = new() { IsHitTestVisible = false };
    readonly bool _motion = new Windows.UI.ViewManagement.UISettings().AnimationsEnabled;
    int _welcomeStep = -1;   // -1 hidden, 0 welcome, 1 pair, 2 connected
    DateTime _stepStart;
    bool _welcomePreview, _wasConnected, _frameHooked, _confettiPlayed;
    Action<double>? _animate;

    /// <summary>CI screenshots: show every step's end state.</summary>
    public static bool InstantMotion { get; set; }

    static readonly Color Green = Color.FromArgb(255, 13, 163, 87);

    static readonly (string glyph, string label)[] Features =
    [
        ("", "Notifications"), ("", "Messages"), ("", "Verification codes"), ("", "Clipboard"),
        ("", "Files"), ("", "Photos"), ("", "Phone screen"), ("", "Music"),
    ];

    void SetUpWelcome(Grid root)
    {
        Grid.SetRow(_welcome, 1);
        root.Children.Add(_welcome);
        Grid.SetRowSpan(_confettiLayer, 2);
        root.Children.Add(_confettiLayer);
        _wasConnected = _state.Connected;
        _state.StatusChanged += () =>
        {
            if (_welcomeStep == 1 && _state.Connected && !_wasConnected) ShowWelcome(2);
            _wasConnected = _state.Connected;
        };
        _state.PairingChanged += () => { if (_welcomeStep == 1) _qrImage.Source = Qr(_state.QrText); };
    }

    /// <summary>Opens a step of the first-run screens over the window. <paramref name="preview"/>: shown again for
    /// testing (--welcome) on a PC that's already paired, so clicking the code pretends a phone scanned it.</summary>
    public void ShowWelcome(int step, bool? preview = null)
    {
        if (preview is { } p) _welcomePreview = p;
        _welcomeStep = step;
        _stepStart = DateTime.UtcNow;
        _welcome.Children.Clear();
        _welcome.Children.Add(step switch { 0 => WelcomeStep(), 1 => PairStep(), _ => ConnectedStep() });
        _welcome.Visibility = Visibility.Visible;
        _nav.Visibility = Visibility.Collapsed;
        _confettiPlayed = false;
        if (!_frameHooked) { CompositionTarget.Rendering += OnFrame; _frameHooked = true; }
        OnFrame(null, null);
    }

    void CloseWelcome(string page)
    {
        _welcomeStep = -1;
        _animate = null;
        _welcome.Children.Clear();
        _welcome.Visibility = Visibility.Collapsed;
        _nav.Visibility = Visibility.Visible;
        Navigate(page);
    }

    void OnFrame(object? sender, object? e)
    {
        var t = InstantMotion || !_motion ? 30 : (DateTime.UtcNow - _stepStart).TotalSeconds;
        _animate?.Invoke(t);
        var confettiLeft = UpdateConfetti();
        if (_welcomeStep < 0 && !confettiLeft && _frameHooked) { CompositionTarget.Rendering -= OnFrame; _frameHooked = false; }
    }

    // MARK: Building blocks

    static Grid Centered(UIElement content) => new()
    {
        Children = { new ScrollViewer { Content = new Border { Child = content, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Padding = new Thickness(24, 24, 24, 40) }, VerticalScrollBarVisibility = ScrollBarVisibility.Auto } },
    };

    static TextBlock Heading(string text) => new()
    {
        Text = text, Style = Style("TitleTextBlockStyle"), HorizontalAlignment = HorizontalAlignment.Center, TextAlignment = TextAlignment.Center,
    };

    static TextBlock Line(string text, double size = 16, bool tertiary = false) => new()
    {
        Text = text, FontSize = size, TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center,
        Foreground = Brush(tertiary ? "TextFillColorTertiaryBrush" : "TextFillColorSecondaryBrush"),
    };

    static Brush? DeviceBrush => Brush("AccentTextFillColorPrimaryBrush");

    static double Clamp01(double x) => Math.Clamp(x, 0, 1);
    static double EaseOut(double x) => 1 - Math.Pow(1 - Clamp01(x), 3);
    static double EaseInOut(double x) { x = Clamp01(x); return x < 0.5 ? 4 * x * x * x : 1 - Math.Pow(-2 * x + 2, 3) / 2; }
    /// <summary>Overshoots a little and settles, like a spring.</summary>
    static double Spring(double x) { x = Clamp01(x); const double c1 = 1.70158, c3 = c1 + 1; return 1 + c3 * Math.Pow(x - 1, 3) + c1 * Math.Pow(x - 1, 2); }

    // MARK: 1. Welcome

    UIElement WelcomeStep()
    {
        var hero = new Canvas { Height = 300, Width = 720 };
        var chips = Features.Select(f =>
        {
            var chip = new Border
            {
                Width = 44, Height = 44, CornerRadius = new CornerRadius(22),
                Background = Brush("CardBackgroundFillColorDefaultBrush"), BorderBrush = Brush("CardStrokeColorDefaultBrush"), BorderThickness = new Thickness(1),
                Child = new FontIcon { Glyph = f.glyph, FontSize = 17, Foreground = Brush("TextFillColorSecondaryBrush") },
                RenderTransform = new ScaleTransform { CenterX = 22, CenterY = 22 },
            };
            ToolTipService.SetToolTip(chip, f.label);
            hero.Children.Add(chip);
            return chip;
        }).ToArray();

        // Phone — electricity — laptop, in the middle.
        var link = new Canvas { Width = 168, Height = 70 };
        var strands = new Polyline[4];
        for (var s = 0; s < strands.Length; s++)
        {
            strands[s] = new Polyline
            {
                Stroke = DeviceBrush, StrokeThickness = s == 0 ? 6 : 1.6, Opacity = s == 0 ? 0.12 : 0.85 - s * 0.18,
                StrokeLineJoin = PenLineJoin.Round, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round,
            };
            link.Children.Add(strands[s]);
        }
        var devices = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
        devices.Children.Add(new FontIcon { Glyph = "", FontSize = 60, Foreground = DeviceBrush, VerticalAlignment = VerticalAlignment.Center });
        devices.Children.Add(link);
        devices.Children.Add(new FontIcon { Glyph = "", FontSize = 84, Foreground = DeviceBrush, VerticalAlignment = VerticalAlignment.Center });
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(devices, "Your phone and your PC, connected");
        Canvas.SetZIndex(devices, 2);
        hero.Children.Add(devices);

        var start = new Button { Content = "Get started", Style = Style("AccentButtonStyle"), HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 20, 0, 0), Padding = new Thickness(28, 8, 28, 8), FontSize = 15 };
        start.Click += (_, _) => ShowWelcome(1);
        var text = new StackPanel { Spacing = 6, Children = { Heading("Welcome to Mac n Droid"), Line("Your Android phone and your PC, together."), start } };
        var panel = new StackPanel { Spacing = 8, Children = { hero, text } };

        _animate = t =>
        {
            double w = hero.Width, h = hero.Height, cx = w / 2, cy = h / 2;
            devices.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
            Canvas.SetLeft(devices, cx - devices.DesiredSize.Width / 2);
            Canvas.SetTop(devices, cy - devices.DesiredSize.Height / 2);

            // Eight features on a tilted ellipse: the orbit speeds up and slows down, each chip floats on its own,
            // and they arrive one by one.
            double rx = 330, ry = 128;
            var spin = t * (2 * Math.PI / 80) + 0.35 * Math.Sin(t * 0.17);
            for (var i = 0; i < chips.Length; i++)
            {
                var a = spin + (double)i / chips.Length * 2 * Math.PI;
                var depth = (Math.Sin(a) + 1) / 2;
                var bob = _motion && !InstantMotion ? Math.Sin(t * 0.9 + i * 1.3) * 3 : 0;
                var k = Clamp01((t - 0.25 - i * 0.09) / 0.7);
                var scale = (0.78 + depth * 0.22) * (0.6 + 0.4 * Spring(k));
                var chip = chips[i];
                ((ScaleTransform)chip.RenderTransform).ScaleX = ((ScaleTransform)chip.RenderTransform).ScaleY = scale;
                chip.Opacity = (0.45 + depth * 0.55) * Clamp01(k * 1.5);
                Canvas.SetLeft(chip, cx + rx * Math.Cos(a) - 22);
                Canvas.SetTop(chip, cy + ry * Math.Sin(a) * 0.92 + bob - 22);
                Canvas.SetZIndex(chip, depth > 0.5 ? 3 : 1);
            }

            // Strands pinned at both ends, wobbling with smooth noise and a little crackle.
            for (var s = 0; s < strands.Length; s++)
            {
                var pts = new PointCollection();
                var seed = s == 0 ? 1 : s;
                for (var j = 0; j <= 24; j++)
                {
                    var u = j / 24.0;
                    var envelope = Math.Sin(Math.PI * u);
                    var n = Math.Sin(u * 7.1 + t * 3.1 * seed + seed * 1.7) * 0.6 + Math.Sin(u * 13.3 - t * 5.3 + seed * 2.9) * 0.3
                            + Math.Sin(u * 29.0 + t * 11.0 * seed) * 0.1;
                    pts.Add(new Point(u * link.Width, link.Height / 2 + envelope * n * (s == 0 ? 10 : 14)));
                }
                strands[s].Points = pts;
            }
        };
        return Centered(panel);
    }

    // MARK: 2. Pair your phone

    readonly Image _qrImage = new() { Width = 232, Height = 232 };

    UIElement PairStep()
    {
        _qrImage.Source = Qr(_state.QrText);
        var qr = new Border
        {
            Background = new SolidColorBrush(Microsoft.UI.Colors.White), CornerRadius = new CornerRadius(16), Padding = new Thickness(12),
            Child = _qrImage, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
            RenderTransform = new ScaleTransform { CenterX = 128, CenterY = 128 },
        };
        if (_welcomePreview)
        {
            ToolTipService.SetToolTip(qr, "Preview: click to pretend a phone scanned the code");
            qr.Tapped += (_, _) => ShowWelcome(2);
        }
        var stage = new Grid { Width = 330, Height = 330, HorizontalAlignment = HorizontalAlignment.Center };
        var rings = new Rectangle[3];
        for (var i = 0; i < rings.Length; i++)
        {
            rings[i] = new Rectangle
            {
                Width = 256, Height = 256, RadiusX = 28, RadiusY = 28, Stroke = Brush("AccentFillColorDefaultBrush"), StrokeThickness = 1.5,
                HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
                RenderTransform = new ScaleTransform { CenterX = 128, CenterY = 128 }, Opacity = 0,
            };
            stage.Children.Add(rings[i]);
        }
        stage.Children.Add(qr);

        var copy = new Button { Content = "Copy pairing link", HorizontalAlignment = HorizontalAlignment.Center };
        ToolTipService.SetToolTip(copy, "Send this link to your phone and open it there, if you can't scan the code");
        copy.Click += async (_, _) =>
        {
            var p = new DataPackage();
            p.SetText(_state.PairingLink);
            Clipboard.SetContent(p);
            copy.Content = "Copied";
            await Task.Delay(2000);
            copy.Content = "Copy pairing link";
        };
        var later = new HyperlinkButton { Content = "Set up later", HorizontalAlignment = HorizontalAlignment.Center };
        later.Click += (_, _) => CloseWelcome("pair");

        var waiting = new StackPanel
        {
            Orientation = Orientation.Horizontal, Spacing = 8, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 14, 0, 0),
            Children = { new ProgressRing { Width = 16, Height = 16, IsActive = true }, Line("Waiting for your phone", 14) },
        };
        var text = new StackPanel
        {
            Spacing = 8, MaxWidth = 480, RenderTransform = new TranslateTransform(),
            Children =
            {
                Heading("Pair your Android phone"),
                Line("Open Mac n Droid on your phone, tap Scan QR code and point it at this code."),
                Line("No app yet? Scan the code with your phone's camera to get it from Google Play.", 14),
                waiting,
                new StackPanel { Spacing = 6, Margin = new Thickness(0, 18, 0, 0), Children = { copy, later } },
                new TextBlock
                {
                    Text = "Both devices need to be on the same Wi-Fi. Everything stays on your network and is end-to-end encrypted.",
                    FontSize = 12, Foreground = Brush("TextFillColorTertiaryBrush"), TextWrapping = TextWrapping.Wrap,
                    TextAlignment = TextAlignment.Center, MaxWidth = 420, Margin = new Thickness(0, 20, 0, 0), HorizontalAlignment = HorizontalAlignment.Center,
                },
            },
        };

        _animate = t =>
        {
            var shown = Spring(t / 0.6);
            var s = (ScaleTransform)qr.RenderTransform;
            s.ScaleX = s.ScaleY = 0.9 + 0.1 * shown;
            qr.Opacity = Clamp01(t / 0.35);
            text.Opacity = Clamp01((t - 0.1) / 0.4);
            ((TranslateTransform)text.RenderTransform).Y = 8 * (1 - EaseOut((t - 0.1) / 0.5));
            // Rings drift outwards and fade, one after another, while it waits.
            for (var i = 0; i < rings.Length; i++)
            {
                var phase = (t * 0.45 + i / 3.0) % 1;
                var r = (ScaleTransform)rings[i].RenderTransform;
                r.ScaleX = r.ScaleY = 1 + phase * 0.26;
                rings[i].Opacity = _motion && !InstantMotion ? 0.45 * (1 - phase) * Clamp01(t / 0.6) : 0;
            }
        };
        return Centered(new StackPanel { Spacing = 10, Children = { stage, text } });
    }

    // MARK: 3. You're connected

    UIElement ConnectedStep()
    {
        Border Tile(string glyph, double size) => new()
        {
            Width = 96, Height = 96, CornerRadius = new CornerRadius(20),
            Background = Brush("CardBackgroundFillColorDefaultBrush"), BorderBrush = Brush("CardStrokeColorDefaultBrush"), BorderThickness = new Thickness(1),
            Child = new FontIcon { Glyph = glyph, FontSize = size, Foreground = DeviceBrush },
            RenderTransform = new TranslateTransform(),
        };
        var phone = Tile("", 48);
        var pc = Tile("", 60);
        Rectangle Bar(double centerX) => new()
        {
            Width = 33, Height = 2, RadiusX = 1, RadiusY = 1, Fill = new SolidColorBrush(Green),
            RenderTransform = new ScaleTransform { CenterX = centerX }, VerticalAlignment = VerticalAlignment.Center,
        };
        var left = Bar(0);
        var right = Bar(33);
        left.HorizontalAlignment = HorizontalAlignment.Left;
        right.HorizontalAlignment = HorizontalAlignment.Right;
        var check = new Border
        {
            Width = 34, Height = 34, CornerRadius = new CornerRadius(17), Background = new SolidColorBrush(Green),
            Child = new FontIcon { Glyph = "", FontSize = 15, Foreground = new SolidColorBrush(Microsoft.UI.Colors.White) },
            RenderTransform = new ScaleTransform { CenterX = 17, CenterY = 17 },
        };
        var middle = new Grid { Width = 120, Children = { left, right, check } };
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 0, 0, 24), Children = { phone, middle, pc } };

        var again = new Button { Content = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Children = { new FontIcon { Glyph = "", FontSize = 14 }, new TextBlock { Text = "Play confetti" } } } };
        again.Click += (_, _) => AddConfettiBurst();
        var go = new Button { Content = "Continue", Style = Style("AccentButtonStyle"), Padding = new Thickness(28, 6, 28, 6) };
        go.Click += (_, _) => CloseWelcome("overview");
        var text = new StackPanel
        {
            Spacing = 8, RenderTransform = new TranslateTransform(),
            Children =
            {
                Heading("You're connected"),
                Line($"{_state.PhoneName} and this PC now work together."),
                new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 16, 0, 0), Children = { again, go } },
            },
        };

        _animate = t =>
        {
            var snap = Spring(t / 0.7);
            ((TranslateTransform)phone.RenderTransform).X = -40 * (1 - snap);
            ((TranslateTransform)pc.RenderTransform).X = 40 * (1 - snap);
            phone.Opacity = pc.Opacity = Clamp01(t / 0.3);
            var lines = EaseInOut((t - 0.55) / 0.4);
            ((ScaleTransform)left.RenderTransform).ScaleX = ((ScaleTransform)right.RenderTransform).ScaleX = lines;
            var c = Math.Max(0.01, Spring((t - 0.85) / 0.45));
            ((ScaleTransform)check.RenderTransform).ScaleX = ((ScaleTransform)check.RenderTransform).ScaleY = c;
            text.Opacity = Clamp01((t - 1.0) / 0.5);
            ((TranslateTransform)text.RenderTransform).Y = 8 * (1 - EaseOut((t - 1.0) / 0.5));
            if (!_confettiPlayed && t >= 0.75 && _motion && !InstantMotion) { _confettiPlayed = true; AddConfettiBurst(); }
        };
        return Centered(new StackPanel { Children = { row, text } });
    }

    // MARK: Confetti

    sealed record Piece(FrameworkElement Shape, CompositeTransform Move, bool Right, double Angle, double Speed, double W, double R, double VR, double Tilt);
    readonly List<(DateTime start, Piece[] pieces)> _bursts = new();

    static readonly Color[] Palette =
    [
        Green, Color.FromArgb(255, 48, 209, 89), Color.FromArgb(255, 0, 120, 212), Color.FromArgb(255, 90, 200, 250),
        Color.FromArgb(255, 255, 204, 0), Color.FromArgb(255, 255, 149, 0), Color.FromArgb(255, 255, 45, 85), Color.FromArgb(255, 175, 82, 222),
    ];

    /// <summary>A burst from both bottom corners. Pressing again adds to what's already falling (at most six).</summary>
    void AddConfettiBurst()
    {
        var rnd = Random.Shared;
        var pieces = new List<Piece>();
        for (var side = 0; side < 2; side++)
            for (var i = 0; i < 70; i++)
            {
                double w = rnd.NextDouble() * 6 + 7, h = rnd.NextDouble() * 5 + 4;
                var round = rnd.NextDouble() < 0.25;
                var brush = new SolidColorBrush(Palette[rnd.Next(Palette.Length)]);
                FrameworkElement shape = round
                    ? new Ellipse { Width = h, Height = h, Fill = brush }
                    : new Rectangle { Width = w, Height = h, Fill = brush };
                var move = new CompositeTransform { CenterX = shape.Width / 2, CenterY = shape.Height / 2 };
                shape.RenderTransform = move;
                _confettiLayer.Children.Add(shape);
                pieces.Add(new Piece(shape, move, side == 1,
                    (side == 1 ? -Math.PI * 0.62 : -Math.PI * 0.38) + (rnd.NextDouble() - 0.5) * 0.56,
                    rnd.NextDouble() * 0.9 + 0.9, shape.Width, rnd.NextDouble() * 6, (rnd.NextDouble() - 0.5) * 0.34, rnd.NextDouble() * 6));
            }
        _bursts.Add((DateTime.UtcNow, pieces.ToArray()));
        while (_bursts.Count > 6) { foreach (var p in _bursts[0].pieces) _confettiLayer.Children.Remove(p.Shape); _bursts.RemoveAt(0); }
        if (!_frameHooked) { CompositionTarget.Rendering += OnFrame; _frameHooked = true; }
    }

    /// <summary>Simple ballistic motion with drag, worked out from each burst's age. True while any is still falling.</summary>
    bool UpdateConfetti()
    {
        if (_bursts.Count == 0) return false;
        double W = _confettiLayer.ActualWidth, H = _confettiLayer.ActualHeight;
        const double drag = 0.985;
        var g = H / 2600;
        for (var b = _bursts.Count - 1; b >= 0; b--)
        {
            var (start, pieces) = _bursts[b];
            var frames = (DateTime.UtcNow - start).TotalSeconds * 60;
            var fade = Clamp01((300 - frames) / 60);
            if (fade <= 0)
            {
                foreach (var p in pieces) _confettiLayer.Children.Remove(p.Shape);
                _bursts.RemoveAt(b);
                continue;
            }
            var decay = (1 - Math.Pow(drag, frames)) / (1 - drag);
            var fall = g * (frames - decay) / (1 - drag);
            foreach (var p in pieces)
            {
                var sp = H / 55 * p.Speed;
                p.Move.TranslateX = (p.Right ? W + 10 : -10) + Math.Cos(p.Angle) * sp * decay - p.W / 2;
                p.Move.TranslateY = H * 0.95 + Math.Sin(p.Angle) * sp * decay + fall;
                p.Move.Rotation = (p.R + p.VR * frames) * 180 / Math.PI;
                p.Move.ScaleY = Math.Max(0.15, Math.Abs(Math.Cos(p.Tilt + 0.12 * frames)));
                p.Shape.Opacity = fade;
            }
        }
        return _bursts.Count > 0;
    }
}
