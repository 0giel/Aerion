<script lang="ts">
  import Logo from "../../components/Logo.svelte";
  import { app } from "../../stores/app.svelte";
  import { t } from "../../i18n/index.svelte";
  import type { WidgetProps } from "../widgets";

  // Widgets without options still take the shared props.
  const _props: WidgetProps = $props();
  const account = $derived(app.selectedAccount);
</script>

<div class="greet">
  <span class="logo"><Logo /></span>
  <div class="col gap-1" style="min-width:0">
    <strong class="title">Aerion</strong>
    <span class="muted truncate"
      >{account ? t("home.greeting", { name: account.username }) : t("home.greetingAnon")}</span
    >
  </div>
</div>

<style>
  .greet {
    display: flex;
    align-items: center;
    gap: var(--sp-3);
    height: 100%;
  }
  .logo {
    width: 3rem;
    height: 3rem;
    flex: none;
    color: var(--c-text);
  }
  .title {
    font-size: 1.5rem;
  }
</style>
