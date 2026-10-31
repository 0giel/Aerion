// Global application state, kept in sync with the backend through events.
import {
  commands,
  errorMessage,
  events,
  type AccountsView,
  type AppInfo,
  type CrashInfo,
  type ImportResult,
  type Instance,
  type LogLine,
  type Settings,
  type TaskInfo,
} from "../api";
import { i18n, t } from "../i18n/index.svelte";
import { themes } from "../theme/engine.svelte";
import { router } from "./router.svelte";
import { ui } from "./ui.svelte";
import { toasts } from "./toasts.svelte";

const MAX_LOG_LINES = 20_000;

/** Launch failures explained by the account help dialog instead of a toast. */
// eslint-disable-next-line svelte/prefer-svelte-reactivity -- constant lookup table, not UI state
export const ACCOUNT_HELP = new Set([
  "auth.noAccount",
  "auth.offlineNotAllowed",
  "auth.sessionExpired",
  "auth.noLicense",
  "auth.noProfile",
  "auth.clientIdMissing",
  "auth.invalidAppRegistration",
]);

class AppStore {
  ready = $state(false);
  fatal = $state<string | null>(null);
  info = $state.raw<AppInfo | null>(null);
  settings = $state.raw<Settings | null>(null);
  accounts = $state.raw<AccountsView | null>(null);
  instances = $state.raw<Instance[]>([]);
  tasks = $state.raw<TaskInfo[]>([]);
  running = $state.raw<Record<string, number>>({});
  crashes = $state.raw<Record<string, CrashInfo | null>>({});
  exitCodes = $state.raw<Record<string, number | null>>({});
  logVersion = $state(0);
  manualDownloads = $state.raw<ImportResult["manualDownloads"]>([]);
  private logs = new Map<string, LogLine[]>();

  get selectedAccount() {
    const a = this.accounts;
    return a?.accounts.find((x) => x.id === a.selected) ?? null;
  }

  get lastInstance(): Instance | null {
    const id = this.settings?.lastInstance;
    return this.instances.find((i) => i.id === id) ?? this.instances[0] ?? null;
  }

  async init() {
    try {
      const [info, settings, accounts, instances, tasks, running] = await Promise.all([
        commands.appInfo(),
        commands.getSettings(),
        commands.getAccounts(),
        commands.listInstances(),
        commands.listTasks(),
        commands.runningGames(),
      ]);
      this.info = info;
      this.settings = settings;
      this.accounts = accounts;
      this.instances = instances;
      this.tasks = tasks;
      this.running = Object.fromEntries(running.map((r) => [r.instanceId, r.startedAt ?? Date.now()]));
      i18n.set(settings.language);
      await themes.init();
      themes.sync(settings);
      this.subscribe();
      if (!settings.onboardingDone) router.go("onboarding");
      this.ready = true;
    } catch (e) {
      this.fatal = errorMessage(e);
    }
  }

  private subscribe() {
    void events.taskEvent.listen((e) => this.onTask(e.payload as unknown as TaskInfo));
    void events.gameStateEvent.listen(({ payload: p }) => {
      if (p.state === "running") {
        this.running = { ...this.running, [p.instanceId]: Date.now() };
        this.crashes = { ...this.crashes, [p.instanceId]: null };
        if (this.settings?.showConsoleOnLaunch) router.go("console", { id: p.instanceId });
      } else {
        const { [p.instanceId]: _, ...rest } = this.running;
        this.running = rest;
        this.crashes = { ...this.crashes, [p.instanceId]: p.crash };
        this.exitCodes = { ...this.exitCodes, [p.instanceId]: p.exitCode };
        void this.refreshInstances();
        if (p.crash) {
          toasts.error(t("crash.title"), {
            label: t("crash.viewLogs"),
            run: () => router.go("console", { id: p.instanceId }),
          });
        }
      }
    });
    void events.gameLogEvent.listen(({ payload: p }) => {
      const list = this.logs.get(p.instanceId) ?? [];
      list.push(...p.lines);
      if (list.length > MAX_LOG_LINES) list.splice(0, list.length - MAX_LOG_LINES);
      this.logs.set(p.instanceId, list);
      this.logVersion++;
    });
  }

  private onTask(task: TaskInfo) {
    const prev = this.tasks.find((x) => x.id === task.id);
    this.tasks = prev ? this.tasks.map((x) => (x.id === task.id ? task : x)) : [...this.tasks, task];
    if (prev?.status === task.status || task.status === "running") return;
    if (task.status === "failed") {
      const code = task.error?.code ?? "";
      if (task.kind === "launch" && ACCOUNT_HELP.has(code)) ui.accountHelp = code;
      else toasts.error(errorMessage(task.error));
    } else if (task.status === "done") {
      this.onTaskDone(task);
    }
    // Finished tasks leave the panel after a moment (failures stay until dismissed).
    if (task.status !== "failed")
      setTimeout(() => this.dismissTask(task.id), task.kind === "launch" ? 1500 : 6000);
  }

  private onTaskDone(task: TaskInfo) {
    switch (task.kind) {
      case "install":
        toasts.success(t("plan.done", { name: task.subject ?? "" }));
        break;
      case "import":
      case "modpack": {
        void this.refreshInstances();
        const r: ImportResult | null = task.result ? JSON.parse(task.result) : null;
        if (r) {
          toasts.success(t("instances.importDone", { name: r.instance.name }), {
            label: t("common.open"),
            run: () => router.go("instance", { id: r.instance.id }),
          });
          if (r.manualDownloads.length) this.manualDownloads = r.manualDownloads;
        }
        break;
      }
      case "export":
        toasts.success(t("instances.exportDone", { path: task.result ? JSON.parse(task.result) : "" }));
        break;
      case "verify":
        toasts.success(t("instances.verifyDone"));
        break;
      case "update":
        void this.refreshInstances();
        break;
      default:
        void this.refreshInstances();
    }
  }

  async dismissTask(id: string) {
    this.tasks = this.tasks.filter((x) => x.id !== id);
    await commands.dismissTask(id).catch(() => {});
  }

  async refreshInstances() {
    this.instances = await commands.listInstances();
  }

  private replaceInstance(inst: Instance) {
    this.instances = this.instances.map((i) => (i.id === inst.id ? inst : i));
  }

  async toggleFavorite(id: string) {
    const inst = this.instances.find((i) => i.id === id);
    if (!inst) return;
    try {
      this.replaceInstance(await commands.setInstanceFavorite(id, !inst.favorite));
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  }

  /** A built-in icon key (or `null`), or an image file to import. */
  async setInstanceIcon(id: string, icon: string | null, file: string | null) {
    try {
      this.replaceInstance(
        file ? await commands.importInstanceIcon(id, file) : await commands.setInstanceIcon(id, icon),
      );
      return true;
    } catch (e) {
      toasts.error(errorMessage(e));
      return false;
    }
  }

  async refreshAccounts() {
    this.accounts = await commands.getAccounts();
  }

  async saveSettings(patch: Partial<Settings>) {
    if (!this.settings) return;
    const next = await commands.saveSettings({ ...this.settings, ...patch });
    this.settings = next;
    i18n.set(next.language);
    themes.sync(next);
    return next;
  }

  logsFor(id: string | null): LogLine[] {
    void this.logVersion;
    return id ? (this.logs.get(id) ?? []) : [];
  }

  async loadLogs(id: string) {
    if (this.logs.has(id)) return;
    const lines = await commands.gameLogs(id).catch(() => []);
    if (!this.logs.has(id)) {
      this.logs.set(id, lines as LogLine[]);
      this.logVersion++;
    }
  }

  async clearLogs(id: string) {
    this.logs.set(id, []);
    this.logVersion++;
    await commands.clearGameLogs(id).catch(() => {});
  }

  isBusy(instanceId: string) {
    return (
      !!this.running[instanceId] ||
      this.tasks.some((x) => x.instanceId === instanceId && x.kind === "launch" && x.status === "running")
    );
  }

  async launch(id: string) {
    // No account at all: explain before starting anything.
    if (!this.selectedAccount) {
      ui.accountHelp = "auth.noAccount";
      return;
    }
    try {
      await commands.launchInstance(id, null);
    } catch (e) {
      const code = (e as { code?: string })?.code ?? "";
      if (ACCOUNT_HELP.has(code)) ui.accountHelp = code;
      else toasts.error(errorMessage(e));
    }
  }

  async stop(id: string) {
    await commands.stopInstance(id).catch((e) => toasts.error(errorMessage(e)));
  }
}

export const app = new AppStore();
