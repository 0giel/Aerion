// Launcher update state shared by the banner and Settings.
import { commands, errorMessage } from "../api";
import type { AppUpdate } from "../bindings";
import { t } from "../i18n/index.svelte";
import { toasts } from "./toasts.svelte";

class Updates {
  available = $state.raw<AppUpdate | null>(null);
  dismissed = $state(false);
  checking = $state(false);

  /** `manual`: report "up to date" and errors (the automatic check stays silent). */
  async check(manual: boolean) {
    this.checking = true;
    try {
      this.available = await commands.checkAppUpdate();
      this.dismissed = false;
      if (manual && !this.available) toasts.success(t("appUpdate.upToDate"));
    } catch (e) {
      if (manual) toasts.error(errorMessage(e));
    } finally {
      this.checking = false;
    }
  }
}

export const updates = new Updates();
