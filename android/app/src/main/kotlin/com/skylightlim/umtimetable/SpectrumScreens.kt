package com.skylightlim.umtimetable

import android.graphics.Color
import android.graphics.drawable.InsetDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.skylightlim.umtimetable.Motion.spring
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** What the Spectrum screens need on top of [Host]. */
interface SpectrumHost : Host {
    val syncing: Boolean
    var openCourse: Int?
    /** A section of the open course shown as a page of its own (Spectrum shows it as a link or a card), or null. */
    val openSection: Int?
    /** Opens section [id]'s page over the page shown now; Back comes back to where it was. */
    fun showSection(id: Int)
    val expanded: MutableSet<String>
    val seenNews: Set<String>
    val seenGrades: Set<String>
    /** A course's announcements ([news] ids) and marks ([grades], [SpectrumSync.gradeKey]) have been seen: their New goes, soon. */
    fun markSeen(news: Collection<String>, grades: Collection<String>) = Unit
    /** The course page as [SpectrumSync.courseContents] gives it, for [CourseOutline]. */
    fun courseContents(id: Int): JSONObject?
    fun courseError(id: Int): String?
    fun signIn()
    /** Sign out's question, its card flying out of [from] (the Sign out button) when there is one. */
    fun signOut(from: View? = null)
    fun refresh()
    /** The page drawn again as it is now, where it's scrolled to, with no entrance (as each minute's redraw). */
    fun redraw() = render()
    fun openMaya()
    fun openTimeEdit()
    fun enableNotifications()
    fun openAppSettings()
    /** Settings' Check for updates: asks GitHub, and says how it went. */
    fun checkForUpdates()
    /** Light or dark, and the accent scheme, and choosing them (Settings). */
    val look: Look
    fun setLook(look: Look)
    val scheme: Scheme
    fun setScheme(scheme: Scheme)
}

object SpectrumScreens {
    private fun hm(ms: Long) = SpectrumStore.hm(ms)

    private fun dayLabel(ms: Long): String {
        val d = Calendar.getInstance().apply { timeInMillis = ms }
        val days = ((startOfDay(ms) - startOfDay(System.currentTimeMillis())) / 86_400_000.0).let { Math.round(it) }
        return when (days) {
            0L -> "Today"
            1L -> "Tomorrow"
            -1L -> "Yesterday"
            else -> "${DAY_SHORT[d.get(Calendar.DAY_OF_WEEK) - 1]} ${d.get(Calendar.DAY_OF_MONTH)} ${MONTHS[d.get(Calendar.MONTH)]}"
        }
    }

    /** Spectrum display names often end in " ." (an empty field after the name). */
    private val TRAILING_DOT = Regex("""\s+\.$""")

    private fun rel(ms: Long): String {
        val m = Math.round((ms - System.currentTimeMillis()) / 60_000.0)
        return if (m >= 0) "in ${span(ms)}" else "${span(ms)} ago"
    }

    /** How far [ms] is from now, either way: "40 min", "12 h", "3 d". */
    private fun span(ms: Long): String {
        val a = Math.abs(Math.round((ms - System.currentTimeMillis()) / 60_000.0))
        return when {
            a < 60 -> "$a min"
            a < 1440 -> "${Math.round(a / 60.0)} h"
            else -> "${Math.round(a / 1440.0)} d"
        }
    }

    /**
     * The small line under a Spectrum tab's name: what that page holds, at a glance. It used to be the
     * week of the semester and today's date on every tab; the week is on Day's strip. Courses counts what's
     * new in their folds: announcements for the user and marks not seen yet.
     */
    fun summary(h: SpectrumHost, view: String): String {
        val c = h.ui.context
        // Settings: who's signed in, and the app's version.
        if (view == "settings") return listOf(if (SpectrumStore.signedIn(c)) accountName(c).ifBlank { "Signed in" } else "Not signed in", "version ${Updater.installed(c).second}").joinToString(" · ")
        if (!SpectrumStore.signedIn(c)) return "Not signed in"
        if (SpectrumStore.lastSync(c) == 0L) return "Not synced yet"
        val parts = when (view) {
            "due" -> {
                val now = System.currentTimeMillis()
                val (late, coming) = SpectrumStore.due(c).partition { it.optBoolean("overdue") || it.getLong("due") <= now }
                val next = coming.minOfOrNull { it.getLong("due") }
                listOfNotNull(
                    "Attendance open".takeIf { openAttendance(c) > 0 },
                    if (coming.isEmpty() && late.isEmpty()) "Nothing due" else coming.size.takeIf { it > 0 }?.let { "$it due" },
                    next?.let { "next in ${span(it).replace(" ", "")}" },
                    late.size.takeIf { it > 0 }?.let { "$it late" },
                ).take(3)
            }
            else -> listOfNotNull(
                count(SpectrumStore.array(c, "courses").length(), "course"),
                newCount(h).takeIf { it > 0 }?.let { "$it new" },
                lastSynced(c).replaceFirstChar { it.lowercase() },
            )
        }
        return parts.joinToString(" · ")
    }

    /** What's new in the Courses list's folds: the user's announcements not yet seen, and marks not yet seen (the Courses tab's badge). */
    fun newCount(h: SpectrumHost): Int {
        val c = h.ui.context
        val news = myNews(c).count { it.getInt("id").toString() !in h.seenNews }
        val marks = SpectrumStore.array(c, "grades").objects().count { !it.isNull("raw") && !SpectrumSync.isAttendance(it) && SpectrumSync.gradeKey(it) !in h.seenGrades }
        return news + marks
    }

    private fun count(n: Int, what: String) = "$n $what${if (n == 1) "" else "s"}"

    /** "Last synced 11:32" (today), "Last synced yesterday", "Last synced Mon 12 Oct", or not yet. */
    fun lastSynced(c: android.content.Context): String {
        val t = SpectrumStore.lastSync(c)
        if (t == 0L) return "Not synced yet"
        val label = dayLabel(t)
        return "Last synced ${if (label == "Today") hm(t) else if (label == "Yesterday") "yesterday" else label}"
    }

    private fun synced(h: SpectrumHost): String {
        val t = SpectrumStore.lastSync(h.ui.context)
        if (t == 0L) return "not synced yet"
        val label = dayLabel(t)
        return "synced ${if (label == "Today") hm(t) else label.lowercase()}"
    }

    private fun colorOf(h: Host, code: String) = h.timetable.courses.firstOrNull { it.code == code }?.color ?: 0xFF8F9AA9.toInt()

    /**
     * A course's name for display: the timetable's own name for a known code ("Computing
     * Mathematics I"), else Spectrum's without the leading code, in title case if it's all capitals.
     */
    private fun courseName(h: Host, name: String, code: String): String {
        h.timetable.courses.firstOrNull { it.code == code && code.isNotEmpty() && it.name != code }?.let { return it.name }
        val bare = if (code.isEmpty()) name else name.replace(Regex("""^\s*${Regex.escape(code)}\b[\s:|–-]*""", RegexOption.IGNORE_CASE), "").ifBlank { name }
        return if (bare.any { it.isLetter() } && bare == bare.uppercase()) MayaImport.titleCase(bare) else bare
    }

    private fun card(ui: Ui) = ui.column().apply {
        background = ui.shape(ui.p.surface, 18f, ui.p.line)
        setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18))
    }

    private fun button(ui: Ui, label: String, onClick: () -> Unit) = ui.text(label, 15f, ui.p.accentInk, ui.f.bodyBold).apply {
        background = ui.pressable(ui.shape(ui.p.accent, 12f), 12f)
        setPadding(ui.dp(18), ui.dp(11), ui.dp(18), ui.dp(11))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun link(ui: Ui, label: String, onClick: () -> Unit) = ui.text(label, 13f, ui.p.accentText, ui.f.bodySemi).apply {
        minHeight = ui.dp(48)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, ui.dp(16), 0)
        setOnClickListener { onClick() }
    }

    private fun connect(h: SpectrumHost, what: String) = card(h.ui).apply {
        val ui = h.ui
        addView(ui.text("Connect Spectrum", 20f, ui.p.text, ui.f.bodyBold))
        addView(ui.text("$what Sign in once with your SiswaMail. Your login stays on this phone, and the app only talks to spectrum.um.edu.my.", 14.5f, ui.p.muted), ui.wide(top = 6f, bottom = 16f))
        SpectrumStore.lastSignOut(ui.context)?.let { addView(ui.text("Signed out $it.", 13f, ui.p.faint), ui.wide(top = -8f, bottom = 14f)) }
        addView(button(ui, "Sign in to Spectrum") { h.signIn() })
    }

    private fun problem(h: SpectrumHost, into: LinearLayout) {
        val ui = h.ui
        val error = SpectrumStore.lastError(ui.context) ?: return
        val box = ui.column().apply {
            background = ui.shape(Palette.mix(0xFFF2645F.toInt(), ui.p.surface, 0.14f), 12f)
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            addView(ui.text(error, 13.5f))
            if ("expired" in error) addView(link(ui, "Sign in again") { h.signIn() })
        }
        into.addView(box, ui.wide(bottom = 14f))
    }

    private fun backgroundTip(h: SpectrumHost, into: LinearLayout) {
        val ui = h.ui
        if (!SpectrumStore.flag(ui.context, "bgBlocked")) return
        into.addView(card(ui).apply {
            background = ui.shape(ui.p.surface, 18f, ui.p.accent)
            addView(ui.text("Alerts only arrive while the app is open", 17f, ui.p.text, ui.f.bodyBold))
            addView(ui.text("Your phone blocks ${ui.context.getString(R.string.app_name)} from using data in the background, so it can't check Spectrum for attendance, announcements or deadlines while it's closed. To fix it, open app settings, tap Mobile data & Wi‑Fi, and turn on Background data.", 14.5f, ui.p.muted), ui.wide(top = 6f, bottom = 16f))
            addView(button(ui, "Open app settings") { h.openAppSettings() })
        }, ui.wide(bottom = 14f))
    }

    /** Waiting on Spectrum: the cow and the horse fighting over [text]. */
    private fun loading(ui: Ui, text: String) = ui.column().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(ui.dp(8), ui.dp(28), ui.dp(8), ui.dp(28))
        addView(DuelView(ui).apply { loopWhileShown = true })
        addView(ui.text(text, 15f, ui.p.muted), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(10) })
    }

    /** What to show while a page has nothing: [done] once Spectrum has synced, else the loader. */
    private fun emptyOrLoading(ui: Ui, done: String): View =
        if (SpectrumStore.lastSync(ui.context) > 0) empty(ui, done) else loading(ui, "Syncing with Spectrum…")

    private fun empty(ui: Ui, text: String) = ui.text(text, 15f, ui.p.muted).apply {
        gravity = Gravity.CENTER
        setPadding(ui.dp(8), ui.dp(32), ui.dp(8), ui.dp(32))
    }

    // --- Due -------------------------------------------------------------------------------

    /**
     * What's due, as in redesign 1i (in the app's own fonts): open attendance as a band to tap, then
     * one ruled row per deadline, led by how long is left. Pull down to sync. A long press on a row
     * offers to dismiss it: it goes from the list, the reminders and the widget, into a Dismissed fold at
     * the end, faded, where a long press brings it back.
     */
    fun due(
        h: SpectrumHost,
        dueItems: List<JSONObject> = SpectrumStore.array(h.ui.context, "due").objects(),
        sessions: List<JSONObject> = SpectrumStore.array(h.ui.context, "attendance").objects(),
    ): View {
        val ui = h.ui
        val p = ui.p
        val page = ui.column().apply { setPadding(0, ui.dp(6), 0, 0) }
        if (!SpectrumStore.signedIn(ui.context)) {
            page.addView(connect(h, "See what's due and get reminders 24 h and 2 h before."))
            return page
        }
        val now = System.currentTimeMillis()
        val gone = SpectrumStore.dismissedDue(ui.context)
        val (dismissed, items) = dueItems.sortedBy { it.getLong("due") }.partition { DueList.activityKey(it) in gone }
        problem(h, page)
        backgroundTip(h, page)
        attendance(h, page, sessions)
        if (items.isEmpty()) page.addView(emptyOrLoading(ui, if (dismissed.isEmpty()) "Nothing due on Spectrum right now." else "Nothing else due on Spectrum right now."))
        // Ruled inside only, as in 1i: lines between the rows and down the middle, none above or below.
        val rows = items.map { i -> dueRow(h, i, now, faded = false) }
        for ((n, row) in rows.withIndex()) {
            val line = if (n > 0) rule(ui).also(page::addView) else null
            page.addView(row)
            row.setOnLongClickListener { at ->
                popMenu(ui, at, menuCard(ui, R.drawable.ic_close, "Dismiss")) {
                    SpectrumStore.dismissDue(ui.context, items[n], gone = true)
                    Reminders.schedule(ui.context)
                    TimetableWidget.updateAll(ui.context)
                    // It folds away where it is (and the line that led to it, or the first row's line after
                    // it); the page is then drawn again, with it under Dismissed.
                    Motion.resize(row)
                    row.visibility = View.GONE
                    (line ?: (page.getChildAt(page.indexOfChild(row) + 1)?.takeIf { rows.size > 1 }))?.visibility = View.GONE
                    row.postDelayed({ h.redraw() }, Motion.QUICK.duration + 60)
                }
                true
            }
        }
        if (dismissed.isNotEmpty()) page.addView(dismissedFold(h, dismissed, now), ui.wide(top = if (items.isEmpty()) 0f else 18f))
        return page
    }

    /** The deadlines dismissed from Due, folded under "Dismissed · 2": faded rows a long press brings back. */
    private fun dismissedFold(h: SpectrumHost, items: List<JSONObject>, now: Long): View {
        val ui = h.ui
        val p = ui.p
        val key = "due-dismissed"
        val shown = key in h.expanded
        val chevron = Motion.chevron(ui, shown, p.faint)
        val list = ui.column().apply { visibility = if (shown) View.VISIBLE else View.GONE }
        for ((n, i) in items.withIndex()) {
            if (n > 0) list.addView(rule(ui))
            list.addView(dueRow(h, i, now, faded = true).apply {
                setOnLongClickListener { at ->
                    popMenu(ui, at, menuCard(ui, R.drawable.ic_repeat, "Bring back")) {
                        SpectrumStore.dismissDue(ui.context, i, gone = false)
                        Reminders.schedule(ui.context)
                        TimetableWidget.updateAll(ui.context)
                        h.redraw()
                    }
                    true
                }
            })
        }
        val head = ui.row(gap = 8f).apply {
            minimumHeight = ui.dp(48)
            background = ui.pressable(ui.shape(Color.TRANSPARENT, 8f), 8f)
            addView(ui.label("Dismissed · ${items.size}", p.muted, 10.5f), ui.grow())
            addView(chevron, LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)))
            contentDescription = "${items.size} dismissed. Long press one to bring it back."
            setOnClickListener {
                Motion.resize(this)
                val open = h.expanded.add(key) || !h.expanded.remove(key)
                list.visibility = if (open) View.VISIBLE else View.GONE
                Motion.turn(chevron, open)
            }
        }
        return ui.column().apply {
            addView(head)
            addView(list)
        }
    }

    /** One deadline's row: how long is left, large, and when; then its course, name and kind. [faded]: dismissed. */
    private fun dueRow(h: SpectrumHost, i: JSONObject, now: Long, faded: Boolean): LinearLayout {
        val ui = h.ui
        val p = ui.p
        val due = i.getLong("due")
        val overdue = i.optBoolean("overdue") || due <= now
        val code = i.optString("code")
        val color = when {
            overdue -> LATE
            due - now < 24 * 3_600_000L -> p.accentText
            else -> p.text
        }
        val row = ui.row().apply {
            background = ui.pressable(ui.shape(Color.TRANSPARENT, 8f), 8f)
            isHapticFeedbackEnabled = false // the menu's own vibration instead
            if (faded) alpha = 0.45f
            setOnClickListener { h.openUrl(i.optString("url")) }
        }
        // Left: how long is left, as large as the cell's width allows, and when it's due, centred in the
        // cell's height.
        row.addView(ui.column().apply {
            setPadding(0, ui.dp(14), ui.dp(4), ui.dp(14))
            addView(countdown(ui, if (overdue) "Late" else span(due), color, ui.dp(76)))
            addView(ui.text(whenDue(due), 11f, p.muted, ui.f.mono), ui.wide(top = 4f))
        // Wrapping its content so the row grows to fit it, and centred when the other side is taller.
        }, LinearLayout.LayoutParams(ui.dp(80), ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_VERTICAL })
        row.addView(View(ui.context).apply { setBackgroundColor(p.line) }, LinearLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT))
        // Right: course, name, and what kind of activity it is.
        row.addView(ui.column().apply {
            setPadding(ui.dp(16), ui.dp(14), 0, ui.dp(14))
            addView(ui.row(gap = 6f).apply {
                addView(View(ui.context).apply { background = ui.shape(colorOf(h, code), 99f) }, LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)))
                addView(ui.text(code.ifEmpty { "Spectrum" }, 12f, p.courseText(colorOf(h, code)), ui.f.monoBold))
            })
            addView(ui.text(i.optString("name"), 16f, p.text, ui.f.bodySemi), ui.wide(top = 4f))
            addView(ui.text(listOf(i.optString("moduleLabel"), courseName(h, i.optString("course"), code)).filter { it.isNotBlank() }.joinToString(" · "), 12.5f, p.muted), ui.wide(top = 2f))
        }, ui.grow())
        return row
    }

    /**
     * "1d", "12h", "40min" or "Late" in the display face, number and unit the same size, as large as fills
     * [width]: "1d" about 90sp, "40min" about 34sp. (With the unit at half size, "d" was too small.)
     */
    private fun countdown(ui: Ui, text: String, color: Int, width: Int): View {
        val label = text.replace(" ", "")
        // 96% of the width, on one line: sized to the exact width, "1d" broke into "1" and "d".
        return ui.text(label, 34f, color, ui.f.sign).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize * width * 0.96f / paint.measureText(label))
            maxLines = 1
        }
    }

    private const val LATE = 0xFFE5534B.toInt()

    /** When a deadline is: "today 23:59", "Wed 17:00" within the week, else "26 Oct". */
    private fun whenDue(ms: Long): String {
        val days = Math.round((startOfDay(ms) - startOfDay(System.currentTimeMillis())) / 86_400_000.0)
        val d = Calendar.getInstance().apply { timeInMillis = ms }
        return when {
            days == 0L -> "today ${hm(ms)}"
            days in -6..6 -> "${DAY_SHORT[d.get(Calendar.DAY_OF_WEEK) - 1]} ${hm(ms)}"
            else -> "${d.get(Calendar.DAY_OF_MONTH)} ${MONTHS[d.get(Calendar.MONTH)]}"
        }
    }

    /**
     * Attendance sessions open now or starting within 3 hours, as 1i's band: yellow while one is open
     * to mark, plain before it opens. One that isn't in Spectrum's calendar (`listed: false`) is the
     * attendance page of the user's class: it may or may not be open.
     */
    private fun attendance(h: SpectrumHost, into: LinearLayout, sessions: List<JSONObject>) {
        val ui = h.ui
        val p = ui.p
        val now = System.currentTimeMillis()
        var bands = 0
        for (a in sessions) {
            val start = a.getLong("start")
            val end = a.getLong("end")
            if (end <= now || start - now >= 3 * 3_600_000L) continue
            val live = start <= now
            val listed = a.optBoolean("listed", true)
            val open = live && listed
            val code = a.optString("code")
            // "WIX2001 lecture": the timetable's class at that time, if there is one.
            val at = Calendar.getInstance().apply { timeInMillis = start }
            val from = at.get(Calendar.HOUR_OF_DAY) * 60 + at.get(Calendar.MINUTE)
            val to = from + ((end - start) / 60_000L).toInt()
            val session = h.timetable.day(at.get(Calendar.DAY_OF_WEEK) - 1).firstOrNull { it.course.code == code && it.start < to && it.end > from }
            val what = listOfNotNull(code.ifEmpty { null }, session?.type?.lowercase()).joinToString(" ").ifEmpty { a.optString("name") }
            val ink = if (open) p.accentInk else p.text
            val band = ui.row(gap = 12f).apply {
                background = ui.pressable(if (open) ui.shape(p.accent, 16f) else ui.shape(p.surface, 16f, p.line), 16f)
                setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
                contentDescription = "${a.optString("name")}. Opens the attendance page."
                setOnClickListener { h.openUrl(a.optString("url")) }
            }
            band.addView(ui.column().apply {
                addView(ui.label(
                    when {
                        !listed -> "Attendance · class ${if (live) "until ${hm(end)}" else "at ${hm(start)}"}"
                        live -> "Attendance open · until ${hm(end)}"
                        else -> "Attendance at ${hm(start)} · ${rel(start)}"
                    },
                    if (open) ink else p.accentText, 11f,
                ))
                addView(ui.text(if (open) "Mark $what" else if (!listed) "Check $what" else what.replaceFirstChar { it.uppercase() }, 26f, ink, ui.f.sign).apply { setLineSpacing(0f, 1f) }, ui.wide(top = 4f))
                if (!listed) addView(ui.text("Spectrum's calendar doesn't list today's session: tap to check your class's attendance page.", 13f, p.muted), ui.wide(top = 4f))
            }, ui.grow())
            band.addView(ui.icon(R.drawable.ic_arrow, ink, 28f))
            into.addView(band, ui.wide(top = 12f))
            bands++
        }
        if (bands > 0) into.addView(ui.space(h = ui.dp(12)))
    }

    fun openAttendance(context: android.content.Context): Int {
        val now = System.currentTimeMillis()
        return SpectrumStore.array(context, "attendance").objects().count {
            it.getLong("start") <= now && it.getLong("end") > now && it.optBoolean("listed", true)
        }
    }

    // --- Grades (in each course's fold on the Courses list) --------------------------------

    /**
     * Grade items not marked yet, folded under [head] (a row; the chevron goes at its end). Tapping
     * the row opens the list in place; an item with a Spectrum page opens it.
     */
    private fun unmarked(h: SpectrumHost, head: LinearLayout, items: List<JSONObject>, key: String): View {
        val ui = h.ui
        val p = ui.p
        val shown = key in h.expanded
        val chevron = Motion.chevron(ui, shown)
        // A 16dp box: turned on its side the 8×16 chevron is 16dp wide, and the row ends right at it.
        head.addView(chevron, LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)).apply { marginStart = ui.dp(6) })
        val list = ui.column().apply { visibility = if (shown) View.VISIBLE else View.GONE }
        for (g in items) {
            val url = g.optString("url")
            list.addView(ui.row(gap = 10f).apply {
                minimumHeight = ui.dp(44)
                // Set in under "Not marked yet".
                setPadding(ui.dp(12), ui.dp(8), 0, ui.dp(8))
                addView(ui.text(g.optString("name"), 14f, p.text), ui.grow())
                g.optString("kind").takeIf { it.isNotEmpty() }?.let { addView(ui.text(it, 12.5f, p.muted)) }
                if (url.isNotEmpty()) {
                    background = ui.pressable(ui.shape(Color.TRANSPARENT, 8f), 8f)
                    contentDescription = "${g.optString("name")}, not marked yet. Opens it on Spectrum."
                    addView(ui.icon(R.drawable.ic_chevron, p.faint).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(8), ui.dp(16)) })
                    setOnClickListener { h.openUrl(url) }
                }
            })
        }
        head.background = ui.pressable(ui.shape(Color.TRANSPARENT, 8f), 8f)
        head.setOnClickListener {
            Motion.resize(head)
            val open = h.expanded.add(key) || !h.expanded.remove(key)
            list.visibility = if (open) View.VISIBLE else View.GONE
            Motion.turn(chevron, open)
        }
        return ui.column().apply {
            addView(head)
            addView(list)
        }
    }

    private fun rule(ui: Ui) = View(ui.context).apply {
        setBackgroundColor(ui.p.line)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
    }

    /**
     * One course's marks as in 1j, in its fold on the Courses list: how many items are marked, then a card
     * per mark, newest first, tagged NEW until seen, then the course total and what's still to be marked.
     */
    private fun courseMarks(h: SpectrumHost, items: List<JSONObject>): View {
        val ui = h.ui
        val p = ui.p
        val first = items.first()
        val code = first.optString("code")
        val box = ui.column()
        val marks = items.filter { !it.isNull("raw") && !it.optBoolean("total") }.sortedByDescending { it.getLong("gradedAt") }
        val total = items.firstOrNull { it.optBoolean("total") && !it.isNull("raw") }
        val count = items.count { !it.optBoolean("total") }
        box.addView(ui.text(if (marks.isEmpty()) "No marks released yet: when a lecturer releases one, it shows here and you get a notification." else "${marks.size} of $count marked", 13f, p.muted), ui.wide(top = 6f))
        for (g in marks) {
            val isNew = SpectrumSync.gradeKey(g) !in h.seenGrades
            val item = ui.column().apply {
                background = ui.pressable(ui.shape(p.surface, 14f, p.line), 14f)
                setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
                g.optString("url").takeIf { it.isNotEmpty() }?.let { url -> setOnClickListener { h.openUrl(url) } }
            }
            val row = ui.row(gap = 12f)
            row.addView(ui.column().apply {
                addView(ui.row(gap = 8f).apply {
                    addView(ui.text(g.optString("name"), 15f, p.text, ui.f.bodySemi), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).takeIf { !isNew } ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f))
                    if (isNew) addView(ui.chip("New", p.accent, p.accentInk).apply { setPadding(ui.dp(6), ui.dp(1), ui.dp(6), ui.dp(1)) })
                })
                val markedOn = g.getLong("gradedAt").takeIf { it > 0 }?.let { dayLabel(it) }?.let { d -> "marked ${if (d in setOf("Today", "Yesterday")) d.lowercase() else d}" }
                addView(ui.text(listOfNotNull(g.optString("kind").ifEmpty { null }, markedOn).joinToString(" · "), 12.5f, p.muted), ui.wide(top = 2f))
            }, ui.grow())
            val word = g.optString("word")
            if (word.isNotEmpty()) {
                row.addView(ui.text(word, 22f, p.accentText, ui.f.sign))
            } else {
                fun num(x: Double) = "%.2f".format(x).trimEnd('0').trimEnd('.')
                // The mark and its maximum share a baseline.
                row.addView(ui.row(gap = 3f, center = false).apply {
                    addView(ui.text(num(g.getDouble("raw")), 28f, p.accentText, ui.f.sign))
                    addView(ui.text("/ ${num(g.optDouble("max"))}", 12f, p.muted, ui.f.mono))
                })
            }
            item.addView(row)
            val feedback = g.optString("feedback")
            if (hasContent(feedback)) item.addView(RichText.view(h, feedback, "g${g.getInt("id")}", code.ifEmpty { "Spectrum" }, 3, 13.5f, p.muted, h.expanded), ui.wide(top = 6f))
            box.addView(item, ui.wide(top = 8f))
        }
        total?.let { box.addView(ui.text("Course total ${SpectrumSync.markText(it)}", 12.5f, p.muted), ui.wide(top = 8f)) }
        val left = items.filter { it.isNull("raw") && !it.optBoolean("total") }
        if (left.isNotEmpty()) {
            val head = ui.row().apply {
                setPadding(0, ui.dp(10), 0, ui.dp(10))
                addView(ui.text("Not marked yet · ${left.size} ${if (left.size == 1) "item" else "items"}", 12.5f, p.muted), ui.grow())
            }
            box.addView(unmarked(h, head, left, "unmarked-${first.getInt("courseId")}"), ui.wide(top = if (total == null) 2f else 0f))
        }
        return box
    }

    // --- News (in each course's fold on the Courses list) ----------------------------------

    /** The announcements for the user (not those for other occurrences only), newest first. */
    private fun myNews(c: android.content.Context) = SpectrumStore.array(c, "news").objects().filter { it.optBoolean("forMe", true) }.sortedByDescending { it.getLong("time") }

    /** "NEW ··· Today 09:12": a post's first line, in its course's fold; [extra] goes at the end (a row's chevron). */
    private fun newsTop(h: SpectrumHost, a: JSONObject, size: Float, extra: View? = null) = h.ui.row(gap = 8f).apply {
        val ui = h.ui
        if (a.getInt("id").toString() !in h.seenNews) addView(ui.chip("New", ui.p.accent, ui.p.accentInk).apply { setPadding(ui.dp(6), ui.dp(1), ui.dp(6), ui.dp(1)) })
        addView(ui.space(), ui.spacer())
        addView(ui.text("${dayLabel(a.getLong("time"))} ${hm(a.getLong("time"))}", size, ui.p.muted, ui.f.mono))
        extra?.let { addView(it) }
    }

    /** Who posted, and where when it isn't the course's Announcements forum: "Dr Lee · Announcement OCC 4". */
    private fun author(a: JSONObject) = listOf(a.optString("author").replace(TRAILING_DOT, ""), a.optString("forum"), if (a.optString("code").isEmpty()) a.optString("course") else "")
        .filter { it.isNotBlank() }.joinToString(" · ")

    /** A post's text and files. */
    private fun newsBody(h: SpectrumHost, a: JSONObject, into: LinearLayout) {
        val ui = h.ui
        val code = a.optString("code")
        // An opened post shows all of its text: tapping the row was the "show more".
        into.addView(RichText.view(h, a.optString("html"), "n${a.getInt("id")}", code.ifEmpty { "Spectrum" }, 0, expanded = h.expanded), ui.wide(top = 8f))
        for (f in a.optJSONArray("attachments")?.objects().orEmpty()) into.addView(fileButton(h, f, code), ui.wide(top = 8f))
    }

    /** A post as a ruled row: when, subject and author; tapping it opens its text and files in place. */
    private fun newsRow(h: SpectrumHost, a: JSONObject): View {
        val ui = h.ui
        val key = "news-open-${a.getInt("id")}"
        val shown = key in h.expanded
        val chevron = Motion.chevron(ui, shown, ui.p.faint)
        val body = ui.column().apply { visibility = if (shown) View.VISIBLE else View.GONE }
        newsBody(h, a, body)
        val head = ui.column().apply {
            setPadding(0, ui.dp(14), 0, ui.dp(14))
            background = ui.pressable(ui.shape(Color.TRANSPARENT, 8f), 8f)
            addView(newsTop(h, a, 11f, chevron.apply { layoutParams = LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)) }))
            // Bold and large, as 1m's rows.
            addView(ui.text(a.optString("subject"), 19f, ui.p.text, ui.f.bodyBold).apply { setLineSpacing(0f, 1.15f) }, ui.wide(top = 4f))
            author(a).takeIf { it.isNotEmpty() }?.let { addView(ui.text(it, 12.5f, ui.p.muted), ui.wide(top = 2f)) }
            setOnClickListener {
                Motion.resize(this)
                val open = h.expanded.add(key) || !h.expanded.remove(key)
                body.visibility = if (open) View.VISIBLE else View.GONE
                Motion.turn(chevron, open)
            }
        }
        return ui.column().apply {
            addView(head)
            addView(body.apply { setPadding(0, 0, 0, ui.dp(14)) })
        }
    }

    // --- Courses ---------------------------------------------------------------------------

    /** Course [id]'s fold of news and grades on the Courses list: its key in [SpectrumHost.expanded]. */
    fun foldKey(id: Int) = "course-fold-$id"

    /** The user's occurrence in [code]: their Spectrum group or their choice, else the timetable's; 0 unknown. */
    private fun myOcc(h: SpectrumHost, occurrences: JSONObject, code: String) =
        occurrences.optInt(code).takeIf { it > 0 } ?: h.timetable.courses.firstOrNull { it.code == code }?.occ ?: 0

    /**
     * The courses, as in redesign 1n: a plain ruled list (dot, code and occurrence, name), each opening
     * its course page. The end of each row, past a short line, opens the course's news and then its grades
     * in place ([courseEntry]); Grades and News were tabs of their own. Only what's for the user's
     * occurrence: announcements for other occurrences only, and their unmarked items, are left out. The
     * account, imports and other settings are in the Settings tab. Pull to sync.
     */
    fun courses(h: SpectrumHost, gradeItems: List<JSONObject> = SpectrumStore.array(h.ui.context, "grades").objects()): View {
        val ui = h.ui
        val page = ui.column().apply { setPadding(0, ui.dp(6), 0, 0) }
        if (!SpectrumStore.signedIn(ui.context)) {
            page.addView(connect(h, "Read each course page, its announcements and your marks, and open its slides and notes in one tap."))
            return page
        }
        val courses = SpectrumStore.array(ui.context, "courses").objects()
        h.openCourse?.let { id -> courses.firstOrNull { it.getInt("id") == id }?.let { return coursePage(h, it) } }
        problem(h, page)
        backgroundTip(h, page)
        if (courses.isEmpty()) page.addView(emptyOrLoading(ui, "No current courses found on Spectrum."))
        val occurrences = SpectrumStore.occurrences(ui.context)
        val news = myNews(ui.context).groupBy { it.getInt("courseId") }
        // Attendance is in Due; unmarked items named for other occurrences are other groups' submissions.
        val grades = gradeItems.filter { g ->
            !SpectrumSync.isAttendance(g) && !(g.isNull("raw") && AttendanceMatch.othersOnly(g.optString("name"), myOcc(h, occurrences, g.optString("code"))))
        }.groupBy { it.getInt("courseId") }
        // Ruled inside only: a line between courses, none above the first or below the last.
        for ((n, c) in courses.withIndex()) {
            if (n > 0) page.addView(rule(ui))
            val id = c.getInt("id")
            val code = c.optString("code")
            page.addView(courseEntry(h, c, code, myOcc(h, occurrences, code).takeIf { it > 0 }, news[id].orEmpty(), grades[id].orEmpty()))
        }
        if (courses.isNotEmpty()) totalHours(h)?.let(page::addView)
        return page
    }

    /**
     * Under the courses, the timetable's week in total (it was under Week's grid): under a heavier line,
     * each kind's hours by its initial ("L12 T5") and how many classes, and the hours large on the right.
     * Null without a timetable.
     */
    private fun totalHours(h: SpectrumHost): View? {
        val ui = h.ui
        val p = ui.p
        val sessions = h.timetable.sessions
        if (sessions.isEmpty()) return null
        val all = sessions.sumOf { it.end - it.start }
        val count = "${sessions.size} ${if (sessions.size == 1) "class" else "classes"} a week"
        return ui.column().apply {
            addView(View(ui.context).apply { setBackgroundColor(p.text) }, ui.wide(top = 4f).apply { height = ui.dp(1.5f) })
            addView(ui.row(gap = 12f).apply {
                minimumHeight = ui.dp(60)
                // In line with the courses' names (after their 12dp dot and 12dp gap).
                setPadding(ui.dp(24), ui.dp(10), 0, ui.dp(10))
                contentDescription = "Total: $count, ${hoursByType(sessions)}, ${hoursText(all)}"
                addView(ui.column().apply {
                    addView(ui.text("Total", 15f, p.text, ui.f.bodyBold))
                    addView(ui.text("${hoursShort(sessions)} · $count", 12.5f, p.muted), ui.wide(top = 2f))
                }, ui.grow())
                addView(ui.text(hoursText(all), 26f, p.text, ui.f.sign))
            })
        }
    }

    /**
     * A course on the Courses list. Its row opens the course page; the end of it, past a short line (a
     * count of what's new over a chevron), opens the course's announcements and then its marks in place
     * under it, built the first time it's opened. Opening it marks what's new there as seen.
     */
    private fun courseEntry(h: SpectrumHost, c: JSONObject, code: String, occ: Int?, news: List<JSONObject>, grades: List<JSONObject>): View {
        val ui = h.ui
        val p = ui.p
        val id = c.getInt("id")
        val key = foldKey(id)
        val name = courseName(h, c.optString("name"), code)
        val freshNews = news.map { it.getInt("id").toString() }.filter { it !in h.seenNews }
        val freshMarks = grades.filter { !it.isNull("raw") }.map(SpectrumSync::gradeKey).filter { it !in h.seenGrades }
        val fresh = freshNews.size + freshMarks.size
        val body = courseRow(h, code, name, occ, null).apply {
            background = ui.pressable(ui.shape(Color.TRANSPARENT, 8f), 8f)
            contentDescription = "$code, $name. Opens the course page."
            setOnClickListener { Haptics.tick(this); h.openCourse = id; h.render() }
        }
        val chevron = Motion.chevron(ui, key in h.expanded, p.faint)
        val toggle = ui.column().apply {
            gravity = Gravity.CENTER
            background = ui.pressable(ui.shape(Color.TRANSPARENT, 8f), 8f)
            contentDescription = "$code announcements and marks${if (fresh > 0) ", $fresh new" else ""}"
            if (fresh > 0) addView(ui.chip(fresh.toString(), p.accent, p.accentInk).apply { setPadding(ui.dp(6), ui.dp(1), ui.dp(6), ui.dp(1)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ui.dp(6) })
            addView(chevron, LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)))
        }
        // In line with the course's name (after its 12dp dot and 12dp gap).
        val fold = ui.column().apply { setPadding(ui.dp(24), 0, 0, ui.dp(16)) }
        fun open(now: Boolean) {
            if (fold.childCount == 0) courseFold(h, fold, news, grades)
            fold.visibility = if (now) View.VISIBLE else View.GONE
            if (now) h.markSeen(freshNews, freshMarks)
        }
        if (key in h.expanded) open(true) else fold.visibility = View.GONE
        toggle.setOnClickListener {
            Haptics.tick(toggle)
            Motion.resize(toggle)
            val now = h.expanded.add(key) || !h.expanded.remove(key)
            open(now)
            Motion.turn(chevron, now)
        }
        return ui.column().apply {
            addView(ui.row().apply {
                addView(body, ui.grow())
                addView(View(ui.context).apply { setBackgroundColor(p.line) }, LinearLayout.LayoutParams(1, ui.dp(32)))
                addView(toggle, LinearLayout.LayoutParams(ui.dp(56), ViewGroup.LayoutParams.MATCH_PARENT))
            })
            addView(fold)
        }
    }

    /** A course's fold: its announcements for the user (as News showed them), then its marks (as Grades did). */
    private fun courseFold(h: SpectrumHost, into: LinearLayout, news: List<JSONObject>, grades: List<JSONObject>) {
        val ui = h.ui
        val p = ui.p
        into.addView(ui.label("News", p.muted, 10.5f), ui.wide(top = 2f))
        if (news.isEmpty()) into.addView(ui.text("No announcements for you in the past month.", 14f, p.muted), ui.wide(top = 8f, bottom = 4f))
        for ((n, a) in news.withIndex()) {
            if (n > 0) into.addView(rule(ui))
            into.addView(newsRow(h, a))
        }
        into.addView(ui.label("Grades", p.muted, 10.5f), ui.wide(top = 18f))
        if (grades.none { !it.optBoolean("total") }) into.addView(ui.text("Spectrum lists no grade items for this course.", 14f, p.muted), ui.wide(top = 8f))
        else into.addView(courseMarks(h, grades))
    }

    /** A course as the Courses list shows it: its colour dot, the code and occurrence, the full name under them, and [end] at the end. */
    private fun courseRow(h: SpectrumHost, code: String, name: String, occ: Int?, end: View?): LinearLayout {
        val ui = h.ui
        val p = ui.p
        return ui.row(gap = 12f).apply {
            minimumHeight = ui.dp(64)
            setPadding(0, ui.dp(13), 0, ui.dp(13))
            addView(View(ui.context).apply { background = ui.shape(colorOf(h, code), 99f) }, LinearLayout.LayoutParams(ui.dp(12), ui.dp(12)))
            addView(ui.column().apply {
                if (code.isNotEmpty()) addView(ui.row().apply {
                    addView(ui.text(code, 13f, p.courseText(colorOf(h, code)), ui.f.monoBold))
                    occ?.let { addView(ui.text(" · Occ $it", 13f, p.faint, ui.f.mono)) }
                })
                addView(ui.text(name, 17f, p.text, ui.f.bodySemi), ui.wide(top = 3f))
            }, ui.grow())
            end?.let(::addView)
        }
    }

    /** The signed-in student's name as Spectrum gives it, in title case when Spectrum shouts it. */
    fun accountName(c: android.content.Context): String =
        SpectrumStore.name(c).replace(TRAILING_DOT, "").let { if (it == it.uppercase()) MayaImport.titleCase(it) else it }

    /**
     * A course page, arranged as Spectrum's own page is in a browser ([CourseOutline]): the sections in
     * order, subsections and Flexible sections' inner sections set in where they sit, and a section Spectrum
     * shows as a link or a card (one section per page) as a card opening a page of its own ([SpectrumHost.openSection]).
     */
    private fun coursePage(h: SpectrumHost, c: JSONObject): View {
        val ui = h.ui
        val p = ui.p
        val id = c.getInt("id")
        val code = c.optString("code")
        val color = colorOf(h, code)
        val page = ui.column()
        val course = h.courseContents(id)
        val top = course?.let(CourseOutline::build).orEmpty()
        val folder = code.ifEmpty { "Spectrum" }
        h.openSection?.let { CourseOutline.find(top, it) }?.let { node ->
            // A section's own page: the course code over its name, then all of it.
            page.addView(ui.column().apply {
                setPadding(0, ui.dp(18), 0, ui.dp(12))
                addView(ui.text((code.ifEmpty { "Course" }).uppercase(), 15f, p.courseText(color), ui.f.monoBold))
                addView(ui.text(node.name, 26f, p.text, ui.f.bodyBold).apply { RichText.selectable(this) }, ui.wide(top = 4f))
            })
            page.addView(sectionBody(h, node, folder, 0, "p${node.id}"))
            return page
        }
        page.addView(ui.column().apply {
            setPadding(0, ui.dp(18), 0, ui.dp(14))
            addView(ui.text((code.ifEmpty { "Course" }).uppercase(), 38f, p.courseText(color), ui.f.sign))
            addView(ui.text(courseName(h, c.optString("name"), code), 20f, p.text, ui.f.bodyBold).apply { RichText.selectable(this) }, ui.wide(top = 6f))
        })
        hours(h, code)?.let { page.addView(it, ui.wide(bottom = 6f)) }
        h.courseError(id)?.let {
            page.addView(ui.text(it, 13.5f).apply { background = ui.shape(Palette.mix(0xFFF2645F.toInt(), p.surface, 0.14f), 12f); setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10)) })
            return page
        }
        if (course == null) {
            page.addView(loading(ui, "Loading the course page…"))
            return page
        }
        // Each top section in a box of its own, so a pin can move it to the top: pinned ones come first (in
        // the order pinned), under "Pinned", then the rest in Spectrum's order.
        val boxes = LinkedHashMap<String, LinearLayout>()
        val pins = SpectrumStore.pinnedSections(ui.context, id).toMutableList()
        val holder = ui.column()
        val pinnedLabel = ui.label("Pinned", p.muted, 10.5f)
        val restLabel = ui.label("Other sections", p.muted, 10.5f)
        fun arrange() {
            holder.removeAllViews()
            val pinned = pins.filter { it in boxes }
            if (pinned.isNotEmpty()) holder.addView(pinnedLabel, ui.wide(top = 18f, bottom = -12f))
            for (k in pinned) holder.addView(boxes.getValue(k))
            if (pinned.isNotEmpty() && boxes.size > pinned.size) holder.addView(restLabel, ui.wide(top = 30f, bottom = -12f))
            for ((k, box) in boxes) if (k !in pinned) holder.addView(box)
        }
        for (node in top) {
            val sectionKey = node.id.toString()
            val box = ui.column()
            boxes[sectionKey] = box
            val name = node.name
            val mark = ui.icon(R.drawable.ic_pin_on, p.accentText, 16f).apply {
                visibility = if (sectionKey in pins) View.VISIBLE else View.GONE
                contentDescription = "Pinned"
            }
            // A long press on a section's name (or its card) offers to pin it, or unpin it; a pinned one has a pin.
            fun pinnable(head: View) = head.setOnLongClickListener { at ->
                val pinned = sectionKey in pins
                pinMenu(ui, at, pinned) {
                    Motion.resize(holder)
                    pins.remove(sectionKey)
                    if (!pinned) pins += sectionKey
                    SpectrumStore.pinSection(ui.context, id, sectionKey, !pinned)
                    mark.visibility = if (pinned) View.GONE else View.VISIBLE
                    if (!pinned) Motion.pop(mark, 0.3f)
                    arrange()
                }
                true
            }
            if (node.link) {
                box.addView(sectionCard(h, node, folder, mark = mark).also(::pinnable), ui.wide(top = 10f))
                continue
            }
            // Flexible sections leaves the first section's name off unless the course shows it.
            if (node.number > 0 || course.optBoolean("section0title", true)) {
                box.addView(sectionName(ui, node, 0).apply {
                    minimumHeight = ui.dp(48)
                    addView(mark)
                    contentDescription = "$name. Long press to pin or unpin it."
                    pinnable(this)
                }, ui.wide(top = 14f, bottom = -2f))
            }
            box.addView(sectionBody(h, node, folder, 0, "s$id-${node.id}"))
        }
        arrange()
        page.addView(holder)
        return page
    }

    /**
     * A course page's Hours, first, before Spectrum's sections: its classes' hours a week by kind ("Lecture
     * 2 h · Tutorial 1 h"), how many classes, and the hours large on the right (they were in a Courses
     * section under Week's grid). A tap opens the course's class sheet, with each class's day, time and room.
     * Null for a course with no classes in the timetable.
     */
    private fun hours(h: SpectrumHost, code: String): View? {
        val ui = h.ui
        val p = ui.p
        val course = h.timetable.courses.firstOrNull { code.isNotEmpty() && it.code == code } ?: return null
        val sessions = h.timetable.sessions.filter { it.course == course }
        if (sessions.isEmpty()) return null
        val total = sessions.sumOf { it.end - it.start }
        val count = "${sessions.size} ${if (sessions.size == 1) "class" else "classes"} a week${if (sessions.any { it.room == "Online" }) " · online" else ""}"
        return ui.column().apply {
            // A heading as the sections' own.
            addView(ui.text("Hours", 18f, p.text, ui.f.bodyBold), ui.wide(bottom = 8f))
            addView(ui.row(gap = 12f).apply {
                minimumHeight = ui.dp(60)
                background = ui.pressable(ui.shape(p.surface, 12f, p.line), 12f)
                setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
                contentDescription = "Hours: ${hoursByType(sessions)}, $count, ${hoursText(total)} a week. Opens the class times."
                setOnClickListener { h.openSheet(course, null) }
                addView(ui.column().apply {
                    addView(ui.text(hoursByType(sessions), 15f, p.text, ui.f.bodySemi))
                    addView(ui.text(count, 12.5f, p.muted), ui.wide(top = 2f))
                }, ui.grow())
                addView(ui.text(hoursText(total), 26f, p.text, ui.f.sign))
            })
        }
    }

    /** A section's name as a heading: bold, smaller set in; greyed, with a lock when it isn't open to this student. */
    private fun sectionName(ui: Ui, node: CourseOutline.Node, depth: Int) = ui.row(gap = 8f).apply {
        val closed = node.restricted || node.hidden
        addView(ui.text(node.name, if (depth == 0) 18f else 16f, if (closed) ui.p.muted else ui.p.text, ui.f.bodyBold), ui.grow())
        if (node.restricted) addView(ui.icon(R.drawable.ic_lock, ui.p.muted, 16f).apply { contentDescription = "Not available to you" })
    }

    /**
     * What a section holds, as on Spectrum's page: what it waits for (another group's), its summary, its
     * activities and texts with its subsections where they sit, then the sections in it. A hidden one shows
     * only "Not available". Every text in full and at reading size: nothing behind Show more. A long press
     * selects it to copy.
     */
    private fun sectionBody(h: SpectrumHost, node: CourseOutline.Node, folder: String, depth: Int, key: String): LinearLayout {
        val ui = h.ui
        val p = ui.p
        val body = ui.column()
        if (node.hidden) {
            body.addView(ui.text("Not available", 14f, p.muted), ui.wide(bottom = 6f))
            return body
        }
        val availability = node.section.optString("availability")
        if (hasContent(availability)) body.addView(RichText.view(h, availability, "$key-a", folder, 2, 13.5f, p.muted, h.expanded, selectable = true), ui.wide(bottom = 8f))
        val summary = node.section.optString("summary")
        if (hasContent(summary)) body.addView(textBlock(ui, RichText.view(h, summary, "$key-s", folder, 0, 16f, expanded = h.expanded, selectable = true)), ui.wide(bottom = 8f))
        val list = ui.column(gap = 8f)
        node.items.forEachIndexed { mi, item ->
            val sub = item.sub
            if (sub != null) list.addView(nested(h, sub, folder, depth + 1, "$key-$mi"))
            else moduleView(h, item.module, folder, "$key-$mi")?.let(list::addView)
        }
        for ((ci, child) in node.children.withIndex()) {
            list.addView(if (child.link) sectionCard(h, child, folder) else nested(h, child, folder, depth + 1, "$key-c$ci"))
        }
        if (list.childCount > 0) body.addView(list)
        return body
    }

    /** A section inside a section (a subsection, or one of Flexible sections' inner ones): set in, with a line down its side. */
    private fun nested(h: SpectrumHost, node: CourseOutline.Node, folder: String, depth: Int, key: String): View {
        val ui = h.ui
        return ui.row(gap = 12f).apply {
            setPadding(0, ui.dp(6), 0, ui.dp(2))
            addView(View(ui.context).apply { background = ui.shape(ui.p.line, 2f) }, LinearLayout.LayoutParams(ui.dp(3), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(ui.column().apply {
                addView(sectionName(ui, node, depth), ui.wide(bottom = 6f))
                addView(sectionBody(h, node, folder, depth, key))
            }, ui.grow())
        }
    }

    /**
     * A section Spectrum shows as a link to a page of its own (or as a card, when a course shows one
     * section per page): its name, how much is in it and its summary, as Spectrum shows them (a lecturer's
     * card under their name), and a tap opens its page. One that isn't open to this student is greyed and
     * says why, and doesn't open.
     */
    private fun sectionCard(h: SpectrumHost, node: CourseOutline.Node, folder: String, mark: View? = null): LinearLayout {
        val ui = h.ui
        val p = ui.p
        val closed = node.restricted || node.hidden
        val card = ui.column().apply {
            background = if (closed) ui.shape(Color.TRANSPARENT, 12f, p.line) else ui.pressable(ui.shape(p.surface, 12f, p.line), 12f)
            setPadding(ui.dp(14), ui.dp(12), ui.dp(12), ui.dp(12))
        }
        val inside = listOfNotNull(
            node.activities.takeIf { it > 0 }?.let { "$it ${if (it == 1) "activity" else "activities"}" },
            node.children.size.takeIf { it > 0 }?.let { "$it ${if (it == 1) "section" else "sections"}" },
        ).joinToString(" · ").ifEmpty { if (node.hidden) "Not available" else "" }
        card.addView(ui.row(gap = 10f).apply {
            minimumHeight = ui.dp(24)
            addView(ui.column().apply {
                addView(ui.text(node.name, 16.5f, if (closed) p.muted else p.text, ui.f.bodyBold))
                if (inside.isNotEmpty()) addView(ui.text(inside, 11f, p.muted, ui.f.mono), ui.wide(top = 3f))
            }, ui.grow())
            mark?.let(::addView)
            if (node.restricted) addView(ui.icon(R.drawable.ic_lock, p.muted, 16f).apply { contentDescription = "Not available to you" })
            if (!closed) addView(ui.icon(R.drawable.ic_chevron, p.faint), LinearLayout.LayoutParams(ui.dp(8), ui.dp(16)))
        })
        val availability = node.section.optString("availability")
        if (node.restricted && hasContent(availability)) card.addView(RichText.view(h, availability, "c${node.id}-a", folder, 2, 13.5f, p.muted, h.expanded), ui.wide(top = 6f))
        val summary = node.section.optString("summary")
        if (!node.hidden && hasContent(summary)) card.addView(RichText.view(h, summary, "c${node.id}-s", folder, 0, 15f, p.text, h.expanded, selectable = true), ui.wide(top = 8f))
        if (!closed) card.setOnClickListener { h.showSection(node.id) }
        card.contentDescription = listOf(node.name, inside, if (closed) "not available" else "opens its page").filter { it.isNotEmpty() }.joinToString(", ")
        return card
    }

    /**
     * One activity or text on a course page: a text block (a lone dividing line as a line), a file, a folder
     * opening in place, a link or any other activity opening in the app's Spectrum view, with its dates, its
     * description and what it waits for under it. One that isn't open to this student (another group's) is
     * greyed with a lock and doesn't open, as on Spectrum's page. Null for an empty text.
     */
    private fun moduleView(h: SpectrumHost, m: JSONObject, folder: String, key: String): View? {
        val ui = h.ui
        val p = ui.p
        val kind = m.optString("kind")
        val restricted = m.optBoolean("restricted")
        val availability = m.optString("availability")
        if (kind == "label") {
            val html = m.optString("html")
            return when {
                restricted -> if (hasContent(availability)) RichText.view(h, availability, "$key-a", folder, 2, 13.5f, p.muted, h.expanded, selectable = true) else null
                SpectrumHtml.isRule(html) -> rule(ui)
                hasContent(html) -> textBlock(ui, RichText.view(h, html, key, folder, 0, 16f, expanded = h.expanded, selectable = true))
                else -> null
            }
        }
        val item = ui.column()
        val files = m.optJSONArray("files")?.objects().orEmpty()
        when {
            restricted -> item.addView(ui.row(gap = 10f).apply {
                background = ui.shape(Color.TRANSPARENT, 12f, p.line)
                minimumHeight = ui.dp(48)
                setPadding(ui.dp(12), ui.dp(11), ui.dp(12), ui.dp(11))
                addView(ui.icon(R.drawable.ic_lock, p.faint, 18f))
                addView(ui.text(m.optString("name"), 15.5f, p.muted, ui.f.bodySemi), ui.grow())
                m.optString("label").takeIf { it.isNotEmpty() }?.let { addView(ui.text(it, 11f, p.faint, ui.f.mono)) }
                contentDescription = "${m.optString("name")}, not available to you"
            })
            kind == "file" && files.size == 1 -> item.addView(fileButton(h, files[0], folder, label = m.optString("name")))
            kind == "folder" || kind == "file" -> {
                // Opens in place; rebuilding the page would lose the scroll position.
                val sub = ui.column(gap = 6f).apply { setPadding(ui.dp(14), ui.dp(6), 0, ui.dp(2)) }
                for (f in files) sub.addView(fileButton(h, f, folder))
                val chevron = Motion.chevron(ui, "$key-files" in h.expanded)
                sub.visibility = if ("$key-files" in h.expanded) View.VISIBLE else View.GONE
                lateinit var button: LinearLayout
                button = moduleButton(ui, R.drawable.ic_tab_courses, m.optString("name"), "${files.size} files") {
                    Motion.resize(button)
                    val open = h.expanded.add("$key-files") || !h.expanded.remove("$key-files")
                    sub.visibility = if (open) View.VISIBLE else View.GONE
                    Motion.turn(chevron, open)
                }
                button.addView(chevron, LinearLayout.LayoutParams(ui.dp(8), ui.dp(16)))
                item.addView(button)
                item.addView(sub)
            }
            else -> item.addView(moduleButton(ui, if (kind == "url") R.drawable.ic_link else R.drawable.ic_module, m.optString("name"), m.optString("label")) { h.openUrl(m.optString("url")) })
        }
        val dates = m.optJSONArray("dates")?.objects().orEmpty().filter { it.optLong("time") > 0 }
            .joinToString(" · ") { "${it.optString("label")} ${dayLabel(it.getLong("time"))} ${hm(it.getLong("time"))}" }
        if (!restricted && dates.isNotEmpty()) item.addView(ui.text(dates, 13.5f, p.muted).apply { setPadding(ui.dp(42), ui.dp(4), ui.dp(4), 0); RichText.selectable(this) })
        val html = m.optString("html")
        if (!restricted && hasContent(html)) item.addView(RichText.view(h, html, "$key-d", folder, 0, 15f, p.text, h.expanded, selectable = true).apply { setPadding(ui.dp(42), ui.dp(6), ui.dp(4), ui.dp(2)) })
        // A long list of conditions folds under Show more, as on Spectrum's page.
        if (hasContent(availability)) item.addView(RichText.view(h, availability, "$key-a", folder, 2, 13.5f, p.muted, h.expanded, selectable = true).apply { setPadding(ui.dp(42), ui.dp(4), ui.dp(4), ui.dp(2)) })
        return item
    }

    /** [pinMenu]'s card: a pin and "Pin to top", or an outline pin and "Unpin". */
    fun pinCard(ui: Ui, pinned: Boolean) = menuCard(ui, if (pinned) R.drawable.ic_pin else R.drawable.ic_pin_on, if (pinned) "Unpin" else "Pin to top")

    /** A long press's one-choice card ([popMenu]): an icon in the accent and what it does. */
    fun menuCard(ui: Ui, icon: Int, label: String) = ui.row(gap = 10f).apply {
        background = ui.pressable(ui.shape(ui.p.surface, 14f, ui.p.line), 14f)
        elevation = ui.dp(10f).toFloat()
        setPadding(ui.dp(14), ui.dp(12), ui.dp(18), ui.dp(12))
        addView(ui.icon(icon, ui.p.accentText, 18f))
        addView(ui.text(label, 15f, ui.p.text, ui.f.bodySemi))
    }

    /**
     * What a long press on a section's name offers: "Pin to top", or "Unpin" for a pinned one ([popMenu]).
     */
    private fun pinMenu(ui: Ui, heading: View, pinned: Boolean, toggle: () -> Unit) = popMenu(ui, heading, pinCard(ui, pinned), toggle)

    /**
     * [card] (a [menuCard]) on a small popup that grows out of [heading], what was long pressed, with a
     * vibration as it comes up. A tap on it runs [action]; a tap anywhere else puts it away.
     */
    private fun popMenu(ui: Ui, heading: View, card: View, action: () -> Unit) {
        // Room around the card for its shadow, which the popup's window would otherwise cut.
        val room = FrameLayout(ui.context).apply {
            setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(16))
            clipChildren = false
            addView(card)
        }
        val popup = android.widget.PopupWindow(room, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = 0f
            animationStyle = 0
        }
        card.setOnClickListener {
            Haptics.tick(card)
            popup.dismiss()
            action()
        }
        Haptics.threshold(heading, true)
        popup.showAsDropDown(heading, -ui.dp(12), -ui.dp(6))
        if (!Motion.enabled) return
        card.pivotX = 0f
        card.pivotY = 0f
        card.scaleX = 0.8f
        card.scaleY = 0.8f
        card.alpha = 0f
        card.animate().scaleX(1f).scaleY(1f).spring(Motion.BOUNCY).start()
        Motion.fade(card, 0f, 1f, 120)
    }

    private fun hasContent(html: String) = html.contains("<img", ignoreCase = true) ||
        android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT).toString().replace(Regex("[\\s\\u200b\\u00a0\\uFFFC]"), "").isNotEmpty()

    private fun textBlock(ui: Ui, content: View) = FrameLayout(ui.context).apply {
        background = ui.shape(ui.p.surface, 12f, ui.p.line)
        setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
        addView(content)
    }

    private fun moduleButton(ui: Ui, icon: Int, name: String, meta: String, onClick: () -> Unit) = ui.row(gap = 10f).apply {
        background = ui.pressable(ui.shape(ui.p.surface, 12f, ui.p.line), 12f)
        minimumHeight = ui.dp(48)
        setPadding(ui.dp(12), ui.dp(11), ui.dp(12), ui.dp(11))
        addView(ui.icon(icon, ui.p.muted, 18f))
        addView(ui.text(name, 15.5f, ui.p.text, ui.f.bodySemi), ui.grow())
        if (meta.isNotEmpty()) addView(ui.text(meta, 11f, ui.p.muted, ui.f.mono))
        setOnClickListener { onClick() }
    }

    private fun fileButton(h: Host, f: JSONObject, code: String, label: String? = null, compact: Boolean = false): View {
        val ui = h.ui
        val name = f.optString("name")
        val size = f.optLong("size")
        val meta = listOf(name.substringAfterLast('.', "").uppercase().take(4), if (size <= 0) "" else if (size < 1_000_000) "${maxOf(1, size / 1000)} KB" else "${"%.1f".format(size / 1e6)} MB")
            .filter { it.isNotEmpty() }.joinToString(" · ")
        return ui.row(gap = 10f).apply {
            background = ui.pressable(if (compact) ui.shape(ui.p.surface2, 12f) else ui.shape(ui.p.surface, 12f, ui.p.line), 12f)
            minimumHeight = ui.dp(48)
            setPadding(ui.dp(12), ui.dp(if (compact) 8 else 11), ui.dp(12), ui.dp(if (compact) 8 else 11))
            addView(ui.icon(R.drawable.ic_file, ui.p.muted, 18f))
            addView(ui.text(label ?: name, 15.5f, ui.p.text, ui.f.bodySemi), ui.grow())
            if (meta.isNotEmpty()) addView(ui.text(meta, 11f, ui.p.muted, ui.f.mono))
            setOnClickListener { h.openFile(f, code.ifEmpty { "Spectrum" }) }
        }
    }
}
