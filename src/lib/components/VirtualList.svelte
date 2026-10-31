<script lang="ts" generics="T">
  // Windowed list with fixed row height: only visible rows are in the DOM, so thousands of
  // entries scroll at 60 fps.
  import type { Snippet } from "svelte";

  let {
    items,
    rowHeight,
    row,
    overscan = 8,
    onend,
    viewport = $bindable(),
    class: klass = "",
  }: {
    items: T[];
    rowHeight: number;
    row: Snippet<[T, number]>;
    overscan?: number;
    onend?: () => void;
    viewport?: HTMLDivElement;
    class?: string;
  } = $props();

  let scrollTop = $state(0);
  let height = $state(600);

  const start = $derived(Math.max(0, Math.floor(scrollTop / rowHeight) - overscan));
  const end = $derived(Math.min(items.length, Math.ceil((scrollTop + height) / rowHeight) + overscan));
  const visible = $derived(items.slice(start, end));

  function onscroll() {
    if (!viewport) return;
    scrollTop = viewport.scrollTop;
    if (onend && viewport.scrollTop + viewport.clientHeight > viewport.scrollHeight - rowHeight * 4) onend();
  }
</script>

<div class="viewport {klass}" bind:this={viewport} bind:clientHeight={height} {onscroll}>
  <div class="spacer" style:height="{items.length * rowHeight}px">
    <div class="window" style:transform="translateY({start * rowHeight}px)">
      {#each visible as item, i (start + i)}
        <div class="row" style:height="{rowHeight}px">{@render row(item, start + i)}</div>
      {/each}
    </div>
  </div>
</div>

<style>
  .viewport {
    overflow-y: auto;
    overflow-x: hidden;
    height: 100%;
    contain: strict;
  }
  .spacer {
    position: relative;
  }
  .window {
    position: absolute;
    inset: 0 0 auto 0;
    will-change: transform;
  }
  .row {
    overflow: hidden;
  }
</style>
