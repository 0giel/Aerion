<script lang="ts">
  import { open as openDialog, save as saveDialog } from "@tauri-apps/plugin-dialog";
  import { openUrl } from "@tauri-apps/plugin-opener";
  import KeybindEditor from "../components/KeybindEditor.svelte";
  import { DISCORD_URL, ISSUES_URL, REPO_URL } from "../links";
  import Icon from "../components/Icon.svelte";
  import { layout, NAV_MODES, NAV_SIDES, type NavMode } from "../stores/layout.svelte";
  import { dashboards } from "../stores/dashboards.svelte";
  import { dialogs } from "../stores/dialogs.svelte";
  import Advanced from "../components/Advanced.svelte";
  import Switch from "../components/Switch.svelte";
  import Logo from "../components/Logo.svelte";
  import ThemeGalleryModal from "./ThemeGalleryModal.svelte";
  import MemorySlider from "../components/MemorySlider.svelte";
  import { updates } from "../stores/updates.svelte";
  import { app } from "../stores/app.svelte";
  import { router } from "../stores/router.svelte";
  import { ui } from "../stores/ui.svelte";
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
  import { autoSummary } from "../javaAuto";
  import type { AutoPlan } from "../bindings";

  const s = $derived(app.settings as Settings);
  let cfKey = $state("");
  let cfStatus = $state<{ configured: boolean; fromEnv: boolean } | null>(null);
  let javas = $state.raw<JavaInstall[]>([]);
  let proxy = $state(app.settings?.proxy ?? "");
  let clientId = $state(app.settings?.msaClientId ?? "");
  let galleryOpen = $state(false);
  let autoPlan = $state.raw<AutoPlan | null>(null);
  // Heap and GC picked from the computer (on unless switched off).
  const javaAuto = $derived(app.settings?.javaAuto !== false);
  const javaAutoSummary = (p: AutoPlan) => autoSummary(p, false);
  $effect(() => {
    commands
      .autoJavaPlan(null)
      .then((p) => (autoPlan = p))
      .catch(() => {});
  });
  let optionsTemplate = $state.raw<{ source: string; savedAt: number } | null>(null);
  $effect(() => {
    commands
      .optionsTemplate()
      .then((tpl) => (optionsTemplate = tpl))
      .catch(() => {});
  });
  async function clearTemplate() {
    try {
      await commands.clearOptionsTemplate();
      optionsTemplate = null;
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  }

  const sections = ["general", "appearance", "java", "game", "mods", "data", "advanced", "about"] as const;

  // Game defaults are edited as text and saved when a field loses focus.
  const lines = (v: string) =>
    v
      .split("\n")
      .map((x) => x.trim())
      .filter(Boolean);
  const envToText = (env: Record<string, string>) =>
    Object.entries(env)
      .map(([k, v]) => `${k}=${v}`)
      .join("\n");
  function textToEnv(v: string): Record<string, string> {
    const env: Record<string, string> = {};
    for (const line of lines(v)) {
      const i = line.indexOf("=");
      if (i > 0) env[line.slice(0, i).trim()] = line.slice(i + 1);
    }
    return env;
  }

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

  // Everything (settings, themes, skins, HUD, shared game settings) to or from one file.
  async function exportAll() {
    const day = new Date().toISOString().slice(0, 10);
    const dest = await saveDialog({
      defaultPath: `aerion-settings-${day}.aerion`,
      filters: [{ name: "Aerion", extensions: ["aerion"] }],
    });
    if (!dest) return;
    try {
      const r = await commands.exportSettings(dest);
      toasts.success(t("settings.transfer.exported", { n: r.files + 1 }));
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  }
  async function importAll() {
    const src = await openDialog({
      multiple: false,
      filters: [{ name: "Aerion", extensions: ["aerion", "zip"] }],
    });
    if (typeof src !== "string") return;
    const ok = await dialogs.confirm({
      title: t("settings.transfer.importTitle"),
      text: t("settings.transfer.importText"),
      confirmLabel: t("settings.transfer.import"),
    });
    if (!ok) return;
    try {
      const r = await commands.importSettings(src);
      app.applySettings(r.settings);
      proxy = r.settings.proxy ?? "";
      clientId = r.settings.msaClientId ?? "";
      toasts.success(t("settings.transfer.imported", { themes: r.summary.themes, skins: r.summary.skins }));
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

  // Sections scroll in below the sticky chip row, which wraps to two lines on narrow windows.
  let tocHeight = $state(0);

  // The full licence texts are only loaded when someone opens them.
  let licenseText = $state("");
  async function loadLicenses() {
    if (!licenseText) licenseText = (await import("../../assets/licenses/THIRD_PARTY.txt?raw")).default;
  }

  async function newPage() {
    const name = await dialogs.prompt({ title: t("widgets.newPage"), placeholder: t("widgets.pageName") });
    if (!name?.trim()) return;
    const id = await dashboards.create(name.trim());
    router.go("dashboard", { id });
  }

  function scrollTo(id: string) {
    document.getElementById(`set-${id}`)?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  const majors = [8, 17, 21, 25];
  const sysLang = LOCALES.find((l) => l.id === systemLocale())?.name ?? "";
  let sharedKeysOpen = $state(false);
</script>

<div class="page settings">
  <header class="page-header"><h1>{t("settings.title")}</h1></header>
  <nav class="toc row wrap" bind:clientHeight={tocHeight}>
    {#each sections as sec (sec)}<button class="chip" onclick={() => scrollTo(sec)}
        >{t(`settings.sections.${sec}`)}</button
      >{/each}
  </nav>

  {#if s}
    <div class="col gap-4 body" style:--toc-h="{tocHeight}px">
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
        <Switch
          label={t("appUpdate.background")}
          hint={t("appUpdate.backgroundHint")}
          checked={s.updateInBackground}
          onchange={(v) => save({ updateInBackground: v })}
        />
        <Switch
          label={t("settings.aerionModUpdates")}
          hint={t("settings.aerionModUpdatesHint")}
          checked={s.aerionModUpdates}
          onchange={(v) => save({ aerionModUpdates: v })}
        />
        <div class="row wrap gap-2">
          <div class="grow">
            <Switch
              label={t("appUpdate.autoCheck")}
              checked={s.checkUpdates}
              onchange={(v) => save({ checkUpdates: v })}
            />
          </div>
          <button class="btn sm" disabled={updates.checking} onclick={() => updates.check(true)}
            >{#if updates.checking}<span class="spinner"></span>{:else}<Icon
                name="refresh"
                size={14}
              />{/if}{t("appUpdate.checkNow")}</button
          >
        </div>
      </section>

      <section id="set-appearance" class="card col gap-3">
        <h2>{t("settings.sections.appearance")}</h2>
        <div class="row wrap between gap-2">
          <span class="label">{t("settings.themeLabel")}</span>
          <div class="row wrap gap-2">
            <button class="btn sm" onclick={() => (galleryOpen = true)}
              ><Icon name="sparkles" size={15} />{t("gallery.open")}</button
            >
            <button class="btn sm" onclick={importTheme}
              ><Icon name="upload" size={15} />{t("settings.importTheme")}</button
            >
            <button class="btn sm" onclick={() => commands.openFolder({ kind: "themes" })}
              ><Icon name="folder" size={15} />{t("settings.themesFolder")}</button
            >
            <button class="btn sm" onclick={() => router.go("themeEditor", { new: "1" })}
              ><Icon name="plus" size={15} />{t("settings.newTheme")}</button
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
        <hr class="sep" />
        <div class="row wrap between gap-2">
          <div class="col layout-text">
            <span class="label">{t("settings.layoutLabel")}</span>
            <span class="small muted">{t("settings.layoutHint")}</span>
          </div>
          <div class="row wrap gap-2">
            <button class="btn sm" onclick={() => (ui.customize = "nav")}
              ><Icon name="sliders" size={15} />{t("settings.customizeMenu")}</button
            >
            <button class="btn sm" onclick={() => (ui.customize = "home")}
              ><Icon name="home" size={15} />{t("settings.customizeHome")}</button
            >
          </div>
        </div>
        <div class="col gap-3">
          <div class="field">
            <span class="label">{t("settings.ui.navSide")}</span>
            <div class="segmented wrap" role="group" aria-label={t("settings.ui.navSide")}>
              {#each NAV_SIDES as v (v)}
                <button
                  type="button"
                  aria-pressed={layout.navSide === v}
                  onclick={() => void layout.setNavSide(v)}>{t(`settings.ui.sides.${v}`)}</button
                >
              {/each}
            </div>
          </div>
          <div class="field">
            <span class="label">{t("settings.ui.navMode")}</span>
            <div class="segmented wrap" role="group" aria-label={t("settings.ui.navMode")}>
              {#each ["theme", ...NAV_MODES] as v (v)}
                <button
                  type="button"
                  aria-pressed={layout.navMode === v}
                  onclick={() => void layout.setNavMode(v as NavMode | "theme")}
                  >{t(`settings.ui.modes.${v}`)}</button
                >
              {/each}
            </div>
          </div>
        </div>
        <div class="field">
          <span class="label">{t("settings.ui.springy")}</span>
          <span class="small muted">{t("settings.ui.springyHint")}</span>
          <div class="segmented wrap" role="group" aria-label={t("settings.ui.springy")}>
            {#each ["theme", "on", "off"] as const as v (v)}
              <button
                type="button"
                aria-pressed={layout.effectsPref === v}
                onclick={() => void layout.setEffects(v)}>{t(`settings.ui.effects.${v}`)}</button
              >
            {/each}
          </div>
        </div>
        <Switch
          label={t("presence.label")}
          hint={t("presence.hint")}
          checked={s.discordPresence}
          onchange={(v) => save({ discordPresence: v })}
        />
        <Switch
          label={t("lite.label")}
          hint={t("lite.hint")}
          checked={s.liteMode}
          onchange={(v) => save({ liteMode: v })}
        />
        <Switch
          label={t("settings.followCursor")}
          hint={t("settings.followCursorHint")}
          checked={s.followCursor}
          onchange={(v) => save({ followCursor: v })}
        />
        <hr class="sep" />
        <div class="col layout-text">
          <span class="label">{t("settings.inGame")}</span>
          <span class="small muted">{t("settings.inGameHint")}</span>
        </div>
        <div class="row wrap gap-2">
          <button class="btn sm primary" onclick={() => router.go("hud")}
            ><Icon name="sliders" size={15} />{t("hudEditor.open")}</button
          >
        </div>
        {#each ["colors", "particles", "logo"] as const as key (key)}
          <Switch
            label={t(`settings.inGameOptions.${key}`)}
            checked={s.gameUi[key]}
            onchange={(v) => save({ gameUi: { ...s.gameUi, [key]: v } })}
          />
        {/each}
        <hr class="sep" />
        <div class="col layout-text">
          <span class="label"
            >{t("widgets.settingsTitle")} <span class="badge warn">{t("widgets.experimental")}</span></span
          >
          <span class="small muted">{t("widgets.settingsHint")}</span>
        </div>
        <Switch
          label={t("widgets.enable")}
          checked={s.experimentalWidgets}
          onchange={(v) => save({ experimentalWidgets: v })}
        />
        {#if s.experimentalWidgets}
          <Switch
            label={t("widgets.homeDashboard")}
            hint={t("widgets.homeDashboardHint")}
            checked={s.homeDashboard}
            onchange={(v) => save({ homeDashboard: v })}
          />
          <div class="row wrap gap-2">
            <button class="btn sm" onclick={newPage}
              ><Icon name="plus" size={15} />{t("widgets.newPage")}</button
            >
          </div>
        {/if}
      </section>

      <section id="set-java" class="card col gap-3">
        <h2>{t("settings.sections.java")}</h2>
        <Switch
          label={t("javaAuto.label")}
          hint={t("javaAuto.hint")}
          checked={javaAuto}
          onchange={(v) => save({ javaAuto: v })}
        />
        {#if javaAuto && autoPlan}
          <div class="small muted">{javaAutoSummary(autoPlan)}</div>
        {/if}
        <div class="field">
          <span class="label">{t("settings.defaultMemory")}</span>
          <div class="segmented" role="group" aria-label={t("settings.defaultMemory")}>
            <button
              type="button"
              aria-pressed={s.maxMemoryMb === null}
              onclick={() => save({ maxMemoryMb: null })}
              >{t("instance.settings.memoryAuto", {
                mb: (javaAuto ? autoPlan?.maxMb : null) ?? app.info?.defaultMemoryMb ?? 4096,
              })}</button
            >
            <button
              type="button"
              aria-pressed={s.maxMemoryMb !== null}
              onclick={() => save({ maxMemoryMb: s.maxMemoryMb ?? app.info?.defaultMemoryMb ?? 4096 })}
              >{t("instance.settings.own")}</button
            >
          </div>
          <MemorySlider
            label={t("settings.defaultMemory")}
            value={s.maxMemoryMb ?? app.info?.defaultMemoryMb ?? 4096}
            onchange={(mb) => save({ maxMemoryMb: mb })}
          />
          <span class="hint">{t("memory.globalHint")}</span>
        </div>
        <Advanced hint={t("settings.javaAdvancedHint")}>
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
        </Advanced>
      </section>

      <section id="set-game" class="card col gap-3">
        <h2>{t("settings.sections.game")}</h2>
        <span class="hint">{t("settings.gameHint")}</span>
        <div class="field">
          <span class="label">{t("settings.sharedOptions")}</span>
          <span class="hint">{t("settings.sharedOptionsHint")}</span>
          <div class="row">
            <button class="btn" onclick={() => (sharedKeysOpen = true)}
              ><Icon name="key" size={15} />{t("settings.sharedKeys")}</button
            >
          </div>
        </div>
        <div class="field">
          <span class="label">{t("instance.settings.gameOptions")}</span>
          <div class="row wrap gap-2">
            <span class="small grow"
              >{optionsTemplate
                ? t("instance.settings.templateInfo", { name: optionsTemplate.source })
                : t("settings.noTemplateHint")}</span
            >
            {#if optionsTemplate}<button class="btn sm ghost" onclick={clearTemplate}
                ><Icon name="trash" size={14} />{t("settings.clearTemplate")}</button
              >{/if}
          </div>
        </div>
        <div class="grid2">
          <div class="field">
            <span class="label">{t("instance.settings.resolution")}</span>
            <div class="row">
              <input
                class="input"
                type="number"
                min="320"
                placeholder={t("instance.settings.width")}
                value={s.resolution?.width ?? ""}
                onchange={(e) => {
                  const w = Number((e.target as HTMLInputElement).value);
                  save({ resolution: w ? { width: w, height: s.resolution?.height ?? 480 } : null });
                }}
              />
              <span class="faint">×</span>
              <input
                class="input"
                type="number"
                min="240"
                placeholder={t("instance.settings.height")}
                value={s.resolution?.height ?? ""}
                onchange={(e) => {
                  const h = Number((e.target as HTMLInputElement).value);
                  save({ resolution: h ? { width: s.resolution?.width ?? 854, height: h } : null });
                }}
              />
            </div>
          </div>
          <div class="field">
            <span class="label">{t("instance.settings.fullscreen")}</span>
            <Switch
              label={t("settings.fullscreenDefault")}
              checked={s.fullscreen}
              onchange={(v) => save({ fullscreen: v })}
            />
          </div>
        </div>
        <Advanced hint={t("settings.gameAdvancedHint")}>
          <div class="field">
            <span class="label">{t("instance.settings.minMemory")}</span>
            <div class="row wrap gap-2">
              <label class="row check"
                ><input
                  type="checkbox"
                  checked={s.minMemoryMb === null}
                  onchange={(e) =>
                    save({ minMemoryMb: (e.target as HTMLInputElement).checked ? null : 1024 })}
                />{t("common.auto")}</label
              >
              {#if s.minMemoryMb !== null}
                <input
                  class="input num"
                  type="number"
                  min="256"
                  step="256"
                  value={s.minMemoryMb}
                  onchange={(e) =>
                    save({ minMemoryMb: Number((e.target as HTMLInputElement).value) || null })}
                  aria-label={t("instance.settings.minMemory")}
                /><span class="faint">MB</span>
              {/if}
            </div>
          </div>
          <div class="field">
            <label for="g-jvm">{t("instance.settings.jvmArgs")}</label>
            <textarea
              id="g-jvm"
              class="textarea mono"
              rows="2"
              spellcheck="false"
              value={s.jvmArgs.join("\n")}
              onchange={(e) => save({ jvmArgs: lines((e.target as HTMLTextAreaElement).value) })}></textarea>
            <span class="hint">{t("settings.globalArgsHint")}</span>
          </div>
          <div class="field">
            <label for="g-game">{t("instance.settings.gameArgs")}</label>
            <textarea
              id="g-game"
              class="textarea mono"
              rows="2"
              spellcheck="false"
              value={s.gameArgs.join("\n")}
              onchange={(e) => save({ gameArgs: lines((e.target as HTMLTextAreaElement).value) })}></textarea>
          </div>
          <div class="grid2">
            <div class="field">
              <label for="g-wrap">{t("instance.settings.wrapper")}</label>
              <input
                id="g-wrap"
                class="input mono"
                placeholder="gamemoderun"
                value={s.wrapper ?? ""}
                onchange={(e) => save({ wrapper: (e.target as HTMLInputElement).value.trim() || null })}
              />
              <span class="hint">{t("instance.settings.wrapperHint")}</span>
            </div>
            <div class="field">
              <label for="g-env">{t("instance.settings.env")}</label>
              <textarea
                id="g-env"
                class="textarea mono"
                rows="2"
                spellcheck="false"
                value={envToText(s.envVars)}
                onchange={(e) => save({ envVars: textToEnv((e.target as HTMLTextAreaElement).value) })}
              ></textarea>
              <span class="hint">{t("instance.settings.envHint")}</span>
            </div>
          </div>
        </Advanced>
      </section>

      <section id="set-mods" class="card col gap-3">
        <h2>{t("settings.sections.mods")}</h2>
        <Switch
          label={t("settings.checkModUpdates")}
          hint={t("settings.checkModUpdatesHint")}
          checked={s.checkModUpdates}
          onchange={(v) => save({ checkModUpdates: v })}
        />
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
        <div class="field">
          <span class="label">{t("settings.transfer.label")}</span>
          <div class="row">
            <button class="btn sm" onclick={exportAll}
              ><Icon name="upload" size={15} />{t("settings.transfer.export")}</button
            ><button class="btn sm" onclick={importAll}
              ><Icon name="download" size={15} />{t("settings.transfer.import")}</button
            >
          </div>
          <span class="hint">{t("settings.transfer.hint")}</span>
        </div>
        <div class="row">
          <button class="btn sm" onclick={() => commands.openFolder({ kind: "logs" })}
            ><Icon name="terminal" size={15} />{t("settings.openLogs")}</button
          >
        </div>
      </section>

      <section id="set-advanced" class="card col gap-3">
        <h2>{t("settings.sections.advanced")}</h2>
        <span class="hint">{t("settings.advancedHint")}</span>
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

      <section id="set-about" class="card col gap-3 about">
        <div class="row gap-3">
          <span class="logo"><Logo /></span>
          <div class="col gap-1">
            <h2>Aerion</h2>
            <span class="small muted">{t("settings.version", { version: app.info?.version ?? "" })}</span>
          </div>
        </div>
        <p class="small">{t("settings.about")}</p>
        <div class="row gap-2 wrap">
          <button class="btn" onclick={() => openUrl(REPO_URL)} title={REPO_URL}>
            <Icon name="github" size={16} />{t("settings.github")}
          </button>
          <button class="btn" onclick={() => openUrl(DISCORD_URL)} title={DISCORD_URL}>
            <Icon name="discord" size={16} />{t("settings.discord")}
          </button>
          <button class="btn ghost" onclick={() => openUrl(ISSUES_URL)}>
            <Icon name="bug" size={16} />{t("settings.reportIssue")}
          </button>
          <span class="small faint">{t("settings.githubHint")}</span>
        </div>
        <div class="alert">
          <Icon name="info" size={18} /><span class="small">{t("app.disclaimer")}</span>
        </div>
        <p class="small faint">{t("settings.thirdParty")}</p>
        <details class="licenses" ontoggle={loadLicenses}>
          <summary class="small faint">{t("settings.licenses")}</summary>
          {#if licenseText}<pre class="tiny selectable">{licenseText}</pre>{/if}
        </details>
        <p class="tiny faint">
          {t("settings.userAgent")}: <code class="selectable">{app.info?.userAgent}</code>
        </p>
      </section>
    </div>
  {/if}
</div>

<ThemeGalleryModal bind:open={galleryOpen} />
<KeybindEditor bind:open={sharedKeysOpen} instanceId={null} title={t("keybinds.sharedTitle")} />

<style>
  .num {
    width: 7rem;
  }
  .toc {
    position: sticky;
    top: calc(-1 * var(--sp-5));
    z-index: 3;
    margin-bottom: var(--sp-3);
    /* A bar of its own, as wide as the cards below it. */
    max-width: 52rem;
    padding: var(--sp-2);
    border: 1px solid var(--c-border);
    border-radius: var(--r-lg);
    background: var(--c-surface);
  }
  .body {
    max-width: 52rem;
    padding-bottom: 3rem;
  }
  .body > section {
    scroll-margin-top: calc(var(--toc-h) + var(--sp-2));
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
  .sep {
    width: 100%;
    margin: var(--sp-1) 0;
    border: 0;
    border-top: 1px solid var(--c-border);
  }
  .layout-text {
    gap: 0.15rem;
  }
  /* Beside buttons it takes the free width; in a column a flex-basis would become a height. */
  .row > .layout-text {
    flex: 1 1 14rem;
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
  .licenses pre {
    max-height: 18rem;
    overflow: auto;
    white-space: pre-wrap;
    padding: var(--sp-2);
    border-radius: var(--r-sm);
    background: var(--c-hover);
  }
  .licenses summary {
    cursor: pointer;
  }
</style>
