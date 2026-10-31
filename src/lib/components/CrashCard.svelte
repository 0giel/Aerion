<script lang="ts">
  import Icon from "./Icon.svelte";
  import { commands, type CrashInfo } from "../api";
  import { t } from "../i18n/index.svelte";
  import { toasts } from "../stores/toasts.svelte";

  let { crash, ondetails }: { crash: CrashInfo; ondetails?: () => void } = $props();

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
    <div class="row wrap">
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
  summary {
    cursor: pointer;
    color: var(--c-text-muted);
  }
</style>
