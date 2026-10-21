package com.example.appblocker.fragments

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.appblocker.AdultBlockStore
import com.example.appblocker.AppSettings
import com.example.appblocker.BlockDecision
import com.example.appblocker.BlockEngine
import com.example.appblocker.BlockList
import com.example.appblocker.BlockListEditActivity
import com.example.appblocker.BlockListStore
import com.example.appblocker.BlockOverlayManager
import com.example.appblocker.PauseStore
import com.example.appblocker.PermissionUtils
import com.example.appblocker.QuickBlockStore
import com.example.appblocker.R
import com.example.appblocker.ScheduleFormat
import com.example.appblocker.StrictModeStore
import com.example.appblocker.StrictOptionsStore
import com.google.android.material.button.MaterialButton
import java.util.Calendar

class BlockingFragment : Fragment() {

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            view?.let { render(it) }
            handler.postDelayed(this, 20_000L)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_blocking, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialButton>(R.id.fixPermissionButton).setOnClickListener { fixNextPermission() }
        view.findViewById<MaterialButton>(R.id.quickStartStopButton).setOnClickListener { onQuickClicked(view) }
        view.findViewById<MaterialButton>(R.id.quickEditButton).setOnClickListener {
            openEditor(BlockList.QUICK_ID)
        }
        view.findViewById<MaterialButton>(R.id.breakButton).setOnClickListener { onBreakClicked(view) }
        setupAdultFilter(view)
        // A new list can only add blocking, so it's allowed even in Strict Mode.
        view.findViewById<MaterialButton>(R.id.newListButton).setOnClickListener { openEditor(null) }
        view.findViewById<MaterialButton>(R.id.testBlockButton).setOnClickListener {
            if (PermissionUtils.hasOverlayPermission(requireContext())) {
                BlockOverlayManager.show(
                    requireContext(),
                    BlockDecision("This app is blocked", "Preview", "Unblocks at 5:00 pm")
                )
            } else {
                requestOverlayPermission()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    private fun render(view: View) {
        val context = requireContext()
        renderPermissions(view)
        renderQuick(view)
        renderBreak(view)
        renderAdult(view)
        renderLists(view)
        view.findViewById<TextView>(R.id.activeChip).text = "${BlockEngine.blockingLists(context).size} active"
        view.findViewById<TextView>(R.id.listsLockedNote).visibility = if (isLocked()) View.VISIBLE else View.GONE
    }

    // ----- Permissions -----

    private fun renderPermissions(view: View) {
        val context = requireContext()
        val card = view.findViewById<View>(R.id.permissionCard)
        val text = view.findViewById<TextView>(R.id.permissionText)
        val button = view.findViewById<MaterialButton>(R.id.fixPermissionButton)
        when {
            !PermissionUtils.hasAccessibilityPermission(context) -> {
                card.visibility = View.VISIBLE
                text.text = "Step 1 of 2: turn on AppBlocker under Accessibility so it can see which app or website is open."
                button.text = "Open Accessibility settings"
            }
            !PermissionUtils.hasOverlayPermission(context) -> {
                card.visibility = View.VISIBLE
                text.text = "Step 2 of 2: allow AppBlocker to display over other apps so it can show the block screen."
                button.text = "Allow display over apps"
            }
            else -> card.visibility = View.GONE
        }
    }

    private fun fixNextPermission() {
        val context = requireContext()
        if (!PermissionUtils.hasAccessibilityPermission(context)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else if (!PermissionUtils.hasOverlayPermission(context)) {
            requestOverlayPermission()
        }
    }

    private fun permissionsReady(): Boolean {
        val context = requireContext()
        val ok = PermissionUtils.hasAccessibilityPermission(context) && PermissionUtils.hasOverlayPermission(context)
        if (!ok) {
            Toast.makeText(context, "Finish setting up permissions first", Toast.LENGTH_SHORT).show()
            fixNextPermission()
        }
        return ok
    }

    // ----- Quick Block -----

    private fun renderQuick(view: View) {
        val context = requireContext()
        val quick = BlockListStore.quickList(context)
        view.findViewById<TextView>(R.id.quickSummary).text =
            if (quick.isEmpty()) "Pick apps and websites to block instantly." else ScheduleFormat.contents(quick)

        val status = view.findViewById<TextView>(R.id.quickStatus)
        val button = view.findViewById<MaterialButton>(R.id.quickStartStopButton)
        if (QuickBlockStore.isRunning(context)) {
            val until = QuickBlockStore.untilMillis(context)
            status.text = if (until == 0L) {
                "● Blocking until you stop it"
            } else {
                val minsLeft = ((until - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(1)
                "● Blocking until ${ScheduleFormat.clock(until)} (${ScheduleFormat.duration(minsLeft.toInt())} left)"
            }
            status.setTextColor(ContextCompat.getColor(context, R.color.accent_green))
            button.text = "Stop Quick Block"
        } else {
            status.text = "Not running"
            status.setTextColor(ContextCompat.getColor(context, R.color.text_disabled))
            button.text = if (quick.isEmpty()) "Choose what to block" else "Start Quick Block"
        }
    }

    private fun onQuickClicked(view: View) {
        val context = requireContext()
        if (QuickBlockStore.isRunning(context)) {
            if (StrictModeStore.isActive(context)) {
                toastLocked()
                return
            }
            QuickBlockStore.stop(context)
            render(view)
            return
        }
        if (BlockListStore.quickList(context).isEmpty()) {
            openEditor(BlockList.QUICK_ID)
            return
        }
        if (!permissionsReady()) return

        val options = arrayOf("15 minutes", "30 minutes", "1 hour", "2 hours", "4 hours", "Until a set time…", "Until I stop it")
        val minutes = listOf(15, 30, 60, 120, 240)
        AlertDialog.Builder(context)
            .setTitle("Block for how long?")
            .setItems(options) { _, which ->
                when {
                    which < minutes.size -> startQuick(view, System.currentTimeMillis() + minutes[which] * 60_000L)
                    which == 5 -> pickQuickEndTime(view)
                    else -> startQuick(view, 0L)
                }
            }
            .show()
    }

    private fun pickQuickEndTime(view: View) {
        val now = Calendar.getInstance()
        TimePickerDialog(
            requireContext(),
            { _, hour, minute ->
                val end = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
                }
                startQuick(view, end.timeInMillis)
            },
            (now.get(Calendar.HOUR_OF_DAY) + 1) % 24,
            0,
            false
        ).show()
    }

    private fun startQuick(view: View, untilMillis: Long) {
        QuickBlockStore.start(requireContext(), untilMillis)
        render(view)
    }

    // ----- Adult content filter -----

    private fun setupAdultFilter(view: View) {
        val context = requireContext()
        val mainSwitch = view.findViewById<SwitchCompat>(R.id.adultSwitch)
        val subSwitch = view.findViewById<SwitchCompat>(R.id.adultUnsupportedSwitch)

        mainSwitch.setOnCheckedChangeListener { _, isChecked ->
            // Strict Mode: can turn the filter ON, never OFF.
            if (!isChecked && StrictModeStore.freezeOn(context, true)) {
                mainSwitch.isChecked = true
                toastLocked()
                return@setOnCheckedChangeListener
            }
            if (isChecked && !permissionsReady()) {
                mainSwitch.isChecked = false
                return@setOnCheckedChangeListener
            }
            AdultBlockStore.setEnabled(context, isChecked)
            renderAdult(view)
        }
        subSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked && StrictModeStore.freezeOn(context, AdultBlockStore.isEnabled(context))) {
                subSwitch.isChecked = true
                toastLocked()
                return@setOnCheckedChangeListener
            }
            AdultBlockStore.setBlockUnsupportedBrowsers(context, isChecked)
        }
    }

    private fun renderAdult(view: View) {
        val context = requireContext()
        val on = AdultBlockStore.isEnabled(context)
        val mainSwitch = view.findViewById<SwitchCompat>(R.id.adultSwitch)
        val subSwitch = view.findViewById<SwitchCompat>(R.id.adultUnsupportedSwitch)
        mainSwitch.isChecked = on
        // Frozen only if it was already on when Strict Mode began.
        mainSwitch.isEnabled = !StrictModeStore.freezeOn(context, on)
        view.findViewById<View>(R.id.adultUnsupportedRow).visibility = if (on) View.VISIBLE else View.GONE
        subSwitch.isChecked = AdultBlockStore.blockUnsupportedBrowsers(context)
        subSwitch.isEnabled = !StrictModeStore.freezeOn(context, on && subSwitch.isChecked)
    }

    // ----- Breaks -----

    private fun renderBreak(view: View) {
        val context = requireContext()
        val text = view.findViewById<TextView>(R.id.breakText)
        val button = view.findViewById<MaterialButton>(R.id.breakButton)
        val now = System.currentTimeMillis()
        when {
            PauseStore.isPaused(context, now) -> {
                text.text = "On a break - blocking resumes at ${ScheduleFormat.clock(PauseStore.pausedUntil(context))}"
                button.text = "Resume now"
                button.isEnabled = true
            }
            StrictModeStore.isActive(context) -> {
                text.text = "Breaks are disabled while Strict Mode is on"
                button.text = "Break"
                button.isEnabled = false
            }
            AppSettings.breaksPerDay(context) == 0 -> {
                text.text = "Breaks are turned off in Settings"
                button.text = "Break"
                button.isEnabled = false
            }
            else -> {
                val left = PauseStore.breaksLeft(context, now)
                text.text = "Pause all blocking for ${AppSettings.breakMinutes(context)} min · $left left today"
                button.text = "Take a break"
                button.isEnabled = left > 0
            }
        }
    }

    private fun onBreakClicked(view: View) {
        val context = requireContext()
        if (PauseStore.isPaused(context)) {
            PauseStore.endPause(context)
        } else if (!PauseStore.startPause(context)) {
            Toast.makeText(context, "No breaks available", Toast.LENGTH_SHORT).show()
        }
        render(view)
    }

    // ----- Lists -----

    private fun renderLists(view: View) {
        val context = requireContext()
        val container = view.findViewById<LinearLayout>(R.id.listsContainer)
        container.removeAllViews()

        val lists = BlockListStore.userLists(context)
        view.findViewById<TextView>(R.id.emptyListsText).visibility = if (lists.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(context)
        val locked = isLocked()
        for (list in lists) {
            val card = inflater.inflate(R.layout.item_block_list, container, false)
            card.findViewById<TextView>(R.id.listName).text = list.displayName()
            card.findViewById<TextView>(R.id.listContents).text = ScheduleFormat.contents(list)
            card.findViewById<TextView>(R.id.listSchedule).text = ScheduleFormat.summary(list)
            bindStatus(card.findViewById(R.id.listStatus), list)

            val switch = card.findViewById<SwitchCompat>(R.id.listSwitch)
            switch.isChecked = list.enabled
            // Strict Mode: a list that's off can be switched on, never off.
            switch.isEnabled = !locked || !list.enabled
            switch.setOnCheckedChangeListener { _, isChecked ->
                if (!isChecked && isLocked()) {
                    switch.isChecked = true
                    toastLocked()
                    return@setOnCheckedChangeListener
                }
                if (isChecked && !permissionsReady()) {
                    switch.isChecked = false
                    return@setOnCheckedChangeListener
                }
                BlockListStore.setEnabled(context, list.id, isChecked)
                render(view)
            }
            // Even locked, open the editor - it opens in add-only mode.
            card.setOnClickListener { openEditor(list.id) }
            container.addView(card)
        }
    }

    private fun bindStatus(status: TextView, list: BlockList) {
        val context = requireContext()
        fun set(text: String, color: Int) {
            status.text = text
            status.setTextColor(ContextCompat.getColor(context, color))
        }
        val now = System.currentTimeMillis()
        when {
            !list.enabled -> set("Off", R.color.text_disabled)
            PauseStore.isPaused(context, now) -> set("Paused for a break", R.color.text_secondary)
            BlockEngine.isListBlocking(context, list, now) -> {
                val until = BlockEngine.untilText(context, list, now)
                set("● Blocking now" + (until?.let { " · ${it.replaceFirstChar { c -> c.lowercase() }}" } ?: ""), R.color.accent_green)
            }
            list.isWithinSchedule() && list.hasLimits() -> {
                val (ms, opens) = BlockEngine.usageFor(context, list, now)
                val parts = mutableListOf<String>()
                if (list.usageLimitMinutes > 0) parts.add("${ScheduleFormat.durationMs(ms)} of ${ScheduleFormat.duration(list.usageLimitMinutes)}")
                if (list.launchLimit > 0) parts.add("$opens of ${list.launchLimit} opens")
                set("Used today: " + parts.joinToString(" · "), R.color.accent_blue)
            }
            else -> set("Scheduled - not active right now", R.color.accent_blue)
        }
    }

    // ----- Helpers -----

    private fun openEditor(listId: String?) {
        val intent = Intent(requireContext(), BlockListEditActivity::class.java)
        if (listId != null) intent.putExtra(BlockListEditActivity.EXTRA_LIST_ID, listId)
        startActivity(intent)
    }

    // Central Strict Mode: when it's on, your blockings are locked - period.
    private fun isLocked(): Boolean = StrictModeStore.isActive(requireContext())

    private fun toastLocked() {
        Toast.makeText(requireContext(), "Strict Mode: you can only make blocking stricter", Toast.LENGTH_SHORT).show()
    }

    private fun requestOverlayPermission() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${requireContext().packageName}"))
        )
    }
}
