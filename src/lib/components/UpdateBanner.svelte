<script lang="ts">
  // "A new Aerion version is available": checked once shortly after start (if enabled) or on
  // demand from Settings; installs in place where the package allows it.
  import { openUrl } from "@tauri-apps/plugin-opener";
  import Modal from "./Modal.svelte";
  import Icon from "./Icon.svelte";
  import { app } from "../stores/app.svelte";
  import { toasts } from "../stores/toasts.svelte";
  import { updates } from "../stores/updates.svelte";
  import { commands, errorMessage } from "../api";
  import { t } from "../i18n/index.svelte";

  let details = $state(false);
  let installing = $state(false);

  $effect(() => {
    if (!app.ready || !app.settings?.checkUpdates) return;
    const timer = setTimeout(() => void updates.check(false), 4000);
    return () => clearTimeout(timer);
  });

  async function install() {
    installing = true;
    try {
      await commands.installAppUpdate();
    } catch (e) {
      toasts.error(errorMessage(e));
      installing = false;
    }
  }
</script>

{#if updates.available && !updates.dismissed}
  <div class="banner card raised" role="status">
    <Icon name="download" size={16} />
    <span class="small grow">{t("appUpdate.available", { version: updates.available.version })}</span>
    <button class="btn sm primary" onclick={() => (details = true)}>{t("appUpdate.details")}</button>
    <button class="icon-btn sm" aria-label={t("common.close")} onclick={() => (updates.dismissed = true)}
      ><Icon name="x" size={14} /></button
    >
  </div>
{/if}

<Modal
  bind:open={details}
  title={t("appUpdate.title", { version: updates.available?.version ?? "" })}
  width="30rem"
>
  <div class="col gap-3">
    <p class="small muted">{t("appUpdate.current", { version: app.info?.version ?? "" })}</p>
    {#if updates.available?.notes}<pre class="notes small">{updates.available.notes}</pre>{/if}
    {#if !updates.available?.canInstall}<p class="small">{t("appUpdate.manual")}</p>{/if}
  </div>
  {#snippet footer()}
    <button class="btn ghost" onclick={() => (details = false)}>{t("appUpdate.later")}</button>
    {#if updates.available?.canInstall}
      <button class="btn primary" disabled={installing} onclick={install}
        >{#if installing}<span class="spinner"></span>{t("appUpdate.installing")}{:else}<Icon
            name="refresh"
            size={15}
          />{t("appUpdate.install")}{/if}</button
      >
    {:else}
      <button class="btn primary" onclick={() => openUrl(updates.available?.url ?? "")}
        ><Icon name="external" size={15} />{t("appUpdate.download")}</button
      >
    {/if}
  {/snippet}
</Modal>

<style>
  .banner {
    position: fixed;
    left: var(--sp-4);
    bottom: var(--sp-4);
    z-index: 40;
    display: flex;
    align-items: center;
    gap: var(--sp-2);
    max-width: 26rem;
    padding: var(--sp-2) var(--sp-3);
    border-color: color-mix(in srgb, var(--c-accent) 45%, var(--c-border));
  }
  .notes {
    margin: 0;
    white-space: pre-wrap;
    max-height: 14rem;
    overflow: auto;
    padding: var(--sp-2);
    border-radius: var(--r-sm);
    background: var(--c-surface-2);
    font-family: inherit;
  }
</style>
