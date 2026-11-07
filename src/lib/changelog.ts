// Release notes shown once after an update ("What's new"). Newest first; short, player-facing
// sentences. Versions without an entry show nothing.
import type { Locale } from "./i18n/index.svelte";

export interface ChangelogEntry {
  version: string;
  notes: Record<Locale, string[]>;
}

export const CHANGELOG: ChangelogEntry[] = [
  {
    version: "1.0.1",
    notes: {
      pl: [
        "Naprawione logowanie przez Microsoft w wersji 1.0.0: instalator nie miał w sobie identyfikatora aplikacji.",
      ],
      en: ["Fixed Microsoft sign-in in 1.0.0: the installer was built without the application ID."],
    },
  },
  {
    version: "1.0.0",
    notes: {
      pl: [
        "Aerion 1.0.0: logowanie kontem Microsoft działa — Mojang zatwierdził aplikację.",
        "Nowe menu moda: sekcje w pasku bocznym, kafelki modułów z dużymi ikonami, filtry Włączone/Wyłączone, każde ustawienie we własnej karcie i krótki opis każdego modułu i funkcji.",
        "W układzie HUD kliknięcie modułu otwiera z boku jego ustawienia — każdą zmianę widać od razu na ekranie.",
        "Nowy ekran główny gry: falująca zorza w kolorach motywu, napis pod logo, Twoja postać po prawej i przycisk „Hostuj świat”, który otwiera świat dla znajomych (przez internet dzięki e4mc, dodawanemu do nowych instancji Fabric + Aerion).",
        "Tło-obraz motywu, także animowany GIF, jest teraz również w menu gry.",
        "Nowe instancje Fabric + Aerion mogą od razu dostać mody optymalizacyjne — Sodium, Lithium, FerriteCore i ImmediatelyFast: więcej FPS i mniej pamięci bez zmiany rozgrywki.",
        "Wspólne ustawienia gry: sterowanie, grafika i dźwięk przechodzą między instancjami, które je włączą — zmiana w jednej grze pojawia się w pozostałych.",
        "Nowy edytor klawiszy z klawiaturą i myszą: widać, co jest pod którym klawiszem, konflikty świecą na czerwono, a przypisanie zmienia się naciśnięciem klawisza.",
        "Znajomi: lista nicków z twarzami ze skórek, kropka pokazuje, kto właśnie hostuje, a „Dołącz” wchodzi na jego świat. Wystarczy wkleić wiadomość z adresem e4mc — Aerion sam go znajdzie.",
        "Galeria zrzutów ekranu ze wszystkich instancji naraz: filtr instancji, kopiowanie obrazu do schowka, „Zapisz jako…”, pokaż w folderze i usuwanie.",
        "Strona Wydajność pokazuje podsumowanie po ostatniej grze: średnie i najniższe FPS, szczyt pamięci i podpowiedzi typu „ta instancja ma za mało RAM” z przyciskiem, który od razu to poprawia.",
        "Profile HUD: osobne układy modułów (np. PvP, budowanie, survival), przełączane w menu, klawiszem albo same po wejściu na wybrany serwer.",
        "Mapa w grze (klawisz M): kliknij miejsce, by dodać punkt trasy, kliknij punkt, by zmienić nazwę, kolor albo go usunąć.",
        "Edytor klawiszy w grze z klawiaturą jak w launcherze: konflikty świecą na czerwono, a „Zapisz pasek” nie zajmuje już klawisza C razem z zoomem.",
        "Wyłączone funkcje moda w ogóle nie zmieniają kodu gry, więc rzadziej gryzą się z innymi modami. Włączona później funkcja zadziała po ponownym uruchomieniu gry.",
        "Odznaki: gracze z odznaką Aeriona (np. Łowca błędów za zgłaszanie błędów) mają ikonkę przy nicku nad głową i na liście Tab. Można je wyłączyć w funkcjach HUD.",
        "Przenoszenie na inny komputer: eksport i import ustawień, motywów, skórek, HUD-u i wspólnych ustawień gry jednym plikiem (Ustawienia → Dane). Konta i tokeny nigdy nie trafiają do pliku.",
        "Nowe funkcje: blokada FOV, regulowana jasność pełnej jasności z klawiszami Jaśniej i Ciemniej oraz przybliżanie zooma kółkiem myszy.",
        "Nowe moduły HUD: info o bloku pod celownikiem, wykres FPS i lista graczy. Czytelniejsze nazwy dźwięków w edytorze dźwięków.",
        "Eksperymentalnie: instancje Horizon (Rust) — Minecraft 26.3 przepisany w Rust przez otwarty projekt Horizon. Aerion pobiera podpisany build i przygotowuje go z Twoich plików gry.",
        "Peleryny wczytują się same i poprawnie pokazują się wszystkie, także nowsze; link do GitHuba w ustawieniach i w pasku bocznym; podgląd w edytorze motywu pokazuje motyw w działaniu.",
      ],
      en: [
        "Aerion 1.0.0: signing in with a Microsoft account works — Mojang approved the app.",
        "A new mod menu: sections in the sidebar, module tiles with big icons, Enabled/Disabled filters, every setting on its own card and a short description of each module and feature.",
        "In the HUD layout, clicking a module opens its settings beside it — every change shows on screen at once.",
        "A new title screen in the game: a moving aurora in the theme's colours, the splash under the logo, your character on the right and a “Host a world” button that opens a world to friends (over the internet with e4mc, added to new Fabric + Aerion instances).",
        "A picture background in your theme, animated GIFs included, now shows in the game's menus too.",
        "New Fabric + Aerion instances can come with optimization mods — Sodium, Lithium, FerriteCore and ImmediatelyFast: more FPS and less memory without changing how the game plays.",
        "Shared game settings: controls, video and sound follow you between the instances that switch them on — a change in one game shows in the others.",
        "A new key binding editor with a keyboard and mouse: see what each key does, clashes light up red, and a binding changes by pressing the key.",
        "Friends: a list of names with faces from their skins, a dot showing who is hosting right now, and “Join” to enter their world. Paste the message with the e4mc address — Aerion finds the address itself.",
        "A screenshot gallery of every instance at once: filter by instance, copy the image to the clipboard, “Save as…”, show in folder and delete.",
        "The Performance page shows a summary after your last game: average and lowest FPS, the memory peak and hints like “this instance has too little RAM”, with a button that fixes it.",
        "HUD profiles: separate module layouts (e.g. PvP, building, survival), switched in the menu, with a key, or on joining a chosen server.",
        "In-game map (M key): click a place to add a waypoint, click a waypoint to rename, recolor or delete it.",
        'In-game key editor with the launcher\'s keyboard: conflicts show in red, and "Save hotbar" no longer shares C with zoom.',
        "Features that are off no longer touch the game's code at all, so they clash less with other mods. A feature switched on later works after a game restart.",
        "Badges: players with an Aerion badge (e.g. Bug hunter for reporting bugs) get an icon next to their name above their head and in the Tab list. They can be turned off in HUD features.",
        "Moving to another computer: export and import settings, themes, skins, the HUD and shared game settings in one file (Settings → Data). Accounts and tokens never go in the file.",
        "New features: FOV lock, adjustable fullbright brightness with Brighter and Darker keys, and zooming in and out with the mouse wheel.",
        "New HUD modules: block info under the crosshair, an FPS graph and a player list. Clearer sound names in the sound editor.",
        "Experimental: Horizon (Rust) instances — Minecraft 26.3 rewritten in Rust by the open-source Horizon project. Aerion downloads the signed build and prepares it from your own game files.",
        "Capes load by themselves and all of them show, newer ones too; a GitHub link in settings and the sidebar; the theme editor's preview shows the theme in action.",
      ],
    },
  },
  {
    version: "0.3.0",
    notes: {
      pl: [
        "Mod Aeriona z HUD-em i dodatkami w stylu klientów PvP: FPS, CPS, współrzędne, kierunek, ping, zegar, pamięć, zbroja, efekty mikstur, klawisze i minimapa.",
        "Każdy moduł HUD-a ustawisz po swojemu: pozycja, rozmiar, kolory, tło, ramka, zaokrąglenie, własny format tekstu. Zmiany z launchera działają w uruchomionej grze w ciągu sekundy, a w grze prawy Shift albo przycisk Aerion w menu otwiera układanie HUD-a: moduły przeciągasz z przyciąganiem do linii, rozmiar zmieniasz kwadracikami na rogach, a przycisk Mody otwiera menu z opcjami, wyglądem i klawiszem każdego modułu.",
        "Nowe funkcje gry: zoom (domyślnie C), a do włączenia osobno freelook, pełna jasność, stały sprint i stałe skradanie. Niektóre serwery zabraniają takich dodatków — sprawdź regulamin, zanim ich użyjesz.",
        "Hitboxy z własnymi kolorami, także innym kolorem, gdy cel jest w zasięgu ciosu; edytor dźwięków z głośnością każdego dźwięku osobno; overlaye: niższy i bardziej przezroczysty ogień, bez widoku z dyni, animacji totemu i innych efektów, kolor ekranu i czerwone krawędzie przy małym zdrowiu.",
        "Własny celownik, regulowany hurt cam, chroma i nowy wybór koloru oraz nowe moduły: licznik totemów, licznik przedmiotów, prędkość, pozycja śmierci, czas sesji, dzień świata i poziom światła.",
        "Liczby światła na podłodze wokół Ciebie, ikony przy efektach mikstur i wyszukiwarka w menu modów.",
        "Obrys bloku, punkty trasy (także na minimapie), opcje czatu, przełączanie perspektywy, klawisz FOV, pora i pogoda tylko u Ciebie, kolor trafienia, ostrzeżenie o zbroi i wskaźnik paczki zasobów.",
        "Pasek bossa, tablicę wyników i czat przesuniesz i przeskalujesz w układzie HUD, a kolor motywu za ekwipunkiem i menu możesz wyłączyć.",
        "W ustawieniach instancji możesz wybrać świat, do którego gra ma wejść od razu po starcie.",
        "Przyciski i suwaki w grze są teraz ostre w każdej skali, z gładkimi rogami i wyraźnym tekstem.",
        "Mod działa teraz na Fabric 26.3 i 1.21.11, bez Fabric API. Instancje z modem mają oznaczenie „Fabric + Aerion”.",
        "Eksperymentalnie: ekran główny i własne strony złożone z widgetów, które przeciągasz i skalujesz (Ustawienia → Wygląd → Widgety i własne strony).",
      ],
      en: [
        "The Aerion mod gains a HUD and PvP-client-style extras: FPS, CPS, coordinates, direction, ping, clock, memory, armor, potion effects, keystrokes and a minimap.",
        "Every HUD module is yours to set up: position, size, colors, background, border, corners and custom text formats. Changes from the launcher reach a running game within a second, and in game Right Shift or the Aerion button in the menu opens the HUD layout: drag modules with snapping guides, resize them by the squares on their corners, and use the Mods button for each module's options, look and on/off key.",
        "New game features: zoom (C by default), plus freelook, fullbright, toggle sprint and toggle sneak to switch on separately. Some servers forbid such extras — check their rules before using them.",
        "Hitboxes in your own colors, with a different color when a target is in hit range; a sound editor with a volume for every sound; overlays: lower and fainter fire, no pumpkin view, totem animation or other effects, a screen tint and red edges at low health.",
        "Your own crosshair, adjustable hurt cam, chroma and a new color picker, plus new modules: totem counter, item counter, speed, death position, session time, world day and light level.",
        "Light numbers on the floor around you, icons next to potion effects and a search box in the mods menu.",
        "Block outline, waypoints (also on the minimap), chat options, perspective switching, an FOV key, time and weather only you see, hit color, an armor warning and a resource pack module.",
        "Move and resize the boss bar, scoreboard and chat in the HUD layout, and turn off the theme color behind the inventory and menus.",
        "Instance settings let you choose a world the game enters right after start.",
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
