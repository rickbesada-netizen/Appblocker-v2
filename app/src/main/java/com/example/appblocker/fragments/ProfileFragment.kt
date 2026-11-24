package com.example.appblocker.fragments

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.appblocker.AppSettings
import com.example.appblocker.DeviceOwner
import com.example.appblocker.PermissionUtils
import com.example.appblocker.R
import com.example.appblocker.AppCloser
import com.example.appblocker.SplitLauncherActivity
import com.example.appblocker.StrictModeStore
import com.example.appblocker.Updater
import com.example.appblocker.cloud.CloudSync
import com.example.appblocker.cloud.DeviceRow
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// "Settings" tab (kept as ProfileFragment so the bottom nav wiring is unchanged).
class ProfileFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_profile, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()

        view.findViewById<View>(R.id.accountRow).setOnClickListener { onAccountTapped(view) }

        view.findViewById<View>(R.id.messageRow).setOnClickListener { editMessage(view) }
        view.findViewById<View>(R.id.breakLengthRow).setOnClickListener {
            // Strict Mode: breaks can only get shorter.
            val choices = stricter(AppSettings.BREAK_LENGTH_CHOICES, AppSettings.breakMinutes(context), lower = true)
            AlertDialog.Builder(context)
                .setTitle("Break length")
                .setItems(choices.map { "$it min" }.toTypedArray()) { _, which ->
                    AppSettings.setBreakMinutes(context, choices[which])
                    render(view)
                }
                .show()
        }
        view.findViewById<View>(R.id.breaksPerDayRow).setOnClickListener {
            // Strict Mode: fewer breaks only.
            val choices = stricter(AppSettings.BREAKS_PER_DAY_CHOICES, AppSettings.breaksPerDay(context), lower = true)
            AlertDialog.Builder(context)
                .setTitle("Breaks per day")
                .setItems(choices.map { if (it == 0) "No breaks" else "$it" }.toTypedArray()) { _, which ->
                    AppSettings.setBreaksPerDay(context, choices[which])
                    render(view)
                }
                .show()
        }
        view.findViewById<View>(R.id.startupLockRow).setOnClickListener {
            // Strict Mode: the restart lock can only get longer.
            val choices = stricter(AppSettings.STARTUP_LOCK_CHOICES, AppSettings.startupLockSeconds(context), lower = false)
            AlertDialog.Builder(context)
                .setTitle("Lock screen after restart")
                .setItems(choices.map { "At least $it seconds" }.toTypedArray()) { _, which ->
                    AppSettings.setStartupLockSeconds(context, choices[which])
                    render(view)
                }
                .show()
        }
        // Strict Mode: a permission that's off can be turned on; one that's on
        // can't be opened (that's where it would be switched off).
        view.findViewById<View>(R.id.splitScreenRow).setOnClickListener {
            startActivity(Intent(requireContext(), SplitLauncherActivity::class.java))
        }
        view.findViewById<View>(R.id.closeAppsRow).setOnClickListener {
            val closed = AppCloser.closeBackgroundApps(requireContext())
            Toast.makeText(
                requireContext(),
                if (closed == 0) "No background apps to close" else "Closed background apps ($closed)",
                Toast.LENGTH_SHORT
            ).show()
        }
        view.findViewById<View>(R.id.accessibilityRow).setOnClickListener {
            openPermission(PermissionUtils.hasAccessibilityPermission(context), Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        view.findViewById<View>(R.id.overlayRow).setOnClickListener {
            openPermission(
                PermissionUtils.hasOverlayPermission(context),
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            )
        }
        view.findViewById<View>(R.id.notificationRow).setOnClickListener {
            openPermission(PermissionUtils.hasNotificationAccess(context), Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            view.findViewById<TextView>(R.id.appVersionText).text = info.versionName
            view.findViewById<TextView>(R.id.memberSinceText).text =
                SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(info.firstInstallTime))
        } catch (e: PackageManager.NameNotFoundException) {
            // leave blank
        }

        setupUpdates(view)
        setupDeviceOwner(view)
    }

    // ---- Device lock (Device Owner) ----
    private fun setupDeviceOwner(view: View) {
        view.findViewById<MaterialButton>(R.id.deviceOwnerBtn)
            .setOnClickListener { onDeviceOwnerTapped(view) }
        renderDeviceOwner(view)
    }

    private fun renderDeviceOwner(view: View) {
        val context = requireContext()
        val status = view.findViewById<TextView>(R.id.deviceOwnerStatus)
        val btn = view.findViewById<MaterialButton>(R.id.deviceOwnerBtn)
        status.text = DeviceOwner.statusLine(context)
        btn.text = when {
            !DeviceOwner.isDeviceOwner(context) -> "Set up device lock"
            StrictModeStore.isActive(context) -> "How the lock works"
            else -> "Remove device lock"
        }
    }

    private fun onDeviceOwnerTapped(view: View) {
        val context = requireContext()
        when {
            !DeviceOwner.isDeviceOwner(context) -> showDeviceOwnerSetup()
            StrictModeStore.isActive(context) ->
                AlertDialog.Builder(context)
                    .setTitle("Device lock is active")
                    .setMessage(
                        "While Strict Mode is on, Android blocks AppBlocker's Uninstall, " +
                            "factory reset and Safe Mode.\n\nTurn Strict Mode off first if you " +
                            "ever need to remove the lock."
                    )
                    .setPositiveButton("OK", null)
                    .show()
            else -> AlertDialog.Builder(context)
                .setTitle("Remove device lock?")
                .setMessage(
                    "This gives up AppBlocker's device-owner protection. Uninstall and " +
                        "factory reset will work normally again. You'd need to redo the one-time " +
                        "USB setup to re-enable it."
                )
                .setNegativeButton("Keep it", null)
                .setPositiveButton("Remove") { _, _ ->
                    val ok = DeviceOwner.relinquish(context)
                    Toast.makeText(
                        context,
                        if (ok) "Device lock removed." else "Couldn't remove — is Strict Mode off?",
                        Toast.LENGTH_SHORT
                    ).show()
                    renderDeviceOwner(view)
                }
                .show()
        }
    }

    private fun showDeviceOwnerSetup() {
        val cmd = "adb shell dpm set-device-owner " +
            "com.example.appblocker/.BlockerDeviceAdminReceiver"
        val steps =
            "This is a one-time setup over USB. It only works on a device with no " +
                "Google/Samsung accounts added yet.\n\n" +
                "1. Remove all accounts: Settings → Accounts → remove each one.\n" +
                "2. On a computer, install \"adb\" (Android Platform Tools).\n" +
                "3. On the phone: Settings → About phone → tap Build number 7× to unlock " +
                "Developer options, then Settings → Developer options → turn on USB debugging.\n" +
                "4. Plug the phone into the computer and accept the \"Allow USB debugging\" prompt.\n" +
                "5. Run this command on the computer:\n\n$cmd\n\n" +
                "6. You'll see \"Success: Device owner set\". Re-add your accounts.\n\n" +
                "After that, AppBlocker blocks uninstall, factory reset and Safe Mode automatically " +
                "whenever Strict Mode is on — and lifts them when Strict Mode is off.\n\n" +
                "Note: a factory reset from the phone's hardware recovery menu still wipes the " +
                "device — no app can stop that."
        AlertDialog.Builder(requireContext())
            .setTitle("Set up device lock")
            .setMessage(steps)
            .setPositiveButton("Copy command") { _, _ ->
                val clip = requireContext()
                    .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                clip.setPrimaryClip(android.content.ClipData.newPlainText("adb", cmd))
                Toast.makeText(requireContext(), "Command copied.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---- In-app updater ----
    private var downloadedUpdate: java.io.File? = null

    private fun setupUpdates(view: View) {
        val btn = view.findViewById<MaterialButton>(R.id.checkUpdateBtn)
        btn.setOnClickListener { checkForUpdate(view, manual = true) }
        checkForUpdate(view, manual = false) // quiet auto-check on open
    }

    private fun checkForUpdate(view: View, manual: Boolean) {
        val status = view.findViewById<TextView>(R.id.updateStatus)
        val btn = view.findViewById<MaterialButton>(R.id.checkUpdateBtn)
        if (manual) status.text = "Checking…"
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { Updater.check() }
            if (!isAdded) return@launch
            when {
                r == null -> if (manual) status.text = "Couldn't check for updates (no internet?)."
                r.available -> {
                    status.text = "🔔 Update available. Tap Update now."
                    btn.text = "Update now"
                    btn.setOnClickListener { downloadAndInstall(view) }
                }
                else -> {
                    if (manual) status.text = "You're on the latest version." else status.text = ""
                    btn.text = "Check for updates"
                    btn.setOnClickListener { checkForUpdate(view, manual = true) }
                }
            }
        }
    }

    private fun downloadAndInstall(view: View) {
        val context = requireContext()
        val status = view.findViewById<TextView>(R.id.updateStatus)
        // Android needs "install unknown apps" allowed for AppBlocker first.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            status.text = "Allow AppBlocker to install apps, then tap Update now again."
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            }
            return
        }
        status.text = "Downloading update…"
        lifecycleScope.launch {
            val apk = withContext(Dispatchers.IO) { Updater.downloadApk(context) }
            if (!isAdded) return@launch
            if (apk == null) { status.text = "Download failed — try again."; return@launch }
            downloadedUpdate = apk
            status.text = "Opening installer — tap Install."
            runCatching { Updater.install(context, apk) }
                .onFailure { status.text = "Couldn't open the installer." }
        }
    }

    override fun onResume() {
        super.onResume()
        view?.let { render(it); renderDeviceOwner(it) }
    }

    private fun render(view: View) {
        val context = requireContext()
        renderAccount(view)
        view.findViewById<TextView>(R.id.strictBanner).visibility =
            if (StrictModeStore.isActive(context)) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.messageValue).text =
            AppSettings.blockMessage(context).ifBlank { "None" }
        view.findViewById<TextView>(R.id.breakLengthValue).text = "${AppSettings.breakMinutes(context)} min"
        val perDay = AppSettings.breaksPerDay(context)
        view.findViewById<TextView>(R.id.breaksPerDayValue).text = if (perDay == 0) "No breaks" else "$perDay"
        view.findViewById<TextView>(R.id.startupLockValue).text = "${AppSettings.startupLockSeconds(context)} sec"
        view.findViewById<TextView>(R.id.accessibilityValue).text = onOff(PermissionUtils.hasAccessibilityPermission(context))
        view.findViewById<TextView>(R.id.overlayValue).text = onOff(PermissionUtils.hasOverlayPermission(context))
        view.findViewById<TextView>(R.id.notificationValue).text = onOff(PermissionUtils.hasNotificationAccess(context))
    }

    private fun onOff(on: Boolean) = when {
        on && StrictModeStore.isActive(requireContext()) -> "On 🔒"
        on -> "On ✓"
        else -> "Off - tap to turn on"
    }

    private fun openPermission(isOn: Boolean, intent: Intent) {
        if (isOn && StrictModeStore.isActive(requireContext())) {
            Toast.makeText(requireContext(), "Strict Mode: permissions can't be turned off", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(intent)
    }

    // All choices normally; in Strict Mode only the current value and stricter ones.
    private fun stricter(choices: List<Int>, current: Int, lower: Boolean): List<Int> {
        if (!StrictModeStore.isActive(requireContext())) return choices
        val allowed = choices.filter { if (lower) it <= current else it >= current }
        return if (allowed.isEmpty()) listOf(current) else allowed
    }

    // ---------- Account & sync ----------

    private fun renderAccount(view: View) {
        val status = view.findViewById<TextView>(R.id.accountStatus)
        val sub = view.findViewById<TextView>(R.id.accountSub)
        val action = view.findViewById<TextView>(R.id.accountAction)
        val divider = view.findViewById<View>(R.id.devicesDivider)
        val header = view.findViewById<TextView>(R.id.devicesHeader)
        val container = view.findViewById<LinearLayout>(R.id.devicesContainer)

        if (CloudSync.isSignedIn) {
            status.text = "Signed in"
            sub.text = CloudSync.email ?: "Block lists sync automatically"
            action.text = "Sign out"
            divider.visibility = View.VISIBLE
            header.visibility = View.VISIBLE
            container.visibility = View.VISIBLE
            loadDevices(container)
        } else {
            status.text = "Not signed in"
            sub.text = "Sign in to sync your block lists and control your other devices"
            action.text = "Sign in"
            divider.visibility = View.GONE
            header.visibility = View.GONE
            container.visibility = View.GONE
            container.removeAllViews()
        }
    }

    private fun onAccountTapped(view: View) {
        if (CloudSync.isSignedIn) {
            AlertDialog.Builder(requireContext())
                .setTitle("Sign out")
                .setMessage("Sign out of ${CloudSync.email ?: "this account"}? Your block lists stay on this device.")
                .setPositiveButton("Sign out") { _, _ ->
                    lifecycleScope.launch {
                        CloudSync.signOut()
                        view.let { render(it) }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            showSignInDialog(view)
        }
    }

    private fun showSignInDialog(view: View) {
        val context = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val emailInput = EditText(context).apply {
            hint = "Email"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val passInput = EditText(context).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad + pad, pad, pad + pad, 0)
            addView(emailInput); addView(passInput)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("Account")
            .setView(wrap)
            .setPositiveButton("Sign in", null)
            .setNeutralButton("Create account", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                auth(view, emailInput, passInput, signUp = false, dialog)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                auth(view, emailInput, passInput, signUp = true, dialog)
            }
        }
        dialog.show()
    }

    private fun auth(view: View, emailInput: EditText, passInput: EditText, signUp: Boolean, dialog: AlertDialog) {
        val email = emailInput.text.toString().trim()
        val password = passInput.text.toString()
        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(requireContext(), "Enter an email and password", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val result = if (signUp) CloudSync.signUp(email, password) else CloudSync.signIn(email, password)
            result
                .onSuccess {
                    if (CloudSync.isSignedIn) {
                        Toast.makeText(requireContext(), "Signed in", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        view.let { render(it) }
                    } else {
                        Toast.makeText(requireContext(), "Check your email to confirm, then sign in", Toast.LENGTH_LONG).show()
                    }
                }
                .onFailure {
                    Toast.makeText(requireContext(), it.message ?: "Sign in failed", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun loadDevices(container: LinearLayout) {
        container.removeAllViews()
        lifecycleScope.launch {
            val devices = CloudSync.listDevices()
            container.removeAllViews()
            if (devices.isEmpty()) {
                container.addView(deviceLabel("No other devices yet"))
                return@launch
            }
            devices.forEach { container.addView(deviceRow(it)) }
        }
    }

    private fun deviceLabel(text: String): TextView {
        val pad = (16 * resources.displayMetrics.density).toInt()
        return TextView(requireContext()).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_disabled))
            textSize = 13f
            setPadding(pad, pad / 2, pad, pad / 2)
        }
    }

    private fun deviceRow(d: DeviceRow): View {
        val context = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val isThis = d.id == CloudSync.thisDeviceId()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val name = TextView(context).apply {
            text = (d.name ?: "Device") + if (isThis) " (this device)" else ""
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(name)
        if (!isThis && d.id != null) {
            row.addView(controlButton("Strict on") {
                lifecycleScope.launch {
                    CloudSync.sendCommand("strict_on", d.id)
                    Toast.makeText(context, "Sent to ${d.name ?: "device"}", Toast.LENGTH_SHORT).show()
                }
            })
            row.addView(controlButton("Lock all") {
                lifecycleScope.launch {
                    CloudSync.sendCommand("lock_all", d.id)
                    Toast.makeText(context, "Sent to ${d.name ?: "device"}", Toast.LENGTH_SHORT).show()
                }
            })
        }
        return row
    }

    private fun controlButton(label: String, onClick: () -> Unit): Button {
        return Button(requireContext()).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_blue))
            setBackgroundColor(0x00000000)
            minWidth = 0
            minimumWidth = 0
            setPadding(24, 0, 24, 0)
            setOnClickListener { onClick() }
        }
    }

    private fun editMessage(view: View) {
        val context = requireContext()
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(AppSettings.blockMessage(context))
            hint = "e.g. Is this really worth it?"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(context)
            .setTitle("Block screen message")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                AppSettings.setBlockMessage(context, input.text.toString())
                render(view)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


}
