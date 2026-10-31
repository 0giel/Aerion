#!/usr/bin/env bash
# Rootless Tauri build deps: download Ubuntu -dev packages and unpack them into a private prefix.
set -euo pipefail
BASE="$HOME/.cache/luncher-devroot"
ROOT="$BASE/root"
mkdir -p "$BASE/debs" "$ROOT"
cd "$BASE/debs"
PKGS=$(apt-get -s install libwebkit2gtk-4.1-dev libayatana-appindicator3-dev librsvg2-dev libxdo-dev libssl-dev 2>/dev/null | awk '/^Inst/{print $2}')
apt-get download $PKGS >/dev/null
for f in *.deb; do dpkg-deb -x "$f" "$ROOT"; done
LIB="$ROOT/usr/lib/x86_64-linux-gnu"
# Point pkg-config files at the private prefix.
for pc in "$LIB"/pkgconfig/*.pc "$ROOT"/usr/share/pkgconfig/*.pc; do
  [ -f "$pc" ] || continue
  sed -i -E "s#^(prefix|exec_prefix|libdir|includedir|sharedlibdir)=/usr#\1=$ROOT/usr#" "$pc"
done
# Re-target dangling dev symlinks (libfoo.so -> libfoo.so.N) to the host's runtime libraries.
for l in "$LIB"/*.so; do
  [ -L "$l" ] || continue
  t=$(readlink "$l")
  if [ ! -e "$l" ]; then
    name=$(basename "$t")
    if [ -e "/usr/lib/x86_64-linux-gnu/$name" ]; then ln -sf "/usr/lib/x86_64-linux-gnu/$name" "$l"; fi
  fi
done
echo "devroot ready: $(ls "$LIB"/pkgconfig | wc -l) pc files"
