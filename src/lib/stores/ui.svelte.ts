// Cross-page UI state: the "new instance" dialog and drag & drop routing.

class Ui {
  newInstanceOpen = $state(false);
  paletteOpen = $state(false);
  /** The "update all mods" window. */
  updateAllOpen = $state(false);
  /** Error code of a launch that failed for an account reason; shows the help dialog. */
  accountHelp = $state<string | null>(null);
  /** Open "customize" dialog: the sidebar or the home screen. */
  customize = $state<"nav" | "home" | null>(null);
  /** A page can claim dropped files (e.g. an instance's content tab). */
  dropHandler: ((paths: string[]) => boolean) | null = null;
}

export const ui = new Ui();
