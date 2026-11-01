// Release notes shown once after an update ("What's new"). Newest first; short, player-facing
// sentences. Versions without an entry show nothing.
import type { Locale } from "./i18n/index.svelte";

export interface ChangelogEntry {
  version: string;
  notes: Record<Locale, string[]>;
}

export const CHANGELOG: ChangelogEntry[] = [
  {
    version: "0.3.0",
    notes: {
      pl: [
        "Mod Aeriona z HUD-em i dodatkami w stylu klientów PvP: FPS, CPS, współrzędne, kierunek, ping, zegar, pamięć, zbroja, efekty mikstur, klawisze i minimapa.",
        "Każdy moduł HUD-a ustawisz po swojemu: pozycja, rozmiar, kolory, tło, ramka, zaokrąglenie, własny format tekstu. Zmiany z launchera działają w uruchomionej grze w ciągu sekundy, a w grze prawy Shift albo przycisk Aerion w menu otwiera układanie HUD-a: moduły przeciągasz z przyciąganiem do linii, rozmiar zmieniasz kwadracikami na rogach, a przycisk Mody otwiera menu z opcjami, wyglądem i klawiszem każdego modułu.",
        "Nowe funkcje gry: zoom (domyślnie C), a do włączenia osobno freelook, pełna jasność, stały sprint i stałe skradanie. Niektóre serwery zabraniają takich dodatków — sprawdź regulamin, zanim ich użyjesz.",
        "Hitboxy z własnymi kolorami, także innym kolorem, gdy cel jest w zasięgu ciosu; edytor dźwięków z głośnością każdego dźwięku osobno; nakładki: niższy ogień, bez widoku z dyni, winiety i innych efektów.",
        "Przyciski i suwaki w grze są teraz ostre w każdej skali, z gładkimi rogami i wyraźnym tekstem.",
        "Mod działa teraz na Fabric 26.3 i 1.21.11, bez Fabric API. Instancje z modem mają oznaczenie „Fabric + Aerion”.",
        "Eksperymentalnie: ekran główny i własne strony złożone z widgetów, które przeciągasz i skalujesz (Ustawienia → Wygląd → Widgety i własne strony).",
      ],
      en: [
        "The Aerion mod gains a HUD and PvP-client-style extras: FPS, CPS, coordinates, direction, ping, clock, memory, armor, potion effects, keystrokes and a minimap.",
        "Every HUD module is yours to set up: position, size, colors, background, border, corners and custom text formats. Changes from the launcher reach a running game within a second, and in game Right Shift or the Aerion button in the menu opens the HUD layout: drag modules with snapping guides, resize them by the squares on their corners, and use the Mods button for each module's options, look and on/off key.",
        "New game features: zoom (C by default), plus freelook, fullbright, toggle sprint and toggle sneak to switch on separately. Some servers forbid such extras — check their rules before using them.",
        "Hitboxes in your own colors, with a different color when a target is in hit range; a sound editor with a volume for every sound; overlays: lower fire, no pumpkin view, vignette or other effects.",
        "Buttons and sliders in the game are now sharp at every scale, with smooth corners and clear text.",
        "The mod now runs on Fabric 26.3 and 1.21.11, without Fabric API. Instances with the mod are labelled “Fabric + Aerion”.",
        "Experimental: a home screen and your own pages made of widgets you drag and resize (Settings → Appearance → Widgets and your own pages).",
      ],
    },
  },
  {
    version: "0.2.0",
    notes: {
      pl: [
        "Nowa strona Wydajność: procesor, pamięć i karta graficzna na żywo, ile zużywa sam Aerion i gra, FPS z modem Aeriona oraz lista programów, które najbardziej obciążają komputer.",
        "Kontrola modów przed uruchomieniem: Aerion wykrywa duplikaty, brakujące lub za stare zależności, konflikty, mody do innego loadera i innej wersji gry — i podpowiada, jak to naprawić.",
        "Aktualizacje modów sprawdzane w tle; liczba aktualizacji widoczna na kafelkach instancji, a pojedynczy mod zaktualizujesz jednym kliknięciem.",
        "Dowolna wersja moda (także starsza) z listy „Wersje…”; wybrana wersja zostaje przypięta i nie jest aktualizowana.",
        "Zamienniki dla modów bez wersji dla Twojej gry, np. OptiFine → Sodium i Iris.",
      ],
      en: [
        "New Performance page: live processor, memory and graphics card use, what Aerion and the game use, FPS with the Aerion mod, and the programs using the computer the most.",
        "Mod check before launch: Aerion finds duplicates, missing or outdated dependencies, conflicts, mods for another loader or game version — and suggests how to fix them.",
        "Mod updates are checked in the background; the count shows on instance tiles, and a single mod updates in one click.",
        "Any version of a mod (older ones too) from the “Versions…” list; the chosen version is pinned and not updated.",
        "Replacements for mods without a version for your game, e.g. OptiFine → Sodium and Iris.",
      ],
    },
  },
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
