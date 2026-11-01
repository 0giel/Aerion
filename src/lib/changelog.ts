// Release notes shown once after an update ("What's new"). Newest first; short, player-facing
// sentences. Versions without an entry show nothing.
import type { Locale } from "./i18n/index.svelte";

export interface ChangelogEntry {
  version: string;
  notes: Record<Locale, string[]>;
}

export const CHANGELOG: ChangelogEntry[] = [
  {
    version: "0.1.9",
    notes: {
      pl: [
        "Okno „Co nowego” — takie jak to — pokazuje zmiany po każdej aktualizacji.",
        "Pomoc przy awariach: Aerion wskazuje mod, który najpewniej wywołał crash, i wyłącza go jednym kliknięciem przed ponownym uruchomieniem.",
        "„Udostępnij log” wysyła raport awarii na mclo.gs i kopiuje link do wklejenia np. na Discordzie (tokeny i nazwa użytkownika komputera są usuwane).",
        "Wersja próbna moda Aeriona dla Fabric 26.3: menu gry w kolorach motywu launchera, z cząsteczkami i logo Aeriona, zmieniane na żywo. Wybierz „Fabric + Aerion” przy tworzeniu instancji albo włącz go w jej ustawieniach; każdy element wyłączysz w Ustawienia → Wygląd → W grze.",
      ],
      en: [
        'A "What\'s new" window — like this one — shows the changes after every update.',
        "Crash help: Aerion points at the mod that most likely caused a crash and can disable it before relaunching, in one click.",
        '"Share log" uploads the crash report to mclo.gs and copies a link to paste e.g. on Discord (tokens and your computer\'s user name are removed).',
        'Preview of the Aerion mod for Fabric 26.3: the game\'s menus in your launcher theme, with its particles and the Aerion logo, updated live. Pick "Fabric + Aerion" when creating an instance or turn it on in its settings; switch each part off in Settings → Appearance → In game.',
      ],
    },
  },
  {
    version: "0.1.8",
    notes: {
      pl: [
        "Postać na ekranie głównym stoi prosto z każdej strony, peleryna wyświetla się poprawnie.",
        "Rzadko zmieniane opcje są w zwijanych sekcjach „Zaawansowane”.",
        "Czytelniejsze przeglądanie modów, karty instancji i statystyki.",
      ],
      en: [
        "The player on the home screen stands upright from every side, capes display correctly.",
        'Rarely changed options live in collapsible "Advanced" sections.',
        "Clearer mod browsing, instance cards and statistics.",
      ],
    },
  },
];

function parts(v: string): number[] {
  return v.split(/[.+-]/).map((x) => Number.parseInt(x, 10) || 0);
}

/** Negative when `a` is older than `b`. */
export function compareVersions(a: string, b: string): number {
  const [x, y] = [parts(a), parts(b)];
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    const d = (x[i] ?? 0) - (y[i] ?? 0);
    if (d) return d;
  }
  return 0;
}

/**
 * Notes to show when `current` starts after `lastSeen`: every version in between, newest first.
 * Without a record (updated from a version before 0.1.9) only the current version is shown.
 */
export function notesSince(lastSeen: string | null, current: string): ChangelogEntry[] {
  return CHANGELOG.filter((e) =>
    lastSeen === null
      ? e.version === current
      : compareVersions(e.version, lastSeen) > 0 && compareVersions(e.version, current) <= 0,
  );
}
