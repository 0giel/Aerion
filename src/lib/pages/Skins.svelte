<script lang="ts">
  // Skin library + changing the skin and cape of the selected Microsoft account.
  import { open as openDialog } from "@tauri-apps/plugin-dialog";
  import Icon from "../components/Icon.svelte";
  import Modal from "../components/Modal.svelte";
  import SkinViewer from "../components/SkinViewer.svelte";
  import SkinFace from "../components/SkinFace.svelte";
  import { app } from "../stores/app.svelte";
  import { router } from "../stores/router.svelte";
  import { ui } from "../stores/ui.svelte";
  import { dialogs } from "../stores/dialogs.svelte";
  import { toasts } from "../stores/toasts.svelte";
  import {
    commands,
    DEFAULT_SKIN,
    errorMessage,
    imgUrl,
    savedSkinUrl,
    type Cape,
    type SavedSkin,
    type SkinVariant,
  } from "../api";
  import { formatRelative, t } from "../i18n/index.svelte";

  const account = $derived(app.selectedAccount);
  const microsoft = $derived(account?.kind === "microsoft");

  let library = $state.raw<SavedSkin[]>([]);
  /** The library skin shown in the big preview; `null` = the account's current skin. */
  let selected = $state<string | null>(null);
  let busy = $state<string | null>(null);
  let capes = $state.raw<Cape[] | null>(null);
  /** Cape under the pointer, previewed on the model before it is worn. */
  let hoverCape = $state<string | null>(null);

  let editing = $state<SavedSkin | null>(null);
  let editName = $state("");
  let editVariant = $state<SkinVariant>("classic");

  const current = $derived(library.find((s) => s.id === selected) ?? null);
  const previewUrl = $derived(
    editing
      ? savedSkinUrl(editing.id)
      : current
        ? savedSkinUrl(current.id)
        : (account?.skinUrl ?? DEFAULT_SKIN),
  );
  const previewSlim = $derived(
    editing ? editVariant === "slim" : current ? current.variant === "slim" : account?.skinVariant === "slim",
  );
  const activeCape = $derived(account?.capeUrl ?? null);
  const previewCape = $derived(hoverCape === "none" ? null : (hoverCape ?? activeCape));

  async function load() {
    library = await commands.listSkins().catch(() => []);
  }

  $effect(() => {
    void load();
    // PNGs dropped on this page go to the library.
    ui.dropHandler = (paths) => {
      const pngs = paths.filter((p) => /\.png$/i.test(p));
      if (!pngs.length) return false;
      void addFiles(pngs);
      return true;
    };
    return () => (ui.dropHandler = null);
  });

  // Capes belong to the account; forget them when it changes.
  $effect(() => {
    void account?.id;
    capes = null;
  });

  async function run<T>(key: string, fn: () => Promise<T>, success?: string): Promise<T | undefined> {
    busy = key;
    try {
      const out = await fn();
      if (success) toasts.success(success);
      return out;
    } catch (e) {
      toasts.error(errorMessage(e));
    } finally {
      busy = null;
    }
  }

  const stem = (path: string) => (path.split(/[\\/]/).pop() ?? "skin").replace(/\.png$/i, "").slice(0, 64);

  async function addFiles(paths: string[]) {
    let last: SavedSkin | undefined;
    for (const path of paths) {
      last = (await run("add", () => commands.addSkin(path, stem(path) || "Skin", "classic"))) ?? last;
    }
    await load();
    // Let the user name it and pick the arm width with a live preview.
    if (last && paths.length === 1) startEdit(last);
    else if (last) selected = last.id;
  }

  async function pick() {
    const picked = await openDialog({
      multiple: true,
      filters: [{ name: t("skins.pngFiles"), extensions: ["png"] }],
    });
    const paths = Array.isArray(picked) ? picked : typeof picked === "string" ? [picked] : [];
    if (paths.length) await addFiles(paths);
  }

  function startEdit(s: SavedSkin) {
    editing = s;
    editName = s.name;
    editVariant = s.variant;
  }

  async function saveEdit() {
    const s = editing;
    if (!s) return;
    const updated = await run("edit", () =>
      commands.updateSkin(s.id, editName.trim() || s.name, editVariant),
    );
    if (updated) {
      editing = null;
      selected = updated.id;
      await load();
    }
  }

  async function remove(s: SavedSkin) {
    const ok = await dialogs.confirm({
      title: t("skins.deleteTitle", { name: s.name }),
      confirmLabel: t("common.delete"),
      danger: true,
    });
    if (!ok) return;
    await run("delete", () => commands.deleteSkin(s.id));
    if (selected === s.id) selected = null;
    await load();
  }

  async function wear(s: SavedSkin) {
    if (!account) return;
    const acc = await run(`wear:${s.id}`, () => commands.applySkin(account.id, s.id), t("skins.applied"));
    if (acc) {
      await app.refreshAccounts();
      selected = null;
    }
  }

  async function saveCurrent() {
    if (!account) return;
    const name = await dialogs.prompt({ title: t("skins.saveCurrentTitle"), value: account.username });
    if (!name) return;
    await run("saveCurrent", () => commands.saveAccountSkin(account.id, name), t("skins.saved"));
    await load();
  }

  async function reset() {
    if (!account) return;
    const ok = await dialogs.confirm({ title: t("skins.resetTitle"), text: t("skins.resetText") });
    if (!ok) return;
    if (await run("reset", () => commands.resetSkin(account.id), t("skins.applied")))
      await app.refreshAccounts();
  }

  async function loadCapes() {
    if (!account) return;
    capes = (await run("capes", () => commands.accountCapes(account.id))) ?? null;
    await app.refreshAccounts();
  }

  async function chooseCape(id: string | null) {
    if (!account) return;
    if (await run(`cape:${id}`, () => commands.setCape(account.id, id), t("skins.capeChanged")))
      await app.refreshAccounts();
  }

  /** Draws the front of a cape texture (10×16 at 1,1). */
  function capeFront(canvas: HTMLCanvasElement, url: string) {
    const draw = (u: string) => {
      const ctx = canvas.getContext("2d");
      const src = imgUrl(u);
      if (!ctx || !src) return;
      const img = new Image();
      img.onload = () => {
        ctx.imageSmoothingEnabled = false;
        ctx.clearRect(0, 0, 10, 16);
        // Cape textures are 64×32 or multiples of it.
        const k = img.naturalWidth / 64;
        ctx.drawImage(img, k, k, 10 * k, 16 * k, 0, 0, 10, 16);
      };
      img.src = src;
    };
    draw(url);
    return { update: draw };
  }
</script>

<div class="page">
  <header class="page-header">
    <h1 class="grow">{t("skins.title")}</h1>
    <button class="btn primary" onclick={pick} disabled={busy === "add"}
      ><Icon name="plus" size={16} />{t("skins.add")}</button
    >
  </header>

  {#if !account}
    <div class="alert">
      <Icon name="info" size={18} /><span class="small grow">{t("skins.noAccount")}</span>
      <button class="btn sm" onclick={() => router.go("accounts")}>{t("nav.accounts")}</button>
    </div>
  {:else if !microsoft}
    <div class="alert">
      <Icon name="info" size={18} /><span class="small">{t("skins.offlineHint")}</span>
    </div>
  {/if}

  <div class="layout">
    <aside class="preview card">
      <div class="viewer">
        <SkinViewer
          skinUrl={previewUrl}
          capeUrl={previewCape}
          slim={previewSlim}
          scale={8}
          follow={app.settings?.followCursor ?? true}
        />
      </div>
      <div class="col gap-1 center">
        <strong class="truncate">{editing?.name ?? current?.name ?? t("skins.currentSkin")}</strong>
        <span class="tiny faint">{previewSlim ? t("skins.slim") : t("skins.classic")}</span>
      </div>
      {#if current && !editing}
        <div class="row gap-2 wrap center">
          <button
            class="btn primary"
            disabled={!microsoft || !!busy}
            title={microsoft ? "" : t("skins.needsMicrosoft")}
            onclick={() => current && wear(current)}><Icon name="check" size={15} />{t("skins.wear")}</button
          >
          <button class="btn ghost" onclick={() => (selected = null)}>{t("skins.showCurrent")}</button>
        </div>
      {/if}
    </aside>

    <div class="col gap-4 grow main">
      {#if account}
        <section class="card col gap-3">
          <div class="row">
            <SkinFace skinUrl={account.skinUrl ?? DEFAULT_SKIN} size={36} />
            <div class="col gap-1 grow" style="min-width:0">
              <h3 class="truncate">{t("skins.currentOf", { name: account.username })}</h3>
              <span class="tiny faint"
                >{account.skinUrl ? t("skins.customSkin") : t("skins.defaultSkin")} · {account.skinVariant ===
                "slim"
                  ? t("skins.slim")
                  : t("skins.classic")}</span
              >
            </div>
            <button class="btn sm" onclick={() => (selected = null)} aria-pressed={selected === null}
              ><Icon name="eye" size={14} />{t("skins.preview")}</button
            >
          </div>
          {#if microsoft}
            <div class="row gap-2 wrap">
              <button class="btn sm" disabled={!account.skinUrl || !!busy} onclick={saveCurrent}
                ><Icon name="download" size={14} />{t("skins.saveCurrent")}</button
              >
              <button class="btn sm ghost" disabled={!!busy} onclick={reset}
                ><Icon name="undo" size={14} />{t("skins.reset")}</button
              >
            </div>
          {/if}
        </section>

        {#if !microsoft}
          <section class="card col gap-2">
            <h3>{t("skins.capes")}</h3>
            <p class="small muted">{t("skins.capesNeedMicrosoft")}</p>
          </section>
        {:else}
          <section class="card col gap-3">
            <div class="row">
              <h3 class="grow">{t("skins.capes")}</h3>
              {#if capes === null}
                <button class="btn sm" disabled={busy === "capes"} onclick={loadCapes}
                  >{#if busy === "capes"}<span class="spinner"></span>{:else}<Icon
                      name="refresh"
                      size={14}
                    />{/if}{t("skins.loadCapes")}</button
                >
              {/if}
            </div>
            {#if capes !== null}
              {#if !capes.length}
                <p class="small muted">{t("skins.noCapes")}</p>
              {:else}
                <div class="capes" role="radiogroup" aria-label={t("skins.capes")}>
                  <button
                    class="cape"
                    role="radio"
                    aria-checked={!activeCape}
                    disabled={!!busy}
                    onclick={() => chooseCape(null)}
                    onpointerenter={() => (hoverCape = "none")}
                    onpointerleave={() => (hoverCape = null)}
                  >
                    <span class="none"><Icon name="x" size={18} /></span>
                    <span class="tiny">{t("skins.noCape")}</span>
                  </button>
                  {#each capes as c (c.id)}
                    <button
                      class="cape"
                      role="radio"
                      aria-checked={c.state === "ACTIVE"}
                      disabled={!!busy}
                      onclick={() => chooseCape(c.id)}
                      onpointerenter={() => (hoverCape = c.url)}
                      onpointerleave={() => (hoverCape = null)}
                    >
                      <canvas width="10" height="16" use:capeFront={c.url}></canvas>
                      <span class="tiny truncate">{c.alias ?? "—"}</span>
                    </button>
                  {/each}
                </div>
                <span class="tiny faint">{t("skins.capeHoverHint")}</span>
              {/if}
            {/if}
          </section>
        {/if}
      {/if}

      <section class="card col gap-3">
        <div class="row">
          <h3 class="grow">{t("skins.library")}</h3>
          <span class="tiny faint">{t("skins.dropHint")}</span>
        </div>
        {#if !library.length}
          <div class="empty-lib col gap-2">
            <Icon name="user" size={32} />
            <p class="small muted">{t("skins.emptyLibrary")}</p>
            <button class="btn" onclick={pick}><Icon name="plus" size={15} />{t("skins.add")}</button>
          </div>
        {:else}
          <div class="lib">
            {#each library as s (s.id)}
              <div class="skin" class:selected={selected === s.id}>
                <button
                  class="pick"
                  onclick={() => (selected = s.id)}
                  aria-label={t("skins.previewOf", { name: s.name })}
                >
                  <SkinFace skinUrl={savedSkinUrl(s.id)} size={44} />
                  <span class="truncate strong small">{s.name}</span>
                  <span class="tiny faint"
                    >{s.variant === "slim" ? t("skins.slim") : t("skins.classic")} · {formatRelative(
                      s.addedAt,
                    )}</span
                  >
                </button>
                <div class="actions row gap-1">
                  <button
                    class="btn sm primary"
                    disabled={!microsoft || !!busy}
                    title={microsoft ? t("skins.wear") : t("skins.needsMicrosoft")}
                    onclick={() => wear(s)}
                    >{#if busy === `wear:${s.id}`}<span class="spinner"></span>{:else}<Icon
                        name="check"
                        size={14}
                      />{/if}{t("skins.wear")}</button
                  >
                  <button
                    class="icon-btn sm"
                    aria-label={t("skins.edit")}
                    title={t("skins.edit")}
                    onclick={() => startEdit(s)}><Icon name="pencil" size={15} /></button
                  >
                  <button
                    class="icon-btn sm"
                    aria-label={t("common.delete")}
                    title={t("common.delete")}
                    onclick={() => remove(s)}><Icon name="trash" size={15} /></button
                  >
                </div>
              </div>
            {/each}
          </div>
        {/if}
      </section>
    </div>
  </div>
</div>

<Modal open={!!editing} onclose={() => (editing = null)} title={t("skins.edit")} width="26rem">
  <div class="col gap-3">
    <div class="field">
      <label for="skin-name">{t("skins.name")}</label>
      <input id="skin-name" class="input" bind:value={editName} maxlength="64" />
    </div>
    <div class="field">
      <span class="label">{t("skins.model")}</span>
      <div class="segmented" role="group" aria-label={t("skins.model")}>
        <button
          type="button"
          aria-pressed={editVariant === "classic"}
          onclick={() => (editVariant = "classic")}>{t("skins.classic")}</button
        >
        <button type="button" aria-pressed={editVariant === "slim"} onclick={() => (editVariant = "slim")}
          >{t("skins.slim")}</button
        >
      </div>
      <span class="tiny faint">{t("skins.modelHint")}</span>
    </div>
  </div>
  {#snippet footer()}
    <button class="btn ghost" onclick={() => (editing = null)}>{t("common.cancel")}</button>
    <button class="btn primary" disabled={busy === "edit"} onclick={saveEdit}>{t("common.save")}</button>
  {/snippet}
</Modal>

<style>
  .alert {
    margin-bottom: var(--sp-4);
  }
  .layout {
    display: flex;
    gap: var(--sp-4);
    align-items: flex-start;
  }
  .preview {
    position: sticky;
    top: 0;
    flex: none;
    width: 15rem;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--sp-3);
  }
  .viewer {
    display: grid;
    place-items: center;
    min-height: 280px;
  }
  .center {
    align-items: center;
    justify-content: center;
    text-align: center;
    max-width: 100%;
  }
  .main {
    min-width: 0;
  }
  .lib {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(11rem, 1fr));
    gap: var(--sp-2);
  }
  .skin {
    display: flex;
    flex-direction: column;
    gap: var(--sp-2);
    padding: var(--sp-2);
    border: 1px solid var(--c-border);
    border-radius: var(--r-md);
    background: var(--c-surface-2);
    transition: border-color var(--dur) var(--ease);
  }
  .skin.selected {
    border-color: var(--c-accent);
  }
  .pick {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 0.3rem;
    min-width: 0;
    padding: var(--sp-2) 0 0;
    border: 0;
    background: none;
    color: inherit;
    cursor: pointer;
    text-align: center;
  }
  .pick > span {
    max-width: 100%;
  }
  .actions {
    justify-content: center;
  }
  .empty-lib {
    align-items: center;
    padding: var(--sp-4);
    color: var(--c-text-faint);
    text-align: center;
  }
  .capes {
    display: flex;
    flex-wrap: wrap;
    gap: var(--sp-2);
  }
  .cape {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 0.3rem;
    width: 5rem;
    padding: var(--sp-2);
    border: 2px solid var(--c-border);
    border-radius: var(--r-md);
    background: var(--c-surface-2);
    color: inherit;
    cursor: pointer;
  }
  .cape[aria-checked="true"] {
    border-color: var(--c-accent);
  }
  .cape canvas,
  .none {
    width: 30px;
    height: 48px;
    image-rendering: pixelated;
  }
  .none {
    display: grid;
    place-items: center;
    color: var(--c-text-faint);
  }
  .cape .tiny {
    max-width: 100%;
  }
  @media (max-width: 900px) {
    .layout {
      flex-direction: column;
      align-items: stretch;
    }
    .preview {
      position: static;
      width: auto;
    }
  }
</style>
