// Builds every app icon from the logos in public/.
// Usage: swift tools/make-icons.swift   (run from the project root)
//
// Mac:     mac/Resources/AppIcon.iconset (dark squircle, white glyph) + MenuBarIcon template PNGs
// Android: launcher foreground/monochrome, background color, status-bar icon
import AppKit

let root = FileManager.default.currentDirectoryPath
func load(_ name: String) -> NSImage { NSImage(contentsOfFile: "\(root)/public/\(name)")! }
/// Recolors a one-color logo (brand colors come from the logos in public/, see below).
func tinted(_ img: NSImage, _ c: NSColor) -> NSImage {
    NSImage(size: img.size, flipped: false) { r in img.draw(in: r); c.set(); r.fill(using: .sourceAtop); return true }
}
// Brand off-white (#FAFAFA) and green, as in public/logo_droid_greenbg.svg: softer than pure white and bright green.
let offWhite = NSColor(srgbRed: 0xFA / 255, green: 0xFA / 255, blue: 0xFA / 255, alpha: 1)
let white = tinted(load("logo_nobg_white.png"), offWhite)
let black = load("logo_nobg_black.png")
let greenBG = load("logo_droid_greenbg.png")

func bitmap(_ w: Int, _ h: Int, _ draw: (CGSize) -> Void) -> NSBitmapImageRep {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: w, pixelsHigh: h, bitsPerSample: 8,
                               samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                               colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    let ctx = NSGraphicsContext(bitmapImageRep: rep)!
    ctx.imageInterpolation = .high
    NSGraphicsContext.current = ctx
    draw(CGSize(width: w, height: h))
    NSGraphicsContext.restoreGraphicsState()
    return rep
}
func save(_ rep: NSBitmapImageRep, _ path: String) {
    let url = URL(fileURLWithPath: "\(root)/\(path)")
    try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    try! rep.representation(using: .png, properties: [:])!.write(to: url)
}

/// Bounding box of the opaque glyph inside a 570×570 logo (in image coordinates, origin bottom-left).
func glyphBounds(_ img: NSImage) -> NSRect {
    let rep = NSBitmapImageRep(data: img.tiffRepresentation!)!
    var minX = rep.pixelsWide, minY = rep.pixelsHigh, maxX = 0, maxY = 0
    for y in 0..<rep.pixelsHigh { for x in 0..<rep.pixelsWide where (rep.colorAt(x: x, y: y)?.alphaComponent ?? 0) > 0.1 {
        minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
    } }
    let h = CGFloat(rep.pixelsHigh)
    let scale = img.size.width / CGFloat(rep.pixelsWide)
    return NSRect(x: CGFloat(minX) * scale, y: (h - CGFloat(maxY) - 1) * scale,
                  width: CGFloat(maxX - minX + 1) * scale, height: CGFloat(maxY - minY + 1) * scale)
}
let glyph = glyphBounds(white)
let glyphAspect = glyph.width / glyph.height

/// Draws just the glyph, fitted (by height) into `rect`, centered.
func drawGlyph(_ img: NSImage, in rect: NSRect) {
    let h = min(rect.height, rect.width / glyphAspect), w = h * glyphAspect
    let dst = NSRect(x: rect.midX - w / 2, y: rect.midY - h / 2, width: w, height: h)
    img.draw(in: dst, from: glyph, operation: .sourceOver, fraction: 1)
}

let green = NSColor(srgbRed: 0x0D / 255, green: 0xA4 / 255, blue: 0x57 / 255, alpha: 1)   // public/logo_droid_greenbg.svg
let greenHex = String(format: "#%02X%02X%02X", Int(green.redComponent * 255), Int(green.greenComponent * 255), Int(green.blueComponent * 255))

// MARK: Mac app icon — macOS grid: 824/1024 body, ~185 radius, soft drop shadow.
let iconset = "mac/Resources/AppIcon.iconset"
for (pt, scales) in [(16, [1, 2]), (32, [1, 2]), (128, [1, 2]), (256, [1, 2]), (512, [1, 2])] {
    for sc in scales {
        let px = pt * sc
        let rep = bitmap(px, px) { size in
            let s = size.width
            let inset = s * 100 / 1024
            let body = NSRect(x: inset, y: inset * 1.1, width: s - inset * 2, height: s - inset * 2)
            let path = NSBezierPath(roundedRect: body, xRadius: s * 185 / 1024, yRadius: s * 185 / 1024)
            NSGraphicsContext.saveGraphicsState()
            let shadow = NSShadow()
            shadow.shadowColor = NSColor.black.withAlphaComponent(0.3)
            shadow.shadowOffset = NSSize(width: 0, height: -s * 10 / 1024)
            shadow.shadowBlurRadius = s * 22 / 1024
            shadow.set()
            NSColor(white: 0.08, alpha: 1).setFill()
            path.fill()
            NSGraphicsContext.restoreGraphicsState()
            // Subtle top-to-bottom sheen, like native dark icons.
            NSGradient(starting: NSColor(white: 0.20, alpha: 1), ending: NSColor(white: 0.06, alpha: 1))!
                .draw(in: path, angle: -90)
            drawGlyph(white, in: body.insetBy(dx: body.width * 0.25, dy: body.height * 0.22))
        }
        save(rep, "\(iconset)/icon_\(pt)x\(pt)\(sc == 2 ? "@2x" : "").png")
    }
}

// MARK: Mac menu bar template icon (18 pt tall glyph area).
for sc in [1, 2] {
    let rep = bitmap(18 * sc, 18 * sc) { size in
        drawGlyph(black, in: NSRect(x: 0, y: 0, width: size.width, height: size.height).insetBy(dx: 0, dy: CGFloat(sc)))
    }
    save(rep, "mac/Resources/MenuBarIcon\(sc == 2 ? "@2x" : "").png")
}

// MARK: Android adaptive icon (108 dp canvas = 432 px at 4x; glyph inside the 66 dp safe zone).
let fg = bitmap(432, 432) { size in
    drawGlyph(white, in: NSRect(x: 0, y: 0, width: size.width, height: size.height).insetBy(dx: 432 * 0.31, dy: 432 * 0.30))
}
save(fg, "android/app/src/main/res/drawable-nodpi/ic_launcher_fg.png")

// Status-bar icon: 24 dp, white glyph on transparent (Android tints it).
for (dir, px) in [("mdpi", 24), ("hdpi", 36), ("xhdpi", 48), ("xxhdpi", 72), ("xxxhdpi", 96)] {
    let rep = bitmap(px, px) { size in
        drawGlyph(white, in: NSRect(x: 0, y: 0, width: size.width, height: size.height).insetBy(dx: 0, dy: size.height * 0.06))
    }
    save(rep, "android/app/src/main/res/drawable-\(dir)/ic_stat_logo.png")
}

// MARK: Browser extension — the green logo on transparent, as large as the slot allows, so it stands
// out in the toolbar next to other extensions.
let greenLogo = tinted(load("logo_nobg_green.png"), green)
let greenGlyph = glyphBounds(greenLogo)
for px in [16, 32, 48, 128] {
    let rep = bitmap(px, px) { size in
        let s = size.width
        let margin = px >= 48 ? s * 0.04 : 0
        let box = NSRect(x: 0, y: 0, width: s, height: s).insetBy(dx: margin, dy: margin)
        let aspect = greenGlyph.width / greenGlyph.height
        let h = min(box.height, box.width / aspect), w = h * aspect
        greenLogo.draw(in: NSRect(x: box.midX - w / 2, y: box.midY - h / 2, width: w, height: h),
                       from: greenGlyph, operation: .sourceOver, fraction: 1)
    }
    save(rep, "browser-extension/icons/icon-\(px).png")
}

// MARK: Share extension ("Send to Phone" in Share menus) — macOS icon grid, brand green, an off-white phone.
let shareIconset = "mac/ShareExtension/ShareIcon.iconset"
for (pt, scales) in [(16, [1, 2]), (32, [1, 2]), (128, [1, 2]), (256, [1, 2]), (512, [1, 2])] {
    for sc in scales {
        let px = pt * sc
        let rep = bitmap(px, px) { size in
            let s = size.width
            let inset = s * 100 / 1024
            let body = NSRect(x: inset, y: inset * 1.1, width: s - inset * 2, height: s - inset * 2)
            let squircle = NSBezierPath(roundedRect: body, xRadius: s * 185 / 1024, yRadius: s * 185 / 1024)
            NSGraphicsContext.saveGraphicsState()
            let shadow = NSShadow()
            shadow.shadowColor = NSColor.black.withAlphaComponent(0.3)
            shadow.shadowOffset = NSSize(width: 0, height: -s * 10 / 1024)
            shadow.shadowBlurRadius = s * 22 / 1024
            shadow.set()
            green.setFill()
            squircle.fill()
            NSGraphicsContext.restoreGraphicsState()
            // A phone: off-white body, green screen, small speaker.
            let pw = body.width * 0.36, ph = body.height * 0.62
            let phone = NSRect(x: body.midX - pw / 2, y: body.midY - ph / 2, width: pw, height: ph)
            offWhite.setFill()
            NSBezierPath(roundedRect: phone, xRadius: pw * 0.2, yRadius: pw * 0.2).fill()
            let bezel = pw * 0.09
            let screen = phone.insetBy(dx: bezel, dy: bezel)
            green.setFill()
            NSBezierPath(roundedRect: screen, xRadius: pw * 0.12, yRadius: pw * 0.12).fill()
            let speaker = NSRect(x: phone.midX - pw * 0.14, y: screen.maxY - bezel * 1.5, width: pw * 0.28, height: bezel * 0.7)
            offWhite.setFill()
            NSBezierPath(roundedRect: speaker, xRadius: bezel * 0.35, yRadius: bezel * 0.35).fill()
        }
        save(rep, "\(shareIconset)/icon_\(pt)x\(pt)\(sc == 2 ? "@2x" : "").png")
    }
}

print("green = \(greenHex)")
