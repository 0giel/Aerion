// Promise-based confirm/prompt dialogs rendered by <Dialogs />.

export interface DialogRequest {
  kind: "confirm" | "prompt";
  title: string;
  text?: string;
  confirmLabel?: string;
  danger?: boolean;
  value?: string;
  placeholder?: string;
  resolve: (v: string | boolean | null) => void;
}

class Dialogs {
  current = $state.raw<DialogRequest | null>(null);

  confirm(opts: { title: string; text?: string; confirmLabel?: string; danger?: boolean }): Promise<boolean> {
    return new Promise((resolve) => {
      this.current = { kind: "confirm", ...opts, resolve: (v) => resolve(v === true) };
    });
  }

  prompt(opts: {
    title: string;
    text?: string;
    value?: string;
    placeholder?: string;
    confirmLabel?: string;
  }): Promise<string | null> {
    return new Promise((resolve) => {
      this.current = { kind: "prompt", ...opts, resolve: (v) => resolve(typeof v === "string" ? v : null) };
    });
  }

  close(v: string | boolean | null) {
    const c = this.current;
    this.current = null;
    c?.resolve(v);
  }
}

export const dialogs = new Dialogs();
