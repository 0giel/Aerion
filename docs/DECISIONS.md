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
`LUNCHER_DEV_EVAL`, console mirroring and page-load logging exist only under
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
