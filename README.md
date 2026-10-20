# AppBlocker

A cross-platform distraction blocker — block distracting apps and websites, with
Strict locks that can't be trivially switched off, an adult-content filter, and
optional account sign-in so your devices sync and can be controlled remotely.

## Downloads

| Platform | Download |
|----------|----------|
| **Android** (phone/tablet) | [AppBlocker.apk](../../releases/download/latest/AppBlocker.apk) · [release page](../../releases/tag/latest) |
| **Windows** (desktop) | [release page → download the .exe](../../releases/tag/windows-latest) |

Both are rebuilt automatically whenever the code changes, so the links always
point to the newest version.

### Installing on Android
Open the link on your phone → download `AppBlocker.apk` → tap it → if it says
"can't install / unknown app", tap **Settings → allow from this source** → back
→ **Install**. Play Protect may show a "scanned this app" notice (normal for any
app not from the Play Store) → **Install anyway**.

### Installing on Windows
Download the `AppBlocker-Setup-<version>.exe` from the Windows release page and
run it. Windows SmartScreen may warn because the installer isn't code-signed yet
→ **More info → Run anyway**.

## What's in this repo
- **`app/`, `gradle/`, `build.gradle`, …** — the Android app (Kotlin). Built by
  `.github/workflows/build.yml` into the `latest` release.
- **`desktop/`** — the Windows/macOS desktop app (Electron). Built by
  `.github/workflows/build-windows.yml` into the `windows-latest` release.
- **`ios/`** — the iOS app (SwiftUI + Family Controls). Source only — build it in
  Xcode on a Mac with an Apple Developer account (needs the Family Controls
  entitlement); no cloud build.
- **`phone-control/`** — the phone-control PWA (remote Strict-Mode control).

## Features
- Block apps and websites, with allow-lists and per-site exceptions.
- **One central Strict Mode** (Strict tab) that locks all your blockings,
  settings and permissions, with your chosen unlock method; the app can't be
  uninstalled while it's on.
- Adult content filter.
- Android anti-bypass hardening (split-screen, recent apps, device-admin,
  Settings tamper screens).
- Optional account sign-in with multi-device sync and remote "Strict on" /
  "Lock all" (shared Supabase backend).
