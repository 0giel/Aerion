# Luncher — implementation plan

Status: **approved by the owner up front** ("do everything, then run the launcher"), so work
continues through the milestones without stopping. Decisions taken along the way live in
[`DECISIONS.md`](DECISIONS.md).

Priorities, in order: (1) the game always starts, (2) performance and low resource use,
(3) convenience, (4) visual customisation, (5) extensibility.

---

## 1. Research summary (verified against live services on 2026-10-30)

Nothing below is from memory alone; each item was checked against the live API or official docs.

### Mojang
- Manifest: `https://piston-meta.mojang.com/mc/game/version_manifest_v2.json` (921 versions).
  Latest release **26.3**, latest snapshot **26.4-snapshot-6**. Since 2026 Mojang uses
  *year.drop[.hotfix]* numbering (26.1, 26.1.2, 26.3); the last 1.x line is **1.21.11**.
  → Never sort versions by parsing the id; use `releaseTime` and manifest order.
- Version JSON variants seen in fixtures:
  - 1.8.9 / 1.12.2: legacy `minecraftArguments` string, natives via `natives` map +
    `classifiers` + `extract.exclude`, `${arch}` inside classifier names (`natives-windows-${arch}`),
    OS rules (`disallow osx`), `javaVersion` = `jre-legacy` (8).
  - 1.16.5: `arguments.{game,jvm}` with rules, `os.version` regex (`^10\.`), `os.arch: x86`.
  - 1.20.1: Java 17 (`java-runtime-gamma`); 1.21.11: Java 21 (`java-runtime-delta`).
  - 26.x: Java **25** (`java-runtime-epsilon`), natives as plain classpath jars with OS rules,
    JVM args point at **sub-directories** of the natives dir
    (`${natives_directory}/java`, `/jna`, `/lwjgl`, `/netty`), new placeholders `${clientid}`,
    `${auth_xuid}`, and `--userType` is gone (removed in 1.21.9). No `client_mappings` (unobfuscated).
  - Logging: `logging.client` (`client-1.7.xml`, `client-1.12.xml`, `client-1.21.2.xml`), log4j2 XML
    layout → the console parses `<log4j:Event>` records.
- Assets: `assetIndex` → `https://resources.download.minecraft.net/<hh>/<hash>`.
- Java runtimes: `https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json`.
  Platforms: `linux`, `linux-i386`, `mac-os`, `mac-os-arm64`, `windows-x64`, `windows-x86`, `windows-arm64`.
  Gaps: no Linux ARM64 at all, no Java 8 on Apple Silicon / Windows ARM → **Adoptium fallback**
  (`https://api.adoptium.net/v3/assets/latest/{major}/hotspot?...`, returns link + SHA-256).
  Runtime manifests list `file` (raw/lzma downloads with SHA-1, `executable` flag), `directory`, `link`.

### Loaders
- Fabric meta v2: `/v2/versions/loader/{mc}` and `/v2/versions/loader/{mc}/{loader}/profile/json`
  (a version JSON with `inheritsFrom`; libraries carry `url` + sha1/sha256/sha512/size).
  For 26.x the intermediary is `0.0.0` and absent from the profile (unobfuscated game).
- Quilt meta v3: same shape (`/v3/versions/loader/...`), profile includes `hashed` + intermediary.
- NeoForge: versions from `https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge`
  (`26.3.0.99` ⇒ MC 26.3; `21.1.x` ⇒ MC 1.21.1); MC 1.20.1 lives under `net.neoforged:forge`
  (`1.20.1-47.1.x`). Installer jar: `install_profile.json` (spec 1) + `version.json`;
  26.x needs one client processor (`installertools PROCESS_MINECRAFT_JAR`).
- Forge: `promotions_slim.json` (latest/recommended per MC) and `maven-metadata.xml`.
  Three installer generations, all handled:
  1. legacy (1.8.9 … early 1.12.2): `install` + embedded `versionInfo`, universal jar inside installer;
  2. spec 0 with `/version.json`, no processors (1.12.2 ≥ 2851), libraries with empty URL come from
     the installer's `maven/` folder;
  3. spec 1 with processors (1.13+, e.g. 1.20.1: MCP_DATA, DOWNLOAD_MOJMAPS, MERGE_MAPPING,
     jarsplitter, ART, binarypatcher). Data tokens: `[maven coords]`, `'literal'`, `/path/in/installer`,
     built-ins `{SIDE} {MINECRAFT_JAR} {MINECRAFT_VERSION} {ROOT} {INSTALLER} {LIBRARY_DIR}`.
  Modern Forge JVM args use `${library_directory}`, `${classpath_separator}`, `${version_name}`.

### Modrinth API v2
- Base `https://api.modrinth.com/v2`; headers confirm **300 req/min** (`x-ratelimit-*`).
- Used endpoints: `/search` (facets), `/project/{id}`, `/project/{id}/version?loaders=&game_versions=`,
  bulk `/projects?ids=`, `/versions?ids=`, `POST /version_files` (hash lookup),
  `POST /version_files/update` (update check), `/tag/category`.
- New fields in 2026 responses (`environment`, `disclosure_types`, `all_project_types`) → models
  are tolerant (`#[serde(default)]`, unknown fields ignored).
- A unique `User-Agent` `author/luncher/version (contact)` is mandatory.

### CurseForge Core API v1
- Base `https://api.curseforge.com`, header `x-api-key`. `GET /v1/mods/search` (gameId, classId,
  categoryId, gameVersion, modLoaderType, sortField 1–12, sortOrder, index+pageSize ≤ 10 000,
  pageSize ≤ 50), `GET /v1/mods/{id}`, `POST /v1/mods`, `GET /v1/mods/{id}/description`,
  `GET /v1/mods/{id}/files`, `POST /v1/mods/files`, `POST /v1/fingerprints/432`, `GET /v1/categories`.
- Enums: ModLoaderType 0 Any,1 Forge,4 Fabric,5 Quilt,6 NeoForge; FileReleaseType 1/2/3;
  FileRelationType 1 Embedded,2 Optional,3 Required,4 Tool,5 Incompatible,6 Include.
  Minecraft gameId **432**; classIds mods 6, modpacks 4471, resource packs 12, shaders 6552, data packs 6945.
- **3rd-Party API Terms** (rev. 2024-08-14) — consequences for us:
  - §3.1 forbids "save or cache any data obtained through the API" → **no disk or HTTP cache for
    CurseForge responses or images**; only the identifiers of files the user actually installed are kept
    (needed for updates).
  - §2.2 keys are personal and may not be shared → **no key in the repo or binary**; the user enters
    their own (settings → keyring, or `CURSEFORGE_API_KEY`).
  - §3.1 forbids concealing identity via proxy/VPN → documented next to the proxy setting.
  - Files with `downloadUrl == null` (author disabled 3rd-party distribution) are never fetched from
    elsewhere; the UI links to the file page instead.

### Microsoft → Xbox → Minecraft
- Authorize `https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize`, scope
  `XboxLive.signin offline_access`, PKCE S256, `http://localhost:<any port>` (port ignored for
  localhost). Token endpoint `/token`; device code `/devicecode` + polling
  (`authorization_pending`, `slow_down` (+5 s, RFC 8628), `authorization_declined`, `expired_token`).
  Refresh tokens rotate — always persist the new one.
- XBL `POST https://user.auth.xboxlive.com/user/authenticate` (`RpsTicket: "d=<token>"`),
  XSTS `POST https://xsts.auth.xboxlive.com/xsts/authorize` (`rp://api.minecraftservices.com/`).
  XErr: 2148916227 banned, …233 no Xbox profile, …235 country, …236/…237 adult verification (KR),
  …238 child account not in a family.
- `POST https://api.minecraftservices.com/authentication/login_with_xbox` (token 24 h);
  403 "Invalid app registration" = Azure app not yet approved by Mojang (form https://aka.ms/mce-reviewappid);
  403 `ACCOUNT_SUSPENDED` handled separately. Ownership: `GET /entitlements/mcstore`; profile:
  `GET /minecraft/profile` (404 `NOT_FOUND` = no licence / no profile yet, also Game Pass users who never
  opened the official launcher). XUID comes from the `xuid` claim of the Minecraft access token (JWT).

### Tooling (crates.io / npm, 2026-10-30)
Tauri 2.12 (3.0 is alpha — not used), tauri-specta `=2.0.0-rc.26` + specta `=2.0.0-rc.26` +
specta-typescript `0.0.13` (pinned exactly, as upstream requires), keyring 4.2 (`v1` facade: Keychain /
Credential Manager / Secret Service via zbus), reqwest 0.13 (rustls, platform verifier, *ring* provider),
tokio 1.54, zip 9, Svelte 5.57, Vite 8, TypeScript 6 (TS 7 is not yet supported by svelte-check /
typescript-eslint), ESLint 10, Prettier 3.

---

## 2. Architecture

```
┌──────────────────────────── Svelte 5 UI (src/) ─────────────────────────────┐
│ pages (lazy) · components · stores (runes) · i18n · theme engine (CSS vars) │
└──────────────▲───────────────────────────────▲──────────────────────────────┘
   typed commands (bindings.ts, tauri-specta)   │ typed events (throttled)
┌──────────────┴───────────────────────────────┴──────────────────────────────┐
│ src-tauri: thin IPC layer — commands/, events, task registry, game registry, │
│ img:// proxy (disk-cached thumbnails), theme watcher, tray, single-instance  │
└──────────────▲───────────────────────────────────────────────────────────────┘
               │ plain async Rust API (no Tauri types)
┌──────────────┴───────────────────────────────────────────────────────────────┐
│ crates/launcher-core                                                          │
│  net (client, JSON cache, downloader) · mojang (manifest, version, rules,     │
│  args, assets, libraries) · java (detect, Mojang runtimes, Adoptium, flags) · │
│  game (install, launch, logs, crash) · auth (MSA, Xbox, Minecraft) ·          │
│  accounts (+ SecretStore/keyring) · instances (+ content manifest) ·          │
│  loaders (fabric/quilt meta, forge/neoforge installers) · mods (ModProvider,  │
│  modrinth, curseforge, selection, dependency resolver, updates) · modpacks    │
└───────────────────────────────────────────────────────────────────────────────┘
```

- **launcher-core** has zero Tauri dependencies and is unit/integration tested on its own.
  IPC-visible types derive `specta::Type` behind the optional `specta` feature.
- Long work runs as **tasks**: `TaskHandle` = cancellation token + progress sink. The Tauri layer turns
  progress into `task-update` events at ≤ 10 Hz per task.
- Game processes are tracked in a registry; log lines are batched (~60 ms) into `game-log` events and
  kept in a ring buffer (last 20k lines) so the console can be reopened.
- Everything heavy (I/O, hashing, zip, processes) is async/tokio or `spawn_blocking`; the UI thread
  only renders.

### Repository layout
```
Cargo.toml (workspace) · package.json · vite.config.ts · svelte.config.js · tsconfig.json
crates/launcher-core/{src,tests/fixtures}
src-tauri/{src,capabilities,icons,tauri.conf.json}
src/{App.svelte,main.ts,lib/{bindings.ts,i18n,theme,stores,components,icons},pages}
themes/*.json (built-in themes, embedded at build time)
docs/{PLAN,DECISIONS,THEMING,PERFORMANCE,AZURE_SETUP}.md · .github/workflows/ci.yml · .env.example
```

### Data on disk (default root: `%LOCALAPPDATA%\Luncher`, `~/.local/share/luncher`,
`~/Library/Application Support/Luncher`; `portable` file next to the exe ⇒ `<exe dir>/data`)
```
settings.json  accounts.json (no secrets)  themes/  logs/
cache/http/ (Mojang & Modrinth JSON, ETag)  cache/images/ (thumbnails; never CurseForge)
meta/versions/<id>.json   versions/<id>/<id>.jar   libraries/ (maven layout, shared)
assets/{indexes,objects,log_configs,virtual}   runtimes/   natives/<version>/
installers/   instances/<name>/{instance.json,content.json,.backups/,minecraft/}
```
Instances store human-editable JSON; no SQLite is needed (hundreds of records at most).

---

## 3. Key designs

- **Downloader**: bounded concurrency (setting, default 8), retries with exponential backoff + jitter,
  `.part` files resumed with `Range`, SHA-1/SHA-512/size verification, cancellation, aggregated
  progress. Fast path before launch: existence + size only; full hashing on "Verify files" or after a
  failure.
- **Launch pipeline**: resolve instance → refresh account session → ensure installed → build args
  (rules, both argument formats, placeholders, classpath, natives) → spawn (args as array,
  `CREATE_NO_WINDOW` + `javaw.exe` on Windows) → stream logs → crash analysis on abnormal exit.
- **Java**: detect (JAVA_HOME, PATH, common dirs, Mojang/other launcher runtimes) using the `release`
  file; auto-install the `javaVersion.component` from Mojang, Adoptium fallback; presets
  Standard / Performance / Low-RAM generated per Java major; custom flags validated by a cached
  `java <flags> -version` probe.
- **Accounts**: metadata in `accounts.json`, secrets (MS refresh token, Minecraft token) only in the
  OS keyring, chunked to stay under Windows Credential Manager blob limits. Offline accounts unlock only
  after a licensed Microsoft account was added (or `LAUNCHER_DEV_OFFLINE=1` in debug builds).
- **Mods**: `ModProvider` trait (search, project, versions, bulk lookups, hash identification,
  update check) implemented for Modrinth and CurseForge; UI works on a unified model; "both" mode
  interleaves results and merges duplicates by slug/title. Resolver installs required dependencies,
  proposes optional ones, warns about incompatibilities, and records provenance in `content.json`.
- **Theming**: design tokens → CSS custom properties; a theme is one JSON file (+ optional CSS);
  hot-reload via a file watcher; built-in dark / light / AMOLED / accent variants + "follow system".
- **Security**: minimal capabilities, strict CSP (no remote scripts; remote images only through the
  `img://` proxy), DOMPurify on all remote HTML/Markdown, zip-slip protection everywhere, hash checks,
  argument arrays only, validated instance names / JVM args, secrets masked in logs.

---

## 4. Milestones (each ends with fmt + clippy + tests + lint green and a commit)

The code for M0–M9 was written in one pass (commit `79add78`); the **Status** column tracks what
has been *verified* against its acceptance criterion. Details and the running log: [`STATUS.md`](STATUS.md).

| M | Scope | Acceptance | Status |
|---|-------|------------|--------|
| M0 | Skeleton, CI, lint, theme tokens + 2 themes, i18n pl/en | app window opens, `cargo test`, `pnpm check` green | ✅ (blank-window fontconfig hang fixed 2026-10-31) |
| M1 | Vanilla engine end-to-end + minimal "pick version → Play" | 26.3, 1.21.11, 1.20.1, 1.16.5, 1.8.9 and latest snapshot reach the title screen | ✅ smoke tool for all; 26.3 also via the UI |
| M2 | Microsoft accounts, keyring, offline gating | full chain unit-tested against a mock server; real login once Azure ID is approved | ✅ unit-tested; live login needs the owner's Azure ID |
| M3 | Instances + full UI (list, wizard, edit, clone, groups, console) | CRUD + launch from UI | create + launch + console verified; edit/clone/groups pending |
| M4 | Fabric, Quilt | Fabric 26.3 / 1.21.11 and Quilt 1.21.11 launch | ✅ Fabric 1.21.11 via UI; Quilt pending |
| M5 | Modrinth browser, install with dependencies, enable/disable, updates + undo, hash recognition | Sodium + Fabric API install and run | ✅ install, toggle, update check (channel fix); undo pending |
| M6 | CurseForge in the same browser (user key, missing `downloadUrl` handling) | mocked integration tests; live once a key is entered | code + mocks; live needs a key |
| M7 | Modpacks (.mrpack / CurseForge zip import+export, own format), resource packs, shaders | a Modrinth pack installs and starts | code + tests; live pending |
| M8 | NeoForge, Forge | NeoForge 26.3, Forge 1.20.1, Forge 1.8.9 launch | ✅ NeoForge 26.3 + Forge 1.20.1 via the app (NVIDIA env fix); Forge 1.8.9 pending |
| M9 | Theme editor, built-in themes, THEMING.md | live edits without restart; export/import | editor + 7 themes done; THEMING.md pending |
| M10 | Performance audit, accessibility, crash analysis polish, tray, updater hook, bundles, README | targets measured in PERFORMANCE.md | not started |

### Next steps (in order)
1. ~~Fix the blank window~~ ✅ · ~~onboarding → offline account → 26.3 → Play through the UI~~ ✅
2. ~~Wizard with a loader, Fabric + Sodium/Fabric API from Modrinth~~ ✅
3. ~~Forge 1.20.1 and NeoForge 26.3 launches~~ ✅ · a `.mrpack` import; Quilt; Forge 1.8.9.
4. Docs: README, DECISIONS, THEMING (+ `themes/theme.schema.json`), AZURE_SETUP, PERFORMANCE.
5. Vitest tests (i18n shape, theme schema), release build + bundles, performance measurements.

## 5. Risks and mitigations

| Risk | Mitigation |
|------|------------|
| Azure app needs Mojang approval (403 "Invalid app registration") | Clear error + `docs/AZURE_SETUP.md`; everything else works meanwhile; dev offline mode for tests |
| CurseForge terms (no caching, personal keys) | no CF cache, user-supplied key, documented |
| Forge/NeoForge processors change between generations | generic processor runner driven by `install_profile.json`; fixtures for each generation |
| 26.x-specific changes (Java 25, natives subdirs, no `--userType`) | data-driven arguments; natives sub-dirs pre-created; tests on 26.x fixtures |
| WebKitGTK + NVIDIA blank window | auto-set `WEBKIT_DISABLE_DMABUF_RENDERER=1` when the NVIDIA driver is present |
| Windows Credential Manager size limit for tokens | chunked secret storage |
| Linux dev box without root (this machine) | rootless "devroot" of `-dev` packages (see `CONTRIBUTING.md`) |
| Apple Silicon + legacy versions (no arm64 LWJGL 2) | documented limitation; x86_64 Java via Rosetta may be added later |

## 6. Manual actions needed from the owner
1. **Azure app registration + Mojang review** — steps in `docs/AZURE_SETUP.md`; then set
   `LAUNCHER_MSA_CLIENT_ID` or paste the ID in Settings → Accounts.
2. **CurseForge API key** — apply at https://console.curseforge.com, paste in Settings → Mods.
3. Optional: proper Linux dev packages (`sudo apt install libwebkit2gtk-4.1-dev …`) instead of the devroot.
