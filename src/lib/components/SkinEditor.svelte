<script lang="ts">
  // A pixel editor for a skin: the 64×64 picture large on the left, the player turning on the
  // right with every stroke. Pencil, eraser, colour picker and fill; undo and redo; outlines of
  // the body parts so it's clear where each one is. Saves a new skin to the library.
  import Modal from "./Modal.svelte";
  import Icon from "./Icon.svelte";
  import SkinViewer from "./SkinViewer.svelte";
  import { commands, errorMessage } from "../api";
  import type { SkinVariant } from "../bindings";
  import { toasts } from "../stores/toasts.svelte";
  import { t } from "../i18n/index.svelte";

  let {
    open = $bindable(false),
    source,
    name: startName = "",
    variant: startVariant = "classic",
    onsaved,
  }: {
    open?: boolean;
    /** The skin to start from: a saved one or a skin on Mojang's server. */
    source: { id: string } | { url: string } | null;
    name?: string;
    variant?: SkinVariant;
    onsaved?: (id: string) => void;
  } = $props();

  type Tool = "pencil" | "eraser" | "picker" | "fill";
  const TOOLS: { id: Tool; icon: string }[] = [
    { id: "pencil", icon: "pencil" },
    { id: "eraser", icon: "eraser" },
    { id: "picker", icon: "eyedropper" },
    { id: "fill", icon: "bucket" },
  ];
  const ZOOM = 8;
  const SIZE = 64;

  let canvas: HTMLCanvasElement | undefined = $state();
  let tool = $state<Tool>("pencil");
  let color = $state("#3b82f6");
  let recent = $state<string[]>([]);
  let grid = $state(true);
  let guides = $state(true);
  let name = $state("");
  let variant = $state<SkinVariant>("classic");
  let preview = $state<string | undefined>(undefined);
  let loading = $state(false);
  let saving = $state(false);
  const undo: ImageData[] = [];
  const redo: ImageData[] = [];
  let history = $state(0);

  const ctx = () => canvas?.getContext("2d", { willReadFrequently: true }) ?? null;

  /** Where each body part's faces are on the picture (inner layer, then outer): for the outlines. */
  const PARTS: [number, number, number, number, number][] = [
    // u, v, width, height, depth (Minecraft's box layout)
    [0, 0, 8, 8, 8],
    [32, 0, 8, 8, 8],
    [16, 16, 8, 12, 4],
    [16, 32, 8, 12, 4],
    [40, 16, 4, 12, 4],
    [40, 32, 4, 12, 4],
    [32, 48, 4, 12, 4],
    [48, 48, 4, 12, 4],
    [0, 16, 4, 12, 4],
    [0, 32, 4, 12, 4],
    [16, 48, 4, 12, 4],
    [0, 48, 4, 12, 4],
  ];
  const boxes = PARTS.flatMap(([u, v, w, h, d]) => [
    [u + d, v, w, d],
    [u + d + w, v, w, d],
    [u, v + d, d, h],
    [u + d, v + d, w, h],
    [u + d + w, v + d, d, h],
    [u + 2 * d + w, v + d, w, h],
  ]);

  $effect(() => {
    if (!open) return;
    name = startName ? `${startName} (2)` : t("skinEditor.newName");
    variant = startVariant;
    undo.length = 0;
    redo.length = 0;
    history = 0;
    void load();
  });

  async function load() {
    const c = ctx();
    if (!c || !source) return;
    loading = true;
    try {
      const bytes =
        "id" in source ? await commands.skinPng(source.id, null) : await commands.skinPng(null, source.url);
      const blob = new Blob([new Uint8Array(bytes)], { type: "image/png" });
      const img = await createImageBitmap(blob);
      c.clearRect(0, 0, SIZE, SIZE);
      c.drawImage(img, 0, 0);
      // An old 64×32 skin: the left arm and leg copy the right ones, as the game shows them.
      if (img.height === 32) {
        c.drawImage(canvas!, 0, 16, 16, 16, 16, 48, 16, 16);
        c.drawImage(canvas!, 40, 16, 16, 16, 32, 48, 16, 16);
      }
      refresh();
    } catch (e) {
      toasts.error(errorMessage(e));
    } finally {
      loading = false;
    }
  }

  let refreshTimer: ReturnType<typeof setTimeout> | undefined;
  function refresh(now = true) {
    clearTimeout(refreshTimer);
    const go = () => (preview = canvas?.toDataURL("image/png"));
    if (now) go();
    else refreshTimer = setTimeout(go, 120);
  }

  function snapshot() {
    const c = ctx();
    if (!c) return;
    undo.push(c.getImageData(0, 0, SIZE, SIZE));
    if (undo.length > 100) undo.shift();
    redo.length = 0;
    history++;
  }

  function step(from: ImageData[], to: ImageData[]) {
    const c = ctx();
    const prev = from.pop();
    if (!c || !prev) return;
    to.push(c.getImageData(0, 0, SIZE, SIZE));
    c.putImageData(prev, 0, 0);
    history++;
    refresh();
  }

  const hex = (r: number, g: number, b: number) =>
    "#" + [r, g, b].map((v) => v.toString(16).padStart(2, "0")).join("");

  function rgba(): [number, number, number, number] {
    const n = parseInt(color.slice(1), 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255, 255];
  }

  function at(e: PointerEvent): [number, number] | null {
    if (!canvas) return null;
    const r = canvas.getBoundingClientRect();
    const x = Math.floor(((e.clientX - r.left) / r.width) * SIZE);
    const y = Math.floor(((e.clientY - r.top) / r.height) * SIZE);
    return x >= 0 && y >= 0 && x < SIZE && y < SIZE ? [x, y] : null;
  }

  function paint(x: number, y: number) {
    const c = ctx();
    if (!c) return;
    if (tool === "eraser") c.clearRect(x, y, 1, 1);
    else {
      c.fillStyle = color;
      c.fillRect(x, y, 1, 1);
    }
  }

  function fill(x: number, y: number) {
    const c = ctx();
    if (!c) return;
    const img = c.getImageData(0, 0, SIZE, SIZE);
    const d = img.data;
    const i0 = (y * SIZE + x) * 4;
    const target = [d[i0], d[i0 + 1], d[i0 + 2], d[i0 + 3]];
    const next = rgba();
    if (target.every((v, k) => v === next[k])) return;
    const same = (i: number) =>
      d[i] === target[0] && d[i + 1] === target[1] && d[i + 2] === target[2] && d[i + 3] === target[3];
    // Only within the face that was clicked, so a fill never runs into a neighbouring part.
    const box = boxes.find(([bx, by, bw, bh]) => x >= bx && y >= by && x < bx + bw && y < by + bh) ?? [
      0,
      0,
      SIZE,
      SIZE,
    ];
    const stack = [[x, y]];
    while (stack.length) {
      const [px, py] = stack.pop()!;
      if (px < box[0] || py < box[1] || px >= box[0] + box[2] || py >= box[1] + box[3]) continue;
      const i = (py * SIZE + px) * 4;
      if (!same(i)) continue;
      d.set(next, i);
      stack.push([px + 1, py], [px - 1, py], [px, py + 1], [px, py - 1]);
    }
    c.putImageData(img, 0, 0);
  }

  function remember(c: string) {
    recent = [c, ...recent.filter((r) => r !== c)].slice(0, 10);
  }

  let drawing = false;
  let last: [number, number] | null = null;
  function down(e: PointerEvent) {
    const p = at(e);
    const c = ctx();
    if (!p || !c) return;
    if (tool === "picker") {
      const [r, g, b, a] = c.getImageData(p[0], p[1], 1, 1).data;
      if (a > 0) {
        color = hex(r, g, b);
        tool = "pencil";
      }
      return;
    }
    snapshot();
    if (tool !== "eraser") remember(color);
    if (tool === "fill") {
      fill(p[0], p[1]);
      refresh();
      return;
    }
    drawing = true;
    last = p;
    canvas?.setPointerCapture(e.pointerId);
    paint(p[0], p[1]);
    refresh(false);
  }
  function move(e: PointerEvent) {
    if (!drawing) return;
    const p = at(e);
    if (!p || !last) return;
    // Every pixel between the last point and this one, so fast strokes have no gaps.
    const n = Math.max(Math.abs(p[0] - last[0]), Math.abs(p[1] - last[1]));
    for (let i = 1; i <= n; i++) {
      paint(
        Math.round(last[0] + ((p[0] - last[0]) * i) / n),
        Math.round(last[1] + ((p[1] - last[1]) * i) / n),
      );
    }
    last = p;
    refresh(false);
  }
  function up() {
    if (!drawing) return;
    drawing = false;
    last = null;
    refresh();
  }

  function keys(e: KeyboardEvent) {
    if (!open || (e.target as HTMLElement)?.tagName === "INPUT") return;
    const mod = e.ctrlKey || e.metaKey;
    if (mod && e.key.toLowerCase() === "z" && !e.shiftKey) {
      e.preventDefault();
      step(undo, redo);
    } else if (mod && (e.key.toLowerCase() === "y" || (e.key.toLowerCase() === "z" && e.shiftKey))) {
      e.preventDefault();
      step(redo, undo);
    } else if (!mod && "1234".includes(e.key)) tool = TOOLS[Number(e.key) - 1].id;
  }

  async function save() {
    if (!canvas) return;
    saving = true;
    try {
      const blob = await new Promise<Blob | null>((r) => canvas!.toBlob(r, "image/png"));
      if (!blob) throw new Error("png");
      const bytes = Array.from(new Uint8Array(await blob.arrayBuffer()));
      const saved = await commands.addSkinPng(bytes, name.trim().slice(0, 64) || "Skin", variant);
      toasts.success(t("skinEditor.saved", { name: saved.name }));
      onsaved?.(saved.id);
      open = false;
    } catch (e) {
      toasts.error(errorMessage(e));
    } finally {
      saving = false;
    }
  }
</script>

<svelte:window onkeydown={keys} />

<Modal bind:open title={t("skinEditor.title")} width="64rem">
  <div class="editor">
    <div class="col gap-2">
      <div class="row gap-1 wrap toolbar">
        {#each TOOLS as tl, i (tl.id)}
          <button
            class="icon-btn"
            aria-pressed={tool === tl.id}
            title="{t(`skinEditor.tools.${tl.id}`)} ({i + 1})"
            aria-label={t(`skinEditor.tools.${tl.id}`)}
            onclick={() => (tool = tl.id)}><Icon name={tl.icon} size={17} /></button
          >
        {/each}
        <span class="sep"></span>
        <input class="color" type="color" bind:value={color} aria-label={t("skinEditor.color")} />
        {#each recent as c (c)}
          <button class="recent" style:background={c} aria-label={c} title={c} onclick={() => (color = c)}
          ></button>
        {/each}
        <span class="grow"></span>
        {#key history}
          <button
            class="icon-btn"
            disabled={!undo.length}
            title="{t('skinEditor.undo')} (Ctrl+Z)"
            aria-label={t("skinEditor.undo")}
            onclick={() => step(undo, redo)}><Icon name="undo" size={17} /></button
          >
          <button
            class="icon-btn"
            disabled={!redo.length}
            title="{t('skinEditor.redo')} (Ctrl+Y)"
            aria-label={t("skinEditor.redo")}
            onclick={() => step(redo, undo)}><Icon name="redo" size={17} /></button
          >
        {/key}
      </div>
      <div class="paper" class:grid style:--z="{ZOOM}px" style:--size="{SIZE * ZOOM}px">
        <canvas
          bind:this={canvas}
          width={SIZE}
          height={SIZE}
          class:busy={loading}
          data-tool={tool}
          onpointerdown={down}
          onpointermove={move}
          onpointerup={up}
          onpointercancel={up}
        ></canvas>
        {#if guides}
          <svg viewBox="0 0 {SIZE} {SIZE}" aria-hidden="true">
            {#each boxes as [x, y, w, h], i (i)}
              <rect {x} {y} width={w} height={h} />
            {/each}
          </svg>
        {/if}
      </div>
      <div class="row gap-3 small">
        <label class="row gap-1"><input type="checkbox" bind:checked={grid} />{t("skinEditor.grid")}</label>
        <label class="row gap-1"
          ><input type="checkbox" bind:checked={guides} />{t("skinEditor.guides")}</label
        >
      </div>
    </div>
    <div class="col gap-3 side">
      <div class="viewer">
        <SkinViewer skinUrl={preview} slim={variant === "slim"} scale={6} follow={false} />
      </div>
      <label class="col gap-1">
        <span class="label">{t("skinEditor.name")}</span>
        <input class="input" bind:value={name} maxlength="64" />
      </label>
      <div class="segmented" role="group" aria-label={t("skinEditor.arms")}>
        <button aria-pressed={variant === "classic"} onclick={() => (variant = "classic")}
          >{t("skins.classic")}</button
        >
        <button aria-pressed={variant === "slim"} onclick={() => (variant = "slim")}>{t("skins.slim")}</button
        >
      </div>
      <p class="tiny faint">{t("skinEditor.hint")}</p>
    </div>
  </div>
  {#snippet footer()}
    <span class="grow"></span>
    <button class="btn ghost" onclick={() => (open = false)}>{t("common.cancel")}</button>
    <button class="btn primary" disabled={saving || loading} onclick={save}
      ><Icon name="check" size={15} />{t("skinEditor.save")}</button
    >
  {/snippet}
</Modal>

<style>
  .editor {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr);
    gap: var(--sp-5);
    align-items: start;
  }
  .toolbar {
    align-items: center;
  }
  .toolbar .icon-btn[aria-pressed="true"] {
    background: var(--c-accent-soft);
    color: var(--c-accent);
  }
  .sep {
    width: 1px;
    height: 1.4rem;
    background: var(--c-border);
    margin: 0 var(--sp-1);
  }
  .color {
    width: 2rem;
    height: 2rem;
    padding: 0;
    border: 1px solid var(--c-border);
    border-radius: var(--r-sm);
    background: none;
    cursor: pointer;
  }
  .recent {
    width: 1.1rem;
    height: 1.1rem;
    border: 1px solid var(--c-border);
    border-radius: 4px;
    cursor: pointer;
  }
  .paper {
    position: relative;
    width: var(--size);
    height: var(--size);
    border: 1px solid var(--c-border);
    border-radius: var(--r-sm);
    /* A checkerboard shows which pixels are see-through. */
    background:
      repeating-conic-gradient(color-mix(in srgb, var(--c-text) 10%, transparent) 0% 25%, transparent 0% 50%)
        0 0 / calc(var(--z) * 2) calc(var(--z) * 2),
      var(--c-surface-2);
  }
  .paper canvas {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    image-rendering: pixelated;
    cursor: crosshair;
    touch-action: none;
  }
  .paper canvas.busy {
    opacity: 0.4;
  }
  .paper.grid::after {
    content: "";
    position: absolute;
    inset: 0;
    pointer-events: none;
    background-image:
      linear-gradient(to right, color-mix(in srgb, var(--c-text) 9%, transparent) 1px, transparent 1px),
      linear-gradient(to bottom, color-mix(in srgb, var(--c-text) 9%, transparent) 1px, transparent 1px);
    background-size: var(--z) var(--z);
  }
  .paper svg {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    pointer-events: none;
  }
  .paper rect {
    fill: none;
    stroke: var(--c-accent);
    stroke-opacity: 0.55;
    stroke-width: 0.12;
  }
  .side {
    min-width: 14rem;
  }
  .side .segmented {
    display: flex;
  }
  .side .segmented button {
    flex: 1;
    white-space: normal;
  }
  .viewer {
    display: grid;
    place-items: center;
    min-height: 16rem;
    border-radius: var(--r-md);
    background: color-mix(in srgb, var(--c-text) 4%, transparent);
  }
  @media (max-width: 900px) {
    .editor {
      grid-template-columns: 1fr;
    }
  }
</style>
