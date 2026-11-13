<script lang="ts">
  let { value = null, label }: { value?: number | null; label?: string } = $props();
</script>

<div
  class="bar"
  class:indeterminate={value === null}
  role="progressbar"
  aria-label={label}
  aria-valuemin={0}
  aria-valuemax={100}
  aria-valuenow={value === null ? undefined : Math.round(value * 100)}
>
  <div
    class="fill"
    style:width={value === null ? undefined : `${Math.max(2, Math.min(100, value * 100))}%`}
  ></div>
</div>

<style>
  .bar {
    height: 8px;
    border-radius: 2px;
    background: var(--c-hover);
    overflow: hidden;
    /* Cut into blocks: progress is built block by block. */
    -webkit-mask-image: repeating-linear-gradient(90deg, #000 0 8px, transparent 8px 10px);
    mask-image: repeating-linear-gradient(90deg, #000 0 8px, transparent 8px 10px);
  }
  .fill {
    height: 100%;
    box-shadow: inset 0 -2px 0 rgb(0 0 0 / 0.2);
    background: var(--c-accent);
    transition: width var(--dur) linear;
  }
  .indeterminate .fill {
    width: 35%;
    animation: slide 1.2s var(--ease) infinite;
  }
  @keyframes slide {
    from {
      transform: translateX(-100%);
    }
    to {
      transform: translateX(300%);
    }
  }
</style>
