<script lang="ts">
  import { untrack } from "svelte";
  import { open as openDialog } from "@tauri-apps/plugin-dialog";
  import Modal from "../components/Modal.svelte";
  import Icon from "../components/Icon.svelte";
  import IconPicker from "../components/IconPicker.svelte";
  import { app } from "../stores/app.svelte";
  import { router } from "../stores/router.svelte";
  import { toasts } from "../stores/toasts.svelte";
  import {
    commands,
    errorMessage,
    LOADERS,
    loaderLabel,
    type GameVersions,
    type LoaderKind,
    type LoaderVersion,
  } from "../api";
  import { t } from "../i18n/index.svelte";

  let { open = $bindable(false), presetName = "" }: { open?: boolean; presetName?: string } = $props();

  let name = $state(untrack(() => presetName));
  let nameTouched = $state(false);
  let versions = $state.raw<GameVersions | null>(null);
  let snapshots = $state(false);
  let gameVersion = $state("");
  let loader = $state<LoaderKind>("vanilla");
  let loaderVersions = $state.raw<LoaderVersion[] | null>(null);
  let loaderVersion = $state("");
  let group = $state("");
  let icon = $state<string | null>(null);
  let iconFile = $state<string | null>(null);
  /** Game folder elsewhere (e.g. another drive); `null` = inside the launcher's data folder. */
  let gameDir = $state<string | null>(null);

  async function pickLocation() {
    const picked = await openDialog({ directory: true, multiple: false });
    if (typeof picked === "string") gameDir = picked;
  }
  let busy = $state(false);
  let loadError = $state<string | null>(null);

  const visible = $derived(
    versions?.versions.filter((v) => v.kind === "release" || (snapshots && v.kind === "snapshot")) ?? [],
  );
  const groups = $derived([...new Set(app.instances.map((i) => i.group).filter(Boolean))] as string[]);

  $effect(() => {
    commands
      .listGameVersions()
      .then((v) => {
        versions = v;
        gameVersion ||= v.latestRelease;
      })
      .catch((e) => (loadError = errorMessage(e)));
  });

  $effect(() => {
    const gv = gameVersion;
    const l = loader;
    loaderVersions = null;
    loaderVersion = "";
    if (!gv || l === "vanilla") return;
    commands
      .listLoaderVersions(l, gv)
      .then((list) => {
        if (gameVersion === gv && loader === l) loaderVersions = list;
      })
      .catch(() => (loaderVersions = []));
  });

  // Suggest a name until the user types one.
  $effect(() => {
    if (!nameTouched) name = loader === "vanilla" ? gameVersion : `${loaderLabel(loader)} ${gameVersion}`;
  });

  const defaultLoader = $derived(loaderVersions?.find((v) => v.stable) ?? loaderVersions?.[0]);
  const unavailable = $derived(
    loader !== "vanilla" && loaderVersions !== null && loaderVersions.length === 0,
  );

  async function create(play: boolean) {
    busy = true;
    try {
      const inst = await commands.createInstance({
        name: name.trim() || gameVersion,
        gameVersion,
        loader,
        loaderVersion: loader === "vanilla" ? null : loaderVersion || null,
        icon,
        group: group.trim() || null,
        gameDir,
      });
      if (iconFile) {
        // The instance exists either way; a bad image only costs the custom icon.
        await commands.importInstanceIcon(inst.id, iconFile).catch((e) => toasts.error(errorMessage(e)));
      }
      await app.refreshInstances();
      await app.saveSettings({ lastInstance: inst.id });
      open = false;
      if (play) void app.launch(inst.id);
      else router.go("instance", { id: inst.id });
    } catch (e) {
      toasts.error(errorMessage(e));
    } finally {
      busy = false;
    }
  }
</script>

<Modal bind:open title={t("newInstance.title")} width="36rem">
  <form id="new-instance" class="col gap-4" onsubmit={(e) => (e.preventDefault(), create(false))}>
    <div class="field">
      <label for="ni-name">{t("newInstance.name")}</label>
      <input
        id="ni-name"
        class="input"
        bind:value={name}
        oninput={() => (nameTouched = true)}
        placeholder={t("newInstance.namePlaceholder")}
        maxlength="64"
      />
    </div>
    <div class="row gap-3 two">
      <div class="field grow">
        <label for="ni-version">{t("newInstance.gameVersion")}</label>
        {#if loadError}
          <span class="small danger-text">{loadError}</span>
        {:else}
          <select id="ni-version" class="select" bind:value={gameVersion} disabled={!versions}>
            {#if !versions}<option>{t("common.loading")}</option>{/if}
            {#each visible as v (v.id)}<option value={v.id}
                >{v.id}{v.kind === "snapshot" ? " (snapshot)" : ""}</option
              >{/each}
          </select>
        {/if}
        <label class="row small muted check"
          ><input type="checkbox" bind:checked={snapshots} />{t("newInstance.showSnapshots")}</label
        >
      </div>
      <div class="field grow">
        <label for="ni-group">{t("newInstance.group")}</label>
        <input
          id="ni-group"
          class="input"
          bind:value={group}
          list="ni-groups"
          placeholder={t("common.noGroup")}
        />
        <datalist id="ni-groups"
          >{#each groups as g (g)}<option value={g}></option>{/each}</datalist
        >
      </div>
    </div>
    <div class="field">
      <span class="label">{t("newInstance.loader")}</span>
      <div class="segmented loaders" role="group" aria-label={t("newInstance.loader")}>
        {#each LOADERS as l (l)}
          <button type="button" aria-pressed={loader === l} onclick={() => (loader = l)}
            >{loaderLabel(l)}</button
          >
        {/each}
      </div>
    </div>
    {#if loader !== "vanilla"}
      <div class="field">
        <label for="ni-loader">{t("newInstance.loaderVersion")}</label>
        {#if loaderVersions === null}
          <span class="small muted">{t("newInstance.loadingVersions")}</span>
        {:else if unavailable}
          <span class="small warn"
            ><Icon name="alert" size={14} />{t("newInstance.notAvailable", {
              loader: loaderLabel(loader),
              version: gameVersion,
            })}</span
          >
        {:else}
          <select id="ni-loader" class="select" bind:value={loaderVersion}>
            <option value=""
              >{defaultLoader?.stable
                ? t("newInstance.latestStable", { version: defaultLoader.version })
                : t("newInstance.latest", { version: defaultLoader?.version ?? "" })}</option
            >
            {#each loaderVersions.slice(0, 200) as v (v.version)}<option value={v.version}
                >{v.version}{v.stable ? "" : " (beta)"}</option
              >{/each}
          </select>
        {/if}
      </div>
    {/if}
    <div class="field">
      <span class="label">{t("newInstance.location")}</span>
      <div class="row gap-2">
        <code class="loc grow truncate" title={gameDir ?? ""}
          >{gameDir ?? t("newInstance.locationDefault")}</code
        >
        <button type="button" class="btn sm" onclick={pickLocation}
          ><Icon name="folder" size={14} />{t("newInstance.locationPick")}</button
        >
        {#if gameDir}<button type="button" class="btn sm ghost" onclick={() => (gameDir = null)}
            >{t("common.default")}</button
          >{/if}
      </div>
      <span class="hint">{t("newInstance.locationHint")}</span>
    </div>
    <div class="field">
      <span class="label">{t("newInstance.icon")}</span>
      <IconPicker bind:icon bind:file={iconFile} name={name || gameVersion} {loader} />
    </div>
  </form>
  {#snippet footer()}
    <button class="btn ghost" onclick={() => (open = false)}>{t("common.cancel")}</button>
    <button class="btn" disabled={busy || !gameVersion || unavailable} onclick={() => create(true)}
      ><Icon name="play" size={15} />{t("newInstance.createAndPlay")}</button
    >
    <button
      class="btn primary"
      type="submit"
      form="new-instance"
      disabled={busy || !gameVersion || unavailable}>{t("newInstance.create")}</button
    >
  {/snippet}
</Modal>

<style>
  .two {
    align-items: flex-start;
  }
  .two > .field {
    flex: 1 1 0;
  }
  .loc {
    padding: 0.35rem 0.6rem;
    border-radius: var(--r-sm);
    background: var(--c-surface-2);
    min-width: 0;
  }
  .check {
    gap: 0.4rem;
    cursor: pointer;
  }
  .loaders {
    align-self: flex-start;
    flex-wrap: wrap;
  }
  .warn {
    display: inline-flex;
    gap: 0.4rem;
    align-items: center;
    color: var(--c-warning);
  }
</style>
