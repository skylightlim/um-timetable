package com.skylightlim.umtimetable

import android.content.ClipData
import android.content.ClipboardManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Auto attendance's log ([AttendanceLog]) inside the app, opened from Settings. Classes / All across the top:
 * Classes says in words how each class of the past two weeks went ([AttendanceHistory]), marked or not and
 * why, the ones not marked in red; All is every step the app took, newest first under each day, with the
 * steps that went wrong in red. Copy log puts the whole file on the clipboard, to send on.
 */
object AttendanceLogPage {
    /** Past this many steps All shows only the newest: the file holds about a month. */
    private const val SHOWN = 200
    /** How many days Classes goes back. */
    private const val DAYS = 14
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.UK)

    /** The classes not marked in the past week. */
    private fun missed(c: android.content.Context) = AttendanceHistory.rows(c, 7).count { it.outcome.missed }

    /** What the page's top line says: "2 classes not marked in the past week", "None missed in the past week", "Nothing logged yet". */
    fun summary(c: android.content.Context): String {
        if (AttendanceLog.read(c).isEmpty()) return "Nothing logged yet"
        return when (val n = missed(c)) {
            0 -> "None missed in the past week"
            1 -> "1 class not marked in the past week"
            else -> "$n classes not marked in the past week"
        }
    }

    /** What Settings' row says at its end: "None missed", "2 not marked", or "Empty". */
    fun short(c: android.content.Context): String {
        if (AttendanceLog.read(c).isEmpty()) return "Empty"
        return when (val n = missed(c)) {
            0 -> "None missed"
            else -> "$n not marked"
        }
    }

    /** The page; [all] (a still drawing's) shows that tab instead of the one last chosen. */
    fun build(h: SpectrumHost, all: Boolean? = null): View {
        val ui = h.ui
        val p = ui.p
        val c = ui.context
        val page = ui.column().apply { setPadding(0, ui.dp(6), 0, ui.dp(8)) }
        val entries = AttendanceLog.read(c)
        if (entries.isEmpty()) {
            page.addView(ui.text("Nothing logged yet. With Mark my attendance on, the app writes down each try here: when it ran, the class, and what Spectrum said.", 15f, p.muted).apply {
                gravity = Gravity.CENTER
                setPadding(ui.dp(8), ui.dp(32), ui.dp(8), ui.dp(32))
            })
            return page
        }
        val prefs = uiPrefs(c)
        // Classes first. (A new key: whoever chose All over the old Problems starts on Classes too.)
        val showAll = all ?: prefs.getBoolean("logSteps", false)
        page.addView(ui.segmented(listOf("Classes", "All · ${entries.size}"), if (showAll) 1 else 0, fill = true) { i, _ ->
            prefs.edit().putBoolean("logSteps", i == 1).apply()
            h.render()
        }, ui.wide(bottom = 6f))
        if (showAll) steps(ui, page, entries.asReversed()) else classes(ui, page)
        lateinit var copy: android.widget.TextView
        copy = ui.text("Copy log", 14f, p.accentText, ui.f.bodySemi).apply {
            gravity = Gravity.CENTER
            minHeight = ui.dp(44)
            background = ui.pressable(ui.shape(p.surface2, 12f), 12f)
            setOnClickListener {
                val text = runCatching { AttendanceLog.file(c)?.readText() }.getOrNull().orEmpty()
                c.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Attendance log", text))
                Haptics.tick(this)
                copy.text = "Copied"
                Motion.pop(copy, 0.6f)
            }
        }
        page.addView(copy, ui.wide(top = 20f))
        page.addView(ui.text("No passwords or sign-in go in the log: only times, classes, activity names and what Spectrum said.", 12.5f, p.muted), ui.wide(top = 10f))
        return page
    }

    /**
     * Classes: each class auto attendance had to mark in the past [DAYS] days, newest day first and each
     * day's in time order: its start, the course and type, then how it went in words, red when it wasn't marked.
     */
    private fun classes(ui: Ui, page: LinearLayout) {
        val p = ui.p
        val c = ui.context
        val rows = AttendanceHistory.rows(c, DAYS)
        if (rows.isEmpty()) {
            page.addView(ui.text("No classes to mark yet. Each class the app should mark shows here, marked or not.", 15f, p.muted).apply {
                gravity = Gravity.CENTER
                setPadding(ui.dp(8), ui.dp(32), ui.dp(8), ui.dp(32))
            })
            return
        }
        batteryHelp(ui, page, rows)
        val courses = Timetable.load(c).courses
        var day: LocalDate? = null
        for (r in rows) {
            if (r.date != day) {
                page.addView(ui.label(dayLabel(r.date), p.muted, 10.5f), ui.wide(top = if (day == null) 14f else 22f, bottom = 2f))
                day = r.date
            } else {
                page.addView(View(c).apply { setBackgroundColor(p.line) }, ui.wide().apply { height = 1 })
            }
            val name = courses.firstOrNull { it.code == r.code }?.name ?: r.code
            val type = r.type.replaceFirstChar { it.uppercase() }
            val color = when {
                r.outcome.missed -> SpectrumScreens.LATE
                r.outcome.kind == AttendanceHistory.Kind.NOTHING -> p.muted
                else -> p.text
            }
            page.addView(ui.row(gap = 12f, center = false).apply {
                setPadding(0, ui.dp(10), 0, ui.dp(10))
                contentDescription = "${clockText(r.start)}, $name, $type: ${r.outcome.text}"
                addView(ui.text(clockText(r.start), 12f, if (r.outcome.missed) SpectrumScreens.LATE else p.muted, ui.f.mono), LinearLayout.LayoutParams(ui.dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(ui.column().apply {
                    addView(ui.text("$name · $type", 14.5f, p.text, ui.f.bodySemi))
                    addView(ui.text(r.outcome.text, 13.5f, color), ui.wide(top = 2f))
                }, ui.grow())
            })
        }
        page.addView(ui.text("All has every step the app took, as it wrote them down.", 12.5f, p.muted), ui.wide(top = 12f))
    }

    /**
     * When the phone didn't wake the app for a class ([AttendanceHistory.Outcome.asleep]): what to change,
     * and a button to the app's page in Android's settings, where Battery (and Autostart, on the phones
     * that have it) live. Gone once opened, until it happens to a class after that.
     */
    private fun batteryHelp(ui: Ui, page: LinearLayout, rows: List<AttendanceHistory.Row>) {
        val p = ui.p
        val c = ui.context
        val prefs = uiPrefs(c)
        val seen = prefs.getLong("batteryHelpAt", 0)
        val zone = java.time.ZoneId.systemDefault()
        if (rows.none { it.outcome.asleep && it.date.atStartOfDay(zone).plusMinutes(it.start.toLong()).toInstant().toEpochMilli() > seen }) return
        page.addView(ui.text(
            "Your phone didn't wake the app for some classes, so it couldn't mark them. In the app's settings, set Battery to Unrestricted. " +
                "On Xiaomi, Redmi, POCO, OPPO, realme, vivo, Huawei or Honor, also turn on Autostart. On Samsung, take it out of Sleeping apps and Deep sleeping apps.",
            13.5f, p.text,
        ), ui.wide(top = 14f))
        page.addView(ui.text("Open the app's settings", 14f, p.accentText, ui.f.bodySemi).apply {
            gravity = Gravity.CENTER
            minHeight = ui.dp(44)
            background = ui.pressable(ui.shape(p.surface2, 12f), 12f)
            setOnClickListener {
                Haptics.tick(this)
                prefs.edit().putLong("batteryHelpAt", System.currentTimeMillis()).apply()
                runCatching {
                    c.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${c.packageName}"))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }, ui.wide(top = 10f))
    }

    /** All: every step, newest first, each day under its own label, its steps ruled inside only. */
    private fun steps(ui: Ui, page: LinearLayout, list: List<AttendanceLog.Entry>) {
        val p = ui.p
        val c = ui.context
        var day: LocalDate? = null
        for (e in list.take(SHOWN)) {
            val d = e.time.toLocalDate()
            if (d != day) {
                page.addView(ui.label(dayLabel(d), p.muted, 10.5f), ui.wide(top = if (day == null) 14f else 22f, bottom = 2f))
                day = d
            } else {
                page.addView(View(c).apply { setBackgroundColor(p.line) }, ui.wide().apply { height = 1 })
            }
            page.addView(ui.row(gap = 12f, center = false).apply {
                setPadding(0, ui.dp(10), 0, ui.dp(10))
                contentDescription = "${CLOCK.format(e.time)}${if (e.problem) ", problem" else ""}: ${e.text}"
                addView(ui.text(CLOCK.format(e.time), 12f, if (e.problem) SpectrumScreens.LATE else p.muted, ui.f.mono), LinearLayout.LayoutParams(ui.dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
                // Long press to select and copy a step.
                addView(ui.text(e.text, 13.5f, if (e.problem) SpectrumScreens.LATE else p.text).apply { RichText.selectable(this) }, ui.grow())
            })
        }
        if (list.size > SHOWN) page.addView(ui.text("Showing the newest $SHOWN of ${list.size}. Copy log has all of it.", 13f, p.muted), ui.wide(top = 12f))
    }

    private fun dayLabel(d: LocalDate): String {
        val today = LocalDate.now()
        return when (d) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> "${DAY_SHORT[d.dayOfWeek.value % 7]} ${d.dayOfMonth} ${MONTHS[d.monthValue - 1]}"
        }
    }
}
