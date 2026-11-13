<script lang="ts">
  import Icon from "../components/Icon.svelte";
  import VirtualList from "../components/VirtualList.svelte";
  import ProjectView from "./mods/ProjectView.svelte";
  import InstallPlanModal from "./mods/InstallPlanModal.svelte";
  import { toasts } from "../stores/toasts.svelte";
  import { app } from "../stores/app.svelte";
  import { router } from "../stores/router.svelte";
  import {
    commands,
    errorMessage,
    loaderLabel,
    providerImg,
    type Category,
    type ContentKind,
    type ModSource,
    type ProjectSummary,
    type Provider,
    type SortOrder,
  } from "../api";
  import { formatNumber, formatRelative, t } from "../i18n/index.svelte";

  const kinds: { kind: ContentKind; key: string; icon: string }[] = [
    { kind: "mod", key: "mods.kinds.mod", icon: "package" },
    { kind: "modpack", key: "mods.kinds.modpack", icon: "layers" },
    { kind: "resourcePack", key: "mods.kinds.resourcePack", icon: "image" },
    { kind: "shader", key: "mods.kinds.shader", icon: "sparkles" },
    { kind: "dataPack", key: "mods.kinds.dataPack", icon: "database" },
  ];

  let targetId = $state<string>(router.params.instance ?? app.lastInstance?.id ?? "");
  let kind = $state<ContentKind>((router.params.kind as ContentKind) ?? "mod");
  let source = $state<ModSource>(app.settings?.modSource ?? "modrinth");
  // A search can be preset by other pages (e.g. a missing dependency's id).
  let query = $state(router.params.q ?? "");
  let debounced = $state(router.params.q ?? "");
  let sort = $state<SortOrder>("relevance");
  let categories = $state<string[]>([]);
  let categoryList = $state.raw<Category[]>([]);
  let hits = $state.raw<ProjectSummary[]>([]);
  let total = $state(0);
  let loading = $state(false);
  let error = $state<string | null>(null);
  let cfKey = $state(false);
  /** Shown after picking CurseForge without an API key: what to do instead of a dead button. */
  let cfPrompt = $state(false);
  let catsOpen = $state(false);
  let selected = $state.raw<ProjectSummary | null>(null);
  let installed = $state.raw<Set<string>>(new Set());
  let searchInput: HTMLInputElement | undefined = $state();

  const target = $derived(app.instances.find((i) => i.id === targetId) ?? null);
  // Modpacks create their own instance, so they are not filtered by the target instance.
  const filterVersion = $derived(kind === "modpack" ? null : (target?.gameVersion ?? null));
  const filterLoader = $derived(
    kind === "modpack" || !target || target.loader === "vanilla" ? null : target.loader,
  );
  function pickSource(next: ModSource) {
    cfPrompt = !cfKey;
    if (cfKey) source = next;
  }
  const effectiveSource = $derived<ModSource>(!cfKey && source !== "modrinth" ? "modrinth" : source);
  const categoryProvider = $derived<Provider>(effectiveSource === "curseforge" ? "curseforge" : "modrinth");

  $effect(() => {
    commands
      .curseforgeStatus()
      .then((s) => (cfKey = s.configured))
      .catch(() => {});
  });

  let timer: ReturnType<typeof setTimeout> | undefined;
  $effect(() => {
    const q = query;
    clearTimeout(timer);
    timer = setTimeout(() => (debounced = q), 300);
    return () => clearTimeout(timer);
  });

  $effect(() => {
    const k = kind;
    const p = categoryProvider;
    categories = [];
    commands
      .getCategories(p, k)
      .then((c) => (categoryList = c))
      .catch(() => (categoryList = []));
  });

  $effect(() => {
    const id = targetId;
    if (!id) return void (installed = new Set());
    commands
      .listContent(id)
      .then(
        (list) =>
          (installed = new Set(list.filter((e) => e.projectId).map((e) => `${e.provider}:${e.projectId}`))),
      )
      .catch(() => {});
  });

  let generation = 0;
  async function search(reset: boolean) {
    const gen = reset ? ++generation : generation;
    loading = true;
    error = null;
    try {
      const page = await commands.searchProjects(
        {
          query: debounced,
          kind,
          gameVersion: filterVersion,
          loader: filterLoader,
          categories,
          sort,
          offset: reset ? 0 : hits.length,
          limit: 30,
        },
        effectiveSource,
      );
      if (gen !== generation) return;
      hits = reset ? page.hits : [...hits, ...page.hits];
      total = page.total;
    } catch (e) {
      if (gen === generation) error = errorMessage(e);
    } finally {
      if (gen === generation) loading = false;
    }
  }

  $effect(() => {
    void [debounced, kind, effectiveSource, sort, categories, filterVersion, filterLoader];
    void search(true);
  });

  /** Projects being added with the side button right now. */
  let quickBusy = $state.raw<string[]>([]);
  /** A quick install that needs a decision (optional or conflicting mods) goes through the full plan. */
  let planFor = $state.raw<ProjectSummary | null>(null);

  const setBusy = (key: string, on: boolean) => {
    quickBusy = on ? [...quickBusy, key] : quickBusy.filter((k) => k !== key);
  };

  /** One click on the side button: install the newest compatible version, with its required mods. */
  async function quick(p: ProjectSummary) {
    const key = `${p.provider}:${p.id}`;
    if (quickBusy.includes(key)) return;
    if (kind !== "modpack" && !target) {
      toasts.info(t("mods.pickInstance"));
      return;
    }
    setBusy(key, true);
    try {
      if (kind === "modpack") {
        await commands.installModpack(p.provider, p.id, null, p.title);
        toasts.info(t("mods.quickStarted", { name: p.title }));
        return;
      }
      const plan = await commands.planContentInstall(target!.id, p.provider, p.id, null, kind);
      if (plan.optional.length || plan.incompatible.length || plan.unavailable.length) {
        planFor = p;
        return;
      }
      await commands.installContent(target!.id, p.provider, p.id, null, kind, [], p.title);
      installed = new Set([...installed, key]);
      toasts.success(t("mods.quickDone", { name: p.title, target: target!.name }));
    } catch (e) {
      toasts.error(errorMessage(e));
    } finally {
      setBusy(key, false);
    }
  }

  function more() {
    if (!loading && hits.length < total) void search(false);
  }

  function toggleCategory(id: string) {
    categories =
      categoryProvider === "curseforge"
        ? categories.includes(id)
          ? []
          : [id]
        : categories.includes(id)
          ? categories.filter((c) => c !== id)
          : [...categories, id];
  }

  function onkeydown(e: KeyboardEvent) {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "f") {
      searchInput?.focus();
      e.preventDefault();
    }
  }
</script>

<svelte:window {onkeydown} />

<div class="page mods">
  <header class="page-header">
    <h1>{t("mods.title")}</h1>
    <span class="grow"></span>
    <label class="target">
      <span class="small muted">{t("mods.target")}</span>
      <select class="select" bind:value={targetId}>
        <option value="">{t("mods.noTarget")}</option>
        {#each app.instances as i (i.id)}<option value={i.id}
            >{i.name}{i.name.includes(i.gameVersion) ? "" : ` (${i.gameVersion})`}</option
          >{/each}
      </select>
    </label>
    <div class="segmented" role="group" aria-label={t("mods.source")}>
      <button
        aria-pressed={effectiveSource === "modrinth"}
        onclick={() => {
          source = "modrinth";
          cfPrompt = false;
        }}>Modrinth</button
      >
      <button aria-pressed={effectiveSource === "curseforge"} onclick={() => pickSource("curseforge")}
        >CurseForge</button
      >
      <button aria-pressed={effectiveSource === "both"} onclick={() => pickSource("both")}
        >{t("mods.sourceBoth")}</button
      >
    </div>
  </header>

  {#if cfPrompt}
    <div class="alert info">
      <Icon name="info" size={18} /><span class="grow">{t("mods.cfNoKey")}</span>
      <button class="btn sm" onclick={() => router.go("settings")}>{t("mods.openSettings")}</button>
      <button class="icon-btn sm" aria-label={t("common.close")} onclick={() => (cfPrompt = false)}
        ><Icon name="x" size={15} /></button
      >
    </div>
  {/if}

  <div class="kinds row wrap" role="tablist">
    {#each kinds as k (k.kind)}
      <button class="chip" role="tab" aria-selected={kind === k.kind} onclick={() => (kind = k.kind)}
        ><Icon name={k.icon} size={15} />{t(k.key)}</button
      >
    {/each}
  </div>

  <div class="bar row wrap">
    <label class="search grow">
      <Icon name="search" size={17} />
      <input
        bind:this={searchInput}
        class="input"
        bind:value={query}
        placeholder={t("mods.search", { kind: t(`mods.kinds.${kind}`).toLowerCase() })}
        aria-label={t("common.search")}
      />
    </label>
    <select class="select sort" bind:value={sort} aria-label={t("mods.sort")}>
      <option value="relevance">{t("mods.sortRelevance")}</option>
      <option value="downloads">{t("mods.sortDownloads")}</option>
      <option value="updated">{t("mods.sortUpdated")}</option>
      <option value="newest">{t("mods.sortNewest")}</option>
    </select>
  </div>

  <div class="filters row wrap small">
    {#if filterVersion}
      <span class="badge accent"
        ><Icon name="filter" size={12} />{t("mods.filtersFor", {
          version: filterVersion,
          loader: filterLoader ? loaderLabel(filterLoader) : loaderLabel("vanilla"),
        })}</span
      >
    {:else if kind !== "modpack"}
      <span class="faint">{t("mods.pickInstance")}</span>
    {/if}
    <span class="grow"></span>
    <span class="faint">{t("mods.results", { n: total })}</span>
  </div>

  {#if categoryList.length}
    <div class="cats-wrap row">
      <div class="cats row wrap" class:open={catsOpen}>
        {#each categoryList.slice(0, 40) as c (c.id)}
          <button class="chip" aria-pressed={categories.includes(c.id)} onclick={() => toggleCategory(c.id)}
            >{c.name.replace(/-/g, " ")}</button
          >
        {/each}
      </div>
      <button class="btn sm ghost more" aria-expanded={catsOpen} onclick={() => (catsOpen = !catsOpen)}
        >{catsOpen ? t("mods.lessCategories") : t("mods.moreCategories")}<Icon
          name={catsOpen ? "chevronUp" : "chevronDown"}
          size={14}
        /></button
      >
    </div>
  {/if}

  <div class="results">
    {#if error}
      <div class="alert danger">
        <Icon name="alert" size={18} /><span class="grow">{error}</span><button
          class="btn sm"
          onclick={() => search(true)}>{t("common.retry")}</button
        >
      </div>
    {:else if !hits.length && loading}
      {#each [1, 2, 3, 4, 5] as n (n)}<div
          class="skeleton"
          style="height: 5.6rem; margin-bottom: 0.5rem"
        ></div>{/each}
    {:else if !hits.length}
      <div class="empty">
        <Icon name="search" size={32} />
        <p>{t("mods.noResults")}</p>
      </div>
    {:else}
      <VirtualList items={hits} rowHeight={100} onend={more}>
        {#snippet row(p: ProjectSummary)}
          {@const isInstalled = installed.has(`${p.provider}:${p.id}`)}
          <div class="hit-wrap">
            <button class="hit" onclick={() => (selected = p)}>
              {#if p.iconUrl}
                <img src={providerImg(p.provider, p.iconUrl)} alt="" loading="lazy" />
              {:else}
                <span class="noimg"><Icon name="package" size={26} /></span>
              {/if}
              <span class="col gap-1 grow info">
                <span class="row title-row">
                  <strong class="truncate">{p.title}</strong>
                  <span class="tiny faint truncate">{t("mods.byAuthor", { author: p.author })}</span>
                </span>
                <span class="desc small muted">{p.description}</span>
                <span class="row tiny faint meta">
                  <span><Icon name="download" size={12} />{formatNumber(p.downloads, true)}</span>
                  {#if p.updated}<span><Icon name="clock" size={12} />{formatRelative(p.updated)}</span>{/if}
                  <span class="badge src {p.provider}"
                    >{p.provider === "modrinth" ? "Modrinth" : "CurseForge"}</span
                  >
                  {#if p.alsoOn}<span class="badge"
                      >+ {p.alsoOn.provider === "modrinth" ? "Modrinth" : "CurseForge"}</span
                    >{/if}
                  {#each p.categories.slice(0, 3) as c (c)}<span class="cat">{c}</span>{/each}
                </span>
              </span>
              {#if isInstalled}<span class="badge success"
                  ><Icon name="check" size={12} />{t("common.installed")}</span
                >{/if}
            </button>
            {#if kind === "modpack" || !isInstalled}
              <button
                class="quick btn primary"
                disabled={quickBusy.includes(`${p.provider}:${p.id}`)}
                aria-label={kind === "modpack"
                  ? t("mods.installNewInstance")
                  : target
                    ? t("mods.installTo", { name: target.name })
                    : t("mods.pickInstance")}
                title={kind === "modpack"
                  ? t("mods.installNewInstance")
                  : target
                    ? t("mods.installTo", { name: target.name })
                    : t("mods.pickInstance")}
                onclick={() => quick(p)}
              >
                {#if quickBusy.includes(`${p.provider}:${p.id}`)}<span class="spinner sm"></span>{:else}<Icon
                    name="download"
                    size={18}
                  />{/if}
              </button>
            {/if}
          </div>
        {/snippet}
      </VirtualList>
      {#if loading}<div class="more faint small">{t("common.loading")}</div>{/if}
    {/if}
  </div>
</div>

{#if planFor && target}
  <InstallPlanModal
    summary={planFor}
    instance={target}
    {kind}
    versionId={null}
    onclose={() => (planFor = null)}
    ondone={() => {
      const done = planFor;
      planFor = null;
      if (done) installed = new Set([...installed, `${done.provider}:${done.id}`]);
    }}
  />
{/if}

{#if selected}
  <ProjectView
    summary={selected}
    target={kind === "modpack" ? null : target}
    {kind}
    installed={installed.has(`${selected.provider}:${selected.id}`)}
    onclose={() => (selected = null)}
    oninstalled={(pid) => (installed = new Set([...installed, pid]))}
  />
{/if}

<style>
  /* Many results, one screen: the install button is quiet until its row is pointed at. */
  .results :global(.quick.btn.primary:not(:hover):not(:focus-visible)) {
    background: var(--c-accent-soft);
    color: var(--c-accent);
    box-shadow: inset 0 -3px 0 color-mix(in srgb, var(--c-accent) 22%, transparent);
  }

  .mods {
    display: flex;
    flex-direction: column;
    gap: var(--sp-2);
    padding-bottom: var(--sp-3);
  }
  .page-header {
    flex-wrap: wrap;
    margin-bottom: var(--sp-2);
  }
  .target {
    display: flex;
    align-items: center;
    gap: 0.5rem;
  }
  .target .select {
    width: auto;
    max-width: 18rem;
  }
  .search {
    position: relative;
    display: flex;
    align-items: center;
    color: var(--c-text-faint);
  }
  .search :global(.icon) {
    position: absolute;
    left: 0.75rem;
  }
  .search .input {
    padding-left: 2.4rem;
    height: calc(var(--control-h) + 0.3rem);
    font-size: 1rem;
  }
  .sort {
    width: auto;
  }
  .filters .badge :global(.icon) {
    margin-right: 0.15rem;
  }
  .cats-wrap {
    align-items: flex-start;
  }
  /* One row of categories until expanded; the rest are a click away instead of off screen. */
  .cats {
    flex: 1;
    min-width: 0;
    max-height: 2rem;
    overflow: hidden;
  }
  .cats.open {
    max-height: none;
  }
  .more {
    flex: none;
  }
  .cats .chip {
    text-transform: capitalize;
  }
  .results {
    flex: 1;
    min-height: 0;
  }
  .hit-wrap {
    position: relative;
    width: 100%;
    height: 92px;
  }
  /* The side button: add the newest compatible version in one click. */
  .quick {
    position: absolute;
    right: 0.8rem;
    top: 50%;
    width: 2.6rem;
    height: 2.6rem;
    padding: 0;
    border-radius: var(--r-md);
    transform: translateY(-50%);
  }
  :global([data-spring="on"]) .quick:hover:not(:disabled) {
    transform: translateY(-50%) scale(1.1);
  }
  :global([data-spring="on"]) .quick:active:not(:disabled) {
    transform: translateY(-50%) scale(0.9);
  }
  .hit {
    padding-right: 4.2rem !important;
    display: flex;
    align-items: center;
    gap: var(--sp-3);
    width: 100%;
    height: 92px;
    padding: 0.7rem 0.9rem;
    border: 1px solid var(--c-border);
    border-radius: var(--r-lg);
    background: var(--c-glass);
    text-align: left;
    cursor: pointer;
    transition:
      border-color var(--dur) var(--ease),
      background var(--dur) var(--ease);
  }
  .hit:hover {
    border-color: color-mix(in srgb, var(--c-accent) 45%, var(--c-border));
    background: var(--c-glass-2);
  }
  .hit img,
  .noimg {
    width: 64px;
    height: 64px;
    flex: none;
    border-radius: var(--r-md);
    object-fit: cover;
    background: var(--c-surface-3);
  }
  .noimg {
    display: grid;
    place-items: center;
    color: var(--c-text-faint);
  }
  .info {
    min-width: 0;
  }
  .title-row {
    gap: 0.5rem;
  }
  .desc {
    max-width: 78ch;
    display: -webkit-box;
    -webkit-line-clamp: 1;
    line-clamp: 1;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
  .meta {
    gap: 0.75rem;
    flex-wrap: nowrap;
    overflow: hidden;
  }
  .meta > span {
    display: inline-flex;
    align-items: center;
    gap: 0.25rem;
  }
  .src.modrinth {
    color: var(--c-brand-modrinth);
    background: color-mix(in srgb, var(--c-brand-modrinth) 14%, transparent);
  }
  .src.curseforge {
    color: var(--c-brand-curseforge);
    background: color-mix(in srgb, var(--c-brand-curseforge) 14%, transparent);
  }
  .cat {
    text-transform: capitalize;
  }
  .more {
    text-align: center;
    padding: 0.5rem;
  }
</style>
