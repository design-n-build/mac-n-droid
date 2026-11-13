# Help test Mac n Droid for Windows and Linux

Thanks for trying the beta! Mac n Droid links your Android phone to your computer over your own Wi-Fi:
notifications, texts, your phone's screen, music, clipboard, files and links. No account, nothing goes through
the internet, and it's free.

The Windows and Linux apps are new, so you may run into a few bugs; we're working on them. Please
[report anything that doesn't work](https://github.com/santosh-mudragada-1/mac-n-droid/issues/new?template=bug_report.yml) — that's exactly what this beta is for.

## 1. Install on your phone
Android 10 or later. Download **MacNDroid-0.13.1.apk** from the release page (the link you were sent), open it
and allow **Install unknown apps** when Android asks. Open Mac n Droid and allow notifications.
If it says **"App blocked to protect your device"**: open the **Play Store** → your profile picture → **Play Protect**
→ ⚙ → turn off **Scan apps with Play Protect**, install Mac n Droid, then turn it back on.

## 2. Install on your computer
From the same release page:

- **Windows 10 or 11:** `MacNDroid-0.13.1-Windows-x64.exe` (most PCs) or `-arm64` (ARM laptops such as Surface
  Pro X or Copilot+ PCs). Windows says "Windows protected your PC" because the app isn't signed yet: click
  **More info → Run anyway**. When Windows Firewall asks, allow **Private networks**.
- **Linux:** `MacNDroid-0.13.1-Linux-x86_64.flatpak` (or `-aarch64` on ARM). In a terminal:
  `flatpak install --user MacNDroid-0.13.1-Linux-x86_64.flatpak`, then open Mac n Droid from your apps.
  On Fedora, also allow the port: `sudo firewall-cmd --add-port=47800/tcp --permanent && sudo firewall-cmd --reload`.

## 3. Pair
Phone and computer on the **same Wi-Fi**. On the computer, open **Pair a phone**. On the phone, tap
**Scan QR code** and point it at the code.

## 4. Try these (a few minutes each)
- [ ] **Notifications:** get a message on the phone. It shows on the computer; reply from there.
- [ ] **Calls:** have someone call you. Answer or decline from the computer.
- [ ] **Messages:** open Messages on the computer, read a conversation, send a text, start a new one.
- [ ] **Phone screen:** click **Show phone screen**, tap **Start** on the phone. Click, drag, scroll and type.
      (To control the phone, it asks you to turn on *Mac n Droid clipboard and controls* once.)
- [ ] **Music:** play something on the phone and control it from the computer. Play something on the computer
      (Spotify, YouTube…) and control it from the phone's notification.
- [ ] **Clipboard:** copy text on one, paste on the other. Try an image too.
- [ ] **Files and folders:** drag a file and a folder onto the window. Send a photo from the phone with
      **Share → Send to…**. They land in Downloads.
- [ ] **Links:** on the phone, **Share → Open on…** a web page. On the computer, use **Open link on phone**.
- [ ] **Ring your phone** from the Overview.
- [ ] **Close the window:** the app keeps running (Windows: the icon next to the clock).
- [ ] **Browser extension** (optional): see the release page for how to add it to Chrome, Edge or Firefox.
      Right-click a link → **Open Link on Your Phone**.
- [ ] **Left behind** (laptops): take the laptop to another Wi-Fi network without the phone.

## 5. Tell us what happened
Anything that didn't work, looked odd, or was confusing: open an issue at
https://github.com/santosh-mudragada-1/mac-n-droid/issues or email **help.macndroid@gmail.com**. Please include
Windows or Linux (which version), your phone model, what you did and what happened. A screenshot helps.

---

## For the developer: testing without other computers
A free app called **UTM** (mac.getutm.app) runs Windows 11 ARM and Ubuntu ARM in a window on a Mac. In the
virtual machine's settings, set **Network Mode: Bridged (Advanced)**, interface **en0**, so the phone can reach
it like a real PC. `.github/workflows/desktop.yml` also tests both apps end to end with a fake phone
(`tools/fake_phone.py`) on every release tag.
