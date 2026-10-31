<script lang="ts">
  import { open as openDialog } from "@tauri-apps/plugin-dialog";
  import { untrack } from "svelte";
  import Icon from "../../components/Icon.svelte";
  import { app } from "../../stores/app.svelte";
  import { toasts } from "../../stores/toasts.svelte";
  import {
    commands,
    errorMessage,
    LOADERS,
    loaderLabel,
    type GameVersions,
    type Instance,
    type JavaInstall,
    type JvmPreset,
    type LoaderVersion,
  } from "../../api";
  import { t } from "../../i18n/index.svelte";

  let { instance, onremove }: { instance: Instance; onremove: () => void } = $props();

  // Editable draft; arrays/maps are edited as text.
  const initial = untrack(() => $state.snapshot(instance)) as Instance;
  let draft = $state<Instance>(structuredClone(initial));
  let jvmText = $state(initial.jvmArgs.join("\n"));
  let gameText = $state(initial.gameArgs.join("\n"));
  let envText = $state(
    Object.entries(initial.envVars)
      .map(([k, v]) => `${k}=${v}`)
      .join("\n"),
  );
  let memoryAuto = $state(initial.maxMemoryMb === null);
  let customJava = $state(initial.javaPath !== null);
  let saving = $state(false);
  let versions = $state.raw<GameVersions | null>(null);
  let loaderVersions = $state.raw<LoaderVersion[]>([]);
  let javas = $state.raw<JavaInstall[]>([]);
  let testResult = $state<string | null>(null);

  const totalMb = $derived(app.info?.totalMemoryMb ?? 8192);
  const autoMb = $derived(
    instance.loader === "vanilla"
      ? (app.info?.defaultMemoryMb ?? 4096)
      : (app.info?.defaultMemoryModdedMb ?? 6144),
  );
  const maxSlider = $derived(Math.max(2048, Math.floor((totalMb * 0.85) / 256) * 256));

  $effect(() => {
    commands
      .listGameVersions()
      .then((v) => (versions = v))
      .catch(() => {});
    commands
      .listJava()
      .then((j) => (javas = j))
      .catch(() => {});
  });
  $effect(() => {
    const l = draft.loader;
    const gv = draft.gameVersion;
    if (l === "vanilla") return void (loaderVersions = []);
    commands
      .listLoaderVersions(l, gv)
      .then((v) => (loaderVersions = v))
      .catch(() => (loaderVersions = []));
  });

  const lines = (s: string) =>
    s
      .split("\n")
      .map((x) => x.trim())
      .filter(Boolean);
  function build(): Instance {
    const env: Record<string, string> = {};
    for (const line of lines(envText)) {
      const i = line.indexOf("=");
      if (i > 0) env[line.slice(0, i).trim()] = line.slice(i + 1);
    }
    return {
      ...$state.snapshot(draft),
      jvmArgs: lines(jvmText),
      gameArgs: lines(gameText),
      envVars: env,
      maxMemoryMb: memoryAuto ? null : draft.maxMemoryMb,
      javaPath: customJava ? draft.javaPath : null,
      group: draft.group?.trim() || null,
      wrapper: draft.wrapper?.trim() || null,
      joinServer: draft.joinServer?.trim() || null,
    } as Instance;
  }
  const dirty = $derived(JSON.stringify(build()) !== JSON.stringify($state.snapshot(instance)));

  function reset() {
    draft = structuredClone($state.snapshot(instance));
    jvmText = instance.jvmArgs.join("\n");
    gameText = instance.gameArgs.join("\n");
    envText = Object.entries(instance.envVars)
      .map(([k, v]) => `${k}=${v}`)
      .join("\n");
    memoryAuto = instance.maxMemoryMb === null;
    customJava = instance.javaPath !== null;
  }

  async function save() {
    saving = true;
    try {
      await commands.updateInstance(build());
      await app.refreshInstances();
      toasts.success(t("common.saved"));
    } catch (e) {
      toasts.error(errorMessage(e));
    } finally {
      saving = false;
    }
  }

  async function pickJava() {
    const f = await openDialog({ multiple: false });
    if (typeof f === "string") draft.javaPath = f;
  }

  async function testArgs() {
    testResult = null;
    const java =
      customJava && draft.javaPath
        ? draft.javaPath
        : (javas.find((j) => j.source !== "system")?.path ?? javas[0]?.path);
    if (!java) return;
    try {
      await commands.testJvmArgs(java, lines(jvmText));
      testResult = t("instance.settings.argsOk");
    } catch (e) {
      testResult = errorMessage(e);
    }
  }

  const presets: (JvmPreset | null)[] = [null, "standard", "performance", "lowMemory"];
</script>

<div class="settings col gap-4">
  {#if dirty}
    <div class="savebar card raised">
      <span class="grow">{t("instance.settings.unsaved")}</span>
      <button class="btn ghost" onclick={reset}>{t("common.cancel")}</button>
      <button class="btn primary" onclick={save} disabled={saving}
        ><Icon name="check" size={16} />{t("instance.settings.saveChanges")}</button
      >
    </div>
  {/if}

  <section class="card col gap-3">
    <h3>{t("instance.settings.general")}</h3>
    <div class="grid2">
      <div class="field">
        <label for="s-name">{t("instance.settings.name")}</label><input
          id="s-name"
          class="input"
          bind:value={draft.name}
          maxlength="64"
        />
      </div>
      <div class="field">
        <label for="s-group">{t("instance.settings.group")}</label><input
          id="s-group"
          class="input"
          bind:value={draft.group}
          placeholder={t("common.noGroup")}
        />
      </div>
    </div>
    <div class="field">
      <label for="s-notes">{t("instance.settings.notes")}</label><textarea
        id="s-notes"
        class="textarea"
        bind:value={draft.notes}
        rows="2"></textarea>
    </div>
  </section>

  <section class="card col gap-3">
    <h3>{t("instance.settings.versionLoader")}</h3>
    <div class="grid2">
      <div class="field">
        <label for="s-ver">{t("common.version")}</label>
        <select id="s-ver" class="select" bind:value={draft.gameVersion}>
          {#if !versions}<option value={draft.gameVersion}>{draft.gameVersion}</option>{/if}
          {#each versions?.versions.filter((v) => v.kind === "release" || v.kind === "snapshot" || v.id === draft.gameVersion) ?? [] as v (v.id)}
            <option value={v.id}>{v.id}{v.kind === "snapshot" ? " (snapshot)" : ""}</option>
          {/each}
        </select>
      </div>
      <div class="field">
        <label for="s-lv">{t("newInstance.loaderVersion")}</label>
        <select
          id="s-lv"
          class="select"
          bind:value={draft.loaderVersion}
          disabled={draft.loader === "vanilla"}
        >
          <option value={null}>{t("common.auto")}</option>
          {#each loaderVersions.slice(0, 200) as v (v.version)}<option value={v.version}
              >{v.version}{v.stable ? "" : " (beta)"}</option
            >{/each}
        </select>
      </div>
    </div>
    <div class="segmented" role="group" aria-label={t("common.loader")}>
      {#each LOADERS as l (l)}<button
          type="button"
          aria-pressed={draft.loader === l}
          onclick={() => ((draft.loader = l), (draft.loaderVersion = null))}>{loaderLabel(l)}</button
        >{/each}
    </div>
    {#if draft.gameVersion !== instance.gameVersion || draft.loader !== instance.loader}
      <div class="alert warning">
        <Icon name="alert" size={18} /><span class="small">{t("instance.settings.changeVersionWarn")}</span>
      </div>
    {/if}
  </section>

  <section class="card col gap-3">
    <h3>{t("instance.settings.memory")} & {t("instance.settings.java")}</h3>
    <label class="row check"
      ><input type="checkbox" bind:checked={memoryAuto} />{t("instance.settings.memoryAuto", {
        mb: autoMb,
      })}</label
    >
    {#if !memoryAuto}
      <div class="row gap-3">
        <input
          type="range"
          min="1024"
          max={maxSlider}
          step="256"
          bind:value={draft.maxMemoryMb}
          aria-label={t("instance.settings.memory")}
        />
        <strong class="mono mem">{draft.maxMemoryMb ?? autoMb} MB</strong>
      </div>
      <span class="hint">{t("instance.settings.memoryHint", { total: Math.round(totalMb / 1024) })}</span>
    {/if}
    <div class="field">
      <span class="label">{t("instance.settings.preset")}</span>
      <div class="segmented" role="group">
        {#each presets as p (p ?? "default")}
          <button type="button" aria-pressed={draft.jvmPreset === p} onclick={() => (draft.jvmPreset = p)}
            >{p ? t(`instance.settings.presets.${p}`) : t("common.default")}</button
          >
        {/each}
      </div>
      {#if draft.jvmPreset}<span class="hint">{t(`instance.settings.presetHints.${draft.jvmPreset}`)}</span
        >{/if}
    </div>
    <div class="field">
      <span class="label">{t("instance.settings.java")}</span>
      <div class="segmented" role="group">
        <button type="button" aria-pressed={!customJava} onclick={() => (customJava = false)}
          >{t("instance.settings.javaAuto")}</button
        >
        <button type="button" aria-pressed={customJava} onclick={() => (customJava = true)}
          >{t("instance.settings.javaCustom")}</button
        >
      </div>
      {#if customJava}
        <div class="row">
          <input
            class="input mono grow"
            bind:value={draft.javaPath}
            list="javas"
            placeholder="/usr/lib/jvm/.../bin/java"
          />
          <button class="btn" onclick={pickJava}>{t("common.browse")}</button>
        </div>
        <datalist id="javas"
          >{#each javas as j (j.path)}<option value={j.path}>Java {j.version} ({j.arch})</option
            >{/each}</datalist
        >
      {/if}
    </div>
  </section>

  <details class="card advanced">
    <summary><h3>{t("instance.settings.advanced")}</h3></summary>
    <div class="col gap-3" style="margin-top: var(--sp-3)">
      <div class="field">
        <label for="s-jvm">{t("instance.settings.jvmArgs")}</label>
        <textarea id="s-jvm" class="textarea mono" bind:value={jvmText} rows="3" spellcheck="false"
        ></textarea>
        <div class="row">
          <span class="hint grow">{t("instance.settings.jvmArgsHint")}</span><button
            class="btn sm"
            onclick={testArgs}>{t("instance.settings.testArgs")}</button
          >
        </div>
        {#if testResult}<span class="small">{testResult}</span>{/if}
      </div>
      <div class="field">
        <label for="s-game">{t("instance.settings.gameArgs")}</label><textarea
          id="s-game"
          class="textarea mono"
          bind:value={gameText}
          rows="2"
          spellcheck="false"></textarea>
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
              value={draft.resolution?.width ?? ""}
              oninput={(e) => {
                const w = Number((e.target as HTMLInputElement).value);
                draft.resolution = w ? { width: w, height: draft.resolution?.height ?? 480 } : null;
              }}
            />
            <span class="faint">×</span>
            <input
              class="input"
              type="number"
              min="240"
              placeholder={t("instance.settings.height")}
              value={draft.resolution?.height ?? ""}
              oninput={(e) => {
                const h = Number((e.target as HTMLInputElement).value);
                if (draft.resolution && h) draft.resolution = { ...draft.resolution, height: h };
              }}
            />
          </div>
        </div>
        <div class="field">
          <label for="s-join">{t("instance.settings.joinServer")}</label><input
            id="s-join"
            class="input"
            bind:value={draft.joinServer}
            placeholder={t("instance.settings.joinServerPlaceholder")}
          />
        </div>
      </div>
      <div class="field">
        <label for="s-wrap">{t("instance.settings.wrapper")}</label><input
          id="s-wrap"
          class="input mono"
          bind:value={draft.wrapper}
          placeholder="gamemoderun"
        /><span class="hint">{t("instance.settings.wrapperHint")}</span>
      </div>
      <div class="field">
        <label for="s-env">{t("instance.settings.env")}</label><textarea
          id="s-env"
          class="textarea mono"
          bind:value={envText}
          rows="2"
          spellcheck="false"></textarea><span class="hint">{t("instance.settings.envHint")}</span>
      </div>
    </div>
  </details>

  <section class="card col gap-2 danger-zone">
    <h3>{t("instance.settings.danger")}</h3>
    <div class="row">
      <span class="grow small muted">{t("instances.deleteText")}</span><button
        class="btn danger"
        onclick={onremove}><Icon name="trash" size={16} />{t("common.delete")}</button
      >
    </div>
  </section>
</div>

<style>
  .settings {
    max-width: 52rem;
    padding-bottom: 4rem;
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
  .mem {
    min-width: 6.5rem;
    text-align: right;
  }
  .savebar {
    position: sticky;
    top: 0;
    z-index: 5;
    display: flex;
    align-items: center;
    gap: var(--sp-2);
    padding: var(--sp-2) var(--sp-3);
    border-color: color-mix(in srgb, var(--c-accent) 45%, var(--c-border));
  }
  .advanced summary {
    cursor: pointer;
    list-style: none;
  }
  .advanced summary h3 {
    display: inline;
  }
  .danger-zone {
    border-color: color-mix(in srgb, var(--c-danger) 35%, var(--c-border));
  }
</style>
