<script lang="ts">
  import { open as openDialog } from "@tauri-apps/plugin-dialog";
  import Icon from "../components/Icon.svelte";
  import InstanceIcon from "../components/InstanceIcon.svelte";
  import ImportLaunchersModal from "./ImportLaunchersModal.svelte";
  import { app } from "../stores/app.svelte";
  import { router } from "../stores/router.svelte";
  import { ui } from "../stores/ui.svelte";
  import { toasts } from "../stores/toasts.svelte";
  import { dialogs } from "../stores/dialogs.svelte";
  import { commands, errorMessage, instanceVersionLabel, type Instance } from "../api";
  import { formatPlayTime, formatRelative, t } from "../i18n/index.svelte";

  type Sort = "recent" | "name" | "version" | "created";
  let query = $state("");
  let sort = $state<Sort>("recent");
  let group = $state<string | null>(null);
  let favoritesOnly = $state(false);

  const groups = $derived(
    [...new Set(app.instances.map((i) => i.group).filter((g): g is string => !!g))].sort(),
  );
  const fold = (s: string) => s.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase();

  const list = $derived.by(() => {
    const q = fold(query.trim());
    const filtered = app.instances.filter(
      (i) =>
        (!favoritesOnly || i.favorite) &&
        (!group || i.group === group) &&
        (!q ||
          fold(`${i.name} ${i.gameVersion} ${i.loader} ${i.group ?? ""} ${i.tags.join(" ")}`).includes(q)),
    );
    const by: Record<Sort, (a: Instance, b: Instance) => number> = {
      recent: (a, b) => (b.lastPlayed ?? 0) - (a.lastPlayed ?? 0) || a.name.localeCompare(b.name),
      name: (a, b) => a.name.localeCompare(b.name),
      version: (a, b) => b.gameVersion.localeCompare(a.gameVersion, undefined, { numeric: true }),
      created: (a, b) => b.createdAt - a.createdAt,
    };
    // Favourites stay on top whatever the sort order.
    return [...filtered].sort((a, b) => Number(b.favorite) - Number(a.favorite) || by[sort](a, b));
  });

  let launchersOpen = $state(false);
  /** Mod updates waiting in instances that aren't running. */
  const updatable = $derived(
    Object.entries(app.contentUpdates).reduce((n, [id, c]) => n + (app.running[id] ? 0 : c), 0),
  );

  // Right-click on a card: the common actions without opening the instance.
  let menu = $state<{ inst: Instance; x: number; y: number } | null>(null);
  function openMenu(e: MouseEvent, inst: Instance) {
    e.preventDefault();
    // Keep the menu inside the window.
    menu = {
      inst,
      x: Math.min(e.clientX, window.innerWidth - 232),
      y: Math.min(e.clientY, window.innerHeight - 300),
    };
  }
  async function run(fn: () => Promise<unknown>, success?: string) {
    menu = null;
    try {
      await fn();
      if (success) toasts.success(success);
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  }
  async function clone(inst: Instance) {
    menu = null;
    const name = await dialogs.prompt({
      title: t("instances.cloneTitle"),
      value: t("instances.cloneName", { name: inst.name }),
    });
    if (!name) return;
    await run(async () => {
      await commands.cloneInstance(inst.id, name);
      await app.refreshInstances();
    });
  }
  async function remove(inst: Instance) {
    menu = null;
    const ok = await dialogs.confirm({
      title: t("instances.deleteTitle", { name: inst.name }),
      text: t("instances.deleteText"),
      confirmLabel: t("common.delete"),
      danger: true,
    });
    if (!ok) return;
    await run(async () => {
      await commands.deleteInstance(inst.id);
      await app.refreshInstances();
    });
  }

  async function importPack() {
    const file = await openDialog({
      multiple: false,
      filters: [{ name: "Modpack", extensions: ["mrpack", "zip"] }],
    });
    if (typeof file === "string") await commands.importPack(file).catch((e) => toasts.error(errorMessage(e)));
  }
</script>

<div class="page">
  <header class="page-header">
    <h1 class="grow">{t("instances.title")}</h1>
    {#if updatable}
      <button class="btn" onclick={() => (ui.updateAllOpen = true)}
        ><Icon name="arrowUp" size={16} />{t("updateAll.button", { n: updatable })}</button
      >
    {/if}
    <button class="btn" onclick={() => (launchersOpen = true)}
      ><Icon name="download" size={16} />{t("importLaunchers.button")}</button
    >
    <button class="btn" onclick={importPack}><Icon name="upload" size={16} />{t("instances.import")}</button>
    <button class="btn primary" onclick={() => (ui.newInstanceOpen = true)}
      ><Icon name="plus" size={16} />{t("instances.new")}</button
    >
  </header>

  <div class="toolbar row wrap">
    <label class="search">
      <Icon name="search" size={16} />
      <input
        class="input"
        bind:value={query}
        placeholder={t("instances.search")}
        aria-label={t("instances.search")}
      />
    </label>
    <select class="select sort" bind:value={sort} aria-label={t("mods.sort")}>
      <option value="recent">{t("instances.sortRecent")}</option>
      <option value="name">{t("instances.sortName")}</option>
      <option value="version">{t("instances.sortVersion")}</option>
      <option value="created">{t("instances.sortCreated")}</option>
    </select>
    <span class="grow"></span>
    <span class="small faint">{t("instances.count", { n: list.length })}</span>
  </div>
  {#if groups.length || app.instances.some((i) => i.favorite)}
    <div class="row wrap groups">
      <button
        class="chip"
        aria-pressed={group === null && !favoritesOnly}
        onclick={() => ((group = null), (favoritesOnly = false))}>{t("instances.allGroups")}</button
      >
      <button
        class="chip fav-chip"
        aria-pressed={favoritesOnly}
        onclick={() => (favoritesOnly = !favoritesOnly)}
        ><Icon name="star" size={14} />{t("instances.favorites")}</button
      >
      {#each groups as g (g)}
        <button class="chip" aria-pressed={group === g} onclick={() => (group = g)}>{g}</button>
      {/each}
    </div>
  {/if}

  {#if !app.instances.length}
    <div class="empty card">
      <Icon name="grid" size={40} />
      <h3>{t("instances.empty")}</h3>
      <p class="muted">{t("instances.emptyHint")}</p>
      <button class="btn primary" onclick={() => (ui.newInstanceOpen = true)}
        ><Icon name="plus" size={16} />{t("instances.new")}</button
      >
    </div>
  {:else if !list.length}
    <div class="empty"><p>{t("instances.noResults")}</p></div>
  {:else}
    <div class="grid">
      {#each list as inst (inst.id)}
        {@const running = !!app.running[inst.id]}
        {@const busy = app.isBusy(inst.id)}
        <article class="tile card" class:running data-tilt oncontextmenu={(e) => openMenu(e, inst)}>
          <button class="open" onclick={() => router.go("instance", { id: inst.id })} aria-label={inst.name}
          ></button>
          <button
            class="icon-btn sm star"
            class:on={inst.favorite}
            aria-pressed={inst.favorite}
            title={t(inst.favorite ? "instances.unfavorite" : "instances.favorite")}
            aria-label={t(inst.favorite ? "instances.unfavorite" : "instances.favorite")}
            onclick={() => app.toggleFavorite(inst.id)}><Icon name="star" size={16} /></button
          >
          <div class="row head">
            <InstanceIcon name={inst.name} loader={inst.loader} icon={inst.icon} id={inst.id} size={46} />
            <div class="col gap-1 grow" style="min-width:0">
              <strong class="truncate">{inst.name}</strong>
              <span class="small muted truncate">{instanceVersionLabel(inst)}</span>
            </div>
          </div>
          <div class="meta tiny faint">
            <span><Icon name="clock" size={13} />{formatRelative(inst.lastPlayed)}</span>
            {#if inst.playTimeSecs}<span>{formatPlayTime(inst.playTimeSecs)}</span>{/if}
            {#if inst.group}<span class="badge">{inst.group}</span>{/if}
            {#if app.contentUpdates[inst.id]}<span
                class="badge success"
                title={t("instances.modUpdates", { n: app.contentUpdates[inst.id] })}
                ><Icon name="arrowUp" size={11} />{app.contentUpdates[inst.id]}</span
              >{/if}
          </div>
          <button
            class="btn play"
            class:primary={!running}
            class:danger={running}
            disabled={busy && !running}
            onclick={() => (running ? app.stop(inst.id) : app.launch(inst.id))}
          >
            <Icon name={running ? "stop" : "play"} size={15} />{running ? t("common.stop") : t("common.play")}
          </button>
        </article>
      {/each}
    </div>
  {/if}
</div>

<svelte:window
  onkeydown={(e) => e.key === "Escape" && (menu = null)}
  onblur={() => (menu = null)}
  onresize={() => (menu = null)}
/>
{#if menu}
  {@const inst = menu.inst}
  {@const isRunning = !!app.running[inst.id]}
  <div
    class="menu-back"
    role="presentation"
    onclick={() => (menu = null)}
    oncontextmenu={(e) => (e.preventDefault(), (menu = null))}
  ></div>
  <div class="menu card raised" role="menu" style:left="{menu.x}px" style:top="{menu.y}px">
    <div class="menu-title truncate">{inst.name}</div>
    <button
      role="menuitem"
      disabled={app.isBusy(inst.id) && !isRunning}
      onclick={() => run(() => (isRunning ? app.stop(inst.id) : app.launch(inst.id)))}
      ><Icon name={isRunning ? "stop" : "play"} size={16} />{t(
        isRunning ? "common.stop" : "common.play",
      )}</button
    >
    <button role="menuitem" onclick={() => ((menu = null), router.go("instance", { id: inst.id }))}
      ><Icon name="arrowRight" size={16} />{t("common.open")}</button
    >
    <button role="menuitem" onclick={() => run(() => commands.openFolder({ kind: "gameDir", id: inst.id }))}
      ><Icon name="folder" size={16} />{t("common.openFolder")}</button
    >
    <button role="menuitem" onclick={() => run(() => app.toggleFavorite(inst.id))}
      ><Icon name="star" size={16} />{t(
        inst.favorite ? "instances.unfavorite" : "instances.favorite",
      )}</button
    >
    <button role="menuitem" onclick={() => clone(inst)}
      ><Icon name="copy" size={16} />{t("instances.clone")}</button
    >
    <button
      role="menuitem"
      onclick={() => run(() => commands.createShortcut(inst.id), t("instances.shortcutCreated"))}
      ><Icon name="link" size={16} />{t("instances.shortcut")}</button
    >
    <hr class="divider" />
    <button role="menuitem" class="danger-text" onclick={() => remove(inst)}
      ><Icon name="trash" size={16} />{t("common.delete")}</button
    >
  </div>
{/if}

<ImportLaunchersModal bind:open={launchersOpen} />

<style>
  .menu-back {
    position: fixed;
    inset: 0;
    z-index: 40;
  }
  .menu {
    position: fixed;
    z-index: 41;
    width: 14rem;
    padding: 0.35rem;
    background: var(--c-surface);
    display: flex;
    flex-direction: column;
    animation: fade-up var(--dur) var(--ease);
  }
  .menu-title {
    padding: 0.35rem 0.65rem 0.4rem;
    font-size: 0.78rem;
    font-weight: 650;
    color: var(--c-text-muted);
  }
  .menu button {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    padding: 0.5rem 0.65rem;
    border: 0;
    border-radius: var(--r-sm);
    background: transparent;
    color: var(--c-text);
    font-size: 0.9rem;
    text-align: left;
    cursor: pointer;
  }
  .menu button:hover:not(:disabled),
  .menu button:focus-visible {
    background: var(--c-hover);
  }
  .menu button:disabled {
    opacity: 0.5;
    cursor: default;
  }
  .menu .danger-text {
    color: var(--c-danger);
  }
  .toolbar {
    margin-bottom: var(--sp-3);
  }
  .search {
    position: relative;
    display: flex;
    align-items: center;
    width: min(22rem, 100%);
    color: var(--c-text-faint);
  }
  .search :global(.icon) {
    position: absolute;
    left: 0.7rem;
  }
  .search .input {
    padding-left: 2.2rem;
  }
  .sort {
    width: auto;
  }
  .groups {
    margin-bottom: var(--sp-3);
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(16rem, 1fr));
    gap: var(--sp-3);
  }
  .tile {
    position: relative;
    display: flex;
    flex-direction: column;
    gap: var(--sp-3);
    transition:
      transform var(--dur) var(--ease),
      border-color var(--dur) var(--ease);
  }
  .tile:hover {
    transform: translateY(-2px);
    border-color: color-mix(in srgb, var(--c-accent) 40%, var(--c-border));
  }
  .tile.running {
    border-color: color-mix(in srgb, var(--c-success) 50%, transparent);
  }
  .open {
    position: absolute;
    inset: 0;
    border: 0;
    background: transparent;
    border-radius: inherit;
    cursor: pointer;
  }
  /* Room for the favourite star, so long names end in an ellipsis before it. */
  .head {
    padding-right: 1.4rem;
  }
  .meta {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.8rem;
    min-height: 1.4rem;
  }
  .meta span {
    display: inline-flex;
    align-items: center;
    gap: 0.3rem;
  }
  /* Play buttons line up across a row of tiles whatever the metadata above them. */
  .play {
    position: relative;
    align-self: flex-start;
    margin-top: auto;
  }
  /* One loud button per screen ("New instance"): Play on a card is quiet until the card is
     pointed at or the button has focus. */
  .tile:not(:hover) .play.primary:not(:focus-visible) {
    background: var(--c-accent-soft);
    color: var(--c-accent);
    box-shadow: inset 0 -3px 0 color-mix(in srgb, var(--c-accent) 22%, transparent);
  }
  .star {
    position: absolute;
    top: var(--sp-2);
    right: var(--sp-2);
    opacity: 0;
    transition: opacity var(--dur) var(--ease);
  }
  .tile:hover .star,
  .star:focus-visible,
  .star.on {
    opacity: 1;
  }
  .star.on,
  .fav-chip[aria-pressed="true"] {
    color: var(--c-warning);
  }
  .star.on :global(svg),
  .fav-chip[aria-pressed="true"] :global(svg) {
    fill: currentColor;
  }
  .fav-chip {
    display: inline-flex;
    align-items: center;
    gap: 0.3rem;
  }
</style>
