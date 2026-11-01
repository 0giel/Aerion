<script lang="ts">
  import Icon from "../../components/Icon.svelte";
  import { app } from "../../stores/app.svelte";
  import { instanceVersionLabel } from "../../api";
  import { t } from "../../i18n/index.svelte";
  import type { WidgetProps } from "../widgets";

  // Widgets without options still take the shared props.
  const _props: WidgetProps = $props();
  const current = $derived(app.lastInstance);
  const running = $derived(current ? !!app.running[current.id] : false);
</script>

<div class="play">
  {#if current}
    <button
      class="btn primary big"
      class:danger={running}
      onclick={() => (running ? app.stop(current.id) : app.launch(current.id))}
    >
      <Icon name={running ? "stop" : "play"} size={20} />
      <span class="col" style="align-items:flex-start;min-width:0">
        <strong>{running ? t("common.stop") : t("common.play")}</strong>
        <span class="tiny truncate">{current.name} · {instanceVersionLabel(current)}</span>
      </span>
    </button>
    <select
      class="select"
      aria-label={t("home.chooseInstance")}
      value={current.id}
      onchange={(e) => app.saveSettings({ lastInstance: (e.target as HTMLSelectElement).value })}
    >
      {#each app.instances as i (i.id)}<option value={i.id}>{i.name}</option>{/each}
    </select>
  {:else}
    <span class="muted small">{t("widgets.noInstance")}</span>
  {/if}
</div>

<style>
  .play {
    display: flex;
    flex-direction: column;
    gap: var(--sp-2);
    justify-content: center;
    height: 100%;
  }
  .big {
    justify-content: flex-start;
    gap: var(--sp-3);
    height: auto;
    min-height: 3.2rem;
    padding-block: var(--sp-2);
    font-size: 1.05rem;
  }
</style>
