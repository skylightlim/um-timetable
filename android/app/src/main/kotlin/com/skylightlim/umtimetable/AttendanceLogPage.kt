package com.skylightlim.umtimetable

import android.content.ClipData
import android.content.ClipboardManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Auto attendance's log ([AttendanceLog]) inside the app, opened from Settings: every step the app took
 * to mark attendance, newest first under each day, with the steps that went wrong in red. Problems / All
 * across the top; Copy log puts the whole file on the clipboard, to send on.
 */
object AttendanceLogPage {
    /** Past this many steps the page shows only the newest: the file holds about a month. */
    private const val SHOWN = 200
    private const val LATE = 0xFFE5534B.toInt()
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.UK)

    /** The problems in the past [days] days. */
    private fun recentProblems(entries: List<AttendanceLog.Entry>, days: Long = 7): List<AttendanceLog.Entry> {
        val since = LocalDateTime.now().minusDays(days)
        return entries.filter { it.problem && it.time.isAfter(since) }
    }

    /** What Settings' row and the page's top line say: "2 problems this week", "No problems this week", "Nothing logged yet". */
    fun summary(c: android.content.Context, entries: List<AttendanceLog.Entry> = AttendanceLog.read(c)): String = when {
        entries.isEmpty() -> "Nothing logged yet"
        else -> when (val n = recentProblems(entries).size) {
            0 -> "No problems in the past week"
            1 -> "1 problem in the past week"
            else -> "$n problems in the past week"
        }
    }

    fun build(h: SpectrumHost): View {
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
        val problems = entries.filter { it.problem }
        val prefs = uiPrefs(c)
        // Problems first, unless there are none.
        val showAll = problems.isEmpty() || prefs.getBoolean("logAll", false)
        page.addView(ui.segmented(listOf("Problems · ${problems.size}", "All · ${entries.size}"), if (showAll) 1 else 0, fill = true) { i, _ ->
            prefs.edit().putBoolean("logAll", i == 1).apply()
            h.render()
        }, ui.wide(bottom = 6f))
        val list = (if (showAll) entries else problems).asReversed()
        if (list.isEmpty()) {
            page.addView(ui.text("No problems logged. All shows every step.", 15f, p.muted).apply {
                gravity = Gravity.CENTER
                setPadding(ui.dp(8), ui.dp(32), ui.dp(8), ui.dp(32))
            })
        }
        // Each day under its own label, its steps ruled inside only.
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
                addView(ui.text(CLOCK.format(e.time), 12f, if (e.problem) LATE else p.muted, ui.f.mono), LinearLayout.LayoutParams(ui.dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
                // Long press to select and copy a step.
                addView(ui.text(e.text, 13.5f, if (e.problem) LATE else p.text).apply { RichText.selectable(this) }, ui.grow())
            })
        }
        if (list.size > SHOWN) page.addView(ui.text("Showing the newest $SHOWN of ${list.size}. Copy log has all of it.", 13f, p.muted), ui.wide(top = 12f))
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

    private fun dayLabel(d: LocalDate): String {
        val today = LocalDate.now()
        return when (d) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> "${DAY_SHORT[d.dayOfWeek.value % 7]} ${d.dayOfMonth} ${MONTHS[d.monthValue - 1]}"
        }
    }
}
