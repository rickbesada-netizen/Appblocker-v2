package com.example.appblocker

import android.content.Context

// Tracks whether Strict Mode is active. There is ONE central Strict Mode for
// the whole app (controlled from the Strict tab). While it's on, blockings,
// settings and permissions can only be made stricter, never weaker, until it's
// deactivated with the chosen unlock method.
object StrictModeStore {

    private const val PREFS_NAME = "app_blocker_prefs"
    private const val KEY_STRICT_MODE_ACTIVE = "strict_mode_active"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // The central lock uses the default scope (""). The optional scope param is
    // retained only so the unlock stores stay signature-compatible.
    private fun key(scope: String) = KEY_STRICT_MODE_ACTIVE + scope

    fun isActive(context: Context, scope: String = ""): Boolean =
        prefs(context).getBoolean(key(scope), false)

    fun setActive(context: Context, active: Boolean, scope: String = "") {
        prefs(context).edit().putBoolean(key(scope), active).apply()
    }

    // A master-guarded setting is "frozen" (can't be weakened) only if it was
    // already ON when the master lock started. One that was OFF can still be
    // switched ON.
    fun freezeOn(context: Context, alreadyOn: Boolean): Boolean = isActive(context) && alreadyOn
}
