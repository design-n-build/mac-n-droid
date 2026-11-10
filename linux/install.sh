#!/bin/sh
# Installs Mac n Droid into a prefix: ./install.sh /usr/local  (or ~/.local for one user, no sudo)
set -e
PREFIX="${1:-$HOME/.local}"
HERE="$(cd "$(dirname "$0")" && pwd)"
SITE="$PREFIX/share/macndroid"
mkdir -p "$SITE" "$PREFIX/bin" "$PREFIX/share/applications" "$PREFIX/share/metainfo" \
         "$PREFIX/share/icons/hicolor/512x512/apps"
rm -rf "$SITE/macndroid"
cp -R "$HERE/macndroid" "$SITE/"
find "$SITE" -name "__pycache__" -prune -exec rm -rf {} +
cat > "$PREFIX/bin/macndroid" <<SH
#!/bin/sh
PYTHONPATH="$SITE\${PYTHONPATH:+:\$PYTHONPATH}" exec python3 -m macndroid "\$@"
SH
chmod +x "$PREFIX/bin/macndroid"
sed "s|^Exec=macndroid|Exec=$PREFIX/bin/macndroid|" "$HERE/data/app.macndroid.Linux.desktop" \
  > "$PREFIX/share/applications/app.macndroid.Linux.desktop"
[ "$PREFIX" = "/app" ] && cp "$HERE/data/app.macndroid.Linux.desktop" "$PREFIX/share/applications/"
cp "$HERE/data/app.macndroid.Linux.metainfo.xml" "$PREFIX/share/metainfo/"
cp "$HERE/data/icons/app.macndroid.Linux.png" "$PREFIX/share/icons/hicolor/512x512/apps/"
echo "Installed Mac n Droid in $PREFIX"
