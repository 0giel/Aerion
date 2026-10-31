<script lang="ts">
  // Worlds of the instance and their zip backups (manual, automatic, restore).
  import Icon from "../../components/Icon.svelte";
  import { app } from "../../stores/app.svelte";
  import { dialogs } from "../../stores/dialogs.svelte";
  import { toasts } from "../../stores/toasts.svelte";
  import { commands, errorMessage, worldIconUrl, type Instance } from "../../api";
  import type { WorldBackup, WorldInfo } from "../../bindings";
  import { formatRelative, t } from "../../i18n/index.svelte";

  let { instance }: { instance: Instance } = $props();
  let worlds = $state.raw<WorldInfo[] | null>(null);
  let backups = $state.raw<WorldBackup[]>([]);
  let busy = $state<string | null>(null);
  const running = $derived(!!app.running[instance.id]);

  const size = (b: number) =>
    b > 1 << 30 ? `${(b / (1 << 30)).toFixed(1)} GB` : `${Math.max(0.1, b / (1 << 20)).toFixed(1)} MB`;

  async function load() {
    const id = instance.id;
    [worlds, backups] = await Promise.all([
      commands.listWorlds(id).catch(() => []),
      commands.listWorldBackups(id).catch(() => []),
    ]);
  }
  $effect(() => {
    void instance.id;
    void load();
  });

  async function run(key: string, fn: () => Promise<unknown>, ok?: string) {
    busy = key;
    try {
      await fn();
      if (ok) toasts.success(ok);
      await load();
    } catch (e) {
      toasts.error(errorMessage(e));
    } finally {
      busy = null;
    }
  }

  async function restore(b: WorldBackup) {
    const ok = await dialogs.confirm({
      title: t("instance.worlds.restoreTitle", { world: b.world }),
      text: t("instance.worlds.restoreText"),
      confirmLabel: t("instance.worlds.restore"),
    });
    if (ok)
      await run(
        `r:${b.file}`,
        () => commands.restoreWorldBackup(instance.id, b.file),
        t("instance.worlds.restored"),
      );
  }

  async function remove(b: WorldBackup) {
    const ok = await dialogs.confirm({
      title: t("instance.worlds.deleteBackupTitle"),
      confirmLabel: t("common.delete"),
      danger: true,
    });
    if (ok) await run(`d:${b.file}`, () => commands.deleteWorldBackup(instance.id, b.file));
  }

  const backupsOf = (folder: string) => backups.filter((b) => b.world === folder);
  const orphaned = $derived(backups.filter((b) => !worlds?.some((w) => w.folder === b.world)));
</script>

<div class="col gap-3">
  <div class="row">
    <span class="hint grow">{t("instance.worlds.hint")}</span>
    <button class="btn sm ghost" onclick={() => commands.openFolder({ kind: "gameDir", id: instance.id })}
      ><Icon name="folder" size={14} />{t("common.openFolder")}</button
    >
  </div>
  {#if worlds === null}
    <div class="empty"><span class="spinner"></span></div>
  {:else if !worlds.length && !orphaned.length}
    <div class="empty card">
      <Icon name="globe" size={32} />
      <p class="muted">{t("instance.worlds.empty")}</p>
    </div>
  {:else}
    {#each worlds as w (w.folder)}
      {@const list = backupsOf(w.folder)}
      <article class="world card col gap-2">
        <div class="row">
          {#if w.hasIcon}<img class="wicon" src={worldIconUrl(instance.id, w.folder)} alt="" />{:else}<span
              class="wicon"><Icon name="globe" size={20} /></span
            >{/if}
          <div class="col gap-1 grow" style="min-width:0">
            <strong class="truncate">{w.name}</strong>
            <span class="tiny faint truncate"
              >{w.folder} · {formatRelative(w.lastPlayed)} · {size(w.sizeBytes)}</span
            >
          </div>
          <button
            class="btn sm"
            disabled={!!busy}
            onclick={() =>
              run(
                `b:${w.folder}`,
                () => commands.backupWorld(instance.id, w.folder),
                t("instance.worlds.backedUp"),
              )}
            >{#if busy === `b:${w.folder}`}<span class="spinner"></span>{:else}<Icon
                name="download"
                size={14}
              />{/if}{t("instance.worlds.backup")}</button
          >
        </div>
        {#if list.length}
          <ul class="backups">
            {#each list as b (b.file)}
              <li class="row">
                <Icon name="clock" size={13} />
                <span class="small grow"
                  >{formatRelative(b.created)} · {size(b.sizeBytes)}{#if b.auto}
                    <span class="badge">{t("instance.worlds.auto")}</span>{/if}</span
                >
                <button
                  class="btn sm ghost"
                  disabled={!!busy || running}
                  title={running ? t("instance.worlds.closeGame") : ""}
                  onclick={() => restore(b)}
                  ><Icon name="undo" size={13} />{t("instance.worlds.restore")}</button
                >
                <button
                  class="icon-btn sm"
                  aria-label={t("common.delete")}
                  disabled={!!busy}
                  onclick={() => remove(b)}><Icon name="trash" size={14} /></button
                >
              </li>
            {/each}
          </ul>
        {/if}
      </article>
    {/each}
    {#if orphaned.length}
      <article class="world card col gap-2">
        <strong>{t("instance.worlds.deletedWorlds")}</strong>
        <ul class="backups">
          {#each orphaned as b (b.file)}
            <li class="row">
              <span class="small grow">{b.world} · {formatRelative(b.created)} · {size(b.sizeBytes)}</span>
              <button class="btn sm ghost" disabled={!!busy || running} onclick={() => restore(b)}
                ><Icon name="undo" size={13} />{t("instance.worlds.restore")}</button
              >
              <button
                class="icon-btn sm"
                aria-label={t("common.delete")}
                disabled={!!busy}
                onclick={() => remove(b)}><Icon name="trash" size={14} /></button
              >
            </li>
          {/each}
        </ul>
      </article>
    {/if}
  {/if}
</div>

<style>
  .wicon {
    width: 40px;
    height: 40px;
    flex: none;
    border-radius: var(--r-sm);
    display: grid;
    place-items: center;
    background: var(--c-surface-2);
    color: var(--c-text-faint);
    image-rendering: pixelated;
    object-fit: cover;
  }
  .backups {
    list-style: none;
    margin: 0;
    padding: 0 0 0 calc(40px + var(--sp-2));
    display: grid;
    gap: 2px;
    color: var(--c-text-muted);
  }
  .backups li {
    gap: var(--sp-2);
  }
  .empty {
    display: grid;
    justify-items: center;
    gap: var(--sp-2);
    padding: var(--sp-5);
    color: var(--c-text-faint);
  }
</style>
