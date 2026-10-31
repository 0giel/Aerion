<script lang="ts">
  // The in-game server list with live status and "Join".
  import Icon from "../../components/Icon.svelte";
  import { app } from "../../stores/app.svelte";
  import { ui } from "../../stores/ui.svelte";
  import { toasts } from "../../stores/toasts.svelte";
  import { commands, errorMessage, type Instance } from "../../api";
  import type { SavedServer, ServerStatus } from "../../bindings";
  import { t } from "../../i18n/index.svelte";

  let { instance }: { instance: Instance } = $props();
  let servers = $state.raw<SavedServer[] | null>(null);
  let status = $state<Record<string, ServerStatus | "offline" | "loading">>({});
  let name = $state("");
  let address = $state("");
  let adding = $state(false);
  const running = $derived(!!app.running[instance.id]);

  async function load() {
    servers = await commands.listServers(instance.id).catch(() => []);
    refresh();
  }
  function refresh() {
    for (const s of servers ?? []) {
      status[s.address] = "loading";
      commands
        .serverStatus(s.address)
        .then((st) => (status[s.address] = st))
        .catch(() => (status[s.address] = "offline"));
    }
  }
  $effect(() => {
    void instance.id;
    void load();
  });

  async function add() {
    adding = true;
    try {
      await commands.addServer(instance.id, name.trim() || address.trim(), address.trim());
      name = "";
      address = "";
      await load();
    } catch (e) {
      toasts.error(errorMessage(e));
    } finally {
      adding = false;
    }
  }

  async function remove(s: SavedServer) {
    await commands.removeServer(instance.id, s.address).catch((e) => toasts.error(errorMessage(e)));
    await load();
  }

  async function join(s: SavedServer) {
    if (!app.selectedAccount) {
      ui.accountHelp = "auth.noAccount";
      return;
    }
    await commands.joinServer(instance.id, s.address).catch((e) => toasts.error(errorMessage(e)));
  }

  const pingClass = (ms: number) => (ms < 80 ? "good" : ms < 200 ? "ok" : "bad");
</script>

<div class="col gap-3">
  <form class="row gap-2 add card" onsubmit={(e) => (e.preventDefault(), add())}>
    <input
      class="input"
      bind:value={name}
      placeholder={t("instance.servers.name")}
      aria-label={t("instance.servers.name")}
      maxlength="64"
    />
    <input
      class="input mono grow"
      bind:value={address}
      placeholder={t("instance.servers.address")}
      aria-label={t("instance.servers.address")}
      maxlength="255"
    />
    <button class="btn primary" type="submit" disabled={adding || !address.trim()}
      ><Icon name="plus" size={15} />{t("instance.servers.add")}</button
    >
  </form>
  <div class="row">
    <span class="hint grow">{t("instance.servers.hint")}</span>
    <button class="btn sm ghost" onclick={refresh} disabled={!servers?.length}
      ><Icon name="refresh" size={14} />{t("instance.servers.refresh")}</button
    >
  </div>
  {#if servers === null}
    <div class="empty"><span class="spinner"></span></div>
  {:else if !servers.length}
    <div class="empty card">
      <Icon name="globe" size={32} />
      <p class="muted">{t("instance.servers.empty")}</p>
    </div>
  {:else}
    {#each servers as s (s.address)}
      {@const st = status[s.address]}
      {@const live = typeof st === "object" ? st : null}
      <article class="server card row">
        {#if live?.favicon || s.icon}
          <img class="sicon" src={live?.favicon ?? `data:image/png;base64,${s.icon}`} alt="" />
        {:else}
          <span class="sicon"><Icon name="globe" size={20} /></span>
        {/if}
        <div class="col gap-1 grow" style="min-width:0">
          <div class="row gap-2">
            <strong class="truncate">{s.name || s.address}</strong>
            <span class="tiny faint mono truncate">{s.address}</span>
          </div>
          <span class="small muted truncate motd"
            >{#if st === "loading"}{t("instance.servers.checking")}{:else if st === "offline"}{t(
                "instance.servers.offline",
              )}{:else if live}{live.motd || live.version}{/if}</span
          >
        </div>
        {#if live}
          <div class="col meta">
            <span class="small"><Icon name="user" size={13} /> {live.online}/{live.max}</span>
            <span class="tiny ping {pingClass(live.pingMs)}">{live.pingMs} ms</span>
          </div>
        {:else if st === "offline"}
          <span class="badge">{t("instance.servers.offlineBadge")}</span>
        {/if}
        <button class="btn primary" disabled={running} onclick={() => join(s)}
          ><Icon name="play" size={15} />{t("instance.servers.join")}</button
        >
        <button class="icon-btn sm" aria-label={t("common.remove")} onclick={() => remove(s)}
          ><Icon name="trash" size={15} /></button
        >
      </article>
    {/each}
  {/if}
</div>

<style>
  .add .input:first-child {
    width: 12rem;
  }
  .server {
    gap: var(--sp-3);
  }
  .sicon {
    width: 44px;
    height: 44px;
    flex: none;
    border-radius: var(--r-sm);
    display: grid;
    place-items: center;
    background: var(--c-surface-2);
    color: var(--c-text-faint);
    image-rendering: pixelated;
  }
  .meta {
    align-items: flex-end;
    gap: 2px;
    white-space: nowrap;
  }
  .ping.good {
    color: var(--c-success);
  }
  .ping.ok {
    color: var(--c-warning);
  }
  .ping.bad {
    color: var(--c-danger);
  }
  .empty {
    display: grid;
    justify-items: center;
    gap: var(--sp-2);
    padding: var(--sp-5);
    color: var(--c-text-faint);
  }
</style>
