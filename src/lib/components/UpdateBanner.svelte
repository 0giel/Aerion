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
  /** Restarting would cut a running game off from the launcher. */
  const gameRunning = $derived(Object.keys(app.running).length > 0);

  $effect(() => {
    if (!app.ready || !app.settings?.checkUpdates) return;
    const background = app.settings.updateInBackground;
    const timer = setTimeout(() => void updates.check(false, background), 4000);
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
    {#if updates.ready}
      <span class="small grow">{t("appUpdate.ready", { version: updates.available.version })}</span>
      <button
        class="btn sm primary"
        disabled={installing || gameRunning}
        title={gameRunning ? t("appUpdate.closeGame") : ""}
        onclick={install}
        >{#if installing}<span class="spinner"></span>{:else}<Icon name="refresh" size={14} />{/if}{t(
          "appUpdate.restart",
        )}</button
      >
      <button class="btn sm ghost" onclick={() => (details = true)}>{t("appUpdate.whatsNew")}</button>
    {:else if updates.progress !== null}
      <span class="small grow"
        >{t("appUpdate.downloading", {
          version: updates.available.version,
          percent: Math.round(updates.progress * 100),
        })}</span
      >
      <span class="bar"><span style:width="{Math.round(updates.progress * 100)}%"></span></span>
    {:else}
      <span class="small grow">{t("appUpdate.available", { version: updates.available.version })}</span>
      <button class="btn sm primary" onclick={() => (details = true)}>{t("appUpdate.details")}</button>
    {/if}
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
    {#if updates.available?.canInstall && gameRunning}<p class="small muted">
        {t("appUpdate.closeGame")}
      </p>{/if}
    {#if updates.available?.needsAdmin}<p class="small muted">{t("appUpdate.admin")}</p>{/if}
    {#if updates.ready && !updates.available?.needsAdmin}<p class="small muted">
        {t("appUpdate.onExit")}
      </p>{/if}
  </div>
  {#snippet footer()}
    <button class="btn ghost" onclick={() => (details = false)}>{t("appUpdate.later")}</button>
    {#if updates.available?.canInstall}
      <button class="btn primary" disabled={installing || gameRunning} onclick={install}
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
  .bar {
    width: 5rem;
    height: 0.35rem;
    border-radius: 999px;
    background: var(--c-surface-2);
    overflow: hidden;
  }
  .bar span {
    display: block;
    height: 100%;
    background: var(--c-accent);
    transition: width var(--dur) var(--ease);
  }
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
