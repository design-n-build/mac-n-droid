// Renders the store graphics from compose.html with headless Chromium.
//   NODE_PATH=<folder with playwright> node store/graphics/src/render.mjs
import { createRequire } from "module";
import path from "path";
import { fileURLToPath } from "url";
const require = createRequire(import.meta.url);
const { chromium } = require("playwright");
const here = path.dirname(fileURLToPath(import.meta.url));
const out = path.resolve(here, "..");
const ext = path.resolve(here, "../../../browser-extension");

const shots = [
  // Google Play: phone screenshots 1080×1920, feature graphic 1024×500, icon 512×512
  ["play-1-notifications", 1080, 1920, 2], ["play-2-clipboard", 1080, 1920, 2], ["play-3-files", 1080, 1920, 2],
  ["play-4-apps", 1080, 1920, 2], ["play-5-menubar", 1080, 1920, 2], ["play-6-dark", 1080, 1920, 2],
  ["play-feature-graphic", 1024, 500, 1], ["play-icon", 512, 512, 1],
  // Chrome Web Store / Edge / Firefox: screenshots 1280×800, small promo 440×280, marquee 1400×560
  ["ext-1-music", 1280, 800, 1], ["ext-2-links", 1280, 800, 1], ["ext-3-popup", 1280, 800, 1], ["ext-4-setup", 1280, 800, 1],
  ["ext-promo-small", 440, 280, 1], ["ext-promo-marquee", 1400, 560, 1],
];
const only = process.argv.slice(2);

const browser = await chromium.launch();
// The real extension popup, with the browser API answered by sample data.
{
  const page = await browser.newPage({ viewport: { width: 288, height: 200 }, deviceScaleFactor: 3 });
  await page.addInitScript(() => {
    const status = { app: true, connected: true, phone: "Phone", media: { playing: true, title: "Golden Hour", site: "music.example.com" } };
    window.chrome = { runtime: { sendMessage: (_m, cb) => (cb ? cb(status) : Promise.resolve()) }, tabs: { query: async () => [] } };
  });
  await page.goto("file://" + ext + "/popup.html");
  const h = await page.evaluate(() => document.documentElement.scrollHeight);
  await page.setViewportSize({ width: 288, height: h });
  await page.screenshot({ path: path.join(here, "shots/ext-popup.png") });
  await page.close();
}
for (const [name, w, h, scale] of shots) {
  if (only.length && !only.some((o) => name.includes(o))) continue;
  const page = await browser.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: scale });
  await page.goto("file://" + path.join(here, "compose.html") + "#" + name);
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(300);
  await page.screenshot({ path: path.join(out, name + ".png"), omitBackground: false });
  console.log("✓", name, `${w * scale}×${h * scale}`);
  await page.close();
}
await browser.close();
