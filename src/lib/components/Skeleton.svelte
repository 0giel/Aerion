<script lang="ts">
  // Grey pulsing placeholders shaped like the content that is loading.
  let { rows = 3, height = "2.6rem" }: { rows?: number; height?: string } = $props();
</script>

<div class="col gap-2" aria-hidden="true">
  {#each Array.from({ length: rows }, (_, i) => i) as n (n)}
    <div class="skeleton" style:height style:width={n === rows - 1 && rows > 1 ? "70%" : "100%"}></div>
  {/each}
</div>
