#!/bin/sh
# Builds one zip per store from browser-extension/ (same code, each with only the manifest keys that store reads):
#   tools/package-extension.sh 0.13.1  →  release/MacNDroid-extension-{chrome,edge,firefox}-0.13.1.zip
# Chrome and Edge run the background as a service worker; Firefox runs it as a background script and needs its
# add-on id. Upload chrome to the Chrome Web Store, edge to Edge Add-ons, firefox to addons.mozilla.org.
set -e
VERSION="${1:?usage: tools/package-extension.sh <version>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/release"
mkdir -p "$OUT"
for store in chrome edge firefox; do
  TMP="$(mktemp -d)"
  (cd "$ROOT/browser-extension" && cp -R background.js content.js popup.html popup.js icons "$TMP/")
  python3 - "$ROOT/browser-extension/manifest.json" "$TMP/manifest.json" "$store" "$VERSION" <<'PY'
import json, sys
src, dst, store, version = sys.argv[1:]
m = json.load(open(src))
m["version"] = version
if store == "firefox":
    m["background"] = {"scripts": m["background"]["scripts"]}
else:
    m["background"] = {"service_worker": m["background"]["service_worker"]}
    m.pop("browser_specific_settings", None)
json.dump(m, open(dst, "w"), indent=2)
PY
  find "$TMP" -name ".*" -delete
  rm -f "$OUT/MacNDroid-extension-$store-$VERSION.zip"
  (cd "$TMP" && zip -qr -X "$OUT/MacNDroid-extension-$store-$VERSION.zip" .)
  rm -rf "$TMP"
  echo "release/MacNDroid-extension-$store-$VERSION.zip"
done
