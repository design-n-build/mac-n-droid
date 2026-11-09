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

function connect() {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
  try { ws = new WebSocket(URL_); } catch (e) { return; }
  ws.onopen = () => {
    open = true;
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
    } else if (m.t === "cmd" && typeof m.tab === "number") {
      chrome.tabs.sendMessage(m.tab, { t: "cmd", cmd: m.cmd }).catch(() => {});
    }
  };
  ws.onclose = () => {
    open = false;
    status = { app: false, connected: false, phone: "" };
    updateMenus();
  };
  ws.onerror = () => {};
}

// Keeps the connection (and this worker) alive, and reconnects once the app is running.
setInterval(() => { if (open) send({ t: "ping" }); else connect(); }, 20000);
chrome.alarms.create("reconnect", { periodInMinutes: 1 });
chrome.alarms.onAlarm.addListener(() => connect());
chrome.runtime.onStartup.addListener(() => { connect(); attachToOpenTabs(); });
chrome.runtime.onInstalled.addListener(() => { setUpMenus(); connect(); attachToOpenTabs(); });

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
    connect();
    forwardMedia(tab, m);
  } else if (m.t === "media_end" && tab != null) {
    tabs.delete(tab);
    artSent.delete(tab);
    send({ t: "media_end", tab });
  } else if (m.t === "status") {
    connect();
    const playing = [...tabs.values()].sort((a, b) => (b.playing ? 1 : 0) - (a.playing ? 1 : 0))[0];
    reply({ ...status, media: playing ? { title: playing.title, site: playing.site, playing: playing.playing } : null });
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
  const enabled = status.connected;
  chrome.contextMenus.update("link", { enabled }, () => void chrome.runtime.lastError);
  chrome.contextMenus.update("page", { enabled }, () => void chrome.runtime.lastError);
}

chrome.contextMenus.onClicked.addListener((info, tab) => {
  sendUrl(info.menuItemId === "link" ? info.linkUrl : (tab && tab.url));
});
