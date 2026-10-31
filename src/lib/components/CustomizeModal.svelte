<script lang="ts">
  // Arrange the sidebar or the home screen: order (↑/↓ buttons, keyboard friendly), visibility,
  // and a few options. Changes apply immediately.
  import Modal from "./Modal.svelte";
  import Icon from "./Icon.svelte";
  import Switch from "./Switch.svelte";
  import { ui } from "../stores/ui.svelte";
  import { layout, type PlayerSide } from "../stores/layout.svelte";
  import { toasts } from "../stores/toasts.svelte";
  import { errorMessage } from "../api";
  import { t } from "../i18n/index.svelte";

  const mode = $derived(ui.customize);
  const items = $derived(mode === "nav" ? layout.nav : mode === "home" ? layout.home : []);

  async function run(p: Promise<unknown> | undefined) {
    try {
      await p;
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  }

  function save(next: { id: string; visible: boolean }[]) {
    return run(mode === "nav" ? layout.saveNav(next) : layout.saveHome(next));
  }

  function move(i: number, d: number) {
    const next = [...items];
    const j = i + d;
    if (j < 0 || j >= next.length) return;
    [next[i], next[j]] = [next[j], next[i]];
    void save(next);
    // Keep keyboard focus on the moved row's same button.
    requestAnimationFrame(() =>
      document.querySelector<HTMLButtonElement>(`[data-move="${next[j].id}:${d}"]`)?.focus(),
    );
  }

  function toggle(i: number) {
    const next = items.map((it, k) => (k === i ? { ...it, visible: !it.visible } : it));
    void save(next);
  }

  function reset() {
    void run(mode === "nav" ? layout.resetNav() : layout.resetHome());
  }

  const sides: PlayerSide[] = ["left", "right", "hidden"];
</script>

<Modal
  open={!!mode}
  onclose={() => (ui.customize = null)}
  title={t(mode === "home" ? "layout.homeTitle" : "layout.navTitle")}
  width="28rem"
>
  <div class="col gap-3">
    <span class="hint">{t("layout.hint")}</span>
    <ol class="list">
      {#each items as it, i (it.id)}
        <li class="row" class:hidden={!it.visible}>
          <Icon name={it.icon} size={16} />
          <span class="grow">{t(it.key)}</span>
          <button
            class="icon-btn sm"
            data-move="{it.id}:-1"
            aria-label={t("layout.up", { name: t(it.key) })}
            disabled={i === 0}
            onclick={() => move(i, -1)}><Icon name="arrowUp" size={15} /></button
          >
          <button
            class="icon-btn sm"
            data-move="{it.id}:1"
            aria-label={t("layout.down", { name: t(it.key) })}
            disabled={i === items.length - 1}
            onclick={() => move(i, 1)}><Icon name="arrowDown" size={15} /></button
          >
          <button
            class="icon-btn sm"
            aria-pressed={it.visible}
            aria-label={t(it.visible ? "layout.hide" : "layout.show", { name: t(it.key) })}
            title={it.locked ? t("layout.locked") : ""}
            disabled={it.locked}
            onclick={() => toggle(i)}><Icon name={it.visible ? "eye" : "eyeOff"} size={15} /></button
          >
        </li>
      {/each}
    </ol>
    {#if mode === "nav"}
      <Switch
        label={t("layout.navFavorites")}
        hint={t("layout.navFavoritesHint")}
        checked={layout.navFavorites}
        onchange={(v) => void run(layout.setNavFavorites(v))}
      />
    {:else if mode === "home"}
      <div class="field">
        <span class="label">{t("layout.player")}</span>
        <div class="segmented" role="group" aria-label={t("layout.player")}>
          {#each sides as s (s)}
            <button
              type="button"
              aria-pressed={layout.player === s}
              onclick={() => void run(layout.setPlayer(s))}>{t(`layout.sides.${s}`)}</button
            >
          {/each}
        </div>
      </div>
    {/if}
  </div>
  {#snippet footer()}
    <button class="btn ghost" onclick={reset}><Icon name="undo" size={14} />{t("layout.reset")}</button>
    <span class="grow"></span>
    <button class="btn primary" onclick={() => (ui.customize = null)}>{t("layout.done")}</button>
  {/snippet}
</Modal>

<style>
  .list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: 0.3rem;
  }
  .list li {
    gap: var(--sp-2);
    padding: 0.4rem 0.5rem;
    border: 1px solid var(--c-border);
    border-radius: var(--r-md);
    background: var(--c-surface-2);
  }
  .list li.hidden {
    opacity: 0.55;
  }
</style>
