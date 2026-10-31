<script lang="ts">
  import { onDestroy } from "svelte";
  import { open as openDialog, save as saveDialog } from "@tauri-apps/plugin-dialog";
  import Icon from "../components/Icon.svelte";
  import Switch from "../components/Switch.svelte";
  import ProgressBar from "../components/ProgressBar.svelte";
  import { app } from "../stores/app.svelte";
  import { router } from "../stores/router.svelte";
  import { dialogs } from "../stores/dialogs.svelte";
  import { toasts } from "../stores/toasts.svelte";
  import { themes } from "../theme/engine.svelte";
  import { COLOR_KEYS, FONT_STACKS, type Particles, type Theme } from "../theme/schema";
  import { commands, errorMessage } from "../api";
  import { t } from "../i18n/index.svelte";

  const base = themes.current;
  const builtin = themes.isBuiltin(base.id);
  const slug = (s: string) =>
    s
      .toLowerCase()
      .normalize("NFD")
      .replace(/[̀-ͯ]/g, "")
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-|-$/g, "")
      .slice(0, 40) || "theme";

  function uniqueId(name: string) {
    const stem = slug(name);
    let id = stem;
    for (let n = 2; themes.find(id); n++) id = `${stem}-${n}`;
    return id;
  }

  let draft = $state<Theme>(
    builtin
      ? {
          ...structuredClone($state.snapshot(base)),
          name: `${base.name} ✦`,
          id: uniqueId(`${base.name}-custom`),
          author: undefined,
        }
      : structuredClone($state.snapshot(base)),
  );
  let css = $state(themes.user.find((u) => u.theme.id === base.id)?.css ?? "");

  $effect(() => {
    themes.setPreview($state.snapshot(draft) as Theme, css);
  });
  onDestroy(() => themes.setPreview(null));

  function reset() {
    draft = structuredClone($state.snapshot(base));
    css = themes.user.find((u) => u.theme.id === base.id)?.css ?? "";
  }

  function json() {
    return JSON.stringify({ $schema: "./theme.schema.json", ...$state.snapshot(draft) }, null, 2);
  }

  async function save() {
    if (builtin && draft.id === base.id) draft.id = uniqueId(draft.name);
    try {
      await commands.saveUserTheme({ id: draft.id, json: json(), css });
      await themes.loadUser();
      await app.saveSettings({ theme: draft.id, themeFollowSystem: false });
      themes.setPreview(null);
      toasts.success(t("themeEditor.savedAs", { name: draft.name }));
      router.go("settings");
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  }

  async function exportTheme() {
    const path = await saveDialog({
      defaultPath: `${draft.id}.json`,
      filters: [{ name: "Theme", extensions: ["json"] }],
    });
    if (!path) return;
    await commands
      .exportTheme({ id: draft.id, json: json(), css }, path)
      .catch((e) => toasts.error(errorMessage(e)));
  }

  async function remove() {
    if (
      !(await dialogs.confirm({
        title: t("themeEditor.deleteTheme"),
        text: draft.name,
        danger: true,
        confirmLabel: t("common.delete"),
      }))
    )
      return;
    await commands.deleteUserTheme(draft.id).catch((e) => toasts.error(errorMessage(e)));
    await themes.loadUser();
    await app.saveSettings({ theme: "nocturne" });
    themes.setPreview(null);
    router.go("settings");
  }

  async function pickBackground() {
    const f = await openDialog({
      multiple: false,
      filters: [
        { name: "Image / video", extensions: ["png", "jpg", "jpeg", "webp", "gif", "avif", "mp4", "webm"] },
      ],
    });
    if (typeof f !== "string") return;
    try {
      const name = await commands.addThemeAsset(f);
      draft.background.asset = name;
      draft.background.type = /\.(mp4|webm)$/i.test(name) ? "video" : "image";
      if (draft.background.dim < 0.2) draft.background.dim = 0.35;
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  }

  const hex = (v: string) => (/^#[0-9a-f]{6}/i.test(v) ? v.slice(0, 7) : "#000000");
  function setColor(key: keyof Theme["colors"], v: string, fromPicker: boolean) {
    const prev = draft.colors[key];
    draft.colors[key] = fromPicker && /^#[0-9a-f]{8}$/i.test(prev) ? v + prev.slice(7) : v;
  }
  const fonts = Object.keys(FONT_STACKS).filter((f) => f !== "system-mono");
  const particles: Particles[] = ["none", "sparkles", "dust", "snow"];
</script>

<div class="page editor">
  <header class="page-header">
    <button class="icon-btn" onclick={() => router.back()} aria-label={t("common.back")}
      ><Icon name="arrowLeft" /></button
    >
    <h1 class="grow">{t("themeEditor.title")}</h1>
    {#if builtin}<span class="small muted">{t("themeEditor.basedOn", { name: base.name })}</span>{/if}
    <button class="btn ghost" onclick={reset}><Icon name="undo" size={16} />{t("themeEditor.reset")}</button>
    <button class="btn" onclick={exportTheme}
      ><Icon name="upload" size={16} />{t("themeEditor.exportFile")}</button
    >
    {#if !builtin}<button class="btn danger" onclick={remove}><Icon name="trash" size={16} /></button>{/if}
    <button class="btn primary" onclick={save}
      ><Icon name="check" size={16} />{t("themeEditor.saveAs")}</button
    >
  </header>

  <div class="layout">
    <div class="controls col gap-4">
      <section class="card col gap-3">
        <div class="field">
          <label for="th-name">{t("themeEditor.name")}</label><input
            id="th-name"
            class="input"
            bind:value={draft.name}
            maxlength="64"
          />
        </div>
        <div class="segmented" role="group">
          <button aria-pressed={draft.base === "dark"} onclick={() => (draft.base = "dark")}
            ><Icon name="moon" size={14} /> Dark</button
          >
          <button aria-pressed={draft.base === "light"} onclick={() => (draft.base = "light")}
            ><Icon name="sun" size={14} /> Light</button
          >
        </div>
      </section>

      <section class="card col gap-2">
        <h3>{t("themeEditor.colors")}</h3>
        <div class="colors">
          {#each COLOR_KEYS as key (key)}
            <label class="color">
              <input
                type="color"
                value={hex(draft.colors[key])}
                oninput={(e) => setColor(key, (e.target as HTMLInputElement).value, true)}
                aria-label={t(`themeEditor.color.${key}`)}
              />
              <span class="col" style="gap: 0.1rem; min-width: 0">
                <span class="small">{t(`themeEditor.color.${key}`)}</span>
                <input
                  class="mini mono"
                  value={draft.colors[key]}
                  onchange={(e) => setColor(key, (e.target as HTMLInputElement).value, false)}
                  spellcheck="false"
                />
              </span>
            </label>
          {/each}
        </div>
      </section>

      <section class="card col gap-3">
        <h3>{t("themeEditor.shape")}</h3>
        <div class="field">
          <label for="rad">{t("themeEditor.radius")}: {draft.radius.md}px</label>
          <input
            id="rad"
            type="range"
            min="0"
            max="24"
            value={draft.radius.md}
            oninput={(e) => {
              const md = Number((e.target as HTMLInputElement).value);
              draft.radius = {
                sm: Math.round(md * 0.6),
                md,
                lg: Math.round(md * 1.6),
                xl: Math.round(md * 2.2),
              };
            }}
          />
        </div>
        <div class="row wrap gap-3">
          <div class="field">
            <span class="label">{t("themeEditor.density")}</span>
            <div class="segmented" role="group">
              <button
                aria-pressed={draft.density === "comfortable"}
                onclick={() => (draft.density = "comfortable")}>{t("themeEditor.comfortable")}</button
              >
              <button aria-pressed={draft.density === "compact"} onclick={() => (draft.density = "compact")}
                >{t("themeEditor.compact")}</button
              >
            </div>
          </div>
          <div class="field">
            <span class="label">{t("themeEditor.sidebar")}</span>
            <div class="segmented" role="group">
              <button
                aria-pressed={draft.layout.sidebar === "left"}
                onclick={() => (draft.layout.sidebar = "left")}>{t("themeEditor.sidebarLeft")}</button
              >
              <button
                aria-pressed={draft.layout.sidebar === "top"}
                onclick={() => (draft.layout.sidebar = "top")}>{t("themeEditor.sidebarTop")}</button
              >
            </div>
          </div>
        </div>
        <div class="field">
          <label for="scale">{t("themeEditor.scale")}: {Math.round(draft.layout.scale * 100)}%</label><input
            id="scale"
            type="range"
            min="0.8"
            max="1.3"
            step="0.05"
            bind:value={draft.layout.scale}
          />
        </div>
        <div class="row gap-3">
          <div class="field grow">
            <label for="font">{t("themeEditor.font")}</label>
            <select id="font" class="select" bind:value={draft.font.family}>
              {#each fonts as f (f)}<option value={f}
                  >{f === "system" ? t("themeEditor.fontSystem") : f}</option
                >{/each}
              {#if !fonts.includes(draft.font.family)}<option value={draft.font.family}
                  >{draft.font.family}</option
                >{/if}
            </select>
          </div>
          <div class="field grow">
            <label for="fs">{t("themeEditor.fontSize")}: {draft.font.size}px</label><input
              id="fs"
              type="range"
              min="12"
              max="18"
              step="0.5"
              bind:value={draft.font.size}
            />
          </div>
        </div>
      </section>

      <section class="card col gap-3">
        <h3>{t("themeEditor.effects")}</h3>
        <div class="field">
          <label for="blur">{t("themeEditor.blur")}: {draft.effects.blur}px</label><input
            id="blur"
            type="range"
            min="0"
            max="40"
            bind:value={draft.effects.blur}
          />
        </div>
        <div class="field">
          <label for="glass">{t("themeEditor.glass")}: {Math.round(draft.effects.glass * 100)}%</label><input
            id="glass"
            type="range"
            min="0.2"
            max="1"
            step="0.02"
            bind:value={draft.effects.glass}
          />
        </div>
        <div class="field">
          <label for="shadow">{t("themeEditor.shadow")}: {Math.round(draft.effects.shadow * 100)}%</label
          ><input id="shadow" type="range" min="0" max="1" step="0.05" bind:value={draft.effects.shadow} />
        </div>
        <Switch label={t("themeEditor.animations")} bind:checked={draft.motion.enabled} />
        {#if themes.reducedMotion}<span class="hint">{t("themeEditor.reducedMotion")}</span>{/if}
        <div class="field">
          <label for="speed">{t("themeEditor.animationSpeed")}: {draft.motion.speed}×</label><input
            id="speed"
            type="range"
            min="0.5"
            max="2"
            step="0.25"
            bind:value={draft.motion.speed}
          />
        </div>
      </section>

      <section class="card col gap-3">
        <h3>{t("themeEditor.background")}</h3>
        <div class="segmented" role="group">
          {#each ["color", "gradient", "image", "video"] as bt (bt)}
            <button
              aria-pressed={draft.background.type === bt}
              onclick={() => (draft.background.type = bt as Theme["background"]["type"])}
              >{t(`themeEditor.bgTypes.${bt}`)}</button
            >
          {/each}
        </div>
        {#if draft.background.type === "color"}
          <label class="color"
            ><input
              type="color"
              value={hex(draft.background.color)}
              oninput={(e) => (draft.background.color = (e.target as HTMLInputElement).value)}
            /><span class="mono small">{draft.background.color}</span></label
          >
        {:else if draft.background.type === "gradient"}
          <div class="field">
            <label for="grad">{t("themeEditor.bgGradient")}</label><textarea
              id="grad"
              class="textarea mono"
              rows="3"
              value={draft.background.gradient ?? ""}
              onchange={(e) => (draft.background.gradient = (e.target as HTMLTextAreaElement).value)}
              spellcheck="false"></textarea>
          </div>
        {:else}
          <div class="row">
            <button class="btn" onclick={pickBackground}
              ><Icon name="image" size={16} />{t("themeEditor.bgPick")}</button
            ><span class="small faint mono truncate">{draft.background.asset ?? ""}</span>
          </div>
        {/if}
        <div class="field">
          <label for="dim">{t("themeEditor.bgDim")}: {Math.round(draft.background.dim * 100)}%</label><input
            id="dim"
            type="range"
            min="0"
            max="0.9"
            step="0.05"
            bind:value={draft.background.dim}
          />
        </div>
        <div class="field">
          <label for="bblur">{t("themeEditor.bgBlur")}: {draft.background.blur}px</label><input
            id="bblur"
            type="range"
            min="0"
            max="30"
            bind:value={draft.background.blur}
          />
        </div>
        <Switch label={t("themeEditor.ribbons")} bind:checked={draft.background.ribbons} />
        <div class="field">
          <span class="label">{t("themeEditor.particles")}</span>
          <div class="segmented" role="group">
            {#each particles as p (p)}<button
                aria-pressed={draft.background.particles === p}
                onclick={() => (draft.background.particles = p)}>{t(`themeEditor.particleTypes.${p}`)}</button
              >{/each}
          </div>
        </div>
      </section>

      <section class="card col gap-2">
        <h3>{t("themeEditor.customCss")}</h3>
        <span class="hint">{t("themeEditor.customCssHint")}</span>
        <textarea
          class="textarea mono"
          rows="6"
          bind:value={css}
          spellcheck="false"
          placeholder={".nav { border-right: none; }"}></textarea>
      </section>
    </div>

    <aside class="preview card col gap-3">
      <span class="section-title">{t("themeEditor.preview")}</span>
      <h2>{draft.name}</h2>
      <p class="muted">{t("app.tagline")}</p>
      <div class="row wrap">
        <button class="btn primary"><Icon name="play" size={15} />{t("common.play")}</button>
        <button class="btn">{t("common.cancel")}</button>
        <button class="btn ghost">{t("common.more")}</button>
      </div>
      <input class="input" placeholder={t("common.search")} />
      <div class="row wrap">
        <span class="badge accent">Fabric</span><span class="badge success">OK</span><span
          class="badge warning">Beta</span
        ><span class="badge danger">!</span>
      </div>
      <ProgressBar value={0.62} />
      <div class="card flat col gap-1">
        <strong>{t("instances.title")}</strong><span class="small muted"
          >{t("home.lastPlayed", { when: t("time.justNow") })}</span
        >
      </div>
      <div class="alert"><Icon name="info" size={16} /><span class="small">{t("app.disclaimer")}</span></div>
    </aside>
  </div>
</div>

<style>
  .layout {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 20rem;
    gap: var(--sp-4);
    align-items: start;
  }
  .preview {
    position: sticky;
    top: 0;
  }
  .colors {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(12rem, 1fr));
    gap: 0.6rem 1rem;
  }
  .color {
    display: flex;
    align-items: center;
    gap: 0.6rem;
  }
  .mini {
    width: 100%;
    border: 0;
    border-bottom: 1px dashed var(--c-border);
    background: transparent;
    font-size: 0.78rem;
    color: var(--c-text-muted);
    padding: 0.1rem 0;
  }
  .mini:focus {
    outline: none;
    border-bottom-color: var(--c-accent);
  }
  @media (max-width: 1000px) {
    .layout {
      grid-template-columns: 1fr;
    }
    .preview {
      position: static;
    }
  }
</style>
