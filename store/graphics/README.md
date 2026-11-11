# Store graphics

Ready-to-upload images for Google Play, the Chrome Web Store, Microsoft Edge Add-ons and Firefox Add-ons.
Every screen inside the devices is a real render of the app with sample data, not a drawing.

| File | Where it goes |
|---|---|
| `play-icon.png` (512 × 512) | Play Console → Store listing → App icon |
| `play-feature-graphic.png` (1024 × 500) | Play Console → Feature graphic |
| `play_1.jpg` … `play_8.jpg` (2160 × 3840, 9:16, made in Figma) | Play Console → Phone screenshots, in this order |
| `ext-1` … `ext-4` (1280 × 800) | Chrome Web Store, Edge and Firefox → Screenshots, in this order |
| `ext-promo-small.png` (440 × 280) | Chrome Web Store → Small promo tile (required) |
| `ext-promo-marquee.png` (1400 × 560) | Chrome Web Store → Marquee promo tile (optional) |

## Making them again (after the UI changes)

1. Android screens: `cd android && ./gradlew storeShots` → `android/app/build/store-shots/`
2. Mac screens: a debug build run with `--store-shots <folder> light|dark` inside a temporary app bundle
   (see `mac/Sources/MacNDroid/Debug/StoreShots.swift`). It renders offscreen; nothing appears on screen.
3. Copy the PNGs into `src/shots/`, then `NODE_PATH=<folder with playwright> node store/graphics/src/render.mjs`.

Layouts and text live in `src/compose.html`. Open `src/compose.html#play-2-clipboard` in a browser to preview one.
