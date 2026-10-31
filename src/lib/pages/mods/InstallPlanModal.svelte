<script lang="ts">
  import { openUrl } from "@tauri-apps/plugin-opener";
  import Modal from "../../components/Modal.svelte";
  import Icon from "../../components/Icon.svelte";
  import { toasts } from "../../stores/toasts.svelte";
  import {
    commands,
    errorMessage,
    providerImg,
    type ContentKind,
    type InstallPlan,
    type Instance,
    type ProjectSummary,
  } from "../../api";
  import { formatBytes, t } from "../../i18n/index.svelte";

  let {
    summary,
    instance,
    kind,
    versionId,
    onclose,
    ondone,
  }: {
    summary: ProjectSummary;
    instance: Instance;
    kind: ContentKind;
    versionId: string | null;
    onclose: () => void;
    ondone: () => void;
  } = $props();

  let plan = $state.raw<InstallPlan | null>(null);
  let error = $state<string | null>(null);
  let optional = $state<Record<string, boolean>>({});
  let busy = $state(false);
  let open = $state(true);

  $effect(() => {
    commands
      .planContentInstall(instance.id, summary.provider, summary.id, versionId, kind)
      .then((p) => {
        plan = p;
        optional = Object.fromEntries(p.optional.map((o) => [o.projectId, false]));
      })
      .catch((e) => (error = errorMessage(e)));
  });

  const size = $derived(plan?.install.reduce((s, f) => s + (f.version.files[0]?.size ?? 0), 0) ?? 0);

  async function confirm() {
    busy = true;
    try {
      await commands.installContent(
        instance.id,
        summary.provider,
        summary.id,
        versionId,
        kind,
        Object.keys(optional).filter((k) => optional[k]),
        summary.title,
      );
      ondone();
    } catch (e) {
      toasts.error(errorMessage(e));
      busy = false;
    }
  }
</script>

<Modal bind:open title={t("plan.title", { name: summary.title })} width="38rem" {onclose}>
  {#if error}
    <div class="alert danger"><Icon name="alert" size={18} /><span>{error}</span></div>
  {:else if !plan}
    <div class="row muted"><span class="spinner"></span>{t("plan.resolving")}</div>
  {:else}
    <div class="col gap-4">
      <section class="col gap-2">
        <div class="row between">
          <span class="section-title">{t("plan.willInstall")}</span><span class="tiny faint"
            >{formatBytes(size)}</span
          >
        </div>
        {#each plan.install as f (f.projectId)}
          <div class="item">
            {#if f.iconUrl}<img src={providerImg(summary.provider, f.iconUrl)} alt="" />{:else}<span
                class="ph"><Icon name="package" size={16} /></span
              >{/if}
            <div class="col gap-1 grow" style="min-width:0">
              <strong class="truncate">{f.title}</strong>
              <span class="tiny faint truncate"
                >{f.version.versionNumber}{#if f.requiredBy}
                  · {t("plan.requiredBy", { name: f.requiredBy })}{/if}</span
              >
            </div>
          </div>
        {/each}
      </section>

      {#if plan.optional.length}
        <section class="col gap-2">
          <span class="section-title">{t("plan.optional")}</span>
          <span class="hint">{t("plan.optionalHint")}</span>
          {#each plan.optional as o (o.projectId)}
            <label class="item check"
              ><input type="checkbox" bind:checked={optional[o.projectId]} /><span class="grow truncate"
                >{o.title}</span
              ><span class="tiny faint">{o.by}</span></label
            >
          {/each}
        </section>
      {/if}

      {#if plan.incompatible.length}
        <section class="alert warning col gap-1">
          <strong><Icon name="alert" size={16} /> {t("plan.incompatible")}</strong>
          {#each plan.incompatible as c (c.projectId)}<span class="small"
              >{t("plan.incompatibleHint", { name: c.by, other: c.title })}</span
            >{/each}
        </section>
      {/if}

      {#if plan.unavailable.length}
        <section class="alert col gap-2">
          <strong>{t("plan.unavailable")}</strong>
          <span class="small">{t("plan.unavailableHint")}</span>
          {#each plan.unavailable as u (u.projectId)}
            <div class="row">
              <span class="grow truncate">{u.title}</span>{#if u.url}<button
                  class="btn sm"
                  onclick={() => openUrl(u.url ?? "")}
                  ><Icon name="external" size={14} />{t("common.open")}</button
                >{/if}
            </div>
          {/each}
        </section>
      {/if}
    </div>
  {/if}
  {#snippet footer()}
    <button class="btn ghost" onclick={() => ((open = false), onclose())}>{t("common.cancel")}</button>
    <button class="btn primary" onclick={confirm} disabled={!plan || !plan.install.length || busy}
      ><Icon name="download" size={16} />{t("plan.confirm")}</button
    >
  {/snippet}
</Modal>

<style>
  .item {
    display: flex;
    align-items: center;
    gap: 0.65rem;
    padding: 0.45rem 0.6rem;
    border-radius: var(--r-md);
    background: var(--c-surface-2);
  }
  .item img,
  .ph {
    width: 2rem;
    height: 2rem;
    flex: none;
    border-radius: var(--r-sm);
    object-fit: cover;
    background: var(--c-surface-3);
  }
  .ph {
    display: grid;
    place-items: center;
    color: var(--c-text-faint);
  }
  .check {
    cursor: pointer;
  }
  .spinner {
    width: 16px;
    height: 16px;
    border-radius: 50%;
    border: 2px solid var(--c-accent-soft);
    border-top-color: var(--c-accent);
    animation: spin 0.8s linear infinite;
  }
</style>
