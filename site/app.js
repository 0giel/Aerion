// Language switch (Polish is the page's own text; English is below) and the latest version number.
(function () {
  const EN = {
    "nav.hud": "HUD", "nav.features": "Features", "nav.start": "Download", "nav.faq": "FAQ",
    "hero.pill": 'Version <span id="version">1.0</span> · Windows and Linux',
    "hero.title": "A Minecraft launcher<br>with its own <em>HUD</em>",
    "hero.lead": "Fast, light and highly customizable. Its own mod with a HUD you arrange with the mouse, themes inside the game menus, Modrinth mods and no data collection.",
    "hero.download": "Download", "hero.discord": "Join the Discord", "hero.source": "Source on GitHub",
    "hero.need": "Needs a Microsoft account that owns Minecraft: Java Edition.",
    "hud.title": "A HUD you arrange your way",
    "hud.text": "Over twenty modules — FPS, coordinates, potions, keystrokes, map — each with its own look, anchor and rules: show it in combat, hide it underground, fade it out after a few seconds.",
    "hud.b1": "Drag and resize, with snapping to edges", "hud.b2": "Profiles (PvP, building, survival) switched with a key or by themselves when you join a server",
    "hud.b3": "Ready-made looks: Minimal, Glass, Neon, Retro",
    "theme.title": "Your theme in the game too",
    "theme.text": "A theme editor with live preview, a theme gallery and animated backgrounds. The launcher's colours reach Minecraft's menus, and the menu's position, style and effects are set in the theme itself.",
    "theme.b1": "Menu on the left, right, top or bottom, opening on hover", "theme.b2": "Springy animations and effects you can turn off with one switch",
    "theme.b3": "Golden Amber by default, with several themes to choose from",
    "f.title": "Everything you need to play",
    "f.1t": "Mods and packs", "f.1d": "Fabric, Quilt, Forge and NeoForge, Modrinth mods and packs in one click, with their dependencies.",
    "f.2t": "Map and waypoints", "f.2d": "An in-game map where you add waypoints, a death point and sharing coordinates in chat.",
    "f.3t": "Key editor", "f.3d": "A keyboard in the game that highlights conflicts, and a shared one in the launcher.",
    "f.4t": "Crash fixes", "f.4d": "Game crashes analysed with one-click fixes, and the game console in the launcher.",
    "f.5t": "Updates by themselves", "f.5d": "The launcher downloads new versions in the background, and the mod updates independently of it.",
    "f.6t": "Privacy", "f.6d": "No data collection. Open source under GPL-3.0.",
    "s.title": "After installing",
    "s.1t": "Sign in", "s.1d": "With the Microsoft account that owns Minecraft: Java Edition.",
    "s.2t": "Create an instance", "s.2d": "Pick a game version and turn on the Aerion mod. The launcher downloads Java by itself.",
    "s.3t": "Play", "s.3d": "Press Play. Right Shift in the game opens the HUD editor.",
    "q.title": "Questions",
    "q.1q": "Is it legal and safe?", "q.1a": "Yes. You sign in with an official Microsoft account and need to own the game. Aerion is an unofficial launcher, not affiliated with Mojang or Microsoft. The code is open and updates are signed.",
    "q.2q": "Does it collect my data?", "q.2a": "No. There is no analytics or tracking. The launcher only connects to what it needs to work: Microsoft sign-in, Mojang's servers, mod and loader sources (Modrinth, CurseForge, Fabric, Quilt, Forge, NeoForge) and GitHub for updates.",
    "q.3q": "Which systems does it run on?", "q.3a": "Windows 10 and 11, and Linux, 64-bit in both cases. There is no macOS or phone version.",
    "q.5q": "Do I need to install Java?", "q.5a": "No. The launcher downloads the Java version each game version needs.",
    "q.6q": "Why does Windows warn about the installer?", "q.6a": "The installer has no paid publisher certificate, so SmartScreen doesn't recognize it. The code is open, and this page lists the SHA-256 of every file.",
    "q.4q": "Where do I report a bug or an idea?", "q.4a": "On the Discord (the report and ideas channels) or in Issues on GitHub.",
    "foot.disclaimer": "Unofficial product. Aerion is not approved by or associated with Mojang or Microsoft. “Minecraft” is a trademark of Mojang Synergies AB.",
    "foot.releases": "Releases",
    "hero.other": "Other files and instructions",
    "g.title": "See it in action", "g.1": "Arranging the HUD in the game", "g.2": "HUD profiles", "g.3": "The title screen with the mod",
    "d.title": "Download and install", "d.get": "Download", "d.copy": "Copy", "d.verify": "SHA-256 checksum",
    "d.unsupported": "Aerion runs on Windows and Linux computers. You can't run it on this device, but you can come back here from a computer.",
    "d.exeT": "Windows installer", "d.exeD": "Windows 10 or 11, 64-bit. No administrator rights needed.",
    "d.w1": "Run the downloaded file and go through the installer.",
    "d.w2": "If Windows shows a SmartScreen warning, choose <b>More info</b>, then <b>Run anyway</b>. It appears because the installer has no paid publisher certificate.",
    "d.w3": "If the WebView2 component is missing, the installer downloads it by itself.",
    "d.appD": "Most distributions, nothing to install. 64-bit.",
    "d.appN": "After downloading, run this command in the file's folder. If the system reports that FUSE is missing, install the <code>libfuse2</code> package.",
    "d.debD": "Ubuntu, Debian, Mint, Zorin and similar. 64-bit.",
    "d.debN": "After downloading, run this command in the file's folder. Dependencies install by themselves.",
    "d.updates": "You don't have to download updates by hand: the launcher and the mod update themselves.",
    "st.1": "HUD modules", "st.2": "mod loaders", "st.3": "themes included", "st.4": "data collected",
    "try.title": "Try the HUD right here", "try.text": "Drag the modules and switch the ready-made looks. It is the same idea as in the game.",
    "try.default": "Default", "try.minimal": "Minimal", "try.glass": "Glass", "try.neon": "Neon", "try.retro": "Retro", "try.combat": "Simulate a fight",
    "try.hint": "Tip: in the game every module also has its own rules, e.g. “show only in combat”.",
  };
  const nodes = [...document.querySelectorAll("[data-i18n], [data-i18n-html]")];
  const PL = {};
  for (const n of nodes) {
    const key = n.dataset.i18n || n.dataset.i18nHtml;
    PL[key] = n.dataset.i18nHtml ? n.innerHTML : n.textContent;
  }
  function apply(lang) {
    const dict = lang === "en" ? EN : PL;
    for (const n of nodes) {
      const key = n.dataset.i18n || n.dataset.i18nHtml;
      if (dict[key] === undefined) continue;
      if (n.dataset.i18nHtml) n.innerHTML = dict[key];
      else n.textContent = dict[key];
    }
    document.documentElement.lang = lang;
    document.title = lang === "en" ? "Aerion — a Minecraft launcher with its own HUD" : "Aerion — launcher Minecrafta z własnym HUD-em";
    document.getElementById("lang").textContent = lang === "en" ? "PL" : "EN";
    try { localStorage.setItem("lang", lang); } catch { /* storage unavailable */ }
    document.dispatchEvent(new Event("aerion:lang"));
  }
  let saved = null;
  try { saved = localStorage.getItem("lang"); } catch { /* storage unavailable */ }
  let lang = saved || ((navigator.language || "pl").toLowerCase().startsWith("pl") ? "pl" : "en");
  document.getElementById("lang").addEventListener("click", () => { lang = lang === "en" ? "pl" : "en"; apply(lang); });
  apply(lang);
})();

// Page theme, dust, reveal on scroll, numbers, tilt and the HUD playground.
(function () {
  const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const root = document.documentElement;

  // --- page theme -------------------------------------------------------------------------
  const dots = [...document.querySelectorAll(".dot")];
  function setTheme(name) {
    root.dataset.theme = name;
    dots.forEach((d) => d.setAttribute("aria-pressed", String(d.dataset.theme === name)));
    try { localStorage.setItem("theme", name); } catch { /* storage unavailable */ }
    accent = getComputedStyle(root).getPropertyValue("--accent").trim() || "#fbbf24";
  }
  let accent = "#fbbf24";
  let saved = null;
  try { saved = localStorage.getItem("theme"); } catch { /* storage unavailable */ }
  dots.forEach((d) => d.addEventListener("click", () => setTheme(d.dataset.theme)));
  setTheme(saved && dots.some((d) => d.dataset.theme === saved) ? saved : "amber");

  // --- reveal on scroll and counting numbers ------------------------------------------------
  function countUp(el) {
    const to = Number(el.dataset.count);
    if (reduce || !to) { el.textContent = String(to); return; }
    const t0 = performance.now();
    const step = (t) => {
      const k = Math.min(1, (t - t0) / 1200);
      el.textContent = String(Math.round(to * (1 - Math.pow(1 - k, 3))));
      if (k < 1) requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  }
  const targets = [...document.querySelectorAll(".reveal")];
  if ("IntersectionObserver" in window) {
    const io = new IntersectionObserver((entries) => {
      for (const e of entries) {
        if (!e.isIntersecting) continue;
        e.target.classList.add("in");
        e.target.querySelectorAll("[data-count]").forEach(countUp);
        io.unobserve(e.target);
      }
    }, { threshold: 0.05 });
    targets.forEach((t) => io.observe(t));
  } else {
    targets.forEach((t) => { t.classList.add("in"); t.querySelectorAll("[data-count]").forEach(countUp); });
  }

  // --- dust behind the hero (stops when it is off screen or motion is reduced) ---------------
  const canvas = document.getElementById("dust");
  if (canvas && !reduce) {
    const ctx = canvas.getContext("2d");
    let w = 0, h = 0, running = true;
    const dust = Array.from({ length: 55 }, () => ({ x: Math.random(), y: Math.random(), r: 0.6 + Math.random() * 1.6, v: 0.015 + Math.random() * 0.05, a: 0.15 + Math.random() * 0.5 }));
    const resize = () => {
      const d = Math.min(2, devicePixelRatio || 1);
      w = canvas.clientWidth; h = canvas.clientHeight;
      canvas.width = w * d; canvas.height = h * d; ctx.setTransform(d, 0, 0, d, 0, 0);
    };
    resize(); addEventListener("resize", resize);
    let last = performance.now();
    const frame = (t) => {
      const dt = Math.min(0.05, (t - last) / 1000); last = t;
      ctx.clearRect(0, 0, w, h);
      ctx.fillStyle = accent;
      for (const p of dust) {
        p.y -= p.v * dt; if (p.y < -0.02) { p.y = 1.02; p.x = Math.random(); }
        ctx.globalAlpha = p.a; ctx.beginPath(); ctx.arc(p.x * w, p.y * h, p.r, 0, 6.283); ctx.fill();
      }
      if (running) requestAnimationFrame(frame);
    };
    new IntersectionObserver((e) => {
      const on = e[0].isIntersecting;
      if (on && !running) { running = true; last = performance.now(); requestAnimationFrame(frame); }
      running = on;
    }).observe(canvas);
    requestAnimationFrame(frame);
  }

  // --- the screenshot leans a little towards the pointer -----------------------------------
  const tilt = document.querySelector(".tilt");
  if (tilt && !reduce && matchMedia("(hover: hover)").matches) {
    tilt.addEventListener("pointermove", (e) => {
      const r = tilt.getBoundingClientRect();
      const x = (e.clientX - r.left) / r.width - 0.5, y = (e.clientY - r.top) / r.height - 0.5;
      tilt.style.transform = `perspective(1100px) rotateX(${(-y * 5).toFixed(2)}deg) rotateY(${(x * 7).toFixed(2)}deg)`;
    });
    tilt.addEventListener("pointerleave", () => { tilt.style.transform = ""; });
  }

  // --- the HUD playground ---------------------------------------------------------------------
  const scene = document.getElementById("scene");
  if (scene) {
    document.querySelectorAll(".looks [data-look]").forEach((b) => b.addEventListener("click", () => {
      scene.dataset.look = b.dataset.look;
      document.querySelectorAll(".looks [data-look]").forEach((o) => o.classList.toggle("on", o === b));
    }));
    const combat = document.getElementById("combat");
    combat.addEventListener("click", () => {
      const on = scene.classList.toggle("fight");
      combat.setAttribute("aria-pressed", String(on));
    });
    // Drag a module, kept inside the scene.
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
          m.style.left = (x / sr.width * 100) + "%"; m.style.top = (y / sr.height * 100) + "%";
          m.style.right = "auto"; m.style.bottom = "auto";
        };
        const up = () => { m.classList.remove("drag"); m.removeEventListener("pointermove", move); m.removeEventListener("pointerup", up); };
        m.addEventListener("pointermove", move); m.addEventListener("pointerup", up);
      });
    });
    const fps = document.getElementById("fps");
    setInterval(() => { if (!document.hidden) fps.textContent = String(138 + Math.floor(Math.random() * 9)); }, 700);
  }
})();

// Downloads: exact files of the newest release, picked for this computer. Without an answer from
// GitHub every button simply opens the release page.
(function () {
  const PAGE = "https://github.com/0giel/Aerion/releases/latest";
  const API = "https://api.github.com/repos/0giel/Aerion/releases/latest";
  const CACHE_MS = 10 * 60 * 1000;
  const TEXT = {
    pl: { win: "Pobierz dla Windows", linux: "Pobierz dla Linuksa", any: "Pobierz", version: "Wersja {v}", date: "wydana {d}", notes: "Co nowego", copied: "Skopiowano", failed: "Nie udało się odczytać listy plików. Przyciski prowadzą do strony wydania na GitHubie.", loading: "Sprawdzam najnowszą wersję…" },
    en: { win: "Download for Windows", linux: "Download for Linux", any: "Download", version: "Version {v}", date: "released {d}", notes: "What's new", copied: "Copied", failed: "Couldn't read the list of files. The buttons open the release page on GitHub.", loading: "Checking the newest version…" },
  };
  const lang = () => (document.documentElement.lang === "en" ? "en" : "pl");
  const tr = (k, v) => TEXT[lang()][k].replace(/\{(\w)\}/, () => v ?? "");

  const ua = navigator.userAgent;
  const os = /Android|iPhone|iPad|iPod/i.test(ua) ? "mobile" : /Windows/i.test(ua) ? "windows" : /Mac/i.test(ua) ? "mac" : /Linux|X11/i.test(ua) ? "linux" : "other";
  const mainFile = { windows: "exe", linux: "appimage" }[os];

  let release = null;
  let failed = false;

  function mb(bytes) {
    const n = bytes / 1048576;
    return n.toLocaleString(lang(), { maximumFractionDigits: n >= 10 ? 0 : 1 }) + " MB";
  }

  function command(kind, name) {
    if (kind === "appimage") {
      const f = name || "Aerion_*.AppImage";
      return "chmod +x " + f + " && ./" + f;
    }
    return "sudo apt install ./" + (name || "Aerion_*.deb");
  }

  function render() {
    const v = document.getElementById("version");
    if (v && release) v.textContent = release.version;

    const line = document.getElementById("rel-line");
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

    document.querySelectorAll(".file").forEach((card) => {
      const kind = card.dataset.file;
      const f = release && release.files[kind];
      card.querySelector("[data-dl]").href = f ? f.url : PAGE;
      card.querySelector("[data-size]").textContent = f ? f.name + " · " + mb(f.size) : "";
      const sum = card.querySelector(".sum");
      sum.hidden = !(f && f.sha);
      if (f && f.sha) card.querySelector("[data-sha]").textContent = f.sha;
      const cmd = card.querySelector("[data-cmd]");
      if (cmd) cmd.textContent = command(kind, f && f.name);
    });

    // The big button downloads the right file straight away; on other devices it opens the section.
    const hero = document.getElementById("hero-dl");
    const label = document.getElementById("hero-dl-text");
    const sub = document.getElementById("hero-dl-sub");
    const f = release && mainFile && release.files[mainFile];
    hero.href = f ? f.url : "#download";
    label.textContent = f ? tr(os === "windows" ? "win" : "linux") : tr("any");
    sub.textContent = f ? release.version + " · " + mb(f.size) : "";
    sub.hidden = !f;
  }

  function adopt(j) {
    const pick = (test) => {
      const a = (j.assets || []).find((x) => test(x.name));
      return a ? { name: a.name, size: a.size, url: a.browser_download_url, sha: (a.digest || "").replace(/^sha256:/, "") } : null;
    };
    const files = { exe: pick((n) => n.endsWith("-setup.exe")), appimage: pick((n) => n.endsWith(".AppImage")), deb: pick((n) => n.endsWith(".deb")) };
    if (!files.exe && !files.appimage && !files.deb) return null;
    return { version: String(j.tag_name).replace(/^v/, ""), date: j.published_at, page: j.html_url, files };
  }

  function load() {
    try {
      const c = JSON.parse(sessionStorage.getItem("aerion-release") || "null");
      if (c && Date.now() - c.at < CACHE_MS) { release = c.release; render(); return; }
    } catch { /* storage unavailable */ }
    fetch(API, { headers: { Accept: "application/vnd.github+json" } })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
      .then((j) => {
        release = adopt(j);
        failed = !release;
        if (release) {
          try { sessionStorage.setItem("aerion-release", JSON.stringify({ at: Date.now(), release })); } catch { /* storage unavailable */ }
        }
        render();
      })
      .catch(() => { failed = true; render(); });
  }

  // --- tabs -----------------------------------------------------------------------------------
  const tabs = [...document.querySelectorAll('.os-tabs [role="tab"]')];
  function show(tab, focus) {
    for (const t of tabs) {
      const on = t === tab;
      t.setAttribute("aria-selected", String(on));
      t.tabIndex = on ? 0 : -1;
      document.getElementById(t.getAttribute("aria-controls")).hidden = !on;
    }
    if (focus) tab.focus();
  }
  tabs.forEach((t, i) => {
    t.addEventListener("click", () => show(t));
    t.addEventListener("keydown", (e) => {
      if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
      e.preventDefault();
      show(tabs[(i + (e.key === "ArrowRight" ? 1 : tabs.length - 1)) % tabs.length], true);
    });
  });
  if (os === "linux") show(document.getElementById("tab-linux"));
  if (os === "mobile" || os === "mac") document.getElementById("no-desktop").hidden = false;

  // After the big button starts a download, show what to do next.
  document.getElementById("hero-dl").addEventListener("click", () => {
    if (!document.getElementById("hero-dl").href.endsWith("#download")) {
      setTimeout(() => document.getElementById("download").scrollIntoView({ behavior: "smooth" }), 400);
    }
  });

  // --- copying a command ----------------------------------------------------------------------
  document.querySelectorAll(".copy").forEach((b) => b.addEventListener("click", async () => {
    const code = b.parentElement.querySelector("code");
    try {
      await navigator.clipboard.writeText(code.textContent);
      const old = b.textContent;
      b.textContent = tr("copied");
      setTimeout(() => { b.textContent = old; }, 1500);
    } catch {
      // No clipboard access (e.g. a local file): select it for Ctrl+C.
      const r = document.createRange();
      r.selectNodeContents(code);
      const sel = getSelection();
      sel.removeAllRanges();
      sel.addRange(r);
    }
  }));

  // --- screenshot gallery ---------------------------------------------------------------------
  const box = document.getElementById("lightbox");
  document.querySelectorAll(".gal button").forEach((b) => b.addEventListener("click", () => {
    box.querySelector("img").src = b.dataset.full;
    box.querySelector("img").alt = b.querySelector("span").textContent;
    box.showModal();
  }));
  box.addEventListener("click", (e) => { if (e.target === box || e.target.classList.contains("close")) box.close(); });

  document.addEventListener("aerion:lang", render);
  render();
  load();
})();
