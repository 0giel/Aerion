# Aerion

A fast, themeable launcher for **Minecraft: Java Edition** — instances, mod loaders, Modrinth and
CurseForge content, modpacks — built with Tauri 2 (Rust) and Svelte 5.

> **Unofficial product.** Aerion is not approved by, endorsed by or associated with Mojang or
> Microsoft. "Minecraft" is a trademark of Mojang Synergies AB. Aerion contains no game files:
> the game, libraries, assets and Java runtimes are downloaded at run time from Mojang's servers
> (and Adoptium for missing Java builds), and you need a Minecraft licence to play.

## Features

- **Play in one click** — home screen with *Play*, every Java Edition version from the manifest
  (releases, snapshots, old versions), both argument formats, automatic Java (Mojang runtimes,
  Adoptium fallback), JVM presets (*Standard / Performance / Low RAM*) with flag validation.
- **Reliable downloads** — parallel, resumable, hash-verified (SHA-1/SHA-512), retried with
  back-off, cancellable; libraries and assets shared between instances.
- **Accounts** — Microsoft sign-in (browser with PKCE, or device code), several accounts, silent
  refresh; tokens only in the OS credential store. Offline accounts after a licensed account.
- **Instances** like Prism — wizard, groups, clone, desktop shortcuts, per-instance Java/memory/
  arguments/environment, drag & drop import, console with log levels and crash analysis
  (out of memory, wrong Java, GPU driver, mod conflicts, missing dependencies, mixin errors…).
- **Mod loaders** — Fabric, Quilt, NeoForge, Forge (all installer generations).
- **Content browser** — Modrinth and CurseForge side by side: filters, project pages, one-click
  install with dependencies, enable/disable, update check with undo, recognition of manually added
  files by hash. Resource packs, shaders, data packs.
- **Modpacks** — install from the browser, import/export `.mrpack` and CurseForge zips, plus a
  native archive format.
- **Theming** — themes are one JSON file of design tokens (+ optional CSS), applied live; seven
  built-in themes, follow-system light/dark, a theme editor with live preview, export/import,
  image/video backgrounds. See [docs/THEMING.md](docs/THEMING.md).
- **Comfort** — Ctrl+K command palette, task panel, onboarding, tray, portable mode, proxy,
  Polish and English UI.

## Status

Work in progress (version 0.1.0). Verified end-to-end on Linux (2026-10-31): vanilla 26.3,
1.21.11, 1.20.1, 1.16.5, 1.8.9 and snapshots; Fabric 1.21.11 with Sodium + Fabric API from
Modrinth; Forge 1.20.1; NeoForge 26.3; the *Fabulously Optimized* modpack (149 mods). Details:
[docs/STATUS.md](docs/STATUS.md); roadmap: [docs/PLAN.md](docs/PLAN.md).

## Before first use

1. **Microsoft sign-in** needs your own Azure application approved by Mojang —
   [docs/AZURE_SETUP.md](docs/AZURE_SETUP.md). Without it everything except signing in works.
2. **CurseForge** (optional) needs a personal API key from <https://console.curseforge.com>,
   entered in *Settings → Mods* (stored in the OS keyring) or as `CURSEFORGE_API_KEY`.
   Modrinth needs nothing.

## Build and run

Requirements: Rust ≥ 1.88 (`rustup`), Node.js ≥ 22 and pnpm; on Linux the WebKitGTK 4.1 stack:

```sh
sudo apt install libwebkit2gtk-4.1-dev libayatana-appindicator3-dev librsvg2-dev libxdo-dev libssl-dev
```

```sh
pnpm install
pnpm tauri dev      # development, hot reload
pnpm tauri build    # release build + installers (NSIS/MSI, deb/AppImage, dmg) in target/release/bundle
```

Without root on Linux, `scripts/linux-devroot.sh` unpacks the `-dev` packages into a private
prefix and `source scripts/env.sh` points the build at it (see [CONTRIBUTING.md](CONTRIBUTING.md)).

## Configuration

| Variable | Effect |
|----------|--------|
| `LAUNCHER_MSA_CLIENT_ID` | Azure client ID (run time, or build time for distributed builds) |
| `CURSEFORGE_API_KEY` | CurseForge key (otherwise entered in Settings) |
| `AERION_UA_AUTHOR`, `AERION_UA_CONTACT` | Build time: identity in the Modrinth `User-Agent` (`author/aerion/version (contact)`) |
| `AERION_HOME` | Put all launcher data in this folder (old name `LUNCHER_HOME` also accepted) |
| `LAUNCHER_DEV_OFFLINE=1` | Debug builds only: offline accounts without a Microsoft account |

See `.env.example`. Never commit a `.env` file.

**Data folder**: Windows `%LOCALAPPDATA%\Aerion`, Linux `~/.local/share/aerion`, macOS
`~/Library/Application Support/Aerion`. **Portable mode**: create an empty file named `portable`
next to the executable — data then lives in `data/` beside it.

## Troubleshooting

- **Linux: blank window.** Aerion works around two known causes automatically: broken fontconfig
  caches (symlinks between cache versions, left by newer Flatpak runtimes) and the NVIDIA DMA-BUF
  renderer. The log says when a workaround is active. To repair the font cache for every app:
  `find ~/.cache/fontconfig -name '*.cache-*' -type l -delete && fc-cache`.
- **Linux + NVIDIA: game crashes in `libnvidia-glcore`.** Aerion sets
  `__GL_THREADED_OPTIMIZATIONS=0` for the game by default; an instance variable overrides it.
- **No sound in very old versions on PipeWire** (1.8.9–1.16.5 ship an old OpenAL); the game runs.
- **"Invalid app registration"** — the Azure app awaits Mojang's approval
  ([docs/AZURE_SETUP.md](docs/AZURE_SETUP.md)).
- Launcher logs: *Settings → Data & storage → Launcher logs*; game logs: *Console* or the
  instance's *Logs* tab.

## Documentation

- [docs/PLAN.md](docs/PLAN.md) — research, architecture, milestones
- [docs/STATUS.md](docs/STATUS.md) — what is verified, running log
- [docs/DECISIONS.md](docs/DECISIONS.md) — design decisions
- [docs/THEMING.md](docs/THEMING.md) — theme format and tokens
- [docs/AZURE_SETUP.md](docs/AZURE_SETUP.md) — Microsoft sign-in setup
- [CONTRIBUTING.md](CONTRIBUTING.md) — commands and conventions for contributors

## Development

| Task | Command |
|------|---------|
| Rust tests / lint | `cargo test --workspace` · `cargo clippy --workspace --all-targets -- -D warnings` · `cargo fmt --all --check` |
| Frontend checks | `pnpm check` · `pnpm lint` · `pnpm test` |
| Regenerate IPC types | `cargo test -p aerion -- --ignored export_bindings` |
| Vanilla smoke test | `cargo run -p launcher-core --example smoke -- 26.3` |

Commits follow [Conventional Commits](https://www.conventionalcommits.org). CI runs on Windows,
Linux and macOS.

Third-party terms: Modrinth API (unique User-Agent), CurseForge 3rd-Party API Terms (no caching,
personal keys, no identity concealment through proxies), Mojang's EULA and brand guidelines.

## License

No license has been chosen yet; until a `LICENSE` file is added, all rights are reserved by the
author.
