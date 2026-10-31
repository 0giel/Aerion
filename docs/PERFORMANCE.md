# Performance

Targets from the specification: **start-up < 1.5 s** to an interactive window, **< 200 MB RAM**
for the launcher at rest, no work while idle, smooth UI with long lists and logs.

## Measurements

Machine: Zorin OS 18.1 (X11), Linux 7.0, NVIDIA RTX 3060 Ti (driver 580), WebKitGTK 2.52.6,
release build (`pnpm tauri build`, LTO, `codegen-units = 1`, `opt-level = 3`, stripped).
Method: `scripts/perf-linux.sh` — start `target/release/luncher`, wait for the frontend's `UI ready` log line,
idle 10 s on the home screen, then sum PSS/RSS over the launcher and its WebKit processes and
sample CPU twice: 10–20 s after start (window focused, ambient animations running = "active")
and 35–45 s (no input for 30 s = "idle"). Three runs, warm disk cache. PSS (proportional set
size) counts each shared page once across processes; RSS double-counts shared libraries.

| | Target | Run 1 | Run 2 | Run 3 |
|---|---|---|---|---|
| Process start → `UI ready` | < 1.5 s | 0.37 s | 0.42 s | 0.37 s |
| PSS, 10 s after start (launcher + web + network) | < 200 MB | 201 MB | 201 MB | 197 MB |
| PSS, settled (45 s) | < 200 MB | 183 MB | 183 MB | 179 MB |
| RSS sum, 10 s (double-counts shared libraries) | — | 373 MB | 371 MB | 369 MB |
| CPU while in use (window focused, animations on) | — | 35 % | 32 % | 23 % |
| CPU idle (no input for 30 s) | ~0 | 0.9 % | 0.6 % | 0.7 % |

CPU is a percentage of one core. Settled PSS split: launcher process 63–67 MB, WebKit web
process 100–101 MB, network process 16 MB. Of the total, ~95 MB are pages of shared libraries
(WebKitGTK and JavaScriptCore alone ~80 MB), which other WebKitGTK apps share.

History on this machine (same method):

| Change | PSS (10 s) | Idle CPU |
|---|---|---|
| First release build | 228 MB | 81 % |
| Ambient gating, static ribbons, shared 20 fps clock (D16) | 228 MB | 0.8 % |
| ≤ 4 Tokio workers, `M_ARENA_MAX=2` (D17) | 228 MB → 216 MB settled | 0.8 % |
| No Mesa EGL vendor on NVIDIA-only machines (D13) | 199 MB → 182 MB settled | 0.7 % |

### Sizes

| Artefact | Size |
|----------|------|
| `luncher` binary (x86_64, stripped, frontend embedded) | 20.2 MB |
| `.deb` | 8.6 MB |
| AppImage (bundles WebKitGTK + GTK) | 88.6 MB |
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
- Tokio uses at most 4 worker threads and glibc at most 2 malloc arenas (D17).
- On machines where NVIDIA is the only GPU driver, Mesa's EGL vendor (and its LLVM) is not
  loaded into the WebKit processes (D13): −31 MB PSS here.

**Idle**
- Ambient animations (particles, player model, background video) run only while the launcher is
  in use — window visible and focused, input within the last 30 s — on one shared ~20 fps clock,
  and never when the OS asks for reduced motion. Without GPU compositing (WebKitGTK here) every
  animated frame repaints the whole window, so this is the difference between ~1 % and ~80 % of
  a core at rest (D16). Background ribbons are static; particles redraw only their own boxes.
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
