<script lang="ts">
  import { onMount, type Component } from "svelte";
  import { getCurrentWindow } from "@tauri-apps/api/window";
  import { getCurrentWebview } from "@tauri-apps/api/webview";
  import Background from "./lib/components/Background.svelte";
  import Nav from "./lib/components/Nav.svelte";
  import TaskPanel from "./lib/components/TaskPanel.svelte";
  import Toasts from "./lib/components/Toasts.svelte";
  import Dialogs from "./lib/components/Dialogs.svelte";
  import AccountHelp from "./lib/components/AccountHelp.svelte";
  import UpdateBanner from "./lib/components/UpdateBanner.svelte";
  import CustomizeModal from "./lib/components/CustomizeModal.svelte";
  import WhatsNew from "./lib/components/WhatsNew.svelte";
  import { layout } from "./lib/stores/layout.svelte";
  import { dashboards } from "./lib/stores/dashboards.svelte";
  import CommandPalette from "./lib/components/CommandPalette.svelte";
  import Modal from "./lib/components/Modal.svelte";
  import Icon from "./lib/components/Icon.svelte";
  import Home from "./lib/pages/Home.svelte";
  import { app } from "./lib/stores/app.svelte";
  import { router, type Page } from "./lib/stores/router.svelte";
  import { ui } from "./lib/stores/ui.svelte";
  import { toasts } from "./lib/stores/toasts.svelte";
  import { commands, errorMessage } from "./lib/api";
  import { t } from "./lib/i18n/index.svelte";
  import { openUrl } from "@tauri-apps/plugin-opener";
  import { themes } from "./lib/theme/engine.svelte";
  import { initFx } from "./lib/fx";
  import { toGameTheme } from "./lib/theme/gameTheme";

  // Everything except Home is code-split and loaded on first visit.
  const pages: Partial<Record<Page, () => Promise<{ default: Component<Record<string, never>> }>>> = {
    instances: () => import("./lib/pages/Instances.svelte"),
    instance: () => import("./lib/pages/InstanceDetail.svelte"),
    mods: () => import("./lib/pages/Mods.svelte"),
    accounts: () => import("./lib/pages/Accounts.svelte"),
    skins: () => import("./lib/pages/Skins.svelte"),
    stats: () => import("./lib/pages/Stats.svelte"),
    screenshots: () => import("./lib/pages/Screenshots.svelte"),
    friends: () => import("./lib/pages/Friends.svelte"),
    performance: () => import("./lib/pages/Performance.svelte"),
    dashboard: () => import("./lib/pages/Dashboard.svelte"),
    hud: () => import("./lib/pages/HudEditor.svelte"),
    console: () => import("./lib/pages/Console.svelte"),
    settings: () => import("./lib/pages/Settings.svelte"),
    themeEditor: () => import("./lib/pages/ThemeEditor.svelte"),
    onboarding: () => import("./lib/pages/Onboarding.svelte"),
  };
  const newInstance = () => import("./lib/pages/NewInstanceModal.svelte");

  let dragging = $state(false);

  onMount(() => {
    const stopFx = initFx();
    void (async () => {
      await app.init();
      void commands.frontendLog("info", `UI ready (${app.fatal ? `failed: ${app.fatal}` : "ok"})`);
      await getCurrentWindow()
        .show()
        .catch(() => {});
    })();
    const unlisten = getCurrentWebview().onDragDropEvent((e) => {
      const p = e.payload;
      if (p.type === "enter" || p.type === "over") dragging = true;
      else if (p.type === "leave") dragging = false;
      else if (p.type === "drop") {
        dragging = false;
        void handleDrop(p.paths);
      }
    });
    return () => {
      stopFx();
      void unlisten.then((f) => f());
    };
  });

  async function handleDrop(paths: string[]) {
    if (!paths.length || ui.dropHandler?.(paths)) return;
    for (const path of paths) {
      if (!/\.(mrpack|zip)$/i.test(path)) continue;
      try {
        await commands.importPack(path);
      } catch (e) {
        toasts.error(errorMessage(e));
      }
    }
  }

  // Running games with the Aerion mod follow the launcher theme (they re-read the file).
  let gameThemeTimer: ReturnType<typeof setTimeout> | undefined;
  let gameThemeSent = "";
  // The user's own menu position beats the theme's; springy animations are a root switch for CSS.
  $effect(() => {
    const side = layout.navSide;
    themes.sideOverride = side === "theme" ? null : side;
    themes.apply();
    document.documentElement.dataset.spring =
      layout.springy && themes.active.motion.enabled && !themes.reducedMotion ? "on" : "off";
  });

  $effect(() => {
    if (!app.ready || !app.settings) return;
    const theme = toGameTheme(themes.active, app.settings.gameUi);
    // Only a real change is written: a reload of the same theme mustn't keep postponing it.
    const json = JSON.stringify(theme);
    clearTimeout(gameThemeTimer);
    if (json === gameThemeSent) return;
    gameThemeTimer = setTimeout(() => {
      commands
        .setGameTheme(theme)
        .then(() => (gameThemeSent = json))
        .catch((e) => console.warn("game theme not saved", e));
    }, 300);
  });

  // Ctrl+1…9 follow the sidebar as the user arranged it.
  const order = $derived(layout.nav.filter((i) => i.visible).map((i) => i.page));
  function onkeydown(e: KeyboardEvent) {
    const mod = e.ctrlKey || e.metaKey;
    if (!mod) return;
    if (e.key.toLowerCase() === "k") {
      ui.paletteOpen = !ui.paletteOpen;
      e.preventDefault();
    } else if (e.key.toLowerCase() === "n" && app.ready) {
      ui.newInstanceOpen = true;
      e.preventDefault();
    } else if (/^[1-9]$/.test(e.key) && order[Number(e.key) - 1]) {
      router.go(order[Number(e.key) - 1]);
      e.preventDefault();
    }
  }
</script>

<svelte:window {onkeydown} />

<Background />

<div class="shell">
  {#if router.page !== "onboarding" && app.ready}
    <Nav onpalette={() => (ui.paletteOpen = true)} />
  {/if}
  <main class="content" id="main">
    {#if app.fatal}
      <div class="center fatal">
        <div class="card col" style="max-width: 32rem">
          <h2>{t("errors.title")}</h2>
          <p class="muted selectable">{app.fatal}</p>
          <button class="btn primary" onclick={() => location.reload()}>{t("common.retry")}</button>
        </div>
      </div>
    {:else if !app.ready}
      <div class="center splash"><span class="spinner" aria-label={t("common.loading")}></span></div>
    {:else if router.page === "home" && !dashboards.homeActive}
      <Home />
    {:else if router.page === "home"}
      {#await pages.dashboard?.() then mod}
        {#if mod}<mod.default />{/if}
      {/await}
    {:else}
      {#key router.page + JSON.stringify(router.params)}
        {#await pages[router.page]?.() then mod}
          {#if mod}<mod.default />{/if}
        {/await}
      {/key}
    {/if}
  </main>
</div>

{#if app.ready}
  <TaskPanel />
  {#if ui.newInstanceOpen}
    {#await newInstance() then mod}
      <mod.default bind:open={ui.newInstanceOpen} />
    {/await}
  {/if}
  <CommandPalette bind:open={ui.paletteOpen} onnew={() => (ui.newInstanceOpen = true)} />
  {#if app.manualDownloads.length}
    <Modal
      open={true}
      title={t("instances.manualDownloads")}
      onclose={() => (app.manualDownloads = [])}
      width="40rem"
    >
      <div class="col gap-3">
        <p class="muted">{t("instances.manualDownloadsText")}</p>
        {#each app.manualDownloads as d (d.fileName)}
          <div class="row between card flat">
            <div class="col gap-1 grow">
              <strong class="truncate">{d.name}</strong>
              <span class="tiny faint mono truncate selectable">{d.fileName} → {d.folder}</span>
            </div>
            <button class="btn sm" onclick={() => openUrl(d.url)}
              ><Icon name="external" size={15} />CurseForge</button
            >
          </div>
        {/each}
      </div>
    </Modal>
  {/if}
{/if}
<Toasts />
<Dialogs />
<AccountHelp />
<UpdateBanner />
<CustomizeModal />
<WhatsNew />

{#if dragging}
  <div class="drop" aria-hidden="true">
    <div class="drop-inner"><Icon name="download" size={36} /><span>{t("common.dropHere")}</span></div>
  </div>
{/if}

<style>
  .shell {
    position: relative;
    z-index: 1;
    display: grid;
    grid-template-columns: auto 1fr;
    height: 100vh;
  }
  :global([data-sidebar="right"]) .shell {
    grid-template-columns: 1fr auto;
  }
  :global([data-sidebar="top"]) .shell {
    grid-template-columns: 1fr;
    grid-template-rows: auto 1fr;
  }
  :global([data-sidebar="bottom"]) .shell {
    grid-template-columns: 1fr;
    grid-template-rows: 1fr auto;
  }
  .content {
    /* Always the last track, also when the navigation is hidden (onboarding, splash). */
    grid-column: -2;
    min-width: 0;
    min-height: 0;
    position: relative;
  }
  :global([data-sidebar="right"]) .content {
    grid-column: 1;
  }
  :global([data-sidebar="right"]) .shell > :global(.navslot) {
    grid-column: 2;
    grid-row: 1;
  }
  :global([data-sidebar="top"]) .content {
    grid-column: auto;
    grid-row: -2;
  }
  :global([data-sidebar="bottom"]) .content {
    grid-column: auto;
    grid-row: 1;
  }
  :global([data-sidebar="bottom"]) .shell > :global(.navslot) {
    grid-column: 1;
    grid-row: 2;
  }
  .splash,
  .fatal {
    height: 100%;
  }
  .spinner {
    width: 28px;
    height: 28px;
    border-radius: 50%;
    border: 3px solid var(--c-accent-soft);
    border-top-color: var(--c-accent);
    animation: spin 0.8s linear infinite;
  }
  .drop {
    position: fixed;
    inset: 0;
    z-index: 300;
    display: grid;
    place-items: center;
    background: rgb(0 0 0 / 0.45);
    pointer-events: none;
  }
  .drop-inner {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 0.75rem;
    padding: 2.5rem 3.5rem;
    border: 2px dashed var(--c-accent);
    border-radius: var(--r-xl);
    background: var(--c-surface);
    font-size: 1.1rem;
    font-weight: 600;
  }
</style>
