# Security

Mac n Droid can read notifications and texts, and can control the phone when you allow it, so security reports
matter a lot to us.

## Reporting a problem
Please report it privately, not in a public issue:

- **[Report a vulnerability](https://github.com/santosh-mudragada-1/mac-n-droid/security/advisories/new)** on GitHub (preferred), or
- email **help.macndroid@gmail.com** with "Security" in the subject.

Include what you found, the steps to reproduce it, and the app versions. You'll get a reply within a week. Once
it's fixed, we'll credit you in the release notes if you'd like.

## Supported versions
Only the latest release gets security fixes, so please stay on the newest version. The Mac app updates itself.

## How your data is protected
- The phone and the computer talk directly over your own Wi-Fi. Nothing goes through a server, and there's no account.
- Pairing shares a secret key through the QR code, never over the network. The code works once: the paired
  phone then gets its own key, and the computer shows a new code. A different phone has to be allowed on the
  computer or on the paired phone.
- Every connection is encrypted (AES-256-GCM) with a key that also comes from a one-time key exchange (P-256),
  so a leaked key can't decrypt traffic recorded earlier. See [PROTOCOL.md](PROTOCOL.md) for the details.
- Received files are marked as downloaded, so macOS and Windows check them before they first run.
- See the [privacy policy](site/privacy.html) for what each permission is used for.
