// Launcher update state shared by the banner and Settings.
import { commands, errorMessage, events } from "../api";
import type { AppUpdate } from "../bindings";
import { t } from "../i18n/index.svelte";
import { toasts } from "./toasts.svelte";

class Updates {
  available = $state.raw<AppUpdate | null>(null);
  dismissed = $state(false);
  checking = $state(false);
  /** Background download: 0–1 while it runs (null before), and whether it finished. */
  progress = $state<number | null>(null);
  ready = $state(false);
  private listening = false;

  /** Fetch the found update in the background, so installing is a restart away. */
  async download() {
    if (!this.available?.canInstall || this.ready || this.progress !== null) return;
    if (!this.listening) {
      this.listening = true;
      void events.appUpdateProgressEvent.listen(({ payload: p }) => {
        this.progress = p.total ? Math.min(1, (p.downloaded ?? 0) / p.total) : 0;
        if (p.done) this.ready = true;
      });
    }
    this.progress = 0;
    try {
      await commands.downloadAppUpdate();
      this.ready = true;
    } catch {
      // Installing still downloads it then; the next start tries again in the background.
      this.progress = null;
    }
  }

  /** `manual`: report "up to date" and errors (the automatic check stays silent). */
  async check(manual: boolean, background = false) {
    this.checking = true;
    try {
      this.available = await commands.checkAppUpdate();
      this.dismissed = false;
      if (background && this.available?.canInstall) void this.download();
      if (manual && !this.available) toasts.success(t("appUpdate.upToDate"));
    } catch (e) {
      if (manual) toasts.error(errorMessage(e));
    } finally {
      this.checking = false;
    }
  }
}

export const updates = new Updates();
