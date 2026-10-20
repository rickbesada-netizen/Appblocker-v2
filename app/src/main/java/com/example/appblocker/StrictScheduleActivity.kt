package com.example.appblocker

import android.app.TimePickerDialog
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

// Weekly schedule for the "Follow schedules" unlock method. While the current
// time is inside any range, Strict Mode stays locked; outside them it can be
// turned off.
class StrictScheduleActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SCOPE = "scope"
    }

    private var scope: String = ""
    private val ranges = mutableListOf<TimeRange>()
    private var originalJson = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_strict_schedule)

        scope = intent.getStringExtra(EXTRA_SCOPE) ?: ""
        ranges.addAll(StrictScheduleStore.getRanges(this, scope))
        if (ranges.isEmpty()) ranges.add(TimeRange())
        originalJson = rangesJson()

        findViewById<ImageButton>(R.id.closeButton).setOnClickListener { leave() }
        findViewById<MaterialButton>(R.id.addRangeButton).setOnClickListener {
            ranges.add(TimeRange())
            render()
        }
        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener { save() }
        render()
    }

    override fun onBackPressed() { leave() }

    private fun rangesJson(): String {
        val arr = org.json.JSONArray()
        ranges.filter { it.days.isNotEmpty() }.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    // Warn before leaving with unsaved edits (this is what locks people out).
    private fun leave() {
        if (rangesJson() == originalJson) { finish(); return }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Unsaved changes")
            .setMessage("Save your schedule changes before leaving?")
            .setPositiveButton("Save") { _, _ -> save() } // save() finishes on success
            .setNegativeButton("Discard") { _, _ -> finish() }
            .setNeutralButton("Keep editing", null)
            .show()
    }

    private fun render() {
        val container = findViewById<LinearLayout>(R.id.rangesContainer)
        container.removeAllViews()
        ranges.forEachIndexed { i, r -> container.addView(buildRangeCard(i, r)) }
    }

    private fun buildRangeCard(index: Int, range: TimeRange): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_range_card)
            setPadding(dp(14), dp(12), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = if (ranges.size == 1) "Days and times" else "Time ${index + 1}"
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (ranges.size > 1) {
            header.addView(TextView(this).apply {
                text = "✕ Remove"
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(8), dp(4), 0, dp(4))
                setOnClickListener { ranges.removeAt(index); render() }
            })
        }
        card.addView(header)

        val daysRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(12))
        }
        ScheduleFormat.WEEK_ORDER.forEachIndexed { i, day ->
            val on = day in range.days
            daysRow.addView(TextView(this).apply {
                text = ScheduleFormat.SHORT_LETTERS[i]
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setBackgroundResource(if (on) R.drawable.bg_day_on else R.drawable.bg_day_off)
                setTextColor(ContextCompat.getColor(context, if (on) R.color.text_primary else R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    marginStart = dp(2); marginEnd = dp(2)
                }
                setOnClickListener {
                    ranges[index] = range.copy(days = if (on) range.days - day else range.days + day)
                    render()
                }
            })
        }
        card.addView(daysRow)

        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        times.addView(timeButton("From  ${ScheduleFormat.time(range.startMinutes)}", dp(5), 0) {
            pickTime(range.startMinutes) { ranges[index] = ranges[index].copy(startMinutes = it); render() }
        })
        times.addView(timeButton("To  ${ScheduleFormat.time(range.endMinutes)}", 0, dp(5)) {
            pickTime(range.endMinutes) { ranges[index] = ranges[index].copy(endMinutes = it); render() }
        })
        card.addView(times)
        return card
    }

    private fun timeButton(label: String, ms: Int, me: Int, onClick: () -> Unit): View =
        TextView(this).apply {
            text = label; gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setBackgroundResource(R.drawable.bg_time_button)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginStart = ms; marginEnd = me
            }
            setOnClickListener { onClick() }
        }

    private fun pickTime(current: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(this, { _, h, m -> onPicked(h * 60 + m) }, current / 60, current % 60, false).show()
    }

    // Returns true if the schedule was saved, false if validation stopped it.
    private fun save(): Boolean {
        val valid = ranges.filter { it.days.isNotEmpty() }
        if (valid.isEmpty()) {
            Toast.makeText(this, "Pick at least one day", Toast.LENGTH_SHORT).show()
            return false
        }
        // Guaranteed free window: a follow-schedules lock must leave a minute to
        // unlock - unless a weekly unlock window is set (that gives immunity).
        if (!WeeklyWindowStore.hasGrace(this)) {
            ScheduleGuard.scheduleError(true, valid)?.let {
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Leave a free minute")
                    .setMessage(it).setPositiveButton("OK", null).show()
                return false
            }
        }
        StrictScheduleStore.setRanges(this, valid, scope)
        Toast.makeText(this, "Schedule saved", Toast.LENGTH_SHORT).show()
        finish()
        return true
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
