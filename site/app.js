// Language switch (Polish is the page's own text; English is below) and the latest version number.
(function () {
  const EN = {
    "nav.hud": "HUD", "nav.features": "Features", "nav.start": "Get started", "nav.faq": "FAQ",
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
    "s.title": "How to start",
    "s.1t": "Download", "s.1d": "The Windows installer or the Linux package from the latest release.",
    "s.2t": "Sign in", "s.2d": "With the Microsoft account that owns Minecraft: Java Edition.",
    "s.3t": "Play", "s.3d": "Create an instance with the Aerion mod and press Play. Right Shift in the game opens the HUD editor.",
    "q.title": "Questions",
    "q.1q": "Is it legal and safe?", "q.1a": "Yes. You sign in with an official Microsoft account and need to own the game. Aerion is an unofficial launcher, not affiliated with Mojang or Microsoft. The code is open and updates are signed.",
    "q.2q": "Does it collect my data?", "q.2a": "No. There is no analytics or tracking. The launcher only talks to the services it needs: Microsoft, Mojang, Modrinth and GitHub.",
    "q.3q": "Does it run on Linux?", "q.3a": "Yes, there are .deb and AppImage packages next to the Windows version.",
    "q.4q": "Where do I report a bug or an idea?", "q.4a": "On the Discord (the report and ideas channels) or in Issues on GitHub.",
    "foot.disclaimer": "Unofficial product. Aerion is not approved by or associated with Mojang or Microsoft. “Minecraft” is a trademark of Mojang Synergies AB.",
    "foot.releases": "Releases",
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
  let version = "";
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
    const v = document.getElementById("version");
    if (v && version) v.textContent = version;
    try { localStorage.setItem("lang", lang); } catch { /* storage unavailable */ }
  }
  let saved = null;
  try { saved = localStorage.getItem("lang"); } catch { /* storage unavailable */ }
  let lang = saved || ((navigator.language || "pl").toLowerCase().startsWith("pl") ? "pl" : "en");
  document.getElementById("lang").addEventListener("click", () => { lang = lang === "en" ? "pl" : "en"; apply(lang); });
  apply(lang);

  // The newest release's number, when GitHub answers; the page works without it.
  fetch("https://api.github.com/repos/0giel/Aerion/releases/latest", { headers: { Accept: "application/vnd.github+json" } })
    .then((r) => (r.ok ? r.json() : null))
    .then((j) => {
      if (j && typeof j.tag_name === "string" && /^v?\d[\w.-]*$/.test(j.tag_name)) {
        version = j.tag_name.replace(/^v/, "");
        const v = document.getElementById("version");
        if (v) v.textContent = version;
      }
    })
    .catch(() => {});
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
    }, { threshold: 0.15 });
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
