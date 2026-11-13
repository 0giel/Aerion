<script lang="ts">
  import AdSlot from "../components/AdSlot.svelte";
  import Icon from "../components/Icon.svelte";
  import InstanceIcon from "../components/InstanceIcon.svelte";
  import SkinViewer from "../components/SkinViewer.svelte";
  import SkyIsland from "../components/SkyIsland.svelte";
  import { ambient } from "../stores/ambient.svelte";
  import { themes } from "../theme/engine.svelte";
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
    { key: "home.actions.mods", icon: "navMods", run: () => router.go("mods") },
    { key: "home.actions.skins", icon: "navSkins", run: () => router.go("skins") },
    { key: "home.actions.stats", icon: "navStats", run: () => router.go("stats") },
  ];

  // The Play button leans towards the cursor, and a burst of sparks marks the game starting.
  let lean = $state({ x: 0, y: 0 });
  let sparks = $state(0);
  let wasRunning = false;
  $effect(() => {
    const now = running;
    if (now && !wasRunning && document.documentElement.dataset.spring === "on") {
      sparks++;
      const id = setTimeout(() => (sparks = 0), 1200);
      wasRunning = now;
      return () => clearTimeout(id);
    }
    wasRunning = now;
  });
  function onLean(e: PointerEvent) {
    if (document.documentElement.dataset.spring !== "on") return;
    const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
    lean = {
      x: Math.max(-1, Math.min(1, (e.clientX - (r.left + r.width / 2)) / (r.width / 2))) * 6,
      y: Math.max(-1, Math.min(1, (e.clientY - (r.top + r.height / 2)) / (r.height / 2))) * 4,
    };
  }

  // The scene: the player on a floating island, clouds drifting behind. It only moves while the
  // launcher is in use, and not at all in lite mode or with motion off.
  const SCENE = 7;
  const TILT = -20;
  const CLOUDS = [
    { x: 4, y: 16, k: 1, t: 95 },
    { x: 62, y: 8, k: 0.7, t: 130 },
    { x: 70, y: 58, k: 0.85, t: 110 },
    { x: -4, y: 70, k: 0.6, t: 150 },
  ];
  // By the computer's clock: a square sun by day, a moon and stars from evening to morning.
  const night = (() => {
    const h = new Date().getHours();
    return h >= 20 || h < 6;
  })();
  const STARS = Array.from({ length: 14 }, (_, n) => ({
    x: (n * 37 + 11) % 97,
    y: (n * 53 + 7) % 62,
    s: 2 + (n % 3),
  }));
  const drift = $derived(
    themes.active.motion.enabled && !themes.reducedMotion && !themes.lite && ambient.active,
  );

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
          <p class="kicker">{account ? t("home.welcomeBack") : "Aerion"}</p>
          <h1>{account ? account.username : t("home.greetingAnon")}</h1>
        </div>
      {:else if b.id === "play"}
        <div
          class="play-block"
          onpointermove={onLean}
          onpointerleave={() => (lean = { x: 0, y: 0 })}
          role="presentation"
        >
          <div class="play-row" style:transform="translate({lean.x}px, {lean.y}px)">
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
            {#if sparks}
              {#key sparks}
                <span class="burst" aria-hidden="true">
                  {#each Array.from({ length: 18 }, (_, i) => i) as n (n)}
                    <i style:--a="{n * 20}deg" style:--d="{70 + (n % 3) * 26}px" style:--s="{4 + (n % 4)}px"
                    ></i>
                  {/each}
                </span>
              {/key}
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
      <div class="sky" class:still={!drift} aria-hidden="true">
        <span class="glow"></span>
        <span class="orb" class:night></span>
        {#if night}
          {#each STARS as st, n (n)}
            <span class="star" style:left="{st.x}%" style:top="{st.y}%" style:--s="{st.s}px"></span>
          {/each}
        {/if}
        {#each CLOUDS as c, n (n)}
          <span class="cloud" style:--x="{c.x}%" style:--y="{c.y}%" style:--k={c.k} style:--t="{c.t}s">
            <i></i><i></i><i></i>
          </span>
        {/each}
      </div>
      <div class="stage" class:still={!drift} style:--u="{SCENE}px">
        <div class="island"><SkyIsland scale={SCENE} pitch={TILT} /></div>
        <SkinViewer
          skinUrl={account?.skinUrl ?? DEFAULT_SKIN}
          capeUrl={account?.capeUrl}
          follow={app.settings?.followCursor ?? true}
          slim={account?.skinVariant === "slim"}
          scale={SCENE}
          pitch={TILT}
          ground={false}
        />
      </div>
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
    grid-template-columns: 1fr minmax(22rem, 46%);
    padding: var(--sp-5) var(--sp-6);
    overflow: auto;
  }
  .home[data-player="left"] {
    grid-template-columns: minmax(22rem, 46%) 1fr;
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
    align-items: flex-start;
    justify-content: center;
    gap: var(--sp-4);
    min-height: 26rem;
    padding-left: var(--sp-5);
    /* Every block shares one width, so their left and right edges line up. */
    --col: min(35rem, 100%);
  }
  .main > :global(*) {
    width: var(--col);
  }
  .home[data-player="hidden"] .main {
    align-items: center;
    padding-left: 0;
  }
  .brand {
    display: flex;
    flex-direction: column;
    gap: 0.35rem;
    min-width: 0;
    max-width: 100%;
  }
  .kicker {
    margin: 0;
    font-family: var(--font-display);
    font-size: 0.72rem;
    font-weight: 600;
    letter-spacing: 0.16em;
    text-transform: uppercase;
    color: var(--c-accent);
  }
  h1 {
    font-family: var(--font-display);
    font-size: clamp(2rem, 4.2vw, 3.1rem);
    font-weight: 800;
    letter-spacing: -0.02em;
    line-height: 1.05;
    overflow-wrap: anywhere;
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
    background: var(--c-accent);
    color: var(--c-accent-text);
    box-shadow: inset 0 -4px 0 rgb(0 0 0 / 0.2);
  }
  .item.play:hover:not(:disabled) {
    background: var(--c-accent-hover);
  }
  .item.play .sub {
    color: color-mix(in srgb, var(--c-accent-text) 72%, transparent);
  }
  .item.play.launching,
  .item.play.running {
    color: var(--c-text);
  }
  .item.play.launching {
    background: color-mix(in srgb, var(--c-accent) 20%, transparent);
    box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--c-accent) 35%, transparent);
  }
  .item.play.launching .sub,
  .item.play.running .sub {
    color: var(--c-text-muted);
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
    color: inherit;
  }
  .play.running .main-label :global(.icon) {
    color: var(--c-danger);
  }
  .play-block {
    display: flex;
    flex-direction: column;
    align-items: stretch;
    gap: 0.4rem;
  }
  :global([data-spring="on"]) .play-row {
    transition: transform var(--dur-slow) var(--spring);
  }
  .burst {
    position: absolute;
    left: 50%;
    top: 50%;
    width: 0;
    height: 0;
    pointer-events: none;
  }
  .burst i {
    position: absolute;
    left: calc(var(--s) / -2);
    top: calc(var(--s) / -2);
    width: var(--s);
    height: var(--s);
    border-radius: 50%;
    background: var(--c-accent);
    box-shadow: 0 0 8px var(--c-accent);
    animation: spark 900ms var(--ease) forwards;
  }
  @keyframes spark {
    to {
      transform: rotate(var(--a)) translateX(var(--d)) scale(0.2);
      opacity: 0;
    }
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
    flex-wrap: nowrap;
    gap: var(--sp-2);
  }
  .action {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    flex: 1 1 auto;
    justify-content: center;
    min-width: 0;
    white-space: nowrap;
    padding: 0.5rem 0.55rem;
    border: 1px solid transparent;
    border-radius: var(--r-md);
    background: color-mix(in srgb, var(--c-surface) 70%, transparent);
    color: var(--c-text);
    font-size: 0.84rem;
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
  /* The accent is kept for the main action; secondary icons take it only when pointed at. */
  .action :global(.icon) {
    color: var(--c-text-muted);
    transition: color var(--dur) var(--ease);
  }
  .action:hover :global(.icon),
  .action:focus-visible :global(.icon) {
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
    position: relative;
    display: grid;
    place-items: center;
    min-height: 34rem;
  }
  .sky {
    position: absolute;
    inset: 0;
    overflow: hidden;
    pointer-events: none;
  }
  .glow {
    position: absolute;
    left: 50%;
    top: 46%;
    width: 34rem;
    height: 34rem;
    transform: translate(-50%, -50%);
    border-radius: 50%;
    background: radial-gradient(
      closest-side,
      color-mix(in srgb, var(--c-accent) 26%, transparent),
      color-mix(in srgb, var(--c-accent) 8%, transparent) 55%,
      transparent
    );
  }
  /* The sun is a square, as it should be in a world of blocks; at night it is a paler moon. */
  .orb {
    position: absolute;
    left: 14%;
    top: 9%;
    width: 3.4rem;
    height: 3.4rem;
    background: color-mix(in srgb, var(--c-accent) 70%, #fff);
    box-shadow: 0 0 3.5rem 0.6rem color-mix(in srgb, var(--c-accent) 38%, transparent);
    opacity: 0.85;
  }
  .orb.night {
    width: 2.6rem;
    height: 2.6rem;
    background: color-mix(in srgb, var(--c-text) 82%, var(--c-accent));
    box-shadow: 0 0 2.5rem 0.3rem color-mix(in srgb, var(--c-text) 22%, transparent);
    opacity: 0.75;
  }
  .star {
    position: absolute;
    width: var(--s);
    height: var(--s);
    background: var(--c-text);
    opacity: 0.45;
  }
  /* A cloud is three stacked slabs with square corners, like the ones in the game's sky. */
  .cloud {
    position: absolute;
    left: var(--x);
    top: var(--y);
    width: calc(11rem * var(--k));
    height: calc(3.3rem * var(--k));
    opacity: calc(0.5 + 0.4 * var(--k));
    animation: drift var(--t) steps(600) infinite alternate;
  }
  .cloud i {
    position: absolute;
    height: 34%;
    background: color-mix(in srgb, var(--c-text) 13%, transparent);
  }
  .cloud i:nth-child(1) {
    left: 22%;
    right: 30%;
    top: 0;
  }
  .cloud i:nth-child(2) {
    left: 0;
    right: 10%;
    top: 33%;
    background: color-mix(in srgb, var(--c-text) 10%, transparent);
  }
  .cloud i:nth-child(3) {
    left: 12%;
    right: 0;
    top: 66%;
    background: color-mix(in srgb, var(--c-text) 7%, transparent);
  }
  @keyframes drift {
    to {
      transform: translateX(4.5rem);
    }
  }
  .stage {
    position: relative;
    margin-top: -7rem;
    animation: hover 7s steps(70) infinite alternate;
  }
  @keyframes hover {
    to {
      transform: translateY(-7px);
    }
  }
  .still .cloud,
  .stage.still {
    animation-play-state: paused;
  }
  /* The island's top centre sits exactly under the player's feet. */
  .island {
    position: absolute;
    left: calc((11 - 46) * var(--u));
    top: calc((18 + 16 * 0.9397 - 50) * var(--u));
    pointer-events: none;
  }
  .stage > :global(.viewer) {
    position: relative;
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
    flex-direction: column;
    gap: var(--sp-2);
    padding-top: var(--sp-4);
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
    border-color: transparent;
    background: color-mix(in srgb, var(--c-surface) 78%, transparent);
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
