package com.example.appblocker

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager

/**
 * OS-level "cannot be uninstalled while locked" for Android.
 *
 * Device Admin (BlockerDeviceAdminReceiver) only makes uninstall harder - the
 * user can still deactivate the admin, boot to Safe Mode, or factory-reset.
 * DEVICE OWNER is the enterprise tier that closes those doors: it can grey out
 * Uninstall at the OS level and forbid factory reset and Safe Mode entirely.
 *
 * Device Owner must be provisioned once over USB on a device with no accounts:
 *     adb shell dpm set-device-owner com.example.appblocker/.BlockerDeviceAdminReceiver
 * After that, this object enforces the locks whenever Strict Mode is ON and
 * releases them the moment Strict Mode is turned OFF - so the device is only
 * ever as locked as the user's own central Strict Mode.
 *
 * Everything here is a no-op (and never throws) when the app is NOT a Device
 * Owner, so it has zero effect on ordinary installs.
 */
object DeviceOwner {

    private fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun admin(context: Context): ComponentName =
        ComponentName(context.applicationContext, BlockerDeviceAdminReceiver::class.java)

    /** True when AppBlocker has been provisioned as the device owner. */
    fun isDeviceOwner(context: Context): Boolean = try {
        dpm(context).isDeviceOwnerApp(context.packageName)
    } catch (e: Exception) {
        false
    }

    /**
     * Bring the OS-level restrictions in line with the central Strict Mode.
     * Blocks uninstall + factory reset + Safe Mode while Strict is ON; lifts
     * them while Strict is OFF. Safe to call often (idempotent) and from any
     * thread. No-op unless we are the device owner.
     */
    fun sync(context: Context) {
        val app = context.applicationContext
        if (!isDeviceOwner(app)) return
        apply(app, StrictModeStore.isActive(app))
    }

    /** Force a specific state (used right after provisioning / from the setup screen). */
    fun apply(context: Context, locked: Boolean) {
        val app = context.applicationContext
        if (!isDeviceOwner(app)) return
        val dpm = dpm(app)
        val admin = admin(app)

        // 1. Grey out "Uninstall" for AppBlocker itself at the OS level.
        runCatching { dpm.setUninstallBlocked(admin, app.packageName, locked) }

        // 2. Forbid factory reset from Settings while locked (recovery-mode
        //    wipe is still possible - nothing any app can prevent).
        setRestriction(dpm, admin, UserManager.DISALLOW_FACTORY_RESET, locked)

        // 3. Forbid Safe Mode so the accessibility guard can't be bypassed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            setRestriction(dpm, admin, UserManager.DISALLOW_SAFE_BOOT, locked)
        }
    }

    private fun setRestriction(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        key: String,
        on: Boolean,
    ) {
        runCatching {
            if (on) dpm.addUserRestriction(admin, key)
            else dpm.clearUserRestriction(admin, key)
        }
    }

    /**
     * Give up Device Owner entirely (used if the user ever wants to fully
     * remove the lock). Only works while Strict Mode is OFF - a locked app
     * must not be able to release its own OS-level protection.
     */
    fun relinquish(context: Context): Boolean {
        val app = context.applicationContext
        if (!isDeviceOwner(app)) return false
        if (StrictModeStore.isActive(app)) return false
        return try {
            apply(app, false)
            dpm(app).clearDeviceOwnerApp(app.packageName)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** One-line human status for the setup screen. */
    fun statusLine(context: Context): String = when {
        !isDeviceOwner(context) -> "Not set up - this device can still be uninstalled normally."
        StrictModeStore.isActive(context) ->
            "Active - uninstall, factory reset and Safe Mode are blocked while Strict Mode is on."
        else -> "Ready - locks will engage automatically whenever Strict Mode is on."
    }
}
