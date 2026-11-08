<script lang="ts">
  // Mod updates of every instance in one window: what changes (old → new version) per instance
  // and per mod, then one task updates the chosen ones, one instance after another. Worlds are
  // backed up first and each instance's Content tab can undo its update.
  import Modal from "./Modal.svelte";
  import Icon from "./Icon.svelte";
  import { app } from "../stores/app.svelte";
  import { toasts } from "../stores/toasts.svelte";
  import { commands, errorMessage, type UpdateInfo } from "../api";
  import { t } from "../i18n/index.svelte";

  let { open = $bindable(false) }: { open?: boolean } = $props();

  interface Row {
    id: string;
    name: string;
    running: boolean;
    updates: { u: UpdateInfo; on: boolean }[];
  }
  let rows = $state<Row[]>([]);
  let loading = $state(false);
  let checking = $state(false);
  let busy = $state(false);

  /** The updates found so far (no network), for every instance that has some. */
  async function load() {
    loading = true;
    try {
      const out: Row[] = [];
      for (const inst of app.instances) {
        if (!app.contentUpdates[inst.id]) continue;
        const list = await commands.pendingContentUpdates(inst.id).catch(() => [] as UpdateInfo[]);
        if (list.length) {
          out.push({
            id: inst.id,
            name: inst.name,
            running: !!app.running[inst.id],
            updates: list.map((u) => ({ u, on: true })),
          });
        }
      }
      rows = out;
    } finally {
      loading = false;
    }
  }

  /** The lists can be hours old: look again, instance by instance, before updating. */
  async function checkNow() {
    checking = true;
    try {
      for (const inst of app.instances) {
        if (app.running[inst.id]) continue;
        await commands.checkContentUpdates(inst.id).catch(() => []);
      }
      await load();
      if (!rows.length) toasts.success(t("updateAll.none"));
    } finally {
      checking = false;
    }
  }

  $effect(() => {
    if (open) void load();
  });

  const chosen = $derived(rows.filter((r) => !r.running).flatMap((r) => r.updates.filter((x) => x.on)));
  const chosenInstances = $derived(rows.filter((r) => !r.running && r.updates.some((x) => x.on)).length);

  function setAll(row: Row, on: boolean) {
    for (const x of row.updates) x.on = on;
  }

  async function go() {
    busy = true;
    try {
      const items = rows
        .filter((r) => !r.running)
        .map((r) => ({ instanceId: r.id, updates: r.updates.filter((x) => x.on).map((x) => x.u) }))
        .filter((i) => i.updates.length);
      await commands.applyAllContentUpdates(items);
      toasts.info(t("updateAll.started", { n: chosen.length }));
      open = false;
    } catch (e) {
      toasts.error(errorMessage(e));
    } finally {
      busy = false;
    }
  }

  const version = (u: UpdateInfo) => u.version.versionNumber ?? u.version.name;
</script>

<Modal bind:open title={t("updateAll.title")} width="40rem">
  <div class="col gap-3">
    {#if loading}
      <div class="row small muted"><span class="spinner"></span>{t("common.loading")}</div>
    {:else if !rows.length}
      <p class="muted">{t("updateAll.none")}</p>
    {:else}
      <p class="small muted">{t("updateAll.hint")}</p>
      {#each rows as row (row.id)}
        <section class="inst card flat" class:skip={row.running}>
          <label class="row head">
            <input
              type="checkbox"
              disabled={row.running}
              checked={row.updates.every((x) => x.on)}
              onchange={(e) => setAll(row, e.currentTarget.checked)}
            />
            <strong class="grow truncate">{row.name}</strong>
            {#if row.running}<span class="badge warn">{t("updateAll.running")}</span>{/if}
            <span class="badge success"><Icon name="arrowUp" size={11} />{row.updates.length}</span>
          </label>
          <ul>
            {#each row.updates as x (x.u.fileName)}
              <li>
                <label class="row small">
                  <input type="checkbox" disabled={row.running} bind:checked={x.on} />
                  <span class="truncate grow">{x.u.title}</span>
                  <span class="muted mono ver truncate"
                    >{x.u.current ?? "?"} <Icon name="arrowRight" size={11} /> {version(x.u)}</span
                  >
                </label>
              </li>
            {/each}
          </ul>
        </section>
      {/each}
      <p class="tiny faint">{t("updateAll.safety")}</p>
    {/if}
  </div>
  {#snippet footer()}
    <button class="btn ghost" onclick={checkNow} disabled={checking || busy}
      ><Icon name="refresh" size={14} />{checking
        ? t("instance.content.checking")
        : t("updateAll.checkNow")}</button
    >
    <span class="grow"></span>
    <button class="btn ghost" onclick={() => (open = false)}>{t("common.cancel")}</button>
    <button class="btn primary" disabled={busy || !chosen.length} onclick={go}
      ><Icon name="download" size={15} />{t("updateAll.go", { n: chosen.length, k: chosenInstances })}</button
    >
  {/snippet}
</Modal>

<style>
  .inst {
    padding: var(--sp-3);
  }
  .inst.skip {
    opacity: 0.6;
  }
  .head {
    gap: var(--sp-2);
    cursor: pointer;
  }
  ul {
    list-style: none;
    margin: var(--sp-2) 0 0;
    padding: 0 0 0 1.6rem;
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
    max-height: 11rem;
    overflow-y: auto;
  }
  .ver {
    max-width: 55%;
    display: inline-flex;
    align-items: center;
    gap: 0.3rem;
  }
</style>
