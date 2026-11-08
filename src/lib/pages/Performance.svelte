<script lang="ts">
  import Skeleton from "../components/Skeleton.svelte";
  // Live resource use: the computer, Aerion itself and running games (FPS from the Aerion mod).
  // Only measured numbers are shown; nothing is compared with other launchers.
  import Icon from "../components/Icon.svelte";
  import Meter from "../components/Meter.svelte";
  import Sparkline from "../components/Sparkline.svelte";
  import { app } from "../stores/app.svelte";
  import { router } from "../stores/router.svelte";
  import { toasts } from "../stores/toasts.svelte";
  import { commands, errorMessage, events, type PerfSnapshot } from "../api";
  import type { Hint, SessionSummary_Serialize as SessionSummary } from "../bindings";
  import { formatPlayTime, formatRelative, i18n, t } from "../i18n/index.svelte";

  const SLOTS = 60;
  let snap = $state.raw<PerfSnapshot | null>(null);
  let error = $state<string | null>(null);
  let cpu = $state.raw<number[]>([]);
  let mem = $state.raw<number[]>([]);
  let gpu = $state.raw<(number | null)[]>([]);
  let aerion = $state.raw<number[]>([]);
  /** Per running instance: FPS (null without the mod) and memory history. */
  let games = $state.raw<Record<string, { fps: (number | null)[]; mem: number[] }>>({});

  const push = <T,>(list: T[], v: T) => [...list, v].slice(-SLOTS);
  /** Floats arrive as null when the system reports nothing usable (NaN). */
  const num = (v: number | null) => v ?? 0;

  async function sample() {
    if (document.hidden) return;
    try {
      const s = await commands.perfSnapshot();
      snap = s;
      error = null;
      cpu = push<number>(cpu, num(s.cpu.usage));
      mem = push<number>(mem, s.memoryUsedMb);
      gpu = push<number | null>(gpu, s.gpu?.usage ?? null);
      aerion = push<number>(aerion, s.launcher.memoryMb);
      const next: typeof games = {};
      for (const g of s.games) {
        const prev = games[g.instanceId] ?? { fps: [], mem: [] };
        next[g.instanceId] = {
          fps: push<number | null>(prev.fps, g.fps),
          mem: push<number>(prev.mem, g.usage.memoryMb),
        };
      }
      games = next;
    } catch (e) {
      error = errorMessage(e);
    }
  }

  $effect(() => {
    void sample();
    const timer = setInterval(sample, 1000);
    return () => clearInterval(timer);
  });

  // Summaries of the last games, written by the launcher when a game exits.
  let sessions = $state.raw<SessionSummary[]>([]);
  let picked = $state(0);
  const session = $derived(sessions[picked] ?? null);
  async function loadSessions() {
    sessions = await commands.perfSessions().catch(() => []);
    picked = 0;
  }
  $effect(() => {
    void loadSessions();
    const off = events.gameStateEvent.listen(({ payload }) => {
      // The summary is saved right after the exit event's work; give it a moment.
      if (payload.state === "exited") setTimeout(loadSessions, 1500);
    });
    return () => void off.then((f) => f());
  });
  async function clearSessions() {
    await commands.clearPerfSessions().catch(() => {});
    sessions = [];
  }

  const hintText = (h: Hint) => {
    const p: Record<string, string | number> = { ...h.params };
    for (const k of ["peak", "max", "suggest", "free"]) if (k in p) p[k] = size(Number(p[k]));
    return t(`perf.hints.${h.kind}` as never, p);
  };
  async function setMemory(id: string, mb: number) {
    const inst = app.instances.find((i) => i.id === id);
    if (!inst) return;
    try {
      await commands.updateInstance({ ...inst, maxMemoryMb: mb });
      await app.refreshInstances();
      toasts.success(t("perf.hints.memorySet", { name: inst.name, size: size(mb) }));
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  }

  const n1 = $derived(new Intl.NumberFormat(i18n.locale, { maximumFractionDigits: 1 }));
  const n0 = $derived(new Intl.NumberFormat(i18n.locale, { maximumFractionDigits: 0 }));
  const pct = (v: number) => `${n0.format(v)}%`;
  const size = (mb: number) => (mb >= 1024 ? `${n1.format(mb / 1024)} GB` : `${n0.format(mb)} MB`);
  const ago = (s: number) => (s === 0 ? t("perf.now") : t("perf.secondsAgo", { n: s }));

  const instanceName = (id: string) => app.instances.find((i) => i.id === id)?.name ?? id;
  /** Why a game shows no FPS: the mod is off, or the instance can't use it. */
  function fpsHint(id: string) {
    const inst = app.instances.find((i) => i.id === id);
    if (!inst) return t("perf.fpsUnknown");
    const supported =
      inst.loader === "fabric" && (app.info?.aerionModVersions ?? []).includes(inst.gameVersion);
    if (supported && !inst.aerionMod) return t("perf.fpsEnableMod");
    return supported ? t("perf.fpsWaiting") : t("perf.fpsUnsupported");
  }

  // Short, measured advice; nothing is shown when everything is fine.
  const tips = $derived.by(() => {
    const s = snap;
    if (!s) return [];
    const out: string[] = [];
    if (s.memoryUsedMb / s.memoryTotalMb > 0.9) out.push(t("perf.tips.lowMemory"));
    if (num(s.cpu.usage) > 90) out.push(t(s.games.length ? "perf.tips.cpuBusy" : "perf.tips.cpuBusyIdle"));
    if ((s.gpu?.usage ?? 0) > 95 && s.games.length) out.push(t("perf.tips.gpuBusy"));
    for (const g of s.games) {
      if (g.fps !== null && g.fps < 30)
        out.push(t("perf.tips.lowFps", { name: instanceName(g.instanceId), fps: g.fps }));
    }
    if (s.games.length && app.settings?.afterLaunch === "keep")
      out.push(t("perf.tips.hideLauncher", { size: size(s.launcher.memoryMb) }));
    return out;
  });
</script>

<div class="page perf">
  <header class="page-header">
    <h1>{t("perf.title")}</h1>
    <span class="small muted">{t("perf.subtitle")}</span>
  </header>

  {#if error}
    <div class="alert danger"><Icon name="alert" size={16} /><span>{error}</span></div>
  {/if}

  {#if snap}
    <section class="tiles">
      <article class="tile card">
        <span class="label">{t("perf.cpu")}</span>
        <strong class="value">{pct(num(snap.cpu.usage))}</strong>
        <span class="tiny muted truncate" title={snap.cpu.name}
          >{snap.cpu.name || t("perf.cpu")} · {t("perf.threads", { n: snap.cpu.cores })}</span
        >
        <Sparkline values={cpu} max={100} format={pct} label={t("perf.cpuHistory")} {ago} />
      </article>

      <article class="tile card">
        <span class="label">{t("perf.memory")}</span>
        <strong class="value"
          >{size(snap.memoryUsedMb)} <span class="of">/ {size(snap.memoryTotalMb)}</span></strong
        >
        <Meter value={snap.memoryUsedMb} max={snap.memoryTotalMb} label={t("perf.memory")} />
        <Sparkline
          values={mem}
          max={snap.memoryTotalMb}
          format={size}
          label={t("perf.memoryHistory")}
          {ago}
        />
      </article>

      <article class="tile card">
        <span class="label">{t("perf.gpu")}</span>
        {#if snap.gpu}
          <strong class="value"
            >{snap.gpu.usage !== null ? pct(snap.gpu.usage) : "—"}{#if snap.gpu.temperatureC !== null}<span
                class="of"
              >
                · {n0.format(snap.gpu.temperatureC)} °C</span
              >{/if}</strong
          >
          <span class="tiny muted truncate" title={snap.gpu.name}>{snap.gpu.name}</span>
          {#if snap.gpu.vramUsedMb !== null && snap.gpu.vramTotalMb}
            <Meter value={snap.gpu.vramUsedMb} max={snap.gpu.vramTotalMb} label={t("perf.vram")} />
            <span class="tiny muted"
              >{t("perf.vramOf", {
                used: size(snap.gpu.vramUsedMb),
                total: size(snap.gpu.vramTotalMb),
              })}</span
            >
          {/if}
          <Sparkline values={gpu} max={100} format={pct} label={t("perf.gpuHistory")} {ago} />
        {:else}
          <strong class="value muted">—</strong>
          <span class="tiny muted">{t("perf.gpuUnavailable")}</span>
        {/if}
      </article>

      <article class="tile card">
        <span class="label">{t("perf.aerion")}</span>
        <strong class="value">{size(snap.launcher.memoryMb)}</strong>
        <span class="tiny muted"
          >{t("perf.aerionDetail", {
            cpu: n1.format(num(snap.launcher.cpu)),
            n: snap.launcher.processes,
          })}</span
        >
        <Sparkline values={aerion} format={size} label={t("perf.aerionHistory")} {ago} />
      </article>
    </section>

    <section class="col gap-3">
      <h2 class="section-title">{t("perf.games")}</h2>
      {#if !snap.games.length}
        <div class="empty card">
          <Icon name="play" size={28} />
          <span class="small">{t("perf.noGame")}</span>
          <button class="btn sm" onclick={() => router.go("instances")}>{t("nav.instances")}</button>
        </div>
      {:else}
        {#each snap.games as g (g.instanceId)}
          {@const h = games[g.instanceId]}
          <article class="game card">
            <div class="row between wrap">
              <strong>{instanceName(g.instanceId)}</strong>
              <span class="tiny muted"
                >{t("perf.gameDetail", { cpu: n1.format(num(g.usage.cpu)), n: g.usage.processes })}</span
              >
            </div>
            <div class="grid">
              <div class="col gap-1">
                <span class="label">{t("perf.fps")}</span>
                {#if g.fps !== null}
                  <strong class="hero">{g.fps}</strong>
                  <Sparkline
                    values={h?.fps ?? []}
                    format={(v) => `${n0.format(v)} FPS`}
                    label={t("perf.fpsHistory")}
                    {ago}
                    height={56}
                  />
                {:else}
                  <strong class="hero muted">—</strong>
                  <span class="tiny muted">{fpsHint(g.instanceId)}</span>
                {/if}
              </div>
              <div class="col gap-1">
                <span class="label">{t("perf.gameMemory")}</span>
                <strong class="value"
                  >{size(g.usage.memoryMb)} <span class="of">/ {size(g.maxMemoryMb)}</span></strong
                >
                <Meter value={g.usage.memoryMb} max={g.maxMemoryMb} label={t("perf.gameMemory")} />
                <span class="tiny muted">{t("perf.gameMemoryHint")}</span>
                <Sparkline values={h?.mem ?? []} format={size} label={t("perf.gameMemory")} {ago} />
              </div>
            </div>
          </article>
        {/each}
      {/if}
    </section>

    {#if session}
      <section class="col gap-3">
        <div class="row between wrap">
          <h2 class="section-title">{t("perf.lastGames")}</h2>
          <div class="row gap-1 wrap">
            {#if sessions.length > 1}
              <select class="input sm" bind:value={picked} aria-label={t("perf.lastGames")}>
                {#each sessions as s, i (s.ended + s.instanceId)}
                  <option value={i}>{s.instanceName} · {formatRelative(s.ended)}</option>
                {/each}
              </select>
            {/if}
            <button class="btn sm ghost" onclick={clearSessions} title={t("perf.clearSessions")}
              ><Icon name="trash" size={14} /></button
            >
          </div>
        </div>
        <article class="summary card">
          <div class="row between wrap">
            <strong>{session.instanceName}</strong>
            <span class="tiny muted"
              >{formatRelative(session.ended)} · {formatPlayTime(session.playSecs)}</span
            >
          </div>
          <div class="stats">
            <div class="stat">
              <span class="label">{t("perf.fpsAvg")}</span>
              <strong class="value">{session.fpsAvg ?? "—"}</strong>
            </div>
            <div class="stat">
              <span class="label">{t("perf.fpsLow")}</span>
              <strong class="value">{session.fpsLow ?? "—"}</strong>
            </div>
            <div class="stat">
              <span class="label">{t("perf.heapPeak")}</span>
              {#if session.heapPeakMb !== null && session.maxMemoryMb}
                <strong class="value"
                  >{size(session.heapPeakMb)} <span class="of">/ {size(session.maxMemoryMb)}</span></strong
                >
                <Meter value={session.heapPeakMb} max={session.maxMemoryMb} label={t("perf.heapPeak")} />
              {:else}
                <strong class="value">—</strong>
              {/if}
            </div>
            <div class="stat">
              <span class="label">{t("perf.processPeak")}</span>
              <strong class="value">{size(session.processPeakMb)}</strong>
              <span class="tiny muted">{t("perf.systemFree", { size: size(session.systemFreeMinMb) })}</span>
            </div>
          </div>
          {#if session.fpsAvg === null}
            <span class="tiny muted">{t("perf.noFpsData")}</span>
          {/if}
          {#if session.hints.length}
            <ul class="hints">
              {#each session.hints as h (h.kind)}
                <li class="hint-row {h.level}">
                  <Icon name={h.level === "ok" ? "check" : h.level === "warn" ? "alert" : "info"} size={15} />
                  <span class="small grow">{hintText(h)}</span>
                  {#if (h.kind === "lowMemory" || h.kind === "tooMuchMemory") && app.instances.some((i) => i.id === session.instanceId)}
                    <button
                      class="btn sm"
                      onclick={() => setMemory(session.instanceId, Number(h.params.suggest))}
                      >{t("perf.hints.setMemory", { size: size(Number(h.params.suggest)) })}</button
                    >
                  {:else if h.kind === "noSodium" && app.instances.some((i) => i.id === session.instanceId)}
                    <button
                      class="btn sm"
                      onclick={() => router.go("instance", { id: session.instanceId, tab: "content" })}
                      >{t("perf.hints.openMods")}</button
                    >
                  {/if}
                </li>
              {/each}
            </ul>
          {/if}
        </article>
      </section>
    {/if}

    {#if snap.top.length}
      <section class="col gap-2">
        <h2 class="section-title">{t("perf.topTitle")}</h2>
        <div class="top card">
          {#each snap.top as p (p.name)}
            <div class="prog">
              <span class="truncate" title={p.name}
                >{p.name}{#if p.usage.processes > 1}<span class="faint">
                    ×{p.usage.processes}</span
                  >{/if}</span
              >
              <Meter value={num(p.usage.cpu)} max={100} label={t("perf.cpu")} warn={0.5} danger={0.85} />
              <span class="num">{pct(num(p.usage.cpu))}</span>
              <span class="num muted">{size(p.usage.memoryMb)}</span>
            </div>
          {/each}
          <span class="tiny muted">{t("perf.topHint")}</span>
        </div>
      </section>
    {/if}

    {#if tips.length}
      <section class="tips card col gap-2">
        <h2 class="section-title">{t("perf.tipsTitle")}</h2>
        <ul>
          {#each tips as tip, i (i)}<li class="small"><Icon name="info" size={14} />{tip}</li>{/each}
        </ul>
      </section>
    {/if}
  {:else if !error}
    <Skeleton rows={4} height="3rem" />
  {/if}
</div>

<style>
  .perf {
    display: flex;
    flex-direction: column;
    gap: var(--sp-4);
    padding-bottom: var(--sp-5);
  }
  .page-header {
    flex-wrap: wrap;
    align-items: baseline;
  }
  .tiles {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(14rem, 1fr));
    gap: var(--sp-3);
  }
  .tile {
    display: flex;
    flex-direction: column;
    gap: 0.35rem;
    min-width: 0;
  }
  .value {
    font-size: 1.5rem;
    font-weight: 650;
    line-height: 1.15;
  }
  .hero {
    font-size: 3rem;
    font-weight: 700;
    line-height: 1;
  }
  .of {
    font-size: 0.85rem;
    font-weight: 500;
    color: var(--c-text-muted);
  }
  .game .grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(16rem, 1fr));
    gap: var(--sp-4);
    margin-top: var(--sp-2);
  }
  .empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--sp-2);
    padding: var(--sp-4);
    color: var(--c-text-muted);
  }
  .top {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
  }
  .prog {
    display: grid;
    grid-template-columns: minmax(8rem, 14rem) 1fr 3.5rem 4.5rem;
    align-items: center;
    gap: var(--sp-3);
    font-size: 0.875rem;
  }
  .prog .faint {
    margin-left: 0.3em;
  }
  .num {
    text-align: right;
    font-variant-numeric: tabular-nums;
  }
  .summary {
    display: flex;
    flex-direction: column;
    gap: var(--sp-3);
  }
  .stats {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(10rem, 1fr));
    gap: var(--sp-3);
  }
  .stat {
    display: flex;
    flex-direction: column;
    gap: 0.3rem;
    min-width: 0;
  }
  .hints {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: var(--sp-2);
  }
  .hint-row {
    display: flex;
    align-items: center;
    gap: var(--sp-2);
    padding: var(--sp-2) var(--sp-3);
    border-radius: var(--r-sm);
    background: var(--c-surface-2);
    flex-wrap: wrap;
  }
  .hint-row :global(.icon) {
    flex: none;
    color: var(--c-info);
  }
  .hint-row.warn :global(.icon) {
    color: var(--c-warning);
  }
  .hint-row.ok :global(.icon) {
    color: var(--c-success);
  }
  .tips ul {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 0.4rem;
  }
  .tips li {
    display: flex;
    gap: 0.5rem;
    align-items: flex-start;
  }
  .tips li :global(.icon) {
    flex: none;
    margin-top: 0.15rem;
    color: var(--c-info);
  }
</style>
