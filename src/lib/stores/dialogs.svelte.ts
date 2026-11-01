// Promise-based confirm/prompt dialogs rendered by <Dialogs />.

export interface DialogRequest {
  kind: "confirm" | "prompt";
  title: string;
  text?: string;
  confirmLabel?: string;
  /** Replaces "Cancel" with a second choice; closing the dialog stays a third outcome. */
  cancelLabel?: string;
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

  /** Two choices plus dismissal: `"confirm"`, `"cancel"` (the second button) or `null`. */
  choose(opts: {
    title: string;
    text?: string;
    confirmLabel: string;
    cancelLabel: string;
  }): Promise<"confirm" | "cancel" | null> {
    return new Promise((resolve) => {
      this.current = {
        kind: "confirm",
        ...opts,
        resolve: (v) => resolve(v === true ? "confirm" : v === false ? "cancel" : null),
      };
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
