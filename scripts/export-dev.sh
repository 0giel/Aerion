#!/usr/bin/env bash
# Build portable developer versions for the owner's own tests: the debug profile with the frontend
# embedded, so LAUNCHER_DEV_OFFLINE works, next to a start script. A separate app identifier and data
# folder keep them apart from an installed Aerion. Never publish these: they can skip the ownership
# check, which is only meant for testing.
#
# Usage: scripts/export-dev.sh [output folder, default ~/Desktop/aerion-testy]
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${1:-$HOME/Desktop/aerion-testy}"
ID_FILE="$HOME/.config/aerion-signing/msa-client-id"
if [ -z "${LAUNCHER_MSA_CLIENT_ID:-}" ] && [ -f "$ID_FILE" ]; then LAUNCHER_MSA_CLIENT_ID="$(cat "$ID_FILE")"; fi
export LAUNCHER_MSA_CLIENT_ID="${LAUNCHER_MSA_CLIENT_ID:-}"
source scripts/env.sh
CONFIG='{"identifier":"pl.aerion.devtest","bundle":{"createUpdaterArtifacts":false}}'
OVERRIDE="$HOME/.cache/luncher-devroot/pc-override"
if [ -d "$OVERRIDE" ]; then
  export PKG_CONFIG_ALLOW_SYSTEM_LIBS=1 PKG_CONFIG_PATH="$OVERRIDE:${PKG_CONFIG_PATH:-}"
fi
pnpm tauri build --debug --no-bundle --config "$CONFIG"
scripts/windows-cross.sh pnpm tauri build --debug --no-bundle --runner cargo-xwin --target x86_64-pc-windows-msvc --config "$CONFIG"

mkdir -p "$OUT/linux" "$OUT/windows"
cp target/debug/aerion "$OUT/linux/aerion-dev"
cp target/x86_64-pc-windows-msvc/debug/aerion.exe "$OUT/windows/aerion-dev.exe"
cat > "$OUT/linux/uruchom.sh" <<'EOS'
#!/bin/sh
# Developer version: test data next to this file, offline accounts without Microsoft sign-in.
cd "$(dirname "$0")" || exit 1
AERION_HOME="$PWD/dane" LAUNCHER_DEV_OFFLINE=1 exec ./aerion-dev
EOS
chmod +x "$OUT/linux/uruchom.sh" "$OUT/linux/aerion-dev"
printf '@echo off\r\nrem Developer version: test data next to this file, offline accounts without Microsoft sign-in.\r\nset "AERION_HOME=%%~dp0dane"\r\nset "LAUNCHER_DEV_OFFLINE=1"\r\nstart "" "%%~dp0aerion-dev.exe"\r\n' > "$OUT/windows/uruchom.bat"
ls -la "$OUT/linux" "$OUT/windows"
