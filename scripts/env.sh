#!/usr/bin/env bash
# Development environment for Linux machines without root access (see CONTRIBUTING.md).
# Usage: source scripts/env.sh   (or: scripts/env.sh <command...>)
#
# Uses the toolchains installed in $HOME (rustup, Node, pnpm) and, if present, the rootless
# WebKitGTK "devroot" created by scripts/linux-devroot.sh. With the official dev packages
# installed (sudo apt install libwebkit2gtk-4.1-dev ...), the devroot is simply unused.
DEVROOT="$HOME/.cache/luncher-devroot/root"
export PATH="$HOME/.cargo/bin:$HOME/.local/bin:$PATH"
if [ -d "$DEVROOT" ]; then
  LIB="$DEVROOT/usr/lib/x86_64-linux-gnu"
  export PKG_CONFIG_PATH="$LIB/pkgconfig:$DEVROOT/usr/share/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
  # Runtime libraries that only exist in the devroot (e.g. libxdo).
  export LD_LIBRARY_PATH="$LIB${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
fi
if [ "${BASH_SOURCE[0]}" = "$0" ] && [ $# -gt 0 ]; then
  exec "$@"
fi
