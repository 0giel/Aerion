<script lang="ts">
  import VirtualList from "./VirtualList.svelte";
  import type { LogLine } from "../api";

  let {
    lines,
    filter = "",
    levels,
    autoscroll = true,
  }: { lines: LogLine[]; filter?: string; levels: Set<string>; autoscroll?: boolean } = $props();

  interface Row {
    level: string;
    thread: string | null;
    text: string;
    head: boolean;
    time: number;
  }

  const rows = $derived.by(() => {
    const needle = filter.trim().toLowerCase();
    const out: Row[] = [];
    for (const l of lines) {
      const level = l.level;
      if (!levels.has(level === "fatal" ? "error" : level === "trace" ? "debug" : level)) continue;
      if (
        needle &&
        !l.text.toLowerCase().includes(needle) &&
        !(l.thread ?? "").toLowerCase().includes(needle)
      )
        continue;
      const parts = l.text.split("\n");
      parts.forEach((p, i) =>
        out.push({ level, thread: l.thread, text: p, head: i === 0, time: l.time ?? 0 }),
      );
    }
    return out;
  });

  let viewport: HTMLDivElement | undefined = $state();
  $effect(() => {
    void rows.length;
    if (autoscroll && viewport) {
      const v = viewport;
      requestAnimationFrame(() => (v.scrollTop = v.scrollHeight));
    }
  });

  const fmt = new Intl.DateTimeFormat(undefined, {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  });
</script>

<div class="log mono selectable" role="log" aria-live="off">
  <VirtualList items={rows} rowHeight={21} bind:viewport>
    {#snippet row(r: Row)}
      <div class="line {r.level}">
        <span class="time">{r.head ? fmt.format(r.time) : ""}</span>
        <span class="lvl">{r.head ? r.level.toUpperCase() : ""}</span>
        <span class="text">{r.text}</span>
      </div>
    {/snippet}
  </VirtualList>
</div>

<style>
  .log {
    height: 100%;
    font-size: 0.82rem;
    background: color-mix(in srgb, var(--c-bg) 70%, transparent);
    border: 1px solid var(--c-border);
    border-radius: var(--r-md);
    padding: 0.4rem 0;
  }
  .line {
    display: grid;
    grid-template-columns: 5.2rem 3.6rem 1fr;
    gap: 0.6rem;
    padding: 0 0.8rem;
    height: 21px;
    line-height: 21px;
    white-space: pre;
    color: var(--c-text-muted);
  }
  .line:hover {
    background: var(--c-hover);
  }
  .time,
  .lvl {
    color: var(--c-text-faint);
  }
  .text {
    overflow: hidden;
    text-overflow: ellipsis;
    color: var(--c-text);
  }
  .warn .lvl,
  .warn .text {
    color: var(--c-warning);
  }
  .error .lvl,
  .error .text,
  .fatal .lvl,
  .fatal .text {
    color: var(--c-danger);
  }
  .debug .text,
  .trace .text {
    color: var(--c-text-faint);
  }
  .raw .lvl {
    opacity: 0.6;
  }
</style>
