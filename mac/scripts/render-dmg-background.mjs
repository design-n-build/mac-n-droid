// Renders mac/Resources/dmg/background.html (660 × 440) to background.tiff (1x + 2x in one file, so Retina Macs get the sharp one).
//   NODE_PATH=<folder with playwright> node mac/scripts/render-dmg-background.mjs [preview.png]
import { createRequire } from "module";
import { execFileSync } from "child_process";
import path from "path";
import { fileURLToPath } from "url";
const require = createRequire(import.meta.url);
const { chromium } = require("playwright");
const dir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../Resources/dmg");
const browser = await chromium.launch();
for (const scale of [1, 2]) {
  const page = await browser.newPage({ viewport: { width: 660, height: 440 }, deviceScaleFactor: scale });
  await page.goto("file://" + path.join(dir, "background.html"));
  await page.evaluate(() => document.fonts.ready);
  await page.screenshot({ path: path.join(dir, scale === 1 ? "bg.png" : "bg@2x.png") });
  await page.close();
}
await browser.close();
execFileSync("tiffutil", ["-cathidpicheck", path.join(dir, "bg.png"), path.join(dir, "bg@2x.png"), "-out", path.join(dir, "background.tiff")], { stdio: "ignore" });
for (const f of ["bg.png", "bg@2x.png"]) (await import("fs")).rmSync(path.join(dir, f));
console.log("✓", path.join(dir, "background.tiff"));
