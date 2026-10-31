#!/usr/bin/env bash
# Build signed bundles and prepare a GitHub release in release/v<version>/:
#   Linux .deb + AppImage, Windows NSIS installer, their updater signatures and latest.json.
# Upload everything in that folder to a GitHub release tagged v<version>
# (https://github.com/0giel/Aerion/releases/new); installed launchers then update themselves.
#
# Usage: scripts/release.sh ["release notes"]
# The updater private key never goes into the repository: $AERION_SIGNING_KEY or
# ~/.config/aerion-signing/updater.key (password in $AERION_SIGNING_PASSWORD, empty by default).
set -euo pipefail
cd "$(dirname "$0")/.."
KEY=${AERION_SIGNING_KEY:-$HOME/.config/aerion-signing/updater.key}
[ -f "$KEY" ] || { echo "signing key not found: $KEY" >&2; exit 1; }
TAURI_SIGNING_PRIVATE_KEY="$(cat "$KEY")"
export TAURI_SIGNING_PRIVATE_KEY
export TAURI_SIGNING_PRIVATE_KEY_PASSWORD="${AERION_SIGNING_PASSWORD:-}"
source scripts/env.sh
# Rootless dev machines (see CONTRIBUTING.md) bundle through pkg-config overrides.
OVERRIDE="$HOME/.cache/luncher-devroot/pc-override"
if [ -d "$OVERRIDE" ]; then
  export PKG_CONFIG_ALLOW_SYSTEM_LIBS=1 PKG_CONFIG_PATH="$OVERRIDE:${PKG_CONFIG_PATH:-}"
fi

VERSION=$(node -p "require('./src-tauri/tauri.conf.json').version")
NOTES=${1:-"Aerion $VERSION"}
pnpm tauri build --bundles deb,appimage
scripts/windows-cross.sh build

OUT="release/v$VERSION"
rm -rf "$OUT" && mkdir -p "$OUT"
LIN=target/release/bundle
WIN=target/x86_64-pc-windows-msvc/release/bundle/nsis
cp "$LIN/deb/Aerion_${VERSION}_amd64.deb" "$OUT/"
cp "$LIN/appimage/Aerion_${VERSION}_amd64.AppImage" "$LIN/appimage/Aerion_${VERSION}_amd64.AppImage.sig" "$OUT/"
cp "$WIN/Aerion_${VERSION}_x64-setup.exe" "$WIN/Aerion_${VERSION}_x64-setup.exe.sig" "$OUT/"

python3 -I - "$OUT" "$VERSION" "$NOTES" <<'PY'
import json, sys, datetime, pathlib
out, version, notes = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
base = f"https://github.com/0giel/Aerion/releases/download/v{version}/"
def entry(name):
    return {"signature": (out / f"{name}.sig").read_text().strip(), "url": base + name}
appimage = entry(f"Aerion_{version}_amd64.AppImage")
nsis = entry(f"Aerion_{version}_x64-setup.exe")
latest = {
    "version": version,
    "notes": notes,
    "pub_date": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "platforms": {
        "linux-x86_64": appimage,
        "linux-x86_64-appimage": appimage,
        "windows-x86_64": nsis,
        "windows-x86_64-nsis": nsis,
    },
}
(out / "latest.json").write_text(json.dumps(latest, indent=2) + "\n")
PY
echo "release ready: $OUT"
ls -la "$OUT"
