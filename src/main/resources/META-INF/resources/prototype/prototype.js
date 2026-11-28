// PROTOTYPE, throwaway: the variant switcher and the bits of data the prototype variants pull from other views.
// Lives on the prototype branch only. Reads the real views over fetch; never posts anything.
(() => {
  const VARIANTS = {
    home: [["A", "Current"], ["B", "Next-run preview"], ["C", "Timeline"]],
    groups: [["A", "Current"], ["B", "Compact rows"], ["C", "Verdict lanes"], ["D", "List + detail"]],
    overrides: [["A", "Current"], ["B", "By name, hashes hidden"], ["C", "By status, bulk tidy"]],
    orphans: [["A", "Current"], ["B", "Relative paths, by size"]],
    activity: [["A", "Current"], ["B", "Ledger + chart"]],
    system: [["A", "Current"], ["B", "Health checklist (System + Settings)"]],
    settings: [["A", "Current"]],
  };

  const body = document.body;
  const current = body.dataset.protoCurrent;
  const list = VARIANTS[current] || [["A", "Current"]];
  const params = new URLSearchParams(location.search);
  const variant = (params.get("variant") || "A").toUpperCase();

  function go(key) {
    const p = new URLSearchParams(location.search);
    if (key === "A") p.delete("variant"); else p.set("variant", key);
    const q = p.toString();
    location.replace(location.pathname + (q ? "?" + q : ""));
  }

  function step(delta) {
    const i = list.findIndex(([k]) => k === variant);
    const next = list[(i + delta + list.length) % list.length];
    go(next[0]);
  }

  const label = document.querySelector("[data-proto-label]");
  const named = list.find(([k]) => k === variant);
  label.textContent = `${current} · ${variant} (${named ? named[1] : "unknown"}) · ${list.findIndex(([k]) => k === variant) + 1}/${list.length}`;
  document.querySelectorAll("[data-proto-step]").forEach((b) =>
    b.addEventListener("click", () => step(Number(b.dataset.protoStep))));
  document.querySelectorAll("button[data-proto-nav]").forEach((b) => {
    if (b.dataset.protoNav === body.dataset.protoNav) b.setAttribute("aria-pressed", "true");
    b.addEventListener("click", () => {
      document.cookie = `proto-nav=${b.dataset.protoNav}; path=/; max-age=31536000; samesite=lax`;
      const p = new URLSearchParams(location.search);
      p.delete("nav");
      const q = p.toString();
      location.replace(location.pathname + (q ? "?" + q : ""));
    });
  });
  document.addEventListener("keydown", (e) => {
    const t = e.target;
    if (t.closest && t.closest("input, textarea, select, [contenteditable]")) return;
    if (e.metaKey || e.ctrlKey || e.altKey) return;
    if (e.key === "ArrowLeft") step(-1);
    if (e.key === "ArrowRight") step(1);
  });

  // Fetch a view and parse it. Cached per page load.
  const cache = {};
  window.protoFetch = (url) =>
    (cache[url] ||= fetch(url, { credentials: "same-origin" })
      .then((r) => r.text())
      .then((html) => new DOMParser().parseFromString(html, "text/html")));

  // Links to this same view (tally, sort, pages, clear) keep the variant. Re-run after an htmx swap of <main>.
  function keepVariant() {
    if (variant === "A") return;
    document.querySelectorAll("main a[href^='/']").forEach((a) => {
      const u = new URL(a.getAttribute("href"), location.origin);
      if (u.pathname !== location.pathname || u.searchParams.has("variant")) return;
      u.searchParams.set("variant", variant);
      a.setAttribute("href", u.pathname + u.search + u.hash);
    });
  }

  // Groups D: show the detail of the group in the #hash, else the first; j/k move.
  function masterDetail() {
    const links = [...document.querySelectorAll(".proto-md-list a")];
    if (!links.length) return;
    const show = () => {
      const id = location.hash.slice(1) || links[0].hash.slice(1);
      document.querySelectorAll(".proto-md-detail > article").forEach((a) => (a.hidden = a.id !== id));
      links.forEach((l) => l.toggleAttribute("aria-current", l.hash.slice(1) === id));
    };
    window.onhashchange = show;
    show();
    document.addEventListener("keydown", (e) => {
      if (e.target.closest("input, textarea, select")) return;
      if (e.key !== "j" && e.key !== "k") return;
      const i = links.findIndex((l) => l.hasAttribute("aria-current"));
      const n = links[Math.max(0, Math.min(links.length - 1, i + (e.key === "j" ? 1 : -1)))];
      history.replaceState(null, "", n.hash);
      show();
      n.scrollIntoView({ block: "nearest" });
    });
  }

  function pageScripts() {
    keepVariant();
    masterDetail();
    (window.protoPage || []).forEach((f) => f());
  }
  document.addEventListener("htmx:afterSettle", pageScripts);
  pageScripts();

  // The live status the header variants B and C show.
  const fills = document.querySelectorAll("[data-proto-fill], [data-proto-fill-tone]");
  if (fills.length) {
    Promise.all([protoFetch("/"), protoFetch("/system")]).then(([home, system]) => {
      const count = (code) => {
        const m = home.querySelector(`#verdicts mark[data-verdict="${code}"]`);
        return m ? m.textContent.trim() : "?";
      };
      const dd = (doc, section, term) => {
        const dt = [...doc.querySelectorAll(`#${section} dt`)].find((d) => d.textContent.trim() === term);
        return dt ? dt.nextElementSibling : null;
      };
      const next = dd(home, "runs", "Next run");
      const last = dd(home, "runs", "Last run");
      const tm = system.querySelector("#transmission dd[data-tone]");
      const data = {
        leftAlone: count("left-alone") === "0" ? "" : count("left-alone"),
        removeCount: count("remove") + " groups",
        nextRelative: next ? (next.querySelector("span")?.textContent || next.textContent).trim() : "?",
        lastRun: last ? (last.querySelector("span")?.textContent || last.textContent).trim() : "?",
        transmission: tm ? tm.textContent.trim() : "?",
      };
      document.querySelectorAll("[data-proto-fill]").forEach((el) => {
        el.textContent = data[el.dataset.protoFill] ?? "";
        if (el.classList.contains("proto-badge") && !el.textContent) el.hidden = true;
      });
      document.querySelectorAll("[data-proto-fill-tone]").forEach((el) => {
        el.dataset.tone = tm ? tm.dataset.tone : "muted";
      });
    });
  }
})();
