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
- Pairing shares a secret key through the QR code, never over the network. Every connection after that is
  encrypted (AES-256-GCM) and only works with that key. See [PROTOCOL.md](PROTOCOL.md) for the details.
- See the [privacy policy](site/privacy.html) for what each permission is used for.
