<script lang="ts">
  import Icon from "../../components/Icon.svelte";
  import { app } from "../../stores/app.svelte";
  import { router } from "../../stores/router.svelte";
  import { ui } from "../../stores/ui.svelte";
  import { t } from "../../i18n/index.svelte";
  import type { WidgetProps } from "../widgets";

  // Widgets without options still take the shared props.
  const _props: WidgetProps = $props();
  const list = $derived(
    app.instances.filter((i) => app.contentUpdates[i.id]).map((i) => ({ i, n: app.contentUpdates[i.id] })),
  );
</script>

<div class="col gap-1 updates">
  {#if !list.length}
    <span class="muted small"><Icon name="check" size={14} /> {t("widgets.noUpdates")}</span>
  {:else}
    {#each list as { i, n } (i.id)}
      <button class="row item" onclick={() => router.go("instance", { id: i.id, tab: "content" })}>
        <span class="grow truncate">{i.name}</span>
        <span class="badge success"><Icon name="arrowUp" size={11} />{n}</span>
      </button>
    {/each}
    <button class="btn sm" onclick={() => (ui.updateAllOpen = true)}
      ><Icon name="arrowUp" size={13} />{t("updateAll.widget")}</button
    >
  {/if}
</div>

<style>
  .updates {
    height: 100%;
    overflow-y: auto;
    justify-content: center;
  }
  .item {
    border: 0;
    background: none;
    color: inherit;
    padding: 0.2rem 0;
    cursor: pointer;
    text-align: left;
  }
</style>
