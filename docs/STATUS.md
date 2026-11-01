# Work status

Running log of what is verified; milestones and next steps live in [`PLAN.md`](PLAN.md).
Newest entries first.

## 2026-10-31

### 0.1.9: What's new, crash help, Aerion mod preview (D28, D29)
- **What's new**: notes per version embedded in `src/lib/changelog.ts` (pl/en), shown once after
  an update; `settings.lastSeenVersion` remembers it, fresh installs see nothing. Checked live:
  the dialog appeared for test data without the field and was not shown again.
- **Crash help**: `mods::meta` reads mod ids/names/mixin configs from jars; crash analysis maps
  the suspected mod to its file. The crash card offers "Disable this mod and relaunch" and
  "Share log" (mclo.gs after confirmation, tokens and home-folder names removed). Checked end to
  end with a deliberately broken Fabric mod in a 26.3 instance: Fabric's own error window, then
  the card named "Broken Test · broken-test-1.0.jar"; the button renamed it to `.disabled` and
  the game started. Live upload to mclo.gs not done (it publishes the log); covered by unit tests.
- **Aerion mod (preview)**: `mod/` builds `aerion-ui.jar` (21 KB) for Fabric 26.3; instance
  setting "Aerion mod" (Fabric 26.3 only, off by default). Checked in game: loads next to Fabric
  without Fabric API, all mixins applied, title screen shows the theme gradient and themed
  buttons; switching Emerald → Rose in the launcher restyled the running game within a second;
  Light is dimmed in game so Minecraft's white text stays readable.
- Owner's follow-up: menu particles like the launcher (theme's sparkles/dust/snow), the Aerion logo
  instead of the Minecraft logo (glyph rasterised into GUI pixels + "Aerion" in the game font,
  so it is sharp at every GUI scale and needs no texture loading without Fabric API), and
  switches for colours / particles / logo in Settings → Appearance → In game (`settings.gameUi`,
  passed in theme.json). Checked in game: logo and sparkles on Nocturne; turning the logo off in
  the launcher brought the Minecraft logo back in the running game within a second.
- Owner: the logo looked jagged. Replaced the pixel-built logo with smooth textures (rendered
  from the launcher glyph + Inter in headless Brave, linear sampling). Checked in game at GUI
  scale 2: clean edges, soft shadow, accent sparkle.
- Owner: offer the mod when creating an instance. New instance → loader "Fabric + Aerion"
  (next to Fabric, enabled only for versions in `aerion_mod::GAME_VERSIONS`, now sent in
  `AppInfo.aerionModVersions`) creates a Fabric instance with the mod on; suggested name
  "Aerion <version>". Loaders are a 3×2 grid so six choices fit. Unit test: the flag only sticks
  for Fabric.
- New GitHub screenshots (English UI, Nocturne) showed a ~150 px gap above the in-game switches:
  `.layout-text` had `flex: 1 1 14rem`, which in a column became a height. Now only beside buttons.
- Dev-only gotcha: after many dev-server restarts WebKit's HTTP cache of the test profile served
  stale modules and vite-plugin-svelte logged "failed to load virtual css module" (unstyled
  sidebar/home). Clearing `~/.local/share/pl.aerion.devtest/WebKitCache` fixed it; release builds
  are not affected (assets are bundled).

### 0.1.8: bug pass and calmer settings (D27)
- Player model leaned when turned: the view pitch was applied in the model's frame before the
  yaw, so from the side or back the camera tilt became a sideways lean. The camera now turns the
  model first and tilts the view afterwards (upright from every side). The cape is painted by
  rule (behind the body from the front, over body and limbs from behind, under the head) because
  face-centre depth sorting put a leg over it. A model dragged to face away keeps its head
  straight instead of twisting it towards the cursor. Checked in a headless browser at 0°, 40°,
  90°, 140°, 180° and 230°.
- Walkthrough of every page in a dev instance with a layout audit (clipped text, off-screen
  controls, overlapping buttons) and screenshots. Fixed:
  - Command palette showed fixed Ctrl+1…8 hints that no longer matched the arranged sidebar;
    it now lists pages in sidebar order and numbers only the visible ones (as the shortcuts do).
  - Statistics showed 0 sessions next to minutes of play: launches are counted since 0.1.4, so
    an instance played before counts as at least one session (unit test). "Favourite versions"
    renamed "Most played versions" (favourites are a different feature).
  - Instance tiles: long names ran under the favourite star; Play buttons were misaligned when a
    tile had a group badge.
  - Mods: CurseForge buttons without an API key were silently disabled and a hint repeated on
    every visit; now they explain what to do once, with an "Open settings" button. Categories
    spilled off screen in one scrolling row; now one row with "More categories". The "Install
    to" list repeated the version already in the instance name and was cut off.
  - Import from other launchers: names now line up (fixed-width launcher badge).
  - Global settings: setting only the window height did nothing (needed a width first).
  - About said "open launcher" (the code is not public) and the User-Agent read
    "aerion-dev … (contact not configured)" in release builds; it now names the project.
- Less to take in for most players, everything still there for experts: rarely used options
  (JVM preset, Java per version, initial memory, JVM/game arguments, wrapper, environment,
  game folder location of a new instance) sit in collapsed "Advanced" groups that say what they
  contain; network and Azure client ID moved into one "Advanced" settings section; shorter
  section names keep the chip row on one line. Content tab hides "Check for updates" and
  "Identify" while the instance is empty.

### 0.1.7: more natural layout + customizable sidebar and home (D26)
- Home: Play with an instance dropdown (split button); quick actions (new instance, mods, skins,
  statistics) instead of a text menu duplicating the sidebar; optional summary block (total play
  time, instances, last session); favourites & recent.
- Sidebar: Accounts out of the default list (account button at the bottom), favourite instances
  section with one-click open/start, "customize" button; Ctrl+1…9 follow the arranged order.
- "Customize menu" / "Customize home screen" dialogs (also in Settings → Appearance): order with
  ↑/↓, show/hide (Home/Settings/Play locked), favourites in menu, player left/right/hidden,
  restore defaults. Checked live: reorder, hide, reset, player left, summary shown.
- Before publishing, the owner spotted Settings → Appearance: six buttons in one row cut off
  labels ("Importuj motyw…", "Dostosuj…"). Now a Theme group (gallery, import, folder, theme
  editor — renamed from the ambiguous "Customize…") and a Layout group with a short explanation;
  both wrap. Section jumps from the chip row land below it (it wraps to two lines at 1200 px and
  covered the heading). Checked live: no button overflows, section top 7 px under the chip row.


### 0.1.6: external links were all blocked
- Owner: "Download" in the update dialog does nothing (0.1.4 .deb found 0.1.5 correctly). The log
  showed `Not allowed to open url …`: the capability listed `opener:allow-open-url` without a URL
  scope, so Tauri refused every external link (update download, buy Minecraft, mod pages, the
  Microsoft code page, theme submissions) since the beginning. Now scoped to `https://*`
  (verified in a dev instance: https opens, http stays blocked).
- Update check itself works (0.1.4 detected 0.1.5 from GitHub Releases).


### 0.1.5: head tracking on Windows, RAM slider
- Owner: on Windows the player model doesn't look at the cursor. Cause: with Windows "Show
  animations" off, WebView2 reports `prefers-reduced-motion`, and the model turned *all* motion
  off, including cursor tracking. Tracking is a response to the user's own input, so it now has
  its own setting ("Player model follows the cursor", on by default) and works with reduced
  motion too (head snaps to the target, no easing or idle sway).
- Checked in Chromium (headless Brave driven over CDP — same engine family as WebView2): normal
  and forced reduced motion both track the cursor. The "visual bug" the owner saw on Windows
  could not be reproduced there; waiting for a screenshot.
- RAM: a shared `MemorySlider` (GB value, MB, total memory, GB marks, too-high/too-low warnings),
  always visible in Settings → Java & memory (Automatic / Own) and in instance settings (moving
  it gives the instance its own value).


### Big feature batch (0.1.4)
- **Play without an account**: a dialog explains what is missing (sign in / session expired / no
  licence / sign-in pending Mojang approval) with buttons, instead of a toast.
- **Statistics page** (`Ctrl+6`): total play time, sessions (new per-instance counter), most
  played instances, favourite versions and loaders, most used mods, worlds/screenshots counts.
- **Worlds tab**: worlds with names/icons from `level.dat`, zip backups (manual; automatic before
  mod updates and version changes, last 3 kept), restore with an automatic backup of the current
  state, backups of deleted worlds.
- **Screenshots tab**: gallery + viewer (arrow keys), open in system viewer, delete.
- **Servers tab**: `servers.dat` list (add/remove, unknown fields kept), live status (MOTD,
  players, version, icon, ping) and **Join** (Quick Play).
- **Game settings sync**: copy options.txt/OptiFine/shader settings from or to instances; a
  template applied to every new instance.
- **Update an instance to a newer Minecraft version**: plan (mods updated / already compatible /
  unavailable / unknown), loader availability check, world backup, update in place or as a copy;
  "X available" badge in the instance header.
- **Import from other launchers** (D23): official launcher, Prism/MultiMC, Modrinth App,
  CurseForge, SKlauncher, any folder; copy or use in place. Verified detection on this machine
  (Modrinth App profile → NeoForge 1.21.1 from its log, 5 SKlauncher instances, .minecraft).
- **Theme gallery** (D25): 8 new themes prepared in `~/Desktop/aerion-strona/themes` for upload.
- **Launcher updates** (D22): signed, from GitHub Releases; banner + Settings switch/"Check now".
- Tests: engine 106, Tauri 11, vitest 18 (NBT, worlds, servers + fake-server ping, screenshots,
  options, stats, upgrade plan, importers).
- Not verified live: Join/ping against a real server, update install (needs a published
  release), gallery (needs the themes folder in the repository).


### Game locations, global/per-instance settings, capes, nicer player model (0.1.3)
- **Instance game folder anywhere** (`Instance.gameDir`): chosen in the new-instance dialog or
  moved later (instance → Settings → Game folder; rename on the same drive, copy + delete across
  drives). The folder must be empty/new; a `.aerion-instance` marker is written and only marked
  folders are deleted with their instance. Clones copy it into their own default folder. Unit test
  `game_folder_elsewhere`.
- **Global defaults with per-instance override**: Settings → "Game defaults" (initial memory,
  resolution, fullscreen, JVM and game arguments, wrapper, environment) next to the existing max
  memory, JVM preset and Java per version. Instances show "Global (value)" / "Own" for memory,
  preset, fullscreen and after-launch; arguments and environment are added on top of the global
  ones (instance last), resolution and wrapper replace them. Global values are validated on save.
- **Capes in the wardrobe**: the cape is drawn on the 3D model (home and Skins), pointing at a cape
  previews it before wearing; offline accounts get an explanation instead of a hidden section.
- **Player model**: geometry precomputed once instead of per frame; faces shaded by light
  direction using pre-darkened texture copies (same per-frame cost); soft contact shadow; idle arm
  sway like in game; cape hangs back and sways. Checked on screen with a test cape (front/side).


### Mojang review submitted
- The owner submitted the Minecraft API review form (new AppID, client ID
  `b7169ac0-19f3-4bf2-a815-1e98829a891a`, website https://github.com/0giel/Aerion — a public page
  with README and screenshots, no source code). Waiting for Mojang's e-mail; until then sign-in
  ends with `auth.invalidAppRegistration`, which is expected.


### Instance icons from Minecraft textures (0.1.2)
- 52 item/block textures (diamond, swords, golden apple, nether star, grass block, TNT, …)
  replace the pixel-art set; read at run time from a Mojang client jar (D21) and served as
  `localimg://…/mc/<texture>`; old icon values map to similar textures. All 52 verified present
  (16×16) in the 26.2 jar; checked on screen.
- Icon picker: the selection ring now hugs the icon (same corner radius + 5 px), scrollable grid.


### Player model head tracking: smoother (0.1.1)
- Owner: "the model that looks at the cursor lags". It redrew on the shared 20 fps clock with a
  110 ms easing constant. Now a dedicated rAF loop runs only while the head is turning, easing
  45 ms, and the target is computed on pointer moves (no per-frame layout reads).
- Measured with a synthetic circular cursor (dev, WebKitGTK): model frames **22.5 → 41 fps**
  (median gap 43 → 23 ms), CPU while moving the mouse 52 → 58 %; idle unchanged.
- Version bumped to 0.1.1 so the `.deb` installs as an upgrade.


### Renamed to Aerion; Azure app registered
- The owner chose **Aerion** as the product name (D20). Everything user-visible and the crate
  (`cargo test -p aerion`) were renamed; old data folder is migrated on first start (unit test),
  old archives still import. Polish forms: "w Aerionie", "Aeriona".
- Azure: the owner's Microsoft account had only a placeholder tenant (no user object — the
  portal's "Interaction required" loop and Graph "User was not found"). After the owner created a
  free Azure account (real "Default Directory"), the app registration was created with Azure CLI
  via Microsoft Graph: personal accounts only, public client, redirect `http://localhost`,
  fallback public client on. Client ID `b7169ac0-19f3-4bf2-a815-1e98829a891a` saved in the
  owner's settings. Mojang review form: owner submits it (repo page without code prepared).


### Owner requests: icons, favourites, head tracking, skins tab
- **Instance icons**: 16 original pixel-art icons + "your own image" (PNG/JPEG/WebP/GIF ≤ 2 MB,
  copied into the instance folder, served via the new `localimg://` scheme). Picker in the
  new-instance dialog and on the instance page (click the icon). `set_instance_icon`,
  `import_instance_icon`; icon values validated in the engine.
- **Favourites**: star on instance tiles and the instance header; "Favourites" filter chip;
  favourites first in lists, the home picker and the home quick bar ("Favourites & recent").
- **Player model looks at the cursor**: the head turns on the neck (±60° yaw, ±35° pitch, eased),
  the body keeps swaying; runs on the shared ambient clock, off with reduced motion.
  Checked on screen (cursor top-left / bottom-right): the first version rolled the head when
  looking up and sideways; fixed to nod-then-turn (verified numerically, no new screenshot).
- **Skins tab** (`Ctrl+5`): skin library (add PNGs by dialog or drag & drop, name, classic/slim
  with live 3D preview, delete), wear a skin, save the current skin, reset to default, choose or
  hide a cape. Uses Minecraft services directly (D18). Wiremock tests for upload (multipart),
  reset and cape; library CRUD tests. **Not verified live** — needs a Microsoft account.
- New error codes with translations: `image.unsupported`, `file.tooLarge`, `skin.size`,
  `skin.needsMicrosoft`. New vitest: every literal `t("…")` key used in the UI exists in the
  dictionary (it caught nothing, but a typo now fails CI).
- Fixed a possible panic in the custom-scheme percent decoder (multi-byte char after `%`).
- Tests: engine 89, Tauri 10, vitest 18.


### Windows build (cross-compiled on Linux)
- Rootless toolchain `scripts/windows-cross.sh`: clang-cl/lld-link/llvm-rc 20 and NSIS unpacked
  from Ubuntu packages, `cargo-xwin` (MSVC CRT + Windows SDK), target `x86_64-pc-windows-msvc`
  (Tauri's documented route). Ubuntu's `makensis` has its data dir compiled in, so it runs in a
  bubblewrap namespace that mounts the private copy at `/usr/share/nsis`.
- **`Luncher_0.1.0_x64-setup.exe` (5.9 MB, NSIS, per-user install, downloads WebView2 if
  missing)**; `luncher.exe` 22.8 MB. MSI needs WiX (Windows only) — CI.
- `cargo clippy --target x86_64-pc-windows-msvc -D warnings` clean for the whole workspace
  (fixed an import only unused on Windows).
- **All 84 engine tests pass as Windows binaries under Wine 10** (private prefix). One failed
  first: Forge processor paths joined a `/`-separated Maven path onto a native path
  (`C:\…\libraries\net/neoforged/…`), which breaks under `\\?\` prefixes →
  `MavenCoord::local_path()`. Coordinates now also reject `..`, `/` and `\` in classifier/extension.
- **Smoke test as a Windows binary under Wine: Minecraft 26.3 reached the title screen in 9.7 s**
  with the Windows Mojang Java 25 runtime (`javaw.exe`), Windows natives and `;` classpath
  (NVIDIA OpenGL 3.3 through Wine). Errors seen are Wine/offline artefacts (oshi, 401).
- Not verified: the GUI on Windows (WebView2 under Wine was not tried), Credential Manager,
  `.lnk` shortcuts, tray. Reviewed statically: `http://img.localhost` URLs and CSP, no HTML5
  drag-and-drop, Segoe UI in the font stack, no console window in release, keyring v4 uses the
  Windows store by default, tokens chunked for Credential Manager.


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
- **Quilt 1.21.11**: loader 0.24.0 (newest stable; betas skipped) → title screen in 22 s.
- **Fixed:** "Check updates" offered `Sodium 0.8.15-beta.1` for a stable install (Modrinth's
  `version_files/update` ignores channels). Entries now record their release channel
  (`release`), and updates never move to a less stable channel — falls back to the newest version
  on an allowed channel. Wiremock test `updates_keep_the_release_channel`.

### Verified: Forge and NeoForge (M8)
- **Forge 1.20.1 (47.4.28)**: installer with all 6 processors (installertools ×3, jarsplitter,
  ForgeAutoRenamingTool, binarypatcher) → title screen in 27 s (Java 17).
- **NeoForge 26.3 (26.3.0.100)**: one processor (installertools fatjar). First launch crashed with
  SIGSEGV in `libnvidia-glcore` (FML early-window thread) after GL_INVALID_OPERATION errors.
  `__GL_THREADED_OPTIMIZATIONS=0` fixes it → **title screen in 10 s**.
- **Forge 1.8.9 (11.15.1.2318)**: legacy installer (embedded `versionInfo` + universal jar),
  Java 8 (`jre-legacy`) → FML loading in 15 s.
- **Fixed:** the launcher now sets `__GL_THREADED_OPTIMIZATIONS=0` for game processes on Linux
  when the NVIDIA driver is loaded (`game::launch::default_env`); variables set on the instance
  or in the launcher's own environment take precedence.

### Performance pass (release build)
- First measurement: UI ready 0.32–0.37 s after process start ✅; memory ~228 MB PSS (launcher
  87 MB + WebKit web process ~128 MB + network 16 MB) — over the 200 MB target; **idle CPU ~81 %**.
- Cause of the CPU use: WebKitGTK (no GPU compositing here) repaints the whole window for every
  animated frame; ribbons ~45 %, particles ~32 % (measured by toggling layers).
- Fix (D16): static ribbons; particles + player model only while the launcher is in use, on a
  shared 20 fps clock; particles redraw only their own boxes → **idle ~1 %**, interacting ~32 %.
- Memory (D17): Tokio limited to 4 workers, glibc `M_ARENA_MAX=2` → 216 MB settled.
- Memory (D13): a per-mapping breakdown showed Mesa's EGL vendor (libgallium + **libLLVM, 12.8 MB
  per process**) loaded into both WebKit processes although NVIDIA drives the only GPU. On
  NVIDIA-only machines EGL is now restricted to NVIDIA's vendor file → **~200 MB at 10 s, ~182 MB
  settled**. Hybrid laptops / Mesa / nouveau are untouched; webview-only variables are stripped
  from the game's environment. `WEBKIT_DISABLE_COMPOSITING_MODE` and
  `WEBKIT_SKIA_ENABLE_CPU_RENDERING` were tried and changed nothing.
- **Final release numbers** (`scripts/perf-linux.sh 3`, now samples CPU while in use and idle):
  UI ready 0.37–0.42 s ✅ (target 1.5 s), PSS 197–201 MB at 10 s / 179–183 MB settled ✅
  (target 200 MB), idle CPU 0.6–0.9 % ✅, in use 23–35 %. Sizes: binary 20.2 MB, `.deb` 8.6 MB,
  AppImage 88.6 MB. Details in `PERFORMANCE.md`.
- Bundling on this machine needs two pkg-config overrides (devroot `.pc` files point at the
  devroot, linuxdeploy's GTK plugin reads `gtk+-3.0.pc`): see "Release bundles" in `CONTRIBUTING.md`.
- The DMA-BUF renderer also works on this NVIDIA/X11 machine (`WEBKIT_DISABLE_DMABUF_RENDERER=0`)
  with the same CPU profile; the NVIDIA workaround stays as the safer default.
- Instance titles no longer repeat what the name already says ("Forge 1.20.1 · 1.20.1 · Forge").

### Frontend tests (vitest, 16 tests)
- `i18n/dictionaries.test.ts`: pl/en have the same keys and placeholders, no empty strings, Polish
  plurals have one/few/many/other, and **every error code and crash kind in the Rust sources has a
  translation** (read via `?raw` imports, so a new untranslated code fails CI).
- `theme/schema.test.ts`: all 7 built-in themes survive normalisation unchanged; missing fields
  fall back; numbers are clamped; CSS injection / `url()` / path traversal in assets are rejected;
  motion tokens honour reduced motion.
- `api.test.ts`: `instanceTitle`.

### Documentation
- `README.md` (features, unofficial disclaimer, build/run, configuration, troubleshooting),
  `docs/AZURE_SETUP.md`, `docs/THEMING.md`, `docs/DECISIONS.md` (D1–D17),
  `themes/theme.schema.json` (JSON Schema; also copied to `<data>/themes/` at start-up so editors
  validate user themes; excluded from the built-in theme glob).
- Background videos now pause while the window is hidden or motion is off.

### Verified: modpack from the browser (M7) and theme editor (M9)
- Mods → Modpacks → "Fabulously Optimized" → *Install as new instance*: 52 files + Fabric 0.19.5 for
  MC 26.2 in ~8 s, modpack provenance stored on the instance. **Launch: 149 mods, title screen in
  25 s** (incl. downloading MC 26.2).
- Export → import round trip: "Fabric 1.21.11" exported as `.mrpack` (index with Modrinth CDN URLs
  for Sodium/Fabric API + `overrides/options.txt`, no jars embedded) and re-imported as a working
  instance (removed afterwards).
- **Fixed:** the theme editor's live-preview effect read and wrote the theme engine's state
  (`setPreview` → `apply`), which Svelte aborts with `effect_update_depth_exceeded`. The call is now
  `untrack`ed; editing a colour previews instantly and "Undo changes" restores the theme.

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

## Not verified yet
- Microsoft sign-in end to end (needs the owner's Azure app + Mojang approval) and therefore
  offline accounts in a release build (they unlock only after a licensed account).
- CurseForge search/install/modpacks live (needs a CurseForge API key).
- The GUI on a real Windows machine (installer built and engine verified under Wine); macOS.
- Undo of a mod update through the UI.
