# Project files

What lives where. Architecture and conventions: [../CONTRIBUTING.md](../CONTRIBUTING.md).

## Top level

| Path | What it is |
|---|---|
| `crates/launcher-core/` | The engine, no Tauri dependency |
| `src-tauri/` | Thin desktop layer: commands, tasks, window |
| `src/` | The interface (Svelte 5) |
| `mod/` | The Aerion mod for Fabric (Java) |
| `scripts/` | Builds and releases |
| `docs/` | Documentation |
| `release/` | Output of `release.sh` (not committed) |
| `target/`, `node_modules/` | Build output and packages (not committed) |

## `crates/launcher-core/src`

| Path | What it does |
|---|---|
| `net/` | HTTP client, disk cache with ETag, resumable verified downloads |
| `mojang/` | Version manifest and JSON, rules, libraries, assets, arguments |
| `java/` | Finding and downloading Java, JVM presets, flag checks |
| `game/` | Install, launch, log parsing, crash analysis, log sharing |
| `auth/` | Microsoft → Xbox → XSTS → Minecraft sign-in |
| `accounts.rs` | Accounts; tokens only in the OS keyring |
| `instances/` | Instances, launch planning, worlds and backups |
| `loaders/` | Fabric, Quilt, Forge, NeoForge |
| `mods/` | Modrinth, CurseForge, resolver, health check, replacements |
| `modpacks/` | mrpack, CurseForge zip, native archive |
| `aerion_mod.rs`, `aerion_mod/hud.rs` | Installing the mod's jars; `theme.json` and `hud.json` |
| `importers.rs` | Importing from other launchers |
| `perf.rs` | Measurements for the Performance page |
| `settings.rs`, `paths.rs`, `error.rs`, `engine.rs` | Settings, data folders, errors, shared setup |

## `src-tauri`

| Path | What it does |
|---|---|
| `src/commands/*.rs` | Commands the interface calls, by area |
| `src/tasks.rs` | Background tasks and their progress |
| `src/games.rs` | Running games: supervision, logs, crash analysis |
| `src/protocols.rs` | `img://` image proxy and `themeasset://` |
| `src/themes.rs` | User theme files and their watcher |
| `src/content_watch.rs` | Background mod update checks |
| `src/platform.rs` | Per-system workarounds |
| `src/state.rs` | Shared state; embeds the mod's jars |
| `resources/aerion-ui-<mc>.jar` | Built mod, embedded in the launcher (commit it) |
| `tauri.conf.json` | Window, version, updater |

## `src`

| Path | What it does |
|---|---|
| `App.svelte`, `app.css` | Shell and shared styles |
| `lib/bindings.ts` | Generated from Rust; don't edit by hand |
| `lib/api.ts` | Type aliases and small helpers over the bindings |
| `lib/changelog.ts` | "What's new" entries |
| `lib/i18n/` | `en.ts`, `pl.ts` |
| `lib/theme/` | Theme engine, in-game theme export |
| `lib/stores/` | Global state, router, toasts, dashboards |
| `lib/components/` | Shared components |
| `lib/pages/` | Pages; `instance/` and `mods/` hold their parts |
| `lib/pages/HudEditor.svelte`, `lib/hud/` | HUD and game features page |
| `lib/dashboard/` | Experimental widgets |

## `mod`

| Path | What it does |
|---|---|
| `common/.../hud/Hud.java` | HUD modules and how they are drawn |
| `common/.../hud/HudEditor.java` | Layout screen (Right Shift) |
| `common/.../hud/AerionMenu.java` | Mods menu |
| `common/.../hud/HudConfig.java` | `hud.json` and defaults (keep in step with `aerion_mod/hud.rs`) |
| `common/.../hud/Minimap.java`, `Compass.java`, `Cps.java` | Parts of modules |
| `common/.../features/` | Zoom, freelook, hitboxes, sounds, overlays, crosshair, light numbers, block outline, chat, time and weather, hit colour, screen background, waypoints |
| `common/.../Draw.java`, `Txt.java`, `Icons.java`, `Colors.java`, `Chroma.java` | Drawing, smooth text, icons, colours |
| `common/.../Theme.java`, `Logo.java`, `Particles.java`, `Lang.java` | Theme, logo, particles, the mod's texts |
| `common/.../mixin/` | Hooks shared by all game versions |
| `v26_3/`, `v1_21_11/` | What differs per version: `platform/` and `mixin/version/` |
| `common/src/main/resources/assets/aerion/` | Generated font and icon images, logo |

## `scripts`

| File | What it does |
|---|---|
| `env.sh` | Build environment on this machine |
| `release.sh` | Signed installers and `latest.json` in `release/v<version>/` |
| `export-dev.sh` | Portable developer builds for the owner's tests; never publish |
| `build-mod.sh` | Builds the mod and copies the jars to `src-tauri/resources/` |
| `build-mod-assets.py` | Regenerates the mod's font and icon images |
| `windows-cross.sh` | Windows build on Linux |
| `linux-devroot.sh`, `perf-linux.sh` | Rootless headers; performance measurement |

## `docs`

| File | What it covers |
|---|---|
| `PLAN.md` | Milestones and next steps |
| `STATUS.md` | What was checked, newest first |
| `DECISIONS.md` | Why things are the way they are |
| `AZURE_SETUP.md` | Azure app and sign-in errors |
| `MOJANG_REVIEW.md` | What to do depending on Mojang's answer |
| `THEMING.md` | Theme format |
| `PERFORMANCE.md` | Measurements |
| `FILES.md` | This file |

## Outside the repository

| Path | What it is |
|---|---|
| `~/.config/aerion-signing/updater.key` | Updater signing key; never share it, keep a backup |
| `~/.config/aerion-signing/msa-client-id` | Azure client ID for builds |
| `~/.local/share/aerion` | Data of the installed launcher |

Data folders at run time: `aerion-mod/hud.json`, `aerion-mod/theme.json`; per game folder:
`aerion-waypoints.json`, `aerion-toggles`, and key bindings in `options.txt`.
