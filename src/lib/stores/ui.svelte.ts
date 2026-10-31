// Cross-page UI state: the "new instance" dialog and drag & drop routing.

class Ui {
  newInstanceOpen = $state(false);
  paletteOpen = $state(false);
  /** A page can claim dropped files (e.g. an instance's content tab). */
  dropHandler: ((paths: string[]) => boolean) | null = null;
}

export const ui = new Ui();
