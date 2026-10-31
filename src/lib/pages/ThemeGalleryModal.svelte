<script lang="ts">
  // Ready-made themes shared in the project's repository; one click installs and applies one.
  import { openUrl } from "@tauri-apps/plugin-opener";
  import Modal from "../components/Modal.svelte";
  import Icon from "../components/Icon.svelte";
  import { app } from "../stores/app.svelte";
  import { toasts } from "../stores/toasts.svelte";
  import { themes } from "../theme/engine.svelte";
  import { commands, errorMessage } from "../api";
  import type { GalleryTheme } from "../bindings";
  import { t } from "../i18n/index.svelte";

  const SUBMIT_URL = "https://github.com/0giel/Aerion/issues/new?title=Theme%3A%20";

  let { open = $bindable(false) }: { open?: boolean } = $props();
  let list = $state.raw<GalleryTheme[] | null>(null);
  let failed = $state(false);
  let busy = $state<string | null>(null);

  $effect(() => {
    if (!open) return;
    failed = false;
    commands
      .galleryThemes()
      .then((l) => (list = l))
      .catch(() => (failed = true));
  });

  const installed = (id: string) => themes.all.some((th) => th.id === id);

  async function install(th: GalleryTheme) {
    busy = th.id;
    try {
      const id = await commands.installGalleryTheme(th.id);
      // The themes folder watcher picks the file up; apply once it is there.
      for (let i = 0; i < 20 && !installed(id); i++) await new Promise((r) => setTimeout(r, 100));
      await app.saveSettings({ theme: id, themeFollowSystem: false });
      toasts.success(t("gallery.applied", { name: th.name }));
    } catch (e) {
      toasts.error(errorMessage(e));
    } finally {
      busy = null;
    }
  }
</script>

<Modal bind:open title={t("gallery.title")} width="44rem">
  {#if failed}
    <p class="small muted">{t("gallery.failed")}</p>
  {:else if !list}
    <div class="row small muted"><span class="spinner"></span>{t("common.loading")}</div>
  {:else}
    <div class="grid">
      {#each list as th (th.id)}
        {@const p = th.preview}
        <article class="card flat item">
          <!-- Preview of the theme's own colours (data, not this UI's theme). -->
          <div class="swatch" style:background={p?.bg ?? "#222"}>
            <div class="panel" style:background={p?.surface ?? "#333"}>
              <span class="line" style:background={p?.text ?? "#eee"}></span>
              <span class="line short" style:background={p?.text ?? "#eee"}></span>
              <span class="pill" style:background={p?.accent ?? "#888"}></span>
            </div>
          </div>
          <div class="col gap-1">
            <strong>{th.name}</strong>
            <span class="tiny faint">{th.description || th.author}</span>
          </div>
          <button
            class="btn sm"
            class:primary={!installed(th.id)}
            disabled={!!busy}
            onclick={() => install(th)}
            >{#if busy === th.id}<span class="spinner"></span>{:else}<Icon
                name={installed(th.id) ? "check" : "download"}
                size={14}
              />{/if}{installed(th.id) ? t("gallery.use") : t("gallery.install")}</button
          >
        </article>
      {/each}
    </div>
  {/if}
  {#snippet footer()}
    <button class="btn ghost" onclick={() => openUrl(SUBMIT_URL)}
      ><Icon name="external" size={14} />{t("gallery.submit")}</button
    >
    <span class="grow"></span>
    <button class="btn" onclick={() => (open = false)}>{t("common.close")}</button>
  {/snippet}
</Modal>

<style>
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(12rem, 1fr));
    gap: var(--sp-3);
  }
  .item {
    display: flex;
    flex-direction: column;
    gap: var(--sp-2);
  }
  .swatch {
    height: 5.5rem;
    border-radius: var(--r-md);
    padding: 0.7rem;
    display: flex;
    align-items: flex-end;
  }
  .panel {
    width: 70%;
    border-radius: var(--r-sm);
    padding: 0.45rem;
    display: grid;
    gap: 0.3rem;
  }
  .line {
    height: 5px;
    border-radius: 3px;
    opacity: 0.85;
  }
  .short {
    width: 60%;
    opacity: 0.5;
  }
  .pill {
    width: 45%;
    height: 9px;
    border-radius: 5px;
  }
</style>
