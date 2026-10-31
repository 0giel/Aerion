<script lang="ts">
  // Play statistics: headline tiles, then ranked bar lists (one series each, accent colour).
  import Icon from "../components/Icon.svelte";
  import InstanceIcon from "../components/InstanceIcon.svelte";
  import { router } from "../stores/router.svelte";
  import { commands, imgUrl, loaderLabel, type LoaderKind } from "../api";
  import type { Stats_Serialize as Stats } from "../bindings";
  import { formatPlayTime, t } from "../i18n/index.svelte";

  let stats = $state.raw<Stats | null>(null);
  let failed = $state(false);

  $effect(() => {
    commands
      .playStats()
      .then((s) => (stats = s))
      .catch(() => (failed = true));
  });

  const hours = (secs: number) => (secs < 3600 ? formatPlayTime(secs) : `${(secs / 3600).toFixed(1)} h`);
  const share = (v: number, max: number) => (max > 0 ? Math.max(2, (v / max) * 100) : 0);
  const topInstances = $derived(stats?.instances.filter((i) => i.playSecs > 0).slice(0, 8) ?? []);
  const maxInstance = $derived(topInstances[0]?.playSecs ?? 0);
  const versions = $derived(stats?.versions.slice(0, 8) ?? []);
  const maxVersion = $derived(Math.max(0, ...versions.map((v) => v.playSecs)));
  const loaders = $derived(stats?.loaders ?? []);
  const maxLoader = $derived(Math.max(0, ...loaders.map((v) => v.playSecs)));
  const maxMod = $derived(stats?.mods[0]?.instances ?? 0);
</script>

<div class="page">
  <header class="page-header"><h1>{t("stats.title")}</h1></header>

  {#if failed}
    <div class="empty"><p>{t("stats.failed")}</p></div>
  {:else if !stats}
    <div class="empty"><span class="spinner"></span></div>
  {:else}
    <div class="tiles">
      {#each [["clock", hours(stats.totalPlaySecs), "stats.playTime"], ["play", String(stats.totalLaunches), "stats.launches"], ["grid", String(stats.instanceCount), "stats.instances"], ["globe", String(stats.worldCount), "stats.worlds"], ["image", String(stats.screenshotCount), "stats.screenshots"], ["package", String(stats.modCount), "stats.mods"]] as [icon, value, label] (label)}
        <div class="tile card">
          <span class="tile-icon"><Icon name={icon} size={18} /></span>
          <strong class="value">{value}</strong>
          <span class="small muted">{t(label)}</span>
        </div>
      {/each}
    </div>

    <div class="grid">
      <section class="card col gap-3">
        <h3>{t("stats.mostPlayed")}</h3>
        {#if !topInstances.length}
          <p class="small muted">{t("stats.noPlayYet")}</p>
        {:else}
          <ol class="bars">
            {#each topInstances as i (i.id)}
              <li>
                <button
                  class="bar-row"
                  title={t("stats.instanceTip", { name: i.name, time: hours(i.playSecs), n: i.launches })}
                  onclick={() => router.go("instance", { id: i.id })}
                >
                  <InstanceIcon
                    name={i.name}
                    loader={i.loader as LoaderKind}
                    icon={i.icon}
                    id={i.id}
                    size={26}
                  />
                  <span class="label truncate">{i.name}</span>
                  <span class="track"
                    ><span class="fill" style:width="{share(i.playSecs, maxInstance)}%"></span></span
                  >
                  <span class="num">{hours(i.playSecs)}</span>
                </button>
              </li>
            {/each}
          </ol>
        {/if}
      </section>

      <section class="card col gap-3">
        <h3>{t("stats.versions")}</h3>
        <ol class="bars">
          {#each versions as v (v.key)}
            <li
              class="bar-row static"
              title={t("stats.groupTip", { name: v.key, time: hours(v.playSecs), n: v.instances })}
            >
              <span class="label truncate">{v.key}</span>
              <span class="track"
                ><span class="fill" style:width="{share(v.playSecs, maxVersion)}%"></span></span
              >
              <span class="num">{hours(v.playSecs)}</span>
            </li>
          {/each}
        </ol>
        <h3>{t("stats.loaders")}</h3>
        <ol class="bars">
          {#each loaders as l (l.key)}
            <li
              class="bar-row static"
              title={t("stats.groupTip", {
                name: loaderLabel(l.key),
                time: hours(l.playSecs),
                n: l.instances,
              })}
            >
              <span class="label truncate">{loaderLabel(l.key)}</span>
              <span class="track"
                ><span class="fill" style:width="{share(l.playSecs, maxLoader)}%"></span></span
              >
              <span class="num">{hours(l.playSecs)}</span>
            </li>
          {/each}
        </ol>
      </section>

      <section class="card col gap-3 wide">
        <h3>{t("stats.topMods")}</h3>
        {#if !stats.mods.length}
          <p class="small muted">{t("stats.noMods")}</p>
        {:else}
          <ol class="bars mods">
            {#each stats.mods as m (m.title)}
              <li class="bar-row static" title={t("stats.modTip", { name: m.title, n: m.instances })}>
                {#if m.iconUrl}<img
                    class="mod-icon"
                    src={imgUrl(m.iconUrl)}
                    alt=""
                    loading="lazy"
                  />{:else}<span class="mod-icon"><Icon name="package" size={14} /></span>{/if}
                <span class="label truncate">{m.title}</span>
                <span class="track"
                  ><span class="fill" style:width="{share(m.instances, maxMod)}%"></span></span
                >
                <span class="num">{t("stats.inInstances", { n: m.instances })}</span>
              </li>
            {/each}
          </ol>
        {/if}
      </section>
    </div>
  {/if}
</div>

<style>
  .tiles {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(9.5rem, 1fr));
    gap: var(--sp-3);
    margin-bottom: var(--sp-4);
  }
  .tile {
    display: flex;
    flex-direction: column;
    gap: 0.2rem;
  }
  .tile-icon {
    color: var(--c-accent);
  }
  .value {
    font-size: 1.6rem;
    line-height: 1.2;
    font-variant-numeric: tabular-nums;
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(22rem, 1fr));
    gap: var(--sp-4);
    align-items: start;
  }
  .wide {
    grid-column: 1 / -1;
  }
  .bars {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: 2px;
  }
  .mods {
    grid-template-columns: repeat(auto-fit, minmax(20rem, 1fr));
    column-gap: var(--sp-4);
  }
  .bar-row {
    display: grid;
    grid-template-columns: auto minmax(5rem, 10rem) 1fr auto;
    align-items: center;
    gap: var(--sp-2);
    width: 100%;
    padding: 0.3rem 0.4rem;
    border: 0;
    border-radius: var(--r-sm);
    background: none;
    color: inherit;
    text-align: left;
  }
  .bar-row.static {
    grid-template-columns: minmax(5rem, 10rem) 1fr auto;
  }
  .mods .bar-row.static {
    grid-template-columns: auto minmax(5rem, 12rem) 1fr auto;
  }
  button.bar-row {
    cursor: pointer;
  }
  .bar-row:hover {
    background: var(--c-surface-2);
  }
  .label {
    font-size: 0.9em;
  }
  .track {
    height: 8px;
    border-radius: 4px;
    background: color-mix(in srgb, var(--c-text) 7%, transparent);
    overflow: hidden;
  }
  .fill {
    display: block;
    height: 100%;
    border-radius: 4px;
    background: var(--c-accent);
  }
  .num {
    font-size: 0.85em;
    color: var(--c-text-muted);
    font-variant-numeric: tabular-nums;
    white-space: nowrap;
  }
  .mod-icon {
    width: 22px;
    height: 22px;
    border-radius: var(--r-sm);
    display: grid;
    place-items: center;
    background: var(--c-surface-2);
    object-fit: cover;
  }
</style>
