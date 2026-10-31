<script lang="ts">
  import { untrack } from "svelte";
  import { open as openDialog } from "@tauri-apps/plugin-dialog";
  import { openUrl } from "@tauri-apps/plugin-opener";
  import Icon from "../../components/Icon.svelte";
  import { router } from "../../stores/router.svelte";
  import { ui } from "../../stores/ui.svelte";
  import { app } from "../../stores/app.svelte";
  import { dialogs } from "../../stores/dialogs.svelte";
  import { toasts } from "../../stores/toasts.svelte";
  import {
    commands,
    errorMessage,
    providerImg,
    type ContentEntry,
    type ContentKind,
    type Instance,
    type UpdateInfo,
  } from "../../api";
  import { formatBytes, t } from "../../i18n/index.svelte";

  let { instance }: { instance: Instance } = $props();

  const kinds: { kind: ContentKind; key: string; icon: string }[] = [
    { kind: "mod", key: "instance.content.mods", icon: "package" },
    { kind: "resourcePack", key: "instance.content.resourcePacks", icon: "image" },
    { kind: "shader", key: "instance.content.shaders", icon: "sparkles" },
    { kind: "dataPack", key: "instance.content.dataPacks", icon: "database" },
  ];
  let kind = $state<ContentKind>(untrack(() => instance.loader) === "vanilla" ? "resourcePack" : "mod");
  let entries = $state.raw<ContentEntry[]>([]);
  let filter = $state("");
  let loading = $state(true);
  let updates = $state.raw<UpdateInfo[] | null>(null);
  let checking = $state(false);

  async function load() {
    try {
      entries = await commands.listContent(instance.id);
    } catch (e) {
      toasts.error(errorMessage(e));
    } finally {
      loading = false;
    }
  }

  $effect(() => {
    void load();
  });

  // Content changes when install/update tasks for this instance finish.
  $effect(() => {
    const done = app.tasks.filter(
      (x) =>
        x.instanceId === instance.id && x.status === "done" && (x.kind === "install" || x.kind === "update"),
    ).length;
    if (done) void load();
  });

  $effect(() => {
    ui.dropHandler = (paths) => {
      const jars = paths.filter((p) => p.toLowerCase().endsWith(".jar"));
      const zips = paths.filter((p) => p.toLowerCase().endsWith(".zip"));
      if (!jars.length && !(zips.length && kind !== "mod")) return false;
      void (async () => {
        try {
          if (jars.length) await commands.addContentFiles(instance.id, "mod", jars);
          if (zips.length && kind !== "mod") await commands.addContentFiles(instance.id, kind, zips);
          await load();
        } catch (e) {
          toasts.error(errorMessage(e));
        }
      })();
      return true;
    };
    return () => (ui.dropHandler = null);
  });

  const visible = $derived(
    entries.filter(
      (e) =>
        e.kind === kind &&
        (!filter || `${e.title} ${e.fileName}`.toLowerCase().includes(filter.toLowerCase())),
    ),
  );
  const count = (k: ContentKind) => entries.filter((e) => e.kind === k).length;
  const updateFor = (e: ContentEntry) => updates?.find((u) => u.kind === e.kind && u.fileName === e.fileName);

  async function toggle(e: ContentEntry) {
    try {
      await commands.setContentEnabled(instance.id, e.kind, e.fileName, !e.enabled);
      entries = entries.map((x) => (x === e ? { ...x, enabled: !x.enabled } : x));
    } catch (err) {
      toasts.error(errorMessage(err));
    }
  }

  async function remove(e: ContentEntry) {
    if (
      !(await dialogs.confirm({
        title: t("instance.content.removeTitle", { name: e.title }),
        confirmLabel: t("common.remove"),
        danger: true,
      }))
    )
      return;
    try {
      await commands.removeContent(instance.id, e.kind, e.fileName);
      entries = entries.filter((x) => x !== e);
    } catch (err) {
      toasts.error(errorMessage(err));
    }
  }

  async function addFiles() {
    const ext = kind === "mod" ? ["jar"] : ["zip"];
    const files = await openDialog({
      multiple: true,
      filters: [{ name: t(kinds.find((k) => k.kind === kind)?.key ?? ""), extensions: ext }],
    });
    if (!files) return;
    try {
      await commands.addContentFiles(instance.id, kind, Array.isArray(files) ? files : [files]);
      await load();
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  }

  async function checkUpdates() {
    checking = true;
    try {
      updates = await commands.checkContentUpdates(instance.id);
      if (!updates.length) toasts.success(t("instance.content.noUpdates"));
    } catch (e) {
      toasts.error(errorMessage(e));
    } finally {
      checking = false;
    }
  }

  async function updateAll() {
    if (!updates?.length) return;
    try {
      await commands.applyContentUpdates(instance.id, updates);
      updates = null;
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  }

  async function undo() {
    try {
      if (await commands.undoContentUpdate(instance.id)) {
        toasts.success(t("instance.content.undone"));
        await load();
      }
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  }

  async function identify() {
    try {
      const n = await commands.identifyContent(instance.id);
      toasts.success(t("instance.content.identified", { n }));
      await load();
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  }
</script>

<div class="content col gap-3">
  <div class="row wrap between">
    <div class="segmented" role="tablist">
      {#each kinds as k (k.kind)}
        <button role="tab" aria-selected={kind === k.kind} onclick={() => (kind = k.kind)}>
          {t(k.key)} <span class="faint">{count(k.kind)}</span>
        </button>
      {/each}
    </div>
    <div class="row wrap">
      <!-- Nothing to update or identify in an empty instance. -->
      {#if entries.length}
        <button class="btn sm" onclick={checkUpdates} disabled={checking}
          ><Icon name="refresh" size={15} />{checking
            ? t("instance.content.checking")
            : t("instance.content.checkUpdates")}</button
        >
      {/if}
      {#if updates?.length}
        <button class="btn sm primary" onclick={updateAll}
          ><Icon name="download" size={15} />{t("instance.content.updateAll", { n: updates.length })}</button
        >
      {/if}
      <button
        class="icon-btn sm"
        title={t("instance.content.undo")}
        aria-label={t("instance.content.undo")}
        onclick={undo}><Icon name="undo" size={16} /></button
      >
      {#if entries.length}
        <button
          class="icon-btn sm"
          title={t("instance.content.identify")}
          aria-label={t("instance.content.identify")}
          onclick={identify}><Icon name="search" size={16} /></button
        >
      {/if}
      <button
        class="icon-btn sm"
        title={t("common.openFolder")}
        aria-label={t("common.openFolder")}
        onclick={() => commands.openFolder({ kind: "content", id: instance.id, content: kind })}
        ><Icon name="folder" size={16} /></button
      >
    </div>
  </div>

  <div class="row wrap">
    <input
      class="input filter"
      bind:value={filter}
      placeholder={t("instance.content.search")}
      aria-label={t("instance.content.search")}
    />
    <span class="grow"></span>
    <button class="btn sm" onclick={addFiles}
      ><Icon name="upload" size={15} />{t("instance.content.addFiles")}</button
    >
    <button class="btn sm primary" onclick={() => router.go("mods", { instance: instance.id, kind })}
      ><Icon name="plus" size={15} />{t("instance.content.add")}</button
    >
  </div>
  {#if kind === "dataPack"}<p class="hint">{t("instance.content.dataPacksHint")}</p>{/if}

  {#if loading}
    {#each [1, 2, 3] as n (n)}<div class="skeleton" style="height: 3.4rem"></div>{/each}
  {:else if !visible.length}
    <div class="empty card">
      <Icon name="layers" size={34} />
      <strong>{t("instance.content.empty")}</strong>
      <span class="small">{t("instance.content.emptyHint")}</span>
    </div>
  {:else}
    <ul class="list">
      {#each visible as e (e.kind + e.fileName)}
        {@const upd = updateFor(e)}
        <li class:disabled={!e.enabled}>
          {#if e.iconUrl}
            <img class="ic" src={providerImg(e.provider ?? "modrinth", e.iconUrl)} alt="" loading="lazy" />
          {:else}
            <span class="ic placeholder"
              ><Icon name={kinds.find((k) => k.kind === e.kind)?.icon ?? "package"} size={18} /></span
            >
          {/if}
          <div class="col gap-1 grow" style="min-width:0">
            <div class="row" style="gap:0.4rem">
              <strong class="truncate">{e.title}</strong>
              {#if e.dependency}<span class="badge">{t("instance.content.dependency")}</span>{/if}
              {#if upd}<span class="badge success">↑ {upd.version.versionNumber}</span>{/if}
            </div>
            <span class="tiny faint truncate mono"
              >{e.versionNumber ?? e.fileName} · {formatBytes(e.size)}</span
            >
          </div>
          <span class="badge" class:accent={!!e.provider}
            >{e.provider === "modrinth"
              ? "Modrinth"
              : e.provider === "curseforge"
                ? "CurseForge"
                : t("instance.content.local")}</span
          >
          {#if e.pageUrl}
            <button class="icon-btn sm" aria-label={t("common.open")} onclick={() => openUrl(e.pageUrl ?? "")}
              ><Icon name="external" size={15} /></button
            >
          {/if}
          <button
            class="switch-mini"
            role="switch"
            aria-checked={e.enabled}
            aria-label={e.enabled ? t("instance.content.disable") : t("instance.content.enable")}
            onclick={() => toggle(e)}><span></span></button
          >
          <button class="icon-btn sm" aria-label={t("common.remove")} onclick={() => remove(e)}
            ><Icon name="trash" size={15} /></button
          >
        </li>
      {/each}
    </ul>
  {/if}
</div>

<style>
  .filter {
    width: min(18rem, 100%);
  }
  .list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 0.35rem;
  }
  li {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    padding: 0.55rem 0.75rem;
    border-radius: var(--r-md);
    background: var(--c-glass);
    border: 1px solid var(--c-border);
  }
  li.disabled {
    opacity: 0.55;
  }
  .ic {
    width: 2.3rem;
    height: 2.3rem;
    flex: none;
    border-radius: var(--r-sm);
    object-fit: cover;
    background: var(--c-surface-3);
  }
  .placeholder {
    display: grid;
    place-items: center;
    color: var(--c-text-faint);
  }
  .switch-mini {
    flex: none;
    position: relative;
    width: 2.2rem;
    height: 1.25rem;
    border-radius: 99px;
    border: 1px solid var(--c-border);
    background: var(--c-surface-3);
    cursor: pointer;
    transition: background var(--dur) var(--ease);
  }
  .switch-mini[aria-checked="true"] {
    background: var(--c-accent);
  }
  .switch-mini span {
    position: absolute;
    top: 50%;
    left: 0.15rem;
    width: 0.9rem;
    height: 0.9rem;
    border-radius: 50%;
    background: #fff;
    transform: translateY(-50%);
    transition: left var(--dur) var(--ease);
  }
  .switch-mini[aria-checked="true"] span {
    left: calc(100% - 1.05rem);
  }
</style>
