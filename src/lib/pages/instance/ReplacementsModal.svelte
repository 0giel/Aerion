<script lang="ts">
  import Skeleton from "../../components/Skeleton.svelte";
  // Suggestions for a mod this instance can't use: known successors first, then similar
  // projects; each opens the normal project view to install it.
  import { openUrl } from "@tauri-apps/plugin-opener";
  import Modal from "../../components/Modal.svelte";
  import Icon from "../../components/Icon.svelte";
  import ProjectView from "../mods/ProjectView.svelte";
  import {
    commands,
    errorMessage,
    providerImg,
    type ContentKind,
    type Instance,
    type ProjectSummary,
    type Provider,
    type Replacement,
  } from "../../api";
  import { formatNumber, t } from "../../i18n/index.svelte";

  let {
    open = $bindable(false),
    instance,
    kind,
    title,
    provider = null,
    projectId = null,
    gameVersion = null,
    oninstalled,
  }: {
    open?: boolean;
    instance: Instance;
    kind: ContentKind;
    title: string;
    provider?: Provider | null;
    projectId?: string | null;
    /** Look for this game version instead of the instance's (when moving it to a new one). */
    gameVersion?: string | null;
    oninstalled?: () => void;
  } = $props();

  let results = $state.raw<Replacement[] | null>(null);
  let error = $state<string | null>(null);
  let selected = $state.raw<ProjectSummary | null>(null);

  $effect(() => {
    if (!open) return;
    results = null;
    error = null;
    commands
      .findReplacements(instance.id, kind, title, provider, projectId, gameVersion)
      .then((r) => (results = r))
      .catch((e) => (error = errorMessage(e)));
  });
</script>

<Modal bind:open title={t("replacements.title", { name: title })} width="38rem">
  <div class="col gap-3">
    <p class="small muted">{t("replacements.hint", { version: gameVersion ?? instance.gameVersion })}</p>
    {#if error}
      <div class="alert danger"><Icon name="alert" size={16} /><span>{error}</span></div>
    {:else if results === null}
      <Skeleton rows={3} height="2.4rem" />
    {:else if !results.length}
      <span class="small muted">{t("replacements.none")}</span>
    {:else}
      <ul class="list">
        {#each results as r (r.project.id)}
          <li>
            <button
              class="hit"
              onclick={() =>
                gameVersion
                  ? openUrl(
                      `https://modrinth.com/${r.project.kind === "mod" ? "mod" : "project"}/${r.project.slug}`,
                    )
                  : (selected = r.project)}
            >
              {#if r.project.iconUrl}
                <img class="ic" src={providerImg(r.project.provider, r.project.iconUrl)} alt="" />
              {:else}<span class="ic"></span>{/if}
              <span class="col gap-1 grow" style="min-width:0">
                <span class="row" style="gap:0.4rem">
                  <strong class="truncate">{r.project.title}</strong>
                  {#if r.known}<span class="badge accent">{t("replacements.known")}</span>{/if}
                </span>
                <span class="tiny muted clamp">{r.project.description}</span>
                <span class="tiny faint"
                  ><Icon name="download" size={11} /> {formatNumber(r.project.downloads)}</span
                >
              </span>
              <Icon name="chevronRight" size={16} />
            </button>
          </li>
        {/each}
      </ul>
    {/if}
  </div>
</Modal>

{#if selected && !gameVersion}
  <ProjectView
    summary={selected}
    target={instance}
    {kind}
    installed={false}
    onclose={() => (selected = null)}
    oninstalled={() => {
      selected = null;
      open = false;
      oninstalled?.();
    }}
  />
{/if}

<style>
  .list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 0.35rem;
    max-height: 24rem;
    overflow-y: auto;
  }
  .hit {
    display: flex;
    align-items: center;
    gap: var(--sp-3);
    width: 100%;
    padding: 0.55rem 0.7rem;
    border: 1px solid var(--c-border);
    border-radius: var(--r-md);
    background: var(--c-surface-2);
    color: inherit;
    text-align: left;
    cursor: pointer;
  }
  .hit:hover {
    border-color: color-mix(in srgb, var(--c-accent) 45%, var(--c-border));
  }
  .ic {
    width: 2.4rem;
    height: 2.4rem;
    flex: none;
    border-radius: var(--r-sm);
    background: var(--c-surface-3);
    object-fit: cover;
  }
  .clamp {
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
</style>
