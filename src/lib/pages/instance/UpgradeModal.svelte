<script lang="ts">
  // Move an instance to a newer Minecraft version: pick the target, review what happens to its
  // mods, then upgrade in place or as a copy. Worlds are backed up first either way.
  import Modal from "../../components/Modal.svelte";
  import Icon from "../../components/Icon.svelte";
  import { toasts } from "../../stores/toasts.svelte";
  import { commands, errorMessage, loaderLabel, type GameVersions, type Instance } from "../../api";
  import type { UpgradeCheck_Serialize as UpgradeCheck } from "../../bindings";
  import { t } from "../../i18n/index.svelte";

  let {
    open = $bindable(false),
    instance,
    versions,
  }: { open?: boolean; instance: Instance; versions: GameVersions | null } = $props();

  let target = $state("");
  let check = $state.raw<UpgradeCheck | null>(null);
  let loading = $state(false);
  let failed = $state<string | null>(null);
  let disableUnavailable = $state(true);
  let mode = $state<"copy" | "inPlace">("inPlace");
  let busy = $state(false);

  // Releases newer than the instance's version (the manifest lists newest first).
  const newer = $derived.by(() => {
    const list = versions?.versions ?? [];
    const cur = list.findIndex((v) => v.id === instance.gameVersion);
    return list.filter((v, i) => v.kind === "release" && (cur < 0 || i < cur));
  });

  $effect(() => {
    if (open && !target) target = versions?.latestRelease ?? "";
  });

  $effect(() => {
    const tgt = target;
    if (!open || !tgt) return;
    check = null;
    failed = null;
    loading = true;
    commands
      .instanceUpgradePlan(instance.id, tgt)
      .then((c) => {
        if (target !== tgt) return;
        check = c;
        // Mods that would stop working: safer to keep the old instance.
        mode = c.plan.unavailable.length ? "copy" : "inPlace";
      })
      .catch((e) => (failed = errorMessage(e)))
      .finally(() => (loading = false));
  });

  async function upgrade() {
    if (!check) return;
    busy = true;
    try {
      await commands.upgradeInstance(
        instance.id,
        target,
        check.plan.updates,
        disableUnavailable ? check.plan.unavailable : [],
        mode === "copy" ? `${instance.name} ${target}` : null,
      );
      open = false;
      toasts.info(t("instance.upgrade.started"));
    } catch (e) {
      toasts.error(errorMessage(e));
    } finally {
      busy = false;
    }
  }
</script>

<Modal bind:open title={t("instance.upgrade.title")} width="34rem">
  <div class="col gap-3">
    <div class="field">
      <label for="up-target">{t("instance.upgrade.target", { current: instance.gameVersion })}</label>
      <select id="up-target" class="select" bind:value={target}>
        {#each newer as v (v.id)}<option value={v.id}>{v.id}</option>{/each}
        {#if !newer.length}<option value="">{t("instance.upgrade.upToDate")}</option>{/if}
      </select>
    </div>

    {#if loading}
      <div class="row small muted"><span class="spinner"></span>{t("instance.upgrade.checking")}</div>
    {:else if failed}
      <div class="alert danger"><Icon name="alert" size={16} /><span class="small">{failed}</span></div>
    {:else if check}
      {@const p = check.plan}
      {#if !check.loaderAvailable}
        <div class="alert warning">
          <Icon name="alert" size={16} /><span class="small"
            >{t("instance.upgrade.noLoader", { loader: loaderLabel(instance.loader), version: target })}</span
          >
        </div>
      {/if}
      <ul class="plan small">
        <li><Icon name="globe" size={14} />{t("instance.upgrade.worldsBackedUp")}</li>
        {#if p.updates.length}<li>
            <Icon name="refresh" size={14} />{t("instance.upgrade.updates", { n: p.updates.length })}
          </li>{/if}
        {#if p.compatible.length}<li>
            <Icon name="check" size={14} />{t("instance.upgrade.compatible", { n: p.compatible.length })}
          </li>{/if}
        {#if p.unknown.length}<li class="muted">
            <Icon name="info" size={14} />{t("instance.upgrade.unknown", { names: p.unknown.join(", ") })}
          </li>{/if}
      </ul>
      {#if p.unavailable.length}
        <div class="alert warning col gap-2">
          <span class="small"
            ><strong>{t("instance.upgrade.unavailable", { n: p.unavailable.length, version: target })}</strong
            >
            {p.unavailable.map((u) => u.title).join(", ")}</span
          >
          <label class="row small check"
            ><input type="checkbox" bind:checked={disableUnavailable} />{t(
              "instance.upgrade.disableUnavailable",
            )}</label
          >
        </div>
      {/if}
      <div class="segmented" role="group" aria-label={t("instance.upgrade.mode")}>
        <button type="button" aria-pressed={mode === "copy"} onclick={() => (mode = "copy")}
          >{t("instance.upgrade.asCopy")}</button
        >
        <button type="button" aria-pressed={mode === "inPlace"} onclick={() => (mode = "inPlace")}
          >{t("instance.upgrade.inPlace")}</button
        >
      </div>
      <span class="hint"
        >{mode === "copy"
          ? t("instance.upgrade.asCopyHint", { name: `${instance.name} ${target}` })
          : t("instance.upgrade.inPlaceHint")}</span
      >
    {/if}
  </div>
  {#snippet footer()}
    <button class="btn ghost" onclick={() => (open = false)}>{t("common.cancel")}</button>
    <button
      class="btn primary"
      disabled={busy || !check || !check.loaderAvailable || !target}
      onclick={upgrade}
      ><Icon name="upload" size={15} />{t("instance.upgrade.go", { version: target })}</button
    >
  {/snippet}
</Modal>

<style>
  .plan {
    list-style: none;
    margin: 0;
    padding: 0;
    display: grid;
    gap: 0.35rem;
  }
  .plan li {
    display: flex;
    gap: 0.45rem;
    align-items: flex-start;
  }
  .check {
    gap: 0.4rem;
    cursor: pointer;
  }
</style>
