<script lang="ts">
  // Arrange the sidebar or the home screen: order (↑/↓ buttons, keyboard friendly), visibility,
  // and a few options. Changes apply immediately.
  import Modal from "./Modal.svelte";
  import Icon from "./Icon.svelte";
  import Switch from "./Switch.svelte";
  import { ui } from "../stores/ui.svelte";
  import { layout, NAV_MODES, NAV_SIDES, type PlayerSide } from "../stores/layout.svelte";
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

  // Drag a row onto another to reorder (arrows stay for the keyboard).
  let dragFrom = $state<number | null>(null);
  let dragOver = $state<number | null>(null);
  function drop(to: number) {
    const from = dragFrom;
    dragFrom = dragOver = null;
    if (from === null || from === to) return;
    const next = [...items];
    const [it] = next.splice(from, 1);
    next.splice(to, 0, it);
    void save(next);
  }

  function toggleGap(id: string) {
    const gaps = layout.navGaps;
    void run(layout.setNavGaps(gaps.includes(id) ? gaps.filter((g) => g !== id) : [...gaps, id]));
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
    <span class="hint">{t("layout.dragHint")}</span>
    <ol class="list">
      {#each items as it, i (it.id)}
        <li
          class="row"
          class:hidden={!it.visible}
          class:over={dragOver === i && dragFrom !== null && dragFrom !== i}
          class:dragging={dragFrom === i}
          draggable="true"
          ondragstart={(e) => {
            dragFrom = i;
            e.dataTransfer?.setData("text/plain", it.id);
            if (e.dataTransfer) e.dataTransfer.effectAllowed = "move";
          }}
          ondragover={(e) => {
            e.preventDefault();
            dragOver = i;
          }}
          ondrop={(e) => {
            e.preventDefault();
            drop(i);
          }}
          ondragend={() => (dragFrom = dragOver = null)}
        >
          <span class="grip" aria-hidden="true"><Icon name="grip" size={15} /></span>
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
          {#if mode === "nav"}
            <button
              class="icon-btn sm"
              aria-pressed={layout.navGaps.includes(it.id)}
              class:on={layout.navGaps.includes(it.id)}
              aria-label={t("layout.gap", { name: t(it.key) })}
              title={t("layout.gap", { name: t(it.key) })}
              onclick={() => toggleGap(it.id)}><Icon name="divider" size={15} /></button
            >
          {/if}
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
      <div class="field">
        <span class="label">{t("settings.ui.navSide")}</span>
        <div class="segmented" role="group" aria-label={t("settings.ui.navSide")}>
          {#each NAV_SIDES as v (v)}
            <button
              type="button"
              aria-pressed={layout.navSide === v}
              onclick={() => void run(layout.setNavSide(v))}>{t(`settings.ui.sides.${v}`)}</button
            >
          {/each}
        </div>
      </div>
      <div class="field">
        <span class="label">{t("settings.ui.navMode")}</span>
        <div class="segmented" role="group" aria-label={t("settings.ui.navMode")}>
          {#each NAV_MODES as v (v)}
            <button
              type="button"
              aria-pressed={layout.navMode === v}
              onclick={() => void run(layout.setNavMode(v))}>{t(`settings.ui.modes.${v}`)}</button
            >
          {/each}
        </div>
      </div>
      <div class="field">
        <label for="navicon">{t("settings.ui.iconSize")}: {layout.navIconSize}px</label>
        <input
          id="navicon"
          type="range"
          min="15"
          max="26"
          step="1"
          value={layout.navIconSize}
          oninput={(e) => void run(layout.setNavIconSize(+(e.target as HTMLInputElement).value))}
        />
      </div>
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
  .list li {
    cursor: grab;
    transition:
      transform var(--dur-slow) var(--spring),
      border-color var(--dur) var(--ease),
      opacity var(--dur) var(--ease);
  }
  .list li.dragging {
    opacity: 0.4;
  }
  .list li.over {
    border-color: var(--c-accent);
    transform: translateY(4px) scale(1.02);
  }
  .grip {
    display: grid;
    color: var(--c-text-faint);
  }
  .icon-btn.on {
    color: var(--c-accent);
    background: var(--c-accent-soft);
  }
</style>
