export type ToastKind = "success" | "error" | "info";
export interface Toast {
  id: number;
  kind: ToastKind;
  text: string;
  action?: { label: string; run: () => void };
}

class Toasts {
  list = $state<Toast[]>([]);
  private next = 1;

  push(kind: ToastKind, text: string, action?: Toast["action"], ms?: number) {
    const id = this.next++;
    this.list = [...this.list.slice(-4), { id, kind, text, action }];
    setTimeout(() => this.dismiss(id), ms ?? (kind === "error" ? 9000 : 4500));
  }

  success(text: string, action?: Toast["action"]) {
    this.push("success", text, action);
  }
  error(text: string, action?: Toast["action"]) {
    this.push("error", text, action);
  }
  info(text: string, action?: Toast["action"], ms?: number) {
    this.push("info", text, action, ms);
  }

  dismiss(id: number) {
    this.list = this.list.filter((t) => t.id !== id);
  }
}

export const toasts = new Toasts();
