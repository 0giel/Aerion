<script lang="ts">
  // Memory (RAM) for the game: a slider in 256 MB steps up to ~85 % of the computer's memory,
  // with GB marks and a warning when the value is risky.
  import { app } from "../stores/app.svelte";
  import { t } from "../i18n/index.svelte";

  let {
    value,
    disabled = false,
    onchange,
    label,
  }: { value: number; disabled?: boolean; onchange: (mb: number) => void; label: string } = $props();

  const total = $derived(app.info?.totalMemoryMb ?? 8192);
  const max = $derived(Math.max(2048, Math.floor((total * 0.85) / 256) * 256));
  const min = 1024;
  // Live value while dragging; committed on release.
  let live = $state<number | null>(null);
  const shown = $derived(live ?? value);
  const gb = (mb: number) => (mb / 1024).toFixed(mb % 1024 ? 1 : 0);
  const marks = $derived.by(() => {
    const step = max > 16384 ? 4096 : 2048;
    const out: number[] = [];
    for (let m = step; m < max; m += step) out.push(m);
    return out;
  });
  const pct = (mb: number) => ((mb - min) / (max - min)) * 100;
  const warning = $derived(shown > total * 0.75 ? "high" : shown < 2048 ? "low" : null);
</script>

<div class="mem" class:disabled>
  <div class="head">
    <strong class="value">{gb(shown)} GB</strong>
    <span class="tiny faint">{shown} MB · {t("memory.ofTotal", { total: gb(total) })}</span>
  </div>
  <div class="slider">
    <input
      type="range"
      {min}
      {max}
      step="256"
      value={shown}
      {disabled}
      aria-label={label}
      oninput={(e) => (live = Number((e.target as HTMLInputElement).value))}
      onchange={(e) => {
        const v = Number((e.target as HTMLInputElement).value);
        live = null;
        onchange(v);
      }}
    />
    <div class="marks" aria-hidden="true">
      {#each marks as m (m)}<span style:left="{pct(m)}%">{gb(m)}</span>{/each}
    </div>
  </div>
  {#if warning && !disabled}
    <span class="tiny warn">{t(warning === "high" ? "memory.tooHigh" : "memory.tooLow")}</span>
  {/if}
</div>

<style>
  .mem {
    display: grid;
    gap: 0.35rem;
  }
  .mem.disabled {
    opacity: 0.6;
  }
  .head {
    display: flex;
    align-items: baseline;
    gap: 0.6rem;
  }
  .value {
    font-size: 1.15rem;
    font-variant-numeric: tabular-nums;
  }
  .slider {
    position: relative;
    padding-bottom: 1.1rem;
  }
  .slider input {
    width: 100%;
  }
  .marks {
    position: absolute;
    left: 8px;
    right: 8px;
    bottom: 0;
    height: 1rem;
  }
  .marks span {
    position: absolute;
    transform: translateX(-50%);
    font-size: 0.7rem;
    color: var(--c-text-faint);
  }
  .warn {
    color: var(--c-warning);
  }
</style>
