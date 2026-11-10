# Testing the Windows and Linux apps on a Mac

You don't need a Windows PC or a Linux computer. A free app called **UTM** runs both as "virtual machines",
in a window on your Mac.

## 1. Install UTM
Download it free from **mac.getutm.app** and drag it to Applications.

## 2. Windows 11
1. Download the **Windows 11 ARM64** disk image (ISO) from Microsoft: search "Download Windows 11 Arm64".
2. In UTM: **Create a New Virtual Machine → Virtualize → Windows**, choose the ISO, and give it at least
   4 GB of memory and 64 GB of disk.
3. Install Windows. You can skip the product key ("I don't have a product key"); unactivated Windows is fine
   for testing.
4. Install Mac n Droid with the **arm64** installer.

## 3. Ubuntu Linux
1. Download **Ubuntu 24.04 Desktop for ARM** from ubuntu.com.
2. In UTM: **Create a New Virtual Machine → Virtualize → Linux**, choose the ISO, 4 GB memory, 25 GB disk.
3. After installing Ubuntu, open Terminal in the `linux` folder and follow "From source" in
   [linux/README.md](../linux/README.md).

## 4. Let your phone reach the virtual machine (important)
By default the virtual machine is hidden behind your Mac, so the phone can't connect. In UTM, open the
machine's settings → **Network** → **Network Mode: Bridged (Advanced)**, interface **en0** (Wi-Fi). Restart
the machine. It now gets its own address on your Wi-Fi, like a real PC.

## 5. What to try
- Pair your phone by scanning the code on the **Pair a phone** page.
- Notifications: get a message on the phone; reply from the computer's notification.
- Clipboard: copy on the phone, paste on the computer, and the other way.
- Files: drop a file on the window; send a photo from the phone with **Share → Send to…**.
- Links: share a link from the phone with **Open on…**; use **Open link on phone** on the computer.
- Close the window: the app should keep running (Windows: icon next to the clock).

The automatic builds on GitHub also test both apps with a fake phone every time they change.

## Status
On hold (November 2026) while Mac, Android and the browser extension launch. This work lives on the
`windows-linux` branch. To continue: `git checkout windows-linux`, then `git merge main` to bring in
everything that changed since.

Left to do before a launch:
- Android: say "PC" or "computer" instead of "Mac" when paired with Windows or Linux (the apps send `os` in
  their hello), and hide features those apps don't have yet.
- Messages, music controls, screen mirroring and the browser-extension link on Windows and Linux.
- Test on real Windows and Linux (UTM, above), then code-sign the Windows installer.
