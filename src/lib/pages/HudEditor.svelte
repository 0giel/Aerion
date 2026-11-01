<script lang="ts">
  // HUD modules and game features of the Aerion mod. The preview stands for the game screen:
  // modules are dragged into place there; everything else is set on the right. Changes are saved
  // as they are made and a running game picks them up within a second.
  import Icon from "../components/Icon.svelte";
  import Switch from "../components/Switch.svelte";
  import StyleFields from "../hud/StyleFields.svelte";
  import { FIELDS, MODULE_IDS, PRESETS, sample } from "../hud/modules";
  import { toasts } from "../stores/toasts.svelte";
  import { commands, errorMessage, type HudConfig, type HudModule, type HudStyle } from "../api";
  import { t } from "../i18n/index.svelte";

  let cfg = $state<HudConfig | null>(null);
  let selected = $state<string>("fps");
  let timer: ReturnType<typeof setTimeout> | undefined;

  function load() {
    commands
      .getHudConfig()
      .then((c) => (cfg = c))
      .catch((e) => toasts.error(errorMessage(e)));
  }

  // The in-game editor writes the same file: coming back to the launcher picks up its layout, so
  // a later change here doesn't put modules back where they were.
  $effect(() => {
    load();
    const refresh = () => {
      if (timer === undefined) load();
    };
    window.addEventListener("focus", refresh);
    return () => window.removeEventListener("focus", refresh);
  });

  function changed() {
    clearTimeout(timer);
    const snapshot = $state.snapshot(cfg);
    timer = setTimeout(() => {
      timer = undefined;
      if (snapshot) commands.saveHudConfig(snapshot).catch((e) => toasts.error(errorMessage(e)));
    }, 350);
  }

  const mod = (id: string): HudModule | undefined => cfg?.modules[id];
  const opt = (m: HudModule | undefined, key: string, def: string) => m?.options[key] ?? def;

  function setOption(id: string, key: string, value: string) {
    const m = mod(id);
    if (!m) return;
    m.options = { ...m.options, [key]: value };
    changed();
  }

  /** Effective style of a module: its own values over the shared ones. */
  function effective(m: HudModule): HudStyle {
    const s = cfg?.style ?? {};
    const own = Object.fromEntries(
      Object.entries(m.style ?? {}).filter(([, v]) => v !== null && v !== undefined),
    );
    return { ...s, ...own };
  }

  const hasOwnStyle = (m: HudModule) =>
    Object.values(m.style ?? {}).some((v) => v !== null && v !== undefined);

  // --- preview -----------------------------------------------------------------------------
  let canvas = $state<HTMLDivElement | undefined>();
  let canvasW = $state(0);
  /** Canvas pixels per GUI pixel (a 1280×720 window at GUI scale 3 is 427 GUI pixels wide). */
  const k = $derived(canvasW / 427);

  function startDrag(e: PointerEvent, id: string) {
    const m = mod(id);
    if (!m || !canvas || e.button !== 0) return;
    selected = id;
    const el = e.currentTarget as HTMLElement;
    el.setPointerCapture(e.pointerId);
    const c = canvas.getBoundingClientRect();
    const b = el.getBoundingClientRect();
    const [x0, y0] = [e.clientX, e.clientY];
    const [left0, top0] = [b.left - c.left, b.top - c.top];
    const move = (ev: PointerEvent) => {
      const freeW = Math.max(1, c.width - b.width);
      const freeH = Math.max(1, c.height - b.height);
      m.x = Math.round(Math.min(1, Math.max(0, (left0 + ev.clientX - x0) / freeW)) * 1000);
      m.y = Math.round(Math.min(1, Math.max(0, (top0 + ev.clientY - y0) / freeH)) * 1000);
    };
    const up = () => {
      el.removeEventListener("pointermove", move);
      el.removeEventListener("pointerup", up);
      changed();
    };
    el.addEventListener("pointermove", move);
    el.addEventListener("pointerup", up);
  }

  function nudge(e: KeyboardEvent, id: string) {
    const m = mod(id);
    const d = { ArrowLeft: [-10, 0], ArrowRight: [10, 0], ArrowUp: [0, -10], ArrowDown: [0, 10] }[e.key];
    if (!m || !d) return;
    e.preventDefault();
    m.x = Math.min(1000, Math.max(0, m.x + d[0] * (e.shiftKey ? 5 : 1)));
    m.y = Math.min(1000, Math.max(0, m.y + d[1] * (e.shiftKey ? 5 : 1)));
    changed();
  }

  /** CSS for a module box in the preview, from its effective style. */
  function boxStyle(m: HudModule): string {
    const s = effective(m);
    const kk = k * (m.scale / 100);
    const alpha = (s.opacity ?? 55) / 100;
    const bg =
      s.background === "none"
        ? "transparent"
        : s.background === "theme"
          ? `color-mix(in srgb, var(--c-surface) ${alpha * 100}%, transparent)`
          : `color-mix(in srgb, ${s.background} ${alpha * 100}%, transparent)`;
    const text =
      s.text && s.text.startsWith("#") ? s.text : s.background === "none" ? "#ffffff" : "var(--c-text)";
    const border =
      !s.border || s.border === "none" ? "transparent" : s.border === "accent" ? "var(--c-accent)" : s.border;
    return [
      `left:${m.x / 10}%`,
      `top:${m.y / 10}%`,
      `transform:translate(${-m.x / 10}%, ${-m.y / 10}%)`,
      `--kk:${kk}px`,
      `background:${bg}`,
      `color:${text}`,
      `border:${kk * (s.borderWidth ?? 1)}px solid ${border}`,
      `font-family:${s.font === "smooth" ? "var(--font-ui, Inter, sans-serif)" : "monospace"}`,
      `--box-r:${(s.radius ?? 0) * kk}px`,
      `border-radius:var(--box-r)`,
      `padding:${(s.padding ?? 3) * kk}px`,
      `text-align:${s.align ?? "left"}`,
      `text-shadow:${s.shadow === false ? "none" : `${kk}px ${kk}px 0 rgb(0 0 0 / 0.55)`}`,
    ].join(";");
  }

  function applyPreset(name: string) {
    if (!cfg) return;
    cfg = PRESETS[name](cfg);
    changed();
    toasts.success(t("hudEditor.presetApplied", { name: t(`hudEditor.presets.${name}`) }));
  }
</script>

<div class="page hud">
  <header class="page-header">
    <h1 class="grow">{t("hudEditor.title")}</h1>
  </header>
  <p class="hint">{t("hudEditor.intro")}</p>

  {#if cfg}
    <div class="row wrap gap-2">
      <Switch
        label={t("hudEditor.enabled")}
        checked={cfg.enabled}
        onchange={(v) => cfg && ((cfg.enabled = v), changed())}
      />
      <span class="grow"></span>
      <span class="small muted">{t("hudEditor.presetsLabel")}</span>
      {#each Object.keys(PRESETS) as p (p)}
        <button class="btn sm" onclick={() => applyPreset(p)}>{t(`hudEditor.presets.${p}`)}</button>
      {/each}
    </div>

    <div class="layout">
      <div class="col gap-2">
        <div class="screen" bind:this={canvas} bind:clientWidth={canvasW} class:off={!cfg.enabled}>
          <div class="crosshair"></div>
          <div class="hotbar"></div>
          {#each MODULE_IDS as id (id)}
            {@const m = cfg.modules[id]}
            {#if m?.enabled}
              <button
                class="box"
                class:sel={selected === id}
                style={boxStyle(m)}
                aria-label={t(`hudEditor.modules.${id}`)}
                onpointerdown={(e) => startDrag(e, id)}
                onkeydown={(e) => nudge(e, id)}
                onclick={() => (selected = id)}
              >
                {#if id === "keystrokes"}
                  <span class="keys" style:--key={Number(opt(m, "keySize", "18"))}>
                    <span></span><span class="key">W</span><span></span>
                    <span class="key">A</span><span class="key">S</span><span class="key">D</span>
                    {#if opt(m, "mouse", "1") === "1"}<span class="key wide">LMB</span><span class="key wide"
                        >RMB</span
                      >{/if}
                    {#if opt(m, "space", "1") === "1"}<span class="key space"></span>{/if}
                  </span>
                {:else if id === "armor"}
                  <span class="armor" class:horizontal={opt(m, "layout", "vertical") === "horizontal"}>
                    {#each [100, 86, 72, 95] as d, i (i)}<span class="piece"
                        ><span class="item"></span>{opt(m, "durability", "percent") === "none"
                          ? ""
                          : opt(m, "durability", "percent") === "value"
                            ? d * 5
                            : `${d}%`}</span
                      >{/each}
                  </span>
                {:else if id === "minimap"}
                  <span class="map" style:--size={Number(opt(m, "size", "80"))}></span>
                {:else}
                  {#each sample(id, (key, def) => opt(m, key, def)) as line, i (i)}<span class="line"
                      >{line}</span
                    >{/each}
                {/if}
              </button>
            {/if}
          {/each}
        </div>
        <span class="tiny muted">{t("hudEditor.previewHint")}</span>
      </div>

      <aside class="side col gap-3">
        <section class="card col gap-2">
          <h3>{t("hudEditor.modulesTitle")}</h3>
          <ul class="mods">
            {#each MODULE_IDS as id (id)}
              {@const m = cfg.modules[id]}
              {#if m}
                <li class:sel={selected === id}>
                  <input
                    type="checkbox"
                    aria-label={t(`hudEditor.modules.${id}`)}
                    checked={m.enabled}
                    onchange={(e) => ((m.enabled = (e.target as HTMLInputElement).checked), changed())}
                  />
                  <button class="name" onclick={() => (selected = id)}>{t(`hudEditor.modules.${id}`)}</button>
                </li>
              {/if}
            {/each}
          </ul>
        </section>

        {#if mod(selected)}
          {@const m = mod(selected) as HudModule}
          <section class="card col gap-3">
            <h3>{t(`hudEditor.modules.${selected}`)}</h3>
            <p class="tiny muted">{t(`hudEditor.about.${selected}`)}</p>
            <label class="range"
              ><span class="small muted">{t("hudEditor.scale")}: {m.scale}%</span>
              <input
                type="range"
                min="25"
                max="400"
                step="5"
                value={m.scale}
                oninput={(e) => ((m.scale = Number((e.target as HTMLInputElement).value)), changed())}
              /></label
            >
            {#each FIELDS[selected] ?? [] as f (f.key)}
              {#if f.type === "toggle"}
                <Switch
                  label={t(`hudEditor.options.${selected}.${f.key}`)}
                  checked={opt(m, f.key, f.def) === "1"}
                  onchange={(v) => setOption(selected, f.key, v ? "1" : "0")}
                />
              {:else if f.type === "text"}
                <label class="field"
                  ><span class="label">{t(`hudEditor.options.${selected}.${f.key}`)}</span>
                  <input
                    class="input mono"
                    value={opt(m, f.key, f.def)}
                    maxlength="200"
                    onchange={(e) => setOption(selected, f.key, (e.target as HTMLInputElement).value)}
                  /></label
                >
              {:else if f.type === "select"}
                <div class="field">
                  <span class="label">{t(`hudEditor.options.${selected}.${f.key}`)}</span>
                  <div class="segmented" role="group">
                    {#each f.choices as ch (ch)}
                      <button
                        aria-pressed={opt(m, f.key, f.def) === ch}
                        onclick={() => setOption(selected, f.key, ch)}>{t(`hudEditor.choices.${ch}`)}</button
                      >
                    {/each}
                  </div>
                </div>
              {:else if f.type === "color"}
                <div class="field">
                  <span class="label">{t(`hudEditor.options.${selected}.${f.key}`)}</span>
                  <div class="row wrap gap-2">
                    <div class="segmented" role="group">
                      <button
                        aria-pressed={!opt(m, f.key, f.def).startsWith("#")}
                        onclick={() => setOption(selected, f.key, "accent")}
                        >{t("hudEditor.style.accent")}</button
                      >
                      <button
                        aria-pressed={opt(m, f.key, f.def).startsWith("#")}
                        onclick={() => setOption(selected, f.key, "#ffffff")}
                        >{t("hudEditor.style.custom")}</button
                      >
                    </div>
                    {#if opt(m, f.key, f.def).startsWith("#")}
                      <input
                        type="color"
                        class="pick"
                        aria-label={t(`hudEditor.options.${selected}.${f.key}`)}
                        value={opt(m, f.key, f.def)}
                        oninput={(e) => setOption(selected, f.key, (e.target as HTMLInputElement).value)}
                      />
                    {/if}
                  </div>
                </div>
              {:else}
                <label class="range"
                  ><span class="small muted"
                    >{t(`hudEditor.options.${selected}.${f.key}`)}: {opt(m, f.key, f.def)}</span
                  >
                  <input
                    type="range"
                    min={f.min}
                    max={f.max}
                    value={Number(opt(m, f.key, f.def))}
                    oninput={(e) => setOption(selected, f.key, (e.target as HTMLInputElement).value)}
                  /></label
                >
              {/if}
            {/each}
            <Switch
              label={t("hudEditor.ownStyle")}
              hint={t("hudEditor.ownStyleHint")}
              checked={hasOwnStyle(m)}
              onchange={(v) => ((m.style = v ? { ...(cfg?.style ?? {}) } : {}), changed())}
            />
            {#if hasOwnStyle(m)}
              <StyleFields style={effective(m)} onchange={(s) => ((m.style = s), changed())} />
            {/if}
          </section>
        {/if}

        <section class="card col gap-3">
          <h3>{t("hudEditor.sharedStyle")}</h3>
          <StyleFields style={cfg.style} onchange={(s) => cfg && ((cfg.style = s), changed())} />
        </section>

        <section class="card col gap-3">
          <h3>{t("hudEditor.featuresTitle")}</h3>
          <Switch
            label={t("hudEditor.features.zoom")}
            hint={t("hudEditor.features.zoomHint")}
            checked={cfg.features.zoom.enabled}
            onchange={(v) => cfg && ((cfg.features.zoom.enabled = v), changed())}
          />
          {#if cfg.features.zoom.enabled}
            <label class="range"
              ><span class="small muted"
                >{t("hudEditor.features.zoomFactor")}: {cfg.features.zoom.factor}×</span
              >
              <input
                type="range"
                min="2"
                max="10"
                value={cfg.features.zoom.factor}
                oninput={(e) =>
                  cfg &&
                  ((cfg.features.zoom.factor = Number((e.target as HTMLInputElement).value)), changed())}
              /></label
            >
            <Switch
              label={t("hudEditor.features.zoomSmooth")}
              checked={cfg.features.zoom.smooth}
              onchange={(v) => cfg && ((cfg.features.zoom.smooth = v), changed())}
            />
          {/if}
          <Switch
            label={t("hudEditor.features.toggleSprint")}
            hint={t("hudEditor.features.toggleSprintHint")}
            checked={cfg.features.toggleSprint.enabled}
            onchange={(v) => cfg && ((cfg.features.toggleSprint.enabled = v), changed())}
          />
          <Switch
            label={t("hudEditor.features.toggleSneak")}
            checked={cfg.features.toggleSneak.enabled}
            onchange={(v) => cfg && ((cfg.features.toggleSneak.enabled = v), changed())}
          />
          <div class="warnbox col gap-2">
            <span class="small"><Icon name="alert" size={14} /> {t("hudEditor.features.serverWarning")}</span>
            <Switch
              label={t("hudEditor.features.freelook")}
              hint={t("hudEditor.features.freelookHint")}
              checked={cfg.features.freelook.enabled}
              onchange={(v) => cfg && ((cfg.features.freelook.enabled = v), changed())}
            />
            {#if cfg.features.freelook.enabled}
              <div class="segmented" role="group" aria-label={t("hudEditor.features.freelook")}>
                <button
                  aria-pressed={cfg.features.freelook.hold}
                  onclick={() => cfg && ((cfg.features.freelook.hold = true), changed())}
                  >{t("hudEditor.features.hold")}</button
                >
                <button
                  aria-pressed={!cfg.features.freelook.hold}
                  onclick={() => cfg && ((cfg.features.freelook.hold = false), changed())}
                  >{t("hudEditor.features.toggle")}</button
                >
              </div>
            {/if}
            <Switch
              label={t("hudEditor.features.fullbright")}
              hint={t("hudEditor.features.fullbrightHint")}
              checked={cfg.features.fullbright.enabled}
              onchange={(v) => cfg && ((cfg.features.fullbright.enabled = v), changed())}
            />
            <Switch
              label={t("hudEditor.features.hitboxes")}
              hint={t("hudEditor.features.hitboxesHint")}
              checked={cfg.features.hitboxes.enabled}
              onchange={(v) => cfg && ((cfg.features.hitboxes.enabled = v), changed())}
            />
            <Switch
              label={t("hudEditor.features.overlays")}
              hint={t("hudEditor.features.overlaysHint")}
              checked={cfg.features.overlays.enabled}
              onchange={(v) => cfg && ((cfg.features.overlays.enabled = v), changed())}
            />
          </div>
          <Switch
            label={t("hudEditor.features.sounds")}
            hint={t("hudEditor.features.soundsHint")}
            checked={cfg.features.sounds.enabled}
            onchange={(v) => cfg && ((cfg.features.sounds.enabled = v), changed())}
          />
          <p class="tiny muted">{t("hudEditor.keysHint")}</p>
        </section>
      </aside>
    </div>
  {:else}
    <span class="muted small">{t("common.loading")}</span>
  {/if}
</div>

<style>
  .hud {
    display: flex;
    flex-direction: column;
    gap: var(--sp-3);
    padding-bottom: var(--sp-5);
  }
  .layout {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(17rem, 22rem);
    gap: var(--sp-4);
    align-items: start;
  }
  @media (max-width: 1100px) {
    .layout {
      grid-template-columns: 1fr;
    }
  }
  .screen {
    position: sticky;
    top: 0;
    aspect-ratio: 16 / 9;
    border-radius: var(--r-md);
    overflow: hidden;
    background:
      linear-gradient(to bottom, #78a7ff 0%, #a8c8ff 48%, transparent 48%),
      linear-gradient(to bottom, #5f9a3c 48%, #4b7d2e 70%, #3d5f27 100%);
    border: 1px solid var(--c-border);
    user-select: none;
  }
  .screen.off .box {
    opacity: 0.35;
  }
  .crosshair {
    position: absolute;
    left: 50%;
    top: 50%;
    width: 10px;
    height: 10px;
    transform: translate(-50%, -50%);
    background:
      linear-gradient(#fff, #fff) center / 2px 100% no-repeat,
      linear-gradient(#fff, #fff) center / 100% 2px no-repeat;
    opacity: 0.8;
  }
  .hotbar {
    position: absolute;
    left: 50%;
    bottom: 2%;
    width: 42%;
    height: 7%;
    transform: translateX(-50%);
    background: rgb(0 0 0 / 0.45);
    border: 2px solid rgb(255 255 255 / 0.3);
  }
  .box {
    position: absolute;
    display: flex;
    flex-direction: column;
    font-family: var(--font-mono);
    font-size: calc(var(--kk) * 8);
    line-height: calc(var(--kk) * 10);
    white-space: pre;
    cursor: grab;
    touch-action: none;
  }
  .box:focus-visible,
  .box.sel {
    outline: 2px dashed var(--c-accent);
    outline-offset: 2px;
  }
  .keys {
    display: grid;
    grid-template-columns: repeat(3, calc(var(--kk) * var(--key)));
    gap: calc(var(--kk) * 2);
  }
  .key {
    display: grid;
    place-items: center;
    height: calc(var(--kk) * var(--key));
    background: rgb(0 0 0 / 0.4);
    border-radius: var(--box-r);
  }
  .key.wide {
    grid-column: span 1;
  }
  .key.wide:first-of-type {
    grid-column: 1 / span 2;
  }
  .key.space {
    grid-column: 1 / -1;
    height: calc(var(--kk) * var(--key) / 2);
  }
  .armor {
    display: flex;
    flex-direction: column;
    gap: calc(var(--kk) * 1);
  }
  .armor.horizontal {
    flex-direction: row;
  }
  .piece {
    display: flex;
    align-items: center;
    gap: calc(var(--kk) * 3);
  }
  .item {
    width: calc(var(--kk) * 16);
    height: calc(var(--kk) * 16);
    border-radius: var(--box-r);
    background: linear-gradient(135deg, #9ad7ff, #4a7fae);
  }
  .map {
    width: calc(var(--kk) * var(--size));
    height: calc(var(--kk) * var(--size));
    background:
      radial-gradient(circle at 50% 50%, #fff 0 calc(var(--kk) * 1.5), transparent calc(var(--kk) * 2)),
      radial-gradient(circle at 30% 70%, #3a6fc4 0 18%, transparent 19%),
      repeating-linear-gradient(
        45deg,
        #5c8f3a 0 calc(var(--kk) * 4),
        #6aa043 calc(var(--kk) * 4) calc(var(--kk) * 8)
      );
  }
  .mods {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 0.15rem 0.5rem;
  }
  .mods li {
    display: flex;
    align-items: center;
    gap: 0.4rem;
    border-radius: var(--r-sm);
    padding: 0.1rem 0.25rem;
  }
  .mods li.sel {
    background: var(--c-hover);
  }
  .name {
    border: 0;
    background: none;
    color: inherit;
    text-align: left;
    padding: 0.15rem 0;
    cursor: pointer;
    font-size: 0.875rem;
  }
  .range {
    display: flex;
    flex-direction: column;
    gap: 0.2rem;
  }
  .pick {
    width: 2.4rem;
    height: 1.9rem;
    padding: 0;
    border: 1px solid var(--c-border);
    border-radius: var(--r-sm);
    background: none;
    cursor: pointer;
  }
  .warnbox {
    padding: var(--sp-3);
    border-radius: var(--r-md);
    border: 1px solid color-mix(in srgb, var(--c-warning) 45%, var(--c-border));
  }
  .warnbox > .small :global(.icon) {
    color: var(--c-warning);
  }
</style>
