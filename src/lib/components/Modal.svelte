<script lang="ts">
  import type { Snippet } from "svelte";
  import Icon from "./Icon.svelte";
  import { t } from "../i18n/index.svelte";

  let {
    open = $bindable(false),
    title,
    width = "34rem",
    onclose,
    children,
    footer,
  }: {
    open?: boolean;
    title?: string;
    width?: string;
    onclose?: () => void;
    children: Snippet;
    footer?: Snippet;
  } = $props();

  let dialog: HTMLDivElement | undefined = $state();
  let previouslyFocused: Element | null = null;

  function close() {
    open = false;
    onclose?.();
  }

  function onkeydown(e: KeyboardEvent) {
    if (e.key === "Escape") {
      e.stopPropagation();
      close();
    } else if (e.key === "Tab" && dialog) {
      // Keep keyboard focus inside the dialog.
      const items = dialog.querySelectorAll<HTMLElement>(
        'button:not([disabled]), [href], input:not([disabled]), select, textarea, [tabindex]:not([tabindex="-1"])',
      );
      if (!items.length) return;
      const first = items[0];
      const last = items[items.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        last.focus();
        e.preventDefault();
      } else if (!e.shiftKey && document.activeElement === last) {
        first.focus();
        e.preventDefault();
      }
    }
  }

  $effect(() => {
    if (open && dialog) {
      previouslyFocused = document.activeElement;
      const target =
        dialog.querySelector<HTMLElement>("[autofocus], input, select, textarea, button.primary") ?? dialog;
      setTimeout(() => target.focus(), 0);
      return () => (previouslyFocused as HTMLElement | null)?.focus?.();
    }
  });
</script>

{#if open}
  <div class="backdrop" role="presentation" onmousedown={(e) => e.target === e.currentTarget && close()}>
    <div
      class="dialog"
      role="dialog"
      aria-modal="true"
      aria-label={title}
      tabindex="-1"
      style:max-width={width}
      bind:this={dialog}
      {onkeydown}
    >
      {#if title}
        <header>
          <h2>{title}</h2>
          <button class="icon-btn" onclick={close} aria-label={t("common.close")}><Icon name="x" /></button>
        </header>
      {/if}
      <div class="body">{@render children()}</div>
      {#if footer}<footer>{@render footer()}</footer>{/if}
    </div>
  </div>
{/if}

<style>
  .backdrop {
    position: fixed;
    inset: 0;
    z-index: 100;
    display: grid;
    place-items: center;
    padding: 2rem;
    background: rgb(0 0 0 / 0.5);
    -webkit-backdrop-filter: blur(4px);
    backdrop-filter: blur(4px);
    animation: fade var(--dur) var(--ease);
  }
  .dialog {
    width: 100%;
    max-height: calc(100vh - 4rem);
    display: flex;
    flex-direction: column;
    background: var(--c-surface);
    border: 1px solid var(--c-border);
    border-radius: var(--r-xl);
    box-shadow: var(--shadow-lg);
    animation: pop var(--dur) var(--ease);
  }
  header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
    padding: var(--sp-4) var(--sp-4) 0 var(--sp-5);
  }
  .body {
    padding: var(--sp-4) var(--sp-5);
    overflow: auto;
  }
  footer {
    display: flex;
    justify-content: flex-end;
    gap: var(--sp-2);
    padding: var(--sp-3) var(--sp-5) var(--sp-4);
    border-top: 1px solid var(--c-border);
  }
  @keyframes fade {
    from {
      opacity: 0;
    }
  }
  @keyframes pop {
    from {
      opacity: 0;
      transform: translateY(8px) scale(0.98);
    }
  }
</style>
