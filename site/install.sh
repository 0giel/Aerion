#!/bin/sh
# Aerion installer for Linux (https://0giel.github.io/Aerion/).
#
#   curl -fsSL https://0giel.github.io/Aerion/install.sh | sh
#   curl -fsSL https://0giel.github.io/Aerion/install.sh | sh -s -- --uninstall
#
# Downloads the newest Aerion AppImage from the project's GitHub releases, checks its SHA-256,
# and puts it in your home folder with a menu entry. Nothing is installed system-wide and no
# administrator password is needed. After this the launcher updates itself in place, so you
# never download an installer again; running this script again also upgrades.
#
# Where things go (override the first with AERION_DIR):
#   ~/.local/share/aerion/Aerion.AppImage     the program
#   ~/.local/bin/aerion                       a command that starts it
#   ~/.local/share/applications/aerion.desktop, ~/.local/share/icons/.../aerion.png   menu entry
set -eu

REPO="0giel/Aerion"
SITE="https://0giel.github.io/Aerion"
DATA="${XDG_DATA_HOME:-$HOME/.local/share}"
APP_DIR="${AERION_DIR:-$DATA/aerion}"
BIN_DIR="$HOME/.local/bin"
DESKTOP_FILE="$DATA/applications/aerion.desktop"
ICON_FILE="$DATA/icons/hicolor/256x256/apps/aerion.png"
TARGET="$APP_DIR/Aerion.AppImage"

say() { printf '%s\n' "$*"; }
die() { printf 'Error: %s\n' "$*" >&2; exit 1; }

# Polish when the system language is Polish.
case "${LC_ALL:-${LC_MESSAGES:-${LANG:-}}}" in
  pl*) PL=1 ;;
  *) PL=0 ;;
esac
msg() { if [ "$PL" = 1 ]; then say "$1"; else say "$2"; fi; }

uninstall() {
  rm -f "$TARGET" "$APP_DIR/VERSION" "$BIN_DIR/aerion" "$DESKTOP_FILE" "$ICON_FILE"
  rmdir "$APP_DIR" 2>/dev/null || true
  msg "Aerion usunięty. Twoje instancje i ustawienia zostały (folder ~/.local/share/aerion lub dane launchera)." \
      "Aerion removed. Your instances and settings were kept."
  exit 0
}

[ "${1:-}" = "--uninstall" ] && uninstall
[ "$(id -u)" -ne 0 ] || die "do not run this as root; it installs into your home folder."

case "$(uname -m)" in
  x86_64 | amd64) ;;
  *) die "only 64-bit x86 (x86_64) builds exist for now; this is $(uname -m)." ;;
esac

# curl or wget, whichever there is.
if command -v curl >/dev/null 2>&1; then
  get() { curl -fsSL "$1"; }
  fetch() {
    if [ -t 2 ]; then curl -fL --progress-bar -o "$2" "$1"; else curl -fsSL -o "$2" "$1"; fi
  }
elif command -v wget >/dev/null 2>&1; then
  get() { wget -qO- "$1"; }
  fetch() {
    if [ -t 2 ]; then wget -q --show-progress -O "$2" "$1"; else wget -q -O "$2" "$1"; fi
  }
else
  die "curl or wget is needed."
fi
command -v sha256sum >/dev/null 2>&1 || die "sha256sum is needed."

msg "Sprawdzam najnowszą wersję…" "Checking the newest version…"
API_JSON="$(get "https://api.github.com/repos/$REPO/releases/latest")" || die "can't reach GitHub."

# The AppImage's name, address and checksum from the release; no jq needed. The API lists each
# asset's fields together, "name" first and "digest" after.
ASSET="$(printf '%s' "$API_JSON" | tr ',' '\n' | grep -n '"name": *"[^"]*\.AppImage"' | head -n 1 | cut -d: -f1 || true)"
[ -n "$ASSET" ] || die "no AppImage in the newest release."
NAME="$(printf '%s' "$API_JSON" | tr ',' '\n' | sed -n "${ASSET}p" | sed 's/.*"name": *"\([^"]*\)".*/\1/')"
URL="https://github.com/$REPO/releases/download/$(printf '%s' "$API_JSON" | tr ',' '\n' | grep '"tag_name"' | head -n 1 | sed 's/.*"tag_name": *"\([^"]*\)".*/\1/')/$NAME"
SHA="$(printf '%s' "$API_JSON" | tr ',' '\n' | tail -n +"$ASSET" | grep -m 1 '"digest"' | sed 's/.*"sha256:\([0-9a-f]\{64\}\)".*/\1/' || true)"
VERSION="$(printf '%s' "$NAME" | sed 's/^Aerion_\(.*\)_amd64\.AppImage$/\1/')"
[ -n "$SHA" ] && [ "${#SHA}" -eq 64 ] || die "the release has no checksum to verify against; refusing to install."

if [ -f "$APP_DIR/VERSION" ] && [ "$(cat "$APP_DIR/VERSION")" = "$VERSION" ] && [ -x "$TARGET" ]; then
  msg "Aerion $VERSION jest już zainstalowany." "Aerion $VERSION is already installed."
  exit 0
fi

mkdir -p "$APP_DIR" "$BIN_DIR" "$(dirname "$DESKTOP_FILE")" "$(dirname "$ICON_FILE")"
TMP="$(mktemp "$APP_DIR/.download.XXXXXX")"
trap 'rm -f "$TMP"' EXIT INT TERM

msg "Pobieram Aerion $VERSION…" "Downloading Aerion $VERSION…"
fetch "$URL" "$TMP" || die "download failed."
GOT="$(sha256sum "$TMP" | cut -d' ' -f1)"
if [ "$GOT" != "$SHA" ]; then
  die "the checksum doesn't match (expected $SHA, got $GOT). Nothing was installed."
fi
chmod +x "$TMP"
mv -f "$TMP" "$TARGET"
trap - EXIT INT TERM
printf '%s\n' "$VERSION" >"$APP_DIR/VERSION"
ln -sf "$TARGET" "$BIN_DIR/aerion"

# The menu icon (best effort), and FUSE: an AppImage needs libfuse2; without it, run it unpacked.
get "$SITE/assets/icon.png" >"$ICON_FILE" 2>/dev/null || rm -f "$ICON_FILE"
EXEC="$TARGET"
if ! { ldconfig -p 2>/dev/null | grep -q 'libfuse\.so\.2'; }; then
  EXEC="env APPIMAGE_EXTRACT_AND_RUN=1 $TARGET"
  FUSE_NOTE=1
fi
cat >"$DESKTOP_FILE" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Aerion
GenericName=Minecraft launcher
Comment=Fast, themeable launcher for Minecraft: Java Edition
Exec=$EXEC %U
Icon=aerion
Terminal=false
Categories=Game;
MimeType=x-scheme-handler/aerion;
StartupWMClass=aerion
DESKTOP
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$(dirname "$DESKTOP_FILE")" >/dev/null 2>&1 || true

msg "Gotowe: Aerion $VERSION jest w menu aplikacji (szukaj „Aerion”)." "Done: Aerion $VERSION is in your application menu (search for “Aerion”)."
msg "Od teraz launcher aktualizuje się sam, nie trzeba nic pobierać." "From now on the launcher updates itself; there is nothing to download again."
if [ "${FUSE_NOTE:-0}" = 1 ]; then
  msg "Brak libfuse2, więc program startuje w trybie rozpakowanym (nieco wolniejszy start). Pakiet libfuse2 to naprawi." \
      "libfuse2 isn't installed, so it starts in unpacked mode (a slightly slower start). Installing libfuse2 fixes that."
fi
case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *) msg "Polecenie „aerion” działa po dodaniu $BIN_DIR do PATH." "The “aerion” command works once $BIN_DIR is in your PATH." ;;
esac
