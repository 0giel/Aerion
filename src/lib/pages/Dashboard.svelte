<script lang="ts">
  // A widget page (experimental): the home dashboard or one of the user's own pages.
  import Icon from "../components/Icon.svelte";
  import Modal from "../components/Modal.svelte";
  import WidgetGrid from "../dashboard/WidgetGrid.svelte";
  import { WIDGETS } from "../dashboard/widgets";
  import { dashboards, HOME_ID } from "../stores/dashboards.svelte";
  import { dialogs } from "../stores/dialogs.svelte";
  import { router } from "../stores/router.svelte";
  import { toasts } from "../stores/toasts.svelte";
  import { errorMessage, type Dashboard, type WidgetItem } from "../api";
  import { t } from "../i18n/index.svelte";

  let { id = router.params.id ?? HOME_ID }: { id?: string } = $props();
  const board = $derived(dashboards.get(id));
  let edit = $state(false);
  let catalog = $state(false);

  async function save(d: Dashboard) {
    try {
      await dashboards.save(d);
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  }
  const setWidgets = (widgets: WidgetItem[]) => board && save({ ...board, widgets });

  function addWidget(kind: string) {
    if (!board) return;
    void save(dashboards.add(board, kind));
    catalog = false;
  }

  async function rename() {
    if (!board) return;
    const name = await dialogs.prompt({ title: t("widgets.renamePage"), value: board.name });
    if (name?.trim()) await save({ ...board, name: name.trim().slice(0, 40) });
  }

  async function removePage() {
    if (!board) return;
    const ok = await dialogs.confirm({
      title: t("widgets.deletePage", { name: board.name }),
      confirmLabel: t("common.delete"),
      danger: true,
    });
    if (!ok) return;
    await dashboards.remove(board.id);
    router.go("home");
  }
</script>

<div class="page dash">
  {#if board}
    <header class="page-header">
      <h1 class="grow">{id === HOME_ID ? t("nav.home") : board.name}</h1>
      <span class="badge warn">{t("widgets.experimental")}</span>
      {#if edit}
        <button class="btn sm" onclick={() => (catalog = true)}
          ><Icon name="plus" size={15} />{t("widgets.add")}</button
        >
        {#if id !== HOME_ID}
          <button class="btn sm ghost" onclick={rename}
            ><Icon name="pencil" size={15} />{t("widgets.renamePage")}</button
          >
          <button class="btn sm ghost danger" onclick={removePage}><Icon name="trash" size={15} /></button>
        {/if}
        <button class="btn sm primary" onclick={() => (edit = false)}
          ><Icon name="check" size={15} />{t("common.done")}</button
        >
      {:else}
        <button
          class="icon-btn"
          title={t("widgets.edit")}
          aria-label={t("widgets.edit")}
          onclick={() => (edit = true)}><Icon name="sliders" size={18} /></button
        >
      {/if}
    </header>
    {#if edit}<p class="hint">{t("widgets.editHint")}</p>{/if}
    {#if !board.widgets.length && !edit}
      <div class="empty card">
        <Icon name="grid" size={30} />
        <span class="small">{t("widgets.emptyPage")}</span>
        <button class="btn sm primary" onclick={() => ((edit = true), (catalog = true))}
          ><Icon name="plus" size={15} />{t("widgets.add")}</button
        >
      </div>
    {:else}
      <WidgetGrid widgets={board.widgets} {edit} onchange={setWidgets} />
    {/if}
  {:else}
    <p class="muted">{t("widgets.missingPage")}</p>
  {/if}
</div>

<Modal bind:open={catalog} title={t("widgets.add")} width="36rem">
  <div class="catalog">
    {#each Object.entries(WIDGETS) as [kind, def] (kind)}
      <button class="entry" onclick={() => addWidget(kind)}>
        <Icon name={def.icon} size={20} />
        <span class="strong">{t(def.key)}</span>
        <span class="tiny muted">{t(`widgets.about.${kind}`)}</span>
      </button>
    {/each}
  </div>
</Modal>

<style>
  .dash {
    display: flex;
    flex-direction: column;
    gap: var(--sp-3);
    padding-bottom: var(--sp-5);
  }
  .page-header {
    flex-wrap: wrap;
    gap: var(--sp-2);
  }
  .empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--sp-2);
    padding: var(--sp-5);
    color: var(--c-text-muted);
  }
  .catalog {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(9.5rem, 1fr));
    gap: var(--sp-2);
  }
  .entry {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 0.3rem;
    padding: var(--sp-3);
    border: 1px solid var(--c-border);
    border-radius: var(--r-md);
    background: var(--c-surface-2);
    color: var(--c-text);
    text-align: left;
    cursor: pointer;
  }
  .entry:hover {
    border-color: color-mix(in srgb, var(--c-accent) 45%, var(--c-border));
  }
  .entry :global(.icon) {
    color: var(--c-accent);
  }
</style>
