<script lang="ts">
  import SkinViewer from "../../components/SkinViewer.svelte";
  import { app } from "../../stores/app.svelte";
  import { DEFAULT_SKIN } from "../../api";
  import type { WidgetProps } from "../widgets";

  // Widgets without options still take the shared props.
  const _props: WidgetProps = $props();
  const account = $derived(app.selectedAccount);
  let h = $state(0);
</script>

<div class="player" bind:clientHeight={h}>
  <strong class="small">{account?.username ?? "Steve"}</strong>
  {#if h}
    <SkinViewer
      skinUrl={account?.skinUrl ?? DEFAULT_SKIN}
      capeUrl={account?.capeUrl}
      follow={app.settings?.followCursor ?? true}
      slim={account?.skinVariant === "slim"}
      scale={Math.max(3, Math.min(9, Math.floor((h - 28) / 37)))}
    />
  {/if}
</div>

<style>
  .player {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: var(--sp-1);
    height: 100%;
    overflow: hidden;
  }
</style>
