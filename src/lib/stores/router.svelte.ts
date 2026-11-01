export type Page =
  | "home"
  | "instances"
  | "instance"
  | "mods"
  | "performance"
  | "dashboard"
  | "accounts"
  | "skins"
  | "stats"
  | "console"
  | "settings"
  | "themeEditor"
  | "onboarding";

interface Route {
  page: Page;
  params: Record<string, string>;
}

class Router {
  page = $state<Page>("home");
  params = $state.raw<Record<string, string>>({});
  private stack: Route[] = [];

  go(page: Page, params: Record<string, string> = {}) {
    if (page === this.page && JSON.stringify(params) === JSON.stringify(this.params)) return;
    this.stack.push({ page: this.page, params: this.params });
    if (this.stack.length > 30) this.stack.shift();
    this.page = page;
    this.params = params;
  }

  back() {
    const prev = this.stack.pop();
    if (prev) {
      this.page = prev.page;
      this.params = prev.params;
    } else {
      this.page = "home";
      this.params = {};
    }
  }

  get canGoBack() {
    return this.stack.length > 0;
  }
}

export const router = new Router();
