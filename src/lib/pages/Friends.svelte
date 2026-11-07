<script lang="ts">
  // Friends without a server of ours: nicknames with faces from their skins, and the address a
  // friend shares when hosting a world (the Aerion mod's "Host a world" → e4mc), to join in one click.
  import Icon from "../components/Icon.svelte";
  import SkinFace from "../components/SkinFace.svelte";
  import { app } from "../stores/app.svelte";
  import { dialogs } from "../stores/dialogs.svelte";
  import { toasts } from "../stores/toasts.svelte";
  import { ui } from "../stores/ui.svelte";
  import { commands, DEFAULT_SKIN, errorMessage } from "../api";
  import type { Friend_Serialize as Friend, ServerStatus_Serialize as ServerStatus } from "../bindings";
  import { formatRelative, t } from "../i18n/index.svelte";

  const friends = $derived(app.settings?.friends ?? []);
  let name = $state("");
  let adding = $state(false);
  /** Live status per address: a status, `false` when nobody answers, absent while unknown. */
  let status = $state.raw<Record<string, ServerStatus | false>>({});

  async function saveFriends(next: Friend[]) {
    try {
      await app.saveSettings({ friends: next });
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  }
  const update = (n: string, patch: Partial<Friend>) =>
    saveFriends(friends.map((f) => (f.name === n ? { ...f, ...patch } : f)));

  async function add(e?: Event) {
    e?.preventDefault();
    const n = name.trim();
    if (!n || adding) return;
    if (!/^[A-Za-z0-9_]{1,16}$/.test(n)) {
      toasts.error(t("friends.badName"));
      return;
    }
    if (friends.some((f) => f.name.toLowerCase() === n.toLowerCase())) {
      toasts.info(t("friends.already", { name: n }));
      return;
    }
    adding = true;
    try {
      const p = await commands.lookupPlayer(n).catch(() => undefined);
      if (p === null) {
        const ok = await dialogs.confirm({
          title: t("friends.unknownTitle", { name: n }),
          text: t("friends.unknownText"),
          confirmLabel: t("friends.addAnyway"),
        });
        if (!ok) return;
      }
      const friend: Friend = {
        name: p?.name ?? n,
        uuid: p?.uuid ?? null,
        skinUrl: p?.skinUrl ?? null,
        address: null,
        instanceId: null,
        added: Math.floor(Date.now() / 1000),
      };
      await saveFriends([...friends, friend]);
      name = "";
    } finally {
      adding = false;
    }
  }

  /** The "more" menu closes on a click anywhere but its own button (and after choosing an item). */
  function closeMenus(e: MouseEvent) {
    const target = e.target as Element | null;
    for (const d of document.querySelectorAll<HTMLDetailsElement>("details.more[open]")) {
      const onItsButton = !!target?.closest("summary") && d.contains(target);
      if (!onItsButton) d.open = false;
    }
  }

  async function remove(f: Friend) {
    const ok = await dialogs.confirm({
      title: t("friends.removeTitle", { name: f.name }),
      confirmLabel: t("common.delete"),
      danger: true,
    });
    if (ok) await saveFriends(friends.filter((x) => x.name !== f.name));
  }

  async function refreshSkin(f: Friend) {
    const p = await commands.lookupPlayer(f.name).catch(() => null);
    if (p) await update(f.name, { name: p.name, uuid: p.uuid, skinUrl: p.skinUrl });
  }

  /** The address from the clipboard, or typed in when the clipboard can't be read. */
  async function setAddress(f: Friend, fromClipboard: boolean) {
    let text: string | null = null;
    if (fromClipboard) text = await navigator.clipboard.readText().catch(() => null);
    let address = text ? await commands.parseFriendAddress(text) : null;
    if (!address) {
      const typed = await dialogs.prompt({
        title: t("friends.addressTitle", { name: f.name }),
        text: t("friends.addressText"),
        value: f.address ?? "",
        placeholder: "abc-def.eu.e4mc.link",
      });
      if (typed === null) return;
      if (!typed.trim()) {
        await update(f.name, { address: null });
        return;
      }
      address = await commands.parseFriendAddress(typed);
      if (!address) {
        toasts.error(t("friends.badAddress"));
        return;
      }
    }
    await update(f.name, { address });
    void ping(address);
  }

  async function ping(address: string) {
    try {
      const s = await commands.serverStatus(address);
      // The e4mc relay answers for addresses nobody hosts right now, with 0 slots.
      status = { ...status, [address]: s.max > 0 ? s : false };
    } catch {
      status = { ...status, [address]: false };
    }
  }
  $effect(() => {
    const addresses = [...new Set(friends.map((f) => f.address).filter((a): a is string => !!a))];
    for (const a of addresses) void ping(a);
    const timer = setInterval(() => addresses.forEach((a) => void ping(a)), 30_000);
    return () => clearInterval(timer);
  });

  const joinable = $derived(app.instances);
  const instanceFor = (f: Friend) =>
    joinable.find((i) => i.id === f.instanceId) ?? app.lastInstance ?? joinable[0] ?? null;

  async function join(f: Friend) {
    const inst = instanceFor(f);
    if (!inst || !f.address) return;
    if (!app.selectedAccount) {
      ui.accountHelp = "auth.noAccount";
      return;
    }
    try {
      await commands.joinServer(inst.id, f.address);
      toasts.info(t("friends.joining", { name: f.name, instance: inst.name }));
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  }

  // Online friends first, then by name.
  const sorted = $derived(
    [...friends].sort((a, b) => {
      const on = (f: Friend) => (f.address && status[f.address] ? 0 : 1);
      return on(a) - on(b) || a.name.localeCompare(b.name);
    }),
  );
</script>

<svelte:window onclick={closeMenus} />

<div class="page friends">
  <header class="page-header">
    <h1>{t("friends.title")}</h1>
    <span class="small muted">{t("friends.subtitle")}</span>
  </header>

  <form class="add card row gap-2" onsubmit={add}>
    <input
      class="input grow"
      bind:value={name}
      maxlength="16"
      placeholder={t("friends.namePlaceholder")}
      aria-label={t("friends.namePlaceholder")}
      autocomplete="off"
      spellcheck="false"
    />
    <button class="btn primary" disabled={!name.trim() || adding}>
      {#if adding}<span class="spinner sm"></span>{:else}<Icon name="plus" size={15} />{/if}{t("friends.add")}
    </button>
  </form>

  {#if !friends.length}
    <div class="empty card">
      <Icon name="user" size={32} />
      <p class="muted">{t("friends.empty")}</p>
    </div>
  {:else}
    <div class="list">
      {#each sorted as f (f.name)}
        {@const st = f.address ? status[f.address] : undefined}
        {@const inst = instanceFor(f)}
        <article class="friend card" class:online={!!st}>
          <div class="who">
            <span class="face-wrap">
              <SkinFace skinUrl={f.skinUrl ?? DEFAULT_SKIN} size={44} />
              {#if f.address}<span class="dot" class:on={!!st} class:off={st === false}></span>{/if}
            </span>
            <div class="col min0">
              <strong class="truncate">{f.name}</strong>
              {#if !f.address}
                <span class="tiny muted">{t("friends.noAddress")}</span>
              {:else if st}
                <span class="tiny ok truncate" title={st.motd}
                  >{t("friends.hosting", { online: st.online, max: st.max, ping: st.pingMs })}</span
                >
              {:else if st === false}
                <span class="tiny muted truncate" title={f.address}>{t("friends.notHosting")}</span>
              {:else}
                <span class="tiny muted truncate">{f.address}</span>
              {/if}
            </div>
          </div>

          <div class="actions">
            <button class="btn sm ghost" onclick={() => setAddress(f, true)} title={t("friends.pasteHint")}
              ><Icon name="link" size={14} />{t("friends.paste")}</button
            >
            {#if f.address && joinable.length > 1}
              <select
                class="input sm pick"
                aria-label={t("friends.instance")}
                value={inst?.id ?? ""}
                onchange={(e) => update(f.name, { instanceId: e.currentTarget.value })}
              >
                {#each joinable as i (i.id)}<option value={i.id}>{i.name}</option>{/each}
              </select>
            {/if}
            <button
              class="btn sm primary"
              disabled={!f.address || !inst || (inst && app.isBusy(inst.id))}
              onclick={() => join(f)}
              title={f.address ? f.address : t("friends.noAddress")}
              ><Icon name="play" size={14} />{t("friends.join")}</button
            >
            <details class="more">
              <summary class="icon-btn" aria-label={t("friends.more")}><Icon name="more" size={16} /></summary
              >
              <div class="menu card">
                <button class="btn sm ghost" onclick={() => setAddress(f, false)}
                  ><Icon name="pencil" size={14} />{t("friends.editAddress")}</button
                >
                {#if f.address}
                  <button class="btn sm ghost" onclick={() => update(f.name, { address: null })}
                    ><Icon name="x" size={14} />{t("friends.clearAddress")}</button
                  >
                {/if}
                {#if f.uuid}
                  <button class="btn sm ghost" onclick={() => refreshSkin(f)}
                    ><Icon name="refresh" size={14} />{t("friends.refreshSkin")}</button
                  >
                {/if}
                <button class="btn sm ghost danger" onclick={() => remove(f)}
                  ><Icon name="trash" size={14} />{t("common.delete")}</button
                >
                <span class="tiny faint">{t("friends.added", { when: formatRelative(f.added) })}</span>
              </div>
            </details>
          </div>
        </article>
      {/each}
    </div>
  {/if}

  <section class="how card col gap-2">
    <h2 class="section-title">{t("friends.howTitle")}</h2>
    <ol class="small muted">
      <li>{t("friends.how1")}</li>
      <li>{t("friends.how2")}</li>
      <li>{t("friends.how3")}</li>
    </ol>
  </section>
</div>

<style>
  .friends {
    display: flex;
    flex-direction: column;
    gap: var(--sp-4);
  }
  .page-header {
    flex-wrap: wrap;
    align-items: baseline;
  }
  .add {
    padding: var(--sp-2);
  }
  .list {
    display: flex;
    flex-direction: column;
    gap: var(--sp-2);
  }
  .friend {
    position: relative;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--sp-3);
    flex-wrap: wrap;
    transition: border-color var(--dur) var(--ease);
  }
  /* A card with its menu open stands above the cards under it; otherwise they paint over the menu. */
  .friend:has(details[open]) {
    z-index: 10;
  }
  .friend.online {
    border-color: color-mix(in srgb, var(--c-success) 45%, var(--c-border));
  }
  .who {
    display: flex;
    align-items: center;
    gap: var(--sp-3);
    min-width: 0;
    flex: 1 1 14rem;
  }
  .min0 {
    min-width: 0;
  }
  .face-wrap {
    position: relative;
    display: inline-flex;
  }
  .dot {
    position: absolute;
    right: -3px;
    bottom: -3px;
    width: 12px;
    height: 12px;
    border-radius: 50%;
    border: 2px solid var(--c-surface);
    background: var(--c-text-faint);
  }
  .dot.on {
    background: var(--c-success);
  }
  .ok {
    color: var(--c-success);
  }
  .actions {
    display: flex;
    align-items: center;
    gap: var(--sp-1);
    flex-wrap: wrap;
  }
  .pick {
    max-width: 11rem;
  }
  .more {
    position: relative;
  }
  .more summary {
    list-style: none;
    cursor: pointer;
  }
  .more summary::-webkit-details-marker {
    display: none;
  }
  .menu {
    position: absolute;
    right: 0;
    top: calc(100% + 4px);
    z-index: 5;
    display: flex;
    flex-direction: column;
    align-items: stretch;
    gap: 2px;
    padding: var(--sp-1);
    min-width: 12rem;
    /* A solid panel: the row underneath must not show through. */
    background: var(--c-surface-2);
    backdrop-filter: none;
    box-shadow: var(--shadow-md);
  }
  .menu .btn {
    justify-content: flex-start;
  }
  .menu .faint {
    padding: 4px 8px 2px;
  }
  .empty {
    display: grid;
    justify-items: center;
    gap: var(--sp-2);
    padding: var(--sp-5);
    color: var(--c-text-faint);
  }
  .how ol {
    margin: 0;
    padding-left: 1.2rem;
    display: flex;
    flex-direction: column;
    gap: 0.3rem;
  }
</style>
