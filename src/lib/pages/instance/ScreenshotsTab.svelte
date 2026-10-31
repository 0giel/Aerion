<script lang="ts">
  // Screenshot gallery with a full-size viewer.
  import Icon from "../../components/Icon.svelte";
  import Modal from "../../components/Modal.svelte";
  import { dialogs } from "../../stores/dialogs.svelte";
  import { toasts } from "../../stores/toasts.svelte";
  import { commands, errorMessage, screenshotUrl, type Instance } from "../../api";
  import type { Screenshot } from "../../bindings";
  import { formatRelative, t } from "../../i18n/index.svelte";

  let { instance }: { instance: Instance } = $props();
  let shots = $state.raw<Screenshot[] | null>(null);
  let open = $state<number | null>(null);
  const current = $derived(open === null ? null : (shots?.[open] ?? null));

  async function load() {
    shots = await commands.listScreenshots(instance.id).catch(() => []);
  }
  $effect(() => {
    void instance.id;
    void load();
  });

  async function remove(s: Screenshot) {
    const ok = await dialogs.confirm({
      title: t("instance.screenshots.deleteTitle"),
      text: s.name,
      confirmLabel: t("common.delete"),
      danger: true,
    });
    if (!ok) return;
    try {
      await commands.deleteScreenshot(instance.id, s.name);
      open = null;
      await load();
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
  }
</script>

<svelte:window {onkeydown} />

<div class="col gap-3">
  <div class="row">
    <span class="hint grow">{shots ? t("instance.screenshots.count", { n: shots.length }) : ""}</span>
    <button class="btn sm ghost" onclick={() => commands.openFolder({ kind: "screenshots", id: instance.id })}
      ><Icon name="folder" size={14} />{t("common.openFolder")}</button
    >
  </div>
  {#if shots === null}
    <div class="empty"><span class="spinner"></span></div>
  {:else if !shots.length}
    <div class="empty card">
      <Icon name="image" size={32} />
      <p class="muted">{t("instance.screenshots.empty")}</p>
    </div>
  {:else}
    <div class="gallery">
      {#each shots as s, i (s.name)}
        <button class="shot" onclick={() => (open = i)} title={s.name}>
          <img src={screenshotUrl(instance.id, s.name)} alt={s.name} loading="lazy" />
          <span class="tiny when">{formatRelative(s.taken)}</span>
        </button>
      {/each}
    </div>
  {/if}
</div>

<Modal
  open={current !== null}
  onclose={() => (open = null)}
  title={current?.name ?? ""}
  width="min(92vw, 70rem)"
>
  {#if current}
    <img class="full" src={screenshotUrl(instance.id, current.name)} alt={current.name} />
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
      <button class="btn danger" onclick={() => remove(current)}
        ><Icon name="trash" size={15} />{t("common.delete")}</button
      >
      <button class="btn" onclick={() => commands.openScreenshot(instance.id, current.name)}
        ><Icon name="external" size={15} />{t("instance.screenshots.openViewer")}</button
      >
    {/if}
  {/snippet}
</Modal>

<style>
  .gallery {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(13rem, 1fr));
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
  }
  .when {
    position: absolute;
    left: 6px;
    bottom: 6px;
    padding: 1px 6px;
    border-radius: var(--r-sm);
    background: rgb(0 0 0 / 0.55);
    color: #fff;
  }
  .full {
    display: block;
    width: 100%;
    max-height: 70vh;
    object-fit: contain;
    border-radius: var(--r-md);
  }
  .empty {
    display: grid;
    justify-items: center;
    gap: var(--sp-2);
    padding: var(--sp-5);
    color: var(--c-text-faint);
  }
</style>
