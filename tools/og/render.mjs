// Renders site/assets/og.png and site/assets/apple-touch-icon.png from tools/og/og.html.
//   NODE_PATH=<folder with playwright> node tools/og/render.mjs
import { createRequire } from "module";
import path from "path";
import { fileURLToPath } from "url";
const require = createRequire(import.meta.url);
const { chromium } = require("playwright");
const here = path.dirname(fileURLToPath(import.meta.url));
const out = path.resolve(here, "../../site/assets");

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1200, height: 900 }, deviceScaleFactor: 1 });
await page.goto("file://" + path.join(here, "og.html"), { waitUntil: "networkidle" });
await page.evaluate(() => document.fonts.ready);
await (await page.$("#og")).screenshot({ path: path.join(out, "og.png") });
await (await page.$("#touch")).screenshot({ path: path.join(out, "apple-touch-icon.png") });
await browser.close();
console.log("wrote og.png and apple-touch-icon.png");
