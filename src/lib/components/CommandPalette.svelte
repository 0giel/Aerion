<script lang="ts">
  import Icon from "./Icon.svelte";
  import { router, type Page } from "../stores/router.svelte";
  import { app } from "../stores/app.svelte";
  import { themes } from "../theme/engine.svelte";
  import { t } from "../i18n/index.svelte";

  let { open = $bindable(false), onnew }: { open?: boolean; onnew: () => void } = $props();
  let query = $state("");
  let selected = $state(0);
  let input: HTMLInputElement | undefined = $state();

  interface Command {
    id: string;
    icon: string;
    label: string;
    hint?: string;
    run: () => void;
  }

  const pages: { page: Page; icon: string; key: string }[] = [
    { page: "home", icon: "home", key: "nav.home" },
    { page: "instances", icon: "grid", key: "nav.instances" },
    { page: "mods", icon: "package", key: "nav.mods" },
    { page: "accounts", icon: "user", key: "nav.accounts" },
    { page: "skins", icon: "shirt", key: "nav.skins" },
    { page: "stats", icon: "chart", key: "nav.stats" },
    { page: "console", icon: "terminal", key: "nav.console" },
    { page: "settings", icon: "settings", key: "nav.settings" },
  ];

  const commands = $derived.by((): Command[] => {
    const list: Command[] = pages.map((p, i) => ({
      id: `page:${p.page}`,
      icon: p.icon,
      label: t("palette.goTo", { page: t(p.key) }),
      hint: `Ctrl ${i + 1}`,
      run: () => router.go(p.page),
    }));
    list.push({ id: "new", icon: "plus", label: t("palette.newInstance"), hint: "Ctrl N", run: onnew });
    for (const inst of app.instances) {
      list.push({
        id: `play:${inst.id}`,
        icon: "play",
        label: t("palette.launch", { name: inst.name }),
        run: () => app.launch(inst.id),
      });
      list.push({
        id: `open:${inst.id}`,
        icon: "grid",
        label: t("palette.open", { name: inst.name }),
        run: () => router.go("instance", { id: inst.id }),
      });
    }
    list.push({
      id: "theme",
      icon: themes.active.base === "dark" ? "sun" : "moon",
      label: t("palette.toggleTheme"),
      run: () => {
        const s = app.settings;
        if (!s) return;
        const next = themes.active.base === "dark" ? s.themeLight || "light" : s.themeDark || "nocturne";
        void app.saveSettings({ theme: next, themeFollowSystem: false });
      },
    });
    list.push({
      id: "themes",
      icon: "palette",
      label: t("palette.reloadThemes"),
      run: () => void themes.loadUser().then(() => app.settings && themes.sync(app.settings)),
    });
    return list;
  });

  const fold = (s: string) => s.normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/ł/g, "l").toLowerCase();
  const filtered = $derived.by(() => {
    const words = fold(query).split(/\s+/).filter(Boolean);
    return commands.filter((c) => words.every((w) => fold(c.label).includes(w))).slice(0, 40);
  });

  $effect(() => {
    if (open) {
      query = "";
      selected = 0;
      setTimeout(() => input?.focus(), 0);
    }
  });
  $effect(() => {
    void query;
    selected = 0;
  });

  function run(c: Command | undefined) {
    if (!c) return;
    open = false;
    c.run();
  }

  function onkeydown(e: KeyboardEvent) {
    if (e.key === "ArrowDown") {
      selected = Math.min(filtered.length - 1, selected + 1);
      e.preventDefault();
    } else if (e.key === "ArrowUp") {
      selected = Math.max(0, selected - 1);
      e.preventDefault();
    } else if (e.key === "Enter") {
      run(filtered[selected]);
      e.preventDefault();
    } else if (e.key === "Escape") {
      open = false;
    }
  }
</script>

{#if open}
  <div
    class="backdrop"
    role="presentation"
    onmousedown={(e) => e.target === e.currentTarget && (open = false)}
  >
    <div class="palette" role="dialog" aria-modal="true" aria-label={t("palette.placeholder")}>
      <div class="search">
        <Icon name="search" size={18} />
        <input
          bind:this={input}
          bind:value={query}
          {onkeydown}
          placeholder={t("palette.placeholder")}
          role="combobox"
          aria-expanded="true"
          aria-controls="palette-list"
          aria-activedescendant={filtered[selected] ? `cmd-${selected}` : undefined}
        />
        <kbd>Esc</kbd>
      </div>
      <ul id="palette-list" role="listbox">
        {#each filtered as c, i (c.id)}
          <li id="cmd-{i}" role="option" aria-selected={i === selected}>
            <button onclick={() => run(c)} onmousemove={() => (selected = i)} tabindex="-1">
              <Icon name={c.icon} size={16} />
              <span class="grow truncate">{c.label}</span>
              {#if c.hint}<kbd>{c.hint}</kbd>{/if}
            </button>
          </li>
        {:else}
          <li class="none muted">{t("palette.empty")}</li>
        {/each}
      </ul>
    </div>
  </div>
{/if}

<style>
  .backdrop {
    position: fixed;
    inset: 0;
    z-index: 150;
    display: flex;
    justify-content: center;
    padding-top: 12vh;
    background: rgb(0 0 0 / 0.4);
  }
  .palette {
    width: min(38rem, calc(100vw - 2rem));
    max-height: 60vh;
    display: flex;
    flex-direction: column;
    background: var(--c-surface);
    border: 1px solid var(--c-border);
    border-radius: var(--r-xl);
    box-shadow: var(--shadow-lg);
    overflow: hidden;
    animation: fade-up var(--dur) var(--ease);
  }
  .search {
    display: flex;
    align-items: center;
    gap: 0.7rem;
    padding: 0.9rem 1.1rem;
    border-bottom: 1px solid var(--c-border);
    color: var(--c-text-muted);
  }
  .search input {
    flex: 1;
    border: 0;
    background: transparent;
    font-size: 1.05rem;
    outline: none;
    color: var(--c-text);
  }
  ul {
    list-style: none;
    margin: 0;
    padding: 0.4rem;
    overflow: auto;
  }
  li button {
    display: flex;
    align-items: center;
    gap: 0.7rem;
    width: 100%;
    padding: 0.6rem 0.75rem;
    border: 0;
    border-radius: var(--r-md);
    background: transparent;
    text-align: left;
    cursor: pointer;
    color: var(--c-text-muted);
  }
  li[aria-selected="true"] button {
    background: var(--c-accent-soft);
    color: var(--c-text);
  }
  .none {
    padding: 1rem;
    text-align: center;
  }
</style>
