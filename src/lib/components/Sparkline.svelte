<script lang="ts">
  // A small line chart of the last N samples (one series, newest on the right). The pointer or
  // the arrow keys move a crosshair that shows the value at that moment.
  let {
    values,
    slots = 60,
    max,
    format,
    label,
    ago,
    height = 44,
  }: {
    /** Oldest first; `null` marks a gap (no data at that moment). */
    values: (number | null)[];
    /** Number of samples the chart spans, so it fills from the right as data arrives. */
    slots?: number;
    /** Top of the scale; defaults to the largest value. */
    max?: number;
    format: (v: number) => string;
    /** Accessible name, e.g. "CPU usage, last minute". */
    label: string;
    /** "n seconds ago" text for the tooltip. */
    ago: (secondsAgo: number) => string;
    height?: number;
  } = $props();

  let width = $state(0);
  let active = $state<number | null>(null);

  const top = $derived(Math.max(max ?? 0, ...values.map((v) => v ?? 0), 1e-9));
  const pad = 4;
  const x = (i: number) => {
    const offset = slots - values.length;
    return ((offset + i) / Math.max(1, slots - 1)) * Math.max(0, width - 2 * pad) + pad;
  };
  const y = (v: number) => height - pad - (v / top) * (height - 2 * pad);

  // Runs of consecutive values, so gaps break the line instead of drawing zeros.
  const runs = $derived.by(() => {
    const out: { i: number; v: number }[][] = [];
    let cur: { i: number; v: number }[] = [];
    values.forEach((v, i) => {
      if (v === null) {
        if (cur.length) out.push(cur);
        cur = [];
      } else cur.push({ i, v });
    });
    if (cur.length) out.push(cur);
    return out;
  });
  const line = (r: { i: number; v: number }[]) =>
    r.map((p, k) => `${k ? "L" : "M"}${x(p.i).toFixed(1)},${y(p.v).toFixed(1)}`).join("");
  const area = (r: { i: number; v: number }[]) =>
    `${line(r)}L${x(r[r.length - 1].i).toFixed(1)},${height - pad}L${x(r[0].i).toFixed(1)},${height - pad}Z`;

  const latest = $derived([...values].reverse().find((v) => v !== null) ?? null);
  const shown = $derived(active !== null ? values[active] : null);

  function nearest(e: PointerEvent) {
    const r = (e.currentTarget as SVGElement).getBoundingClientRect();
    const offset = slots - values.length;
    const slot = Math.round(((e.clientX - r.left - pad) / Math.max(1, r.width - 2 * pad)) * (slots - 1));
    const i = slot - offset;
    active = values.length ? Math.max(0, Math.min(values.length - 1, i)) : null;
  }

  function onkeydown(e: KeyboardEvent) {
    if (!values.length) return;
    const last = values.length - 1;
    if (e.key === "ArrowLeft") active = Math.max(0, (active ?? last) - 1);
    else if (e.key === "ArrowRight") active = Math.min(last, (active ?? last) + 1);
    else if (e.key === "Escape") active = null;
    else return;
    e.preventDefault();
  }
</script>

<div class="spark" bind:clientWidth={width} style:height="{height}px">
  {#if width}
    <svg
      {width}
      {height}
      role="slider"
      tabindex="0"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={Math.max(0, values.length - 1)}
      aria-valuenow={active ?? Math.max(0, values.length - 1)}
      aria-valuetext={shown !== null
        ? `${format(shown)}, ${ago(values.length - 1 - (active ?? 0))}`
        : latest !== null
          ? format(latest)
          : undefined}
      onpointermove={nearest}
      onpointerleave={() => (active = null)}
      onblur={() => (active = null)}
      {onkeydown}
    >
      <line class="base" x1={pad} x2={width - pad} y1={height - pad} y2={height - pad} />
      {#each runs as r, k (k)}
        <path class="area" d={area(r)} />
        <path class="line" d={line(r)} />
      {/each}
      {#if active !== null}
        <line class="cross" x1={x(active)} x2={x(active)} y1={pad} y2={height - pad} />
        {#if shown !== null}<circle class="dot" cx={x(active)} cy={y(shown)} r="4" />{/if}
      {/if}
    </svg>
    {#if active !== null}
      <div class="tip" style:left="{Math.min(Math.max(x(active), 40), width - 40)}px" role="status">
        <strong>{shown === null ? "—" : format(shown)}</strong>
        <span>{ago(values.length - 1 - active)}</span>
      </div>
    {/if}
  {/if}
</div>

<style>
  .spark {
    position: relative;
    width: 100%;
  }
  svg {
    display: block;
    overflow: visible;
    cursor: crosshair;
    border-radius: var(--r-sm);
  }
  svg:focus-visible {
    outline: 2px solid var(--c-focus);
    outline-offset: 2px;
  }
  .base {
    stroke: var(--c-border);
    stroke-width: 1;
  }
  .line {
    fill: none;
    stroke: var(--c-accent);
    stroke-width: 2;
    stroke-linejoin: round;
    stroke-linecap: round;
  }
  .area {
    fill: color-mix(in srgb, var(--c-accent) 10%, transparent);
  }
  .cross {
    stroke: var(--c-text-faint);
    stroke-width: 1;
  }
  .dot {
    fill: var(--c-accent);
    stroke: var(--c-surface);
    stroke-width: 2;
  }
  .tip {
    position: absolute;
    bottom: calc(100% + 4px);
    transform: translateX(-50%);
    display: flex;
    gap: 0.4rem;
    align-items: baseline;
    padding: 0.2rem 0.45rem;
    border-radius: var(--r-sm);
    background: var(--c-surface-3);
    border: 1px solid var(--c-border);
    white-space: nowrap;
    font-size: 0.75rem;
    pointer-events: none;
    z-index: 2;
  }
  .tip strong {
    color: var(--c-text);
  }
  .tip span {
    color: var(--c-text-muted);
  }
</style>
