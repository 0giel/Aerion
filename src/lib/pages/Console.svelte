<script lang="ts">
  import { SvelteSet } from "svelte/reactivity";
  import Icon from "../components/Icon.svelte";
  import LogView from "../components/LogView.svelte";
  import CrashCard from "../components/CrashCard.svelte";
  import { app } from "../stores/app.svelte";
  import { router } from "../stores/router.svelte";
  import { toasts } from "../stores/toasts.svelte";
  import { commands } from "../api";
  import { t } from "../i18n/index.svelte";

  const firstRunning = Object.keys(app.running)[0];
  let id = $state(router.params.id ?? firstRunning ?? app.lastInstance?.id ?? "");
  let filter = $state("");
  let autoscroll = $state(true);
  const all = ["info", "warn", "error", "debug", "raw"] as const;
  const levels = new SvelteSet<string>(["info", "warn", "error", "raw"]);

  $effect(() => {
    if (id) void app.loadLogs(id);
  });

  const lines = $derived(app.logsFor(id));
  const running = $derived(!!app.running[id]);
  const crash = $derived(app.crashes[id]);
  const exit = $derived(app.exitCodes[id]);

  function toggleLevel(l: string) {
    if (levels.has(l)) levels.delete(l);
    else levels.add(l);
  }

  async function copy() {
    const text = lines.map((l) => `[${l.level.toUpperCase()}] ${l.text}`).join("\n");
    await navigator.clipboard.writeText(text);
    toasts.success(t("common.copied"));
  }
</script>

<div class="page console">
  <header class="page-header">
    <h1>{t("console.title")}</h1>
    <select class="select pick" bind:value={id} aria-label={t("console.pick")}>
      {#each app.instances as i (i.id)}<option value={i.id}>{app.running[i.id] ? "● " : ""}{i.name}</option
        >{/each}
    </select>
    <span class="grow"></span>
    {#if running}
      <span class="badge success"><span class="live"></span>{t("console.running")}</span>
      <button class="btn danger" onclick={() => app.stop(id)}
        ><Icon name="stop" size={15} />{t("common.stop")}</button
      >
    {:else if exit !== undefined && exit !== null}
      <span class="badge">{t("console.exited", { code: exit })}</span>
    {/if}
  </header>

  {#if crash}<CrashCard {crash} instanceId={id} />{/if}

  <div class="bar row wrap">
    <input
      class="input filter"
      bind:value={filter}
      placeholder={t("console.filter")}
      aria-label={t("console.filter")}
    />
    <div class="row" role="group" aria-label={t("console.levels")}>
      {#each all as l (l)}<button class="chip" aria-pressed={levels.has(l)} onclick={() => toggleLevel(l)}
          >{l.toUpperCase()}</button
        >{/each}
    </div>
    <span class="grow"></span>
    <label class="row small muted auto"
      ><input type="checkbox" bind:checked={autoscroll} />{t("console.autoscroll")}</label
    >
    <span class="small faint">{t("console.lines", { n: lines.length })}</span>
    <button class="icon-btn" title={t("console.copy")} aria-label={t("console.copy")} onclick={copy}
      ><Icon name="copy" size={17} /></button
    >
    <button
      class="icon-btn"
      title={t("console.clear")}
      aria-label={t("console.clear")}
      onclick={() => app.clearLogs(id)}><Icon name="trash" size={17} /></button
    >
    <button
      class="icon-btn"
      title={t("common.openFolder")}
      aria-label={t("common.openFolder")}
      onclick={() => id && commands.openFolder({ kind: "gameDir", id })}
      ><Icon name="folder" size={17} /></button
    >
  </div>

  <div class="view">
    {#if !id || !lines.length}
      <div class="empty card">
        <Icon name="terminal" size={32} />
        <p>{t("console.noGame")}</p>
      </div>
    {:else}
      <LogView {lines} {filter} {levels} {autoscroll} />
    {/if}
  </div>
</div>

<style>
  .console {
    display: flex;
    flex-direction: column;
    gap: var(--sp-2);
  }
  .pick {
    width: auto;
    max-width: 20rem;
  }
  .filter {
    width: min(18rem, 100%);
  }
  .auto {
    gap: 0.4rem;
    cursor: pointer;
  }
  .view {
    flex: 1;
    min-height: 0;
  }
  .live {
    width: 0.45rem;
    height: 0.45rem;
    border-radius: 50%;
    background: var(--c-success);
    display: inline-block;
  }
</style>
