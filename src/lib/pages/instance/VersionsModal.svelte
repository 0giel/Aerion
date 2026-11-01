<script lang="ts">
  // Every version of one installed mod for this instance: install any of them (older ones too),
  // and keep the mod pinned so update checks leave the choice alone.
  import Modal from "../../components/Modal.svelte";
  import Switch from "../../components/Switch.svelte";
  import Icon from "../../components/Icon.svelte";
  import { toasts } from "../../stores/toasts.svelte";
  import { commands, errorMessage, type ContentEntry, type Instance, type ProjectVersion } from "../../api";
  import { formatRelative, t } from "../../i18n/index.svelte";

  let {
    open = $bindable(false),
    instance,
    entry,
    onchanged,
  }: { open?: boolean; instance: Instance; entry: ContentEntry; onchanged: () => void } = $props();

  let versions = $state.raw<ProjectVersion[] | null>(null);
  let error = $state<string | null>(null);
  let busy = $state(false);
  let pinned = $state(false);

  $effect(() => {
    if (!open || !entry.provider || !entry.projectId) return;
    pinned = entry.pinned;
    versions = null;
    error = null;
    commands
      .getProjectVersions(entry.provider, entry.projectId, instance.gameVersion, instance.loader, entry.kind)
      .then((v) => (versions = v))
      .catch((e) => (error = errorMessage(e)));
  });

  async function install(v: ProjectVersion) {
    busy = true;
    try {
      await commands.applyContentUpdates(
        instance.id,
        [
          {
            fileName: entry.fileName,
            kind: entry.kind,
            title: entry.title,
            current: entry.versionNumber,
            version: v,
          },
        ],
        true,
      );
      toasts.success(t("modVersions.installing", { version: v.versionNumber }));
      open = false;
      onchanged();
    } catch (e) {
      toasts.error(errorMessage(e));
    } finally {
      busy = false;
    }
  }

  async function setPinned(v: boolean) {
    try {
      await commands.setContentPinned(instance.id, entry.kind, entry.fileName, v);
      pinned = v;
      onchanged();
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  }
</script>

<Modal bind:open title={t("modVersions.title", { name: entry.title })} width="38rem">
  <div class="col gap-3">
    <Switch
      label={t("modVersions.pin")}
      hint={t("modVersions.pinHint")}
      checked={pinned}
      onchange={setPinned}
    />
    {#if error}
      <div class="alert danger"><Icon name="alert" size={16} /><span>{error}</span></div>
    {:else if versions === null}
      <span class="small muted">{t("common.loading")}</span>
    {:else if !versions.length}
      <span class="small muted">{t("modVersions.none", { version: instance.gameVersion })}</span>
    {:else}
      <ul class="list">
        {#each versions as v (v.id)}
          {@const current = v.id === entry.versionId}
          <li class:current>
            <div class="col gap-1 grow" style="min-width:0">
              <div class="row" style="gap:0.4rem">
                <strong class="truncate">{v.versionNumber}</strong>
                {#if v.kind !== "release"}<span class="badge warn">{t(`modVersions.kinds.${v.kind}`)}</span
                  >{/if}
                {#if current}<span class="badge accent">{t("modVersions.current")}</span>{/if}
              </div>
              <span class="tiny faint truncate"
                >{formatRelative(Date.parse(v.published) / 1000)} · {v.loaders.join(", ")}</span
              >
            </div>
            {#if !current}
              <button class="btn sm" disabled={busy} onclick={() => install(v)}
                ><Icon name="download" size={14} />{t("modVersions.install")}</button
              >
            {/if}
          </li>
        {/each}
      </ul>
    {/if}
  </div>
</Modal>

<style>
  .list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 0.3rem;
    max-height: 22rem;
    overflow-y: auto;
  }
  li {
    display: flex;
    align-items: center;
    gap: var(--sp-2);
    padding: 0.45rem 0.6rem;
    border-radius: var(--r-sm);
    background: var(--c-surface-2);
  }
  li.current {
    outline: 1px solid var(--c-accent);
  }
</style>
