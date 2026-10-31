# Work status

Running log of what is verified; milestones and next steps live in [`PLAN.md`](PLAN.md).
Newest entries first.

## 2026-10-31

### Fixed: blank window on Linux (WebKitGTK hang)
- Symptom: `pnpm tauri dev` showed only the native background colour; no JS ran.
- Root cause (not our code — the system `MiniBrowser` hung the same way): 35 of the 52
  `~/.cache/fontconfig/*-le64.cache-9` files on this machine are **symlinks to `*-le64.cache-12`**
  files written by a newer fontconfig (probably a Flatpak runtime). The system fontconfig 2.15 reads
  them as its own format and `WebKitWebProcess` then spins at 100 % CPU before requesting the page.
  Found by elimination: graphics env vars, allocator, JIT and sandbox made no difference; a fresh
  `XDG_CACHE_HOME` worked; bisecting the cache files pinned it to cache-9 + cache-12 together.
- Fix (`src-tauri/src/platform.rs`): at start-up on Linux the launcher counts cache files that are
  symlinks to a different cache version; if any exist it writes a copy of `/etc/fonts/fonts.conf`
  whose per-user cache dir points to `<data>/cache/fontconfig` and sets `FONTCONFIG_FILE`. The
  user's cache is never modified; a warning with the manual fix is logged
  (`delete those links and run fc-cache`). Unit-tested. The NVIDIA DMA-BUF workaround moved there too.
- Result: page load starts 0.2 s after process start, `UI ready` at ~0.6 s.

### Verified through the UI (dev build, this machine)
- Onboarding: language switch (pl/en), sign-in step (shows the Azure client-ID notice), "first
  instance" creates **Minecraft 26.3**, finish → home.
- Accounts: dev offline account (`LAUNCHER_DEV_OFFLINE=1`) added and selected.
- **Play from the home screen: 26.3 started with Java 25 (Mojang runtime) and reached the title
  screen** (log: sound engine started, texture atlases created). Expected offline-only errors:
  Realms/user-properties 401. Stop button killed the game; play time was recorded.

### Verified: Fabric + mods from Modrinth (M4/M5)
- Wizard: new instance "Fabric 1.21.11", loader list from Fabric meta (latest stable 0.20.1 preselected).
- Mod browser (Modrinth): search filtered to the instance (1.21.11 · Fabric), icons via the image
  proxy, project page with sanitised description and metadata; install plan → **Sodium 0.8.14** and
  **Fabric API 0.141.6** installed, provenance recorded in `content.json`.
- **Launch: Fabric 1.21.11 with 49 mods reached the title screen** (~13 s incl. loader install,
  Java 21); Sodium loaded and applied its NVIDIA workarounds. Loader version is pinned on first launch.
- Enable/disable toggles rename to/from `.jar.disabled`.
- **Fixed:** "Check updates" offered `Sodium 0.8.15-beta.1` for a stable install (Modrinth's
  `version_files/update` ignores channels). Entries now record their release channel
  (`release`), and updates never move to a less stable channel — falls back to the newest version
  on an allowed channel. Wiremock test `updates_keep_the_release_channel`.

### UI fixes from the walkthrough
- Onboarding card was stuck in the left grid track when the sidebar is hidden → `main` always uses
  the last track.
- Player model rewritten from CSS 3D to a **2D canvas with an orthographic projection** (WebKitGTK
  without GPU compositing flattens `preserve-3d`, so the back faces covered the front). Back-face
  culling + depth sort; ~30 fps idle sway, paused when hidden; drag to rotate.
- Default skin when an account has none: Steve, fetched at runtime from `textures.minecraft.net`
  through the image proxy (nothing bundled).
- "26.3 · 26.3" under Play → `instanceTitle()` drops the duplicate.
- Restoring the window after the game exits: Mutter (X11) ignores de-iconify, so a minimised window
  is hidden and re-shown first (`show_main`).

### Dev tooling added (debug builds only)
- Page-load events in the log; page console mirrored to stdout (`CONSOLE …`, Linux).
- `LUNCHER_DEV_EVAL=<file>`: the file's JS is evaluated in the window whenever it changes — used to
  script UI walkthroughs without moving the mouse.
- `LUNCHER_DEV_ON_TOP=1`: window stays on top for `xwd -name Luncher` screenshots.

## 2026-10-30
- **Engine (`crates/launcher-core`)** — builds, `cargo clippy -D warnings` clean, 82 unit/integration
  tests green (fixtures from live APIs, wiremock for HTTP).
- **Vanilla launch end-to-end** (smoke tool `cargo run -p launcher-core --example smoke -- <ver>`):
  26.3 (Java 25), 1.21.11 (Java 21), 1.20.1 (Java 17), 26.4-snapshot-6, 1.16.5 and 1.8.9 (Java 8)
  all reach the title screen. On this machine old OpenAL builds (1.8.9, 1.16.5) cannot open the
  PipeWire/PulseAudio device — sound only, game runs.
- Implemented in code (unit-tested, not yet exercised through the UI): Microsoft login chain
  (PKCE + device code, XBL/XSTS error mapping), keyring accounts + offline gating, instances,
  Fabric/Quilt, Forge/NeoForge installer processors, Modrinth + CurseForge providers, dependency
  resolver, content manifest with updates/undo, mrpack/CurseForge/native import & export.
- **Tauri shell** — 61 typed commands + 4 events (tauri-specta), image proxy, theme watcher, tray.
- **Frontend** — all pages written; `pnpm check`, `pnpm lint`, `pnpm build` clean.

## Not started yet
- `docs/DECISIONS.md`, `docs/THEMING.md` (+ `themes/theme.schema.json`), `docs/AZURE_SETUP.md`,
  `docs/PERFORMANCE.md`, `README.md`; vitest tests for i18n/theme schema.
- Live UI tests: Fabric + Sodium/Fabric API install, Forge/NeoForge launches, modpack import.
- Release bundles (`pnpm tauri build`), performance measurements.
- Owner actions: Azure app registration + Mojang review (client ID), CurseForge API key.
