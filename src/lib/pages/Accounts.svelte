<script lang="ts">
  import { openUrl } from "@tauri-apps/plugin-opener";
  import Icon from "../components/Icon.svelte";
  import SkinFace from "../components/SkinFace.svelte";
  import { app } from "../stores/app.svelte";
  import { dialogs } from "../stores/dialogs.svelte";
  import { toasts } from "../stores/toasts.svelte";
  import { commands, errorMessage, type Account } from "../api";
  import { formatRelative, t } from "../i18n/index.svelte";

  let waiting = $state(false);
  let code = $state.raw<{ userCode: string; verificationUri: string } | null>(null);
  let offlineName = $state("");
  let clientId = $state(app.settings?.msaClientId ?? "");
  const view = $derived(app.accounts);

  async function finish(p: Promise<Account>) {
    waiting = true;
    try {
      const acc = await p;
      await app.refreshAccounts();
      toasts.success(t("accounts.added", { name: acc.username }));
    } catch (e) {
      if ((e as { code?: string })?.code !== "cancelled") toasts.error(errorMessage(e));
    } finally {
      waiting = false;
      code = null;
    }
  }

  const signIn = () => finish(commands.loginMicrosoft());

  async function useCode() {
    try {
      const dc = await commands.deviceCodeBegin();
      code = dc;
      await finish(commands.deviceCodeComplete());
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  }

  async function cancel() {
    await commands.cancelLogin();
    waiting = false;
    code = null;
  }

  async function addOffline() {
    try {
      await commands.addOfflineAccount(offlineName.trim());
      offlineName = "";
      await app.refreshAccounts();
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  }

  async function select(a: Account) {
    await commands.selectAccount(a.id).catch((e) => toasts.error(errorMessage(e)));
    await app.refreshAccounts();
  }

  async function remove(a: Account) {
    if (
      !(await dialogs.confirm({
        title: t("accounts.removeTitle", { name: a.username }),
        confirmLabel: t("accounts.remove"),
        danger: true,
      }))
    )
      return;
    await commands.removeAccount(a.id).catch((e) => toasts.error(errorMessage(e)));
    await app.refreshAccounts();
  }

  async function saveClientId() {
    await app
      .saveSettings({ msaClientId: clientId.trim() || null })
      .catch((e) => toasts.error(errorMessage(e)));
    await app.refreshAccounts();
    toasts.success(t("common.saved"));
  }
</script>

<div class="page">
  <header class="page-header"><h1>{t("accounts.title")}</h1></header>
  <div class="col gap-4 wrapw">
    {#if app.info && !app.info.keyringAvailable}
      <div class="alert warning">
        <Icon name="key" size={18} /><span class="small"
          >{t("accounts.keyringMissing", { error: app.info.keyringError ?? "" })}</span
        >
      </div>
    {/if}

    {#if view && !view.clientIdConfigured}
      <section class="card col gap-3">
        <div class="row">
          <Icon name="info" size={20} />
          <h3>{t("accounts.clientIdTitle")}</h3>
        </div>
        <p class="small muted">{t("accounts.clientIdText")}</p>
        <div class="row">
          <input
            class="input mono"
            bind:value={clientId}
            placeholder={t("accounts.clientIdPlaceholder")}
            aria-label={t("settings.clientId")}
          />
          <button class="btn primary" onclick={saveClientId} disabled={!clientId.trim()}
            >{t("accounts.clientIdSave")}</button
          >
        </div>
      </section>
    {/if}

    <section class="card col gap-3 signin">
      {#if code}
        <h3>{t("accounts.codeTitle")}</h3>
        <p class="small muted">{t("accounts.codeText", { url: code.verificationUri })}</p>
        <div class="row">
          <span class="code mono selectable">{code.userCode}</span>
          <button
            class="icon-btn"
            aria-label={t("common.copy")}
            onclick={() =>
              navigator.clipboard
                .writeText(code?.userCode ?? "")
                .then(() => toasts.success(t("common.copied")))}><Icon name="copy" /></button
          >
          <button class="btn" onclick={() => openUrl(code?.verificationUri ?? "")}
            ><Icon name="external" size={16} />{code.verificationUri}</button
          >
        </div>
        <div class="row small muted">
          <span class="spinner"></span>{t("accounts.codeWaiting")}<button
            class="btn sm ghost"
            onclick={cancel}>{t("common.cancel")}</button
          >
        </div>
      {:else if waiting}
        <div class="row"><span class="spinner"></span><strong>{t("accounts.signingIn")}</strong></div>
        <p class="small muted">{t("accounts.signInHint")}</p>
        <div class="row"><button class="btn" onclick={cancel}>{t("common.cancel")}</button></div>
      {:else}
        <div class="row wrap">
          <button class="btn primary lg" onclick={signIn} disabled={!view?.clientIdConfigured}
            ><Icon name="user" size={18} />{t("accounts.signIn")}</button
          >
          <button class="btn ghost" onclick={useCode} disabled={!view?.clientIdConfigured}
            >{t("accounts.useCode")}</button
          >
        </div>
        <p class="small faint">{t("accounts.signInHint")}</p>
      {/if}
    </section>

    {#if view?.accounts.length}
      <section class="col gap-2">
        {#each view.accounts as a (a.id)}
          {@const selected = view.selected === a.id}
          <article class="card account" class:selected>
            <SkinFace skinUrl={a.skinUrl} size={46} />
            <div class="col gap-1 grow" style="min-width:0">
              <div class="row" style="gap: 0.5rem">
                <strong class="truncate">{a.username}</strong>
                {#if selected}<span class="badge accent">{t("accounts.selected")}</span>{/if}
              </div>
              <span class="tiny faint"
                >{a.kind === "microsoft" ? t("accounts.microsoft") : t("accounts.offline")} · {formatRelative(
                  a.lastUsed,
                )}</span
              >
              {#if a.needsRelogin}<span class="tiny" style="color: var(--c-warning)"
                  >{t("accounts.reloginNeeded")}</span
                >{/if}
            </div>
            {#if a.needsRelogin}<button class="btn sm" onclick={signIn}>{t("accounts.signIn")}</button>{/if}
            {#if !selected}<button class="btn sm" onclick={() => select(a)}>{t("accounts.select")}</button
              >{/if}
            <button
              class="icon-btn"
              aria-label={t("accounts.remove")}
              title={t("accounts.remove")}
              onclick={() => remove(a)}><Icon name="logout" size={17} /></button
            >
          </article>
        {/each}
      </section>
    {:else}
      <div class="empty">
        <Icon name="user" size={32} />
        <p>{t("accounts.empty")}</p>
      </div>
    {/if}

    <section class="card col gap-3">
      <h3>{t("accounts.offline")}</h3>
      {#if view?.offlineAllowed}
        {#if app.info?.devOffline}<span class="badge warning">{t("accounts.devOffline")}</span>{/if}
        <div class="row">
          <input
            class="input"
            bind:value={offlineName}
            placeholder={t("accounts.offlineName")}
            maxlength="16"
            aria-label={t("accounts.offlineName")}
          />
          <button class="btn" onclick={addOffline} disabled={offlineName.trim().length < 3}
            ><Icon name="plus" size={16} />{t("accounts.offlineAdd")}</button
          >
        </div>
        <span class="hint">{t("accounts.offlineHint")}</span>
      {:else}
        <p class="small muted"><Icon name="shield" size={15} /> {t("accounts.offlineLocked")}</p>
      {/if}
    </section>
  </div>
</div>

<style>
  .wrapw {
    max-width: 46rem;
  }
  .account {
    display: flex;
    align-items: center;
    gap: var(--sp-3);
    padding: var(--sp-3) var(--sp-4);
  }
  .account.selected {
    border-color: color-mix(in srgb, var(--c-accent) 55%, var(--c-border));
  }
  .code {
    font-size: 1.7rem;
    letter-spacing: 0.18em;
    font-weight: 700;
    padding: 0.2rem 0.8rem;
    border-radius: var(--r-md);
    background: var(--c-surface-3);
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
