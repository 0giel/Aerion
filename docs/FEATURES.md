# Features: what Aerion does, where it lives, what is verified

A map for anyone picking the project up: every feature, the code behind it, how far it has been
checked, and how it got here. File-level detail is in [`FILES.md`](FILES.md), the reasons behind
the design in [`DECISIONS.md`](DECISIONS.md), user-facing notes per release in
`src/lib/changelog.ts`, the running test log in [`STATUS.md`](STATUS.md).

Latest releases: launcher **1.3.0** (2026-11-17), Aerion mod **1.2.0** (2026-11-18, a mod-only
release that launchers 1.3.x download by themselves), Aerion Screens **1.2.0** on Modrinth
(submitted for review 2026-11-18).

## 1. The three products

| Product | What it is | Built from | Shipped as |
|---|---|---|---|
| Aerion launcher | Tauri 2 app (Rust + Svelte 5) for Minecraft: Java Edition | `crates/launcher-core`, `src-tauri`, `src` | GitHub release `v<version>` (Linux .AppImage/.deb, Windows setup + AerionSetup.exe), self-updating via `latest.json` |
| Aerion mod ("Aerion UI", id `aerion`) | Fabric client mod: the launcher's theme in the game, HUD, client features | `mod/common`, `mod/v26_3`, `mod/v1_21_11` | Bundled in the launcher (`src-tauri/resources/aerion-ui-<mc>.jar`); updates without a launcher release through tag `mod-v<version>` and `data/mod-latest.json` |
| Aerion Screens (id `aerion_screens`) | Windows of the computer in the world, shared with players nearby; one jar for players and servers | `mod/screens`, `mod/screens_v26_3`, `mod/screens_v1_21_11` | Nested inside every Aerion mod jar (jar-in-jar); on its own in `release/screens/`, GitHub mod releases and https://modrinth.com/mod/aerion-screens |

Supported game versions for both mods: **26.3** and **1.21.11** (Fabric, no Fabric API needed).

## 2. Launcher

| Area | What the player gets | Code |
|---|---|---|
| Start | Floating-island scene in the theme's colours (per-instance biome: meadow, winter, desert, nether), player model, Play button with instance menu, recent and favourite instances | `src/lib/pages/Home.svelte`, `SkyIsland.svelte`, `SkinViewer` |
| Menu | Sidebar panel or a hotbar at the bottom (default for new settings, keys 1–9 and the wheel); first-start wizard picks theme and menu | `App.svelte`, `Onboarding.svelte` |
| Instances | Create (vanilla, Fabric, Quilt, Forge, NeoForge, "Fabric + Aerion", experimental Horizon), clone, groups, icons, favourites, right-click actions, import from other launchers, `.mrpack`/CurseForge/own modpacks, **instance codes** (`aerionI1:` short text that rebuilds an instance; Export → Code, Instances → Import → Paste code) | `Instances.svelte`, `InstanceDetail.svelte`, `src/lib/instanceCode.ts`, `commands/instances.rs`, `commands/mods.rs` (`install_shared_content`) |
| Mods | Modrinth + CurseForge browser, quick install with dependencies, any version (pinned), updates in the background and "Update everything" (worlds backed up first), replacements for missing mods, pre-launch check (duplicates, missing deps, conflicts), performance pack in one click (Sodium, Lithium, FerriteCore, ImmediatelyFast, EntityCulling) | `pages/mods/`, `launcher-core/src/mods/` (`boost.rs` holds the pack) |
| Accounts | Microsoft sign-in (Mojang-approved app id), offline accounts in debug builds, tokens in the OS keyring | `launcher-core/src/auth`, `accounts.rs` |
| Skins | Library, capes, 3D viewer, **skin editor** (pencil, eraser, picker, fill, undo) | `Skins.svelte` |
| Friends | Names with faces, who is hosting, *Join* (e4mc addresses found in pasted messages) | `Friends.svelte`, `launcher-core/src/friends.rs` |
| Statistics, Screenshots | Play time per instance; a gallery of all instances' screenshots | `Stats.svelte`, `Screenshots.svelte` |
| Performance | Live CPU/RAM/GPU, after-game summary (FPS, memory peak, hints with fix buttons), **Tune automatically** (memory, render distance, speed-up mods), the Aerion mod's own cost per frame | `Performance.svelte`, `launcher-core/src/perf.rs`, `sessions.rs` |
| Console | Game log with crash analysis (points at the likely mod, can disable it) | `Console.svelte`, `launcher-core/src/game/crash.rs` |
| Settings | Appearance (themes, theme editor and **theme codes** `aerion1:`, video/GIF backgrounds, cards incl. "Blocks", menu position, lite mode), languages (en, pl, German beta), shared game options between instances, data export/import, Discord status, updates | `Settings.svelte`, `ThemeEditor.svelte`, `src/lib/theme/`, `src/lib/i18n/` |
| HUD in the launcher | Edit the in-game HUD layout and modules from the launcher | `HudEditor.svelte`, `aerion_mod/hud.rs` |
| Updates | Signed self-update; package-managed installs (AUR/winget drafts) turn it off with `/usr/lib/aerion/no-self-update` or `AERION_NO_SELF_UPDATE=1` | `commands/updates.rs`, `packaging/` |

## 3. Aerion mod (in the game)

Reads `<root>/aerion-mod/theme.json` and `hud.json` (the launcher writes both; a running game picks
changes up within a second). Menus: **Right Shift** opens the HUD layout, its *Mods* button the
menu (sections, module tiles, a search over all settings at the top).

| Area | What it does | Code |
|---|---|---|
| Theme in the game | Menus, buttons, inventories, tooltips, chat, hotbar, player list and furnace/brewing progress in the launcher's colours; title screen with aurora / panorama / your world, "Host a world" (LAN + e4mc), Look panel | `common/.../Theme.java`, `mixin/`, `features/WorldLook.java` |
| HUD | 30+ modules (FPS, CPS, coordinates, armor, potions, keystrokes, minimap, map with memory of explored terrain, reach, combo, stopwatch, …), looks (Minimal, Glass, Neon, Retro), anchors, auto-hide by situation with show time, HUD profiles | `hud/Hud.java`, `HudEditor.java`, `Profiles.java`, `Situations.java`, `MapMemory.java` |
| Features | Zoom, freelook, fullbright, hitboxes, sound editor, overlays, crosshair, block outline, light numbers, chunk borders, TNT timer, waypoints (also shared from chat with a `[+]`), streamer mode (F7), quick wheel (`), shulker box preview (hold Shift), food values (AppleSkin-like), enchantment glint colour, fewer particles, badges | `features/` |
| Keys | All in the game's controls and the launcher's key editor (names `key.aerion.*`) | `features/Features.java` |
| Cost meter | Time the mod spends per frame, by part; shown on the launcher's Performance page | `Cost.java`, status file `costMs`/`costParts` |
| Windows in the world | Comes from Aerion Screens (section 4). Aerion plugs in its settings (`hud.json` feature `mirrors`), its themed picker and typing page, and its cost meter | `mirror/ScreensBridge.java`, `MirrorsPage.java`, `TypePage.java`, menu section "Okna" in `AerionMenu.java` |

## 4. Aerion Screens

**What players get:** press **J** for the window picker; a chosen window hangs in front of you
(curved or flat). Look at it to click and scroll; **U** gives the window the keyboard (every key,
shortcuts and accented letters go straight to it; Esc or a click returns to the game); drag its bar or hold
**Y** to move; − and + resize; pin it to yourself; **K** arranges all around you, **R** hides them.
*Share* sends the picture (JPEG) and the sound to players nearby who have Aerion Screens; the
owner chooses who may watch (everyone or nicks). Others' windows can be switched off.

**Where it runs:** a world opened to LAN (the host relays by itself) or a server with the same jar.
Server settings: `config/aerion-screens.properties` (range 48, fps 5, kbps 6000, maxPanels 3,
maxFrameKb 500). Client settings on its own: `config/aerion-screens-client.json`; with Aerion they
live in `hud.json`. Saved windows: `aerion-mirrors.json` in the game folder (per world/server key).

| Part | Code |
|---|---|
| Messages (`Proto.VERSION = 1`, keep it: 1.3.0 players and servers speak it) | `screens/.../net/Proto.java`, `ScreenPayload.java`, `mixin/DiscardedPayloadMixin.java` |
| Relay (server and hosted worlds): limits, range, sound only to players within 26 blocks, nick lists | `screens/.../screens/Relay.java`, `Config.java`, `mixin/` |
| Windows and panels | `screens/.../client/Mirrors.java` |
| Window sources | `X11Source` (Linux X11/XWayland: clicks and the wheel as sent X events; GTK programs such as Firefox and GNOME's ignore those, so for them the wheel becomes arrow keys; typing hands the window the X keyboard focus and watches Esc in the keymap; single keys go through XTest), `Win32Source` (Windows: window messages), `PortalSource` + `assets/aerion_screens/portal-capture.py` (Wayland, view only, through xdg-desktop-portal; force on X11 with `AERION_PORTAL=1`) |
| Sound | `WindowAudio` (Linux: PipeWire `pw-record` of the window's program; Windows: `WinLoopback`, WASAPI process loopback), IMA ADPCM (`Adpcm`), playback panned and fading over 24 blocks with a 0.2 s store (`RemoteAudio`) |
| Sharing | `ScreenShare` |
| Hooks for other mods | `Screens` (API 1: settings, menus, cost), `ScreensClient` (keys, tick, `active()`, `openPicker()`) |
| Own menus (game look, both versions) | `client/ui/` (`PickerScreen`, `SettingsScreen`, `AllowScreen`, `TypeScreen`) |
| Texts without Fabric API | `client/Lang.java` + `mixin/LanguageMixin.java` (the game reads mods' language files only with Fabric API) |
| Per version | `screens_v*/.../Platform.java`, `RenderMixin`, `CrosshairMixin` |

**Compatibility rules (don't break):** one copy of the code at runtime. Aerion carries Screens
nested; a copy in `mods/` wins (even an older one), so Aerion checks `Screens.API` and never
hard-depends. Screens `breaks` Aerion < 1.2.0, which had the same classes built in (Fabric then
shows a clear message instead of a crash). Key names, `aerion-mirrors.json` and the protocol stay
as they are.

## 5. Releasing

- **Launcher:** bump versions (package.json, tauri.conf.json, Cargo.toml), add a `changelog.ts`
  entry, all checks green, push `main`, tag `v<version>`; `release.yml` builds a draft (check both
  platforms, the client-ID steps, one draft, `latest.json`, AerionSetup.exe), attach
  `release/screens/*.jar`, set the bilingual body, publish.
- **Mod only:** raise `version=` in `mod/gradle.properties`, push, tag `mod-v<version>`;
  `release-mod.yml` signs and publishes and commits `data/mod-latest.json` to `main` (pull before
  verifying with `AERION_VERIFY_JARS=<jars> cargo test -p launcher-core published_manifest -- --ignored`).
  Attach `release/screens/*.jar`.
- **Modrinth (Aerion Screens):** project `XFYfzVcR`, one version per game version
  (`1.2.0+26.3`, `1.2.0+1.21.11`), environment "client, optional on server", disclosures: AI-generated
  content and external system interactions. Uploaded by hand through the API for now.
- **Site:** `site/` deploys from `main` (Pages) on every push.

## 6. Verified, and not yet

Checked on Linux in a private test setup (Xvfb, local servers, two clients): everything in
sections 3 and 4, both game versions, Aerion ↔ standalone Screens both ways, both installed
together, 1.3.0 mod ↔ new Screens, dedicated servers 26.3 and 1.21.11 with only the Screens jar,
the Wayland path with a stand-in PipeWire stream.

Not verified yet:
- anything about windows on real Windows (capture, clicks, icons, WASAPI sound);
- the real Wayland dialog (the owner's GNOME on X11 has the portal too; try with `AERION_PORTAL=1`);
- clicks into GTK programs (Firefox, GNOME apps): they ignore sent pointer events and the game holds the real pointer, so clicks there may not work; the wheel there moves like the arrow keys (scrolls once the caret reaches the edge in text editors);
- typing on Windows: keys still go as window messages one by one (no keyboard hand-over yet);
- the Realms news icon hidden with the Realms button (the icon needs a Realms login to show at all);
- the Tab list theming in multiplayer; a German mod (the mod knows en/pl only).

## 7. Change history in short

| Version | Main changes |
|---|---|
| 0.1–0.3 | Engine, instances, loaders, mod browser, themes; the Aerion mod with HUD and client features |
| 1.0.x | Microsoft sign-in, new mod menu, title screen, friends, gallery, performance summary, HUD profiles, map, Windows installer (AerionSetup), themed inventories, lite mode |
| 1.1.x | "Sky island" redesign, new logo, performance pack in one click, per-instance island |
| 1.2.0 | Hotbar menu (default), look step on first start, Blocks cards, minimap/map upgrade, new modules, AppleSkin-like food |
| 1.3.0 | Instance codes, Tune automatically, mod cost meter, skin editor, theme codes, German (beta); in the mod: windows in the world with sharing and sound, streamer mode, quick wheel, shulker preview, map memory, waypoints from chat, Look panel, settings search, glint colour |
| mod 1.2.0 | Aerion Screens as its own mod (jar-in-jar in Aerion, standalone on Modrinth, game-look menus), Wayland windows (view only), relay fixes (far windows, sound range), smoother others' sound, nick warning on offline servers |

Full per-release notes: `src/lib/changelog.ts` (shown in the launcher's "What's new").

## 8. The master plan (2026-11-19) against the code

The owner's development plan ("Aerion Master Plan Rozwoju") was written from the public docs, not
the code. Where each part stands, checked 2026-11-21:

| Plan section | State | Notes |
|---|---|---|
| 2. Inventory | Done | This file, `FILES.md`, `DECISIONS.md`, `STATUS.md`. Not yet: a list of user data files and their formats. |
| 3.2 Error model | Done | `ErrorInfo.code` from Rust, mapped to `errors.<code>` in the UI; no error-text parsing in Svelte. |
| 3.3 Cancellation | Mostly done | Downloads: cancellation token, `.part` resume, hash check, mirror fallback, all tested (`net/download.rs`). Not checked: a cancelled install leaving a half-made instance. |
| 3.5 Launching | Done | Arguments are arrays, never shell strings; logs in a bounded ring buffer; token masked. |
| 4.1–4.2 IPC, capabilities | Done | Minimal capabilities (`capabilities/default.json`); no shell or file-system plugin; devtools only in debug builds. |
| 4.3 CSP | Done | Strict; `style-src 'unsafe-inline'` stays (Svelte styles). |
| 4.4 Own protocols | Done | `localimg`, `themeasset` accept only checked names; Windows drive letters blocked in instance ids (2026-11-21). |
| 4.5 Image proxy | Done 2026-11-21 | Public addresses only after DNS (names and redirects to the local network refused), 5 redirects, size counted while reading. |
| 5. Auth, tokens | Done | PKCE/device code; tokens only in the OS keyring; masked in logs. |
| 6. Updater | Done | Tauri updater (minisign signature, newer versions only); mod jars signed and verified; key never in the repo. Not done: key rotation plan, rollback. |
| 7. Archives, modpacks | Done 2026-11-21 | `safe_join` everywhere (zip-slip tested); 64 MiB per in-memory entry, 200 000 files and 16 GiB per extraction. |
| 8. Fabric client | Partly | Two versions tested in game; Windows and Wayland dialog not (section 6). |
| 9. Svelte, UX | Partly | Virtualised console, themes, lite mode; no full keyboard/a11y audit. |
| 10. Performance | Partly | `scripts/perf-linux.sh`, in-game cost meter, release-to-release comparison (`claude-harness/perf`). No long-run or 100-instance tests. |
| 11. Config, migrations | Partly | Atomic writes (synced to disk), damaged files kept as `.broken` (settings, HUD, accounts), new fields get defaults. No `schemaVersion` yet: needed only before the first breaking format change. |
| 12–14. Plugins, Studio, Hub | Not planned now | Studio exists in part (theme and HUD editors, theme codes, gallery). Plugins and a Hub need a server and moderation; Fabric plugins can't be sandboxed. |
| 15. CI | Done | fmt, clippy, tests, svelte-check, ESLint/Prettier, vitest; release workflows check the client ID and signatures. Not yet: `cargo audit`/`cargo deny`. |
