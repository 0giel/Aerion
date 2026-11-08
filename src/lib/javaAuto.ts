// The automatic Java settings in words: what the launcher would pick and why.
import type { AutoPlan } from "./bindings";
import { t } from "./i18n/index.svelte";

export function autoSummary(plan: AutoPlan, withMods: boolean): string {
  return t(withMods ? "javaAuto.summaryMods" : "javaAuto.summary", {
    mb: plan.maxMb,
    preset: t(`instance.settings.presets.${plan.preset}`),
    cores: plan.cores,
    ram: Math.round(plan.totalMb / 1024),
    mods: plan.mods,
  });
}
