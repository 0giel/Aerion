<script lang="ts">
  import Icon from "./Icon.svelte";
  import { commands, type CrashInfo } from "../api";
  import { t } from "../i18n/index.svelte";
  import { toasts } from "../stores/toasts.svelte";
  import { app } from "../stores/app.svelte";
  import { dialogs } from "../stores/dialogs.svelte";
  import { errorMessage } from "../api";
  import { openUrl } from "@tauri-apps/plugin-opener";

  let { crash, instanceId, ondetails }: { crash: CrashInfo; instanceId?: string; ondetails?: () => void } =
    $props();

  let busy = $state(false);
  let shared = $state<string | null>(null);
  const culprit = $derived(crash.modName ?? crash.modFile ?? "");

  /** Switch the suspected mod off (renamed to `.disabled`, re-enabled from the Content tab). */
  async function disableAndRelaunch() {
    if (!instanceId || !crash.modFile) return;
    busy = true;
    try {
      await commands.setContentEnabled(instanceId, "mod", crash.modFile, false);
      toasts.success(t("crash.disabled", { name: culprit }));
      await app.launch(instanceId);
    } catch (e) {
      toasts.error(errorMessage(e));
    } finally {
      busy = false;
    }
  }

  /** Publishing a log is outward-facing: always ask first. */
  async function share() {
    if (!instanceId) return;
    const ok = await dialogs.confirm({
      title: t("crash.shareTitle"),
      text: t("crash.shareText"),
      confirmLabel: t("crash.shareConfirm"),
    });
    if (!ok) return;
    busy = true;
    try {
      shared = await commands.shareGameLog(instanceId, crash.reportPath);
      await copy();
    } catch (e) {
      toasts.error(errorMessage(e));
    } finally {
      busy = false;
    }
  }

  async function copy() {
    if (!shared) return;
    try {
      await navigator.clipboard.writeText(shared);
      toasts.success(t("crash.linkCopied"));
    } catch {
      // Clipboard refused (no focus): the link stays visible to copy by hand.
    }
  }

  const params = $derived({
    ...crash.params,
    option: crash.params.option ? ` (${crash.params.option})` : "",
    mod: crash.params.mod ? ` (${crash.params.mod})` : "",
  });
  const text = $derived(t(`crash.kinds.${crash.kind}`, params));

  async function openReport() {
    if (!crash.reportPath) return;
    await commands
      .openFolder({ kind: "path", path: crash.reportPath })
      .catch((e) => toasts.error(String(e?.message ?? e)));
  }
</script>

<div class="alert danger crash" role="alert">
  <Icon name="bug" size={20} />
  <div class="col grow">
    <strong
      >{t("crash.title")}{#if crash.exitCode !== null}<span class="muted small">
          · {t("crash.exitCode", { code: crash.exitCode })}</span
        >{/if}</strong
    >
    <p>{text}</p>
    {#if crash.description}<p class="small muted">„{crash.description}”</p>{/if}
    {#if crash.excerpt}
      <details>
        <summary class="small">{t("common.details")}</summary>
        <pre class="mono selectable">{crash.excerpt}</pre>
      </details>
    {/if}
    {#if crash.modFile && instanceId}
      <div class="culprit row wrap gap-2">
        <span class="small grow"
          >{t("crash.culprit", { name: culprit })}<span class="faint"> · {crash.modFile}</span></span
        >
        <button
          class="btn sm primary"
          disabled={busy || !!app.running[instanceId]}
          onclick={disableAndRelaunch}><Icon name="power" size={15} />{t("crash.disableAndRelaunch")}</button
        >
      </div>
    {/if}
    {#if shared}
      <div class="row wrap gap-2 small">
        <Icon name="link" size={14} /><code class="selectable grow">{shared}</code>
        <button class="btn sm ghost" onclick={copy}><Icon name="copy" size={14} />{t("common.copy")}</button>
        <button class="btn sm ghost" onclick={() => shared && openUrl(shared)}
          ><Icon name="external" size={14} />{t("crash.openLink")}</button
        >
      </div>
    {/if}
    <div class="row wrap">
      {#if instanceId && !shared}<button class="btn sm" disabled={busy} onclick={share}
          >{#if busy}<span class="spinner"></span>{:else}<Icon name="link" size={15} />{/if}{t(
            "crash.share",
          )}</button
        >{/if}
      {#if crash.reportPath}<button class="btn sm" onclick={openReport}
          ><Icon name="folder" size={15} />{t("console.openReport")}</button
        >{/if}
      {#if ondetails}<button class="btn sm ghost" onclick={ondetails}
          ><Icon name="terminal" size={15} />{t("crash.viewLogs")}</button
        >{/if}
    </div>
  </div>
</div>

<style>
  .crash :global(.icon) {
    color: var(--c-danger);
    margin-top: 0.15rem;
  }
  pre {
    max-height: 14rem;
    overflow: auto;
    font-size: 0.78rem;
    padding: 0.6rem;
    border-radius: var(--r-sm);
    background: var(--c-hover);
    white-space: pre-wrap;
  }
  .culprit {
    padding: var(--sp-2) var(--sp-3);
    border-radius: var(--r-sm);
    background: var(--c-hover);
  }
  summary {
    cursor: pointer;
    color: var(--c-text-muted);
  }
</style>
