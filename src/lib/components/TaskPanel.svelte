<script lang="ts">
  import Icon from "./Icon.svelte";
  import ProgressBar from "./ProgressBar.svelte";
  import { app } from "../stores/app.svelte";
  import { commands, errorMessage, type TaskInfo } from "../api";
  import { formatBytes, t } from "../i18n/index.svelte";

  let expanded = $state(true);
  const tasks = $derived(app.tasks);
  const running = $derived(tasks.filter((x) => x.status === "running").length);

  function stage(task: TaskInfo) {
    const key = task.progress.stage.replace(/^stage\./, "");
    if (!key) return "";
    const text = t(`tasks.stages.${key}`, { detail: task.progress.detail ?? "" });
    return text.startsWith("tasks.") ? key : text;
  }

  function value(task: TaskInfo): number | null {
    const p = task.progress;
    if (task.status !== "running") return 1;
    if (p.totalBytes > 0) return p.doneBytes / p.totalBytes;
    if (p.totalItems > 0) return p.doneItems / p.totalItems;
    return null;
  }

  function amount(task: TaskInfo) {
    const p = task.progress;
    if (p.totalBytes > 0) return `${formatBytes(p.doneBytes)} / ${formatBytes(p.totalBytes)}`;
    if (p.totalItems > 1) return `${p.doneItems} / ${p.totalItems}`;
    return "";
  }
</script>

{#if tasks.length}
  <section class="panel" aria-label={t("tasks.title")}>
    <button class="head" onclick={() => (expanded = !expanded)} aria-expanded={expanded}>
      {#if running}<span class="spinner"></span>{:else}<Icon name="check" size={16} />{/if}
      <span class="grow">{running ? t("tasks.running", { n: running }) : t("tasks.title")}</span>
      <span class="chev" class:open={expanded}><Icon name="chevronDown" size={16} /></span>
    </button>
    {#if expanded}
      <ul>
        {#each tasks as task (task.id)}
          <li class:failed={task.status === "failed"}>
            <div class="row between">
              <div class="grow">
                <div class="title truncate">
                  {t(`tasks.titles.${task.kind}`)}{#if task.subject}<span class="muted">
                      · {task.subject}</span
                    >{/if}
                </div>
                <div class="sub tiny muted truncate">
                  {#if task.status === "failed"}<span class="danger-text">{errorMessage(task.error)}</span>
                  {:else if task.status === "cancelled"}{t("tasks.cancelled")}
                  {:else if task.status === "done"}{t("tasks.finished")}
                  {:else}{stage(task)}{#if amount(task)}<span class="faint"> · {amount(task)}</span>{/if}{/if}
                </div>
              </div>
              {#if task.status === "running"}
                <button
                  class="icon-btn sm"
                  aria-label={t("tasks.cancel")}
                  title={t("tasks.cancel")}
                  onclick={() => commands.cancelTask(task.id)}
                >
                  <Icon name="x" size={15} />
                </button>
              {:else}
                <button
                  class="icon-btn sm"
                  aria-label={t("common.dismiss")}
                  onclick={() => app.dismissTask(task.id)}
                >
                  <Icon name="x" size={15} />
                </button>
              {/if}
            </div>
            {#if task.status === "running"}<ProgressBar
                value={value(task)}
                label={t(`tasks.titles.${task.kind}`)}
              />{/if}
          </li>
        {/each}
      </ul>
    {/if}
  </section>
{/if}

<style>
  .panel {
    position: fixed;
    right: 1rem;
    bottom: 1rem;
    z-index: 90;
    width: min(22rem, calc(100vw - 2rem));
    background: var(--c-surface);
    border: 1px solid var(--c-border);
    border-radius: var(--r-lg);
    box-shadow: var(--shadow-lg);
    overflow: hidden;
    animation: fade-up var(--dur-slow) var(--ease);
  }
  .head {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    width: 100%;
    padding: 0.7rem 0.9rem;
    border: 0;
    background: transparent;
    font-weight: 600;
    cursor: pointer;
    text-align: left;
  }
  .chev {
    transform: rotate(180deg);
    transition: transform var(--dur) var(--ease);
    color: var(--c-text-muted);
  }
  .chev.open {
    transform: none;
  }
  ul {
    list-style: none;
    margin: 0;
    padding: 0 0.6rem 0.6rem;
    display: flex;
    flex-direction: column;
    gap: 0.4rem;
    max-height: 18rem;
    overflow: auto;
  }
  li {
    display: flex;
    flex-direction: column;
    gap: 0.45rem;
    padding: 0.6rem 0.65rem;
    border-radius: var(--r-md);
    background: var(--c-surface-2);
  }
  li.failed {
    background: color-mix(in srgb, var(--c-danger) 10%, var(--c-surface-2));
  }
  .title {
    font-weight: 560;
  }
  .spinner {
    width: 15px;
    height: 15px;
    border-radius: 50%;
    border: 2px solid var(--c-accent-soft);
    border-top-color: var(--c-accent);
    animation: spin 0.8s linear infinite;
  }
</style>
