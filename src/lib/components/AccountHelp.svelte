<script lang="ts">
  // Why "Play" did not start, in plain words, with the next step as a button.
  import { openUrl } from "@tauri-apps/plugin-opener";
  import Modal from "./Modal.svelte";
  import Icon from "./Icon.svelte";
  import { ui } from "../stores/ui.svelte";
  import { router } from "../stores/router.svelte";
  import { t } from "../i18n/index.svelte";

  const BUY_URL = "https://www.minecraft.net/store/minecraft-java-bedrock-edition-pc";

  type Kind = "signIn" | "relogin" | "license" | "pending";
  const kind = $derived.by((): Kind => {
    switch (ui.accountHelp) {
      case "auth.sessionExpired":
        return "relogin";
      case "auth.noLicense":
      case "auth.noProfile":
        return "license";
      case "auth.clientIdMissing":
      case "auth.invalidAppRegistration":
        return "pending";
      default:
        return "signIn";
    }
  });

  function close() {
    ui.accountHelp = null;
  }
  function accounts() {
    close();
    router.go("accounts");
  }
</script>

<Modal open={!!ui.accountHelp} onclose={close} title={t(`accountHelp.${kind}.title`)} width="30rem">
  <div class="col gap-3">
    <p>{t(`accountHelp.${kind}.text`)}</p>
    {#if kind === "signIn"}
      <ol class="steps small">
        <li>{t("accountHelp.signIn.step1")}</li>
        <li>{t("accountHelp.signIn.step2")}</li>
        <li>{t("accountHelp.signIn.step3")}</li>
      </ol>
    {/if}
    {#if kind === "license" || kind === "signIn"}
      <p class="small muted">{t("accountHelp.offlineNote")}</p>
    {/if}
  </div>
  {#snippet footer()}
    <button class="btn ghost" onclick={close}>{t("common.close")}</button>
    {#if kind === "license" || kind === "signIn"}
      <button class="btn" onclick={() => openUrl(BUY_URL)}
        ><Icon name="external" size={15} />{t("accountHelp.buy")}</button
      >
    {/if}
    {#if kind !== "pending"}
      <button class="btn primary" onclick={accounts}
        ><Icon name="user" size={15} />{t(
          kind === "relogin" ? "accountHelp.signInAgain" : "accountHelp.signInButton",
        )}</button
      >
    {/if}
  {/snippet}
</Modal>

<style>
  .steps {
    margin: 0;
    padding-left: 1.2rem;
    display: grid;
    gap: 0.3rem;
  }
</style>
