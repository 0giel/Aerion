<script lang="ts">
  // A 12-column board of widgets. In edit mode widgets are dragged by their header, resized by
  // the corner handle, and with the keyboard: arrows move, Shift+arrows resize, Delete removes.
  // Pointer events only (HTML drag and drop is unavailable while the window accepts files).
  import Icon from "../components/Icon.svelte";
  import type { WidgetItem } from "../api";
  import { COLS, clamp, compact, place, rows } from "./layout";
  import { WIDGETS } from "./widgets";
  import { t } from "../i18n/index.svelte";

  let {
    widgets,
    edit,
    onchange,
  }: { widgets: WidgetItem[]; edit: boolean; onchange: (w: WidgetItem[]) => void } = $props();

  const ROW = 72;
  const GAP = 12;
  let width = $state(0);
  /** Live layout while dragging; committed on release. */
  let preview = $state.raw<WidgetItem[] | null>(null);
  let dragging = $state<string | null>(null);
  const shown = $derived(preview ?? widgets);
  const cell = $derived((width - GAP * (COLS - 1)) / COLS);

  function minFor(kind: string) {
    return WIDGETS[kind]?.min ?? { w: 1, h: 1 };
  }

  function start(e: PointerEvent, w: WidgetItem, mode: "move" | "resize") {
    if (!edit || e.button !== 0) return;
    e.preventDefault();
    const el = e.currentTarget as HTMLElement;
    el.setPointerCapture(e.pointerId);
    const [x0, y0] = [e.clientX, e.clientY];
    dragging = w.id;
    const move = (ev: PointerEvent) => {
      const dx = Math.round((ev.clientX - x0) / (cell + GAP));
      const dy = Math.round((ev.clientY - y0) / (ROW + GAP));
      const min = minFor(w.kind);
      const next =
        mode === "move"
          ? { ...w, x: w.x + dx, y: Math.max(0, w.y + dy) }
          : { ...w, w: Math.max(min.w, w.w + dx), h: Math.max(min.h, w.h + dy) };
      preview = place(widgets, clamp(next));
    };
    const up = () => {
      el.removeEventListener("pointermove", move);
      el.removeEventListener("pointerup", up);
      el.removeEventListener("pointercancel", up);
      dragging = null;
      if (preview) onchange(preview);
      preview = null;
    };
    el.addEventListener("pointermove", move);
    el.addEventListener("pointerup", up);
    el.addEventListener("pointercancel", up);
  }

  function onkeydown(e: KeyboardEvent, w: WidgetItem) {
    if (!edit) return;
    if (e.key === "Delete" || e.key === "Backspace") {
      e.preventDefault();
      remove(w.id);
      return;
    }
    const d = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }[e.key];
    if (!d) return;
    e.preventDefault();
    const min = minFor(w.kind);
    const next = e.shiftKey
      ? { ...w, w: Math.max(min.w, w.w + d[0]), h: Math.max(min.h, w.h + d[1]) }
      : { ...w, x: w.x + d[0], y: Math.max(0, w.y + d[1]) };
    onchange(place(widgets, clamp(next)));
  }

  function remove(id: string) {
    onchange(compact(widgets.filter((w) => w.id !== id)));
  }

  function setConfig(id: string, config: Record<string, string>) {
    onchange(widgets.map((w) => (w.id === id ? { ...w, config } : w)));
  }
</script>

<div
  class="grid"
  class:edit
  bind:clientWidth={width}
  style:--row="{ROW}px"
  style:--gap="{GAP}px"
  style:min-height="{Math.max(1, rows(shown)) * (ROW + GAP)}px"
>
  {#each shown as w (w.id)}
    {@const def = WIDGETS[w.kind]}
    <!-- Focusable only in edit mode, where its role is "button". -->
    <!-- svelte-ignore a11y_no_noninteractive_tabindex -->
    <section
      class="widget card"
      class:dragging={dragging === w.id}
      style:grid-column="{w.x + 1} / span {w.w}"
      style:grid-row="{w.y + 1} / span {w.h}"
      tabindex={edit ? 0 : undefined}
      role={edit ? "button" : undefined}
      aria-label={def ? t(def.key) : w.kind}
      aria-roledescription={edit ? t("widgets.editableWidget") : undefined}
      onkeydown={(e) => onkeydown(e, w)}
    >
      {#if edit}
        <header class="bar" role="presentation" onpointerdown={(e) => start(e, w, "move")}>
          <Icon name={def?.icon ?? "grid"} size={13} />
          <span class="grow truncate">{def ? t(def.key) : w.kind}</span>
          <button
            class="icon-btn sm"
            aria-label={t("common.remove")}
            onpointerdown={(e) => e.stopPropagation()}
            onclick={() => remove(w.id)}><Icon name="x" size={13} /></button
          >
        </header>
      {/if}
      <div class="body">
        {#if def}
          {#await def.load() then mod}
            <mod.default config={w.config} {edit} onconfig={(c) => setConfig(w.id, c)} />
          {/await}
        {:else}
          <span class="muted small">{t("widgets.unknown")}</span>
        {/if}
      </div>
      {#if edit}
        <span
          class="resize"
          role="presentation"
          title={t("widgets.resize")}
          onpointerdown={(e) => start(e, w, "resize")}
        ></span>
      {/if}
    </section>
  {/each}
</div>

<style>
  .grid {
    display: grid;
    grid-template-columns: repeat(12, minmax(0, 1fr));
    grid-auto-rows: var(--row);
    gap: var(--gap);
  }
  .grid.edit {
    background-image: linear-gradient(
      to bottom,
      color-mix(in srgb, var(--c-accent) 6%, transparent) 1px,
      transparent 1px
    );
    background-size: 100% calc(var(--row) + var(--gap));
    border-radius: var(--r-md);
  }
  .widget {
    position: relative;
    display: flex;
    flex-direction: column;
    min-width: 0;
    min-height: 0;
    overflow: hidden;
    padding: var(--sp-3);
    transition: box-shadow var(--dur) var(--ease);
  }
  .edit .widget {
    outline: 1px dashed color-mix(in srgb, var(--c-accent) 45%, transparent);
  }
  .widget:focus-visible {
    outline: 2px solid var(--c-focus);
  }
  .widget.dragging {
    z-index: 3;
    box-shadow: 0 12px 30px rgb(0 0 0 / 0.35);
  }
  .bar {
    display: flex;
    align-items: center;
    gap: 0.35rem;
    margin: calc(-1 * var(--sp-2)) calc(-1 * var(--sp-2)) var(--sp-2);
    padding: 0.15rem 0.3rem;
    border-radius: var(--r-sm);
    background: var(--c-hover);
    font-size: 0.75rem;
    color: var(--c-text-muted);
    cursor: grab;
    touch-action: none;
    user-select: none;
  }
  .dragging .bar {
    cursor: grabbing;
  }
  .body {
    flex: 1;
    min-height: 0;
  }
  .resize {
    position: absolute;
    right: 2px;
    bottom: 2px;
    width: 14px;
    height: 14px;
    cursor: nwse-resize;
    touch-action: none;
    background: linear-gradient(
      135deg,
      transparent 50%,
      color-mix(in srgb, var(--c-accent) 70%, transparent) 50%
    );
    border-radius: 0 0 var(--r-sm) 0;
  }
</style>
