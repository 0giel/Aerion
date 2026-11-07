<script lang="ts">
  import { openUrl } from "@tauri-apps/plugin-opener";
  import { DISCORD_URL, REPO_URL } from "../links";
  import Icon from "./Icon.svelte";
  import SkinFace from "./SkinFace.svelte";
  import { DEFAULT_SKIN } from "../api";
  import Logo from "./Logo.svelte";
  import InstanceIcon from "./InstanceIcon.svelte";
  import { layout } from "../stores/layout.svelte";
  import { dashboards } from "../stores/dashboards.svelte";
  import { ui } from "../stores/ui.svelte";
  import { router, type Page } from "../stores/router.svelte";
  import { app } from "../stores/app.svelte";
  import { t } from "../i18n/index.svelte";

  let { onpalette }: { onpalette: () => void } = $props();

  const items = $derived(layout.nav.filter((i) => i.visible));
  const favorites = $derived(layout.navFavorites ? app.instances.filter((i) => i.favorite).slice(0, 6) : []);

  let collapsed = $state(readCollapsed());
  function readCollapsed() {
    try {
      return localStorage.getItem("nav.collapsed") === "1";
    } catch {
      return false;
    }
  }
  function toggle() {
    collapsed = !collapsed;
    try {
      localStorage.setItem("nav.collapsed", collapsed ? "1" : "0");
    } catch {
      /* storage unavailable */
    }
  }

  const active = (p: Page) =>
    router.page === p ||
    (p === "instances" && router.page === "instance") ||
    (p === "settings" && router.page === "themeEditor");
  const runningCount = $derived(Object.keys(app.running).length);
  const account = $derived(app.selectedAccount);
</script>

<nav class="nav" class:collapsed aria-label="Main">
  <div class="brand">
    <span class="logo"><Logo /></span>
    <span class="name">Aerion</span>
  </div>
  <div class="items">
    {#each items as item (item.page)}
      <button
        class="item"
        aria-current={active(item.page) ? "page" : undefined}
        title={collapsed ? t(item.key) : undefined}
        onclick={() => router.go(item.page)}
      >
        <Icon name={item.icon} size={19} />
        <span class="label">{t(item.key)}</span>
        {#if item.page === "console" && runningCount > 0}<span class="dot" aria-label={t("console.running")}
          ></span>{/if}
      </button>
    {/each}
    {#each dashboards.pages as d (d.id)}
      <button
        class="item"
        aria-current={router.page === "dashboard" && router.params.id === d.id ? "page" : undefined}
        title={collapsed ? d.name : undefined}
        onclick={() => router.go("dashboard", { id: d.id })}
      >
        <Icon name={d.icon} size={19} />
        <span class="label">{d.name}</span>
      </button>
    {/each}
  </div>
  {#if favorites.length}
    <div class="favs" aria-label={t("instances.favorites")}>
      <span class="section label">{t("instances.favorites")}</span>
      {#each favorites as f (f.id)}
        {@const isRunning = !!app.running[f.id]}
        <div class="fav">
          <button
            class="item"
            aria-current={router.page === "instance" && router.params.id === f.id ? "page" : undefined}
            title={f.name}
            onclick={() => router.go("instance", { id: f.id })}
          >
            <InstanceIcon name={f.name} loader={f.loader} icon={f.icon} id={f.id} size={20} />
            <span class="label truncate">{f.name}</span>
            {#if isRunning}<span class="dot"></span>{/if}
          </button>
          <button
            class="icon-btn sm quick"
            aria-label={t(isRunning ? "common.stop" : "common.play") + ": " + f.name}
            title={t(isRunning ? "common.stop" : "common.play")}
            onclick={() => (isRunning ? app.stop(f.id) : app.launch(f.id))}
            ><Icon name={isRunning ? "stop" : "play"} size={13} /></button
          >
        </div>
      {/each}
    </div>
  {/if}
  <div class="spacer"></div>
  <button class="item palette" onclick={onpalette} title={t("palette.placeholder")}>
    <Icon name="command" size={17} />
    <span class="label">{t("common.search")}</span>
    <kbd class="label">Ctrl K</kbd>
  </button>
  <button
    class="account"
    onclick={() => router.go("accounts")}
    title={account?.username ?? t("nav.accounts")}
  >
    <SkinFace skinUrl={account ? (account.skinUrl ?? DEFAULT_SKIN) : null} size={28} />
    <span class="label truncate">{account?.username ?? t("accounts.empty")}</span>
  </button>
  <div class="tools">
    <button
      class="icon-btn collapse"
      onclick={toggle}
      aria-label={collapsed ? t("nav.expand") : t("nav.collapse")}
      title={collapsed ? t("nav.expand") : t("nav.collapse")}
    >
      <Icon name="panelLeft" size={17} />
    </button>
    <button
      class="icon-btn github"
      onclick={() => openUrl(REPO_URL)}
      aria-label={t("nav.github")}
      title={t("nav.github")}><Icon name="github" size={16} /></button
    >
    <button
      class="icon-btn discord"
      onclick={() => openUrl(DISCORD_URL)}
      aria-label={t("nav.discord")}
      title={t("nav.discord")}><Icon name="chat" size={16} /></button
    >
    <button
      class="icon-btn customize"
      onclick={() => (ui.customize = "nav")}
      aria-label={t("layout.navTitle")}
      title={t("layout.navTitle")}><Icon name="sliders" size={16} /></button
    >
  </div>
</nav>

<style>
  .nav {
    position: relative;
    z-index: 2;
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
    width: 13.5rem;
    padding: var(--sp-4) var(--sp-3) var(--sp-3);
    background: color-mix(in srgb, var(--c-surface) calc(var(--glass) * 70%), transparent);
    -webkit-backdrop-filter: blur(var(--blur));
    backdrop-filter: blur(var(--blur));
    border-right: 1px solid var(--c-border);
    transition: width var(--dur) var(--ease);
  }
  .nav.collapsed {
    width: 4.25rem;
  }
  .brand {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    padding: 0 0.45rem 0.9rem;
    font-weight: 750;
    font-size: 1.12rem;
    letter-spacing: -0.02em;
  }
  .logo {
    display: grid;
    width: 1.75rem;
    height: 1.75rem;
    flex: none;
    color: var(--c-accent);
  }
  .logo :global(svg) {
    width: 100%;
    height: 100%;
  }
  .items {
    display: flex;
    flex-direction: column;
    gap: 0.15rem;
  }
  .item,
  .account {
    position: relative;
    display: flex;
    align-items: center;
    gap: 0.75rem;
    height: 2.5rem;
    padding: 0 0.75rem;
    border: 0;
    border-radius: var(--r-md);
    background: transparent;
    color: var(--c-text-muted);
    font-weight: 560;
    cursor: pointer;
    text-align: left;
    white-space: nowrap;
    transition:
      background var(--dur) var(--ease),
      color var(--dur) var(--ease);
  }
  .item:hover,
  .account:hover {
    background: var(--c-hover);
    color: var(--c-text);
  }
  .item[aria-current="page"] {
    background: var(--c-accent-soft);
    color: var(--c-text);
  }
  .item[aria-current="page"]::before {
    content: "";
    position: absolute;
    left: -0.75rem;
    top: 25%;
    bottom: 25%;
    width: 3px;
    border-radius: 3px;
    background: var(--c-accent);
  }
  .item[aria-current="page"] :global(.icon) {
    color: var(--c-accent);
  }
  .palette kbd {
    margin-left: auto;
  }
  .dot {
    position: absolute;
    left: 1.75rem;
    top: 0.55rem;
    width: 0.5rem;
    height: 0.5rem;
    border-radius: 50%;
    background: var(--c-success);
    box-shadow: 0 0 0 3px color-mix(in srgb, var(--c-success) 25%, transparent);
  }
  .spacer {
    flex: 1;
  }
  .account {
    height: 2.9rem;
    padding: 0 0.5rem;
    gap: 0.6rem;
    color: var(--c-text);
  }
  .tools {
    display: flex;
    justify-content: space-between;
  }
  /* GitHub sits with the customise button on the right. */
  .tools .github {
    margin-left: auto;
  }
  .favs {
    display: flex;
    flex-direction: column;
    gap: 0.15rem;
    margin-top: var(--sp-3);
    min-height: 0;
    overflow-y: auto;
  }
  .section {
    padding: 0 0.6rem 0.2rem;
    font-size: 0.72rem;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--c-text-faint);
  }
  .fav {
    position: relative;
    display: flex;
    align-items: center;
  }
  .fav .item {
    flex: 1;
    min-width: 0;
    height: 2.3rem;
    font-size: 0.88rem;
  }
  .fav .quick {
    position: absolute;
    right: 0.25rem;
    opacity: 0;
    transition: opacity var(--dur) var(--ease);
  }
  .fav:hover .quick,
  .fav .quick:focus-visible {
    opacity: 1;
  }
  .collapsed .label,
  .collapsed .name {
    display: none;
  }
  .collapsed .item,
  .collapsed .account {
    justify-content: center;
    padding: 0;
  }
  .collapsed .brand {
    justify-content: center;
    padding: 0 0 0.9rem;
  }
  .collapsed .tools {
    flex-direction: column;
    align-items: center;
    gap: 0.2rem;
  }
  .collapsed .tools .github {
    margin-left: 0;
  }
  .collapsed .section,
  .collapsed .fav .quick {
    display: none;
  }

  /* Top bar layout (theme option). */
  :global([data-sidebar="top"]) .nav {
    flex-direction: row;
    align-items: center;
    width: auto;
    height: 3.6rem;
    padding: 0 var(--sp-4);
    border-right: 0;
    border-bottom: 1px solid var(--c-border);
  }
  :global([data-sidebar="top"]) .brand {
    padding: 0 1rem 0 0;
  }
  :global([data-sidebar="top"]) .items {
    flex-direction: row;
  }
  :global([data-sidebar="top"]) .item[aria-current="page"]::before {
    left: 25%;
    right: 25%;
    top: auto;
    bottom: -0.55rem;
    width: auto;
    height: 3px;
  }
  :global([data-sidebar="top"]) .collapse,
  :global([data-sidebar="top"]) .favs {
    display: none;
  }
  :global([data-sidebar="top"]) .dot {
    left: 1.6rem;
  }
</style>
