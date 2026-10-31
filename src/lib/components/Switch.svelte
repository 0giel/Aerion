<script lang="ts">
  let {
    checked = $bindable(false),
    label,
    hint,
    disabled = false,
    onchange,
  }: {
    checked?: boolean;
    label: string;
    hint?: string;
    disabled?: boolean;
    onchange?: (v: boolean) => void;
  } = $props();
  const id = `sw-${Math.random().toString(36).slice(2, 9)}`;
</script>

<div class="switch-row" class:disabled>
  <div class="text">
    <label for={id}>{label}</label>
    {#if hint}<span class="hint">{hint}</span>{/if}
  </div>
  <button
    {id}
    type="button"
    role="switch"
    aria-checked={checked}
    aria-label={label}
    {disabled}
    class="switch"
    onclick={() => {
      checked = !checked;
      onchange?.(checked);
    }}
  >
    <span class="knob"></span>
  </button>
</div>

<style>
  .switch-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
    padding: 0.35rem 0;
  }
  .switch-row.disabled {
    opacity: 0.5;
  }
  .text {
    display: flex;
    flex-direction: column;
    gap: 0.1rem;
  }
  label {
    cursor: pointer;
  }
  .switch {
    flex: none;
    position: relative;
    width: 2.6rem;
    height: 1.5rem;
    border-radius: 99px;
    border: 1px solid var(--c-border);
    background: var(--c-surface-3);
    cursor: pointer;
    transition: background var(--dur) var(--ease);
  }
  .switch[aria-checked="true"] {
    background: var(--c-accent);
  }
  .knob {
    position: absolute;
    top: 50%;
    left: 0.18rem;
    width: 1.1rem;
    height: 1.1rem;
    border-radius: 50%;
    background: #fff;
    transform: translateY(-50%);
    transition: left var(--dur) var(--ease);
    box-shadow: 0 1px 3px rgb(0 0 0 / 0.3);
  }
  .switch[aria-checked="true"] .knob {
    left: calc(100% - 1.28rem);
  }
</style>
