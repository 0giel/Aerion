# Performance

Targets from the specification: **start-up < 1.5 s** to an interactive window, **< 200 MB RAM**
for the launcher at rest, no work while idle, smooth UI with long lists and logs.

## Measurements

Machine: Zorin OS 18.1 (X11), Linux 7.0, NVIDIA RTX 3060 Ti (driver 580), WebKitGTK 2.52.6,
release build (`pnpm tauri build`, LTO, `codegen-units = 1`, `opt-level = 3`, stripped).
Method: `scripts/perf-linux.sh` — start `target/release/luncher`, wait for the frontend's `UI ready` log line,
idle 10 s on the home screen, then sum PSS/RSS over the launcher and its WebKit processes and
sample CPU for 10 s. Three runs, warm disk cache.

RESULTS_PLACEHOLDER

### Sizes

| Artefact | Size |
|----------|------|
SIZES_PLACEHOLDER
| Frontend (all chunks) | 411 KB (140 KB gzip), 29 files |
| Frontend loaded at start-up | ~200 KB (64 KB gzip): `index`, `app.svelte`, CSS |

## What keeps it fast

**Start-up**
- The window is created hidden with the theme's background colour and shown only after the
  first frame is themed (4 s safety net) — no white flash.
- Only Home is in the initial bundle; every other page (Mods with Markdown + DOMPurify, theme
  editor, console…) is a separate chunk loaded on first visit.
- Rust start-up does no network I/O; the version manifest and other metadata are fetched lazily
  and cached on disk with ETags (10 min TTL for the manifest, stale data used when offline).

**Memory**
- One process for the launcher plus WebKit's web/network processes; no Node runtime, no Chromium.
- Game logs are a ring buffer of 20 000 lines per instance; the console renders only visible rows
  (`VirtualList`).
- Images from Modrinth go through the `img://` proxy with a disk cache that is trimmed at start-up.

**Idle**
- Ambient animations (particles, player model) run at ~30 fps and stop while the window is hidden
  or minimised and when the OS asks for reduced motion; background videos pause when hidden.
- Task progress is sampled every 150 ms and sent only when it changed; game log lines are batched
  every 60 ms.

**Downloads and launches**
- Bounded parallel downloads (default 12), resumable, hash-verified; libraries, assets and Java
  runtimes are stored once and shared by all instances.
- Before a launch only existence and size are checked; full hashing happens on *Verify files* or
  after a failure.
- JVM flags are probed once per Java runtime and cached.

## Re-running

```sh
pnpm tauri build --bundles deb,appimage
scripts/perf-linux.sh 3
```
