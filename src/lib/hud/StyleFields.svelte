<script lang="ts">
  // The look of HUD modules: text and background colours, background opacity, border, corners,
  // padding, text shadow and alignment. Colours follow the launcher theme unless set by hand.
  import Switch from "../components/Switch.svelte";
  import type { HudStyle } from "../api";
  import { t } from "../i18n/index.svelte";

  let {
    style,
    onchange,
    presets = false,
  }: { style: HudStyle; onchange: (s: HudStyle) => void; presets?: boolean } = $props();

  /** Whole looks in one click; each replaces every look field. */
  const PRESETS: Record<string, HudStyle> = {
    default: {},
    minimal: { background: "none", border: "none", outline: true, shadow: false, padding: 2 },
    glass: {
      background: "theme",
      opacity: 40,
      gradient: "#000000",
      radius: 4,
      padding: 4,
      border: "#ffffff",
      shadow: true,
    },
    neon: { background: "theme", opacity: 75, border: "accent", radius: 2, bar: "left" },
    retro: { font: "game", background: "#000000", opacity: 60, border: "#ffffff", shadow: true, padding: 3 },
  };
  /** What an unset look value means (the same as in the mod). */
  const BASE: HudStyle = {
    text: "theme",
    background: "theme",
    opacity: 55,
    border: "none",
    borderWidth: 1,
    radius: 0,
    padding: 3,
    shadow: true,
    align: "left",
    font: "smooth",
    outline: false,
    gradient: "none",
    bar: "none",
    chromaWave: false,
  };
  const LOOK = Object.keys(BASE) as (keyof HudStyle)[];
  /** The ready-made look the style exactly is, or null when it was changed by hand. */
  const active = $derived(
    Object.keys(PRESETS).find((name) =>
      LOOK.filter((k) => k !== "align").every(
        (k) => (style[k] ?? BASE[k]) === ({ ...BASE, ...PRESETS[name] }[k] ?? BASE[k]),
      ),
    ) ?? null,
  );
  const applyPreset = (name: string) =>
    onchange({
      text: "theme",
      background: "theme",
      opacity: 55,
      border: "none",
      borderWidth: 1,
      radius: 0,
      padding: 3,
      shadow: true,
      align: "left",
      font: "smooth",
      chromaSpeed: style.chromaSpeed,
      chromaWave: false,
      outline: false,
      gradient: "none",
      bar: "none",
      ...PRESETS[name],
    });

  const set = (patch: Partial<HudStyle>) => onchange({ ...style, ...patch });
  const isHex = (v: string | null | undefined) => !!v && v.startsWith("#");
  const hexOr = (v: string | null | undefined, fallback: string) => (isHex(v) ? (v as string) : fallback);
</script>

<div class="col gap-3">
  {#if presets}
    <div class="field">
      <span class="label">{t("hudEditor.style.presets")}</span>
      <div class="row wrap gap-2">
        {#each Object.keys(PRESETS) as p (p)}
          <button
            class="btn sm"
            class:primary={active === p}
            aria-pressed={active === p}
            onclick={() => applyPreset(active === p && p !== "default" ? "default" : p)}
            >{t(`hudEditor.style.presetNames.${p}`)}</button
          >
        {/each}
        {#if !active}<span class="small muted">{t("hudEditor.style.presetCustom")}</span>{/if}
      </div>
    </div>
  {/if}
  <div class="field">
    <span class="label">{t("hudEditor.style.text")}</span>
    <div class="row wrap gap-2">
      <div class="segmented" role="group" aria-label={t("hudEditor.style.text")}>
        <button aria-pressed={style.text === "theme" || !style.text} onclick={() => set({ text: "theme" })}
          >{t("hudEditor.style.theme")}</button
        >
        <button aria-pressed={style.text === "chroma"} onclick={() => set({ text: "chroma" })}
          >{t("hudEditor.style.chroma")}</button
        >
        <button aria-pressed={isHex(style.text)} onclick={() => set({ text: hexOr(style.text, "#ffffff") })}
          >{t("hudEditor.style.custom")}</button
        >
      </div>
      {#if isHex(style.text)}
        <input
          type="color"
          aria-label={t("hudEditor.style.text")}
          value={style.text}
          oninput={(e) => set({ text: (e.target as HTMLInputElement).value })}
        />
      {/if}
    </div>
  </div>

  <div class="field">
    <span class="label">{t("hudEditor.style.font")}</span>
    <div class="segmented" role="group" aria-label={t("hudEditor.style.font")}>
      {#each ["game", "smooth"] as f (f)}
        <button aria-pressed={(style.font ?? "smooth") === f} onclick={() => set({ font: f })}
          >{t(`hudEditor.style.fonts.${f}`)}</button
        >
      {/each}
    </div>
  </div>

  <div class="field">
    <span class="label">{t("hudEditor.style.background")}</span>
    <div class="row wrap gap-2">
      <div class="segmented" role="group" aria-label={t("hudEditor.style.background")}>
        <button aria-pressed={style.background === "theme"} onclick={() => set({ background: "theme" })}
          >{t("hudEditor.style.theme")}</button
        >
        <button aria-pressed={style.background === "none"} onclick={() => set({ background: "none" })}
          >{t("hudEditor.style.none")}</button
        >
        <button aria-pressed={style.background === "chroma"} onclick={() => set({ background: "chroma" })}
          >{t("hudEditor.style.chroma")}</button
        >
        <button
          aria-pressed={isHex(style.background)}
          onclick={() => set({ background: hexOr(style.background, "#000000") })}
          >{t("hudEditor.style.custom")}</button
        >
      </div>
      {#if isHex(style.background)}
        <input
          type="color"
          aria-label={t("hudEditor.style.background")}
          value={style.background}
          oninput={(e) => set({ background: (e.target as HTMLInputElement).value })}
        />
      {/if}
    </div>
    {#if style.background !== "none"}
      <label class="range"
        ><span class="small muted">{t("hudEditor.style.opacity")}: {style.opacity ?? 55}%</span>
        <input
          type="range"
          min="0"
          max="100"
          value={style.opacity ?? 55}
          oninput={(e) => set({ opacity: Number((e.target as HTMLInputElement).value) })}
        /></label
      >
    {/if}
  </div>

  {#if style.background !== "none"}
    <div class="field">
      <span class="label">{t("hudEditor.style.gradient")}</span>
      <div class="row wrap gap-2">
        <div class="segmented" role="group" aria-label={t("hudEditor.style.gradient")}>
          <button
            aria-pressed={!style.gradient || style.gradient === "none"}
            onclick={() => set({ gradient: "none" })}>{t("hudEditor.style.none")}</button
          >
          <button aria-pressed={style.gradient === "accent"} onclick={() => set({ gradient: "accent" })}
            >{t("hudEditor.style.accent")}</button
          >
          <button
            aria-pressed={isHex(style.gradient)}
            onclick={() => set({ gradient: hexOr(style.gradient, "#000000") })}
            >{t("hudEditor.style.custom")}</button
          >
        </div>
        {#if isHex(style.gradient)}
          <input
            type="color"
            aria-label={t("hudEditor.style.gradient")}
            value={style.gradient}
            oninput={(e) => set({ gradient: (e.target as HTMLInputElement).value })}
          />
        {/if}
      </div>
    </div>
  {/if}

  <div class="field">
    <span class="label">{t("hudEditor.style.border")}</span>
    <div class="row wrap gap-2">
      <div class="segmented" role="group" aria-label={t("hudEditor.style.border")}>
        <button
          aria-pressed={style.border === "none" || !style.border}
          onclick={() => set({ border: "none" })}>{t("hudEditor.style.none")}</button
        >
        <button aria-pressed={style.border === "accent"} onclick={() => set({ border: "accent" })}
          >{t("hudEditor.style.accent")}</button
        >
        <button aria-pressed={style.border === "chroma"} onclick={() => set({ border: "chroma" })}
          >{t("hudEditor.style.chroma")}</button
        >
        <button
          aria-pressed={isHex(style.border)}
          onclick={() => set({ border: hexOr(style.border, "#ffffff") })}
          >{t("hudEditor.style.custom")}</button
        >
      </div>
      {#if isHex(style.border)}
        <input
          type="color"
          aria-label={t("hudEditor.style.border")}
          value={style.border}
          oninput={(e) => set({ border: (e.target as HTMLInputElement).value })}
        />
      {/if}
    </div>
    {#if style.border && style.border !== "none"}
      <label class="range"
        ><span class="small muted">{t("hudEditor.style.borderWidth")}: {style.borderWidth ?? 1}</span>
        <input
          type="range"
          min="1"
          max="4"
          value={style.borderWidth ?? 1}
          oninput={(e) => set({ borderWidth: Number((e.target as HTMLInputElement).value) })}
        /></label
      >
    {/if}
  </div>

  {#if style.text === "chroma" || style.background === "chroma" || style.border === "chroma"}
    <label class="range"
      ><span class="small muted">{t("hudEditor.style.chromaSpeed")}: {style.chromaSpeed ?? 5}</span>
      <input
        type="range"
        min="1"
        max="10"
        value={style.chromaSpeed ?? 5}
        oninput={(e) => set({ chromaSpeed: Number((e.target as HTMLInputElement).value) })}
      /></label
    >
    {#if style.text === "chroma"}
      <Switch
        label={t("hudEditor.style.chromaWave")}
        checked={style.chromaWave ?? false}
        onchange={(v) => set({ chromaWave: v })}
      />
    {/if}
  {/if}

  <div class="grid2">
    <label class="range"
      ><span class="small muted">{t("hudEditor.style.radius")}: {style.radius ?? 0}</span>
      <input
        type="range"
        min="0"
        max="8"
        value={style.radius ?? 0}
        oninput={(e) => set({ radius: Number((e.target as HTMLInputElement).value) })}
      /></label
    >
    <label class="range"
      ><span class="small muted">{t("hudEditor.style.padding")}: {style.padding ?? 3}</span>
      <input
        type="range"
        min="0"
        max="10"
        value={style.padding ?? 3}
        oninput={(e) => set({ padding: Number((e.target as HTMLInputElement).value) })}
      /></label
    >
  </div>

  <div class="field">
    <span class="label">{t("hudEditor.style.align")}</span>
    <div class="segmented" role="group" aria-label={t("hudEditor.style.align")}>
      {#each ["left", "center", "right"] as a (a)}
        <button aria-pressed={(style.align ?? "left") === a} onclick={() => set({ align: a })}
          >{t(`hudEditor.style.aligns.${a}`)}</button
        >
      {/each}
    </div>
  </div>

  <div class="field">
    <span class="label">{t("hudEditor.style.bar")}</span>
    <div class="segmented" role="group" aria-label={t("hudEditor.style.bar")}>
      {#each ["none", "left", "top", "right", "bottom"] as b (b)}
        <button aria-pressed={(style.bar ?? "none") === b} onclick={() => set({ bar: b })}
          >{t(`hudEditor.style.bars.${b}`)}</button
        >
      {/each}
    </div>
  </div>

  <Switch
    label={t("hudEditor.style.shadow")}
    checked={style.shadow ?? true}
    onchange={(v) => set({ shadow: v })}
  />
  <Switch
    label={t("hudEditor.style.outline")}
    checked={style.outline ?? false}
    onchange={(v) => set({ outline: v })}
  />
</div>

<style>
  .range {
    display: flex;
    flex-direction: column;
    gap: 0.2rem;
  }
  .grid2 {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: var(--sp-3);
  }
  input[type="color"] {
    width: 2.4rem;
    height: 1.9rem;
    padding: 0;
    border: 1px solid var(--c-border);
    border-radius: var(--r-sm);
    background: none;
    cursor: pointer;
  }
</style>
