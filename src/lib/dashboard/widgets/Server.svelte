<script lang="ts">
  import Icon from "../../components/Icon.svelte";
  import { app } from "../../stores/app.svelte";
  import { toasts } from "../../stores/toasts.svelte";
  import { commands, errorMessage, type ServerStatus } from "../../api";
  import { t } from "../../i18n/index.svelte";
  import type { WidgetProps } from "../widgets";

  let { config, edit, onconfig }: WidgetProps = $props();
  let status = $state.raw<ServerStatus | null>(null);
  let failed = $state(false);
  const address = $derived(config.address ?? "");

  $effect(() => {
    const a = address;
    status = null;
    failed = false;
    if (!a) return;
    const check = () =>
      commands
        .serverStatus(a)
        .then((s) => ((status = s), (failed = false)))
        .catch(() => (failed = true));
    void check();
    const timer = setInterval(check, 60_000);
    return () => clearInterval(timer);
  });

  async function join() {
    const inst = app.lastInstance;
    if (!inst || !address) return;
    await commands.joinServer(inst.id, address).catch((e) => toasts.error(errorMessage(e)));
  }
</script>

<div class="server">
  {#if edit || !address}
    <input
      class="input"
      placeholder={t("widgets.serverAddress")}
      value={address}
      onchange={(e) => onconfig({ ...config, address: (e.target as HTMLInputElement).value.trim() })}
    />
  {/if}
  {#if address}
    <div class="row">
      {#if status?.favicon}<img src={status.favicon} alt="" />{/if}
      <div class="col gap-1 grow" style="min-width:0">
        <strong class="truncate">{address}</strong>
        <span class="tiny muted truncate"
          >{status
            ? t("widgets.serverOnline", { online: status.online, max: status.max, ping: status.pingMs })
            : failed
              ? t("widgets.serverOffline")
              : t("common.loading")}</span
        >
      </div>
      {#if status && app.lastInstance}<button class="btn sm" onclick={join}
          ><Icon name="play" size={14} />{t("instance.servers.join")}</button
        >{/if}
    </div>
  {/if}
</div>

<style>
  .server {
    display: flex;
    flex-direction: column;
    gap: var(--sp-2);
    justify-content: center;
    height: 100%;
  }
  img {
    width: 2.4rem;
    height: 2.4rem;
    image-rendering: pixelated;
    border-radius: var(--r-sm);
  }
</style>
