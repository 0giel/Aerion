<script lang="ts">
  // "What's new" after an update: shown once per version, then remembered in settings.
  import { openUrl } from "@tauri-apps/plugin-opener";
  import Modal from "./Modal.svelte";
  import Icon from "./Icon.svelte";
  import { app } from "../stores/app.svelte";
  import { notesSince, type ChangelogEntry } from "../changelog";
  import { i18n, t } from "../i18n/index.svelte";

  const RELEASES = "https://github.com/0giel/Aerion/releases";

  let open = $state(false);
  let entries = $state.raw<ChangelogEntry[]>([]);
  let checked = false;

  $effect(() => {
    const s = app.settings;
    const version = app.info?.version;
    if (checked || !app.ready || !s || !version) return;
    checked = true;
    if (s.lastSeenVersion === version) return;
    // A fresh install has nothing to compare with: remember the version silently.
    entries = s.onboardingDone ? notesSince(s.lastSeenVersion, version) : [];
    if (entries.length) open = true;
    else void app.saveSettings({ lastSeenVersion: version });
  });

  function close() {
    open = false;
    if (app.info) void app.saveSettings({ lastSeenVersion: app.info.version });
  }
</script>

<Modal bind:open title={t("whatsNew.title", { version: app.info?.version ?? "" })} onclose={close}>
  <div class="col gap-3">
    {#each entries as e (e.version)}
      <section class="col gap-2">
        {#if entries.length > 1}<h3 class="small muted">
            {t("whatsNew.version", { version: e.version })}
          </h3>{/if}
        <ul>
          {#each e.notes[i18n.locale] ?? e.notes.en as line, i (i)}
            <li><Icon name="sparkles" size={14} /><span>{line}</span></li>
          {/each}
        </ul>
      </section>
    {/each}
  </div>
  {#snippet footer()}
    <button class="btn ghost" onclick={() => openUrl(RELEASES)}
      ><Icon name="external" size={15} />{t("whatsNew.all")}</button
    >
    <span class="grow"></span>
    <button class="btn primary" onclick={close}>{t("whatsNew.ok")}</button>
  {/snippet}
</Modal>

<style>
  ul {
    display: flex;
    flex-direction: column;
    gap: var(--sp-2);
    margin: 0;
    padding: 0;
    list-style: none;
  }
  li {
    display: flex;
    gap: var(--sp-2);
    align-items: flex-start;
    line-height: 1.45;
  }
  li :global(.icon) {
    flex: none;
    margin-top: 0.2rem;
    color: var(--c-accent);
  }
</style>
