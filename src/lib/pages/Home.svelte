<script lang="ts">
  import Icon from "../components/Icon.svelte";
  import InstanceIcon from "../components/InstanceIcon.svelte";
  import ProgressBar from "../components/ProgressBar.svelte";
  import SkinViewer from "../components/SkinViewer.svelte";
  import Logo from "../components/Logo.svelte";
  import { app } from "../stores/app.svelte";
  import { router } from "../stores/router.svelte";
  import { ui } from "../stores/ui.svelte";
  import { instanceVersionLabel, type Instance } from "../api";
  import { formatPlayTime, formatRelative, t } from "../i18n/index.svelte";

  const account = $derived(app.selectedAccount);
  const current = $derived(app.lastInstance);
  const running = $derived(current ? !!app.running[current.id] : false);
  const launchTask = $derived(
    current
      ? app.tasks.find((x) => x.instanceId === current.id && x.kind === "launch" && x.status === "running")
      : undefined,
  );
  const recent = $derived(app.instances.slice(0, 5));
  let pickerOpen = $state(false);

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

<div class="home">
  <section class="hero fade-up">
    <div class="brand">
      <span class="logo"><Logo /></span>
      <h1>Luncher</h1>
      <p class="greet muted">
        {account ? t("home.greeting", { name: account.username }) : t("home.greetingAnon")}
      </p>
    </div>

    <nav class="menu" aria-label={t("nav.home")}>
      <button class="item play" class:running onclick={play} disabled={!!launchTask && !running}>
        <span class="main">
          <Icon name={running ? "stop" : current ? "play" : "plus"} size={20} />
          {running ? t("common.stop") : current ? t("common.play") : t("home.createFirst")}
        </span>
        {#if current}<span class="sub">{current.name} · {instanceVersionLabel(current)}</span>{/if}
      </button>
      {#if launchTask}
        <div class="launching" aria-live="polite">
          <span class="tiny muted">{stageLabel()}</span>
          <ProgressBar value={progress()} label={stageLabel()} />
        </div>
      {/if}
      <div class="picker-wrap">
        <button
          class="item"
          onclick={() => (pickerOpen = !pickerOpen)}
          aria-expanded={pickerOpen}
          disabled={!app.instances.length}
        >
          {t("home.chooseInstance")}
        </button>
        {#if pickerOpen}
          <div class="picker card raised" role="listbox" aria-label={t("home.chooseInstance")}>
            {#each app.instances as i (i.id)}
              <button
                class="pick"
                role="option"
                aria-selected={i.id === current?.id}
                onclick={() => choose(i)}
              >
                <InstanceIcon name={i.name} loader={i.loader} size={30} />
                <span class="grow truncate">{i.name}</span>
                <span class="tiny faint">{instanceVersionLabel(i)}</span>
              </button>
            {/each}
          </div>
        {/if}
      </div>
      <button class="item" onclick={() => router.go("mods")}>{t("home.menuMods")}</button>
      <button class="item" onclick={() => router.go("settings")}>{t("home.menuSettings")}</button>
      <button class="item" onclick={() => router.go("console")}>{t("home.menuConsole")}</button>
    </nav>

    <p class="status small faint">
      {#if !account}
        <button class="link" onclick={() => router.go("accounts")}>{t("home.signInHint")}</button>
      {:else if running}
        <span class="live"></span>{t("home.running")}
      {:else if current}
        {t("home.lastPlayed", { when: formatRelative(current.lastPlayed) })}{#if current.playTimeSecs > 0}
          · {t("home.playTime", { time: formatPlayTime(current.playTimeSecs) })}{/if}
      {/if}
    </p>
  </section>

  <aside class="player fade-up" aria-label={account?.username ?? ""}>
    <div class="player-name">{account?.username ?? "Steve"}</div>
    <div class="underline"></div>
    <SkinViewer skinUrl={account?.skinUrl} slim={account?.skinVariant === "slim"} scale={9} />
  </aside>

  {#if recent.length > 1}
    <section class="recent fade-up">
      <div class="section-title">{t("home.recent")}</div>
      <div class="cards">
        {#each recent as i (i.id)}
          <button
            class="mini card"
            class:current={i.id === current?.id}
            onclick={() => choose(i)}
            ondblclick={() => router.go("instance", { id: i.id })}
          >
            <InstanceIcon name={i.name} loader={i.loader} size={34} />
            <span class="col gap-1 grow" style="min-width:0">
              <span class="truncate strong">{i.name}</span>
              <span class="tiny faint truncate">{instanceVersionLabel(i)}</span>
            </span>
            {#if app.running[i.id]}<span class="live"></span>{/if}
          </button>
        {/each}
      </div>
    </section>
  {/if}
</div>

<style>
  .home {
    position: relative;
    height: 100%;
    display: grid;
    grid-template-columns: 1fr minmax(14rem, 32%);
    grid-template-rows: 1fr auto;
    padding: var(--sp-5) var(--sp-6);
    overflow: auto;
  }
  .hero {
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
  .menu {
    display: flex;
    flex-direction: column;
    align-items: stretch;
    width: min(26rem, 100%);
    gap: 0.2rem;
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
  .item.play.running {
    background: color-mix(in srgb, var(--c-danger) 18%, transparent);
    box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--c-danger) 40%, transparent);
  }
  .main {
    display: inline-flex;
    align-items: center;
    gap: 0.55rem;
  }
  .play .main :global(.icon) {
    color: var(--c-accent);
  }
  .play.running .main :global(.icon) {
    color: var(--c-danger);
  }
  .sub {
    font-size: 0.8rem;
    font-weight: 500;
    color: var(--c-text-muted);
  }
  .launching {
    display: flex;
    flex-direction: column;
    gap: 0.35rem;
    padding: 0 0.5rem 0.5rem;
    text-align: center;
  }
  .picker-wrap {
    position: relative;
    display: flex;
    flex-direction: column;
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
  .recent {
    grid-column: 1 / -1;
    display: flex;
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
