package com.example.appblocker

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton

private data class OptionRow(
    val title: String,
    val description: String,
    val caveat: String?,
    val isChecked: (SelectOptionsActivity) -> Boolean,
    val onChanged: (SelectOptionsActivity, Boolean) -> Unit
)

class SelectOptionsActivity : AppCompatActivity() {

    private lateinit var deviceAdminComponent: ComponentName
    private lateinit var devicePolicyManager: DevicePolicyManager

    // Kept so the "Disable uninstalling" switch can be reverted if the
    // user backs out of the system admin-activation dialog.
    private var disableUninstallSwitch: SwitchCompat? = null

    private val requestAdminLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
            // Only count it as ON if admin is genuinely active now.
            val nowActive = devicePolicyManager.isAdminActive(deviceAdminComponent)
            StrictOptionsStore.setDisableUninstall(this, nowActive)
            disableUninstallSwitch?.isChecked = nowActive
            if (!nowActive) {
                android.widget.Toast.makeText(this, "Not activated - AppBlocker can still be uninstalled", android.widget.Toast.LENGTH_LONG).show()
            }
            refreshUninstallState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_select_options)

        deviceAdminComponent = ComponentName(this, BlockerDeviceAdminReceiver::class.java)
        devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager

        findViewById<ImageButton>(R.id.closeButton).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.continueButton).setOnClickListener { finish() }

        buildOptionRows()

        // Loosening the rules mid-Strict-Mode would defeat the point.
        if (StrictModeStore.isActive(this)) {
            android.widget.Toast.makeText(this, "Strict Mode is on: options can be turned on, not off", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun buildOptionRows() {
        val container = findViewById<LinearLayout>(R.id.optionsList)
        val inflater = LayoutInflater.from(this)

        val rows = listOf(
            OptionRow(
                title = "Lock blockings in app",
                description = "You won't have access to edit, pause or delete your blockings in AppBlocker.",
                caveat = null,
                isChecked = { StrictOptionsStore.isLockBlockingsInApp(this) },
                onChanged = { _, checked -> StrictOptionsStore.setLockBlockingsInApp(this, checked) }
            ),
            OptionRow(
                title = "Disable AppBlocker uninstalling",
                description = "You won't be able to uninstall AppBlocker without deactivating device admin first.",
                caveat = null,
                isChecked = { StrictOptionsStore.isDisableUninstall(this) },
                onChanged = { _, checked -> onDisableUninstall(checked) }
            ),
            OptionRow(
                title = "Block sensitive settings",
                description = "Settings stays usable, but the risky pages - AppBlocker's own settings, its permissions, uninstall, device admin, and factory reset - are blocked while Strict Mode is active.",
                caveat = null,
                isChecked = { StrictOptionsStore.isBlockDeviceSettings(this) },
                onChanged = { _, checked -> StrictOptionsStore.setBlockDeviceSettings(this, checked) }
            ),
            OptionRow(
                title = "Block recent apps",
                description = "Opening Recent apps sends you straight back to the home screen, so apps can't be closed or switched from there.",
                caveat = null,
                isChecked = { StrictOptionsStore.isBlockRecentApps(this) },
                onChanged = { _, checked -> StrictOptionsStore.setBlockRecentApps(this, checked) }
            ),
            OptionRow(
                title = "Keep Settings out of split screen",
                description = "Split screen stays available, but Settings and the accessibility screen can't be opened in a split - so they can't be used to slip past a block.",
                caveat = null,
                isChecked = { StrictOptionsStore.isBlockSplitScreen(this) },
                onChanged = { _, checked -> StrictOptionsStore.setBlockSplitScreen(this, checked) }
            ),
            OptionRow(
                title = "Block reinstalled applications",
                description = "We will keep uninstalled applications in your blockings. You won't be able to bypass blocking with reinstalling.",
                caveat = null,
                isChecked = { StrictOptionsStore.isBlockReinstalledApps(this) },
                onChanged = { _, checked -> StrictOptionsStore.setBlockReinstalledApps(this, checked) }
            )
        )

        for (row in rows) {
            val itemView = inflater.inflate(R.layout.item_strict_option, container, false)
            itemView.findViewById<TextView>(R.id.optionTitle).text = row.title
            itemView.findViewById<TextView>(R.id.optionDescription).text = row.description

            val caveatView = itemView.findViewById<TextView>(R.id.optionCaveat)
            if (row.caveat != null) {
                caveatView.text = row.caveat
                caveatView.visibility = android.view.View.VISIBLE
            }

            val switch = itemView.findViewById<SwitchCompat>(R.id.optionSwitch)
            switch.isChecked = row.isChecked(this)
            // Frozen only if it was already ON when Strict Mode started.
            switch.isEnabled = !StrictModeStore.freezeOn(this, switch.isChecked)
            switch.setOnCheckedChangeListener { view, checked ->
                if (!checked && StrictModeStore.isActive(this)) {
                    view.isChecked = true
                    return@setOnCheckedChangeListener
                }
                row.onChanged(this, checked)
                if (checked && StrictModeStore.isActive(this)) view.isEnabled = false
            }

            if (row.title == "Disable AppBlocker uninstalling") {
                disableUninstallSwitch = switch
                // Reflect the REAL admin state, not just the saved flag.
                switch.isChecked = devicePolicyManager.isAdminActive(deviceAdminComponent)
                switch.isEnabled = !StrictModeStore.freezeOn(this, switch.isChecked)
            }

            container.addView(itemView)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUninstallState()
    }

    // Admin can be revoked from system settings; keep the saved flag honest.
    private fun refreshUninstallState() {
        val active = devicePolicyManager.isAdminActive(deviceAdminComponent)
        if (StrictOptionsStore.isDisableUninstall(this) != active) {
            StrictOptionsStore.setDisableUninstall(this, active)
        }
        disableUninstallSwitch?.let {
            it.isChecked = active
            it.isEnabled = !StrictModeStore.freezeOn(this, active)
        }
    }

    private fun onDisableUninstall(checked: Boolean) {
        val isAdminActive = devicePolicyManager.isAdminActive(deviceAdminComponent)
        if (checked && !isAdminActive) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, deviceAdminComponent)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "AppBlocker needs Device Admin so it can't be uninstalled while Strict Mode is on. " +
                        "You'll turn this off here in AppBlocker when you no longer need it."
                )
            }
            requestAdminLauncher.launch(intent)
            // Don't save yet - the result callback decides, so nothing is
            // marked "on" unless admin actually activated.
            return
        }
        if (!checked && isAdminActive) {
            // Strict Mode blocks turning protections off elsewhere; here the
            // switch is already frozen when active, so this path is the normal
            // "Strict Mode off" removal.
            devicePolicyManager.removeActiveAdmin(deviceAdminComponent)
        }
        StrictOptionsStore.setDisableUninstall(this, checked && isAdminActive)
        refreshUninstallState()
    }
}
