<script lang="ts">
  // Processor and memory at a glance, sampled every two seconds while visible.
  import Meter from "../../components/Meter.svelte";
  import Sparkline from "../../components/Sparkline.svelte";
  import { router } from "../../stores/router.svelte";
  import { commands } from "../../api";
  import { t } from "../../i18n/index.svelte";
  import type { WidgetProps } from "../widgets";

  // Widgets without options still take the shared props.
  const _props: WidgetProps = $props();
  let cpu = $state.raw<number[]>([]);
  let used = $state(0);
  let total = $state(0);
  let aerion = $state(0);

  $effect(() => {
    const tick = async () => {
      if (document.hidden) return;
      const s = await commands.perfSnapshot().catch(() => null);
      if (!s) return;
      cpu = [...cpu, s.cpu.usage ?? 0].slice(-30);
      used = s.memoryUsedMb;
      total = s.memoryTotalMb;
      aerion = s.launcher.memoryMb;
    };
    void tick();
    const timer = setInterval(tick, 2000);
    return () => clearInterval(timer);
  });
  const gb = (mb: number) => `${(mb / 1024).toFixed(1)} GB`;
</script>

<button class="perf" onclick={() => router.go("performance")}>
  <span class="row between small"
    ><span class="muted">{t("perf.cpu")}</span><strong>{Math.round(cpu.at(-1) ?? 0)}%</strong></span
  >
  <Sparkline
    values={cpu}
    slots={30}
    max={100}
    format={(v) => `${Math.round(v)}%`}
    label={t("perf.cpuHistory")}
    ago={(s) => t("perf.secondsAgo", { n: s * 2 })}
    height={32}
  />
  <span class="row between small"
    ><span class="muted">{t("perf.memory")}</span><strong>{gb(used)} / {gb(total)}</strong></span
  >
  <Meter value={used} max={total || 1} label={t("perf.memory")} />
  <span class="tiny muted">{t("perf.aerion")}: {Math.round(aerion)} MB</span>
</button>

<style>
  .perf {
    display: flex;
    flex-direction: column;
    gap: 0.35rem;
    width: 100%;
    height: 100%;
    padding: 0;
    border: 0;
    background: none;
    color: inherit;
    text-align: left;
    cursor: pointer;
  }
</style>
