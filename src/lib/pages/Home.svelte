<script lang="ts">
  import AdSlot from "../components/AdSlot.svelte";
  import Icon from "../components/Icon.svelte";
  import InstanceIcon from "../components/InstanceIcon.svelte";
  import SkinViewer from "../components/SkinViewer.svelte";
  import Logo from "../components/Logo.svelte";
  import { app } from "../stores/app.svelte";
  import { router } from "../stores/router.svelte";
  import { ui } from "../stores/ui.svelte";
  import { DEFAULT_SKIN, instanceTitle, instanceVersionLabel, type Instance } from "../api";
  import { layout } from "../stores/layout.svelte";
  import { formatPlayTime, formatRelative, t } from "../i18n/index.svelte";

  const account = $derived(app.selectedAccount);
  const current = $derived(app.lastInstance);
  const running = $derived(current ? !!app.running[current.id] : false);
  const launchTask = $derived(
    current
      ? app.tasks.find((x) => x.instanceId === current.id && x.kind === "launch" && x.status === "running")
      : undefined,
  );
  // Favourites first, then the most recently played (the list is already in that order).
  const ordered = $derived([...app.instances].sort((a, b) => Number(b.favorite) - Number(a.favorite)));
  const recent = $derived(ordered.slice(0, 5));
  const hasFavorites = $derived(app.instances.some((i) => i.favorite));
  let pickerOpen = $state(false);
  const blocks = $derived(layout.home.filter((b) => b.visible));
  const totalSecs = $derived(app.instances.reduce((n, i) => n + i.playTimeSecs, 0));
  const lastPlayed = $derived(
    app.instances.filter((i) => i.lastPlayed).sort((a, b) => (b.lastPlayed ?? 0) - (a.lastPlayed ?? 0))[0] ??
      null,
  );
  const actions = [
    { key: "home.actions.newInstance", icon: "plus", run: () => (ui.newInstanceOpen = true) },
    { key: "home.actions.mods", icon: "package", run: () => router.go("mods") },
    { key: "home.actions.skins", icon: "shirt", run: () => router.go("skins") },
    { key: "home.actions.stats", icon: "chart", run: () => router.go("stats") },
  ];

  function play() {
    if (!current) {
      ui.newInstanceOpen = true;
      return;
    }
    if (running) void app.stop(current.id);
    else if (!launchTask) void app.launch(current.id);
  }

  function choose(i: Instance) {
    pickerOpen = false;
    void app.saveSettings({ lastInstance: i.id });
  }

  function progress(): number | null {
    const p = launchTask?.progress;
    if (!p) return null;
    if (p.totalBytes > 0) return p.doneBytes / p.totalBytes;
    if (p.totalItems > 0) return p.doneItems / p.totalItems;
    return null;
  }

  function stageLabel(): string {
    const key = launchTask?.progress.stage.replace(/^stage\./, "") ?? "";
    return key
      ? t(`tasks.stages.${key}`, { detail: launchTask?.progress.detail ?? "" })
      : t("common.loading");
  }
</script>

<div class="home" data-player={layout.player}>
  <section class="main fade-up">
    {#each blocks as b (b.id)}
      {#if b.id === "greeting"}
        <div class="brand">
          <span class="logo"><Logo /></span>
          <h1>Aerion</h1>
          <p class="greet muted">
            {account ? t("home.greeting", { name: account.username }) : t("home.greetingAnon")}
          </p>
        </div>
      {:else if b.id === "play"}
        <div class="play-block">
          <div class="play-row">
            <button
              class="item play"
              class:running
              class:launching={!!launchTask && !running}
              class:indeterminate={!!launchTask && !running && progress() === null}
              style:--p={progress() ?? 0}
              onclick={play}
              disabled={!!launchTask && !running}
            >
              <span class="fill" aria-hidden="true"></span>
              <span class="main-label">
                {#if launchTask && !running}
                  <span class="spinner sm"></span>{t("home.starting")}
                {:else}
                  <Icon name={running ? "stop" : current ? "play" : "plus"} size={20} />
                  {running ? t("common.stop") : current ? t("common.play") : t("home.createFirst")}
                {/if}
              </span>
              {#if launchTask && !running}
                <span class="sub" aria-live="polite">{stageLabel()}</span>
              {:else if current}<span class="sub">{instanceTitle(current)}</span>{/if}
            </button>
            {#if app.instances.length}
              <button
                class="item play chooser"
                onclick={() => (pickerOpen = !pickerOpen)}
                aria-expanded={pickerOpen}
                aria-label={t("home.chooseInstance")}
                title={t("home.chooseInstance")}><Icon name="chevronDown" size={18} /></button
              >
            {/if}
            {#if pickerOpen}
              <div class="picker card raised" role="listbox" aria-label={t("home.chooseInstance")}>
                {#each ordered as i (i.id)}
                  <button
                    class="pick"
                    role="option"
                    aria-selected={i.id === current?.id}
                    onclick={() => choose(i)}
                  >
                    <InstanceIcon name={i.name} loader={i.loader} icon={i.icon} id={i.id} size={30} />
                    <span class="grow truncate">{i.name}</span>
                    {#if i.favorite}<span class="fav-mark"><Icon name="star" size={13} /></span>{/if}
                    <span class="tiny faint">{instanceVersionLabel(i)}</span>
                  </button>
                {/each}
              </div>
            {/if}
          </div>
          <p class="status small faint">
            {#if !account}
              <button class="link" onclick={() => router.go("accounts")}>{t("home.signInHint")}</button>
            {:else if running}
              <span class="live"></span>{t("home.running")}
            {:else if current}
              {t("home.lastPlayed", {
                when: formatRelative(current.lastPlayed),
              })}{#if current.playTimeSecs > 0}&nbsp;· {t("home.playTime", {
                  time: formatPlayTime(current.playTimeSecs),
                })}{/if}
            {/if}
          </p>
        </div>
      {:else if b.id === "actions"}
        <div class="actions">
          {#each actions as a (a.key)}
            <button class="action" onclick={a.run}
              ><Icon name={a.icon} size={18} /><span>{t(a.key)}</span></button
            >
          {/each}
        </div>
      {:else if b.id === "summary"}
        <div class="summary">
          <div class="tile card">
            <strong>{formatPlayTime(totalSecs)}</strong><span class="tiny muted">{t("stats.playTime")}</span>
          </div>
          <div class="tile card">
            <strong>{app.instances.length}</strong><span class="tiny muted">{t("stats.instances")}</span>
          </div>
          <div class="tile card">
            <strong class="truncate">{lastPlayed?.name ?? "—"}</strong>
            <span class="tiny muted"
              >{lastPlayed ? formatRelative(lastPlayed.lastPlayed) : t("home.noPlayYet")}</span
            >
          </div>
        </div>
      {:else if b.id === "recent" && recent.length > 1}
        <div class="recent">
          <div class="section-title">{t(hasFavorites ? "home.favoritesAndRecent" : "home.recent")}</div>
          <div class="cards">
            {#each recent as i (i.id)}
              <button
                class="mini card"
                class:current={i.id === current?.id}
                onclick={() => choose(i)}
                ondblclick={() => router.go("instance", { id: i.id })}
              >
                <InstanceIcon name={i.name} loader={i.loader} icon={i.icon} id={i.id} size={34} />
                <span class="col gap-1 grow" style="min-width:0">
                  <span class="truncate strong">{i.name}</span>
                  <span class="tiny faint truncate">{instanceVersionLabel(i)}</span>
                </span>
                {#if i.favorite}<span class="fav-mark"><Icon name="star" size={13} /></span>{/if}
                {#if app.running[i.id]}<span class="live"></span>{/if}
              </button>
            {/each}
          </div>
        </div>
      {/if}
    {/each}
    <AdSlot slot="home" />
  </section>

  {#if layout.player !== "hidden"}
    <aside class="player fade-up" aria-label={account?.username ?? ""}>
      <div class="player-name">{account?.username ?? "Steve"}</div>
      <div class="underline"></div>
      <SkinViewer
        skinUrl={account?.skinUrl ?? DEFAULT_SKIN}
        capeUrl={account?.capeUrl}
        follow={app.settings?.followCursor ?? true}
        slim={account?.skinVariant === "slim"}
        scale={9}
      />
    </aside>
  {/if}

  <button
    class="icon-btn customize"
    onclick={() => (ui.customize = "home")}
    aria-label={t("layout.homeTitle")}
    title={t("layout.homeTitle")}><Icon name="sliders" size={17} /></button
  >
</div>

<style>
  .home {
    position: relative;
    height: 100%;
    display: grid;
    grid-template-columns: 1fr minmax(14rem, 32%);
    padding: var(--sp-5) var(--sp-6);
    overflow: auto;
  }
  .home[data-player="left"] {
    grid-template-columns: minmax(14rem, 32%) 1fr;
  }
  .home[data-player="left"] .player {
    order: -1;
  }
  .home[data-player="hidden"] {
    grid-template-columns: 1fr;
  }
  .customize {
    position: absolute;
    top: var(--sp-3);
    right: var(--sp-3);
    opacity: 0.55;
  }
  .customize:hover,
  .customize:focus-visible {
    opacity: 1;
  }
  .main {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: var(--sp-5);
    min-height: 26rem;
  }
  .brand {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 0.4rem;
  }
  .logo {
    display: grid;
    width: 4.2rem;
    height: 4.2rem;
    color: var(--c-text);
    filter: drop-shadow(0 6px 18px color-mix(in srgb, var(--c-accent) 35%, transparent));
  }
  .logo :global(svg) {
    width: 100%;
    height: 100%;
  }
  h1 {
    font-size: 2.1rem;
    font-weight: 750;
    letter-spacing: -0.03em;
  }
  .item {
    position: relative;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 0.1rem;
    padding: 0.62rem 1rem;
    border: 0;
    border-radius: var(--r-md);
    background: transparent;
    color: var(--c-text);
    font-size: 1.12rem;
    font-weight: 560;
    cursor: pointer;
    transition:
      background var(--dur) var(--ease),
      transform var(--dur) var(--ease);
  }
  .item:hover:not(:disabled),
  .item:focus-visible {
    background: color-mix(in srgb, var(--c-text) 13%, transparent);
  }
  .item:disabled {
    opacity: 0.55;
    cursor: default;
  }
  .item.play {
    font-size: 1.32rem;
    font-weight: 700;
    padding: 0.85rem 1rem;
    margin-bottom: 0.35rem;
    background: color-mix(in srgb, var(--c-accent) 20%, transparent);
    box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--c-accent) 35%, transparent);
  }
  .item.play:hover:not(:disabled) {
    background: color-mix(in srgb, var(--c-accent) 30%, transparent);
  }
  .item.play {
    position: relative;
    overflow: hidden;
  }
  /* While starting, the button fills with the accent as the launch advances. */
  .item.play .fill {
    position: absolute;
    inset: 0 auto 0 0;
    width: calc(var(--p) * 100%);
    background: color-mix(in srgb, var(--c-accent) 38%, transparent);
    transition: width var(--dur-slow) var(--ease);
    pointer-events: none;
  }
  .item.play.launching:disabled {
    opacity: 1;
  }
  .item.play.indeterminate .fill {
    width: 40%;
    animation: sweep 1.3s var(--ease) infinite;
  }
  @keyframes sweep {
    from {
      transform: translateX(-100%);
    }
    to {
      transform: translateX(260%);
    }
  }
  .item.play > :global(*:not(.fill)) {
    position: relative;
  }
  .item.play.running {
    background: color-mix(in srgb, var(--c-danger) 18%, transparent);
    box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--c-danger) 40%, transparent);
  }
  .main-label {
    display: inline-flex;
    align-items: center;
    gap: 0.55rem;
  }
  .play .main-label :global(.icon) {
    color: var(--c-accent);
  }
  .play.running .main-label :global(.icon) {
    color: var(--c-danger);
  }
  .play-block {
    display: flex;
    flex-direction: column;
    align-items: stretch;
    gap: 0.4rem;
    width: min(26rem, 100%);
  }
  .play-row {
    position: relative;
    display: flex;
    gap: 2px;
  }
  .play-row .play {
    flex: 1;
    margin-bottom: 0;
  }
  .play-row .play:first-child {
    border-top-right-radius: 0;
    border-bottom-right-radius: 0;
  }
  .play-row .chooser {
    flex: none;
    width: 3.2rem;
    border-top-left-radius: 0;
    border-bottom-left-radius: 0;
    justify-content: center;
  }
  .actions {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: var(--sp-2);
    width: min(32rem, 100%);
  }
  .action {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 0.35rem;
    min-width: 6.5rem;
    padding: 0.7rem 0.6rem;
    border: 1px solid var(--c-border);
    border-radius: var(--r-md);
    background: color-mix(in srgb, var(--c-surface) 70%, transparent);
    color: var(--c-text);
    font-size: 0.86rem;
    cursor: pointer;
    transition:
      border-color var(--dur) var(--ease),
      background var(--dur) var(--ease);
  }
  .action:hover,
  .action:focus-visible {
    border-color: var(--c-accent);
    background: color-mix(in srgb, var(--c-accent) 12%, transparent);
  }
  .action :global(.icon) {
    color: var(--c-accent);
  }
  .summary {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: var(--sp-2);
    width: min(32rem, 100%);
  }
  .tile {
    display: flex;
    flex-direction: column;
    gap: 0.15rem;
    min-width: 0;
    padding: var(--sp-2) var(--sp-3);
  }
  .sub {
    font-size: 0.8rem;
    font-weight: 500;
    color: var(--c-text-muted);
  }
  .picker {
    position: absolute;
    top: calc(100% + 0.3rem);
    left: 0;
    right: 0;
    z-index: 5;
    max-height: 18rem;
    overflow: auto;
    padding: 0.35rem;
    background: var(--c-surface);
    display: flex;
    flex-direction: column;
    gap: 0.15rem;
    animation: fade-up var(--dur) var(--ease);
  }
  .pick {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    padding: 0.4rem 0.55rem;
    border: 0;
    border-radius: var(--r-md);
    background: transparent;
    cursor: pointer;
    text-align: left;
  }
  .pick:hover,
  .pick[aria-selected="true"] {
    background: var(--c-hover);
  }
  .status {
    min-height: 1.4em;
    display: flex;
    align-items: center;
    gap: 0.4rem;
  }
  .link {
    border: 0;
    background: none;
    color: var(--c-accent);
    cursor: pointer;
    padding: 0;
  }
  .live {
    display: inline-block;
    width: 0.5rem;
    height: 0.5rem;
    border-radius: 50%;
    background: var(--c-success);
    box-shadow: 0 0 0 3px color-mix(in srgb, var(--c-success) 25%, transparent);
  }
  .player {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 0.4rem;
  }
  .player-name {
    font-size: 1.15rem;
    font-weight: 650;
  }
  .underline {
    width: 2.2rem;
    height: 2px;
    border-radius: 2px;
    background: var(--c-text);
    opacity: 0.7;
    margin-bottom: 1.2rem;
  }
  .fav-mark {
    display: inline-flex;
    color: var(--c-warning);
  }
  .fav-mark :global(svg) {
    fill: currentColor;
  }
  .recent {
    display: flex;
    width: 100%;
    flex-direction: column;
    gap: var(--sp-2);
    padding-top: var(--sp-3);
  }
  .cards {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(12.5rem, 1fr));
    gap: var(--sp-2);
  }
  .mini {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    padding: 0.55rem 0.7rem;
    cursor: pointer;
    text-align: left;
    transition:
      border-color var(--dur) var(--ease),
      transform var(--dur) var(--ease);
  }
  .mini:hover {
    transform: translateY(-1px);
    border-color: color-mix(in srgb, var(--c-accent) 40%, var(--c-border));
  }
  .mini.current {
    border-color: color-mix(in srgb, var(--c-accent) 60%, transparent);
  }
  .strong {
    font-weight: 600;
  }
  @media (max-width: 1050px) {
    .home {
      grid-template-columns: 1fr;
    }
    .player {
      display: none;
    }
  }
</style>
