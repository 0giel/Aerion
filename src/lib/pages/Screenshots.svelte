<script lang="ts">
  // Screenshots of every instance in one gallery: filter by instance, view, share and delete.
  import { save as saveDialog } from "@tauri-apps/plugin-dialog";
  import Icon from "../components/Icon.svelte";
  import Modal from "../components/Modal.svelte";
  import { app } from "../stores/app.svelte";
  import { dialogs } from "../stores/dialogs.svelte";
  import { toasts } from "../stores/toasts.svelte";
  import { router } from "../stores/router.svelte";
  import { commands, errorMessage, screenshotUrl } from "../api";
  import type { GalleryShot } from "../bindings";
  import { formatRelative, t } from "../i18n/index.svelte";

  let all = $state.raw<GalleryShot[] | null>(null);
  let filter = $state("");
  let open = $state<number | null>(null);

  const shots = $derived(all?.filter((s) => !filter || s.instanceId === filter) ?? null);
  const current = $derived(open === null ? null : (shots?.[open] ?? null));
  // Only instances that have screenshots, in the order of their newest shot.
  const owners = $derived.by(() => {
    const seen: Record<string, { id: string; name: string; n: number }> = {};
    for (const s of all ?? []) {
      seen[s.instanceId] ??= { id: s.instanceId, name: s.instanceName, n: 0 };
      seen[s.instanceId].n++;
    }
    return Object.values(seen);
  });
  const totalBytes = $derived(shots?.reduce((a, s) => a + s.sizeBytes, 0) ?? 0);

  async function load() {
    all = await commands.allScreenshots().catch(() => []);
    if (filter && !all.some((s) => s.instanceId === filter)) filter = "";
  }
  $effect(() => {
    void app.instances;
    void load();
  });

  const key = (s: GalleryShot) => `${s.instanceId}/${s.name}`;
  const url = (s: GalleryShot) => screenshotUrl(s.instanceId, s.name);
  const mb = (b: number) => (b / 1024 / 1024).toFixed(b < 10 * 1024 * 1024 ? 1 : 0);

  async function remove(s: GalleryShot) {
    const ok = await dialogs.confirm({
      title: t("instance.screenshots.deleteTitle"),
      text: `${s.name} · ${s.instanceName}`,
      confirmLabel: t("common.delete"),
      danger: true,
    });
    if (!ok) return;
    try {
      await commands.deleteScreenshot(s.instanceId, s.name);
      const at = open;
      await load();
      // Stay in the viewer on the next picture, if any are left.
      open = at !== null && shots?.length ? Math.min(at, shots.length - 1) : null;
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  }

  async function copy(s: GalleryShot) {
    try {
      const blob = await fetch(url(s)).then((r) => r.blob());
      await navigator.clipboard.write([new ClipboardItem({ "image/png": blob })]);
      toasts.success(t("shots.copied"));
    } catch {
      toasts.error(t("shots.copyFailed"));
    }
  }

  async function saveCopy(s: GalleryShot) {
    const dest = await saveDialog({ defaultPath: s.name, filters: [{ name: "PNG", extensions: ["png"] }] });
    if (!dest) return;
    try {
      await commands.saveScreenshotCopy(s.instanceId, s.name, dest);
      toasts.success(t("shots.saved"));
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  }

  function step(d: number) {
    if (open === null || !shots?.length) return;
    open = (open + d + shots.length) % shots.length;
  }
  function onkeydown(e: KeyboardEvent) {
    if (open === null) return;
    if (e.key === "ArrowRight") step(1);
    else if (e.key === "ArrowLeft") step(-1);
    else if (e.key === "Delete" && current) void remove(current);
  }
</script>

<svelte:window {onkeydown} />

<div class="page">
  <header class="page-header">
    <h1>{t("shots.title")}</h1>
    {#if shots?.length}
      <span class="hint">{t("instance.screenshots.count", { n: shots.length })} · {mb(totalBytes)} MB</span>
    {/if}
  </header>

  {#if owners.length > 1}
    <div class="chips" role="tablist">
      <button class="chip" class:on={!filter} onclick={() => (filter = "")}>{t("shots.all")}</button>
      {#each owners as o (o.id)}
        <button class="chip" class:on={filter === o.id} onclick={() => (filter = o.id)}
          >{o.name} <span class="faint">{o.n}</span></button
        >
      {/each}
    </div>
  {/if}

  {#if shots === null}
    <div class="empty"><span class="spinner"></span></div>
  {:else if !shots.length}
    <div class="empty card">
      <Icon name="image" size={32} />
      <p class="muted">{t("shots.empty")}</p>
    </div>
  {:else}
    <div class="gallery">
      {#each shots as s, i (key(s))}
        <button class="shot" onclick={() => (open = i)} title={s.name}>
          <img src={url(s)} alt={s.name} loading="lazy" />
          <span class="tiny tag who">{s.instanceName}</span>
          <span class="tiny tag when">{formatRelative(s.taken)}</span>
        </button>
      {/each}
    </div>
  {/if}
</div>

<Modal
  open={current !== null}
  onclose={() => (open = null)}
  title={current ? `${current.instanceName} · ${formatRelative(current.taken)}` : ""}
  width="min(92vw, 70rem)"
>
  {#if current}
    <img class="full" src={url(current)} alt={current.name} />
    <p class="tiny faint name">{current.name} · {mb(current.sizeBytes)} MB</p>
  {/if}
  {#snippet footer()}
    {#if current}
      <button class="btn ghost" onclick={() => step(-1)} aria-label={t("instance.screenshots.previous")}
        ><Icon name="arrowLeft" size={16} /></button
      >
      <button class="btn ghost" onclick={() => step(1)} aria-label={t("instance.screenshots.next")}
        ><Icon name="arrowRight" size={16} /></button
      >
      <span class="grow"></span>
      <button class="btn danger ghost" onclick={() => remove(current)} title={t("common.delete")}
        ><Icon name="trash" size={15} /></button
      >
      <button
        class="btn ghost"
        onclick={() => {
          open = null;
          router.go("instance", { id: current.instanceId, tab: "screenshots" });
        }}
        title={t("shots.toInstance")}><Icon name="grid" size={15} /></button
      >
      <button
        class="btn ghost"
        onclick={() => commands.revealScreenshot(current.instanceId, current.name)}
        title={t("shots.reveal")}><Icon name="folder" size={15} /></button
      >
      <button class="btn ghost" onclick={() => saveCopy(current)}
        ><Icon name="download" size={15} />{t("shots.saveAs")}</button
      >
      <button class="btn" onclick={() => copy(current)}
        ><Icon name="copy" size={15} />{t("shots.copy")}</button
      >
    {/if}
  {/snippet}
</Modal>

<style>
  .page-header {
    display: flex;
    align-items: baseline;
    gap: var(--sp-3);
  }
  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: var(--sp-1);
    margin-bottom: var(--sp-3);
  }
  .chip {
    padding: 4px 12px;
    border: 1px solid var(--c-border);
    border-radius: 999px;
    background: var(--c-surface-2);
    color: var(--c-text-muted);
    font-size: 0.85rem;
    cursor: pointer;
    transition:
      background var(--dur) var(--ease),
      color var(--dur) var(--ease);
  }
  .chip.on {
    background: var(--c-accent);
    border-color: var(--c-accent);
    color: var(--c-accent-text);
  }
  .chip.on .faint {
    color: inherit;
    opacity: 0.75;
  }
  .gallery {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(14rem, 1fr));
    gap: var(--sp-2);
  }
  .shot {
    position: relative;
    padding: 0;
    border: 1px solid var(--c-border);
    border-radius: var(--r-md);
    overflow: hidden;
    background: var(--c-surface-2);
    aspect-ratio: 16 / 9;
    cursor: zoom-in;
  }
  .shot img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
    transition: transform var(--dur) var(--ease);
  }
  .shot:hover img {
    transform: scale(1.03);
  }
  .tag {
    position: absolute;
    bottom: 6px;
    max-width: 60%;
    padding: 1px 6px;
    border-radius: var(--r-sm);
    background: rgb(0 0 0 / 0.55);
    color: #fff;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .who {
    left: 6px;
  }
  .when {
    right: 6px;
  }
  .full {
    display: block;
    width: 100%;
    max-height: 68vh;
    object-fit: contain;
    border-radius: var(--r-md);
  }
  .name {
    margin: var(--sp-1) 0 0;
  }
  .empty {
    display: grid;
    justify-items: center;
    gap: var(--sp-2);
    padding: var(--sp-5);
    color: var(--c-text-faint);
  }
</style>
