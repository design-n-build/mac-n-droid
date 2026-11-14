#!/usr/bin/env python3
"""Builds site/whats-new.html from CHANGELOG.md, using the page shell (header, footer) of site/privacy.html.

    python3 tools/site/build-whats-new.py

The GitHub Pages workflow runs this before publishing, so the page always matches CHANGELOG.md.
"""
import html
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
changelog = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
shell = (ROOT / "site/privacy.html").read_text(encoding="utf-8")
BASE = "https://design-n-build.github.io/mac-n-droid/"


def inline(text: str) -> str:
    """The little Markdown CHANGELOG.md uses: **bold**, `code`, [links](url)."""
    t = html.escape(text, quote=False)
    t = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", lambda m: f'<a href="{html.escape(m.group(2))}">{m.group(1)}</a>', t)
    t = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", t)
    t = re.sub(r"`([^`]+)`", r"<code>\1</code>", t)
    return t


# Parse "## 0.14.0 (Mac, Android…)" sections and their "- " bullets (continuation lines are indented).
releases = []
for block in re.split(r"(?m)^## ", changelog)[1:]:
    head, _, body = block.partition("\n")
    m = re.match(r"(\S+)\s*(?:\((.*)\))?\s*(.*)", head.strip())
    version, platforms, rest = m.group(1), (m.group(2) or ""), m.group(3)
    if rest:  # e.g. "0.8.2 (Mac) · 0.8.5 (Android)"
        platforms = (platforms + " " + rest).strip()
    items, cur = [], None
    for line in body.splitlines():
        if line.startswith("- "):
            cur = line[2:].strip()
            items.append(cur)
        elif line.strip() and items and line.startswith(" "):
            items[-1] += " " + line.strip()
        elif line.strip() and not line.startswith("#"):
            items.append(line.strip())
    releases.append((version, platforms, items))


def tags(platforms: str) -> str:
    out = []
    p = platforms.lower()
    if "mac" in p: out.append('<span class="tag mac">Mac</span>')
    if "android" in p: out.append('<span class="tag droid">Android</span>')
    if "extension" in p: out.append('<span class="tag">Browser extension</span>')
    if "windows" in p: out.append('<span class="tag">Windows (beta)</span>')
    if "linux" in p: out.append('<span class="tag">Linux (beta)</span>')
    return "".join(out)


rows = []
for i, (version, platforms, items) in enumerate(releases):
    lis = "\n".join(f"          <li>{inline(x)}</li>" for x in items)
    latest = '<span class="latest">Latest</span>' if i == 0 else ""
    rows.append(f"""      <section class="rel rv" id="v{version}" aria-labelledby="h{version}">
        <div class="rel-head">
          <h2 id="h{version}"><a href="#v{version}">{html.escape(version)}</a>{latest}</h2>
          <a class="rel-link" href="https://github.com/design-n-build/mac-n-droid/releases/tag/v{version.split('·')[0].strip()}">Downloads</a>
        </div>
        <div class="rel-tags">{tags(platforms)}</div>
        <ul class="rel-list">
{lis}
        </ul>
      </section>""")

latest_v = releases[0][0] if releases else ""
main = f"""<main id="main">
<div class="wrap">
  <header class="doc-hero">
    <p class="eyebrow"><span class="duo" aria-hidden="true"><i></i><i></i></span>What's new</p>
    <h1>Every update, in plain words.</h1>
    <p class="meta">The latest version is <b>{html.escape(latest_v)}</b>. The Mac app updates itself; on Android, install the new version over the old one. All downloads are on <a href="https://github.com/design-n-build/mac-n-droid/releases">GitHub Releases</a>.</p>
  </header>
  <div class="releases">
{chr(10).join(rows)}
  </div>
</div>
</main>"""

page = shell[: shell.index('<main id="main">')] + main + shell[shell.index("</main>") + len("</main>"):]
# Page-specific head: title, description, canonical, social tags.
page = re.sub(r"<title>.*?</title>", "<title>What's new · Mac n Droid</title>", page)
page = re.sub(r'(<meta name="description" content=")[^"]*', r"\1What's new in each version of Mac n Droid, the free app that connects your Android phone to your Mac: new features, fixes and improvements.", page)
page = page.replace(f'{BASE}privacy.html', f'{BASE}whats-new.html')
page = re.sub(r'(<meta property="og:title" content=")[^"]*', r"\1What's new in Mac n Droid", page)
page = re.sub(r'(<meta property="og:description" content=")[^"]*', r"\1Every Mac n Droid update, in plain words.", page)
page = re.sub(r'<script type="application/ld\+json">.*?</script>', f'''<script type="application/ld+json">
{{"@context":"https://schema.org","@type":"WebPage","name":"What's new in Mac n Droid","url":"{BASE}whats-new.html",
 "breadcrumb":{{"@type":"BreadcrumbList","itemListElement":[{{"@type":"ListItem","position":1,"name":"Mac n Droid","item":"{BASE}"}},{{"@type":"ListItem","position":2,"name":"What's new","item":"{BASE}whats-new.html"}}]}}}}
</script>''', page, flags=re.S)
# The privacy page's table-of-contents script isn't needed here.
page = re.sub(r"<script>\s*// highlight the section.*?</script>\n", "", page, flags=re.S)
page = page.replace('<a href="privacy.html" aria-current="page">', '<a href="privacy.html">')
page = page.replace('<li><a href="https://github.com/design-n-build/mac-n-droid/blob/main/CHANGELOG.md">What\'s new</a></li>',
                    '<li><a href="whats-new.html" aria-current="page">What\'s new</a></li>')
(ROOT / "site/whats-new.html").write_text(page, encoding="utf-8")
print(f"site/whats-new.html: {len(releases)} versions")
