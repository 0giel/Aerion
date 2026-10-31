<script lang="ts">
  import { toasts } from "../stores/toasts.svelte";
  import Icon from "./Icon.svelte";
  import { t } from "../i18n/index.svelte";
</script>

<div class="toasts" role="status" aria-live="polite">
  {#each toasts.list as toast (toast.id)}
    <div class="toast {toast.kind}">
      <span class="glyph"
        ><Icon
          name={toast.kind === "error" ? "alert" : toast.kind === "success" ? "check" : "info"}
          size={16}
        /></span
      >
      <span class="text selectable">{toast.text}</span>
      {#if toast.action}
        <button
          class="btn sm ghost"
          onclick={() => {
            toast.action?.run();
            toasts.dismiss(toast.id);
          }}>{toast.action.label}</button
        >
      {/if}
      <button class="icon-btn sm" aria-label={t("common.dismiss")} onclick={() => toasts.dismiss(toast.id)}
        ><Icon name="x" size={14} /></button
      >
    </div>
  {/each}
</div>

<style>
  .toasts {
    position: fixed;
    left: 50%;
    bottom: 1.25rem;
    transform: translateX(-50%);
    z-index: 200;
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
    width: min(32rem, calc(100vw - 2rem));
    pointer-events: none;
  }
  .toast {
    pointer-events: auto;
    display: flex;
    align-items: center;
    gap: 0.6rem;
    padding: 0.55rem 0.6rem 0.55rem 0.85rem;
    border-radius: var(--r-lg);
    background: var(--c-surface-2);
    border: 1px solid var(--c-border);
    box-shadow: var(--shadow-md);
    animation: fade-up var(--dur-slow) var(--ease);
  }
  .text {
    flex: 1;
    font-size: 0.92rem;
  }
  .glyph {
    display: grid;
    place-items: center;
    width: 1.6rem;
    height: 1.6rem;
    border-radius: 50%;
    background: var(--c-hover);
  }
  .success .glyph {
    color: var(--c-success);
  }
  .error .glyph {
    color: var(--c-danger);
  }
  .info .glyph {
    color: var(--c-info);
  }
</style>
