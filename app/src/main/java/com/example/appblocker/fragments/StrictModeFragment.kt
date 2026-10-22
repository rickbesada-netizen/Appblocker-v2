package com.example.appblocker.fragments

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.appblocker.R
import com.example.appblocker.ScheduleFormat
import com.example.appblocker.SelectOptionsActivity
import com.example.appblocker.StrictModeStore
import com.example.appblocker.StrictOptionsStore
import com.example.appblocker.UnlockChecker
import com.example.appblocker.UnlockMethod
import com.example.appblocker.UnlockMethodActivity
import com.example.appblocker.UnlockMethodStore
import com.example.appblocker.WeeklyWindowStore
import com.google.android.material.button.MaterialButton
import java.util.concurrent.TimeUnit

class StrictModeFragment : Fragment() {

    private val handler = Handler(Looper.getMainLooper())
    private var pinVerifiedForThisAttempt = false
    private var cooldownTicker: Runnable? = null
    private var clockTicker: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_strict_mode, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialButton>(R.id.unlockMethodButton).setOnClickListener {
            startActivity(Intent(requireContext(), UnlockMethodActivity::class.java))
        }
        view.findViewById<MaterialButton>(R.id.advancedOptionsButton).setOnClickListener {
            startActivity(Intent(requireContext(), SelectOptionsActivity::class.java))
        }
        view.findViewById<MaterialButton>(R.id.toggleStrictModeButton).setOnClickListener {
            onToggleClicked(view)
        }
        view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.strictSwitch)
            .setOnClickListener { onToggleClicked(view) }

        setupWeeklyWindow(view)
        renderState(view)
    }

    // ----- Weekly unlock window -----
    private var wwStartValue = "03:00"

    private fun setupWeeklyWindow(view: View) {
        val context = requireContext()
        val days = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
        val spinner = view.findViewById<android.widget.Spinner>(R.id.wwDay)
        spinner.adapter = android.widget.ArrayAdapter(
            context, android.R.layout.simple_spinner_dropdown_item, days
        )
        spinner.setSelection(WeeklyWindowStore.day(context))

        wwStartValue = WeeklyWindowStore.start(context)
        val startBtn = view.findViewById<MaterialButton>(R.id.wwStart)
        startBtn.text = wwStartValue
        startBtn.setOnClickListener {
            val parts = wwStartValue.split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: 3
            val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
            android.app.TimePickerDialog(context, { _, hh, mm ->
                wwStartValue = String.format("%02d:%02d", hh, mm)
                startBtn.text = wwStartValue
            }, h, m, true).show()
        }

        view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.wwEnabled)
            .isChecked = WeeklyWindowStore.isEnabled(context)
        view.findViewById<EditText>(R.id.wwMinutes).setText(WeeklyWindowStore.minutes(context).toString())

        view.findViewById<MaterialButton>(R.id.wwSave).setOnClickListener { saveWeeklyWindow(view) }
        renderWeekly(view)
    }

    private fun saveWeeklyWindow(view: View) {
        val context = requireContext()
        // Can't change the window while Strict Mode is locked outside the window.
        if (StrictModeStore.isActive(context) && WeeklyWindowStore.isEnabled(context) && !WeeklyWindowStore.within(context)) {
            Toast.makeText(context, "You can only change the weekly window during the window itself.", Toast.LENGTH_LONG).show()
            renderWeekly(view)
            return
        }
        val enabled = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.wwEnabled).isChecked
        val day = view.findViewById<android.widget.Spinner>(R.id.wwDay).selectedItemPosition
        val minutes = view.findViewById<EditText>(R.id.wwMinutes).text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1

        // Don't let turning the window OFF seal a schedule with no free minute.
        if (!enabled) {
            val ranges = ArrayList<com.example.appblocker.TimeRange>()
            for (l in com.example.appblocker.BlockListStore.getAll(context)) if (l.scheduleEnabled) ranges.addAll(l.ranges)
            ranges.addAll(com.example.appblocker.StrictScheduleStore.getRanges(context))
            if (ranges.isNotEmpty() && !com.example.appblocker.ScheduleGuard.leavesWeeklyGap(ranges)) {
                AlertDialog.Builder(context).setTitle("Can't turn the window off")
                    .setMessage("One of your schedules covers the whole day with no free minute, and this window is the only time you could unlock. End that range a minute early (e.g. 00:00-23:58) first, or leave the window on.")
                    .setPositiveButton("OK", null).show()
                view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.wwEnabled).isChecked = true
                return
            }
        }

        WeeklyWindowStore.set(context, enabled, day, wwStartValue, minutes)
        Toast.makeText(context, "Weekly window saved", Toast.LENGTH_SHORT).show()
        renderWeekly(view)
    }

    private fun renderWeekly(view: View) {
        val context = requireContext()
        val status = view.findViewById<TextView>(R.id.wwStatus)
        status.text = if (WeeklyWindowStore.isEnabled(context)) {
            val base = "Locked all week except ${WeeklyWindowStore.dayName(WeeklyWindowStore.day(context))} " +
                "${WeeklyWindowStore.start(context)} for ${WeeklyWindowStore.minutes(context)} min — the only time anything unlocks."
            val next = if (WeeklyWindowStore.within(context)) "🔓 Open now — you can turn things off until it closes."
                       else "🔒 Next opens ${ScheduleFormat.moment(WeeklyWindowStore.nextWindowStart(context), System.currentTimeMillis())}."
            "$base\n$next"
        } else "Off — Strict Mode uses its own unlock method instead."
    }

    /**
     * The single clearest answer to "when does this unlock?" for the active lock.
     * Weekly window wins (it's the guaranteed way out); otherwise the timer.
     * Returns null when there's no fixed future time (e.g. PIN-only).
     */
    private fun unlockWhenText(): String? {
        val context = requireContext()
        if (!StrictModeStore.isActive(context)) return null
        if (WeeklyWindowStore.isEnabled(context)) {
            return if (WeeklyWindowStore.within(context)) "🔓 Unlockable now — weekly window is open"
                   else "🔒 Unlocks ${ScheduleFormat.moment(WeeklyWindowStore.nextWindowStart(context), System.currentTimeMillis())}"
        }
        if (UnlockMethod.TIMER in UnlockMethodStore.getSelectedMethods(context)) {
            val done = UnlockMethodStore.getActivatedAt(context) +
                TimeUnit.MINUTES.toMillis(UnlockMethodStore.getTimerMinutes(context).toLong())
            return if (done > System.currentTimeMillis())
                "🔒 Unlocks ${ScheduleFormat.moment(done, System.currentTimeMillis())}"
            else "🔓 Timer done — you can unlock now"
        }
        return null
    }

    override fun onResume() {
        super.onResume()
        view?.let { renderState(it) }
        startClock()
    }

    override fun onPause() {
        super.onPause()
        clockTicker?.let { handler.removeCallbacks(it) }
    }

    // Ticks every second so the countdown updates live without any tapping.
    private fun startClock() {
        clockTicker?.let { handler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                view?.let {
                    renderTimer(it)
                    // Keep the "Unlocks …" line and weekly status live without tapping.
                    if (StrictModeStore.isActive(requireContext())) {
                        val when_ = unlockWhenText()
                        it.findViewById<TextView>(R.id.lockStatusText).text =
                            if (when_ != null) "Your blockings are locked\n$when_" else "Your blockings are locked"
                    }
                    renderWeekly(it)
                }
                handler.postDelayed(this, 1000)
            }
        }
        clockTicker = r
        handler.post(r)
    }

    private fun renderTimer(view: View) {
        val context = requireContext()
        val card = view.findViewById<LinearLayout>(R.id.timerCard)

        if (!StrictModeStore.isActive(context) ||
            UnlockMethod.TIMER !in UnlockMethodStore.getSelectedMethods(context)
        ) {
            card.visibility = View.GONE
            return
        }

        val activatedAt = UnlockMethodStore.getActivatedAt(context)
        val totalMs = TimeUnit.MINUTES.toMillis(UnlockMethodStore.getTimerMinutes(context).toLong())
        val remainingMs = (activatedAt + totalMs) - System.currentTimeMillis()

        card.visibility = View.VISIBLE
        val addBtn = view.findViewById<MaterialButton>(R.id.addTimeButton)
        addBtn.setOnClickListener { promptAddTime() }
        val clock = view.findViewById<TextView>(R.id.timerClock)
        val label = view.findViewById<TextView>(R.id.timerLabel)
        val sub = view.findViewById<TextView>(R.id.timerSub)

        if (remainingMs <= 0) {
            clock.text = "00:00"
            label.text = "TIMER FINISHED"
            sub.text = "You can unlock now (other unlock methods may still apply)."
        } else {
            clock.text = formatClock(remainingMs)
            label.text = "TIME UNTIL YOU CAN UNLOCK"
            val done = System.currentTimeMillis() + remainingMs
            sub.text = "Unlocks at ${com.example.appblocker.ScheduleFormat.clock(done)}"
        }
    }

    private fun promptAddTime() {
        val extras = listOf(5, 15, 30, 60, 120)
        val labels = extras.map { "+${it} minutes" }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Add time to the timer")
            .setItems(labels) { _, which ->
                UnlockMethodStore.addTimerMinutes(requireContext(), extras[which])
                view?.let { renderTimer(it) }
            }
            .show()
    }

    private fun formatClock(ms: Long): String {
        val total = (ms / 1000) + if (ms % 1000 > 0) 1 else 0
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cooldownTicker?.let { handler.removeCallbacks(it) }
    }

    // ----- Activate / deactivate -----

    private fun onToggleClicked(view: View) {
        val context = requireContext()

        if (!StrictModeStore.isActive(context)) {
            StrictModeStore.setActive(context, true)
            UnlockMethodStore.recordActivatedNow(context)
            renderState(view)
            return
        }

        pinVerifiedForThisAttempt = false
        attemptDeactivate(view)
    }

    private fun attemptDeactivate(view: View) {
        val context = requireContext()

        // Weekly unlock window: if enabled, Strict Mode can ONLY be turned off
        // during that window. It's the guaranteed way out (and grants schedule
        // immunity elsewhere).
        if (WeeklyWindowStore.isEnabled(context) && !WeeklyWindowStore.within(context)) {
            Toast.makeText(
                context,
                "Locked until your weekly window: ${WeeklyWindowStore.dayName(WeeklyWindowStore.day(context))} " +
                    "${WeeklyWindowStore.start(context)} for ${WeeklyWindowStore.minutes(context)} min.",
                Toast.LENGTH_LONG
            ).show()
            syncSwitch(view)
            return
        }

        val selected = UnlockMethodStore.getSelectedMethods(context)

        if (selected.isEmpty()) {
            finalizeDeactivate(view)
            return
        }

        if (UnlockMethod.PIN_CODE in selected && !pinVerifiedForThisAttempt) {
            syncSwitch(view)
            promptForPin(view)
            return
        }

        if (UnlockMethod.COOLDOWN in selected) {
            val startedAt = UnlockMethodStore.getCooldownStartedAt(context)
            val requiredMs = TimeUnit.SECONDS.toMillis(UnlockMethodStore.getCooldownSeconds(context).toLong())

            if (startedAt == 0L) {
                UnlockMethodStore.startCooldown(context)
                tickCooldown(view)
                return
            }

            val remainingMs = requiredMs - (System.currentTimeMillis() - startedAt)
            if (remainingMs > 0) {
                tickCooldown(view)
                return
            }
        }

        val ambientResults = UnlockChecker.checkAmbientMethods(context)
        val unmet = ambientResults.filter { !it.satisfied }
        if (unmet.isNotEmpty()) {
            Toast.makeText(context, unmet.joinToString(" · ") { it.reason }, Toast.LENGTH_LONG).show()
            syncSwitch(view)
            return
        }

        finalizeDeactivate(view)
    }

    // Keep the top switch matching the real state (deactivation can be refused).
    private fun syncSwitch(view: View) {
        val active = StrictModeStore.isActive(requireContext())
        val sw = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.strictSwitch)
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = active
        sw.setOnClickListener { onToggleClicked(view) }
        view.findViewById<TextView>(R.id.strictSwitchStatus).text =
            if (active) "On - blockings are locked" else "Off - tap to turn on"
    }

    private fun tickCooldown(view: View) {
        val context = requireContext()
        val cooldownText = view.findViewById<TextView>(R.id.cooldownText)
        val toggleButton = view.findViewById<MaterialButton>(R.id.toggleStrictModeButton)

        val startedAt = UnlockMethodStore.getCooldownStartedAt(context)
        val requiredMs = TimeUnit.SECONDS.toMillis(UnlockMethodStore.getCooldownSeconds(context).toLong())
        val remainingMs = requiredMs - (System.currentTimeMillis() - startedAt)

        if (remainingMs <= 0) {
            cooldownText.visibility = View.GONE
            toggleButton.isEnabled = true
            attemptDeactivate(view)
            return
        }

        cooldownText.visibility = View.VISIBLE
        cooldownText.text = "Unlocking in ${(remainingMs / 1000) + 1}s..."
        toggleButton.isEnabled = false
        syncSwitch(view)

        val runnable = Runnable { tickCooldown(view) }
        cooldownTicker = runnable
        handler.postDelayed(runnable, 1000)
    }

    private fun finalizeDeactivate(view: View) {
        val context = requireContext()
        StrictModeStore.setActive(context, false)
        UnlockMethodStore.clearCooldown(context)
        pinVerifiedForThisAttempt = false
        view.findViewById<MaterialButton>(R.id.toggleStrictModeButton).isEnabled = true
        view.findViewById<TextView>(R.id.cooldownText).visibility = View.GONE
        renderState(view)
    }

    private fun promptForPin(view: View) {
        val context = requireContext()
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_enter_pin, null)
        val input = dialogView.findViewById<EditText>(R.id.pinInput)

        AlertDialog.Builder(context)
            .setView(dialogView)
            .setPositiveButton("Unlock") { _, _ ->
                if (UnlockMethodStore.checkPin(context, input.text.toString())) {
                    pinVerifiedForThisAttempt = true
                    attemptDeactivate(view)
                } else {
                    Toast.makeText(context, "Wrong PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ----- Rendering -----

    private fun renderState(view: View) {
        val context = requireContext()
        val active = StrictModeStore.isActive(context)
        val icon = view.findViewById<ImageView>(R.id.lockIcon)
        val statusText = view.findViewById<TextView>(R.id.lockStatusText)
        val button = view.findViewById<MaterialButton>(R.id.toggleStrictModeButton)

        val strictSwitch = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.strictSwitch)
        // Reflect state without firing the listener.
        strictSwitch.setOnCheckedChangeListener(null)
        strictSwitch.isChecked = active
        strictSwitch.setOnClickListener { onToggleClicked(view) }
        view.findViewById<TextView>(R.id.strictSwitchStatus).text =
            if (active) "On - blockings are locked" else "Off - tap to turn on"

        if (active) {
            icon.setImageResource(R.drawable.ic_lock_closed_big)
            val when_ = unlockWhenText()
            statusText.text = if (when_ != null) "Your blockings are locked\n$when_" else "Your blockings are locked"
            button.text = "Deactivate Lock"
        } else {
            icon.setImageResource(R.drawable.ic_lock_open_big)
            statusText.text = "Your blockings are unlocked"
            button.text = "Activate Lock"
            view.findViewById<TextView>(R.id.cooldownText).visibility = View.GONE
        }

        renderUnlockMethodSummary(view)
        renderLockedItems(view)
        renderTimer(view)
    }

    private fun renderUnlockMethodSummary(view: View) {
        val selected = UnlockMethodStore.getSelectedMethods(requireContext())
        val button = view.findViewById<MaterialButton>(R.id.unlockMethodButton)
        button.text = if (selected.isEmpty()) {
            "None selected - tap to add"
        } else {
            selected.joinToString(" + ") { it.label }
        }
    }

    private fun renderLockedItems(view: View) {
        val context = requireContext()
        val locked = mutableListOf<String>()
        locked.add("Your blockings")   // always locked while Strict Mode is on
        if (StrictOptionsStore.isDisableUninstall(context)) locked.add("Uninstalling")
        if (StrictOptionsStore.isBlockDeviceSettings(context)) locked.add("Device settings")
        if (StrictOptionsStore.isBlockReinstalledApps(context)) locked.add("Reinstall bypass")
        locked.add("Pausing blocking")

        view.findViewById<TextView>(R.id.lockedItemsText).text =
            if (locked.isEmpty()) "Nothing - open Advanced options to choose" else locked.joinToString("  •  ")
    }
}
