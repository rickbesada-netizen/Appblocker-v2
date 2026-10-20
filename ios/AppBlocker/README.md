# AppBlocker for iOS (Screen Time / Family Controls)

A ground-up SwiftUI rewrite of the Android AppBlocker, built on Apple's
**Family Controls / Screen Time** frameworks — the only sanctioned way to block
apps and websites on iPhone.

## What you need
- A **Mac with Xcode 15+** (iOS apps cannot be built on Windows).
- A paid **Apple Developer account** ($99/year).
- Apple must grant the **Family Controls entitlement**. Request it at
  https://developer.apple.com/contact/request/family-controls-distribution
  (development works with the capability enabled in Xcode; App Store
  distribution needs the granted entitlement).

## Open the project
1. Install XcodeGen: `brew install xcodegen`
2. In this folder: `xcodegen generate`
3. Open `AppBlocker.xcodeproj`.
4. Replace `com.yourcompany` and `group.com.yourcompany.appblocker` everywhere
   (project.yml + the 4 `.entitlements` files) with your own bundle prefix and
   App Group.
5. In Signing & Capabilities for **all four targets**: set your Team, and add
   the **App Groups** capability with the same group id. On the main app add the
   **Family Controls** capability.
6. Run on a **real device** (Screen Time APIs don't work in the Simulator).

## How it maps to the Android app
| Android feature | iOS implementation |
|---|---|
| Block lists of apps | `FamilyActivityPicker` + `ManagedSettings` shields |
| App categories | Category tokens (Apple-provided groupings) |
| Website blocking (Safari) | Web domain tokens + shields |
| Adult content filter | Apple's built-in `.auto()` web filter |
| Schedules (days/times) | `DeviceActivitySchedule` + monitor extension |
| Daily usage limits | `DeviceActivityEvent` thresholds |
| Block screen | `ShieldConfiguration` (icon/title/subtitle/buttons) |
| Strict Mode | In-app wait-timer lock (see limits below) |
| Insights | Apple's `DeviceActivityReport` (privacy-preserving) |

## What could NOT be carried over (iOS makes these impossible)
These aren't missing effort — iOS has no API for them, for any app:

- **Killing / closing apps or clearing the app switcher.** There is no
  equivalent of Android's kill-process or "close background apps". iOS shields
  the app instead (Apple's block screen), but can't force-quit it or remove its
  card from the app switcher.
- **Blocking sites inside third-party browsers** (Chrome, Brave, etc.). iOS only
  exposes web filtering at the Safari / system level. Chrome can be blocked as a
  whole app, but not per-website.
- **A fully custom, opaque block overlay** and messages. You only get Apple's
  `ShieldConfiguration` (icon, title, subtitle, two buttons).
- **Uninstall / tamper protection** (Android device admin). Consumer iOS has no
  equivalent. The nearest options are the user setting a **Screen Time
  passcode** (stops changing restrictions), **Guided Access**, or **MDM
  supervision** — all separate from this app.
- **Instant foreground detection** and the immediate app↔website auto-pairing.
  iOS gives opaque tokens, so the app never learns which apps were picked and
  can't map an app to its website automatically.
- **Split screen launcher / saved combos**, **screenshot & recording control**,
  **factory-reset / settings-page blocking** — none are possible on iOS.

## Honest note on Strict Mode
The in-app Strict Mode makes it harder to turn blocking off *inside this app*
(a wait timer). It cannot stop a determined user who opens **Settings > Screen
Time** — unless they themselves set a Screen Time passcode. Without MDM
supervision, no third-party iOS app can fully prevent that.
