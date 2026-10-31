#!/usr/bin/env bash
# Rootless toolchain for building the Windows version on Linux (Tauri's documented route:
# x86_64-pc-windows-msvc via cargo-xwin + NSIS). Ubuntu packages are unpacked into a private
# prefix; nothing is installed system-wide. cargo-xwin downloads the MSVC CRT and Windows SDK
# on first use (Microsoft's license applies).
#
# Usage: scripts/windows-cross.sh            (once; then build with scripts/windows-cross.sh build)
#        scripts/windows-cross.sh build      → target/x86_64-pc-windows-msvc/release/bundle/nsis/*.exe
#        scripts/windows-cross.sh <command>  → run a command with the toolchain on PATH
set -euo pipefail
BASE="$HOME/.cache/luncher-devroot"
ROOT="$BASE/wincross"
BIN="$ROOT/bin"
LLVM=20
TARGET=x86_64-pc-windows-msvc

setup() {
  mkdir -p "$BASE/wincross-debs" "$ROOT" "$BIN"
  cd "$BASE/wincross-debs"
  # Only packages missing on the host are listed; the rest is used from /usr.
  PKGS=$(apt-get -s install "clang-$LLVM" "lld-$LLVM" "llvm-$LLVM" nsis 2>/dev/null | awk '/^Inst/{print $2}')
  [ -n "$PKGS" ] && apt-get download $PKGS >/dev/null
  for f in *.deb; do dpkg-deb -x "$f" "$ROOT"; done
  local llvm_bin="$ROOT/usr/lib/llvm-$LLVM/bin"
  # clang and lld pick their mode from the name they are called by.
  ln -sf "$llvm_bin/clang" "$BIN/clang-cl"
  ln -sf "$llvm_bin/clang" "$BIN/clang"
  ln -sf "$llvm_bin/lld" "$BIN/lld-link"
  ln -sf "$llvm_bin/llvm-ar" "$BIN/llvm-lib"
  ln -sf "$llvm_bin/llvm-ar" "$BIN/llvm-ar"
  ln -sf "$llvm_bin/llvm-rc" "$BIN/llvm-rc"
  # Ubuntu's makensis has its data path compiled in (/usr/share/nsis) and ignores NSISDIR;
  # show it the private copy there through a bubblewrap mount namespace.
  cat > "$BIN/makensis" <<EOS
#!/bin/sh
exec bwrap --dev-bind / / --tmpfs /usr/share --ro-bind "$ROOT/usr/share/nsis" /usr/share/nsis \\
  "$ROOT/usr/bin/makensis" "\$@"
EOS
  chmod +x "$BIN/makensis"
  rustup target add "$TARGET"
  command -v cargo-xwin >/dev/null || cargo install cargo-xwin --locked
  echo "windows cross toolchain ready in $ROOT"
}

env_setup() {
  export PATH="$BIN:$HOME/.cargo/bin:$HOME/.local/bin:$PATH"
  export LD_LIBRARY_PATH="$ROOT/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  # Tests and examples run under Wine in a private prefix (never the user's ~/.wine).
  export WINEPREFIX="$BASE/wine"
  export WINEDEBUG=-all
}

case "${1:-setup}" in
  setup) setup ;;
  build)
    env_setup
    cd "$(dirname "$0")/.."
    pnpm tauri build --runner cargo-xwin --target "$TARGET" --bundles nsis
    ;;
  *)
    env_setup
    exec "$@"
    ;;
esac
