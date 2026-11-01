<script lang="ts">
  import { app } from "../../stores/app.svelte";
  import { formatPlayTime, t } from "../../i18n/index.svelte";
  import type { WidgetProps } from "../widgets";

  // Widgets without options still take the shared props.
  const _props: WidgetProps = $props();
  const total = $derived(app.instances.reduce((s, i) => s + i.playTimeSecs, 0));
  const sessions = $derived(app.instances.reduce((s, i) => s + i.launches, 0));
</script>

<div class="stats">
  <div><strong>{formatPlayTime(total)}</strong><span class="tiny muted">{t("stats.playTime")}</span></div>
  <div><strong>{app.instances.length}</strong><span class="tiny muted">{t("stats.instances")}</span></div>
  <div><strong>{sessions}</strong><span class="tiny muted">{t("stats.launches")}</span></div>
</div>

<style>
  .stats {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(5.5rem, 1fr));
    gap: var(--sp-2);
    align-content: center;
    height: 100%;
  }
  .stats div {
    display: flex;
    flex-direction: column;
  }
  strong {
    font-size: 1.3rem;
  }
</style>
