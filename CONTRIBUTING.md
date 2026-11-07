# Contributing to Aerion

Minecraft: Java Edition launcher and Fabric mod. Tauri 2 (Rust) + Svelte 5 + TypeScript + Vite; the mod is Java (Fabric Loom).
Bug reports and ideas: GitHub issues. Changes: a pull request against `main`; CI runs the checks below.
Plan: `docs/PLAN.md` · decisions: `docs/DECISIONS.md` · theming: `docs/THEMING.md` · file map: `docs/FILES.md` · Mojang's review: `docs/MOJANG_REVIEW.md`.

## Commands

| What | Command |
|------|---------|
| Install JS deps | `pnpm install` |
| Run the app (dev, hot reload) | `pnpm tauri dev` |
| Release (usual way) | push a tag `v<version>` (= `src-tauri/tauri.conf.json` version): `.github/workflows/release.yml` builds Linux + Windows, signs, opens a DRAFT release with `latest.json`; publish it by hand. Needs repo secrets `TAURI_SIGNING_PRIVATE_KEY`, `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`, `LAUNCHER_MSA_CLIENT_ID` |
| Mod update (usual way) | raise `version=` in `mod/gradle.properties`, commit, push a tag `mod-v<version>`: `.github/workflows/release-mod.yml` builds, signs, publishes a non-latest release and commits `mod-latest.json` to main |
| Release build + installers | `scripts/release.sh "notes"` → `release/v<version>/` (signed .deb, AppImage, Windows setup, latest.json for GitHub Releases). Bundling needs the updater key (`~/.config/aerion-signing/updater.key`, never in the repo) and the Azure client ID (`$LAUNCHER_MSA_CLIENT_ID` or `msa-client-id` next to the key) |
| Rust tests | `cargo test --workspace` |
| Rust lint | `cargo clippy --workspace --all-targets -- -D warnings` and `cargo fmt --all --check` |
| Frontend type check | `pnpm check` (svelte-check, fails on warnings) |
| Frontend lint/format | `pnpm lint` (ESLint + Prettier check), `pnpm format` |
| Frontend unit tests | `pnpm test` (vitest) |
| Regenerate IPC bindings | `cargo test -p aerion -- --ignored export_bindings` (debug runs also regenerate `src/lib/bindings.ts`) |
| End-to-end vanilla smoke test | `cargo run -p launcher-core --example smoke -- 26.3 [game-dir]` |
| Build the Aerion mod (Fabric, `mod/`) | once `scripts/build-mod.sh setup` (private JDK 25 + Gradle), then `scripts/build-mod.sh` → `src-tauri/resources/aerion-ui-<mc>.jar`, one per game version (embedded in the launcher; commit them) |
| Release a mod update without a launcher release | raise `version=` in `mod/gradle.properties`, then `scripts/release-mod.sh "notes"` → `release/mod-v<version>/` (signed jars) + `mod-latest.json`; upload the jars to a GitHub release `mod-v<version>` (not "latest"), commit and push `mod-latest.json` |
| Portable developer builds for own tests (never publish) | `scripts/export-dev.sh` → `~/Desktop/aerion-testy/{linux,windows}` |
| Regenerate the mod's font and icon images | `python3 scripts/build-mod-assets.py` (commit the results) |
| Windows build on Linux (once: `scripts/windows-cross.sh`) | `scripts/windows-cross.sh build` → `target/x86_64-pc-windows-msvc/release/bundle/nsis/*-setup.exe` |
| Windows tests under Wine | `scripts/windows-cross.sh cargo xwin test -p launcher-core --target x86_64-pc-windows-msvc` |

### Linux without root access
- WebKitGTK dev headers can come from a rootless "devroot" (`scripts/linux-devroot.sh` →
  `~/.cache/luncher-devroot/root`); `scripts/env.sh` points pkg-config at it. With
  `sudo apt install libwebkit2gtk-4.1-dev libayatana-appindicator3-dev librsvg2-dev libxdo-dev libssl-dev`
  the devroot is unnecessary.
- Release bundles with the devroot: the devroot's `.pc` files point at the devroot and
  linuxdeploy's GTK plugin reads `gtk+-3.0.pc`, so two overrides live in
  `~/.cache/luncher-devroot/pc-override` (`ayatana-appindicator3-0.1.pc` without `Requires`,
  `libdir=/usr/lib/x86_64-linux-gnu`; `gtk+-3.0.pc` with `prefix=/usr`). Build with
  `export PKG_CONFIG_ALLOW_SYSTEM_LIBS=1 PKG_CONFIG_PATH=$HOME/.cache/luncher-devroot/pc-override:$PKG_CONFIG_PATH; pnpm tauri build --bundles deb,appimage`,
  then `scripts/perf-linux.sh 3` (close other Aerion windows first).
- `LAUNCHER_DEV_OFFLINE=1` (debug builds only) enables offline accounts without a Microsoft login.
- `AERION_HOME=/some/dir` relocates all launcher data (useful for clean-state tests; the old `LUNCHER_HOME` still works).

## Architecture
- `crates/launcher-core` — the whole engine, **no Tauri dependency**: `net` (HTTP client, disk
  JSON cache with ETag, parallel resumable verified downloader), `mojang` (manifest, version JSON +
  `inheritsFrom` merge, rules, libraries, assets, arguments), `java` (detection, Mojang/Adoptium
  runtimes, JVM presets, flag probing), `game` (install, launch, log4j XML parsing, crash analysis),
  `auth` (MSA PKCE/device code → XBL → XSTS → Minecraft), `accounts` (keyring-backed),
  `instances`, `loaders` (Fabric/Quilt meta, Forge/NeoForge installers incl. processors),
  `mods` (`ModProvider` trait, Modrinth, CurseForge, selection, resolver, `content.json`),
  `modpacks` (mrpack, CurseForge zip, native archive). IPC-visible types derive `specta::Type`
  behind the `specta` feature.
- `src-tauri` — thin layer: `commands/*` (typed via tauri-specta), `tasks.rs` (background tasks →
  `TaskEvent`, progress sampled at 150 ms), `games.rs` (process supervision, log batching,
  crash analysis), `protocols.rs` (`img://` image proxy with disk cache, `themeasset://`),
  `themes.rs` (user theme files + watcher).
- `mod` — the Aerion mod (Java, Fabric Loom, Mojang names): menus in the launcher theme, HUD modules,
  in-game HUD editor, zoom/freelook/fullbright. `common` is shared; `v26_3` and `v1_21_11` hold what
  differs per game version (`platform/*`, version mixins), one jar each. Client only, no Fabric API;
  loaded with `-Dfabric.addMods`, reads `<root>/aerion-mod/theme.json` and `hud.json`.
- `src` — Svelte 5 (runes). `lib/api.ts` aliases generated types (`X_Serialize` → `X`);
  `lib/stores/*` global state; `lib/theme/*` token engine; `lib/i18n/*` (en/pl, typed);
  pages are code-split (`App.svelte`).

## Conventions
- Code, comments, commits, technical docs: English. UI: i18n (`src/lib/i18n/en.ts` + `pl.ts`;
  `pl` must match `en`'s shape — TypeScript enforces it). Error codes from Rust (`ErrorInfo.code`)
  map to `errors.<code>`; crash kinds to `crash.kinds.<kind>`.
- Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:`, `test:`…).
- Components never hard-code colors/radii/durations — only CSS variables from the theme.
- Never log or persist secrets: tokens and the CurseForge key live only in the OS keyring;
  `CommandSpec::redacted()` masks the access token; game log lines are masked too.
- CurseForge API data must not be cached (their terms §3.1): the CF client bypasses `HttpCache`
  and the image proxy is called with `nc=1` for CF images.
- Untrusted paths (archives, modpack indexes, file names from APIs) always go through
  `util::fs::safe_join` / `safe_file_name`.
- Process arguments are arrays (`tokio::process::Command::args`), never shell strings.
- After changing command signatures or IPC types: regenerate bindings and run `pnpm check`.
