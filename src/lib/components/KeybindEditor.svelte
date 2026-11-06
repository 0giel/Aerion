<script lang="ts">
  // Key bindings on a keyboard and a mouse: bound keys light up, keys with several actions turn
  // red, and a binding changes by pressing the new key. Works on an instance's game settings or
  // on the shared ones (instanceId null); writes options.txt through the launcher.
  import Modal from "./Modal.svelte";
  import Icon from "./Icon.svelte";
  import { commands, errorMessage, type KeyBinding } from "../api";
  import { toasts } from "../stores/toasts.svelte";
  import { has, t } from "../i18n/index.svelte";

  let {
    open = $bindable(false),
    instanceId = null,
    title,
  }: { open?: boolean; instanceId?: string | null; title?: string } = $props();

  let original = $state.raw<KeyBinding[]>([]);
  let draft = $state<KeyBinding[]>([]);
  let loading = $state(true);
  let saving = $state(false);
  let capturing = $state<string | null>(null);
  let selectedKey = $state<string | null>(null);
  let query = $state("");

  $effect(() => {
    if (!open) return;
    loading = true;
    capturing = null;
    selectedKey = null;
    commands
      .keyBindings(instanceId)
      .then((b) => {
        original = b;
        draft = b.map((x) => ({ ...x }));
      })
      .catch((e) => toasts.error(errorMessage(e)))
      .finally(() => (loading = false));
  });

  // --- keys --------------------------------------------------------------------------------------
  const NONE = "key.keyboard.unknown";
  type Key = { k: string; label: string; w?: number };
  const k = (name: string, label: string, w = 1): Key => ({ k: `key.keyboard.${name}`, label, w });
  const letters = (s: string) => [...s].map((c) => k(c, c.toUpperCase()));
  const ROWS: (Key | number)[][] = [
    [
      k("escape", "Esc"),
      0.75,
      k("f1", "F1"),
      k("f2", "F2"),
      k("f3", "F3"),
      k("f4", "F4"),
      0.5,
      k("f5", "F5"),
      k("f6", "F6"),
      k("f7", "F7"),
      k("f8", "F8"),
      0.5,
      k("f9", "F9"),
      k("f10", "F10"),
      k("f11", "F11"),
      k("f12", "F12"),
    ],
    [
      k("grave.accent", "`"),
      ...letters("1234567890"),
      k("minus", "-"),
      k("equal", "="),
      k("backspace", "⌫", 2),
    ],
    [
      k("tab", "Tab", 1.5),
      ...letters("qwertyuiop"),
      k("left.bracket", "["),
      k("right.bracket", "]"),
      k("backslash", "\\", 1.5),
    ],
    [
      k("caps.lock", "Caps", 1.75),
      ...letters("asdfghjkl"),
      k("semicolon", ";"),
      k("apostrophe", "'"),
      k("enter", "Enter", 2.25),
    ],
    [
      k("left.shift", "Shift", 2.25),
      ...letters("zxcvbnm"),
      k("comma", ","),
      k("period", "."),
      k("slash", "/"),
      k("right.shift", "Shift", 2.75),
    ],
    [
      k("left.control", "Ctrl", 1.25),
      k("left.win", "Win", 1.25),
      k("left.alt", "Alt", 1.25),
      k("space", "", 6.25),
      k("right.alt", "Alt", 1.25),
      k("right.win", "Win", 1.25),
      k("menu", "☰", 1.25),
      k("right.control", "Ctrl", 1.25),
    ],
  ];
  const NAV: (Key | null)[][] = [
    [k("print.screen", "PrtSc"), k("scroll.lock", "ScrLk"), k("pause", "Pause")],
    [k("insert", "Ins"), k("home", "Home"), k("page.up", "PgUp")],
    [k("delete", "Del"), k("end", "End"), k("page.down", "PgDn")],
    [null, null, null],
    [null, k("up", "↑"), null],
    [k("left", "←"), k("down", "↓"), k("right", "→")],
  ];
  const MOUSE: Key[] = [
    { k: "key.mouse.left", label: "LMB" },
    { k: "key.mouse.middle", label: "MMB" },
    { k: "key.mouse.right", label: "RMB" },
    { k: "key.mouse.4", label: "M4" },
    { k: "key.mouse.5", label: "M5" },
  ];

  /** Browser key codes → Minecraft's key names (layout-independent, as the game reads keys). */
  function fromCode(code: string): string | null {
    const special: Record<string, string> = {
      Space: "space",
      Enter: "enter",
      Escape: "escape",
      Tab: "tab",
      Backspace: "backspace",
      Insert: "insert",
      Delete: "delete",
      Home: "home",
      End: "end",
      PageUp: "page.up",
      PageDown: "page.down",
      ArrowUp: "up",
      ArrowDown: "down",
      ArrowLeft: "left",
      ArrowRight: "right",
      ShiftLeft: "left.shift",
      ShiftRight: "right.shift",
      ControlLeft: "left.control",
      ControlRight: "right.control",
      AltLeft: "left.alt",
      AltRight: "right.alt",
      MetaLeft: "left.win",
      MetaRight: "right.win",
      CapsLock: "caps.lock",
      NumLock: "num.lock",
      ScrollLock: "scroll.lock",
      PrintScreen: "print.screen",
      Pause: "pause",
      Minus: "minus",
      Equal: "equal",
      BracketLeft: "left.bracket",
      BracketRight: "right.bracket",
      Backslash: "backslash",
      Semicolon: "semicolon",
      Quote: "apostrophe",
      Comma: "comma",
      Period: "period",
      Slash: "slash",
      Backquote: "grave.accent",
      ContextMenu: "menu",
      NumpadAdd: "keypad.add",
      NumpadSubtract: "keypad.subtract",
      NumpadMultiply: "keypad.multiply",
      NumpadDivide: "keypad.divide",
      NumpadDecimal: "keypad.decimal",
      NumpadEnter: "keypad.enter",
      NumpadEqual: "keypad.equal",
    };
    if (special[code]) return `key.keyboard.${special[code]}`;
    let m = code.match(/^Key([A-Z])$/);
    if (m) return `key.keyboard.${m[1].toLowerCase()}`;
    m = code.match(/^Digit(\d)$/);
    if (m) return `key.keyboard.${m[1]}`;
    m = code.match(/^F(\d{1,2})$/);
    if (m) return `key.keyboard.f${m[1]}`;
    m = code.match(/^Numpad(\d)$/);
    if (m) return `key.keyboard.keypad.${m[1]}`;
    return null;
  }

  /** A short name for a key wherever it is shown. */
  function keyLabel(key: string): string {
    if (key === NONE || !key) return t("keybinds.none");
    const all = [...ROWS.flat(), ...NAV.flat(), ...MOUSE].filter(
      (x): x is Key => !!x && typeof x === "object",
    );
    const found = all.find((x) => x.k === key);
    if (found) return found.label || t("keybinds.space");
    const rest = key.replace(/^key\.(keyboard|mouse)\./, "");
    if (rest.startsWith("keypad.")) return `Num ${rest.slice(7)}`;
    return rest.replace(/\./g, " ").toUpperCase();
  }

  // --- actions -----------------------------------------------------------------------------------
  /**
   * Where an action works: F3 combinations (key.debug.*) only with F3 held, spectator actions only
   * in spectator mode. Keys clash only within one context: A for "left" and "F3 + A" is fine.
   */
  function context(a: string): string {
    // The F3 key is both the debug screen and the modifier for the combinations: one key, by design.
    if (a === "key.debug.modifier") return "modifier";
    if (a.startsWith("key.debug.") && a !== "key.debug.overlay") return "debug";
    if (/spectator/i.test(a)) return "spectator";
    return "game";
  }

  const GROUPS: [string, (a: string) => boolean][] = [
    ["movement", (a) => /^key\.(forward|left|back|right|jump|sneak|sprint)$/.test(a)],
    ["gameplay", (a) => /^key\.(attack|use|pickItem|drop|swapOffhand)$/.test(a)],
    ["inventory", (a) => /^key\.(inventory|hotbar\.\d|saveToolbarActivator|loadToolbarActivator)$/.test(a)],
    ["multiplayer", (a) => /^key\.(chat|playerlist|command|socialInteractions)$/.test(a)],
    ["aerion", (a) => a.startsWith("key.aerion.")],
    ["debug", (a) => context(a) === "debug"],
    ["misc", (a) => /^key\.[a-zA-Z]+$/.test(a)],
    ["mods", () => true],
  ];
  const groupOf = (a: string) => GROUPS.find(([, f]) => f(a))![0];

  function actionName(a: string): string {
    const id = a.replace(/^key\./, "").replace(/\./g, "_");
    if (has(`keybinds.actions.${id}`)) return t(`keybinds.actions.${id}`);
    const tail = a.split(".").pop() ?? a;
    const words = tail.replace(/([a-z])([A-Z])/g, "$1 $2").replace(/_/g, " ");
    const pretty = words.charAt(0).toUpperCase() + words.slice(1);
    if (a.startsWith("key.aerion.")) return `Aerion: ${pretty}`;
    return context(a) === "debug" ? `F3 + ${pretty}` : pretty;
  }

  /** Actions per key; a plain record, rebuilt whenever a binding changes. */
  const byKey = $derived.by(() => {
    const m: Record<string, string[]> = {};
    for (const b of draft) if (b.key !== NONE) (m[b.key] ??= []).push(b.action);
    return m;
  });
  /** Actions that share their key with another action of the same context. */
  const clashing = $derived.by(() => {
    const out: Record<string, true> = {};
    for (const actions of Object.values(byKey)) {
      for (const a of actions) {
        if (actions.some((o) => o !== a && context(o) === context(a))) out[a] = true;
      }
    }
    return out;
  });
  const keyClashes = (key: string) => (byKey[key] ?? []).some((a) => clashing[a]);
  const conflicts = $derived(Object.keys(byKey).filter(keyClashes).length);
  const changed = $derived(draft.filter((b, i) => b.key !== original[i]?.key));

  const shown = $derived.by(() => {
    const q = query.trim().toLowerCase();
    return draft.filter(
      (b) =>
        (!selectedKey || b.key === selectedKey) &&
        (!q || actionName(b.action).toLowerCase().includes(q) || keyLabel(b.key).toLowerCase().includes(q)),
    );
  });
  const grouped = $derived(
    GROUPS.map(([g]) => [g, shown.filter((b) => groupOf(b.action) === g)] as const).filter(
      ([, l]) => l.length,
    ),
  );

  function assign(key: string) {
    const b = draft.find((x) => x.action === capturing);
    if (b) b.key = key;
    capturing = null;
  }

  // Ahead of the window's other listeners, so Esc cancels the capture rather than the dialog.
  function onkeydown(e: KeyboardEvent) {
    if (!capturing) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    if (e.code === "Escape") return void (capturing = null);
    const key = fromCode(e.code);
    if (key) assign(key);
  }

  function clickKey(key: string) {
    if (capturing) return assign(key);
    selectedKey = selectedKey === key ? null : key;
  }

  async function save() {
    saving = true;
    try {
      await commands.setKeyBindings(instanceId, $state.snapshot(changed));
      original = draft.map((x) => ({ ...x }));
      toasts.success(t("keybinds.saved"));
    } catch (e) {
      toasts.error(errorMessage(e));
    } finally {
      saving = false;
    }
  }
</script>

<svelte:window onkeydowncapture={onkeydown} />

<Modal bind:open title={title ?? t("keybinds.title")} width="64rem">
  {#if loading}
    <span class="muted small">{t("common.loading")}</span>
  {:else if !draft.length}
    <p class="small muted">{t(instanceId ? "keybinds.empty" : "keybinds.emptyShared")}</p>
  {:else}
    <div class="col gap-3 editor">
      <div class="board" class:capture={!!capturing} aria-label={t("keybinds.keyboard")}>
        {#snippet cap(key: Key)}
          {@const actions = byKey[key.k] ?? []}
          <button
            type="button"
            class="key"
            class:bound={actions.length > 0 && !keyClashes(key.k)}
            class:conflict={keyClashes(key.k)}
            class:sel={selectedKey === key.k}
            style:--w={key.w ?? 1}
            title={actions.map(actionName).join("\n") || keyLabel(key.k)}
            onclick={() => clickKey(key.k)}
            >{key.label}{#if keyClashes(key.k)}<span class="count">{actions.length}</span>{/if}</button
          >
        {/snippet}
        <div class="main">
          {#each ROWS as row, i (i)}
            <div class="row-keys" class:gap-top={i === 1}>
              {#each row as item, j (j)}
                {#if typeof item === "number"}<span class="spacer" style:--w={item}
                  ></span>{:else}{@render cap(item)}{/if}
              {/each}
            </div>
          {/each}
        </div>
        <div class="nav">
          {#each NAV as row, i (i)}
            <div class="row-keys" class:gap-top={i === 1}>
              {#each row as item, j (j)}
                {#if item}{@render cap(item)}{:else}<span class="spacer" style:--w={1}></span>{/if}
              {/each}
            </div>
          {/each}
        </div>
        <div class="mouse">
          <span class="tiny faint">{t("keybinds.mouse")}</span>
          <div class="mouse-body">
            {#each MOUSE as m (m.k)}{@render cap(m)}{/each}
          </div>
        </div>
      </div>

      {#if capturing}
        <div class="alert capture" aria-live="polite">
          <Icon name="key" size={16} /><span class="small"
            >{t("keybinds.press", { action: actionName(capturing) })}</span
          >
          <span class="grow"></span>
          <button class="btn sm" onclick={() => assign(NONE)}>{t("keybinds.clear")}</button>
          <button class="btn sm ghost" onclick={() => (capturing = null)}>{t("common.cancel")}</button>
        </div>
      {/if}

      <div class="row wrap gap-2">
        <input class="input grow" placeholder={t("keybinds.search")} bind:value={query} />
        {#if selectedKey}
          <button class="btn sm ghost" onclick={() => (selectedKey = null)}
            ><Icon name="x" size={14} />{keyLabel(selectedKey)}</button
          >
        {/if}
        {#if conflicts}<span class="small danger"
            ><Icon name="alert" size={14} />{t("keybinds.conflicts", { n: conflicts })}</span
          >{/if}
      </div>

      <div class="list">
        {#each grouped as [g, list] (g)}
          <div class="section-title">{t(`keybinds.groups.${g}`)}</div>
          {#each list as b (b.action)}
            {@const clash = !!clashing[b.action]}
            <div class="action" class:active={capturing === b.action}>
              <span class="grow truncate">{actionName(b.action)}</span>
              <button
                class="btn sm keycap"
                class:clash
                class:unset={b.key === NONE}
                onclick={() => (capturing = capturing === b.action ? null : b.action)}
                >{capturing === b.action ? t("keybinds.pressShort") : keyLabel(b.key)}</button
              >
            </div>
          {/each}
        {/each}
      </div>
    </div>
  {/if}
  {#snippet footer()}
    <span class="small muted grow">{t("keybinds.hint")}</span>
    <button
      class="btn ghost"
      disabled={!changed.length || saving}
      onclick={() => (draft = original.map((x) => ({ ...x })))}>{t("keybinds.undo")}</button
    >
    <button class="btn primary" disabled={!changed.length || saving} onclick={save}
      >{#if saving}<span class="spinner"></span>{/if}{t("keybinds.save", { n: changed.length })}</button
    >
  {/snippet}
</Modal>

<style>
  .editor {
    container-type: inline-size;
  }
  .board {
    --u: min(2.4rem, calc((100cqw - 2rem) / 23));
    display: flex;
    gap: calc(var(--u) * 0.4);
    align-items: flex-start;
    padding: var(--sp-3);
    border-radius: var(--r-md);
    background: var(--c-surface-2);
    overflow-x: auto;
  }
  .board.capture {
    outline: 2px solid var(--c-accent);
  }
  .row-keys {
    display: flex;
    gap: 3px;
    margin-bottom: 3px;
  }
  .gap-top {
    margin-top: calc(var(--u) * 0.25);
  }
  .key,
  .spacer {
    flex: none;
    width: calc(var(--u) * var(--w) + 3px * (var(--w) - 1));
    height: var(--u);
  }
  .key {
    position: relative;
    display: grid;
    place-items: center;
    padding: 0;
    border: 1px solid var(--c-border);
    border-radius: var(--r-sm);
    background: var(--c-surface);
    color: var(--c-text-muted);
    font-size: calc(var(--u) * 0.32);
    cursor: pointer;
    transition:
      background var(--dur) var(--ease),
      border-color var(--dur) var(--ease);
  }
  .key:hover {
    border-color: var(--c-accent);
  }
  .key.bound {
    background: var(--c-accent-soft);
    border-color: var(--c-accent);
    color: var(--c-text);
  }
  .key.conflict {
    background: color-mix(in srgb, var(--c-danger) 25%, var(--c-surface));
    border-color: var(--c-danger);
    color: var(--c-text);
  }
  .key.sel {
    outline: 2px solid var(--c-text);
    outline-offset: 1px;
  }
  .count {
    position: absolute;
    top: 1px;
    right: 3px;
    font-size: calc(var(--u) * 0.24);
  }
  .mouse {
    display: flex;
    flex-direction: column;
    gap: 4px;
    margin-left: calc(var(--u) * 0.2);
  }
  .mouse-body {
    display: grid;
    grid-template-columns: repeat(3, auto);
    gap: 3px;
  }
  .list {
    max-height: 18rem;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
  .action {
    display: flex;
    align-items: center;
    gap: var(--sp-2);
    padding: 0.2rem 0.4rem;
    border-radius: var(--r-sm);
  }
  .action:hover,
  .action.active {
    background: var(--c-surface-2);
  }
  .keycap {
    min-width: 7rem;
    justify-content: center;
  }
  .keycap.clash {
    border-color: var(--c-danger);
    color: var(--c-danger);
  }
  .keycap.unset {
    color: var(--c-text-faint);
  }
  .danger {
    color: var(--c-danger);
    display: inline-flex;
    gap: 0.3rem;
    align-items: center;
  }
</style>
