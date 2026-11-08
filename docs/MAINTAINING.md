# Maintaining Mac n Droid

A plain-language guide for running this project on GitHub. You don't need to write code to do any of
this. When something needs code, ask Claude in this folder.

## The words you'll see

| Word | What it means | Design-world equivalent |
|---|---|---|
| **Repository (repo)** | The project folder on GitHub, with its full history. | The Figma file |
| **Commit** | A saved snapshot of changes, with a short message saying what changed. | A named version in version history |
| **Push** | Upload your new commits from this Mac to GitHub. | Syncing to the cloud |
| **Pull** | Download commits from GitHub to this Mac. | Getting the latest version |
| **Branch** | A parallel copy for trying a change without touching the main version (`main`). | Duplicating a page to explore |
| **Issue** | A bug report, idea or question. Each has its own comment thread. | A comment thread / ticket |
| **Pull request (PR)** | Someone proposes changes to your code and asks you to accept ("merge") them. | A design review before handoff |
| **Fork** | Someone's own copy of your repo, which they change and then send you a PR from. | Someone duplicating your community file |
| **Release** | A numbered version with downloadable files (the DMG and APK). | A published version |
| **Star** | Someone bookmarked the project. A popularity signal. | Likes |

## How we work day to day

1. You describe what you want to Claude (a feature, a bug, a design change).
2. Claude changes the code, builds, and installs it so you can test on your phone and Mac.
3. When you're happy, say **"commit and push"**. Claude saves a snapshot with a clear message and
   uploads it to GitHub.
4. When a version is ready for users, say **"make a release"**. Claude bumps the version numbers,
   builds the DMG and APK, and publishes them as a GitHub Release. Users download from the
   Releases page.

You'll never lose work: every commit can be looked at or restored later.

## When you get GitHub notifications

You'll get emails and notifications at github.com/notifications (bell icon, top right).

**New issue (bug report)**
1. Read it. Can you reproduce it on your own phone and Mac?
2. Reply kindly, even if it's just "Thanks, I can see this too, I'll look into it."
3. Add a label on the right: `bug`, `enhancement`, `question`, `duplicate`, `wontfix`.
4. To fix it, tell Claude: *"Fix issue #12"* (Claude can read issues with the `gh` tool).
   Mention the issue in the commit ("Fixes #12") and GitHub closes it automatically when that commit is pushed.

**New issue (feature idea)**
- Say thanks, and say honestly whether it fits the app: native design, local only, no accounts.
- It's fine to close ideas that don't fit, with a short reason. Close with "not planned".

**Pull request (someone sent code)**
- **Never merge a PR you don't understand.** Ask Claude: *"Review PR #5 and tell me in plain words
  what it changes and whether it's safe."*
- Be especially careful with anything that sends data to the internet, touches permissions or
  signing, or adds new libraries.
- If it's good, Claude can merge it, or you can click **Merge pull request** on GitHub.

**Security report** (arrives privately under the *Security* tab)
- Don't discuss it publicly. Ask Claude to look at it right away.

**Someone is rude or spamming**
- You can hide comments, lock a conversation, or block the user (… menu on the comment).

## Rules that keep you safe

- **The Android signing key never goes on GitHub.** `android/keystore/` and
  `android/keystore.properties` are excluded automatically. **Back them up** somewhere private
  (a USB drive or a password manager). If you lose them, users can't install updates over the old
  app. If someone steals them, they could publish fake updates as you.
- Don't paste passwords, tokens or personal data into issues, commits or code.
- Your commits use a private GitHub email address, so your real email isn't shown publicly.

## Free now, paid features later?

The code is licensed under **GPL-3.0**: anyone can use and change it, but anything they share must
stay open source too. As the owner you can still sell the app or add paid features. Two things to know:

- Other people's code contributions are also GPL. If you might ever want **closed-source** paid
  features, decide that before accepting big outside contributions. You can ask contributors to
  sign a simple agreement (a "CLA") first. Ask Claude to set one up when that time comes.
- Paid extras on the Play Store or Mac App Store can live in a separate, private repo.

## Making the repo public

The repo starts **private**. When you're ready: GitHub → the repo → **Settings** → scroll to the
bottom → **Change visibility** → **Public**. You can't hide the history again afterwards, so check
you're happy with what's there.

## Handy things to ask Claude

- "What's new on GitHub?" (issues, PRs and comments since last time)
- "Fix issue #8 and push"
- "Reply to issue #8 saying it's fixed in the next release"
- "Make a release"
- "Review PR #3 in plain words"
- "Undo the last commit"
