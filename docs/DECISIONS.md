# Decisions

Short records of choices that are not obvious from the code. Newest last; each says what was
decided, why, and what would change it.

### D1 — Tauri 2 (not 3), Svelte 5, one Rust engine crate
Tauri 3 was alpha on 2026-10-30. All launcher logic lives in `crates/launcher-core` with no Tauri
dependency so it can be tested headless (82+ tests, smoke tool) and reused by a CLI later;
`src-tauri` only adapts it to IPC. *Revisit* when Tauri 3 is stable.

### D2 — Typed IPC with tauri-specta (pinned release candidate)
`tauri-specta`/`specta` `=2.0.0-rc.26` generate `src/lib/bindings.ts`, so a changed Rust signature
breaks `pnpm check` instead of failing at run time. Exact pins because upstream requires matching
RCs. Serde "phases" produce `X_Serialize`/`X_Deserialize`; `lib/api.ts` aliases them to `X`.
CI fails if the committed bindings are stale.

### D3 — JSON files, no database
Settings, accounts (metadata only), instances and `content.json` are human-readable JSON written
atomically (temp file + rename). Hundreds of records at most; users can inspect and back them up;
Prism-style instance folders stay portable. *Revisit* only if search over thousands of items is
needed.

### D4 — Version order from the manifest, never by parsing ids
Since 2026 Mojang numbers releases `26.1`, `26.3`, snapshots `26.4-snapshot-6`; older ids are
`1.x`. Sorting uses manifest order / `releaseTime`.

### D5 — Secrets only in the OS keyring, chunked
Refresh/access tokens and the CurseForge key go through `keyring` (Credential Manager, Keychain,
Secret Service). Values are split into ≤ 1000-character chunks because Windows Credential Manager
rejects large blobs. Logs mask tokens (`CommandSpec::redacted`, log-line masking).

### D6 — CurseForge: user-supplied key, no caching
Their 3rd-party API terms (§2.2, §3.1) forbid sharing keys and caching API data. The key is
entered by the user (or `CURSEFORGE_API_KEY`), the CF client bypasses the HTTP cache, CF images go
through the proxy with `nc=1`, and files with `downloadUrl: null` are never fetched elsewhere —
the UI links to the file page.

### D7 — No remote content in the webview
Strict CSP (no remote scripts/styles/images). Remote images come through the `img://` proxy
(disk-cached thumbnails, private hosts refused); Markdown/HTML from mod pages is sanitised with
DOMPurify; links open in the system browser via the opener plugin.

### D8 — Offline accounts gated behind a licensed account
The launcher must not help bypass licensing: offline accounts unlock only after a Microsoft
account that owns the game was added. `LAUNCHER_DEV_OFFLINE=1` works in debug builds only.

### D9 — Java: Mojang runtimes first, Adoptium fallback
The version JSON names the runtime (`java-runtime-epsilon` = Java 25 for 26.x). Mojang has no
Linux ARM64 and no Java 8 on Apple Silicon / Windows ARM, so Adoptium (with its SHA-256) fills the
gaps. System Java is detected and can be pinned per major version.

### D10 — Loaders: one generic processor runner
Forge has three installer generations and NeoForge a fourth shape; all are driven by
`install_profile.json` data (`[maven]`, `'literal'`, `/path`, built-ins) instead of per-version
code. Verified live: Forge 1.20.1 (6 processors), NeoForge 26.3 (1 processor).

### D11 — Updates never move to a less stable channel (2026-10-31)
Modrinth's `version_files/update` returns the newest version on any channel, which offered a
Sodium beta to a stable install. Entries record their channel; updates stay on the same or a more
stable one.

### D12 — Player model drawn on a canvas, not CSS 3D (2026-10-31)
WebKitGTK without GPU compositing flattens `transform-style: preserve-3d`, which showed the back
of the model. A small orthographic projection on a 2D canvas (affine-mapped faces, back-face
culling, depth sort) looks the same on every engine and needs no WebGL or library.

### D13 — Linux environment workarounds applied by the launcher (2026-10-31)
- **fontconfig cache**: a newer fontconfig (e.g. from a Flatpak runtime) can leave
  `*-le64.cache-9` symlinks to `*.cache-12` files in `~/.cache/fontconfig`; the system
  fontconfig then misreads them and WebKitGTK's web process spins forever (blank window). The
  launcher detects such links and gives fontconfig a private cache via `FONTCONFIG_FILE`, leaving
  the user's files untouched.
- **NVIDIA (proprietary driver)**: `WEBKIT_DISABLE_DMABUF_RENDERER=1` for the launcher window, and
  `__GL_THREADED_OPTIMIZATIONS=0` for the game (NeoForge 26.3's early window segfaulted in
  `libnvidia-glcore`). Explicit user/instance values always win.
- **EGL vendors (NVIDIA-only machines)**: GLVND loads Mesa's EGL vendor into both WebKit
  processes even when the NVIDIA driver drives every GPU, where Mesa can only offer a software
  rasteriser; its LLVM alone costs ~30 MB PSS. When every DRM card uses the `nvidia` driver,
  `__EGL_VENDOR_LIBRARY_FILENAMES` points at NVIDIA's vendor file. Hybrid laptops and Mesa or
  nouveau systems are left alone; a user-set `__EGL_VENDOR_LIBRARY_*` disables it.
- Variables the launcher sets only for its webview (DMA-BUF, EGL) are removed from the game's
  environment (`util::sys::private_env`); the private fontconfig file is kept, it is valid for
  any process.

### D14 — Restoring the window after the game
Some X11 window managers (Mutter) ignore de-iconify requests; a minimised window is hidden and
re-shown, which always maps it un-minimised.

### D15 — Debug-only automation hooks
`AERION_DEV_EVAL`, console mirroring and page-load logging exist only under
`cfg(debug_assertions)`, so UI walkthroughs can be scripted without a mouse. Release builds do not
contain them.

### D16 — Ambient animation only while the launcher is in use (2026-10-31)
Measured on WebKitGTK without GPU compositing: every animated frame repaints the whole window
regardless of the dirty area, so the decorative home screen cost ~83 % of a core at rest. Ribbons
are now static; particles and the player model animate only while the window is visible, focused
and received input in the last 30 s, on one shared ~20 fps clock (`stores/ambient.svelte.ts`).
Idle: ~1 % CPU; while interacting: ~30 %. On GPU-composited engines (WebView2, WKWebView) the same
code is far cheaper; the gating still saves battery.

### D17 — Fewer threads and malloc arenas
The engine mostly waits on I/O: the Tokio runtime uses at most 4 workers (default was one per
core), and on Linux/glibc `M_ARENA_MAX=2` stops each thread from keeping its own arena of freed
memory resident.

### D18 — Skins through the official API, library kept locally (2026-10-31)
Skin and cape changes use Minecraft services (`POST /minecraft/profile/skins` multipart,
`DELETE …/skins/active`, `PUT/DELETE …/capes/active`) with the account's normal token — no
third-party skin servers. Saved skins live in `<root>/skins` (`skins.json` + `<uuid>.png`),
validated as 64×64 / 64×32 PNG by reading the `IHDR` header (no image-decoding dependency).
Offline accounts can use the library and preview but cannot change what others see in game.

### D19 — Instance icons: built-in pixel art or a file with a unique name (2026-10-31)
Built-in icons are original 16×16 pixel art (`src/lib/instanceIcons.ts`, rendered as merged SVG
rectangles). Custom images are copied into the instance folder as `icon-<millis>.<ext>` (sniffed
PNG/JPEG/WebP/GIF, ≤ 2 MB) and served by the `localimg://` scheme; a new name per change lets the
response be cached forever. Favourites are a flag on the instance; lists put them first in the UI,
the backend order stays "last played".

### D20 — Renamed from "Luncher" to "Aerion" (2026-10-31)
"Luncher" was a working name. Product name, identifier (`pl.aerion.desktop`), crate (`aerion`),
data folder, keyring service, log files, User-Agent and `launcher_name` are now "Aerion". For
existing users: the old data folder (`luncher` / `Luncher`) is moved to the new one on first start,
`LUNCHER_HOME` is still read after `AERION_HOME`, and archives with the old
`luncher-instance.json` marker still import. Hidden on-disk markers of installed runtimes and
natives (`.luncher-runtime`, `.luncher-natives`) keep their names so nothing is re-downloaded.
Secrets stored under the old keyring service are not migrated (only a CurseForge key could exist;
Microsoft sign-in was never used).

### D21 — Instance icons are Minecraft textures read at run time (2026-10-31)
The owner preferred real game textures to the original pixel art (D19). The specification forbids
shipping Minecraft assets, so `game::textures` reads item/block textures from a client jar that
the launcher downloads from Mojang anyway (newest installed jar with the 1.13+ layout; otherwise
the latest release's jar is fetched once) and caches them under `cache/textures`. Icon values are
`mc:<texture>`; the 16 old pixel-art keys map to similar textures. Without a jar (offline, fresh
install) icons fall back to the launcher logo.

### D22 — Launcher updates through signed GitHub Releases (2026-10-31)
`tauri-plugin-updater` reads `latest.json` from the latest GitHub release of 0giel/Aerion and
verifies each download with the minisign public key in `tauri.conf.json`. The private key lives
only in `~/.config/aerion-signing/` (back it up: without it no update can reach existing installs).
Windows, macOS and AppImage update in place; `.deb` installs only get a "new version" notice with a
download link (the system package manager owns them). `scripts/release.sh` builds and prepares
the release folder.

### D23 — Importing other launchers' instances (2026-10-31)
Metadata comes from each launcher's own files (launcher_profiles.json, instance.cfg +
mmc-pack.json, minecraftinstance.json, SKlauncher's instances.json). Modrinth App keeps its
metadata in an internal SQLite schema that changes between versions, so its game version and
loader are read from `logs/latest.log` instead (and confirmed by the user). Copies skip other
launchers' shared files and anything with credentials (`launcher_*`, `*accounts*`); "use this
folder" imports point the instance at the existing folder without a marker, so deleting the
instance never deletes it.

### D24 — Worlds, servers and screenshots read from game files (2026-10-31)
A small NBT reader/writer (`util::nbt`) reads `level.dat` names and edits `servers.dat` keeping
unknown tags; server status uses the Server List Ping protocol (no SRV lookup yet). World
backups are zips in `instances/<id>/.backups/worlds`; automatic ones (before mod updates and
version changes) keep the last three per world, and restoring first backs up the current state.

### D25 — Theme gallery is a folder in the repository (2026-10-31)
No server: `themes/index.json` and theme files in github.com/0giel/Aerion are fetched by the
backend; installs are JSON only (no custom CSS from the network) and go through the same
validation as local themes.

### D26 — User-arranged sidebar and home screen (2026-10-31)
`settings.layout` stores ordered `{id, visible}` lists for the sidebar and the home blocks plus
two options (favourites in the sidebar, player model left/right/hidden). Empty lists mean the
built-in default; the UI drops unknown ids and appends new ones, so future items appear without
migrations. Home and Settings can't be hidden (nobody locks themselves out); reordering uses
↑/↓ buttons, not drag and drop — keyboard accessible, and HTML5 drag and drop is unavailable in
WebView2 while the window accepts dropped files. Defaults were rearranged at the same time: Play
got an instance dropdown, the text menu that duplicated the sidebar became quick actions, Accounts
moved out of the sidebar list (the account button at its bottom opens it).

### D27 — Simple by default, advanced on request (2026-10-31)
The owner's goal: nobody should feel lost in the basic screens, but demanding players keep every
option. Options most players never change are grouped in collapsed "Advanced" disclosures
(`components/Advanced.svelte`) whose summary lists what is inside, so they stay discoverable
and searchable by eye; a disclosure opens by itself when it holds a non-default value (e.g. an
instance with its own Java). No global "expert mode" switch: it would hide options without a
trace and split support answers into two versions of each screen.

### D28 — Crash help: disable the culprit, share through mclo.gs (2026-10-31)
Crash analysis traces the suspected mod to its jar by reading every jar's own metadata
(`mods::meta`: Fabric/Quilt JSON, Forge/NeoForge TOML, mixin config names) — content.json does
not know mod ids, and files added by hand must work too. Only crashes a mod can cause offer
"Disable and relaunch" (never memory, Java or driver problems), and Fabric API / loader ids are
never blamed. Disabling is the usual `.disabled` rename, so it is undone from the Content tab.
Logs are shared on mclo.gs (the paste service the Minecraft community uses, keeps 90 days) only
after a confirmation each time; before upload the launcher strips JWT-like tokens,
`--accessToken` values and home-folder user names, and keeps the last 25 000 lines.

### D29 — The Aerion mod: launcher theme in the game (2026-10-31)
A client-only Fabric mod in `mod/` (no Fabric API, so it works next to any mod list) restyles
buttons, sliders and menu backgrounds with the launcher theme's colours. The launcher resolves the
theme into plain colours (`aerion_mod::GameTheme`, gradient stops extracted from the CSS) and
writes `<root>/aerion-mod/theme.json`; the mod re-reads it once a second, so theme changes apply
to a running game. The jar is embedded in the launcher and loaded with `-Dfabric.addMods`, never
copied into the player's mods folder. Built only for 26.3 at first (see D33 for 1.21.11): Minecraft's GUI code changes
between versions (26.x renamed rendering to `GuiGraphicsExtractor`/`extract*`), so each further
version is a separate build. Labels stay white (vanilla draws them so), therefore button bodies
are darkened until white text reads well, also with light themes. Off by default per instance.
Follow-up: particles and an Aerion logo; each of colours, particles and logo can be switched
off globally (`settings.gameUi`), the per-instance switch turns the whole mod off. The logo
first was built from fills and the game font; the owner found it jagged, so it is now two white
640×144 images (cube + "Aerion" in Inter, and the sparkle) rendered from the launcher's glyph,
read from the mod jar by hand (mod assets are not game resources without Fabric API), sampled
linearly instead of Minecraft's nearest filter and tinted with the theme.

### D30 — Mod health from the jars themselves (2026-11-01)
Problems are read from each jar's metadata (dependencies, `breaks`/`incompatible`, provided and
bundled ids, versions), not from Modrinth data, so files added by hand are covered and nothing
needs the network. Fabric predicates and maven ranges are compared only for plain release
numbers; snapshots and unusual formats count as unknown and are never reported, because a false
"your mods are broken" is worse than a missed one (checked against a 49-mod Fabric pack and a
NeoForge Create pack: no false alarms). The launch dialog offers "Launch anyway". Versions
chosen by hand are pinned so update checks don't undo the choice. Replacement suggestions are a
short curated list plus a Modrinth search, always labelled as suggestions.

### D31 — Performance page shows measurements only (2026-11-01)
Owner wanted users to see why Aerion is light. The page shows what is measured on the user's
computer (sysinfo; NVIDIA via nvidia-smi, AMD via sysfs on Linux; threads skipped so memory is
not counted once per thread) and never invented numbers about other launchers. Game FPS comes
from the Aerion mod (`-Daerion.status`, written once a second). Sampling runs only while the page
is open and the window visible. The busiest programs are listed by name locally; nothing leaves
the computer.

### D32 — Widget dashboards start as an experimental switch (2026-11-01)
The classic pages stay the default so nobody is pushed into arranging their launcher. With
`experimentalWidgets` on, the home screen can become a dashboard and any number of own pages
appear in the sidebar. Layouts are a 12-column grid in the settings file; placement is free (no
gravity), so a gap the user leaves stays. Widgets only show existing data through existing
commands, so a broken widget can't affect the rest of the launcher.

### D33 — Lunar-style mod: one source, one jar per game version, no Fabric API (2026-11-01)
`mod/common` holds everything that doesn't touch rendering names; `v26_3` and `v1_21_11` hold the
thin part that differs (`platform/*`, version mixins). 26.x is unobfuscated, 1.21.11 is remapped
by Loom. Fabric API proved unnecessary, and leaving it out means nothing extra to download or
break. Settings live in `aerion-mod/hud.json`: the launcher edits everything, and the in-game editor
moves and scales modules, and the in-game menu switches modules and features on and off and sets
everything else (only free-form text formats need the launcher, there is no text field in the game).
The owner asked for many settings without an overwhelming screen, so the menu is a sidebar with quiet
tiles, and one tile's settings sit on tabs. A module's look is stored field by field on top of the
shared look, so there is no all-or-nothing "own style" switch and an arrow restores a single field.
Key bindings are normal game KeyMappings saved in options.txt (per instance, also in Controls); each
module has one to switch it on and off. A running game reloads the file within a second, and the launcher reloads it when
its window regains focus. Corners are square by default (owner's request); the corner setting stays. The minimap draws terrain only: showing other players would be a
radar, which servers treat as cheating. Freelook and fullbright are off by default and the
launcher warns that servers may forbid them. Toggle sprint/sneak use Minecraft's own options;
`aerion-toggles` in the game directory records what Aerion turned on, so switching the feature
off never overrides a choice the player made in the game's settings. Input constants differ by
version (26.3 counts mouse buttons from 1), so code always uses `InputConstants`, never numbers.

### D34 — Menus drawn in screen pixels with a baked font (2026-11-01)
Owner asked for higher-resolution buttons in the game. `Draw` fills in real screen pixels instead
of GUI pixels (thin borders, anti-aliased corners), and `Txt` draws Inter from one image made by
`scripts/build-mod-assets.py`, because Java's AWT font stack can fail in the game's headless JVM and
mod font assets don't load without Fabric API. Button and slider labels use it when the theme's
colours are on; a label that is too long or has a character outside the image stays with the game's
font, which also keeps its scrolling. HUD modules use it too by default (owner's request), with
Minecraft's font one click away, per module or shared. Menu layout follows the
owner's reference screenshots (window, cards, options page) with Aerion's own theme and logo, and no
other client's branding.

### D35 — Layout first, and features that need no HUD element (2026-11-01)
Right Shift opens the HUD layout, not the settings: the screen shows the HUD being arranged, with
the logo and a Mods button in the middle (the owner's reference). The world isn't dimmed or blurred
there, so a layout can be judged against the real picture, and lines are one screen pixel wide. Resizing moved from the mouse
wheel to squares on all four corners, so a module against a screen edge can still grow towards free
space, and the opposite corner stays put. Dragging snaps to the screen and other modules, with
guides; arrow keys nudge, and undo and reset are at hand. The menu sets its own pixel size (about
60 % of the window's height, whole screen pixels per unit) so a high GUI scale can't make it huge.
Hitboxes, the sound editor and overlays are features, not HUD modules: they have no box on screen.
Hitboxes use the game's debug shapes (`Gizmos`) and the player's real interaction range for the
"in reach" colour, so it follows attributes and game mode. Sound volumes apply where the game
computes a sound's final volume, stored per sound id only when different from 100 %. Overlay and
hitbox colours can give an edge on some servers, so they are off by default and carry a warning.

### D36 — Chroma, more features, and sound tested by measurement (2026-11-01)
The first sound editor multiplied the volume only in `calculateVolume(SoundInstance)`, which the game
uses while a sound runs; a new sound takes `calculateVolume(float, SoundSource)` inside `play`, so
the sliders did nothing audible and "Just heard" stayed empty. Both paths are now covered. Audio is
tested without speakers: OpenAL Soft's wave backend (`ALSOFT_DRIVERS=wave`, `ALSOFT_CONF`) writes the
mix to a file, and peaks are compared (26.3 pig death 0.4197 / 0.1049 / 0 at 100 / 25 / 0 %, 1.21.11
button click 0.2409 / 0.0602 / 0).
`chroma` is one more colour value next to `theme` and `#rrggbb`, resolved per frame, so anything
coloured can use it; text can also run as a wave across letters. The picker keeps its hue state per
row, so black and white don't lose the hue. Hurt cam scales the game's tilt constant (present in both
versions) rather than overwriting the player's accessibility option. The custom crosshair replaces
the game's, so it draws its own attack cooldown bar. Hide-the-effect overlays stay off by default.

