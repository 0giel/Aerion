<script lang="ts">
  // The app glyph, colored with `currentColor`.
  import glyph from "../../assets/glyph.svg?raw";
</script>

<!-- eslint-disable-next-line svelte/no-at-html-tags -- static SVG bundled with the app, not user input -->
<span class="logo-glyph">{@html glyph}</span>

<style>
  .logo-glyph {
    display: grid;
    width: 100%;
    height: 100%;
  }
  .logo-glyph :global(svg) {
    width: 100%;
    height: 100%;
  }
</style>
