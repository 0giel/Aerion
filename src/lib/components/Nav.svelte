<script lang="ts">
  import { openUrl } from "@tauri-apps/plugin-opener";
  import { DISCORD_URL, REPO_URL } from "../links";
  import Icon from "./Icon.svelte";
  import SkinFace from "./SkinFace.svelte";
  import { DEFAULT_SKIN } from "../api";
  import Logo from "./Logo.svelte";
  import InstanceIcon from "./InstanceIcon.svelte";
  import { tick } from "svelte";
  import { layout } from "../stores/layout.svelte";
  import { themes } from "../theme/engine.svelte";
  import { dashboards } from "../stores/dashboards.svelte";
  import { ui } from "../stores/ui.svelte";
  import { router, type Page } from "../stores/router.svelte";
  import { app } from "../stores/app.svelte";
  import { t } from "../i18n/index.svelte";

  let { onpalette }: { onpalette: () => void } = $props();

  const items = $derived(layout.nav.filter((i) => i.visible));
  const favorites = $derived(layout.navFavorites ? app.instances.filter((i) => i.favorite).slice(0, 6) : []);

  const side = $derived(layout.navSide === "theme" ? themes.active.layout.sidebar : layout.navSide);
  const horizontal = $derived(side === "top" || side === "bottom");
  const mode = $derived(horizontal ? "fixed" : layout.navMode);
  const spring = $derived(layout.springy && themes.active.motion.enabled && !themes.reducedMotion);
  let hovered = $state(false);
  let itemsEl = $state<HTMLElement>();
  let slide = $state<{ x: number; y: number; w: number; h: number } | null>(null);

  let collapsed = $state(readCollapsed());
  // Hover mode is a slim rail that opens under the cursor; icons mode never opens.
  const shut = $derived(mode === "fixed" ? collapsed : mode === "icons" || !hovered);

  function measure() {
    const el = itemsEl?.querySelector<HTMLElement>('.item[aria-current="page"]');
    slide = el ? { x: el.offsetLeft, y: el.offsetTop, w: el.offsetWidth, h: el.offsetHeight } : null;
  }
  $effect(() => {
    // Re-measure whenever something that moves the active button changes.
    void [
      router.page,
      router.params.id,
      items.length,
      shut,
      side,
      layout.navIconSize,
      layout.navGaps,
      dashboards.pages.length,
    ];
    void tick().then(measure);
  });
  $effect(() => {
    if (!itemsEl) return;
    const ro = new ResizeObserver(measure);
    ro.observe(itemsEl);
    return () => ro.disconnect();
  });
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

<div
  class="navslot"
  class:rail={mode === "hover"}
  data-side={side}
  role="presentation"
  onmouseenter={() => (hovered = true)}
  onmouseleave={() => (hovered = false)}
  onfocusin={() => (hovered = true)}
  onfocusout={() => (hovered = false)}
>
  <nav
    class="nav"
    class:collapsed={shut}
    class:open={mode === "hover" && !shut}
    class:sliding={spring}
    style:--nav-icon="{layout.navIconSize}px"
    aria-label="Main"
  >
    <div class="brand">
      <span class="logo"><Logo /></span>
      <span class="name">Aerion</span>
    </div>
    <div class="items" bind:this={itemsEl}>
      {#if spring && slide}
        <span
          class="slider"
          aria-hidden="true"
          style:transform="translate({slide.x}px, {slide.y}px)"
          style:width="{slide.w}px"
          style:height="{slide.h}px"
        ></span>
      {/if}
      {#each items as item, n (item.page)}
        <button
          class="item"
          style:--i={n}
          aria-current={active(item.page) ? "page" : undefined}
          title={shut ? t(item.key) : undefined}
          onclick={() => router.go(item.page)}
        >
          <Icon name={item.icon} size={layout.navIconSize} />
          <span class="label">{t(item.key)}</span>
          {#if item.page === "console" && runningCount > 0}<span class="dot" aria-label={t("console.running")}
            ></span>{/if}
        </button>
        {#if layout.navGaps.includes(item.id)}<span class="gap" aria-hidden="true"></span>{/if}
      {/each}
      {#each dashboards.pages as d (d.id)}
        <button
          class="item"
          aria-current={router.page === "dashboard" && router.params.id === d.id ? "page" : undefined}
          title={shut ? d.name : undefined}
          onclick={() => router.go("dashboard", { id: d.id })}
        >
          <Icon name={d.icon} size={layout.navIconSize} />
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
        hidden={mode !== "fixed"}
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
</div>

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
  :global([data-spring="on"]) .logo {
    transition: transform var(--dur-slow) var(--spring);
  }
  :global([data-spring="on"]) .brand:hover .logo {
    transform: rotate(-12deg) scale(1.18);
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

  /* The slot is the grid item; in hover mode it keeps only the slim rail's width and the menu opens over the page. */
  .navslot {
    display: flex;
    min-width: 0;
  }
  .navslot > .nav {
    flex: none;
    /* Short windows: the menu scrolls instead of losing its bottom buttons. */
    overflow-x: hidden;
    overflow-y: auto;
    scrollbar-width: none;
  }
  .navslot.rail {
    position: relative;
    z-index: 30;
    width: 4.25rem;
  }
  .navslot.rail > .nav {
    position: absolute;
    top: 0;
    bottom: 0;
    left: 0;
  }
  .navslot.rail[data-side="right"] > .nav {
    left: auto;
    right: 0;
  }
  .nav.open {
    box-shadow: 0 0 40px rgb(0 0 0 / calc(var(--shadow-a) * 0.8));
  }

  /* Icon size follows the setting. */
  .logo {
    width: calc(var(--nav-icon) * 1.45);
    height: calc(var(--nav-icon) * 1.45);
  }
  .item {
    height: calc(var(--nav-icon) + 1.3rem);
  }

  /* Dividers between groups of buttons. */
  .gap {
    flex: none;
    height: 1px;
    margin: 0.3rem 0.75rem;
    background: var(--c-border);
  }

  /* Springy mode: one highlight slides between the buttons. */
  .slider {
    position: absolute;
    left: 0;
    top: 0;
    border-radius: var(--r-md);
    background: var(--c-accent-soft);
    pointer-events: none;
    transition:
      transform var(--dur-slow) var(--spring),
      width var(--dur-slow) var(--spring),
      height var(--dur-slow) var(--spring);
  }
  .slider::before {
    content: "";
    position: absolute;
    left: -0.75rem;
    top: 25%;
    bottom: 25%;
    width: 3px;
    border-radius: 3px;
    background: var(--c-accent);
  }
  .items {
    position: relative;
  }
  .sliding .items .item {
    z-index: 1;
  }
  .sliding .items .item[aria-current="page"] {
    background: transparent;
  }
  .sliding .items .item[aria-current="page"]::before {
    display: none;
  }
  .sliding .item :global(.icon) {
    transition: transform var(--dur) var(--spring);
  }
  .sliding .item:hover :global(.icon) {
    transform: scale(1.14) rotate(-4deg);
  }
  .sliding .item:active :global(.icon) {
    transform: scale(0.82);
  }
  .sliding .item {
    animation: nav-in var(--dur-slow) var(--spring) both;
    animation-delay: calc(var(--i, 0) * 28ms);
  }
  @keyframes nav-in {
    from {
      opacity: 0;
      transform: translateX(-10px) scale(0.96);
    }
    to {
      opacity: 1;
      transform: none;
    }
  }

  /* Menu on the right. */
  [data-side="right"] .nav {
    border-right: 0;
    border-left: 1px solid var(--c-border);
  }
  [data-side="right"] .item[aria-current="page"]::before,
  [data-side="right"] .slider::before {
    left: auto;
    right: -0.75rem;
  }

  /* Menu on the top or the bottom: a row. */
  [data-side="top"] .nav,
  [data-side="bottom"] .nav {
    flex: 1;
    flex-direction: row;
    align-items: center;
    width: auto;
    height: 3.6rem;
    padding: 0 var(--sp-4);
    border-right: 0;
    border-bottom: 1px solid var(--c-border);
  }
  [data-side="bottom"] .nav {
    border-bottom: 0;
    border-top: 1px solid var(--c-border);
  }
  [data-side="top"] .brand,
  [data-side="bottom"] .brand {
    padding: 0 1rem 0 0;
  }
  [data-side="top"] .items,
  [data-side="bottom"] .items {
    flex-direction: row;
  }
  [data-side="top"] .gap,
  [data-side="bottom"] .gap {
    width: 1px;
    height: auto;
    margin: 0.7rem 0.3rem;
  }
  [data-side="top"] .item[aria-current="page"]::before,
  [data-side="top"] .slider::before,
  [data-side="bottom"] .item[aria-current="page"]::before,
  [data-side="bottom"] .slider::before {
    left: 25%;
    right: 25%;
    top: auto;
    bottom: -0.55rem;
    width: auto;
    height: 3px;
  }
  [data-side="bottom"] .item[aria-current="page"]::before,
  [data-side="bottom"] .slider::before {
    bottom: auto;
    top: -0.55rem;
  }
  /* A row has no room for the search label, its shortcut or the account name. */
  [data-side="top"] .palette .label,
  [data-side="top"] .palette kbd,
  [data-side="bottom"] .palette .label,
  [data-side="bottom"] .palette kbd {
    display: none;
  }
  [data-side="top"] .palette,
  [data-side="bottom"] .palette {
    padding: 0 0.6rem;
  }
  [data-side="top"] .nav,
  [data-side="bottom"] .nav {
    overflow: hidden;
  }
  [data-side="top"] .collapse,
  [data-side="top"] .favs,
  [data-side="bottom"] .collapse,
  [data-side="bottom"] .favs {
    display: none;
  }
  [data-side="top"] .dot,
  [data-side="bottom"] .dot {
    left: 1.6rem;
  }
</style>
