<script lang="ts">
  import Icon from "../components/Icon.svelte";
  import Logo from "../components/Logo.svelte";
  import SkyIsland from "../components/SkyIsland.svelte";
  import SkinViewer from "../components/SkinViewer.svelte";
  import { DEFAULT_SKIN } from "../api";
  import { app } from "../stores/app.svelte";
  import { router } from "../stores/router.svelte";
  import { toasts } from "../stores/toasts.svelte";
  import { commands, errorMessage } from "../api";
  import { i18n, LOCALES, t } from "../i18n/index.svelte";

  let step = $state(0);
  /** The scene beside the card: pixels per model unit, and the view's tilt. */
  const SCENE = 6;
  const TILT = -20;
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
  <!-- The island is built as the steps go by; the player lands on it at the end. -->
  <aside class="scene" aria-hidden="true" style:--u="{SCENE}px">
    <span class="glow"></span>
    <div class="stage">
      {#key step}
        <div class="island pop"><SkyIsland scale={SCENE} pitch={TILT} stage={step} /></div>
      {/key}
      <div class="figure" class:shown={step === 3}>
        <SkinViewer
          skinUrl={app.selectedAccount?.skinUrl ?? DEFAULT_SKIN}
          capeUrl={app.selectedAccount?.capeUrl}
          slim={app.selectedAccount?.skinVariant === "slim"}
          follow={false}
          scale={SCENE}
          pitch={TILT}
          ground={false}
        />
      </div>
    </div>
  </aside>
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
    grid-template-columns: minmax(0, 40rem) minmax(0, 30rem);
    grid-template-areas:
      "box scene"
      "note note";
    justify-content: center;
    place-items: center;
    align-content: center;
    gap: 1rem 2rem;
    padding: 2rem;
  }
  .box {
    grid-area: box;
  }
  .disclaimer {
    grid-area: note;
  }
  .scene {
    grid-area: scene;
    position: relative;
    display: grid;
    place-items: center;
    width: 100%;
    min-height: 30rem;
  }
  .glow {
    position: absolute;
    left: 50%;
    top: 50%;
    width: 28rem;
    height: 28rem;
    transform: translate(-50%, -50%);
    border-radius: 50%;
    background: radial-gradient(
      closest-side,
      color-mix(in srgb, var(--c-accent) 22%, transparent),
      transparent
    );
  }
  .stage {
    position: relative;
    margin-top: -5rem;
  }
  /* The island's top centre sits exactly under the player's feet (see the home screen). */
  .island {
    position: absolute;
    left: calc((11 - 46) * var(--u));
    top: calc((18 + 16 * 0.9397 - 50) * var(--u));
  }
  :global([data-motion="on"]) .island.pop {
    animation: settle var(--dur-slow) var(--spring);
  }
  @keyframes settle {
    from {
      transform: translateY(10px) scale(0.97);
    }
  }
  .figure {
    position: relative;
    opacity: 0;
    pointer-events: none;
  }
  .figure.shown {
    opacity: 1;
  }
  :global([data-motion="on"]) .figure.shown {
    animation: land var(--dur-slow) var(--spring);
  }
  @keyframes land {
    from {
      opacity: 0;
      transform: translateY(-40px);
    }
  }
  @media (max-width: 1000px) {
    .onboarding {
      grid-template-columns: minmax(0, 40rem);
      grid-template-areas:
        "box"
        "note";
    }
    .scene {
      display: none;
    }
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
    border-radius: var(--r-sm);
    background: var(--c-hover);
  }
  .steps span {
    display: grid;
    place-items: center;
    width: 1.3rem;
    height: 1.3rem;
    border-radius: 4px;
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
