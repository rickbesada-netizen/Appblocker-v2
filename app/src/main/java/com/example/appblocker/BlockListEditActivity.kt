package com.example.appblocker

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.util.UUID

// Create / edit a block list (or the Quick Block list): mode, apps, websites,
// keywords, notifications, schedule and daily limits.
class BlockListEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LIST_ID = "list_id"
        private const val S_ID = "s_id"
        private const val S_ALLOW = "s_allow"
        private const val S_APPS = "s_apps"
        private const val S_SITES = "s_sites"
        private const val S_KEYWORDS = "s_keywords"
        private const val S_NOTIF = "s_notif"
        private const val S_SCHEDULE = "s_schedule"
        private const val S_RANGES = "s_ranges"
        private const val S_USAGE = "s_usage"
        private const val S_LAUNCH = "s_launch"
    }

    private var existing: BlockList? = null
    private var isQuick = false
    // Strict Mode add-only mode: you may add apps/sites/keywords and widen the
    // day selection, but not remove anything, shrink the schedule, or delete.
    private var addOnly = false
    private var original: BlockList? = null
    private lateinit var listId: String
    private var allowMode = false
    private val apps = LinkedHashSet<String>()
    private val websites = LinkedHashSet<String>()
    private val keywords = LinkedHashSet<String>()
    private var blockNotifications = false
    private var scheduleEnabled = false
    private val ranges = mutableListOf<TimeRange>()
    private var usageLimit = 0
    private var launchLimit = 0
    private var originalSnapshot = ""

    private lateinit var nameInput: EditText
    private lateinit var websiteInput: EditText
    private lateinit var keywordInput: EditText

    private val installedApps: List<AppInfo> by lazy { AppCatalog.launcherApps(this) }

    private val pickApps = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        apps.clear()
        apps.addAll(data.getStringArrayListExtra(ManageAppsActivity.EXTRA_SELECTED) ?: arrayListOf())
        websites.addAll(data.getStringArrayListExtra(ManageAppsActivity.EXTRA_ADDED_WEBSITES) ?: arrayListOf())
        renderAll()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requestedId = intent.getStringExtra(EXTRA_LIST_ID)
        // Central Strict Mode locks editing (add-only) whenever it's active.
        val locked = StrictModeStore.isActive(this)
        val editingExisting = requestedId != null &&
            (requestedId != BlockList.QUICK_ID || QuickBlockStore.isRunning(this))
        // Strict Mode: editing an existing list is allowed but ADD-ONLY -
        // you can make it block more, never less.
        addOnly = locked && editingExisting

        setContentView(R.layout.activity_block_list_edit)

        isQuick = requestedId == BlockList.QUICK_ID
        existing = when {
            isQuick -> BlockListStore.quickList(this)
            requestedId != null -> BlockListStore.get(this, requestedId)
            else -> null
        }
        loadState(savedInstanceState)
        original = existing

        nameInput = findViewById(R.id.nameInput)
        websiteInput = findViewById(R.id.websiteInput)
        keywordInput = findViewById(R.id.keywordInput)
        if (savedInstanceState == null) nameInput.setText(existing?.name ?: "")

        findViewById<TextView>(R.id.editorTitle).text = when {
            isQuick -> "Quick Block"
            existing == null -> "New block list"
            else -> "Edit block list"
        }
        findViewById<ImageButton>(R.id.closeButton).setOnClickListener { leave() }
        val deleteButton = findViewById<ImageButton>(R.id.deleteButton)
        deleteButton.visibility = if (existing != null && !isQuick && !addOnly) View.VISIBLE else View.GONE
        deleteButton.setOnClickListener { confirmDelete() }

        if (addOnly) {
            findViewById<TextView>(R.id.editorTitle).text = "Add to block list"
            findViewById<TextView>(R.id.addOnlyBanner).visibility = View.VISIBLE
        }

        // Quick Block has no name, schedule or limits - it runs for a chosen time.
        findViewById<View>(R.id.nameSection).visibility = if (isQuick) View.GONE else View.VISIBLE
        findViewById<View>(R.id.timingSection).visibility = if (isQuick) View.GONE else View.VISIBLE

        setupMode()
        findViewById<MaterialButton>(R.id.chooseAppsButton).setOnClickListener { openAppPicker() }
        setupAddInput(websiteInput, R.id.addWebsiteButton) { addWebsite() }
        setupAddInput(keywordInput, R.id.addKeywordButton) { addKeyword() }
        setupNotifications()
        setupSchedule()
        setupLimits()
        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener { save() }

        renderAll()
        originalSnapshot = currentList().toJson().toString()
    }

    override fun onBackPressed() { leave() }

    // Warn before leaving with unsaved edits.
    private fun leave() {
        val changed = currentList().toJson().toString() != originalSnapshot
        if (!changed) { finish(); return }
        AlertDialog.Builder(this)
            .setTitle("Unsaved changes")
            .setMessage("Save your changes to this list before leaving?")
            .setPositiveButton("Save") { _, _ -> save() } // save() finishes on success
            .setNegativeButton("Discard") { _, _ -> finish() }
            .setNeutralButton("Keep editing", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Came back from granting notification access?
        if (blockNotifications && !PermissionUtils.hasNotificationAccess(this)) {
            findViewById<TextView>(R.id.notificationsNote).text =
                "⚠ Notification access is off - tap the switch again to grant it."
        } else {
            renderNotificationsNote()
        }
    }

    // ----- State -----

    private fun loadState(saved: Bundle?) {
        if (saved != null) {
            listId = saved.getString(S_ID) ?: UUID.randomUUID().toString()
            allowMode = saved.getBoolean(S_ALLOW)
            apps.addAll(saved.getStringArrayList(S_APPS) ?: arrayListOf())
            websites.addAll(saved.getStringArrayList(S_SITES) ?: arrayListOf())
            keywords.addAll(saved.getStringArrayList(S_KEYWORDS) ?: arrayListOf())
            blockNotifications = saved.getBoolean(S_NOTIF)
            scheduleEnabled = saved.getBoolean(S_SCHEDULE)
            saved.getString(S_RANGES)?.let { json ->
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) ranges.add(TimeRange.fromJson(arr.getJSONObject(i)))
            }
            usageLimit = saved.getInt(S_USAGE)
            launchLimit = saved.getInt(S_LAUNCH)
            return
        }
        val list = existing ?: BlockList()
        listId = list.id
        allowMode = list.allowMode
        apps.addAll(list.apps)
        websites.addAll(list.websites)
        keywords.addAll(list.keywords)
        blockNotifications = list.blockNotifications
        scheduleEnabled = list.scheduleEnabled
        ranges.addAll(list.ranges)
        usageLimit = list.usageLimitMinutes
        launchLimit = list.launchLimit
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(S_ID, listId)
        outState.putBoolean(S_ALLOW, allowMode)
        outState.putStringArrayList(S_APPS, ArrayList(apps))
        outState.putStringArrayList(S_SITES, ArrayList(websites))
        outState.putStringArrayList(S_KEYWORDS, ArrayList(keywords))
        outState.putBoolean(S_NOTIF, blockNotifications)
        outState.putBoolean(S_SCHEDULE, scheduleEnabled)
        outState.putString(S_RANGES, org.json.JSONArray().apply { ranges.forEach { put(it.toJson()) } }.toString())
        outState.putInt(S_USAGE, usageLimit)
        outState.putInt(S_LAUNCH, launchLimit)
    }

    private fun renderAll() {
        renderMode()
        renderApps()
        renderRemovableRows(R.id.websitesContainer, websites)
        renderRemovableRows(R.id.keywordsContainer, keywords)
        renderNotificationsNote()
        renderSchedule()
        renderLimits()
    }

    // ----- Mode -----

    private fun setupMode() {
        val group = findViewById<RadioGroup>(R.id.modeGroup)
        group.check(if (allowMode) R.id.modeAllow else R.id.modeBlock)
        group.setOnCheckedChangeListener { _, checkedId ->
            if (addOnly) {
                group.check(if (allowMode) R.id.modeAllow else R.id.modeBlock)
                toastAddOnly()
                return@setOnCheckedChangeListener
            }
            allowMode = checkedId == R.id.modeAllow
            renderAll()
        }
    }

    private fun renderMode() {
        findViewById<TextView>(R.id.appsSectionTitle).text = if (allowMode) "Allowed apps" else "Apps"
        findViewById<TextView>(R.id.sitesSectionTitle).text = if (allowMode) "Allowed websites" else "Websites"
        findViewById<TextView>(R.id.sitesNote).text = if (allowMode) {
            "Leave empty to allow all websites in allowed browsers, or list the only sites that may be opened."
        } else {
            "Covers the site and its subdomains (m.youtube.com etc.) in Chrome, Samsung Internet, Firefox, Edge, Brave, Opera, DuckDuckGo and more."
        }
    }

    // ----- Apps -----

    private fun openAppPicker() {
        val intent = Intent(this, ManageAppsActivity::class.java).apply {
            putStringArrayListExtra(ManageAppsActivity.EXTRA_SELECTED, ArrayList(apps))
            putStringArrayListExtra(ManageAppsActivity.EXTRA_WEBSITES, ArrayList(websites))
            putExtra(ManageAppsActivity.EXTRA_LIST_NAME, if (isQuick) "Quick Block" else nameInput.text.toString())
            putExtra(ManageAppsActivity.EXTRA_ALLOW_MODE, allowMode)
            putExtra(ManageAppsActivity.EXTRA_ADD_ONLY, addOnly)
        }
        pickApps.launch(intent)
    }

    private fun renderApps() {
        val summary = findViewById<TextView>(R.id.appsSummary)
        val button = findViewById<MaterialButton>(R.id.chooseAppsButton)
        if (apps.isEmpty()) {
            summary.text = if (allowMode) "No apps allowed - every app except essentials is blocked" else "No apps yet"
            button.text = "Choose apps"
        } else {
            val labels = apps.map { AppCatalog.label(this, it) }.sortedBy { it.lowercase() }
            val shown = labels.take(6).joinToString(", ")
            val more = if (labels.size > 6) " +${labels.size - 6} more" else ""
            summary.text = "${apps.size} app${if (apps.size == 1) "" else "s"}: $shown$more"
            button.text = "Change apps"
        }
    }

    // ----- Websites & keywords -----

    private fun setupAddInput(input: EditText, buttonId: Int, onAdd: () -> Unit) {
        findViewById<MaterialButton>(buttonId).setOnClickListener { onAdd() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onAdd()
                true
            } else {
                false
            }
        }
    }

    private fun addWebsite() {
        val domain = WebsiteUtils.normalizeDomain(websiteInput.text.toString())
        if (domain == null) {
            Toast.makeText(this, "Enter a website like youtube.com", Toast.LENGTH_SHORT).show()
            return
        }
        websiteInput.setText("")
        if (!websites.add(domain)) {
            Toast.makeText(this, "$domain is already on this list", Toast.LENGTH_SHORT).show()
            return
        }
        renderRemovableRows(R.id.websitesContainer, websites)
        if (!allowMode) suggestAppsForWebsite(domain)
    }

    private fun addKeyword() {
        val keyword = keywordInput.text.toString().trim().lowercase()
        if (keyword.length < 2) {
            Toast.makeText(this, "Enter at least 2 characters", Toast.LENGTH_SHORT).show()
            return
        }
        keywordInput.setText("")
        keywords.add(keyword)
        renderRemovableRows(R.id.keywordsContainer, keywords)
    }

    // Blocking youtube.com? Offer to block the YouTube app too.
    private fun suggestAppsForWebsite(domain: String) {
        val known = LinkedTargets.packagesForDomain(domain).filter { AppCatalog.isInstalled(this, it) }
        val core = WebsiteUtils.coreName(domain)
        val byName = installedApps.filter { AppCatalog.simplify(it.label) == core }.map { it.packageName }
        val candidates = (known + byName).distinct().filter { it !in apps }
        if (candidates.isEmpty()) return

        val labels = candidates.map { AppCatalog.label(this, it) }
        val appWord = if (candidates.size == 1) "app" else "apps"
        AlertDialog.Builder(this)
            .setTitle("Also block the ${labels.joinToString(" & ")} $appWord?")
            .setMessage("Otherwise $domain can still be opened through the $appWord.")
            .setPositiveButton("Block $appWord too") { _, _ ->
                apps.addAll(candidates)
                renderApps()
            }
            .setNegativeButton("Just the website", null)
            .show()
    }

    private fun renderRemovableRows(containerId: Int, items: MutableSet<String>) {
        val container = findViewById<LinearLayout>(containerId)
        container.removeAllViews()
        for (item in items.toList()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val label = TextView(this).apply {
                text = item
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val remove = TextView(this).apply {
                text = "✕"
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(dp(12), dp(4), dp(4), dp(4))
                contentDescription = "Remove $item"
                setOnClickListener {
                    if (addOnly) { toastAddOnly(); return@setOnClickListener }
                    items.remove(item)
                    renderRemovableRows(containerId, items)
                }
            }
            row.addView(label)
            row.addView(remove)
            container.addView(row)
        }
    }

    // ----- Notifications -----

    private fun setupNotifications() {
        val switch = findViewById<SwitchCompat>(R.id.notificationsSwitch)
        switch.isChecked = blockNotifications
        switch.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked && addOnly) { switch.isChecked = true; toastAddOnly(); return@setOnCheckedChangeListener }
            blockNotifications = isChecked
            renderNotificationsNote()
            if (isChecked && !PermissionUtils.hasNotificationAccess(this)) {
                AlertDialog.Builder(this)
                    .setTitle("Allow notification access")
                    .setMessage("To silence notifications from blocked apps, turn on AppBlocker under Notification access on the next screen.")
                    .setPositiveButton("Open settings") { _, _ ->
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }

    private fun renderNotificationsNote() {
        findViewById<TextView>(R.id.notificationsNote).text = if (allowMode) {
            "Silences notifications from every app that isn't allowed, while the list is blocking."
        } else {
            "Silences notifications from these apps while the list is blocking."
        }
    }

    // ----- Schedule -----

    private fun setupSchedule() {
        if (ranges.isEmpty()) ranges.add(TimeRange())
        val group = findViewById<RadioGroup>(R.id.whenGroup)
        group.check(if (scheduleEnabled) R.id.whenScheduled else R.id.whenAlways)
        group.setOnCheckedChangeListener { _, checkedId ->
            val wantScheduled = checkedId == R.id.whenScheduled
            // Add-only: going from "all the time" to a schedule reduces blocking.
            if (addOnly && wantScheduled && !scheduleEnabled) {
                group.check(R.id.whenAlways)
                toastAddOnly()
                return@setOnCheckedChangeListener
            }
            scheduleEnabled = wantScheduled
            renderSchedule()
        }
        findViewById<MaterialButton>(R.id.addRangeButton).setOnClickListener {
            ranges.add(TimeRange())
            renderSchedule()
        }
    }

    private fun renderSchedule() {
        findViewById<View>(R.id.scheduleDetails).visibility = if (scheduleEnabled) View.VISIBLE else View.GONE
        val container = findViewById<LinearLayout>(R.id.rangesContainer)
        container.removeAllViews()
        ranges.forEachIndexed { index, range -> container.addView(buildRangeCard(index, range)) }
        renderLimits()
    }

    // One card per time range: header, day circles, From / To buttons.
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
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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
                setOnClickListener {
                    if (addOnly) { toastAddOnly(); return@setOnClickListener }
                    ranges.removeAt(index)
                    renderSchedule()
                }
            })
        }
        card.addView(header)

        val daysRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(12))
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
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
                setOnClickListener {
                    if (on && addOnly) { toastAddOnly(); return@setOnClickListener }
                    val newDays = if (on) range.days - day else range.days + day
                    ranges[index] = range.copy(days = newDays)
                    renderSchedule()
                }
            })
        }
        card.addView(daysRow)

        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        times.addView(timeButton("From  ${ScheduleFormat.time(range.startMinutes)}", marginEnd = dp(5)) {
            pickTime(range.startMinutes) { ranges[index] = ranges[index].copy(startMinutes = it); renderSchedule() }
        })
        times.addView(timeButton("To  ${ScheduleFormat.time(range.endMinutes)}", marginStart = dp(5)) {
            pickTime(range.endMinutes) { ranges[index] = ranges[index].copy(endMinutes = it); renderSchedule() }
        })
        card.addView(times)

        if (range.days.isEmpty()) {
            card.addView(TextView(this).apply {
                text = "Pick at least one day"
                setTextColor(ContextCompat.getColor(context, R.color.accent_blue))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(8), 0, 0)
            })
        }
        return card
    }

    private fun timeButton(label: String, marginStart: Int = 0, marginEnd: Int = 0, onClick: () -> Unit): View =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setBackgroundResource(R.drawable.bg_time_button)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                this.marginStart = marginStart
                this.marginEnd = marginEnd
            }
            setOnClickListener { onClick() }
        }

    private fun pickTime(currentMinutes: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(
            this,
            { _, hour, minute -> onPicked(hour * 60 + minute) },
            currentMinutes / 60,
            currentMinutes % 60,
            false
        ).show()
    }

    // ----- Limits -----

    private fun setupLimits() {
        val usageSwitch = findViewById<SwitchCompat>(R.id.usageLimitSwitch)
        usageSwitch.isChecked = usageLimit > 0
        usageSwitch.setOnCheckedChangeListener { _, isChecked ->
            // A usage limit LETS you use the app up to the limit, so removing it
            // makes the list stricter and adding it makes it looser: in add-only,
            // only allow turning a limit OFF, never on.
            if (isChecked && addOnly) { usageSwitch.isChecked = false; toastAddOnly(); return@setOnCheckedChangeListener }
            usageLimit = if (isChecked) (if (usageLimit > 0) usageLimit else 30) else 0
            renderLimits()
        }
        findViewById<MaterialButton>(R.id.usageLimitButton).setOnClickListener {
            val choices = ScheduleFormat.USAGE_LIMIT_CHOICES
            AlertDialog.Builder(this)
                .setTitle("Daily usage limit")
                .setItems(choices.map { ScheduleFormat.duration(it) }.toTypedArray()) { _, which ->
                    if (addOnly && usageLimit > 0 && choices[which] > usageLimit) { toastAddOnly(); return@setItems }
                    usageLimit = choices[which]
                    renderLimits()
                }
                .show()
        }

        val launchSwitch = findViewById<SwitchCompat>(R.id.launchLimitSwitch)
        launchSwitch.isChecked = launchLimit > 0
        launchSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && addOnly) { launchSwitch.isChecked = false; toastAddOnly(); return@setOnCheckedChangeListener }
            launchLimit = if (isChecked) (if (launchLimit > 0) launchLimit else 10) else 0
            renderLimits()
        }
        findViewById<MaterialButton>(R.id.launchLimitButton).setOnClickListener {
            val choices = ScheduleFormat.LAUNCH_LIMIT_CHOICES
            AlertDialog.Builder(this)
                .setTitle("Daily opens limit")
                .setItems(choices.map { if (it == 1) "1 open" else "$it opens" }.toTypedArray()) { _, which ->
                    if (addOnly && launchLimit > 0 && choices[which] > launchLimit) { toastAddOnly(); return@setItems }
                    launchLimit = choices[which]
                    renderLimits()
                }
                .show()
        }
    }

    private fun renderLimits() {
        val usageButton = findViewById<MaterialButton>(R.id.usageLimitButton)
        usageButton.visibility = if (usageLimit > 0) View.VISIBLE else View.GONE
        usageButton.text = "${ScheduleFormat.duration(usageLimit)} a day"

        val launchButton = findViewById<MaterialButton>(R.id.launchLimitButton)
        launchButton.visibility = if (launchLimit > 0) View.VISIBLE else View.GONE
        launchButton.text = "$launchLimit opens a day"

        // Show today's progress against the limits for the current selection.
        val progress = findViewById<TextView>(R.id.limitsProgress)
        if (usageLimit == 0 && launchLimit == 0) {
            progress.visibility = View.GONE
            return
        }
        val preview = currentList()
        val (ms, opens) = BlockEngine.usageFor(this, preview)
        val parts = mutableListOf<String>()
        if (usageLimit > 0) parts.add("${ScheduleFormat.durationMs(ms)} of ${ScheduleFormat.duration(usageLimit)} used")
        if (launchLimit > 0) parts.add("$opens of $launchLimit opens")
        progress.text = "Today: " + parts.joinToString(" · ")
        progress.visibility = View.VISIBLE
    }

    // ----- Save / delete -----

    private fun currentList(): BlockList = BlockList(
        id = listId,
        name = if (isQuick) "Quick Block" else nameInput.text.toString().trim().ifBlank { "Block list" },
        enabled = existing?.enabled ?: true,
        allowMode = allowMode,
        apps = apps.toSet(),
        websites = websites.toSet(),
        keywords = keywords.toSet(),
        scheduleEnabled = !isQuick && scheduleEnabled,
        ranges = ranges.toList(),
        usageLimitMinutes = if (isQuick) 0 else usageLimit,
        launchLimit = if (isQuick) 0 else launchLimit,
        blockNotifications = blockNotifications
    )

    // Returns true if the list was saved, false if validation stopped it.
    private fun save(): Boolean {
        // Anything typed but not added yet - add it.
        WebsiteUtils.normalizeDomain(websiteInput.text.toString())?.let { websites.add(it) }
        keywordInput.text.toString().trim().lowercase().takeIf { it.length >= 2 }?.let { keywords.add(it) }

        val list = currentList()
        if (list.isEmpty()) {
            Toast.makeText(this, "Add at least one app, website or keyword", Toast.LENGTH_SHORT).show()
            return false
        }
        // Final backstop for add-only: the new list must still block everything
        // the old one did.
        original?.let { o ->
            if (addOnly && weakensAny(o, list)) {
                Toast.makeText(this, "Strict Mode: you can only add to this list, not remove from it", Toast.LENGTH_LONG).show()
                return false
            }
        }
        if (list.scheduleEnabled && ranges.any { it.days.isEmpty() }) {
            Toast.makeText(this, "Every time range needs at least one day", Toast.LENGTH_SHORT).show()
            return false
        }
        // Guaranteed free window: never let a schedule seal the whole week -
        // UNLESS a weekly unlock window is set, which gives immunity (24/7 allowed).
        if (!WeeklyWindowStore.hasGrace(this)) {
            ScheduleGuard.scheduleError(list.scheduleEnabled, list.ranges)?.let {
                AlertDialog.Builder(this).setTitle("Leave a free minute").setMessage(it)
                    .setPositiveButton("OK", null).show()
                return false
            }
        }
        BlockListStore.upsert(this, list)
        Toast.makeText(this, "Saved \"${list.displayName()}\"", Toast.LENGTH_SHORT).show()
        finish()
        return true
    }

    private fun confirmDelete() {
        val list = existing ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete \"${list.displayName()}\"?")
            .setMessage("Its apps, websites and keywords will no longer be blocked by this list.")
            .setPositiveButton("Delete") { _, _ ->
                BlockListStore.delete(this, list.id)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toastAddOnly() {
        Toast.makeText(this, "Strict Mode: you can add, but not remove", Toast.LENGTH_SHORT).show()
    }

    // True if `now` blocks anything less than `before` did.
    private fun weakensAny(before: BlockList, now: BlockList): Boolean {
        if (before.allowMode != now.allowMode) return true
        // In block mode, dropping an app/site/keyword weakens it. In allow mode,
        // the app set is an allow-list, so ADDING an app weakens it - guard both.
        val appsWeakened = if (before.allowMode) !before.apps.containsAll(now.apps)
        else !now.apps.containsAll(before.apps)
        if (appsWeakened) return true
        if (!now.websites.containsAll(before.websites)) return true
        if (!now.keywords.containsAll(before.keywords)) return true
        if (before.blockNotifications && !now.blockNotifications) return true
        // A limit that got larger, or was added where there was none, is weaker.
        if (limitWeaker(before.usageLimitMinutes, now.usageLimitMinutes)) return true
        if (limitWeaker(before.launchLimit, now.launchLimit)) return true
        // Schedule: every previously-blocked (day,minute) must still be blocked.
        if (scheduleWeaker(before, now)) return true
        return false
    }

    // 0 = no limit = strictest. Larger limit = weaker. Adding a limit where
    // there was none is weaker.
    private fun limitWeaker(before: Int, now: Int): Boolean = when {
        before == 0 && now == 0 -> false
        before == 0 && now > 0 -> true
        now == 0 -> false
        else -> now > before
    }

    private fun scheduleWeaker(before: BlockList, now: BlockList): Boolean {
        if (!before.scheduleEnabled) {
            // Was always-on; now scheduled means some times are no longer blocked.
            return now.scheduleEnabled
        }
        if (!now.scheduleEnabled) return false // now always-on = stricter
        // Sample every day/half-hour the old config blocked; all must still block.
        val cal = java.util.Calendar.getInstance()
        for (dow in 1..7) {
            for (mins in 0 until 1440 step 30) {
                cal.set(java.util.Calendar.DAY_OF_WEEK, dow)
                cal.set(java.util.Calendar.HOUR_OF_DAY, mins / 60)
                cal.set(java.util.Calendar.MINUTE, mins % 60)
                cal.set(java.util.Calendar.SECOND, 0)
                if (before.isWithinSchedule(cal) && !now.isWithinSchedule(cal)) return true
            }
        }
        return false
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
