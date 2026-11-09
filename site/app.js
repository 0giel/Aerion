// Aerion's web page: language, product tour, HUD demo, theme preview and downloads.
(function () {
  const EN = {
    "nav.skip": "Skip to content", "nav.hud": "HUD", "nav.themes": "Themes", "nav.mods": "Mods", "nav.features": "Features", "nav.faq": "FAQ", "nav.get": "Download",
    "hero.title": "Your game,<br>arranged your way",
    "hero.lead": "A launcher for Minecraft: Java Edition and a mod in one. A HUD you arrange with the mouse, themes in the game menus too, mods from Modrinth and CurseForge. Open source, no tracking.",
    "hero.download": "Download", "hero.discord": "Discord",
    "hero.need": 'Free · Windows and Linux · <span id="version-line">Version <span id="version"></span></span> · Needs Minecraft: Java Edition',
    "tt.1": "Home", "tt.2": "In-game HUD", "tt.3": "Arranging the HUD", "tt.4": "Mod menu", "tt.5": "Instances",
    "fx.1": "Open source, GPL-3.0", "fx.2": "No analytics or tracking", "fx.3": "Signed updates", "fx.4": "Official Microsoft sign-in",
    "hud.title": "Try the HUD right here",
    "hud.text": "Drag the modules and switch the look. In the game you get 27 modules and elements, each with its own style.",
    "try.default": "Default", "try.minimal": "Minimal", "try.glass": "Glass", "try.neon": "Neon", "try.retro": "Retro", "try.combat": "Simulate a fight",
    "try.note": "The coordinates module, set to “hide in combat”, disappears and health takes its place.", "try.potion": "Speed II",
    "rules.title": "Modules that know when to appear",
    "rules.text": "Every module can have its own rules, so the screen shows only what you need right now.",
    "rules.1": "18 situations: combat, underground, low health, night, the Nether and more", "rules.2": "Show time, delay and smooth fading",
    "rules.3": "Anchors to the screen's corners, so a layout works at any window size", "rules.4": "A separate look for each module, or one ready-made style for all",
    "th.title": "A look that's yours",
    "th.text": "Pick a theme or build your own in an editor with live preview. Its colours reach the game menus too, and the menu's position, style and animations are set in the theme.",
    "m.title": "Mods without the hunt",
    "m.text": "Add mods and packs from Modrinth and CurseForge with one button, together with what they require.",
    "m.1": "Fabric, Quilt, Forge and NeoForge", "m.2": "Java downloaded automatically", "m.3": "A new instance can start with optimization mods:",
    "f.title": "And everything else",
    "f.1t": "HUD profiles", "f.1d": "Separate layouts for PvP, building and survival, switched with a key or by themselves when you join a server.",
    "f.2t": "Map and waypoints", "f.2d": "An in-game map, waypoints added with a click, a death point and sharing coordinates.",
    "f.3t": "Key editor", "f.3d": "A keyboard in the game and the launcher that shows conflicts at once.",
    "f.4t": "World hosting", "f.4d": "Open your world to friends over the internet with one button, no port forwarding.",
    "f.5t": "Friends", "f.5d": "A friends list with skins and a button to join their world.",
    "f.6t": "Crash fixes", "f.6d": "The launcher recognizes why the game crashed and suggests a fix.",
    "f.7t": "Stats and performance", "f.7d": "Play time, plus an FPS and memory summary after every session.",
    "f.8t": "Moving in", "f.8d": "Import instances from other launchers and move your settings to a new computer in one file.",
    "o.title": "You don't have to take our word for it",
    "o.text": "All the code of the launcher and the mod is public under GPL-3.0. Sign-in tokens stay in the system's password store, and every update is signed and checked before it installs.",
    "o.code": "View the code", "o.privacy": "Privacy policy",
    "d.title": "Download and install", "d.get": "Download", "d.copy": "Copy", "d.verify": "SHA-256 checksum",
    "d.unsupported": "Aerion runs on Windows and Linux computers. You can't run it on this device, but you can come back here from a computer.",
    "d.setT": "Aerion Setup", "d.setD": "Recommended. One click: it downloads the newest version, checks the signature and starts the launcher.",
    "d.exeT": "Windows installer", "d.exeD": "Windows 10 or 11, 64-bit. No administrator rights needed.",
    "d.w1": "Run the downloaded file and go through the installer.",
    "d.w2": "If Windows shows a SmartScreen warning, choose <b>More info</b>, then <b>Run anyway</b>. It appears because the installer has no paid publisher certificate.",
    "d.w3": "If the WebView2 component is missing, the installer downloads it by itself.",
    "d.instT": "Install with one command", "d.instD": "Recommended. No administrator password, with an icon in your application menu. 64-bit.",
    "d.instN": "Paste it in a terminal. It downloads the newest version, checks its SHA-256 and adds Aerion to your menu. After that the launcher updates itself. <a href=\"install.sh\">See what the script does</a>.",
    "d.appD": "Most distributions, nothing to install. 64-bit.",
    "d.appN": "After downloading, run this command in the file's folder. If the system reports that FUSE is missing, install the <code>libfuse2</code> package.",
    "d.debD": "Ubuntu, Debian, Mint, Zorin and similar. 64-bit.",
    "d.debN": "After downloading, run this command in the file's folder. Dependencies install by themselves.",
    "s.1t": "Sign in", "s.1d": "With the Microsoft account that owns Minecraft: Java Edition.",
    "s.2t": "Create an instance", "s.2d": "Pick a game version and turn on the Aerion mod. The launcher downloads Java by itself.",
    "s.3t": "Play", "s.3d": "Press Play. Right Shift in the game opens the HUD editor.",
    "d.updates": "You don't have to download updates by hand: the launcher and the mod update themselves.",
    "q.title": "Questions",
    "q.7q": "How much does it cost?", "q.7a": "The launcher and the mod are free. You only need to own Minecraft: Java Edition.",
    "q.1q": "Is it safe?", "q.1a": "You sign in with an official Microsoft account, and tokens stay in the system's password store. The code is open and updates are signed. Aerion is an unofficial product, not affiliated with Mojang or Microsoft.",
    "q.2q": "Does Aerion collect my data?", "q.2a": "No. There is no analytics or tracking. The launcher only connects to what it needs to work: Microsoft sign-in, Mojang's servers, mod and loader sources, and GitHub. The privacy policy has the details.",
    "q.3q": "Which systems does it run on?", "q.3a": "Windows 10 and 11, and Linux, 64-bit in both cases. There is no macOS or phone version.",
    "q.5q": "Do I need to install Java?", "q.5a": "No. The launcher downloads the Java version each game version needs.",
    "q.8q": "Which Minecraft versions does the mod support?", "q.8a": "The Aerion mod runs with Fabric on 26.3 and 1.21.11. The launcher also starts other game versions, just without the mod.",
    "q.6q": "Why does Windows warn about the installer?", "q.6a": "The installer has no paid publisher certificate, so SmartScreen doesn't recognize it. The code is open, and the download section lists the SHA-256 of every file.",
    "q.4q": "Where do I report a bug or an idea?", "q.4a": "On the Discord, in the report and ideas channels, or in Issues on GitHub.",
    "c.title": "Ready to play your way?", "c.get": "Download Aerion",
    "foot.disclaimer": "Unofficial product. Aerion is not approved by or associated with Mojang or Microsoft. “Minecraft” is a trademark of Mojang Synergies AB.",
    "foot.p": "Product", "foot.releases": "Releases", "foot.c": "Community", "foot.bug": "Report a bug", "foot.l": "Information", "foot.license": "GPL-3.0 license", "foot.contact": "Contact",
  };
  const DL = {
    pl: { win: "Pobierz dla Windows", linux: "Pobierz dla Linuksa", any: "Pobierz", version: "Wersja {v}", date: "wydana {d}", notes: "Co nowego", copied: "Skopiowano", failed: "Nie udało się odczytać listy plików. Przyciski prowadzą do strony wydania na GitHubie.", loading: "Sprawdzam najnowszą wersję…" },
    en: { win: "Download for Windows", linux: "Download for Linux", any: "Download", version: "Version {v}", date: "released {d}", notes: "What's new", copied: "Copied", failed: "Couldn't read the list of files. The buttons open the release page on GitHub.", loading: "Checking the newest version…" },
  };

  const $ = (id) => document.getElementById(id);
  const root = document.documentElement;
  const lang = () => (root.lang === "en" ? "en" : "pl");
  const tr = (k, v) => DL[lang()][k].replace(/\{(\w)\}/, () => v ?? "");
  const store = {
    get: (s, k) => { try { return s.getItem(k); } catch { return null; } },
    set: (s, k, v) => { try { s.setItem(k, v); } catch { /* storage unavailable */ } },
  };

  // --- language: Polish is the page's own text -----------------------------------------------
  const nodes = [...document.querySelectorAll("[data-i18n], [data-i18n-html]")];
  const PL = {};
  for (const n of nodes) {
    const key = n.dataset.i18n || n.dataset.i18nHtml;
    PL[key] = n.dataset.i18nHtml ? n.innerHTML : n.textContent;
  }
  const shotUrl = (name) => "assets/shots/" + name + "-" + lang() + ".webp";

  function applyLang(l) {
    root.lang = l;
    const dict = l === "en" ? EN : PL;
    for (const n of nodes) {
      const key = n.dataset.i18n || n.dataset.i18nHtml;
      if (dict[key] === undefined) continue;
      if (n.dataset.i18nHtml) n.innerHTML = dict[key];
      else n.textContent = dict[key];
    }
    document.title = l === "en" ? "Aerion — a Minecraft launcher with its own HUD" : "Aerion — launcher Minecrafta z własnym HUD-em";
    $("lang").textContent = l === "en" ? "PL" : "EN";
    document.querySelectorAll("img[data-localized]").forEach((img) => { img.src = shotUrl(img.dataset.localized); });
    store.set(localStorage, "lang", l);
    renderDownloads();
  }

  function swap(img, name, alt) {
    img.dataset.localized = name;
    img.classList.add("swap");
    const next = shotUrl(name);
    const done = () => { img.src = next; img.classList.remove("swap"); if (alt) img.alt = alt; };
    const pre = new Image();
    pre.onload = done;
    pre.onerror = done;
    pre.src = next;
  }

  // --- downloads ------------------------------------------------------------------------------
  const PAGE = "https://github.com/0giel/Aerion/releases/latest";
  const API = "https://api.github.com/repos/0giel/Aerion/releases/latest";
  const ua = navigator.userAgent;
  const os = /Android|iPhone|iPad|iPod/i.test(ua) ? "mobile" : /Windows/i.test(ua) ? "windows" : /Mac/i.test(ua) ? "mac" : /Linux|X11/i.test(ua) ? "linux" : "other";
  const mainFile = () => (os === "linux" ? "appimage" : os !== "windows" ? null : release && release.files.setup ? "setup" : "exe");
  let release = null;
  let failed = false;

  const mb = (b) => { const n = b / 1048576; return n.toLocaleString(lang(), { maximumFractionDigits: n >= 10 ? 0 : 1 }) + " MB"; };
  const command = (kind, name) => {
    if (kind === "appimage") { const f = name || "Aerion_*.AppImage"; return "chmod +x " + f + " && ./" + f; }
    return "sudo apt install ./" + (name || "Aerion_*.deb");
  };

  function renderDownloads() {
    const v = $("version"), vl = $("version-line");
    if (v) v.textContent = release ? release.version : "";
    if (vl) vl.hidden = !release;
    const line = $("rel-line");
    line.textContent = "";
    if (release) {
      const when = new Date(release.date).toLocaleDateString(lang(), { day: "numeric", month: "long", year: "numeric" });
      line.append(tr("version", release.version) + " · " + tr("date", when) + " · ");
      const a = document.createElement("a");
      a.href = release.page;
      a.textContent = tr("notes");
      line.append(a);
    } else {
      line.textContent = failed ? tr("failed") : tr("loading");
    }
    document.querySelectorAll(".file[data-file]").forEach((card) => {
      const kind = card.dataset.file;
      const f = release && release.files[kind];
      // Aerion Setup is offered once a release has it; the regular installer steps back then.
      if (kind === "setup") card.hidden = !f;
      if (kind === "exe") card.querySelector("[data-dl]").classList.toggle("primary", !(release && release.files.setup));
      card.querySelector("[data-dl]").href = f ? f.url : PAGE;
      card.querySelector("[data-size]").textContent = f ? f.name + " · " + mb(f.size) : "";
      const sum = card.querySelector(".sum");
      sum.hidden = !(f && f.sha);
      if (f && f.sha) card.querySelector("[data-sha]").textContent = f.sha;
      const cmd = card.querySelector("[data-cmd]");
      if (cmd) cmd.textContent = command(kind, f && f.name);
    });
    const main = mainFile();
    const f = release && main && release.files[main];
    // On Linux the button leads to the one-line install, which also sets up self-updating.
    $("hero-dl").href = f && os !== "linux" ? f.url : "#download";
    $("hero-dl-text").textContent = f ? tr(os === "windows" ? "win" : "linux") : tr("any");
    $("hero-dl-sub").textContent = f ? release.version + " · " + mb(f.size) : "";
    $("hero-dl-sub").hidden = !f;
  }

  function adopt(j) {
    const pick = (test) => {
      const a = (j.assets || []).find((x) => test(x.name));
      return a ? { name: a.name, size: a.size, url: a.browser_download_url, sha: (a.digest || "").replace(/^sha256:/, "") } : null;
    };
    const files = { setup: pick((n) => n === "AerionSetup.exe"), exe: pick((n) => n.endsWith("-setup.exe")), appimage: pick((n) => n.endsWith(".AppImage")), deb: pick((n) => n.endsWith(".deb")) };
    return files.setup || files.exe || files.appimage || files.deb ? { version: String(j.tag_name).replace(/^v/, ""), date: j.published_at, page: j.html_url, files } : null;
  }

  function loadRelease() {
    try {
      const c = JSON.parse(store.get(sessionStorage, "aerion-release") || "null");
      if (c && Date.now() - c.at < 600000) { release = c.release; renderDownloads(); return; }
    } catch { /* bad cache */ }
    fetch(API, { headers: { Accept: "application/vnd.github+json" } })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
      .then((j) => {
        release = adopt(j);
        failed = !release;
        if (release) store.set(sessionStorage, "aerion-release", JSON.stringify({ at: Date.now(), release }));
        renderDownloads();
      })
      .catch(() => { failed = true; renderDownloads(); });
  }

  function tablist(tabs, activate) {
    tabs.forEach((t, i) => {
      t.addEventListener("click", () => activate(t));
      t.addEventListener("keydown", (e) => {
        if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
        e.preventDefault();
        const next = tabs[(i + (e.key === "ArrowRight" ? 1 : tabs.length - 1)) % tabs.length];
        activate(next);
        next.focus();
      });
    });
  }

  const osTabs = [...document.querySelectorAll('.os-tabs [role="tab"]')];
  function showOs(tab) {
    for (const t of osTabs) {
      const on = t === tab;
      t.setAttribute("aria-selected", String(on));
      t.tabIndex = on ? 0 : -1;
      $(t.getAttribute("aria-controls")).hidden = !on;
    }
  }
  tablist(osTabs, showOs);
  if (os === "linux") showOs($("tab-linux"));
  if (os === "mobile" || os === "mac") $("no-desktop").hidden = false;

  $("hero-dl").addEventListener("click", () => {
    if (!$("hero-dl").getAttribute("href").startsWith("#")) setTimeout(() => $("download").scrollIntoView(), 400);
  });

  document.querySelectorAll(".copy-btn").forEach((b) => b.addEventListener("click", async () => {
    const code = b.parentElement.querySelector("code");
    try {
      await navigator.clipboard.writeText(code.textContent);
      const old = b.textContent;
      b.textContent = tr("copied");
      setTimeout(() => { b.textContent = old; }, 1500);
    } catch {
      const r = document.createRange();
      r.selectNodeContents(code);
      getSelection().removeAllRanges();
      getSelection().addRange(r);
    }
  }));

  // --- product tour ---------------------------------------------------------------------------
  const tourTabs = [...$("tour-tabs").querySelectorAll('[role="tab"]')];
  function showTour(tab) {
    for (const t of tourTabs) {
      const on = t === tab;
      t.setAttribute("aria-selected", String(on));
      t.tabIndex = on ? 0 : -1;
    }
    swap($("tour-img"), tab.dataset.shot, tab.textContent);
  }
  tablist(tourTabs, showTour);

  // --- theme preview --------------------------------------------------------------------------
  const chips = [...document.querySelectorAll("#theme-chips button")];
  chips.forEach((b) => b.addEventListener("click", () => {
    chips.forEach((o) => o.setAttribute("aria-pressed", String(o === b)));
    swap($("theme-img"), "home-" + b.dataset.themeShot);
  }));

  // --- HUD demo -------------------------------------------------------------------------------
  const scene = $("scene");
  const looks = [...document.querySelectorAll(".looks [data-look]")];
  looks.forEach((b) => b.addEventListener("click", () => {
    scene.dataset.look = b.dataset.look;
    looks.forEach((o) => o.setAttribute("aria-pressed", String(o === b)));
  }));
  $("combat").addEventListener("click", () => {
    $("combat").setAttribute("aria-checked", String(scene.classList.toggle("fight")));
  });
  scene.querySelectorAll(".hud-mod").forEach((m) => {
    m.addEventListener("pointerdown", (e) => {
      if (e.button !== 0) return;
      m.setPointerCapture(e.pointerId);
      m.classList.add("drag");
      const sr = scene.getBoundingClientRect(), mr = m.getBoundingClientRect();
      const dx = e.clientX - mr.left, dy = e.clientY - mr.top;
      const move = (ev) => {
        const x = Math.min(Math.max(0, ev.clientX - sr.left - dx), sr.width - mr.width);
        const y = Math.min(Math.max(0, ev.clientY - sr.top - dy), sr.height - mr.height);
        m.style.left = (x / sr.width) * 100 + "%";
        m.style.top = (y / sr.height) * 100 + "%";
        m.style.right = "auto";
        m.style.bottom = "auto";
      };
      const up = () => { m.classList.remove("drag"); m.removeEventListener("pointermove", move); m.removeEventListener("pointerup", up); m.removeEventListener("pointercancel", up); };
      m.addEventListener("pointermove", move);
      m.addEventListener("pointerup", up);
      m.addEventListener("pointercancel", up);
    });
  });

  // --- sections appear once -------------------------------------------------------------------
  const targets = [...document.querySelectorAll(".reveal")];
  if ("IntersectionObserver" in window) {
    const io = new IntersectionObserver((entries) => {
      for (const e of entries) if (e.isIntersecting) { e.target.classList.add("in"); io.unobserve(e.target); }
    }, { threshold: 0.05 });
    targets.forEach((t) => io.observe(t));
  } else {
    targets.forEach((t) => t.classList.add("in"));
  }

  // --- start ----------------------------------------------------------------------------------
  $("lang").addEventListener("click", () => applyLang(lang() === "en" ? "pl" : "en"));
  applyLang(store.get(localStorage, "lang") || ((navigator.language || "pl").toLowerCase().startsWith("pl") ? "pl" : "en"));
  loadRelease();
})();
