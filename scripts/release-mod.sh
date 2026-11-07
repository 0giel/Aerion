#!/usr/bin/env bash
# Release a new Aerion mod without a new launcher. Installed launchers download it before the
# next game launch (crates/launcher-core/src/aerion_mod/update.rs).
#
#   1. Raise `version=` in mod/gradle.properties (e.g. 1.0.0 -> 1.0.1).
#   2. scripts/release-mod.sh ["notes"] [oldest launcher, default: this launcher's version]
#   3. Create a GitHub release tagged mod-v<version>, NOT marked as latest (the launcher's own
#      updater reads the latest release), and upload the .jar files from release/mod-v<version>/.
#   4. Commit and push data/mod-latest.json (and the rebuilt jars in src-tauri/resources).
# Usually not needed: pushing a tag mod-v<version> does all of this on GitHub
# (.github/workflows/release-mod.yml).
#
# Signs with the launcher's updater key ($AERION_SIGNING_KEY or
# ~/.config/aerion-signing/updater.key; password in $AERION_SIGNING_PASSWORD), never stored here.
set -euo pipefail
cd "$(dirname "$0")/.."
KEY=${AERION_SIGNING_KEY:-$HOME/.config/aerion-signing/updater.key}
[ -f "$KEY" ] || { echo "signing key not found: $KEY" >&2; exit 1; }
source scripts/env.sh

VERSION=$(sed -n 's/^version=//p' mod/gradle.properties | tr -d '[:space:]')
LAUNCHER=$(node -p "require('./src-tauri/tauri.conf.json').version")
NOTES=${1:-"Aerion mod $VERSION"}
MIN_LAUNCHER=${2:-$(sed -n 's/^min_launcher=//p' mod/gradle.properties | tr -d '[:space:]')}
MIN_LAUNCHER=${MIN_LAUNCHER:-$LAUNCHER}

scripts/build-mod.sh
OUT="release/mod-v$VERSION"
rm -rf "$OUT" && mkdir -p "$OUT"
for jar in src-tauri/resources/aerion-ui-*.jar; do
  cp "$jar" "$OUT/"
  pnpm -s tauri signer sign -f "$KEY" -p "${AERION_SIGNING_PASSWORD:-}" "$OUT/$(basename "$jar")" > /dev/null
done

python3 -I scripts/mod-manifest.py "$OUT" "$VERSION" "$MIN_LAUNCHER" "$NOTES"
echo "mod $VERSION ready in $OUT; data/mod-latest.json updated."
echo "Next: GitHub release 'mod-v$VERSION' (not latest) with the .jar files, then commit and push data/mod-latest.json."
ls -la "$OUT"
