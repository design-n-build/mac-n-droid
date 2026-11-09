// Talks to the Mac n Droid app on this Mac (ws://127.0.0.1:47809, never the internet).
const URL_ = "ws://127.0.0.1:47809/";
let ws = null;
let open = false;
let status = { app: false, connected: false, phone: "" };
const tabs = new Map();      // tabId → last media state
const artSent = new Map();   // tabId → art URL already sent

function send(m) {
  if (open) { try { ws.send(JSON.stringify(m)); } catch (e) { /* closed */ } }
}

// Connects only when there's a reason to (something playing, the popup, a menu click) and waits longer
// after each failure, so a Mac n Droid app that isn't running doesn't fill the browser's error list:
// the browser logs every refused connection, even though it's handled here.
let failures = 0;
let lastTry = 0;
const waiters = [];

function backoff() { return Math.min(5 * 60_000, 15_000 * 2 ** Math.max(0, failures - 1)); }

function connect(force = false) {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
  if (!force && failures > 0 && Date.now() - lastTry < backoff()) return;
  lastTry = Date.now();
  try { ws = new WebSocket(URL_); } catch (e) { return; }
  ws.onopen = () => {
    open = true;
    failures = 0;
    status.app = true;
    artSent.clear();
    for (const [tab, s] of tabs) forwardMedia(tab, s);
  };
  ws.onmessage = (e) => {
    let m;
    try { m = JSON.parse(e.data); } catch (_) { return; }
    if (m.t === "status") {
      status = { app: true, connected: !!m.connected, phone: m.phone || "" };
      updateMenus();
      while (waiters.length) waiters.shift()();
    } else if (m.t === "cmd" && typeof m.tab === "number") {
      chrome.tabs.sendMessage(m.tab, { t: "cmd", cmd: m.cmd }).catch(() => {});
    }
  };
  ws.onclose = () => {
    if (!open) failures++;
    open = false;
    status = { app: false, connected: false, phone: "" };
    updateMenus();
    while (waiters.length) waiters.shift()();
  };
  ws.onerror = () => {};
}

/** Connects now (for the popup or a menu click) and waits briefly for the app's status. */
function connectNow() {
  if (open) return Promise.resolve();
  return new Promise((resolve) => {
    waiters.push(resolve);
    setTimeout(resolve, 1500);
    connect(true);
  });
}

const anythingPlaying = () => [...tabs.values()].some((s) => s.playing);

// While connected: keeps the connection (and this worker) alive. Otherwise retries only while
// something is playing, so the phone picks it up once the app starts.
setInterval(() => { if (open) send({ t: "ping" }); else if (anythingPlaying()) connect(); }, 20000);
chrome.alarms.create("reconnect", { periodInMinutes: 1 });
chrome.alarms.onAlarm.addListener(() => { if (anythingPlaying()) connect(); });
chrome.runtime.onStartup.addListener(() => attachToOpenTabs());
chrome.runtime.onInstalled.addListener(() => { setUpMenus(); attachToOpenTabs(); });

// Browsers only add content scripts to pages loaded after the extension: add it to tabs already open
// (a YouTube tab playing when the extension was installed or updated).
async function attachToOpenTabs() {
  const all = await chrome.tabs.query({ url: ["http://*/*", "https://*/*"] });
  for (const t of all) {
    chrome.scripting.executeScript({ target: { tabId: t.id, allFrames: false }, files: ["content.js"] }).catch(() => {});
  }
}
connect();

async function artData(url) {
  try {
    const r = await fetch(url);
    if (!r.ok) return "";
    const buf = new Uint8Array(await r.arrayBuffer());
    if (buf.length > 2_000_000) return "";
    let s = "";
    for (let i = 0; i < buf.length; i += 0x8000) s += String.fromCharCode.apply(null, buf.subarray(i, i + 0x8000));
    return btoa(s);
  } catch (e) { return ""; }
}

async function forwardMedia(tab, s) {
  const m = { t: "media", tab, site: s.site, title: s.title, artist: s.artist, album: s.album,
              playing: s.playing, dur: s.dur, pos: s.pos, canNext: s.canNext, canPrev: s.canPrev };
  if (s.art && artSent.get(tab) !== s.art) {
    artSent.set(tab, s.art);
    const data = await artData(s.art);
    if (data) m.art = data;
  }
  send(m);
}

chrome.runtime.onMessage.addListener((m, sender, reply) => {
  const tab = sender.tab && sender.tab.id;
  if (m.t === "media" && tab != null) {
    tabs.set(tab, m);
    if (m.playing) connect();
    forwardMedia(tab, m);
  } else if (m.t === "media_end" && tab != null) {
    tabs.delete(tab);
    artSent.delete(tab);
    send({ t: "media_end", tab });
  } else if (m.t === "status") {
    connectNow().then(() => {
      const playing = [...tabs.values()].sort((a, b) => (b.playing ? 1 : 0) - (a.playing ? 1 : 0))[0];
      reply({ ...status, media: playing ? { title: playing.title, site: playing.site, playing: playing.playing } : null });
    });
    return true;
  } else if (m.t === "send_url") {
    sendUrl(m.url).then(reply);
    return true;
  }
});

chrome.tabs.onRemoved.addListener((tab) => {
  if (tabs.delete(tab)) send({ t: "media_end", tab });
  artSent.delete(tab);
});

async function sendUrl(url) {
  if (!/^https?:\/\//.test(url || "")) return { ok: false, error: "Only web pages can be opened on your phone." };
  await connectNow();
  if (!open) return { ok: false, error: "Open the Mac n Droid app first." };
  if (!status.connected) return { ok: false, error: "Your phone isn't connected." };
  send({ t: "send_url", url });
  return { ok: true };
}

// Right-click → "Open Link on Your Phone" / "Open Page on Your Phone".
function setUpMenus() {
  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({ id: "link", title: "Open Link on Your Phone", contexts: ["link"] });
    chrome.contextMenus.create({ id: "page", title: "Open Page on Your Phone", contexts: ["page"] });
    updateMenus();
  });
}

function updateMenus() {
  // Always clickable: the click connects first, so the menu works even before anything has played.
}

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  const r = await sendUrl(info.menuItemId === "link" ? info.linkUrl : (tab && tab.url));
  // A short "!" on the toolbar icon when it didn't work (app closed or phone not connected).
  chrome.action.setBadgeBackgroundColor({ color: r.ok ? "#0DA457" : "#B3261E" });
  chrome.action.setBadgeText({ text: r.ok ? "✓" : "!" });
  chrome.action.setTitle({ title: r.ok ? "Mac n Droid" : r.error });
  setTimeout(() => { chrome.action.setBadgeText({ text: "" }); chrome.action.setTitle({ title: "Mac n Droid" }); }, 3000);
});
