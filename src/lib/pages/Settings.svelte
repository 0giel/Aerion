<script lang="ts">
  import { open as openDialog } from "@tauri-apps/plugin-dialog";
  import { openUrl } from "@tauri-apps/plugin-opener";
  import Icon from "../components/Icon.svelte";
  import Switch from "../components/Switch.svelte";
  import Logo from "../components/Logo.svelte";
  import { app } from "../stores/app.svelte";
  import { router } from "../stores/router.svelte";
  import { toasts } from "../stores/toasts.svelte";
  import { themes } from "../theme/engine.svelte";
  import {
    commands,
    errorMessage,
    type AfterLaunch,
    type JavaInstall,
    type JvmPreset,
    type ModSource,
    type Settings,
  } from "../api";
  import { LOCALES, systemLocale, t } from "../i18n/index.svelte";

  const s = $derived(app.settings as Settings);
  let cfKey = $state("");
  let cfStatus = $state<{ configured: boolean; fromEnv: boolean } | null>(null);
  let javas = $state.raw<JavaInstall[]>([]);
  let proxy = $state(app.settings?.proxy ?? "");
  let clientId = $state(app.settings?.msaClientId ?? "");

  const sections = ["general", "appearance", "java", "network", "accounts", "mods", "data", "about"] as const;

  $effect(() => {
    commands
      .curseforgeStatus()
      .then((v) => (cfStatus = v))
      .catch(() => {});
    void detectJava();
  });

  async function detectJava() {
    javas = await commands.listJava().catch(() => []);
  }

  async function save(patch: Partial<Settings>) {
    try {
      await app.saveSettings(patch);
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  }

  async function saveCf(remove = false) {
    try {
      cfStatus = await commands.setCurseforgeKey(remove ? null : cfKey);
      cfKey = "";
      toasts.success(remove ? t("settings.cfKeyRemoved") : t("settings.cfKeySaved"));
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  }

  async function importTheme() {
    const f = await openDialog({ multiple: false, filters: [{ name: "Theme", extensions: ["json"] }] });
    if (typeof f !== "string") return;
    try {
      const theme = await commands.importTheme(f);
      await themes.loadUser();
      await save({ theme: theme.id, themeFollowSystem: false });
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  }

  async function chooseDataDir() {
    const dir = await openDialog({ directory: true, multiple: false });
    if (typeof dir === "string") {
      await save({ dataDir: dir });
      toasts.info(t("common.restartRequired"));
    }
  }

  function scrollTo(id: string) {
    document.getElementById(`set-${id}`)?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  const majors = [8, 17, 21, 25];
  const sysLang = LOCALES.find((l) => l.id === systemLocale())?.name ?? "";
  const totalMb = $derived(app.info?.totalMemoryMb ?? 8192);
</script>

<div class="page settings">
  <header class="page-header"><h1>{t("settings.title")}</h1></header>
  <nav class="toc row wrap">
    {#each sections as sec (sec)}<button class="chip" onclick={() => scrollTo(sec)}
        >{t(`settings.sections.${sec}`)}</button
      >{/each}
  </nav>

  {#if s}
    <div class="col gap-4 body">
      <section id="set-general" class="card col gap-3">
        <h2>{t("settings.sections.general")}</h2>
        <div class="field">
          <label for="lang">{t("settings.language")}</label>
          <select
            id="lang"
            class="select"
            value={s.language ?? ""}
            onchange={(e) => save({ language: (e.target as HTMLSelectElement).value || null })}
          >
            <option value="">{t("settings.languageSystem", { lang: sysLang })}</option>
            {#each LOCALES as l (l.id)}<option value={l.id}>{l.name}</option>{/each}
          </select>
        </div>
        <div class="field">
          <label for="after">{t("settings.afterLaunch")}</label>
          <select
            id="after"
            class="select"
            value={s.afterLaunch}
            onchange={(e) => save({ afterLaunch: (e.target as HTMLSelectElement).value as AfterLaunch })}
          >
            {#each ["keep", "minimize", "hide", "close"] as a (a)}<option value={a}
                >{t(`settings.afterLaunchOptions.${a}`)}</option
              >{/each}
          </select>
        </div>
        <Switch
          label={t("settings.closeToTray")}
          checked={s.closeToTray}
          onchange={(v) => save({ closeToTray: v })}
        />
        <Switch
          label={t("settings.showConsole")}
          checked={s.showConsoleOnLaunch}
          onchange={(v) => save({ showConsoleOnLaunch: v })}
        />
      </section>

      <section id="set-appearance" class="card col gap-3">
        <div class="row between">
          <h2>{t("settings.sections.appearance")}</h2>
          <div class="row">
            <button class="btn sm" onclick={importTheme}
              ><Icon name="upload" size={15} />{t("settings.importTheme")}</button
            >
            <button class="btn sm" onclick={() => commands.openFolder({ kind: "themes" })}
              ><Icon name="folder" size={15} />{t("settings.themesFolder")}</button
            >
            <button class="btn sm primary" onclick={() => router.go("themeEditor")}
              ><Icon name="palette" size={15} />{t("settings.editTheme")}</button
            >
          </div>
        </div>
        <div class="themes">
          {#each themes.all as th (th.id)}
            <button
              class="theme"
              aria-pressed={!s.themeFollowSystem && s.theme === th.id}
              onclick={() => save({ theme: th.id, themeFollowSystem: false })}
            >
              <span
                class="swatch"
                style:background={th.background.type === "gradient" && th.background.gradient
                  ? th.background.gradient
                  : th.colors.bg}
              >
                <span
                  class="panel"
                  style:background={th.colors.surface}
                  style:border-color={th.colors.border}
                >
                  <span class="line" style:background={th.colors.text}></span>
                  <span class="line short" style:background={th.colors.textMuted}></span>
                  <span class="pill" style:background={th.colors.accent}></span>
                </span>
              </span>
              <span class="row between tname"
                ><span class="truncate">{th.name}</span>{#if themes.isBuiltin(th.id)}<span class="tiny faint"
                    >{t("themeEditor.builtIn")}</span
                  >{/if}</span
              >
            </button>
          {/each}
        </div>
        <Switch
          label={t("settings.followSystem")}
          checked={s.themeFollowSystem}
          onchange={(v) => save({ themeFollowSystem: v })}
        />
        {#if s.themeFollowSystem}
          <div class="grid2">
            <div class="field">
              <label for="tl"><Icon name="sun" size={14} /> {t("settings.lightTheme")}</label>
              <select
                id="tl"
                class="select"
                value={s.themeLight}
                onchange={(e) => save({ themeLight: (e.target as HTMLSelectElement).value })}
              >
                {#each themes.all as th (th.id)}<option value={th.id}>{th.name}</option>{/each}
              </select>
            </div>
            <div class="field">
              <label for="td"><Icon name="moon" size={14} /> {t("settings.darkTheme")}</label>
              <select
                id="td"
                class="select"
                value={s.themeDark}
                onchange={(e) => save({ themeDark: (e.target as HTMLSelectElement).value })}
              >
                {#each themes.all as th (th.id)}<option value={th.id}>{th.name}</option>{/each}
              </select>
            </div>
          </div>
        {/if}
      </section>

      <section id="set-java" class="card col gap-3">
        <h2>{t("settings.sections.java")}</h2>
        <div class="field">
          <span class="label">{t("settings.defaultMemory")}</span>
          <label class="row check"
            ><input
              type="checkbox"
              checked={s.maxMemoryMb === null}
              onchange={(e) =>
                save({
                  maxMemoryMb: (e.target as HTMLInputElement).checked
                    ? null
                    : (app.info?.defaultMemoryMb ?? 4096),
                })}
            />{t("instance.settings.memoryAuto", { mb: app.info?.defaultMemoryMb ?? 4096 })}</label
          >
          {#if s.maxMemoryMb !== null}
            <div class="row gap-3">
              <input
                type="range"
                min="1024"
                max={Math.max(2048, Math.floor((totalMb * 0.85) / 256) * 256)}
                step="256"
                value={s.maxMemoryMb}
                onchange={(e) => save({ maxMemoryMb: Number((e.target as HTMLInputElement).value) })}
                aria-label={t("settings.defaultMemory")}
              />
              <strong class="mono">{s.maxMemoryMb} MB</strong>
            </div>
          {/if}
        </div>
        <div class="field">
          <span class="label">{t("settings.jvmPreset")}</span>
          <div class="segmented" role="group">
            {#each ["standard", "performance", "lowMemory"] as p (p)}<button
                aria-pressed={s.jvmPreset === p}
                onclick={() => save({ jvmPreset: p as JvmPreset })}
                >{t(`instance.settings.presets.${p}`)}</button
              >{/each}
          </div>
          <span class="hint">{t(`instance.settings.presetHints.${s.jvmPreset}`)}</span>
        </div>
        <Switch
          label={t("settings.autoJava")}
          hint={t("settings.autoJavaHint")}
          checked={s.javaAutoInstall}
          onchange={(v) => save({ javaAutoInstall: v })}
        />
        <div class="field">
          <div class="row between">
            <span class="label">{t("settings.javaOverrides")}</span><button
              class="btn sm ghost"
              onclick={detectJava}><Icon name="refresh" size={14} />{t("settings.detectJava")}</button
            >
          </div>
          <span class="hint">{t("settings.javaOverridesHint")}</span>
          {#each majors as m (m)}
            <div class="row java-row">
              <span class="badge">Java {m}</span>
              <select
                class="select"
                value={s.javaOverrides[String(m)] ?? ""}
                onchange={(e) => {
                  const v = (e.target as HTMLSelectElement).value;
                  const next = { ...s.javaOverrides };
                  if (v) next[String(m)] = v;
                  else delete next[String(m)];
                  save({ javaOverrides: next });
                }}
              >
                <option value="">{t("common.auto")}</option>
                {#each javas.filter((j) => j.major === m) as j (j.path)}<option value={j.path}
                    >{j.version} · {j.vendor ?? j.source} · {j.path}</option
                  >{/each}
              </select>
            </div>
          {/each}
        </div>
      </section>

      <section id="set-network" class="card col gap-3">
        <h2>{t("settings.sections.network")}</h2>
        <div class="field">
          <label for="conc">{t("settings.concurrency")}: <strong>{s.downloadConcurrency}</strong></label>
          <input
            id="conc"
            type="range"
            min="1"
            max="32"
            value={s.downloadConcurrency}
            onchange={(e) => save({ downloadConcurrency: Number((e.target as HTMLInputElement).value) })}
          />
        </div>
        <div class="field">
          <label for="proxy">{t("settings.proxy")}</label>
          <div class="row">
            <input
              id="proxy"
              class="input mono"
              bind:value={proxy}
              placeholder={t("settings.proxyPlaceholder")}
            /><button class="btn" onclick={() => save({ proxy: proxy.trim() || null })}
              >{t("common.save")}</button
            >
          </div>
          <span class="hint">{t("settings.proxyHint")}</span>
        </div>
      </section>

      <section id="set-accounts" class="card col gap-3">
        <h2>{t("settings.sections.accounts")}</h2>
        <div class="field">
          <label for="cid">{t("settings.clientId")}</label>
          <div class="row">
            <input
              id="cid"
              class="input mono"
              bind:value={clientId}
              placeholder={t("accounts.clientIdPlaceholder")}
            /><button
              class="btn"
              onclick={() => save({ msaClientId: clientId.trim() || null }).then(() => app.refreshAccounts())}
              >{t("common.save")}</button
            >
          </div>
          <span class="hint">{t("settings.clientIdHint")}</span>
        </div>
      </section>

      <section id="set-mods" class="card col gap-3">
        <h2>{t("settings.sections.mods")}</h2>
        <div class="field">
          <span class="label">{t("settings.modSource")}</span>
          <div class="segmented" role="group">
            {#each [["modrinth", "Modrinth"], ["curseforge", "CurseForge"], ["both", t("mods.sourceBoth")]] as [v, l] (v)}<button
                aria-pressed={s.modSource === v}
                onclick={() => save({ modSource: v as ModSource })}>{l}</button
              >{/each}
          </div>
        </div>
        <div class="field">
          <span class="label"
            >{t("settings.cfKey")}
            {#if cfStatus?.configured}<span class="badge success">{t("settings.cfConfigured")}</span
              >{/if}</span
          >
          {#if cfStatus?.fromEnv}
            <span class="hint">{t("settings.cfKeyEnv")}</span>
          {:else}
            <div class="row">
              <input
                class="input mono"
                type="password"
                autocomplete="off"
                bind:value={cfKey}
                placeholder="$2a$10$…"
                aria-label={t("settings.cfKey")}
              />
              <button class="btn primary" disabled={!cfKey.trim()} onclick={() => saveCf()}
                >{t("common.save")}</button
              >
              {#if cfStatus?.configured}<button class="btn danger" onclick={() => saveCf(true)}
                  >{t("common.remove")}</button
                >{/if}
            </div>
          {/if}
          <span class="hint"
            >{t("settings.cfKeyHint")}
            <button class="linkbtn" onclick={() => openUrl("https://console.curseforge.com/")}
              >console.curseforge.com</button
            ></span
          >
        </div>
      </section>

      <section id="set-data" class="card col gap-3">
        <h2>{t("settings.sections.data")}</h2>
        <div class="field">
          <span class="label">{t("settings.dataDir")}</span>
          <div class="row">
            <code class="path grow selectable">{app.info?.dataDir}</code><button
              class="btn"
              onclick={chooseDataDir}>{t("common.browse")}</button
            ><button
              class="icon-btn"
              aria-label={t("common.openFolder")}
              onclick={() => commands.openFolder({ kind: "data" })}><Icon name="folder" /></button
            >
          </div>
          <span class="hint">{t("settings.dataDirHint")}</span>
        </div>
        <div class="field">
          <span class="label">{t("settings.rootDir")}</span>
          <div class="row">
            <code class="path grow selectable">{app.info?.rootDir}</code><button
              class="icon-btn"
              aria-label={t("common.openFolder")}
              onclick={() => commands.openFolder({ kind: "root" })}><Icon name="folder" /></button
            >
          </div>
          {#if app.info?.portable}<span class="badge accent">{t("settings.portable")}</span>{/if}
        </div>
        <div class="row">
          <button class="btn sm" onclick={() => commands.openFolder({ kind: "logs" })}
            ><Icon name="terminal" size={15} />{t("settings.openLogs")}</button
          >
        </div>
      </section>

      <section id="set-about" class="card col gap-3 about">
        <div class="row gap-3">
          <span class="logo"><Logo /></span>
          <div class="col gap-1">
            <h2>Aerion</h2>
            <span class="small muted">{t("settings.version", { version: app.info?.version ?? "" })}</span>
          </div>
        </div>
        <p class="small">{t("settings.about")}</p>
        <div class="alert">
          <Icon name="info" size={18} /><span class="small">{t("app.disclaimer")}</span>
        </div>
        <p class="small faint">{t("settings.thirdParty")}</p>
        <p class="tiny faint">
          {t("settings.userAgent")}: <code class="selectable">{app.info?.userAgent}</code>
        </p>
      </section>
    </div>
  {/if}
</div>

<style>
  .toc {
    position: sticky;
    top: calc(-1 * var(--sp-5));
    z-index: 3;
    padding: var(--sp-2) 0;
    margin-bottom: var(--sp-2);
    /* Same width as the cards, so the fade does not read as a bar across the page. */
    max-width: 52rem;
    background: linear-gradient(var(--c-bg) 70%, transparent);
  }
  .body {
    max-width: 52rem;
    padding-bottom: 3rem;
  }
  .grid2 {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: var(--sp-3);
  }
  .check {
    gap: 0.5rem;
    cursor: pointer;
  }
  .themes {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(9.5rem, 1fr));
    gap: var(--sp-3);
  }
  .theme {
    display: flex;
    flex-direction: column;
    gap: 0.45rem;
    padding: 0.4rem;
    border: 1px solid var(--c-border);
    border-radius: var(--r-lg);
    background: var(--c-surface-2);
    cursor: pointer;
    text-align: left;
    transition: border-color var(--dur) var(--ease);
  }
  .theme[aria-pressed="true"] {
    border-color: var(--c-accent);
    box-shadow: 0 0 0 2px var(--c-accent-soft);
  }
  .swatch {
    display: grid;
    place-items: center;
    height: 5rem;
    border-radius: calc(var(--r-lg) - 0.3rem);
    background-size: cover;
  }
  .panel {
    width: 70%;
    padding: 0.45rem;
    border-radius: 0.45rem;
    border: 1px solid;
    display: flex;
    flex-direction: column;
    gap: 0.3rem;
  }
  .line {
    height: 0.3rem;
    border-radius: 1rem;
    width: 80%;
  }
  .line.short {
    width: 55%;
  }
  .pill {
    height: 0.5rem;
    width: 40%;
    border-radius: 1rem;
  }
  .tname {
    padding: 0 0.3rem 0.15rem;
    font-weight: 560;
    font-size: 0.9rem;
  }
  .java-row .badge {
    min-width: 4.6rem;
    justify-content: center;
  }
  .path {
    font-family: var(--font-mono);
    font-size: 0.85rem;
    padding: 0.45rem 0.7rem;
    border-radius: var(--r-md);
    background: var(--c-hover);
    overflow-wrap: anywhere;
  }
  .linkbtn {
    border: 0;
    padding: 0;
    background: none;
    color: var(--c-accent);
    cursor: pointer;
    font-size: inherit;
  }
  .about .logo {
    display: grid;
    width: 3rem;
    height: 3rem;
    color: var(--c-accent);
  }
  .about .logo :global(svg) {
    width: 100%;
    height: 100%;
  }
</style>
