// Runs in every page. Watches for audio or video playing and reports it to the extension, which
// passes it to the Mac n Droid app. Nothing leaves your Mac except to your paired phone.
(() => {
  // Added both by the browser (new tabs) and by the extension (tabs already open when it was installed).
  if (window.__macNDroid) return;
  window.__macNDroid = true;
  const SITES = {
    "music.youtube.com": { name: "YouTube Music", next: ".next-button", prev: ".previous-button" },
    "www.youtube.com": { name: "YouTube", next: ".ytp-next-button", prev: ".ytp-prev-button" },
    "youtube.com": { name: "YouTube", next: ".ytp-next-button", prev: ".ytp-prev-button" },
    "open.spotify.com": { name: "Spotify", next: '[data-testid="control-button-skip-forward"]', prev: '[data-testid="control-button-skip-back"]' },
    "soundcloud.com": { name: "SoundCloud", next: ".skipControl__next", prev: ".skipControl__previous" },
  };
  const site = SITES[location.hostname] || { name: location.hostname.replace(/^www\./, "") };

  function visible(sel) {
    const el = sel && document.querySelector(sel);
    return el && el.offsetParent !== null && !el.disabled && el.getAttribute("aria-disabled") !== "true" ? el : null;
  }

  /** The element that's playing, or the last one that played. Muted previews and ads are ignored. */
  function media() {
    const all = [...document.querySelectorAll("video, audio")].filter((e) => !e.muted && e.volume > 0);
    return all.find((e) => !e.paused && !e.ended && e.readyState > 2)
      || all.filter((e) => e.currentTime > 0 && !e.ended).sort((a, b) => (b.duration || 0) - (a.duration || 0))[0];
  }

  function state() {
    const el = media();
    if (!el) return null;
    const md = navigator.mediaSession && navigator.mediaSession.metadata;
    const art = md && md.artwork && md.artwork.length ? md.artwork[md.artwork.length - 1].src : "";
    const title = (md && md.title) || document.title.replace(/ - YouTube$/, "");
    if (!title) return null;
    return {
      site: site.name,
      title,
      artist: (md && md.artist) || "",
      album: (md && md.album) || "",
      playing: !el.paused && !el.ended,
      dur: isFinite(el.duration) ? el.duration : 0,
      pos: el.currentTime || 0,
      art,
      canNext: !!visible(site.next),
      canPrev: true,
    };
  }

  let last = "";
  function report(force) {
    let s;
    try { s = state(); } catch (e) { return; }
    if (!s) {
      if (last) { last = ""; safeSend({ t: "media_end" }); }
      return;
    }
    const sig = JSON.stringify([s.title, s.artist, s.playing, s.art, s.canNext, Math.round(s.pos / 15)]);
    if (!force && sig === last) return;
    last = sig;
    safeSend({ t: "media", ...s });
  }

  function safeSend(m) {
    try { chrome.runtime.sendMessage(m).catch(() => {}); } catch (e) { /* extension reloaded */ }
  }

  for (const ev of ["play", "pause", "ended", "loadedmetadata", "emptied"]) {
    document.addEventListener(ev, () => setTimeout(() => report(true), 50), true);
  }
  setInterval(() => report(false), 2000);

  // The toolbar icon is white; tell the extension when the browser is in light mode so it can use a dark one.
  const scheme = matchMedia("(prefers-color-scheme: dark)");
  safeSend({ t: "theme", dark: scheme.matches });
  scheme.addEventListener("change", () => safeSend({ t: "theme", dark: scheme.matches }));
  addEventListener("pagehide", () => { if (last) safeSend({ t: "media_end" }); });

  chrome.runtime.onMessage.addListener((m) => {
    if (m.t !== "cmd") return;
    const el = media();
    switch (m.cmd) {
      case "play": el && el.play(); break;
      case "pause": el && el.pause(); break;
      case "toggle": el && (el.paused ? el.play() : el.pause()); break;
      case "next": {
        const b = visible(site.next);
        if (b) b.click(); else if (el && isFinite(el.duration)) el.currentTime = Math.min(el.duration, el.currentTime + 10);
        break;
      }
      case "prev": {
        const b = visible(site.prev);
        if (b && (!el || el.currentTime < 5)) b.click(); else if (el) el.currentTime = 0;
        break;
      }
    }
    setTimeout(() => report(true), 300);
  });
})();
