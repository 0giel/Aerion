<script lang="ts">
  // Import instances from other launchers found on this computer (or any folder).
  import { open as openDialog } from "@tauri-apps/plugin-dialog";
  import Modal from "../components/Modal.svelte";
  import Icon from "../components/Icon.svelte";
  import { toasts } from "../stores/toasts.svelte";
  import { commands, errorMessage, LOADERS, loaderLabel, type GameVersions, type LoaderKind } from "../api";
  import type { ExternalInstance } from "../bindings";
  import { t } from "../i18n/index.svelte";

  let { open = $bindable(false) }: { open?: boolean } = $props();

  let found = $state.raw<ExternalInstance[] | null>(null);
  let versions = $state.raw<GameVersions | null>(null);
  /** The one being configured before import. */
  let picked = $state<ExternalInstance | null>(null);
  let name = $state("");
  let link = $state(false);
  let busy = $state(false);

  $effect(() => {
    if (!open) return;
    found = null;
    picked = null;
    commands
      .detectExternalInstances()
      .then((l) => (found = l))
      .catch(() => (found = []));
    commands
      .listGameVersions()
      .then((v) => (versions = v))
      .catch(() => {});
  });

  function choose(e: ExternalInstance) {
    picked = { ...e, gameVersion: e.gameVersion ?? null };
    name = e.name;
    link = false;
  }

  async function pickFolder() {
    const dir = await openDialog({ directory: true, multiple: false });
    if (typeof dir !== "string") return;
    try {
      choose(await commands.inspectExternalFolder(dir));
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  }

  async function doImport() {
    if (!picked?.gameVersion) return;
    busy = true;
    try {
      await commands.importExternal(picked, name.trim() || picked.name, link);
      toasts.info(t("importLaunchers.started"));
      open = false;
    } catch (e) {
      toasts.error(errorMessage(e));
    } finally {
      busy = false;
    }
  }

  const releases = $derived(
    versions?.versions.filter((v) => v.kind === "release" || v.id === picked?.gameVersion) ?? [],
  );
</script>

<Modal bind:open title={t("importLaunchers.title")} width="40rem">
  {#if !picked}
    <div class="col gap-3">
      <span class="hint">{t("importLaunchers.hint")}</span>
      {#if found === null}
        <div class="row small muted"><span class="spinner"></span>{t("importLaunchers.searching")}</div>
      {:else if !found.length}
        <p class="small muted">{t("importLaunchers.none")}</p>
      {:else}
        <ul class="list">
          {#each found as e (e.gameDir)}
            <li>
              <button class="item" onclick={() => choose(e)}>
                <span class="badge src">{t(`importLaunchers.sources.${e.source}`)}</span>
                <span class="col gap-1 grow" style="min-width:0">
                  <strong class="truncate">{e.name}</strong>
                  <span class="tiny faint truncate"
                    >{e.gameVersion ?? t("importLaunchers.unknownVersion")}{e.loader !== "vanilla"
                      ? ` · ${loaderLabel(e.loader)}`
                      : ""} · {t("importLaunchers.counts", { mods: e.mods, worlds: e.worlds })}</span
                  >
                </span>
                <Icon name="arrowRight" size={16} />
              </button>
            </li>
          {/each}
        </ul>
      {/if}
      <button class="btn ghost" onclick={pickFolder}
        ><Icon name="folder" size={15} />{t("importLaunchers.otherFolder")}</button
      >
    </div>
  {:else}
    <div class="col gap-3">
      <code class="path small">{picked.gameDir}</code>
      <div class="field">
        <label for="il-name">{t("newInstance.name")}</label>
        <input id="il-name" class="input" bind:value={name} maxlength="64" />
      </div>
      <div class="row gap-3 two">
        <div class="field grow">
          <label for="il-ver">{t("newInstance.gameVersion")}</label>
          <select id="il-ver" class="select" bind:value={picked.gameVersion}>
            {#if !picked.gameVersion}<option value={null}>{t("importLaunchers.chooseVersion")}</option>{/if}
            {#each releases as v (v.id)}<option value={v.id}>{v.id}</option>{/each}
          </select>
        </div>
        <div class="field grow">
          <label for="il-loader">{t("newInstance.loader")}</label>
          <select
            id="il-loader"
            class="select"
            value={picked.loader}
            onchange={(e) => {
              if (!picked) return;
              picked.loader = (e.target as HTMLSelectElement).value as LoaderKind;
              picked.loaderVersion = null;
            }}
          >
            {#each LOADERS as l (l)}<option value={l}>{loaderLabel(l)}</option>{/each}
          </select>
        </div>
      </div>
      {#if !picked.gameVersion}<span class="small warn">{t("importLaunchers.versionHint")}</span>{/if}
      <div class="segmented" role="group" aria-label={t("importLaunchers.mode")}>
        <button type="button" aria-pressed={!link} onclick={() => (link = false)}
          >{t("importLaunchers.copy")}</button
        >
        <button type="button" aria-pressed={link} onclick={() => (link = true)}
          >{t("importLaunchers.link")}</button
        >
      </div>
      <span class="hint">{link ? t("importLaunchers.linkHint") : t("importLaunchers.copyHint")}</span>
    </div>
  {/if}
  {#snippet footer()}
    {#if picked}
      <button class="btn ghost" onclick={() => (picked = null)}>{t("common.back")}</button>
      <button class="btn primary" disabled={busy || !picked.gameVersion} onclick={doImport}
        ><Icon name="download" size={15} />{t("importLaunchers.import")}</button
      >
    {:else}
      <button class="btn ghost" onclick={() => (open = false)}>{t("common.close")}</button>
    {/if}
  {/snippet}
</Modal>

<style>
  .list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: 0.25rem;
    max-height: 22rem;
    overflow-y: auto;
  }
  .item {
    display: flex;
    align-items: center;
    gap: var(--sp-2);
    width: 100%;
    padding: var(--sp-2);
    border: 1px solid var(--c-border);
    border-radius: var(--r-md);
    background: var(--c-surface-2);
    color: inherit;
    text-align: left;
    cursor: pointer;
  }
  .item:hover {
    border-color: var(--c-accent);
  }
  .src {
    flex: none;
    min-width: 6.5rem;
    justify-content: center;
  }
  .path {
    display: block;
    padding: var(--sp-2);
    border-radius: var(--r-sm);
    background: var(--c-surface-2);
    word-break: break-all;
  }
  .two > .field {
    flex: 1 1 0;
  }
  .warn {
    color: var(--c-warning);
  }
</style>
