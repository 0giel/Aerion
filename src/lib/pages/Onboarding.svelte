<script lang="ts">
  import Icon from "../components/Icon.svelte";
  import Logo from "../components/Logo.svelte";
  import { app } from "../stores/app.svelte";
  import { router } from "../stores/router.svelte";
  import { toasts } from "../stores/toasts.svelte";
  import { commands, errorMessage } from "../api";
  import { i18n, LOCALES, t } from "../i18n/index.svelte";

  let step = $state(0);
  let busy = $state(false);
  let latest = $state("");
  let clientId = $state("");
  const steps = [
    "onboarding.welcome",
    "onboarding.stepAccount",
    "onboarding.stepInstance",
    "onboarding.stepPlay",
  ];

  $effect(() => {
    commands
      .listGameVersions()
      .then((v) => (latest = v.latestRelease))
      .catch(() => {});
  });

  async function finish(play = false) {
    await app.saveSettings({ onboardingDone: true });
    router.go("home");
    if (play && app.lastInstance) void app.launch(app.lastInstance.id);
  }

  async function signIn() {
    busy = true;
    try {
      await commands.loginMicrosoft();
      await app.refreshAccounts();
      step = 2;
    } catch (e) {
      toasts.error(errorMessage(e));
    } finally {
      busy = false;
    }
  }

  async function saveClientId() {
    await app.saveSettings({ msaClientId: clientId.trim() || null });
    await app.refreshAccounts();
  }

  async function createInstance() {
    busy = true;
    try {
      const inst = await commands.createInstance({
        name: latest,
        gameVersion: latest,
        loader: "vanilla",
        loaderVersion: null,
        icon: null,
        group: null,
      });
      await app.refreshInstances();
      await app.saveSettings({ lastInstance: inst.id });
      step = 3;
    } catch (e) {
      toasts.error(errorMessage(e));
    } finally {
      busy = false;
    }
  }
</script>

<div class="onboarding">
  <div class="card raised box fade-up">
    <ol class="steps" aria-label="Steps">
      {#each steps as s, i (s)}<li class:done={i < step} class:current={i === step}>
          <span>{i + 1}</span>{t(s)}
        </li>{/each}
    </ol>

    {#if step === 0}
      <div class="col center gap-3 hero">
        <span class="logo"><Logo /></span>
        <h1>{t("onboarding.welcome")}</h1>
        <p class="muted">{t("onboarding.welcomeText")}</p>
        <div class="segmented" role="group" aria-label={t("onboarding.language")}>
          {#each LOCALES as l (l.id)}<button
              aria-pressed={i18n.locale === l.id}
              onclick={() => app.saveSettings({ language: l.id })}>{l.name}</button
            >{/each}
        </div>
        <button class="btn primary lg" onclick={() => (step = app.accounts?.accounts.length ? 2 : 1)}
          >{t("onboarding.start")}<Icon name="chevronRight" size={18} /></button
        >
      </div>
    {:else if step === 1}
      <div class="col gap-3">
        <h2>{t("onboarding.stepAccount")}</h2>
        <p class="muted">{t("onboarding.stepAccountText")}</p>
        {#if !app.accounts?.clientIdConfigured}
          <div class="alert">
            <Icon name="info" size={18} />
            <div class="col gap-2 grow">
              <span class="small">{t("accounts.clientIdText")}</span>
              <div class="row">
                <input
                  class="input mono"
                  bind:value={clientId}
                  placeholder={t("accounts.clientIdPlaceholder")}
                /><button class="btn" onclick={saveClientId} disabled={!clientId.trim()}
                  >{t("common.save")}</button
                >
              </div>
            </div>
          </div>
        {/if}
        <div class="row">
          <button class="btn primary lg" onclick={signIn} disabled={busy || !app.accounts?.clientIdConfigured}
            ><Icon name="user" size={18} />{busy ? t("accounts.signingIn") : t("accounts.signIn")}</button
          >
          <button class="btn ghost" onclick={() => (step = 2)}>{t("onboarding.skip")}</button>
        </div>
      </div>
    {:else if step === 2}
      <div class="col gap-3">
        <h2>{t("onboarding.stepInstance")}</h2>
        <p class="muted">{t("onboarding.stepInstanceText")}</p>
        {#if app.instances.length}
          <button class="btn primary" onclick={() => (step = 3)}>{t("common.next")}</button>
        {:else}
          <div class="row">
            <button class="btn primary lg" onclick={createInstance} disabled={busy || !latest}
              ><Icon name="plus" size={18} />Minecraft {latest}</button
            >
            <button class="btn ghost" onclick={() => (step = 3)}>{t("onboarding.later")}</button>
          </div>
        {/if}
      </div>
    {:else}
      <div class="col center gap-3 hero">
        <Icon name="sparkles" size={40} />
        <h2>{t("onboarding.stepPlay")}</h2>
        <p class="muted">{t("onboarding.stepPlayText")}</p>
        <div class="row">
          {#if app.lastInstance && app.selectedAccount}<button
              class="btn primary lg"
              onclick={() => finish(true)}><Icon name="play" size={18} />{t("common.play")}</button
            >{/if}
          <button class="btn lg" onclick={() => finish(false)}>{t("common.done")}</button>
        </div>
      </div>
    {/if}
  </div>
  <p class="tiny faint disclaimer">{t("app.disclaimer")}</p>
</div>

<style>
  .onboarding {
    height: 100%;
    display: grid;
    place-items: center;
    align-content: center;
    gap: 1rem;
    padding: 2rem;
  }
  .box {
    width: min(40rem, 100%);
    padding: var(--sp-6);
    display: flex;
    flex-direction: column;
    gap: var(--sp-5);
  }
  .steps {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    gap: 0.4rem;
    flex-wrap: wrap;
    font-size: 0.82rem;
    color: var(--c-text-faint);
  }
  .steps li {
    display: flex;
    align-items: center;
    gap: 0.4rem;
    padding: 0.25rem 0.6rem 0.25rem 0.3rem;
    border-radius: 99px;
    background: var(--c-hover);
  }
  .steps span {
    display: grid;
    place-items: center;
    width: 1.3rem;
    height: 1.3rem;
    border-radius: 50%;
    background: var(--c-surface-3);
    font-weight: 700;
  }
  .steps .current {
    color: var(--c-text);
    background: var(--c-accent-soft);
  }
  .steps .current span,
  .steps .done span {
    background: var(--c-accent);
    color: var(--c-accent-text);
  }
  .hero {
    text-align: center;
  }
  .logo {
    display: grid;
    width: 4.5rem;
    height: 4.5rem;
    color: var(--c-accent);
  }
  .logo :global(svg) {
    width: 100%;
    height: 100%;
  }
  .disclaimer {
    text-align: center;
  }
</style>
