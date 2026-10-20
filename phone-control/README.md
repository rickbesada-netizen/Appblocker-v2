# AppBlocker Remote (phone web app / PWA)

Control your laptop's Strict Mode from your phone, see your devices, and view
your synced blocks. Connects to the same Supabase account as the desktop app.

This is a **hosted web app**, not a native app — but you can turn it into a real
installable **APK with no Android Studio** (step 3 below).

## What's in here
- `index.html` — the app
- `manifest.webmanifest`, `sw.js` — make it installable as an app (PWA)
- `icon-192.png`, `icon-512.png` — app icon

## 1. Put it online (one time)
Upload the whole folder to a web host over **HTTPS** (an installable app needs
HTTPS). Easiest options:
- **Your Hostinger site:** upload these files to a folder, e.g.
  `public_html/appblocker/`, so it opens at `https://activeviewpoint.com/appblocker/`.
- **Netlify Drop** (free, instant): go to app.netlify.com/drop and drag this
  folder in — it gives you an `https://…netlify.app` link.

Open that link on your phone and sign in with your AppBlocker account. You should
see your laptop under "Your devices" with **Strict on / Lock all** buttons.

## 2. Use it like an app (instant, no APK)
On the phone, open the link in Chrome → menu (⋮) → **Add to Home screen**. It
installs with the padlock icon and opens full-screen like an app.

## 3. Get a real downloadable APK (no Android Studio)
1. Go to **https://www.pwabuilder.com**.
2. Paste your hosted URL (from step 1) and click **Start**.
3. Choose **Android** → **Generate Package**.
4. It gives you a **signed `.apk`/`.aab` to download**. Copy the `.apk` to your
   phone and open it to install (allow "install from this source" when asked).

That APK is a real app on your phone, built from this web app — no Android Studio,
no Gradle.

## Notes
- This remote **controls** devices and shows your blocks; the actual on-phone
  app-blocking is the separate native Android app (that part needs the native
  build). This web app is the cross-device controller.
- It uses the same public Supabase keys as the desktop app, protected by
  row-level security — users only ever see their own devices and blocks.
- Sending "Strict on" / "Lock all" to your laptop works as long as the laptop
  app is running (it receives commands in realtime). Remote "off" still obeys the
  laptop's unlock method — it can't bypass a real lock.
