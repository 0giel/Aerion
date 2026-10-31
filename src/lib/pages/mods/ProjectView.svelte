<script lang="ts">
  import { openUrl } from "@tauri-apps/plugin-opener";
  import Icon from "../../components/Icon.svelte";
  import Markdown from "../../components/Markdown.svelte";
  import InstallPlanModal from "./InstallPlanModal.svelte";
  import { toasts } from "../../stores/toasts.svelte";
  import {
    commands,
    errorMessage,
    loaderLabel,
    providerImg,
    type ContentKind,
    type Instance,
    type ProjectDetails,
    type ProjectSummary,
    type ProjectVersion,
  } from "../../api";
  import { formatDate, formatNumber, t } from "../../i18n/index.svelte";

  let {
    summary,
    target,
    kind,
    installed,
    onclose,
    oninstalled,
  }: {
    summary: ProjectSummary;
    target: Instance | null;
    kind: ContentKind;
    installed: boolean;
    onclose: () => void;
    oninstalled: (key: string) => void;
  } = $props();

  type Tab = "description" | "gallery" | "versions";
  let tab = $state<Tab>("description");
  let details = $state.raw<ProjectDetails | null>(null);
  let versions = $state.raw<ProjectVersion[] | null>(null);
  let onlyCompatible = $state(true);
  let error = $state<string | null>(null);
  let planFor = $state<{ versionId: string | null } | null>(null);
  let lightbox = $state<string | null>(null);
  const noCache = $derived(summary.provider === "curseforge");
  const providerName = $derived(summary.provider === "modrinth" ? "Modrinth" : "CurseForge");

  $effect(() => {
    commands
      .getProject(summary.provider, summary.id)
      .then((d) => (details = d))
      .catch((e) => (error = errorMessage(e)));
  });

  $effect(() => {
    const compat = onlyCompatible && !!target;
    versions = null;
    commands
      .getProjectVersions(
        summary.provider,
        summary.id,
        compat ? target!.gameVersion : null,
        compat && target!.loader !== "vanilla" ? target!.loader : null,
        kind,
      )
      .then((v) => (versions = v))
      .catch(() => (versions = []));
  });

  async function install(versionId: string | null) {
    if (kind === "modpack") {
      try {
        await commands.installModpack(summary.provider, summary.id, versionId, summary.title);
        onclose();
      } catch (e) {
        toasts.error(errorMessage(e));
      }
      return;
    }
    if (!target) {
      toasts.info(t("mods.pickInstance"));
      return;
    }
    planFor = { versionId };
  }

  function onkeydown(e: KeyboardEvent) {
    if (e.key === "Escape" && !planFor) {
      if (lightbox) lightbox = null;
      else onclose();
    }
  }

  const sideLabel = (s: string | null | undefined) => t(`project.sides.${s ?? "unknown"}`);
</script>

<svelte:window {onkeydown} />

<div class="scrim" role="presentation" onmousedown={(e) => e.target === e.currentTarget && onclose()}>
  <div class="panel" role="dialog" aria-modal="true" aria-label={summary.title}>
    <header class="top">
      {#if summary.iconUrl}<img
          class="icon"
          src={providerImg(summary.provider, summary.iconUrl)}
          alt=""
        />{:else}<span class="icon none"><Icon name="package" size={30} /></span>{/if}
      <div class="col gap-1 grow" style="min-width:0">
        <h2 class="truncate">{summary.title}</h2>
        <span class="small muted truncate"
          >{t("mods.byAuthor", { author: details?.summary.author || summary.author })} · {formatNumber(
            summary.downloads,
            true,
          )} ↓</span
        >
        <span class="small faint desc">{summary.description}</span>
      </div>
      <button class="icon-btn" onclick={onclose} aria-label={t("common.close")}><Icon name="x" /></button>
    </header>

    <div class="actions row wrap">
      {#if kind === "modpack"}
        <button class="btn primary" onclick={() => install(null)}
          ><Icon name="download" size={16} />{t("mods.installNewInstance")}</button
        >
      {:else if installed}
        <span class="badge success"><Icon name="check" size={13} />{t("common.installed")}</span>
        <button class="btn" onclick={() => install(null)}
          ><Icon name="refresh" size={16} />{t("common.update")}</button
        >
      {:else}
        <button class="btn primary" onclick={() => install(null)} disabled={!target}>
          <Icon name="download" size={16} />{target
            ? t("mods.installTo", { name: target.name })
            : t("mods.pickInstance")}
        </button>
      {/if}
      <button class="btn ghost" onclick={() => openUrl(summary.pageUrl)}
        ><Icon name="external" size={16} />{t("mods.openPage", { provider: providerName })}</button
      >
    </div>

    <div class="tabs" role="tablist">
      <button role="tab" aria-selected={tab === "description"} onclick={() => (tab = "description")}
        >{t("project.description")}</button
      >
      {#if details?.gallery.length}<button
          role="tab"
          aria-selected={tab === "gallery"}
          onclick={() => (tab = "gallery")}
          >{t("project.gallery")} <span class="faint">{details.gallery.length}</span></button
        >{/if}
      <button role="tab" aria-selected={tab === "versions"} onclick={() => (tab = "versions")}
        >{t("project.versions")}</button
      >
    </div>

    <div class="body">
      <div class="main">
        {#if error}
          <div class="alert danger"><Icon name="alert" size={18} />{error}</div>
        {:else if !details}
          <div class="skeleton" style="height: 1.2rem; width: 60%"></div>
          <div class="skeleton" style="height: 8rem; margin-top: 1rem"></div>
        {:else if tab === "description"}
          <Markdown source={details.body || summary.description} format={details.bodyFormat} {noCache} />
        {:else if tab === "gallery"}
          <div class="gallery">
            {#each details.gallery as g (g.url)}
              <button class="shot" onclick={() => (lightbox = g.url)}>
                <img
                  src={providerImg(summary.provider, g.thumbnailUrl ?? g.url)}
                  alt={g.title ?? ""}
                  loading="lazy"
                />
                {#if g.title}<span class="tiny">{g.title}</span>{/if}
              </button>
            {/each}
          </div>
        {:else}
          {#if target && kind !== "modpack"}
            <label class="row small muted check"
              ><input type="checkbox" bind:checked={onlyCompatible} />{t("project.compatibleOnly", {
                name: target.name,
              })}</label
            >
          {/if}
          {#if versions === null}
            <div class="skeleton" style="height: 3rem; margin-top: .6rem"></div>
          {:else if !versions.length}
            <p class="muted">{t("project.noVersions")}</p>
          {:else}
            <ul class="versions">
              {#each versions.slice(0, 120) as v (v.id)}
                <li>
                  <div class="col gap-1 grow" style="min-width:0">
                    <div class="row" style="gap: 0.4rem">
                      <strong class="truncate">{v.name || v.versionNumber}</strong>
                      <span
                        class="badge"
                        class:success={v.kind === "release"}
                        class:warning={v.kind !== "release"}>{t(`project.${v.kind}`)}</span
                      >
                    </div>
                    <span class="tiny faint truncate"
                      >{v.gameVersions.slice(0, 6).join(", ")}{v.gameVersions.length > 6 ? "…" : ""} · {v.loaders
                        .map(loaderLabel)
                        .join(", ")} · {formatDate(v.published)}</span
                    >
                  </div>
                  {#if !v.files[0]?.url}
                    <button class="btn sm" onclick={() => v.pageUrl && openUrl(v.pageUrl)}
                      ><Icon name="external" size={14} />CurseForge</button
                    >
                  {:else}
                    <button
                      class="btn sm"
                      onclick={() => install(v.id)}
                      disabled={kind !== "modpack" && !target}
                      ><Icon name="download" size={14} />{t("common.install")}</button
                    >
                  {/if}
                </li>
              {/each}
            </ul>
          {/if}
        {/if}
      </div>

      {#if details}
        <aside class="side col gap-3 small">
          {#if details.license}
            <div class="col gap-1">
              <span class="section-title">{t("project.license")}</span><span
                >{details.license.name || details.license.id}</span
              >
            </div>
          {/if}
          {#if details.loaders.length}
            <div class="col gap-1">
              <span class="section-title">{t("project.loaders")}</span><span
                >{details.loaders.map(loaderLabel).join(", ")}</span
              >
            </div>
          {/if}
          {#if details.gameVersions.length}
            <div class="col gap-1">
              <span class="section-title">{t("project.gameVersions")}</span><span class="muted"
                >{details.gameVersions.slice(-1)[0]} – {details.gameVersions[0] ===
                details.gameVersions.slice(-1)[0]
                  ? ""
                  : details.gameVersions[0]}</span
              >
            </div>
          {/if}
          {#if details.clientSide}
            <div class="col gap-1">
              <span class="section-title">{t("project.client")} / {t("project.server")}</span><span
                class="muted">{sideLabel(details.clientSide)} / {sideLabel(details.serverSide)}</span
              >
            </div>
          {/if}
          {#if details.links.length}
            <div class="col gap-1">
              <span class="section-title">{t("project.links")}</span>
              {#each details.links as l (l.url)}
                <button class="link" onclick={() => openUrl(l.url)}
                  ><Icon name="external" size={13} />{t(`project.links_.${l.kind}`)}</button
                >
              {/each}
            </div>
          {/if}
        </aside>
      {/if}
    </div>
  </div>
</div>

{#if lightbox}
  <button class="lightbox" onclick={() => (lightbox = null)} aria-label={t("common.close")}>
    <img src={providerImg(summary.provider, lightbox)} alt="" />
  </button>
{/if}

{#if planFor && target}
  <InstallPlanModal
    {summary}
    instance={target}
    {kind}
    versionId={planFor.versionId}
    onclose={() => (planFor = null)}
    ondone={() => {
      planFor = null;
      oninstalled(`${summary.provider}:${summary.id}`);
    }}
  />
{/if}

<style>
  .scrim {
    position: fixed;
    inset: 0;
    z-index: 80;
    background: rgb(0 0 0 / 0.35);
    display: flex;
    justify-content: flex-end;
    animation: fade var(--dur) var(--ease);
  }
  .panel {
    width: min(64rem, 92vw);
    height: 100%;
    display: flex;
    flex-direction: column;
    background: var(--c-surface);
    border-left: 1px solid var(--c-border);
    box-shadow: var(--shadow-lg);
    animation: slide var(--dur-slow) var(--ease);
  }
  .top {
    display: flex;
    gap: var(--sp-4);
    align-items: flex-start;
    padding: var(--sp-5) var(--sp-5) var(--sp-3);
  }
  .icon {
    width: 5rem;
    height: 5rem;
    flex: none;
    border-radius: var(--r-lg);
    object-fit: cover;
    background: var(--c-surface-3);
  }
  .icon.none {
    display: grid;
    place-items: center;
    color: var(--c-text-faint);
  }
  .desc {
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
  .actions {
    padding: 0 var(--sp-5) var(--sp-3);
  }
  .tabs {
    display: flex;
    gap: 0.2rem;
    padding: 0 var(--sp-5);
    border-bottom: 1px solid var(--c-border);
  }
  .tabs button {
    padding: 0.55rem 0.85rem;
    border: 0;
    border-bottom: 2px solid transparent;
    margin-bottom: -1px;
    background: transparent;
    color: var(--c-text-muted);
    cursor: pointer;
    font-weight: 560;
  }
  .tabs button[aria-selected="true"] {
    color: var(--c-text);
    border-bottom-color: var(--c-accent);
  }
  .body {
    flex: 1;
    min-height: 0;
    display: grid;
    grid-template-columns: 1fr 14rem;
    gap: var(--sp-5);
    padding: var(--sp-4) var(--sp-5);
    overflow: auto;
  }
  .main {
    min-width: 0;
  }
  .side {
    position: sticky;
    top: 0;
    align-self: start;
  }
  .link {
    display: inline-flex;
    align-items: center;
    gap: 0.35rem;
    border: 0;
    background: none;
    color: var(--c-accent);
    padding: 0.1rem 0;
    cursor: pointer;
    text-align: left;
  }
  .gallery {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(14rem, 1fr));
    gap: var(--sp-3);
  }
  .shot {
    display: flex;
    flex-direction: column;
    gap: 0.3rem;
    padding: 0;
    border: 0;
    background: none;
    cursor: zoom-in;
    text-align: left;
    color: var(--c-text-muted);
  }
  .shot img {
    width: 100%;
    aspect-ratio: 16 / 9;
    object-fit: cover;
    border-radius: var(--r-md);
    background: var(--c-surface-3);
  }
  .versions {
    list-style: none;
    margin: 0.6rem 0 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 0.35rem;
  }
  .versions li {
    display: flex;
    align-items: center;
    gap: var(--sp-3);
    padding: 0.55rem 0.75rem;
    border-radius: var(--r-md);
    background: var(--c-surface-2);
  }
  .check {
    gap: 0.45rem;
    cursor: pointer;
  }
  .lightbox {
    position: fixed;
    inset: 0;
    z-index: 120;
    display: grid;
    place-items: center;
    padding: 3rem;
    border: 0;
    background: rgb(0 0 0 / 0.8);
    cursor: zoom-out;
  }
  .lightbox img {
    max-width: 100%;
    max-height: 100%;
    border-radius: var(--r-md);
  }
  @keyframes slide {
    from {
      transform: translateX(6%);
      opacity: 0;
    }
  }
  @keyframes fade {
    from {
      opacity: 0;
    }
  }
  @media (max-width: 900px) {
    .body {
      grid-template-columns: 1fr;
    }
  }
</style>
