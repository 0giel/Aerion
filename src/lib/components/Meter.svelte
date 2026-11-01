<script lang="ts">
  // A share of a limit (memory used of total): the fill turns to warning and danger as it fills;
  // the track is a light step of the same colour.
  let {
    value,
    max,
    label,
    warn = 0.8,
    danger = 0.92,
  }: { value: number; max: number; label: string; warn?: number; danger?: number } = $props();

  const ratio = $derived(max > 0 ? Math.min(1, Math.max(0, value / max)) : 0);
  const level = $derived(ratio >= danger ? "danger" : ratio >= warn ? "warn" : "ok");
</script>

<div
  class="meter {level}"
  role="meter"
  aria-label={label}
  aria-valuemin={0}
  aria-valuemax={max}
  aria-valuenow={value}
>
  <span style:width="{ratio * 100}%"></span>
</div>

<style>
  .meter {
    --fill: var(--c-accent);
    height: 6px;
    border-radius: 999px;
    background: color-mix(in srgb, var(--fill) 18%, transparent);
    overflow: hidden;
  }
  .meter.warn {
    --fill: var(--c-warning);
  }
  .meter.danger {
    --fill: var(--c-danger);
  }
  span {
    display: block;
    height: 100%;
    border-radius: inherit;
    background: var(--fill);
    transition: width var(--dur) var(--ease);
  }
</style>
