package com.example.appblocker

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

// Watches which app is on screen (instant, event-driven), counts screen time
// and opens, reads browser address bars, and re-checks every few seconds so
// schedules, limits, Quick Block timers and breaks kick in mid-session.
class AppBlockAccessibilityService : AccessibilityService() {

    companion object {
        private const val TICK_MS = 4_000L
        private const val SYSTEM_UI = "com.android.systemui"

        // System windows that must never be blocked or treated as a "settings"
        // screen: the fingerprint / face prompt, the lock-screen keyguard, and
        // the credential/autofill pickers. Blocking these breaks biometric
        // sign-in and app-lock unlock prompts.
        private val SYSTEM_ALLOW = setOf(
            "com.android.systemui",
            "com.android.keyguard",
            "android",
            "com.google.android.gms", // Google biometric / auth prompts
            "com.samsung.android.biometrics",
            "com.samsung.android.bio.face.service",
            "com.samsung.android.fingerprint.service",
            "com.samsung.android.authfw",
            "com.android.credentialmanager",
            "com.google.android.gms.auth",
            "com.samsung.android.samsungpassautofill",
            "com.samsung.android.samsungpass",
            // File picker (Storage Access Framework) - used when attaching a
            // file to OneNote, email, etc. Must pass through.
            "com.android.documentsui",
            "com.google.android.documentsui",
            "com.android.providers.media.module",
            "com.google.android.providers.media.module",
            // The app chooser / "share with" and "open with" sheets.
            "com.android.intentresolver",
            "com.android.internal.app.ResolverActivity",
            // Screenshot editor and screen recorder consent (Samsung + AOSP).
            "com.samsung.android.app.smartcapture",
            "com.samsung.android.screenshot",
            "com.samsung.android.game.gametools"
        )

        // Substrings for system surfaces that vary by brand: biometric prompts,
        // the file picker, the share/resolver sheet, screenshot and screen
        // recording. None of these should ever be blocked.
        private val SYSTEM_ALLOW_HINTS = listOf(
            "biometric", "fingerprint", "keyguard", "credential",
            "documentsui", "documents.ui", "filepicker", "file.picker",
            "resolver", "chooser", "intentresolver",
            "screenshot", "smartcapture", "screenrecord", "screen.record"
        )

        private fun isSystemAllowed(pkg: String): Boolean {
            if (pkg in SYSTEM_ALLOW) return true
            return SYSTEM_ALLOW_HINTS.any { pkg.contains(it) }
        }
        private val RECENTS_WORDS = listOf("recent apps", "recents", "overview", "recent applications")

        // Text that identifies a factory-reset / erase-all screen in Settings.
        private val RESET_WORDS = listOf(
            "factory data reset", "factory reset", "erase all data",
            "erase all content", "reset phone", "reset device", "reset tablet",
            "delete all data", "format phone"
        )

        // Text that identifies the Device admin apps screen (where AppBlocker's
        // admin could be deactivated to allow uninstalling).
        private val DEVICE_ADMIN_WORDS = listOf(
            "device admin apps", "device admin app", "device administrators",
            "device admin", "deactivate this device admin app",
            "deactivate device admin"
        )

        // Apps where AppBlocker could be force-stopped, uninstalled or have
        // its permissions removed.
        private val TAMPER_PACKAGES = setOf(
            "com.android.settings", "com.samsung.android.settings", "com.miui.securitycenter",
            "com.miui.settings", "com.oplus.settings", "com.coloros.settings", "com.oneplus.settings",
            "com.vivo.settings", "com.transsion.settings",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller", "com.coloros.safecenter", "com.oplus.safecenter"
        )

        @Volatile
        private var instance: AppBlockAccessibilityService? = null

        fun isRunning(): Boolean = instance != null

        // Re-check whatever is on screen right now (used after the startup lock).
        fun recheckNow() {
            instance?.let { it.handler.post { it.checkCurrentScreen() } }
        }

        // Open two apps in split screen using the system split toggle - far more
        // reliable than launch-adjacent alone. Returns false if the service isn't
        // running (caller can fall back).
        fun launchSplit(topPackage: String, bottomPackage: String): Boolean {
            val svc = instance ?: return false
            svc.doLaunchSplit(topPackage, bottomPackage)
            return true
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingBrowserCheck: Runnable? = null
    private var pendingForegroundCheck: Runnable? = null
    private var foregroundPackage: String? = null
    private var lastStrictKickAt = 0L
    private var pendingTamperCheck: Runnable? = null
    private var lastBackHost: String? = null
    private var backAttempts = 0

    private val ticker = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = System.currentTimeMillis()
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> UsageTracker.onScreenOff(applicationContext, now)
                Intent.ACTION_USER_PRESENT -> {
                    UsageTracker.onUserPresent(now)
                    foregroundPackage?.let { evaluate(it, isNewOpen = false) }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Mark running first: even if something below throws, the service must
        // stay alive. Any crash in onServiceConnected makes Android turn the
        // whole accessibility service OFF (the "reverts to Off" bug), so every
        // startup step is guarded and the heavy ones are deferred off this call.
        instance = this

        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(screenReceiver, filter)
            }
        } catch (e: Exception) {
            // Receiver is a nice-to-have; blocking still works without it.
        }

        try {
            handler.postDelayed(ticker, TICK_MS)
        } catch (e: Exception) {
            // ignore
        }

        // Load the (large) adult list off the main thread so it can never ANR
        // or stall the service bind.
        if (AdultBlockStore.isEnabled(applicationContext)) {
            Thread {
                try {
                    AdultBlocklist.ensureLoaded(applicationContext)
                } catch (e: Exception) {
                    // best effort
                }
            }.start()
        }

        // Check the current screen shortly after binding (not synchronously),
        // so a heavy or throwing evaluate can't take the service down at start.
        handler.postDelayed({
            try {
                checkCurrentScreen()
            } catch (e: Exception) {
                // ignore
            }
        }, 600)
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            // wasn't registered
        }
        BlockOverlayManager.hide(applicationContext)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    private fun doLaunchSplit(topPackage: String, bottomPackage: String) {
        val top = packageManager.getLaunchIntentForPackage(topPackage) ?: return
        val bottom = packageManager.getLaunchIntentForPackage(bottomPackage) ?: return
        top.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        bottom.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
        )

        // The split toggle docks whatever app is FOCUSED, so the top app must be
        // fully in the foreground before we toggle - a fixed delay is unreliable
        // on a cold start (the toggle fires mid-transition and docks nothing).
        // So: launch the top app, then poll until it's actually foreground,
        // toggle split, wait for the split UI, then launch the bottom app into
        // the empty half.
        startActivity(top)
        waitForForeground(topPackage, attemptsLeft = 20) {
            performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            handler.postDelayed({
                try {
                    startActivity(bottom)
                } catch (e: Exception) {
                    // some launchers won't allow the second dock
                }
            }, 900)
        }
    }

    // Calls onReady() once `pkg` is the foreground app, polling every 150ms up
    // to `attemptsLeft` times, then giving up (still runs onReady as a last try).
    private fun waitForForeground(pkg: String, attemptsLeft: Int, onReady: () -> Unit) {
        val current = try {
            rootInActiveWindow?.packageName?.toString()
        } catch (e: Exception) {
            null
        }
        if (current == pkg || attemptsLeft <= 0) {
            onReady()
        } else {
            handler.postDelayed({ waitForForeground(pkg, attemptsLeft - 1, onReady) }, 150)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            handleEvent(event)
        } catch (e: Exception) {
            // A thrown event handler would make Android disable the whole
            // accessibility service - swallow so blocking keeps running.
        }
    }

    private fun handleEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        val context = applicationContext

        // Never block or tamper-check the fingerprint / keyguard / credential
        // system prompts - they must pass through for biometric sign-in.
        if (isSystemAllowed(pkg)) return

        if (enforceStrictScreens(event, pkg)) return

        if (pkg in TAMPER_PACKAGES &&
            (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        ) {
            pendingTamperCheck?.let { handler.removeCallbacks(it) }
            val task = Runnable { checkTamper(pkg) }
            pendingTamperCheck = task
            handler.postDelayed(task, if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) 0L else 200L)
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg == packageName) {
                    // Only our real screens count - not our own block overlay.
                    val cls = event.className?.toString() ?: ""
                    if (cls.startsWith(packageName)) {
                        if (foregroundPackage != pkg) {
                            UsageTracker.onForeground(context, pkg, countable = false, now = System.currentTimeMillis())
                        }
                        foregroundPackage = pkg
                        BlockOverlayManager.hide(context)
                    }
                    return
                }
                // Notification shade, keyboard: not an app switch.
                if (pkg == SYSTEM_UI || pkg == AppCatalog.keyboardPackage(context)) return

                val isNewOpen = pkg != foregroundPackage
                if (isNewOpen) {
                    UsageTracker.onForeground(
                        context, pkg,
                        countable = AppCatalog.isCountable(context, pkg),
                        now = System.currentTimeMillis()
                    )
                }
                foregroundPackage = pkg
                evaluate(pkg, isNewOpen)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (BrowserUrlReader.isSupportedBrowser(pkg) && pkg == foregroundPackage) {
                    pendingBrowserCheck?.let { handler.removeCallbacks(it) }
                    val task = Runnable { evaluate(pkg, isNewOpen = false) }
                    pendingBrowserCheck = task
                    handler.postDelayed(task, 150)
                } else if (pkg != packageName && pkg != SYSTEM_UI) {
                    // Any other app changing content: re-check the true foreground
                    // app. This catches cases where the window-state event was
                    // missed or reported a wrapper package (common with Settings
                    // on some brands), so a block still applies quickly.
                    pendingForegroundCheck?.let { handler.removeCallbacks(it) }
                    val task = Runnable { checkCurrentScreen() }
                    pendingForegroundCheck = task
                    handler.postDelayed(task, 250)
                }
            }
        }
    }

    // ----- Strict Mode: Recent apps & split screen -----

    // Returns true if it kicked the user out (caller should stop processing).
    private fun enforceStrictScreens(event: AccessibilityEvent, pkg: String): Boolean {
        val context = applicationContext
        if (!StrictModeStore.isActive(context)) return false

        if (StrictOptionsStore.isBlockRecentApps(context) &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            looksLikeRecents(event, pkg)
        ) {
            kickOut("Recent apps is blocked by Strict Mode")
            return true
        }

        if (StrictOptionsStore.isBlockSplitScreen(context) &&
            (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) &&
            protectedAppInSplit()
        ) {
            kickOut("Settings can't be used in split screen while Strict Mode is on")
            return true
        }
        return false
    }

    // Strict Mode: any Settings / permission / uninstall screen that shows
    // AppBlocker (App info, Accessibility toggle, display-over-apps, notification
    // access, uninstall prompt) is closed immediately.
    // Ratchet rule: if the overlay permission is OFF, these screens stay open so
    // it can be turned back ON.
    private fun checkTamper(pkg: String) {
        val context = applicationContext
        if (!StrictModeStore.isActive(context)) return
        if (!StrictOptionsStore.isBlockDeviceSettings(context)) return
        if (!PermissionUtils.hasOverlayPermission(context)) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        if (isSystemAllowed(pkg)) return
        val label = getString(R.string.app_name)
        // Any of AppBlocker's own permission / info screens name the app on
        // screen: App info, the Accessibility toggle, Appear-on-top / display
        // over other apps, and Notification access. Blocking whenever the app's
        // name shows on a Settings screen covers all of them, so none can be
        // used to switch AppBlocker off or uninstall it.
        val namesApp = try {
            root.findAccessibilityNodeInfosByText(label).isNotEmpty() ||
                root.findAccessibilityNodeInfosByText(packageName).isNotEmpty()
        } catch (e: Exception) {
            false
        }
        // On a permission DETAIL screen the app name plus a permission keyword
        // is a strong signal; on a big permission LIST the name alone is enough
        // because the only reason it appears is AppBlocker's own row.
        if (namesApp) {
            kickOut("AppBlocker's settings are locked by Strict Mode")
            return
        }
        // Block the factory-reset / erase-all screen while Strict Mode is on.
        if (isResetScreen(root)) {
            kickOut("Factory reset is blocked by Strict Mode")
            return
        }
        // Block the Device admin apps screen (its escape route for uninstalling).
        if (containsAny(root, DEVICE_ADMIN_WORDS)) {
            kickOut("Device admin settings are locked by Strict Mode")
        }
    }

    // True if the current Settings screen is a factory-reset / erase-all page.
    private fun isResetScreen(root: android.view.accessibility.AccessibilityNodeInfo): Boolean =
        containsAny(root, RESET_WORDS)

    // True if any of the phrases appears as on-screen text.
    private fun containsAny(
        root: android.view.accessibility.AccessibilityNodeInfo,
        phrases: List<String>
    ): Boolean {
        for (phrase in phrases) {
            val nodes = try {
                root.findAccessibilityNodeInfosByText(phrase)
            } catch (e: Exception) {
                emptyList()
            }
            if (nodes.any { it.text?.toString()?.lowercase()?.contains(phrase) == true }) return true
        }
        return false
    }

    // The Recents screen is drawn by the launcher or System UI depending on
    // the phone; only look for it there so normal apps are never affected.
    private fun looksLikeRecents(event: AccessibilityEvent, pkg: String): Boolean {
        if (pkg != SYSTEM_UI && pkg !in AppCatalog.homePackages(applicationContext)) return false
        val cls = event.className?.toString()?.lowercase() ?: ""
        if ("recents" in cls || "overview" in cls) return true
        val text = (event.text.joinToString(" ") + " " + (event.contentDescription ?: "")).lowercase()
        return RECENTS_WORDS.any { text.contains(it) }
    }

    // True only when the phone is in split screen AND one of the panes is a
    // Settings / permissions app. Ordinary split screen (two normal apps) is
    // left alone - we only stop Settings being tucked into a split to dodge a
    // full-screen block.
    private fun protectedAppInSplit(): Boolean = try {
        val appWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val splitOn = windows.any { it.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER } ||
            appWindows.size >= 2
        if (!splitOn) {
            false
        } else {
            appWindows.any { w ->
                val p = w.root?.packageName?.toString()
                p != null && p in TAMPER_PACKAGES
            }
        }
    } catch (e: Exception) {
        false
    }

    private fun kickOut(message: String) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        val now = System.currentTimeMillis()
        if (now - lastStrictKickAt > 3000) {
            android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_SHORT).show()
        }
        lastStrictKickAt = now
    }

    // Finds the app currently on screen without waiting for an event.
    private fun detectForeground(): String? {
        val active = rootInActiveWindow?.packageName?.toString()
        if (active != null && active != packageName && active != SYSTEM_UI) return active
        return try {
            windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
                ?.root?.packageName?.toString()
                ?.takeIf { it != packageName }
        } catch (e: Exception) {
            null
        }
    }

    private fun checkCurrentScreen() {
        val pkg = detectForeground() ?: foregroundPackage ?: return
        if (pkg == SYSTEM_UI || pkg == AppCatalog.keyboardPackage(applicationContext)) return
        val isNew = pkg != foregroundPackage
        if (isNew) {
            UsageTracker.onForeground(
                applicationContext, pkg,
                countable = AppCatalog.isCountable(applicationContext, pkg),
                now = System.currentTimeMillis()
            )
            foregroundPackage = pkg
        }
        evaluate(pkg, isNewOpen = true)
    }

    private fun onTick() {
        val now = System.currentTimeMillis()
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!power.isInteractive) return
        UsageTracker.tick(applicationContext, now)
        if (StrictModeStore.isActive(applicationContext) &&
            StrictOptionsStore.isBlockSplitScreen(applicationContext) && protectedAppInSplit()
        ) {
            kickOut("Settings can't be used in split screen while Strict Mode is on")
            return
        }
        if (foregroundPackage == null) {
            checkCurrentScreen()
            return
        }
        val pkg = foregroundPackage ?: return
        if (pkg == packageName) return
        evaluate(pkg, isNewOpen = false)
    }

    private fun evaluate(pkg: String, isNewOpen: Boolean) {
        val context = applicationContext
        if (pkg == packageName) return
        if (isSystemAllowed(pkg)) return

        val decision = BlockEngine.decisionForApp(context, pkg)
        if (decision != null) {
            if (BlockOverlayManager.show(context, decision, tag = "app:$pkg")) {
                UsageTracker.recordBlocked(context, pkg)
            }
            // Immediately dismiss the app so its content can't be seen behind the
            // block screen - the overlay still shows why it was blocked.
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        when {
            BrowserUrlReader.isSupportedBrowser(pkg) -> checkBrowser(pkg, justOpened = isNewOpen)
            else -> {
                val unsupported = BlockEngine.shouldBlockUnsupportedBrowser(context, pkg)
                if (unsupported != null) {
                    BlockOverlayManager.show(context, unsupported, tag = "browser:$pkg")
                } else {
                    BlockOverlayManager.hide(context)
                }
            }
        }
    }

    private fun checkBrowser(pkg: String, justOpened: Boolean) {
        val context = applicationContext
        val address = BrowserUrlReader.readAddress(this, pkg)
        val host = WebsiteUtils.hostFromAddressBar(address)
        if (address == null || host == null) {
            // Address bar hidden (scrolled away / fullscreen video): keep a
            // site block in place, but lift anything else.
            if (justOpened || BlockOverlayManager.currentTag() != "site:$pkg") {
                BlockOverlayManager.hide(context)
            }
            return
        }

        val decision = BlockEngine.decisionForAddress(context, host, address)
        if (decision != null) {
            handleBlockedPage(pkg, host, decision)
        } else {
            // Safe page - reset and clear any leftover block screen.
            lastBackHost = null
            backAttempts = 0
            if (BlockOverlayManager.currentTag() == "site:$pkg") BlockOverlayManager.hide(context)
        }
    }

    // Instead of parking a full-screen block over the browser (which leaves the
    // blocked page loaded, so it reappears next time the browser opens), briefly
    // cover it and press Back to leave the page - so the browser stays usable and
    // the blocked page isn't the one sitting there when you return.
    private fun handleBlockedPage(pkg: String, host: String, decision: BlockDecision) {
        val context = applicationContext

        if (host != lastBackHost) {
            lastBackHost = host
            backAttempts = 0
            UsageTracker.recordBlocked(context, pkg)
        }

        // Cover the page while we navigate away (auto-clears once we're off it).
        BlockOverlayManager.show(context, decision, tag = "site:$pkg")

        if (backAttempts >= 4) {
            // Browser won't leave the page (e.g. it's the only tab / first page).
            // Send the user home rather than fighting; the overlay stays as a
            // last resort so the content isn't visible.
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }
        backAttempts++
        performGlobalAction(GLOBAL_ACTION_BACK)
        // Re-check shortly; if we've left the blocked page, the overlay lifts.
        handler.postDelayed({
            if (foregroundPackage == pkg) checkBrowser(pkg, justOpened = false)
        }, 350)
    }
}
