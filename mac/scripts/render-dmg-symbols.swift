// Renders the SF Symbols used in the DMG background (Resources/dmg/background.html) to PNGs.
//   swift scripts/render-dmg-symbols.swift
import AppKit

let out = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "Resources/dmg/symbols")
let grey = NSColor(srgbRed: 0.43, green: 0.43, blue: 0.45, alpha: 1)   // #6e6e73
let dark = NSColor(srgbRed: 0.23, green: 0.23, blue: 0.24, alpha: 1)   // #3a3a3c
let symbols: [(String, NSColor, CGFloat, NSFont.Weight)] = [
    ("smartphone", dark, 150, .light), ("laptopcomputer", dark, 150, .light),
    ("bell.fill", grey, 44, .regular), ("photo.fill", grey, 44, .regular), ("list.clipboard.fill", grey, 44, .regular),
    ("arrow.right", grey, 60, .medium),
]
for (name, color, size, weight) in symbols {
    // Drawn as a one-colour template (outlines stay outlines), then tinted.
    let config = NSImage.SymbolConfiguration(pointSize: size, weight: weight)
    guard let img = NSImage(systemSymbolName: name, accessibilityDescription: nil)?.withSymbolConfiguration(config) else {
        print("missing", name); continue
    }
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(img.size.width * 2), pixelsHigh: Int(img.size.height * 2),
                               bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                               bytesPerRow: 0, bitsPerPixel: 0)!
    rep.size = img.size
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    img.draw(in: NSRect(origin: .zero, size: img.size))
    color.set()
    NSRect(origin: .zero, size: img.size).fill(using: .sourceAtop)
    NSGraphicsContext.restoreGraphicsState()
    try! rep.representation(using: .png, properties: [:])!.write(to: out.appendingPathComponent(name + ".png"))
    print("✓", name, img.size)
}
