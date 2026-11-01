<script lang="ts">
  import Icon from "../../components/Icon.svelte";
  import InstanceIcon from "../../components/InstanceIcon.svelte";
  import { app } from "../../stores/app.svelte";
  import { router } from "../../stores/router.svelte";
  import { instanceVersionLabel } from "../../api";
  import { formatRelative, t } from "../../i18n/index.svelte";
  import type { WidgetProps } from "../widgets";

  let { config, edit, onconfig }: WidgetProps = $props();
  const count = $derived(Number(config.count) || 5);
  const list = $derived(
    [...app.instances].sort((a, b) => (b.lastPlayed ?? 0) - (a.lastPlayed ?? 0)).slice(0, count),
  );
</script>

<div class="col gap-1 recent">
  {#if edit}
    <label class="row small muted"
      >{t("widgets.count")}
      <input
        class="input num"
        type="number"
        min="1"
        max="20"
        value={count}
        onchange={(e) => onconfig({ ...config, count: (e.target as HTMLInputElement).value })}
      /></label
    >
  {/if}
  {#each list as i (i.id)}
    <div class="row item">
      <InstanceIcon name={i.name} loader={i.loader} icon={i.icon} id={i.id} size={28} />
      <button class="link grow col gap-1" onclick={() => router.go("instance", { id: i.id })}>
        <span class="truncate strong">{i.name}</span>
        <span class="tiny faint truncate">{instanceVersionLabel(i)} · {formatRelative(i.lastPlayed)}</span>
      </button>
      <button class="icon-btn sm" aria-label={t("common.play")} onclick={() => app.launch(i.id)}
        ><Icon name="play" size={14} /></button
      >
    </div>
  {/each}
</div>

<style>
  .recent {
    height: 100%;
    overflow-y: auto;
  }
  .item {
    gap: var(--sp-2);
  }
  .link {
    min-width: 0;
    align-items: flex-start;
    border: 0;
    background: none;
    color: inherit;
    text-align: left;
    cursor: pointer;
    padding: 0;
  }
  .num {
    width: 4.5rem;
  }
</style>
