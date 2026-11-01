<script lang="ts">
  import { t } from "../../i18n/index.svelte";
  import type { WidgetProps } from "../widgets";

  let { config, onconfig }: WidgetProps = $props();
  let timer: ReturnType<typeof setTimeout> | undefined;
</script>

<textarea
  class="notes"
  placeholder={t("widgets.notesPlaceholder")}
  aria-label={t("widgets.kinds.notes")}
  value={config.text ?? ""}
  oninput={(e) => {
    const text = (e.target as HTMLTextAreaElement).value.slice(0, 4000);
    clearTimeout(timer);
    timer = setTimeout(() => onconfig({ ...config, text }), 600);
  }}></textarea>

<style>
  .notes {
    width: 100%;
    height: 100%;
    resize: none;
    border: 0;
    background: transparent;
    color: var(--c-text);
    font: inherit;
    outline: none;
  }
</style>
