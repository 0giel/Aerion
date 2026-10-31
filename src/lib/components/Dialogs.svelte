<script lang="ts">
  import Modal from "./Modal.svelte";
  import { dialogs } from "../stores/dialogs.svelte";
  import { t } from "../i18n/index.svelte";

  let value = $state("");
  const d = $derived(dialogs.current);

  $effect(() => {
    if (d?.kind === "prompt") value = d.value ?? "";
  });

  function submit(e: Event) {
    e.preventDefault();
    if (!d) return;
    dialogs.close(d.kind === "prompt" ? value : true);
  }
</script>

{#if d}
  <Modal open={true} title={d.title} width="28rem" onclose={() => dialogs.close(null)}>
    <form id="dialog-form" class="col gap-3" onsubmit={submit}>
      {#if d.text}<p class="muted">{d.text}</p>{/if}
      {#if d.kind === "prompt"}
        <!-- svelte-ignore a11y_autofocus -->
        <input class="input" bind:value placeholder={d.placeholder} autofocus />
      {/if}
    </form>
    {#snippet footer()}
      <button class="btn ghost" onclick={() => dialogs.close(null)}>{t("common.cancel")}</button>
      <button
        class="btn primary"
        class:danger={d.danger}
        class:solid={d.danger}
        type="submit"
        form="dialog-form"
      >
        {d.confirmLabel ?? t("common.confirm")}
      </button>
    {/snippet}
  </Modal>
{/if}
