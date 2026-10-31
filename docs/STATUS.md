# Work status (2026-10-31)

Snapshot of where the implementation stands; see `PLAN.md` for milestones.

## Done and verified
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

## In progress
- **Blank window in `pnpm tauri dev` on this machine** (Zorin, NVIDIA 580, WebKitGTK 2.52):
  the window shows only the native background color (`#141519`), no frontend log line arrives,
  so the page JavaScript apparently does not run (or WebKit does not paint). Next checks:
  1. `freezePrototype: true` in `tauri.conf.json` → try `false`;
  2. dev CSP (`devCsp`) blocking the Vite module/HMR → try without CSP;
  3. `WEBKIT_DISABLE_COMPOSITING_MODE=1` (NVIDIA);
  4. `frontend_log` command + global error handlers were added to surface JS errors.
- Screenshots: `LUNCHER_DEV_ON_TOP=1` (debug builds) keeps the window on top for `xwd -name Luncher`.

## Not started yet
- `docs/DECISIONS.md`, `docs/THEMING.md` (+ `themes/theme.schema.json`), `docs/AZURE_SETUP.md`,
  `docs/PERFORMANCE.md`, `README.md`; vitest tests for i18n/theme schema.
- Live tests through the UI: Fabric + Sodium/Fabric API install, Forge/NeoForge launches, modpack import.
- Release bundles (`pnpm tauri build`), performance measurements.
- Owner actions: Azure app registration + Mojang review (client ID), CurseForge API key.
