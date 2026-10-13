package com.skylightlim.umtimetable

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** What the Spectrum screens need on top of [Host]. */
interface SpectrumHost : Host {
    val syncing: Boolean
    var openCourse: Int?
    val expanded: MutableSet<String>
    val seenNews: Set<String>
    val seenGrades: Set<String>
    fun courseContents(id: Int): JSONArray?
    fun courseError(id: Int): String?
    fun signIn()
    fun signOut()
    fun refresh()
    fun openMaya()
    fun openTimeEdit()
    fun enableNotifications()
    fun openAppSettings()
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

    private fun refreshButton(h: SpectrumHost): View {
        val ui = h.ui
        return FrameLayout(ui.context).apply {
            // A 38dp circle inside a 48dp touch area.
            background = InsetDrawable(ui.pressable(ui.shape(ui.p.surface, 99f, ui.p.line), 99f), ui.dp(5))
            contentDescription = if (h.syncing) "Syncing" else "Sync with Spectrum"
            val icon = ui.icon(R.drawable.ic_refresh, ui.p.muted, 18f)
            addView(icon, FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER))
            if (h.syncing) icon.animate().rotationBy(360f * 20).setDuration(20_000).setInterpolator(LinearInterpolator()).start()
            setOnClickListener { if (!h.syncing) h.refresh() }
            layoutParams = LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { marginEnd = -ui.dp(5) }
        }
    }

    private fun header(h: SpectrumHost, title: String, meta: String) =
        pageHeader(h.ui, title, meta, action = if (SpectrumStore.signedIn(h.ui.context)) refreshButton(h) else null)

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

    /**
     * A round course-colour dot for a top-aligned row: its baseline is set so that, lined up with
     * [text]'s first baseline, the dot is centred on the capital letters.
     */
    fun courseDot(ui: Ui, color: Int, text: TextView): View {
        val size = ui.dp(10)
        val caps = Rect().also { text.paint.getTextBounds("H", 0, 1, it) }.height()
        return object : View(ui.context) {
            override fun getBaseline() = (size + caps) / 2
        }.apply {
            background = ui.shape(color, 99f)
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    private fun empty(ui: Ui, text: String) = ui.text(text, 15f, ui.p.muted).apply {
        gravity = Gravity.CENTER
        setPadding(ui.dp(8), ui.dp(32), ui.dp(8), ui.dp(32))
    }

    // --- Due -------------------------------------------------------------------------------

    /**
     * What's due, as in redesign 1i (in the app's own fonts): open attendance as a band to tap, then
     * one ruled row per deadline, led by how long is left. Pull down to sync.
     */
    fun due(
        h: SpectrumHost,
        dueItems: List<JSONObject> = SpectrumStore.array(h.ui.context, "due").objects(),
        sessions: List<JSONObject> = SpectrumStore.array(h.ui.context, "attendance").objects(),
    ): View {
        val ui = h.ui
        val p = ui.p
        val page = ui.column()
        if (!SpectrumStore.signedIn(ui.context)) {
            page.addView(header(h, "Due", "Assignments and quizzes from Spectrum"))
            page.addView(connect(h, "See what's due and get reminders 24 h and 2 h before."))
            return page
        }
        val now = System.currentTimeMillis()
        val items = dueItems.sortedBy { it.getLong("due") }
        page.addView(pageHeader(ui, "Due", null))
        problem(h, page)
        backgroundTip(h, page)
        attendance(h, page, sessions)
        if (items.isEmpty()) {
            page.addView(empty(ui, if (SpectrumStore.lastSync(ui.context) > 0) "Nothing due on Spectrum right now." else "Syncing with Spectrum…"))
            return page
        }
        // Ruled inside only, as in 1i: lines between the rows and down the middle, none above or below.
        for ((n, i) in items.withIndex()) {
            if (n > 0) page.addView(rule(ui))
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
                setOnClickListener { h.openUrl(i.optString("url")) }
            }
            // Left: how long is left, as large as the cell's width allows (at most 56sp: "2 d", "12 h", "40 min"
            // smaller), and when it's due, centred in the cell's height.
            row.addView(ui.column().apply {
                setPadding(0, ui.dp(14), ui.dp(4), ui.dp(14))
                addView(ui.text(if (overdue) "Late" else span(due), 34f, color, ui.f.sign).apply {
                    val fit = textSize * ui.dp(76) / paint.measureText(text.toString())
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, minOf(fit, ui.dp(56f).toFloat()))
                })
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
            page.addView(row)
        }
        return page
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

    // --- Grades ----------------------------------------------------------------------------

    /**
     * Marks released on Spectrum. Courses with marks come first, the one with the newest mark on top;
     * courses with none yet are listed compactly after them.
     */
    /**
     * Marks grouped by course as in redesign 1j, the newest marks' course first, then the courses with
     * nothing marked yet. Pull down to sync. Attendance isn't shown: it's in Due.
     */
    fun grades(h: SpectrumHost, items: List<JSONObject> = SpectrumStore.array(h.ui.context, "grades").objects()): View {
        val ui = h.ui
        val p = ui.p
        val page = ui.column()
        if (!SpectrumStore.signedIn(ui.context)) {
            page.addView(header(h, "Grades", "Your marks from Spectrum"))
            page.addView(connect(h, "See each mark as soon as a lecturer releases it, with a notification."))
            return page
        }
        // Unmarked items named for other occurrences (other groups' submissions) are left out too.
        val occurrences = SpectrumStore.occurrences(ui.context)
        fun mine(code: String) = occurrences.optInt(code).takeIf { it > 0 } ?: h.timetable.courses.firstOrNull { it.code == code }?.occ ?: 0
        val all = items.filter { g ->
            !SpectrumSync.isAttendance(g) && !(g.isNull("raw") && AttendanceMatch.othersOnly(g.optString("name"), mine(g.optString("code"))))
        }
        page.addView(pageHeader(ui, "Grades", null))
        problem(h, page)
        if (all.isEmpty()) {
            page.addView(empty(ui, if (SpectrumStore.lastSync(ui.context) > 0) "Spectrum lists no grade items for your courses." else "Syncing with Spectrum…"))
            return page
        }
        val byCourse = all.groupBy { it.getInt("courseId") }
        val (marked, unmarked) = byCourse.entries.partition { (_, items) -> items.any { !it.isNull("raw") && !it.optBoolean("total") } }
        // A course whose only item was attendance (or that has only its total) has nothing to wait for.
        val waiting = unmarked.filter { (_, items) -> items.any { !it.optBoolean("total") } }
        if (marked.isEmpty()) {
            page.addView(card(ui).apply {
                addView(ui.text("No marks released yet", 17f, p.text, ui.f.bodyBold))
                addView(ui.text("When a lecturer releases a mark on Spectrum, it shows here and you get a notification.", 14.5f, p.muted), ui.wide(top = 6f))
            }, ui.wide(bottom = 20f))
        }
        for ((_, items) in marked.sortedByDescending { (_, items) -> items.maxOf { it.getLong("gradedAt") } }) {
            page.addView(courseMarks(h, items), ui.wide(bottom = 20f))
        }
        if (waiting.isNotEmpty()) {
            page.addView(ui.label("Not marked yet", p.text, 11f).apply { setPadding(0, 0, 0, ui.dp(6)) })
            page.addView(rule(ui))
            for ((courseId, items) in waiting) {
                val left = items.filter { !it.optBoolean("total") }
                val head = courseLine(h, items.first(), "${left.size} ${if (left.size == 1) "item" else "items"}", 14f, p.text, ui.f.body).apply { setPadding(0, ui.dp(11), 0, ui.dp(11)) }
                page.addView(unmarked(h, head, left, "unmarked-$courseId"))
                page.addView(rule(ui))
            }
        }
        return page
    }

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
                setPadding(ui.dp(18), ui.dp(8), 0, ui.dp(8))
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

    /** A course on one line: dot, code, name (cut short to fit) and [end] in small mono at the end. */
    private fun courseLine(h: SpectrumHost, item: JSONObject, end: String, nameSize: Float, nameColor: Int, nameFace: android.graphics.Typeface): LinearLayout {
        val ui = h.ui
        val code = item.optString("code")
        return ui.row(gap = 8f).apply {
            addView(View(ui.context).apply { background = ui.shape(colorOf(h, code), 99f) }, LinearLayout.LayoutParams(ui.dp(10), ui.dp(10)))
            if (code.isNotEmpty()) addView(ui.text(code, 13f, ui.p.courseText(colorOf(h, code)), ui.f.monoBold))
            addView(ui.text(courseName(h, item.optString("course"), code), nameSize, nameColor, nameFace, lines = 1), ui.grow())
            addView(ui.text(end, 11f, ui.p.muted, ui.f.mono))
        }
    }

    /**
     * One course's marks as in 1j: a ruled header line with how many items are marked, then a card per
     * mark, newest first, tagged NEW until seen, then the course total and what's still to be marked.
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
        box.addView(courseLine(h, first, "${marks.size} of $count", 14f, p.text, ui.f.bodySemi).apply { setPadding(0, 0, 0, ui.dp(8)) })
        box.addView(rule(ui))
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

    // --- News ------------------------------------------------------------------------------

    /**
     * Announcements as in redesign 1m: a For me / All switch by the heading, then every post as a ruled
     * row (course, when, subject, author) that opens in place to its text and files. "For me" leaves out
     * posts for other occurrences (`forMe`, set by the sync). Pull down to sync.
     */
    fun news(h: SpectrumHost): View {
        val ui = h.ui
        val p = ui.p
        val page = ui.column()
        if (!SpectrumStore.signedIn(ui.context)) {
            page.addView(header(h, "News", "Announcements from your courses"))
            page.addView(connect(h, "Get a notification when a lecturer posts an announcement."))
            return page
        }
        val all = SpectrumStore.array(ui.context, "news").objects().sortedByDescending { it.getLong("time") }
        val mine = all.filter { it.optBoolean("forMe", true) }
        val prefs = ui.context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
        val showAll = prefs.getBoolean("newsAll", false)
        val switch = segmented(ui, listOf("For me · ${mine.size}", "All · ${all.size}"), if (showAll) 1 else 0) { i ->
            prefs.edit().putBoolean("newsAll", i == 1).apply()
            h.render()
        }
        page.addView(pageHeader(ui, "News", null, action = switch))
        problem(h, page)
        val list = if (showAll) all else mine
        if (list.isEmpty()) {
            val synced = SpectrumStore.lastSync(ui.context) > 0
            page.addView(empty(ui, when {
                !synced -> "Syncing with Spectrum…"
                all.isEmpty() -> "No announcements in the past month."
                else -> "Nothing for your occurrence in the past month. All shows the others' too."
            }))
            if (synced) page.addView(olderNews(h))
            return page
        }
        // Every post the same, as 1m's rows; ruled inside only (a line between posts, none above or below).
        for ((n, a) in list.withIndex()) {
            if (n > 0) page.addView(rule(ui))
            page.addView(newsRow(h, a))
        }
        page.addView(olderNews(h), ui.wide(top = 8f))
        return page
    }

    /** A row of options with the chosen one filled in the accent colour (1m's For me / All switch). */
    private fun segmented(ui: Ui, options: List<String>, chosen: Int, onChoose: (Int) -> Unit) = ui.row(gap = 2f).apply {
        background = ui.shape(ui.p.surface2, 12f)
        setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
        for ((i, label) in options.withIndex()) {
            val on = i == chosen
            addView(ui.text(label, 13f, if (on) ui.p.accentInk else ui.p.muted, ui.f.bodySemi).apply {
                gravity = Gravity.CENTER
                minHeight = ui.dp(36)
                setPadding(ui.dp(12), 0, ui.dp(12), 0)
                background = if (on) ui.shape(ui.p.accent, 10f) else ui.pressable(ui.shape(Color.TRANSPARENT, 10f), 10f)
                contentDescription = "$label${if (on) ", shown" else ""}"
                if (!on) setOnClickListener { Haptics.tick(this); onChoose(i) }
            })
        }
    }

    /** "NEW  WIX1002 ··· Today 09:12": a post's first line; [extra] goes at the end (a row's chevron). */
    private fun newsTop(h: SpectrumHost, a: JSONObject, size: Float, extra: View? = null) = h.ui.row(gap = 8f).apply {
        val ui = h.ui
        val code = a.optString("code")
        if (a.getInt("id").toString() !in h.seenNews) addView(ui.chip("New", ui.p.accent, ui.p.accentInk).apply { setPadding(ui.dp(6), ui.dp(1), ui.dp(6), ui.dp(1)) })
        addView(ui.text(code.ifEmpty { "Spectrum" }, size, ui.p.courseText(colorOf(h, code)), ui.f.monoBold))
        if (!a.optBoolean("forMe", true)) addView(ui.text("other occurrence", 11f, ui.p.muted))
        addView(ui.space(), ui.spacer())
        addView(ui.text("${dayLabel(a.getLong("time"))} ${hm(a.getLong("time"))}", size, ui.p.muted, ui.f.mono))
        extra?.let { addView(it) }
    }

    private fun author(a: JSONObject) = listOf(a.optString("author").replace(TRAILING_DOT, ""), if (a.optString("code").isEmpty()) a.optString("course") else "")
        .filter { it.isNotBlank() }.joinToString(" · ")

    /** A post's text and files. */
    private fun newsBody(h: SpectrumHost, a: JSONObject, into: LinearLayout) {
        val ui = h.ui
        val code = a.optString("code")
        into.addView(RichText.view(h, a.optString("html"), "n${a.getInt("id")}", code.ifEmpty { "Spectrum" }, 5, expanded = h.expanded), ui.wide(top = 8f))
        for (f in a.optJSONArray("attachments")?.objects().orEmpty()) into.addView(fileButton(h, f, code), ui.wide(top = 8f))
    }

    /** A post as a ruled row: course, when, subject and author; tapping it opens its text and files in place. */
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
            addView(ui.text(a.optString("subject"), 16f, ui.p.text, ui.f.bodySemi), ui.wide(top = 4f))
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

    /** The end of News: it only reaches back [SpectrumSync.NEWS_DAYS] days. */
    private fun olderNews(h: SpectrumHost) = h.ui.column().apply {
        setPadding(0, h.ui.dp(10), 0, h.ui.dp(8))
        addView(h.ui.text("News only shows announcements from the past month. For older ones, open the course in Courses and tap its announcements forum.", 13f, h.ui.p.muted))
        addView(link(h.ui, "Open Courses") { h.setView("files") })
    }

    // --- Courses ---------------------------------------------------------------------------

    fun courses(h: SpectrumHost): View {
        val ui = h.ui
        val p = ui.p
        val page = ui.column()
        if (!SpectrumStore.signedIn(ui.context)) {
            page.addView(header(h, "Courses", "Your Spectrum course pages"))
            page.addView(connect(h, "Read each course page and open its slides and notes in one tap."))
            // The timetable imports don't need Spectrum, and the Day tab sends first-time users here for them.
            page.addView(appActions(h, signedIn = false))
            return page
        }
        val courses = SpectrumStore.array(ui.context, "courses").objects()
        h.openCourse?.let { id -> courses.firstOrNull { it.getInt("id") == id }?.let { return coursePage(h, it) } }
        page.addView(header(h, "Courses", "${courses.size} courses · ${synced(h)}"))
        problem(h, page)
        backgroundTip(h, page)
        if (courses.isEmpty()) page.addView(empty(ui, if (SpectrumStore.lastSync(ui.context) > 0) "No current courses found on Spectrum." else "Syncing with Spectrum…"))
        for (c in courses) {
            val code = c.optString("code")
            // Dot, code and name share the first line, so a name that wraps keeps them together.
            val row = ui.row(gap = 12f, center = false).apply {
                setPadding(ui.dp(2), ui.dp(17), ui.dp(2), ui.dp(17))
                background = ui.pressable(ui.shape(Color.TRANSPARENT, 8f), 8f)
                setOnClickListener { Haptics.tick(this); h.openCourse = c.getInt("id"); h.render() }
            }
            val name = ui.text(courseName(h, c.optString("name"), code), 15f, p.text, ui.f.bodySemi)
            val codeText = code.takeIf { it.isNotEmpty() }?.let { ui.text(it, 14f, p.courseText(colorOf(h, code)), ui.f.monoBold) }
            row.addView(courseDot(ui, colorOf(h, code), codeText ?: name))
            row.addView(ui.row(gap = 8f, center = false).apply {
                codeText?.let { addView(it) }
                addView(name, ui.grow())
                // The row lines the dot up with this row's first baseline.
                baselineAlignedChildIndex = 0
            }, ui.grow())
            row.addView(ui.icon(R.drawable.ic_chevron, p.faint).apply {
                layoutParams = LinearLayout.LayoutParams(ui.dp(8), ui.dp(16)).apply { gravity = Gravity.CENTER_VERTICAL }
            })
            page.addView(row)
            page.addView(View(ui.context).apply { setBackgroundColor(p.line) }, ui.wide().apply { height = 1 })
        }
        page.addView(appActions(h, signedIn = true))
        return page
    }

    /** Timetable imports, and the Spectrum account when signed in. */
    private fun appActions(h: SpectrumHost, signedIn: Boolean) = h.ui.column(gap = 2f).apply {
        val ui = h.ui
        setPadding(0, ui.dp(22), 0, 0)
        if (signedIn) {
            val name = SpectrumStore.name(ui.context).replace(TRAILING_DOT, "").let { if (it == it.uppercase()) MayaImport.titleCase(it) else it }
            addView(ui.text("Signed in as ${name.ifBlank { "you" }}", 13f, ui.p.muted))
            if (!Notify.allowed(ui.context)) addView(link(ui, "Turn on notifications") { h.enableNotifications() })
        }
        addView(link(ui, "Get timetable from TimeEdit") { h.openTimeEdit() })
        addView(link(ui, "Import timetable from MAYA instead") { h.openMaya() })
        addView(vibrationLevel(ui), ui.wide(top = 6f, bottom = 6f))
        if (signedIn) addView(link(ui, "Sign out") { h.signOut() })
    }

    /**
     * The vibration strength ([Haptics]): each tap on the row steps Off → Light → Medium → Strong → Off
     * and plays the new strength. A three-bar meter and the name show where it is.
     */
    private fun vibrationLevel(ui: Ui): View {
        val p = ui.p
        val name = ui.text("", 14f, p.text, ui.f.bodySemi).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val bars = List(3) { View(ui.context) }
        val meter = LinearLayout(ui.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            bars.forEachIndexed { i, b -> addView(b, LinearLayout.LayoutParams(ui.dp(5), ui.dp(7 + i * 5)).apply { if (i > 0) marginStart = ui.dp(3) }) }
        }
        fun show(level: Haptics.Level) {
            name.text = level.label
            bars.forEachIndexed { i, b -> b.background = ui.shape(if (i < level.ordinal) p.accent else p.line, 2f) }
        }
        show(Haptics.level(ui.context))
        return ui.row(gap = 12f).apply {
            minimumHeight = ui.dp(56)
            background = ui.pressable(ui.shape(Color.TRANSPARENT, 12f), 12f)
            addView(ui.column(gap = 2f).apply {
                addView(ui.text("Vibration", 15f, p.text, ui.f.bodySemi))
                addView(ui.text("When you change page, go back, or swipe days. Tap to change.", 12.5f, p.muted))
            }, ui.grow())
            addView(name)
            addView(meter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(17)))
            contentDescription = "Vibration: ${Haptics.level(context).label}. Tap to change."
            setOnClickListener {
                val level = Haptics.next(Haptics.level(context))
                Haptics.setLevel(context, level)
                show(level)
                Motion.pop(meter, 0.75f)
                Haptics.tick(this)
                contentDescription = "Vibration: ${level.label}. Tap to change."
            }
        }
    }

    private fun coursePage(h: SpectrumHost, c: JSONObject): View {
        val ui = h.ui
        val p = ui.p
        val id = c.getInt("id")
        val code = c.optString("code")
        val color = colorOf(h, code)
        val page = ui.column()
        page.addView(ui.column().apply {
            setPadding(0, ui.dp(18), 0, ui.dp(14))
            addView(ui.text((code.ifEmpty { "Course" }).uppercase(), 38f, p.courseText(color), ui.f.sign))
            addView(ui.text(courseName(h, c.optString("name"), code), 13.5f, p.muted), ui.wide(top = 8f))
        })
        h.courseError(id)?.let {
            page.addView(ui.text(it, 13.5f).apply { background = ui.shape(Palette.mix(0xFFF2645F.toInt(), p.surface, 0.14f), 12f); setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10)) })
            return page
        }
        val sections = h.courseContents(id) ?: run {
            page.addView(empty(ui, "Loading the course page…"))
            return page
        }
        val folder = code.ifEmpty { "Spectrum" }
        sections.objects().forEachIndexed { si, sec ->
            val modules = sec.optJSONArray("modules")?.objects().orEmpty().filter { it.optString("kind") != "label" || hasContent(it.optString("html")) }
            val summary = sec.optString("summary")
            if (modules.isEmpty() && !hasContent(summary)) return@forEachIndexed
            page.addView(ui.label(sec.optString("name")), ui.wide(top = 22f, bottom = 8f))
            if (hasContent(summary)) page.addView(textBlock(ui, RichText.view(h, summary, "s$id-$si", folder, 6, expanded = h.expanded)), ui.wide(bottom = 8f))
            val list = ui.column(gap = 8f)
            modules.forEachIndexed { mi, m ->
                val key = "m$id-$si-$mi"
                val kind = m.optString("kind")
                if (kind == "label") {
                    list.addView(textBlock(ui, RichText.view(h, m.optString("html"), key, folder, 6, expanded = h.expanded)))
                    return@forEachIndexed
                }
                val item = ui.column()
                val files = m.optJSONArray("files")?.objects().orEmpty()
                when {
                    kind == "file" && files.size == 1 -> item.addView(fileButton(h, files[0], code, label = m.optString("name")))
                    kind == "folder" || kind == "file" -> {
                        // Opens in place; rebuilding the page would lose the scroll position.
                        val sub = ui.column(gap = 6f).apply { setPadding(ui.dp(14), ui.dp(6), 0, ui.dp(2)) }
                        for (f in files) sub.addView(fileButton(h, f, code))
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
                if (dates.isNotEmpty()) item.addView(ui.text(dates, 12f, p.muted).apply { setPadding(ui.dp(42), ui.dp(4), ui.dp(4), 0) })
                val html = m.optString("html")
                if (hasContent(html)) item.addView(RichText.view(h, html, "$key-d", folder, 3, 13f, p.muted, h.expanded).apply { setPadding(ui.dp(42), ui.dp(6), ui.dp(4), ui.dp(2)) })
                list.addView(item)
            }
            page.addView(list)
        }
        return page
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
        addView(ui.text(name, 14f), ui.grow())
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
            addView(ui.text(label ?: name, 14f), ui.grow())
            if (meta.isNotEmpty()) addView(ui.text(meta, 11f, ui.p.muted, ui.f.mono))
            setOnClickListener { h.openFile(f, code.ifEmpty { "Spectrum" }) }
        }
    }
}
