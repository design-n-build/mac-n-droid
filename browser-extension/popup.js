chrome.runtime.sendMessage({ t: "theme", dark: matchMedia("(prefers-color-scheme: dark)").matches }).catch?.(() => {});
const $ = (id) => document.getElementById(id);

const DOWNLOAD = "https://design-n-build.github.io/mac-n-droid/#download";
let getApp = false;

chrome.runtime.sendMessage({ t: "status" }, (s) => {
  s = s || {};
  if (!s.app) {
    // The app isn't running, or isn't installed yet: say where to get it.
    getApp = true;
    $("status").textContent = "The app isn't running";
    $("send").textContent = "Get the Mac n Droid App";
    $("send").disabled = false;
    $("note").textContent = "Open Mac n Droid on this computer. Don't have it yet? It's free for Mac, Windows and Linux.";
  } else if (!s.connected) {
    $("status").textContent = "Phone not connected";
  } else {
    $("phone").textContent = s.phone || "Phone";
    $("status").textContent = "Connected";
    $("send").disabled = false;
  }
  // What the phone can control right now: makes it obvious when a tab isn't being picked up.
  $("media").textContent = s.media
    ? `${s.media.playing ? "Playing" : "Paused"}: ${s.media.title} · ${s.media.site}`
    : "Nothing playing in this browser. If a video is playing, reload its tab once.";
});

$("send").addEventListener("click", async () => {
  if (getApp) {
    chrome.tabs.create({ url: DOWNLOAD });
    window.close();
    return;
  }
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  $("send").disabled = true;
  chrome.runtime.sendMessage({ t: "send_url", url: tab && tab.url }, (r) => {
    if (r && r.ok) {
      $("send").textContent = "Opened on your phone ✓";
      setTimeout(() => window.close(), 700);
    } else {
      $("send").disabled = false;
      $("note").textContent = (r && r.error) || "Couldn't open this page on your phone.";
    }
  });
});
