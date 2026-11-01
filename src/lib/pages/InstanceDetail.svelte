<script lang="ts">
  import Icon from "../components/Icon.svelte";
  import InstanceIcon from "../components/InstanceIcon.svelte";
  import ProgressBar from "../components/ProgressBar.svelte";
  import CrashCard from "../components/CrashCard.svelte";
  import LogView from "../components/LogView.svelte";
  import ContentTab from "./instance/ContentTab.svelte";
  import SettingsTab from "./instance/SettingsTab.svelte";
  import ExportModal from "./instance/ExportModal.svelte";
  import WorldsTab from "./instance/WorldsTab.svelte";
  import ScreenshotsTab from "./instance/ScreenshotsTab.svelte";
  import ServersTab from "./instance/ServersTab.svelte";
  import UpgradeModal from "./instance/UpgradeModal.svelte";
  import Modal from "../components/Modal.svelte";
  import IconPicker from "../components/IconPicker.svelte";
  import { app } from "../stores/app.svelte";
  import { router } from "../stores/router.svelte";
  import { dialogs } from "../stores/dialogs.svelte";
  import { toasts } from "../stores/toasts.svelte";
  import { commands, errorMessage, instanceVersionLabel, type GameVersions } from "../api";
  import { formatPlayTime, formatRelative, t } from "../i18n/index.svelte";

  const id = router.params.id ?? "";
  type Tab = "content" | "worlds" | "screenshots" | "servers" | "settings" | "logs";
  let tab = $state<Tab>((router.params.tab as Tab) ?? "content");
  let menuOpen = $state(false);
  let exportOpen = $state(false);
  let iconOpen = $state(false);
  let upgradeOpen = $state(false);
  let versions = $state.raw<GameVersions | null>(null);
  $effect(() => {
    commands
      .listGameVersions()
      .then((v) => (versions = v))
      .catch(() => {});
  });
  // A newer release than the instance's (manifest order: newest first).
  const newerRelease = $derived.by(() => {
    if (!versions || !inst) return null;
    const list = versions.versions;
    const cur = list.findIndex((v) => v.id === inst.gameVersion);
    const latest = list.findIndex((v) => v.id === versions!.latestRelease);
    return cur > latest && latest >= 0 ? versions.latestRelease : null;
  });
  let iconChoice = $state<string | null>(null);
  let iconFile = $state<string | null>(null);
  let iconBusy = $state(false);

  function editIcon() {
    iconChoice = inst?.icon ?? null;
    iconFile = null;
    iconOpen = true;
  }

  async function saveIcon() {
    iconBusy = true;
    // Keeping the current custom image needs no change.
    const unchanged = !iconFile && iconChoice === (inst?.icon ?? null);
    if (unchanged || (await app.setInstanceIcon(id, iconChoice, iconFile))) iconOpen = false;
    iconBusy = false;
  }

  const inst = $derived(app.instances.find((i) => i.id === id));
  const running = $derived(!!app.running[id]);
  const launchTask = $derived(
    app.tasks.find((x) => x.instanceId === id && x.kind === "launch" && x.status === "running"),
  );
  const crash = $derived(app.crashes[id]);
  const logs = $derived(app.logsFor(id));
  const levels = new Set(["info", "warn", "error", "raw", "debug"]);

  $effect(() => {
    void app.refreshInstances();
    void app.loadLogs(id);
  });

  async function act(fn: () => Promise<unknown>, success?: string) {
    menuOpen = false;
    try {
      await fn();
      if (success) toasts.success(success);
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  }

  async function clone() {
    if (!inst) return;
    const name = await dialogs.prompt({
      title: t("instances.cloneTitle"),
      value: t("instances.cloneName", { name: inst.name }),
    });
    if (!name) return;
    await act(async () => {
      const c = await commands.cloneInstance(id, name);
      await app.refreshInstances();
      router.go("instance", { id: c.id });
    });
  }

  async function remove() {
    if (!inst) return;
    menuOpen = false;
    const ok = await dialogs.confirm({
      title: t("instances.deleteTitle", { name: inst.name }),
      text: t("instances.deleteText"),
      confirmLabel: t("common.delete"),
      danger: true,
    });
    if (!ok) return;
    await act(async () => {
      await commands.deleteInstance(id);
      await app.refreshInstances();
      router.go("instances");
    });
  }

  function progress(): number | null {
    const p = launchTask?.progress;
    if (!p) return null;
    if (p.totalBytes > 0) return p.doneBytes / p.totalBytes;
    return p.totalItems > 0 ? p.doneItems / p.totalItems : null;
  }
</script>

<div class="page detail">
  {#if !inst}
    <div class="empty">
      <p>{t("instance.notFound")}</p>
      <button class="btn" onclick={() => router.go("instances")}>{t("common.back")}</button>
    </div>
  {:else}
    <header class="head card">
      <button class="icon-btn" onclick={() => router.back()} aria-label={t("common.back")}
        ><Icon name="arrowLeft" /></button
      >
      <button
        class="icon-edit"
        title={t("instance.changeIcon")}
        aria-label={t("instance.changeIcon")}
        onclick={editIcon}
        ><InstanceIcon name={inst.name} loader={inst.loader} icon={inst.icon} id={inst.id} size={58} /><span
          class="pen"><Icon name="pencil" size={13} /></span
        ></button
      >
      <div class="col gap-1 grow" style="min-width:0">
        <h1 class="truncate">{inst.name}</h1>
        <div class="row wrap small muted">
          <span class="badge accent">{instanceVersionLabel(inst, true)}</span>
          {#if newerRelease}<button class="badge newer" onclick={() => (upgradeOpen = true)}
              ><Icon name="upload" size={12} />{t("instance.upgrade.available", {
                version: newerRelease,
              })}</button
            >{/if}
          {#if inst.modpack}<span class="badge">{t("instances.modpack", { name: inst.modpack.name })}</span
            >{/if}
          {#if inst.group}<span class="badge">{inst.group}</span>{/if}
          <span>{t("instances.lastPlayed")}: {formatRelative(inst.lastPlayed)}</span>
          {#if inst.playTimeSecs}<span>· {t("instances.playTime")}: {formatPlayTime(inst.playTimeSecs)}</span
            >{/if}
        </div>
      </div>
      <button
        class="icon-btn fav"
        class:on={inst.favorite}
        title={t(inst.favorite ? "instances.unfavorite" : "instances.favorite")}
        aria-label={t(inst.favorite ? "instances.unfavorite" : "instances.favorite")}
        aria-pressed={inst.favorite}
        onclick={() => app.toggleFavorite(id)}><Icon name="star" /></button
      >
      <button
        class="icon-btn"
        title={t("common.openFolder")}
        aria-label={t("common.openFolder")}
        onclick={() => act(() => commands.openFolder({ kind: "gameDir", id }))}><Icon name="folder" /></button
      >
      <div class="menu-wrap">
        <button
          class="icon-btn"
          aria-label={t("common.more")}
          aria-expanded={menuOpen}
          onclick={() => (menuOpen = !menuOpen)}><Icon name="more" /></button
        >
        {#if menuOpen}
          <div class="menu card raised" role="menu">
            <button role="menuitem" onclick={() => ((menuOpen = false), (upgradeOpen = true))}
              ><Icon name="upload" size={16} />{t("instance.upgrade.menu")}</button
            >
            <button role="menuitem" onclick={clone}
              ><Icon name="copy" size={16} />{t("instances.clone")}</button
            >
            <button role="menuitem" onclick={() => ((menuOpen = false), (exportOpen = true))}
              ><Icon name="upload" size={16} />{t("common.export")}</button
            >
            <button
              role="menuitem"
              onclick={() => act(() => commands.createShortcut(id), t("instances.shortcutCreated"))}
              ><Icon name="link" size={16} />{t("instances.shortcut")}</button
            >
            <button role="menuitem" onclick={() => act(() => commands.verifyInstance(id))}
              ><Icon name="wrench" size={16} />{t("instances.verify")}</button
            >
            <hr class="divider" />
            <button role="menuitem" class="danger-text" onclick={remove}
              ><Icon name="trash" size={16} />{t("common.delete")}</button
            >
          </div>
        {/if}
      </div>
      <button
        class="btn lg"
        class:primary={!running}
        class:danger={running}
        disabled={!!launchTask && !running}
        onclick={() => (running ? app.stop(id) : app.launch(id))}
      >
        <Icon name={running ? "stop" : "play"} size={18} />{running ? t("common.stop") : t("common.play")}
      </button>
    </header>

    {#if launchTask}
      <div class="launch card">
        <span class="small muted"
          >{t(`tasks.stages.${launchTask.progress.stage.replace(/^stage\./, "")}`, {
            detail: launchTask.progress.detail ?? "",
          })}</span
        >
        <ProgressBar value={progress()} />
      </div>
    {/if}
    {#if crash}<CrashCard {crash} instanceId={id} ondetails={() => (tab = "logs")} />{/if}

    <div class="tabs" role="tablist">
      {#each [["content", "instance.tabContent", "layers"], ["worlds", "instance.tabWorlds", "globe"], ["screenshots", "instance.tabScreenshots", "image"], ["servers", "instance.tabServers", "monitor"], ["settings", "instance.tabSettings", "sliders"], ["logs", "instance.tabLogs", "terminal"]] as [key, label, icon] (key)}
        <button role="tab" aria-selected={tab === key} onclick={() => (tab = key as Tab)}
          ><Icon name={icon} size={16} />{t(label)}</button
        >
      {/each}
    </div>

    <section class="tab-body">
      {#if tab === "content"}
        <ContentTab instance={inst} />
      {:else if tab === "worlds"}
        <WorldsTab instance={inst} />
      {:else if tab === "screenshots"}
        <ScreenshotsTab instance={inst} />
      {:else if tab === "servers"}
        <ServersTab instance={inst} />
      {:else if tab === "settings"}
        <SettingsTab instance={inst} onremove={remove} />
      {:else}
        <div class="logs">
          <LogView lines={logs} {levels} />
        </div>
      {/if}
    </section>
    <ExportModal bind:open={exportOpen} instance={inst} />
    <UpgradeModal bind:open={upgradeOpen} instance={inst} {versions} />
    <Modal bind:open={iconOpen} title={t("instance.changeIcon")} width="30rem">
      <IconPicker
        bind:icon={iconChoice}
        bind:file={iconFile}
        name={inst.name}
        loader={inst.loader}
        id={inst.id}
      />
      {#snippet footer()}
        <button class="btn ghost" onclick={() => (iconOpen = false)}>{t("common.cancel")}</button>
        <button class="btn primary" disabled={iconBusy} onclick={saveIcon}>{t("common.save")}</button>
      {/snippet}
    </Modal>
  {/if}
</div>

<style>
  .newer {
    display: inline-flex;
    align-items: center;
    gap: 0.25rem;
    border: 1px solid color-mix(in srgb, var(--c-success) 45%, transparent);
    color: var(--c-success);
    background: color-mix(in srgb, var(--c-success) 12%, transparent);
    cursor: pointer;
  }
  .icon-edit {
    position: relative;
    padding: 0;
    border: none;
    background: none;
    cursor: pointer;
    border-radius: calc(var(--r-md) * 1.1);
  }
  .pen {
    position: absolute;
    right: -4px;
    bottom: -4px;
    display: grid;
    place-items: center;
    width: 22px;
    height: 22px;
    border-radius: 50%;
    background: var(--c-surface-3);
    color: var(--c-text);
    box-shadow: 0 1px 3px rgb(0 0 0 / 0.35);
    opacity: 0;
    transition: opacity var(--dur) var(--ease);
  }
  .icon-edit:hover .pen,
  .icon-edit:focus-visible .pen {
    opacity: 1;
  }
  .fav.on {
    color: var(--c-warning);
  }
  .fav.on :global(svg) {
    fill: currentColor;
  }
  .detail {
    display: flex;
    flex-direction: column;
    gap: var(--sp-3);
  }
  .head {
    /* Cards are stacking contexts: without this the next card paints over the "more" menu. */
    position: relative;
    z-index: 5;
    display: flex;
    align-items: center;
    gap: var(--sp-3);
  }
  .head h1 {
    font-size: 1.5rem;
  }
  .launch {
    display: flex;
    flex-direction: column;
    gap: 0.4rem;
    padding: var(--sp-3) var(--sp-4);
  }
  .menu-wrap {
    position: relative;
  }
  .menu {
    position: absolute;
    right: 0;
    top: calc(100% + 0.3rem);
    z-index: 10;
    min-width: 13rem;
    padding: 0.35rem;
    background: var(--c-surface);
    display: flex;
    flex-direction: column;
    animation: fade-up var(--dur) var(--ease);
  }
  .menu button {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    padding: 0.5rem 0.65rem;
    border: 0;
    border-radius: var(--r-sm);
    background: transparent;
    text-align: left;
    cursor: pointer;
  }
  .menu button:hover {
    background: var(--c-hover);
  }
  .menu .divider {
    margin: 0.3rem 0;
  }
  .tabs {
    display: flex;
    gap: 0.25rem;
    border-bottom: 1px solid var(--c-border);
  }
  .tabs button {
    display: inline-flex;
    align-items: center;
    gap: 0.45rem;
    padding: 0.6rem 0.9rem;
    border: 0;
    border-bottom: 2px solid transparent;
    margin-bottom: -1px;
    background: transparent;
    color: var(--c-text-muted);
    font-weight: 560;
    cursor: pointer;
  }
  .tabs button[aria-selected="true"] {
    color: var(--c-text);
    border-bottom-color: var(--c-accent);
  }
  .tab-body {
    flex: 1;
    min-height: 0;
  }
  .logs {
    height: calc(100vh - 20rem);
    min-height: 18rem;
  }
</style>
