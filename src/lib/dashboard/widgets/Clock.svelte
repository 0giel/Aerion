<script lang="ts">
  import { i18n, t } from "../../i18n/index.svelte";
  import type { WidgetProps } from "../widgets";

  let { config, edit, onconfig }: WidgetProps = $props();
  let now = $state(new Date());
  const seconds = $derived(config.seconds === "1");
  $effect(() => {
    const timer = setInterval(() => (now = new Date()), 1000);
    return () => clearInterval(timer);
  });
</script>

<div class="clock">
  <strong
    >{now.toLocaleTimeString(i18n.locale, {
      hour: "2-digit",
      minute: "2-digit",
      second: seconds ? "2-digit" : undefined,
    })}</strong
  >
  <span class="muted small"
    >{now.toLocaleDateString(i18n.locale, { weekday: "long", day: "numeric", month: "long" })}</span
  >
  {#if edit}
    <label class="row small"
      ><input
        type="checkbox"
        checked={seconds}
        onchange={(e) => onconfig({ ...config, seconds: (e.target as HTMLInputElement).checked ? "1" : "0" })}
      />{t("widgets.showSeconds")}</label
    >
  {/if}
</div>

<style>
  .clock {
    display: flex;
    flex-direction: column;
    justify-content: center;
    height: 100%;
  }
  strong {
    font-size: 2rem;
    font-variant-numeric: tabular-nums;
    line-height: 1.1;
  }
</style>
