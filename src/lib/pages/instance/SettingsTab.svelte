<script lang="ts">
  import { open as openDialog } from "@tauri-apps/plugin-dialog";
  import { untrack } from "svelte";
  import Advanced from "../../components/Advanced.svelte";
  import Switch from "../../components/Switch.svelte";
  import Icon from "../../components/Icon.svelte";
  import MemorySlider from "../../components/MemorySlider.svelte";
  import { app } from "../../stores/app.svelte";
  import { dialogs } from "../../stores/dialogs.svelte";
  import { toasts } from "../../stores/toasts.svelte";
  import {
    commands,
    errorMessage,
    LOADERS,
    loaderLabel,
    type AfterLaunch,
    type GameVersions,
    type Instance,
    type JavaInstall,
    type JvmPreset,
    type LoaderVersion,
  } from "../../api";
  import type { WorldInfo } from "../../bindings";
  import { t } from "../../i18n/index.svelte";

  let { instance, onremove }: { instance: Instance; onremove: () => void } = $props();

  // Editable draft; arrays/maps are edited as text.
  const initial = untrack(() => $state.snapshot(instance)) as Instance;
  const AERION_MOD_VERSIONS = $derived(app.info?.aerionModVersions ?? []);
  let draft = $state<Instance>(structuredClone(initial));
  let jvmText = $state(initial.jvmArgs.join("\n"));
  let gameText = $state(initial.gameArgs.join("\n"));
  let envText = $state(
    Object.entries(initial.envVars)
      .map(([k, v]) => `${k}=${v}`)
      .join("\n"),
  );
  let memoryAuto = $state(initial.maxMemoryMb === null);
  let minMemoryOwn = $state(initial.minMemoryMb !== null);
  let moving = $state(false);
  const g = $derived(app.settings);
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
  let worlds = $state<WorldInfo[]>([]);
  $effect(() => {
    commands
      .listWorlds(instance.id)
      .then((w) => (worlds = w))
      .catch(() => (worlds = []));
  });

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
      minMemoryMb: minMemoryOwn ? draft.minMemoryMb : null,
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
    minMemoryOwn = instance.minMemoryMb !== null;
    customJava = instance.javaPath !== null;
  }

  // Game settings (options.txt …): copy between instances, or keep as template for new ones.
  let copyFrom = $state("");
  let template = $state.raw<{ source: string; savedAt: number } | null>(null);
  const others = $derived(app.instances.filter((i) => i.id !== instance.id));
  $effect(() => {
    commands
      .optionsTemplate()
      .then((tpl) => (template = tpl))
      .catch(() => {});
  });

  async function optionsAction(fn: () => Promise<unknown>, ok: string) {
    try {
      await fn();
      toasts.success(ok);
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  }
  const copyFromOther = () =>
    optionsAction(
      () => commands.copyGameOptions(copyFrom, [instance.id]),
      t("instance.settings.optionsCopied"),
    );
  async function copyToAll() {
    const ok = await dialogs.confirm({
      title: t("instance.settings.optionsToAllTitle"),
      text: t("instance.settings.optionsToAllText", { n: others.length }),
    });
    if (ok)
      await optionsAction(
        () =>
          commands.copyGameOptions(
            instance.id,
            others.map((i) => i.id),
          ),
        t("instance.settings.optionsCopied"),
      );
  }
  async function saveTemplate() {
    await optionsAction(
      async () => (template = await commands.saveOptionsTemplate(instance.id)),
      t("instance.settings.templateSaved"),
    );
  }

  /** Move the game folder (worlds, mods…) somewhere else, or back with `null`. */
  async function moveGameDir(to: "pick" | null) {
    let path: string | null = null;
    if (to === "pick") {
      const picked = await openDialog({ directory: true, multiple: false });
      if (typeof picked !== "string") return;
      path = picked;
    }
    moving = true;
    try {
      await commands.moveInstanceGameDir(instance.id, path);
      await app.refreshInstances();
      toasts.success(t("instance.settings.gameDirMoved"));
    } catch (e) {
      toasts.error(errorMessage(e));
    } finally {
      moving = false;
    }
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
  const afterOptions: AfterLaunch[] = ["keep", "minimize", "hide", "close"];
  // Effective global values, shown next to "use global".
  const globalMax = $derived(g?.maxMemoryMb ?? autoMb);
  const globalRes = $derived(
    g?.resolution ? `${g.resolution.width}×${g.resolution.height}` : t("common.auto"),
  );
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
    <h3>{t("instance.settings.memoryAndJava")}</h3>
    <span class="label">{t("instance.settings.maxMemory")}</span>
    <div class="segmented" role="group" aria-label={t("instance.settings.maxMemory")}>
      <button type="button" aria-pressed={memoryAuto} onclick={() => (memoryAuto = true)}
        >{t("instance.settings.useGlobal", { value: `${globalMax} MB` })}</button
      >
      <button
        type="button"
        aria-pressed={!memoryAuto}
        onclick={() => ((memoryAuto = false), (draft.maxMemoryMb ??= globalMax))}
        >{t("instance.settings.own")}</button
      >
    </div>
    <MemorySlider
      label={t("instance.settings.maxMemory")}
      value={memoryAuto ? globalMax : (draft.maxMemoryMb ?? globalMax)}
      onchange={(mb) => ((memoryAuto = false), (draft.maxMemoryMb = mb))}
    />
    <span class="hint">{t("memory.instanceHint")}</span>
    <Advanced
      hint={t("instance.settings.memoryAdvancedHint")}
      open={minMemoryOwn || !!draft.jvmPreset || customJava}
    >
      <span class="label">{t("instance.settings.minMemory")}</span>
      <div class="row wrap gap-2">
        <div class="segmented" role="group" aria-label={t("instance.settings.minMemory")}>
          <button type="button" aria-pressed={!minMemoryOwn} onclick={() => (minMemoryOwn = false)}
            >{t("instance.settings.useGlobal", {
              value: g?.minMemoryMb ? `${g.minMemoryMb} MB` : t("common.auto"),
            })}</button
          >
          <button
            type="button"
            aria-pressed={minMemoryOwn}
            onclick={() => ((minMemoryOwn = true), (draft.minMemoryMb ??= 1024))}
            >{t("instance.settings.own")}</button
          >
        </div>
        {#if minMemoryOwn}
          <input
            class="input num"
            type="number"
            min="256"
            max={maxSlider}
            step="256"
            bind:value={draft.minMemoryMb}
            aria-label={t("instance.settings.minMemory")}
          /><span class="faint">MB</span>
        {/if}
      </div>
      <div class="field">
        <span class="label">{t("instance.settings.preset")}</span>
        <div class="segmented" role="group">
          {#each presets as p (p ?? "default")}
            <button type="button" aria-pressed={draft.jvmPreset === p} onclick={() => (draft.jvmPreset = p)}
              >{p
                ? t(`instance.settings.presets.${p}`)
                : t("instance.settings.useGlobal", {
                    value: t(`instance.settings.presets.${g?.jvmPreset ?? "standard"}`),
                  })}</button
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
    </Advanced>
  </section>

  <section class="card col gap-3">
    <h3>{t("instance.settings.launching")}</h3>
    <div class="grid2">
      <div class="field">
        <label for="s-full">{t("instance.settings.fullscreen")}</label>
        <select
          id="s-full"
          class="select"
          value={draft.fullscreen === null ? "global" : String(draft.fullscreen)}
          onchange={(e) => {
            const v = (e.target as HTMLSelectElement).value;
            draft.fullscreen = v === "global" ? null : v === "true";
          }}
        >
          <option value="global"
            >{t("instance.settings.useGlobal", {
              value: g?.fullscreen ? t("common.on") : t("common.off"),
            })}</option
          >
          <option value="true">{t("common.on")}</option>
          <option value="false">{t("common.off")}</option>
        </select>
      </div>
      <div class="field">
        <label for="s-after">{t("settings.afterLaunch")}</label>
        <select
          id="s-after"
          class="select"
          value={draft.afterLaunch ?? "global"}
          onchange={(e) => {
            const v = (e.target as HTMLSelectElement).value;
            draft.afterLaunch = v === "global" ? null : (v as AfterLaunch);
          }}
        >
          <option value="global"
            >{t("instance.settings.useGlobal", {
              value: t(`settings.afterLaunchOptions.${g?.afterLaunch ?? "minimize"}`),
            })}</option
          >
          {#each afterOptions as a (a)}<option value={a}>{t(`settings.afterLaunchOptions.${a}`)}</option
            >{/each}
        </select>
      </div>
    </div>
    {#if draft.loader === "fabric"}
      <!-- The bundled jar is built for these versions only (aerion_mod::GAME_VERSIONS). -->
      <Switch
        label={t("instance.settings.aerionMod")}
        hint={AERION_MOD_VERSIONS.includes(draft.gameVersion)
          ? t("instance.settings.aerionModHint")
          : t("instance.settings.aerionModUnsupported", { versions: AERION_MOD_VERSIONS.join(", ") })}
        disabled={!AERION_MOD_VERSIONS.includes(draft.gameVersion)}
        bind:checked={draft.aerionMod}
      />
    {/if}
  </section>

  <section class="card col gap-3">
    <h3>{t("instance.settings.gameOptions")}</h3>
    <span class="hint">{t("instance.settings.gameOptionsHint")}</span>
    <div class="row wrap gap-2">
      <select class="select grow" bind:value={copyFrom} aria-label={t("instance.settings.copyFrom")}>
        <option value="">{t("instance.settings.copyFrom")}</option>
        {#each others as o (o.id)}<option value={o.id}>{o.name}</option>{/each}
      </select>
      <button class="btn" disabled={!copyFrom} onclick={copyFromOther}
        ><Icon name="download" size={15} />{t("instance.settings.copy")}</button
      >
      <button class="btn ghost" disabled={!others.length} onclick={copyToAll}
        ><Icon name="upload" size={15} />{t("instance.settings.copyToAll")}</button
      >
    </div>
    <div class="row wrap gap-2">
      <button class="btn" onclick={saveTemplate}
        ><Icon name="star" size={15} />{t("instance.settings.saveTemplate")}</button
      >
      <span class="small muted"
        >{template
          ? t("instance.settings.templateInfo", { name: template.source })
          : t("instance.settings.noTemplate")}</span
      >
    </div>
  </section>

  <section class="card col gap-3">
    <h3>{t("instance.settings.gameDir")}</h3>
    <code class="path selectable">{instance.gameDir ?? t("instance.settings.gameDirDefault")}</code>
    <div class="row wrap gap-2">
      <button class="btn" disabled={moving} onclick={() => moveGameDir("pick")}
        >{#if moving}<span class="spinner"></span>{:else}<Icon name="folder" size={15} />{/if}{t(
          "instance.settings.gameDirMove",
        )}</button
      >
      {#if instance.gameDir}
        <button class="btn ghost" disabled={moving} onclick={() => moveGameDir(null)}
          >{t("instance.settings.gameDirReset")}</button
        >
      {/if}
      <button class="btn ghost" onclick={() => commands.openFolder({ kind: "gameDir", id: instance.id })}
        >{t("common.openFolder")}</button
      >
    </div>
    <span class="hint">{t("instance.settings.gameDirHint")}</span>
  </section>

  <details class="card advanced">
    <summary
      ><span class="chev"><Icon name="chevronRight" size={16} /></span>
      <h3>{t("instance.settings.advanced")}</h3>
      <span class="small faint">{t("instance.settings.advancedHint")}</span></summary
    >
    <div class="col gap-3" style="margin-top: var(--sp-3)">
      <div class="field">
        <label for="s-jvm">{t("instance.settings.jvmArgs")}</label>
        <textarea id="s-jvm" class="textarea mono" bind:value={jvmText} rows="3" spellcheck="false"
        ></textarea>
        <div class="row">
          <span class="hint grow"
            >{t("instance.settings.jvmArgsHint")}{#if g?.jvmArgs.length}
              {t("instance.settings.addedToGlobal", { value: g.jvmArgs.join(" ") })}{/if}</span
          ><button class="btn sm" onclick={testArgs}>{t("instance.settings.testArgs")}</button>
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
        {#if g?.gameArgs.length}<span class="hint"
            >{t("instance.settings.addedToGlobal", { value: g.gameArgs.join(" ") })}</span
          >{/if}
      </div>
      <div class="grid2">
        <div class="field">
          <span class="label">{t("instance.settings.resolution")}</span>
          <span class="hint">{t("instance.settings.emptyMeansGlobal", { value: globalRes })}</span>
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
          <label for="s-world">{t("instance.settings.joinWorld")}</label><select
            id="s-world"
            class="select"
            bind:value={draft.joinWorld}
          >
            <option value={null}>{t("instance.settings.joinWorldOff")}</option>
            <option value="@last">{t("instance.settings.joinWorldLast")}</option>
            {#each worlds as w (w.folder)}<option value={w.folder}>{w.name}</option>{/each}
          </select>
          <span class="hint">{t("instance.settings.joinWorldHint")}</span>
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
          placeholder={g?.wrapper ?? "gamemoderun"}
        /><span class="hint"
          >{t("instance.settings.wrapperHint")}{#if g?.wrapper}
            {t("instance.settings.emptyMeansGlobal", { value: g.wrapper })}{/if}</span
        >
      </div>
      <div class="field">
        <label for="s-env">{t("instance.settings.env")}</label><textarea
          id="s-env"
          class="textarea mono"
          bind:value={envText}
          rows="2"
          spellcheck="false"></textarea><span class="hint"
          >{t("instance.settings.envHint")} {t("instance.settings.envOverGlobal")}</span
        >
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
  .num {
    width: 7rem;
  }
  .path {
    display: block;
    padding: var(--sp-2);
    border-radius: var(--r-sm);
    background: var(--c-surface-2);
    word-break: break-all;
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
    display: flex;
    align-items: center;
    gap: var(--sp-2);
    cursor: pointer;
    list-style: none;
  }
  .advanced summary::-webkit-details-marker {
    display: none;
  }
  .chev {
    display: inline-flex;
    transition: transform var(--dur) var(--ease);
  }
  .advanced[open] .chev {
    transform: rotate(90deg);
  }
  .danger-zone {
    border-color: color-mix(in srgb, var(--c-danger) 35%, var(--c-border));
  }
</style>
