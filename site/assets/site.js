/* Mac n Droid site. Small, dependency-free. Everything works without it; this adds motion and smarts. */
(() => {
  const doc = document.documentElement;
  doc.classList.add('js');
  document.addEventListener('dragstart', (e) => { if (e.target.tagName === 'IMG') e.preventDefault(); });
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];

  /* nav: a hairline and blur once you scroll */
  const nav = $('#nav');
  if (nav) {
    const onScroll = () => nav.classList.toggle('scrolled', scrollY > 8);
    addEventListener('scroll', onScroll, { passive: true });
    onScroll();
  }

  /* reveal on scroll, once */
  const rv = $$('.rv');
  if (!reduce && 'IntersectionObserver' in window) {
    const io = new IntersectionObserver((es) => es.forEach((e) => {
      if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); }
    }), { rootMargin: '0px 0px -8% 0px', threshold: 0.08 });
    rv.forEach((el) => io.observe(el));
  } else rv.forEach((el) => el.classList.add('in'));

  /* soft spotlight that follows the pointer on cards */
  $$('.card').forEach((c) => c.addEventListener('pointermove', (e) => {
    const r = c.getBoundingClientRect();
    c.style.setProperty('--mx', `${e.clientX - r.left}px`);
    c.style.setProperty('--my', `${e.clientY - r.top}px`);
  }));


  /* ---------- long pages: contents that follow you on phones and tablets ---------- */
  const toc = $('.toc');
  if (toc) {
    const links = $$('a', toc);
    const m = document.createElement('details');
    m.className = 'toc-m';
    m.innerHTML = `<summary><span class="lbl">Jump to</span><b data-cur>${links[0] ? links[0].textContent : ''}</b><svg class="ic" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg></summary><ol>${links.map((a) => `<li><a href="${a.getAttribute('href')}">${a.textContent}</a></li>`).join('')}</ol>`;
    toc.parentElement.insertBefore(m, toc);
    const cur = $('[data-cur]', m);
    $$('a', m).forEach((a) => a.addEventListener('click', () => { m.open = false; }));
    document.addEventListener('click', (e) => { if (m.open && !m.contains(e.target)) m.open = false; });
    const all = [...links, ...$$('a', m)];
    const byId = (id) => all.filter((a) => a.getAttribute('href') === `#${id}`);
    if ('IntersectionObserver' in window) {
      const io = new IntersectionObserver((es) => es.forEach((e) => {
        if (!e.isIntersecting) return;
        all.forEach((l) => l.classList.remove('on'));
        byId(e.target.id).forEach((l) => l.classList.add('on'));
        const a = byId(e.target.id)[0];
        if (a) cur.textContent = a.textContent;
      }), { rootMargin: '-25% 0px -65% 0px' });
      $$('.prose section[id]').forEach((sec) => io.observe(sec));
    }
  }

  $$('[data-year]').forEach((el) => { el.textContent = new Date().getFullYear(); });

  /* ---------- downloads: suggest the right one, keep links on the latest release ---------- */
  // When the Play Store listing is live, put its link here; every Android button then opens Google Play.
  const PLAY_URL = '';
  const ua = navigator.userAgent;
  const plat = (navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || '';
  const touch = navigator.maxTouchPoints > 1;
  // Android tablets often ask for the desktop site and then report "Linux"; a touch screen gives them away.
  const isAndroid = /Android/i.test(ua) || /Android/i.test(plat) || (/Linux/i.test(plat) && !/X11|CrOS/i.test(ua) && touch);
  const isIOS = /iPhone|iPad|iPod/i.test(ua) || (/Mac/i.test(plat) && touch);
  const os = isAndroid ? 'android' : isIOS ? 'ios' : /Win/i.test(plat || ua) ? 'windows' : /Linux|X11|CrOS/i.test(plat || ua) ? 'linux' : 'mac';
  doc.dataset.os = os;
  // On any phone or tablet the first thing to get is the Android app.
  const label = { mac: 'Download for Mac', android: 'Install for Android', windows: 'Download for Windows', linux: 'Download for Linux', ios: 'Install for Android' }[os];
  $$('[data-primary-label]').forEach((el) => { el.textContent = label; });
  if (os === 'ios') $$('[data-nav-dl]').forEach((el) => { el.textContent = 'Install'; });
  if (os === 'android') {
    $$('[data-nav-dl]').forEach((el) => { el.textContent = 'Install'; });
    $$('.dl[data-os="android"] [data-asset="apk"]').forEach((el) => { el.lastChild.textContent = 'Install for Android'; });
  }
  if (PLAY_URL) {
    $$('[data-asset="apk"]').forEach((el) => { el.href = PLAY_URL; el.removeAttribute('data-asset'); el.lastChild.textContent = 'Get it on Google Play'; });
  }
  const you = os === 'ios' ? null : $(`.dl[data-os="${os}"]`);
  if (you) you.classList.add('is-you');

  const fmt = (b) => (b > 1e6 ? `${(b / 1e6).toFixed(0)} MB` : `${Math.round(b / 1e3)} kB`);
  const pick = {
    dmg: /\.dmg$/i, apk: /\.apk$/i,
    'win-x64': /Windows-x64\.exe$/i, 'win-arm64': /Windows-arm64\.exe$/i,
    'linux-x64': /Linux-x86_64\.flatpak$/i, 'linux-arm64': /Linux-aarch64\.flatpak$/i,
  };
  const applyRelease = (rel) => {
    if (!rel || !rel.assets) return;
    const v = (rel.tag_name || '').replace(/^v/, '');
    if (v) $$('[data-version]').forEach((el) => { el.textContent = v; });
    for (const [key, re] of Object.entries(pick)) {
      const a = rel.assets.find((x) => re.test(x.name));
      if (!a) continue;
      $$(`[data-asset="${key}"]`).forEach((el) => { el.href = a.browser_download_url; });
      $$(`[data-size="${key}"]`).forEach((el) => { el.textContent = `${fmt(a.size)} · v${v}`; });
    }
  };
  const KEY = 'mnd-release';
  try {
    const c = JSON.parse(sessionStorage.getItem(KEY) || 'null');
    if (c) applyRelease(c);
    else fetch('https://api.github.com/repos/design-n-build/mac-n-droid/releases/latest', { headers: { Accept: 'application/vnd.github+json' } })
      .then((r) => (r.ok ? r.json() : null))
      .then((rel) => {
        if (!rel) return;
        const slim = { tag_name: rel.tag_name, assets: rel.assets.map(({ name, size, browser_download_url }) => ({ name, size, browser_download_url })) };
        try { sessionStorage.setItem(KEY, JSON.stringify(slim)); } catch (_) {}
        applyRelease(slim);
      }).catch(() => {});
  } catch (_) {}

  // the hero button goes straight to the file for the visitor's device
  const direct = { mac: 'dmg', android: 'apk', windows: 'win-x64', linux: 'linux-x64' }[os];
  if (direct) $$('[data-primary-dl]').forEach((btn) => btn.addEventListener('click', (e) => {
    const a = os === 'android' && PLAY_URL ? { href: PLAY_URL } : $(`[data-asset="${direct}"]`);
    if (!a) return;
    e.preventDefault();
    location.href = a.href;
    const sec = $('#download');
    if (sec) setTimeout(() => sec.scrollIntoView({ behavior: reduce ? 'auto' : 'smooth' }), 120);
  }));

  /* ---------- the hero: a phone and a Mac, acting out each feature ---------- */
  const bridge = $('#bridge');
  const stage = $('#stage');
  if (!bridge || !stage) return;
  const tabs = $$('.tab', stage);
  const caption = $('#caption');
  // Each scene is a timeline: at each time (ms) the next step class (s1, s2, …) is added; step "go" sends the pulse.
  const scenes = {
    notifications: { dir: 'p2m', dur: 6200, steps: [300, 1100, 1900, 5200], go: 2,
      text: '<b>Phone → Mac.</b> A message arrives on your phone, and drops from your Mac’s notch with Reply right there.' },
    messages: { dir: 'm2p', dur: 6000, steps: [300, 1900, 2800], go: 2,
      text: '<b>Mac → phone.</b> Type on your Mac. Your phone sends the text, from your number.' },
    clipboard: { dir: 'p2m', dur: 7000, steps: [400, 1500, 2000, 2900, 3200, 4900], go: 3,
      text: '<b>Phone → Mac.</b> Copy on your phone, press ⌘V on your Mac.' },
    files: { dir: 'm2p', dur: 7200, steps: [500, 750, 1900, 2400, 2600, 3300, 3900, 4600], go: 5,
      text: '<b>Mac → phone.</b> Drag a file to the notch and drop it. It lands in your phone’s Downloads.' },
    music: { dir: 'p2m', dur: 6200, steps: [300, 1700, 2000, 2800], go: 3,
      text: '<b>Phone → Mac.</b> Play, pause and skip your Mac’s music from your phone.' },
    links: { dir: 'p2m', dur: 7000, steps: [300, 1900, 2600, 3500], go: 3,
      text: '<b>Phone → Mac.</b> Share a page with Open on Mac, and it opens in your Mac’s browser.' },
  };
  const order = Object.keys(scenes);
  let idx = 0, t = 0, last = 0, done = 0, auto = !reduce, visible = true;

  const setSteps = (n) => {
    for (let k = 1; k <= 8; k++) bridge.classList.toggle(`s${k}`, k <= n);
  };
  const show = (i, instant) => {
    idx = i; t = 0;
    const name = order[i], s = scenes[name];
    tabs.forEach((b, j) => { b.setAttribute('aria-selected', j === i); b.tabIndex = j === i ? 0 : -1; });
    bridge.classList.remove('go');
    bridge.dataset.scene = name;
    bridge.dataset.dir = s.dir;
    // Reduced motion shows each scene's end state; the message scene stops before its notification hides.
    done = instant ? s.steps.length - (name === 'notifications' ? 1 : 0) : 0;
    setSteps(done);
    if (caption) caption.innerHTML = s.text;
    const bar = stage.querySelector('.tabs');
    // centre the chosen tab inside the tab strip only; never scroll the page
    if (bar.scrollWidth > bar.clientWidth) bar.scrollTo({ left: tabs[i].offsetLeft - (bar.clientWidth - tabs[i].offsetWidth) / 2, behavior: reduce ? 'auto' : 'smooth' });
  };
  const step = () => {
    const s = scenes[order[idx]];
    while (done < s.steps.length && t >= s.steps[done]) {
      done++;
      setSteps(done);
      if (done === s.go) { bridge.classList.remove('go'); void bridge.offsetWidth; bridge.classList.add('go'); }
    }
  };
  const loop = (now) => {
    requestAnimationFrame(loop);
    const dt = last ? Math.min(now - last, 100) : 0; last = now;
    if (!visible || document.hidden) return;
    t += dt;
    step();
    const s = scenes[order[idx]];
    if (auto) {
      const bar = tabs[idx] && tabs[idx].querySelector('.prog');
      if (bar) bar.style.setProperty('--p', Math.min(t / s.dur, 1));
      if (t >= s.dur) show((idx + 1) % order.length);
    }
  };

  tabs.forEach((b, i) => {
    b.addEventListener('click', () => { show(i, reduce); });
    b.addEventListener('keydown', (e) => {
      const d = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1 : 0;
      if (!d) return;
      e.preventDefault();
      const n = (i + d + tabs.length) % tabs.length;
      tabs[n].focus(); tabs[n].click();
    });
  });
  bridge.addEventListener('dragstart', (e) => e.preventDefault());

  if (reduce) { show(0, true); return; }
  stage.classList.add('autoplay');
  show(0);
  if ('IntersectionObserver' in window) {
    new IntersectionObserver(([e]) => { visible = e.isIntersecting; last = 0; }, { threshold: 0.2 }).observe(bridge);
  }
  document.addEventListener('visibilitychange', () => { last = 0; });
  requestAnimationFrame(loop);
})();
