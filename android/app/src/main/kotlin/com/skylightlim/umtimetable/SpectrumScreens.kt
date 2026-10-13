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

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private fun rel(ms: Long): String {
        val m = Math.round((ms - System.currentTimeMillis()) / 60_000.0)
        val a = Math.abs(m)
        val t = when {
            a < 60 -> "$a min"
            a < 1440 -> "${Math.round(a / 60.0)} h"
            else -> "${Math.round(a / 1440.0)} d"
        }
        return if (m >= 0) "in $t" else "$t ago"
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
    private fun courseDot(ui: Ui, color: Int, text: TextView): View {
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

    fun due(h: SpectrumHost): View {
        val ui = h.ui
        val p = ui.p
        val page = ui.column()
        if (!SpectrumStore.signedIn(ui.context)) {
            page.addView(header(h, "Due", "Assignments and quizzes from Spectrum"))
            page.addView(connect(h, "See what's due and get reminders 24 h and 2 h before."))
            return page
        }
        val now = System.currentTimeMillis()
        val items = SpectrumStore.array(ui.context, "due").objects().sortedBy { it.getLong("due") }
        page.addView(header(h, "Due", "${items.count { !it.optBoolean("overdue") && it.getLong("due") > now }} upcoming · ${synced(h)}"))
        problem(h, page)
        backgroundTip(h, page)
        attendance(h, page)
        if (items.isEmpty()) {
            page.addView(empty(ui, if (SpectrumStore.lastSync(ui.context) > 0) "Nothing due on Spectrum right now." else "Syncing with Spectrum…"))
            return page
        }
        var group = ""
        for (i in items) {
            val due = i.getLong("due")
            val overdue = i.optBoolean("overdue") || due <= now
            val label = if (overdue) "Overdue" else dayLabel(due)
            if (label != group) {
                page.addView(ui.label(label), ui.wide(top = if (group.isEmpty()) 0f else 14f, bottom = 8f))
                group = label
            }
            val code = i.optString("code")
            val c = colorOf(h, code)
            val item = ui.column(gap = 4f).apply {
                background = ui.pressable(ui.shape(p.surface, 14f, p.line), 14f)
                setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
                setOnClickListener { h.openUrl(i.optString("url")) }
            }
            item.addView(ui.row(gap = 8f).apply {
                addView(ui.text(code.ifEmpty { "Spectrum" }, 12f, p.courseText(c), ui.f.monoBold), ui.grow())
                addView(ui.text("${hm(due)} · ${rel(due)}", 12f, if (overdue) 0xFFE5534B.toInt() else p.muted, ui.f.mono))
            })
            item.addView(ui.text(i.optString("name"), 16f, p.text, ui.f.bodySemi))
            item.addView(ui.text(listOf(i.optString("moduleLabel"), courseName(h, i.optString("course"), code)).filter { it.isNotBlank() }.joinToString(" · "), 12.5f, p.muted))
            page.addView(item, ui.wide(bottom = 8f))
        }
        return page
    }

    /**
     * Attendance sessions open now or starting within 3 hours. One that isn't in Spectrum's calendar
     * (`listed: false`) is the attendance page of the user's class: it may or may not be open.
     */
    private fun attendance(h: SpectrumHost, into: LinearLayout) {
        val ui = h.ui
        val p = ui.p
        val now = System.currentTimeMillis()
        for (a in SpectrumStore.array(ui.context, "attendance").objects()) {
            val start = a.getLong("start")
            val end = a.getLong("end")
            if (end <= now || start - now >= 3 * 3_600_000L) continue
            val live = start <= now
            val listed = a.optBoolean("listed", true)
            val code = a.optString("code")
            val box = ui.column(gap = 3f).apply {
                background = ui.pressable(ui.shape(p.surface, 16f, if (live && listed) p.accent else p.line, if (live && listed) 2f else 1f), 16f)
                setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
                setOnClickListener { h.openUrl(a.optString("url")) }
            }
            box.addView(ui.label(
                when {
                    !listed -> "Attendance · class ${if (live) "until ${hm(end)}" else "at ${hm(start)}"}"
                    live -> "Attendance open · until ${hm(end)}"
                    else -> "Attendance at ${hm(start)}"
                },
                p.accentText,
            ))
            box.addView(ui.row(gap = 8f).apply {
                addView(ui.text(code, 13f, p.courseText(colorOf(h, code)), ui.f.monoBold))
                addView(ui.text(a.optString("name"), 16f, p.text, ui.f.bodySemi), ui.grow())
            })
            box.addView(ui.text(
                when {
                    !listed -> "Spectrum's calendar doesn't list today's session. Tap to check your class's attendance page."
                    live -> "Tap to mark it in Spectrum"
                    else -> "Opens ${rel(start)}"
                },
                13f, p.muted,
            ))
            into.addView(box, ui.wide(bottom = 12f))
        }
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
    fun grades(h: SpectrumHost): View {
        val ui = h.ui
        val p = ui.p
        val page = ui.column()
        if (!SpectrumStore.signedIn(ui.context)) {
            page.addView(header(h, "Grades", "Your marks from Spectrum"))
            page.addView(connect(h, "See each mark as soon as a lecturer releases it, with a notification."))
            return page
        }
        // Unmarked attendance items are mostly other occurrences' (GIG1017 has one per OCC 1–15).
        val all = SpectrumStore.array(ui.context, "grades").objects().filter { !(it.isNull("raw") && it.optString("kind") == "Attendance") }
        val marks = all.filter { !it.isNull("raw") && !it.optBoolean("total") }
        page.addView(header(h, "Grades", "${marks.size} ${if (marks.size == 1) "mark" else "marks"} · ${synced(h)}"))
        problem(h, page)
        if (all.isEmpty()) {
            page.addView(empty(ui, if (SpectrumStore.lastSync(ui.context) > 0) "Spectrum lists no grade items for your courses." else "Syncing with Spectrum…"))
            return page
        }
        val byCourse = all.groupBy { it.getInt("courseId") }
        val (marked, waiting) = byCourse.entries.partition { (_, items) -> items.any { !it.isNull("raw") && !it.optBoolean("total") } }
        if (marked.isEmpty()) {
            page.addView(card(ui).apply {
                addView(ui.text("No marks released yet", 17f, p.text, ui.f.bodyBold))
                addView(ui.text("When a lecturer releases a mark on Spectrum, it shows here and you get a notification.", 14.5f, p.muted), ui.wide(top = 6f))
            }, ui.wide(bottom = 18f))
        }
        for ((_, items) in marked.sortedByDescending { (_, items) -> items.maxOf { it.getLong("gradedAt") } }) {
            page.addView(courseMarks(h, items), ui.wide(bottom = 22f))
        }
        if (waiting.isNotEmpty()) {
            page.addView(ui.label("Not marked yet"), ui.wide(top = if (marked.isEmpty()) 0f else 6f, bottom = 4f))
            for ((_, items) in waiting) {
                val first = items.first()
                val code = first.optString("code")
                val n = items.count { !it.optBoolean("total") }
                val row = ui.row(gap = 12f, center = false).apply { setPadding(ui.dp(2), ui.dp(14), ui.dp(2), ui.dp(14)) }
                val name = ui.text(courseName(h, first.optString("course"), code), 15f, p.text, ui.f.bodySemi)
                val codeText = code.takeIf { it.isNotEmpty() }?.let { ui.text(it, 14f, p.courseText(colorOf(h, code)), ui.f.monoBold) }
                row.addView(courseDot(ui, colorOf(h, code), codeText ?: name))
                row.addView(ui.row(gap = 8f, center = false).apply {
                    codeText?.let { addView(it) }
                    addView(name, ui.grow())
                    baselineAlignedChildIndex = 0
                }, ui.grow())
                row.addView(ui.text("$n ${if (n == 1) "item" else "items"}", 12.5f, p.muted, ui.f.mono).apply { (layoutParams as? LinearLayout.LayoutParams)?.gravity = Gravity.CENTER_VERTICAL })
                page.addView(row)
                page.addView(View(ui.context).apply { setBackgroundColor(p.line) }, ui.wide().apply { height = 1 })
            }
        }
        return page
    }

    /** One course's marks, newest first, then what's still to be marked. */
    private fun courseMarks(h: SpectrumHost, items: List<JSONObject>): View {
        val ui = h.ui
        val p = ui.p
        val first = items.first()
        val code = first.optString("code")
        val color = colorOf(h, code)
        val box = ui.column(gap = 8f)
        val marks = items.filter { !it.isNull("raw") && !it.optBoolean("total") }.sortedByDescending { it.getLong("gradedAt") }
        val total = items.firstOrNull { it.optBoolean("total") && !it.isNull("raw") }
        val title = ui.row(gap = 12f, center = false)
        val name = ui.text(courseName(h, first.optString("course"), code), 15f, p.text, ui.f.bodySemi)
        val codeText = code.takeIf { it.isNotEmpty() }?.let { ui.text(it, 14f, p.courseText(color), ui.f.monoBold) }
        title.addView(courseDot(ui, color, codeText ?: name))
        title.addView(ui.row(gap = 8f, center = false).apply {
            codeText?.let { addView(it) }
            addView(name, ui.grow())
            baselineAlignedChildIndex = 0
        }, ui.grow())
        box.addView(title)
        val count = items.count { !it.optBoolean("total") }
        box.addView(ui.text(listOfNotNull("${marks.size} of $count marked", total?.let { "course total ${SpectrumSync.markText(it)}" }).joinToString(" · "), 12.5f, p.muted).apply { setPadding(ui.dp(24), 0, 0, ui.dp(2)) })
        for (g in marks) {
            val isNew = SpectrumSync.gradeKey(g) !in h.seenGrades
            val item = ui.column(gap = 4f).apply {
                background = ui.pressable(ui.shape(p.surface, 14f, if (isNew) p.accent else p.line, if (isNew) 1.5f else 1f), 14f)
                setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
                g.optString("url").takeIf { it.isNotEmpty() }?.let { url -> setOnClickListener { h.openUrl(url) } }
            }
            val word = g.optString("word")
            val row = ui.row(gap = 12f)
            row.addView(ui.column().apply {
                addView(ui.text(g.optString("name"), 16f, p.text, ui.f.bodySemi))
                val markedOn = g.getLong("gradedAt").takeIf { it > 0 }?.let { dayLabel(it) }?.let { d -> "marked ${if (d in setOf("Today", "Yesterday")) d.lowercase() else d}" }
                addView(ui.text(listOfNotNull(g.optString("kind").ifEmpty { null }, markedOn).joinToString(" · "), 12.5f, p.muted), ui.wide(top = 3f))
            }, ui.grow())
            row.addView(ui.column().apply {
                gravity = Gravity.END
                if (word.isNotEmpty()) {
                    addView(ui.text(word, 22f, p.accentText, ui.f.sign))
                } else {
                    fun num(x: Double) = "%.2f".format(x).trimEnd('0').trimEnd('.')
                    addView(ui.row().apply {
                        gravity = Gravity.BOTTOM or Gravity.END
                        addView(ui.text(num(g.getDouble("raw")), 30f, p.accentText, ui.f.sign))
                        addView(ui.text(" / ${num(g.optDouble("max"))}", 13f, p.muted, ui.f.mono).apply { setPadding(0, 0, 0, ui.dp(4)) })
                    })
                    g.optString("percent").takeIf { it.isNotEmpty() && it != "-" }?.let { addView(ui.text(it, 12f, p.muted, ui.f.mono)) }
                }
            })
            item.addView(row)
            val feedback = g.optString("feedback")
            if (hasContent(feedback)) item.addView(RichText.view(h, feedback, "g${g.getInt("id")}", code.ifEmpty { "Spectrum" }, 3, 13.5f, p.muted, h.expanded), ui.wide(top = 4f))
            box.addView(item)
        }
        val left = items.filter { it.isNull("raw") && !it.optBoolean("total") }
        if (left.isNotEmpty()) {
            box.addView(ui.text("Not marked yet: " + left.joinToString(", ") { it.optString("name") }, 12.5f, p.muted).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(ui.dp(2), ui.dp(2), 0, 0)
            })
        }
        return box
    }

    // --- News ------------------------------------------------------------------------------

    fun news(h: SpectrumHost): View {
        val ui = h.ui
        val p = ui.p
        val page = ui.column()
        if (!SpectrumStore.signedIn(ui.context)) {
            page.addView(header(h, "News", "Announcements from your courses"))
            page.addView(connect(h, "Get a notification when a lecturer posts an announcement."))
            return page
        }
        val all = SpectrumStore.array(ui.context, "news").objects()
        // Announcements posted only to other occurrences' groups go in one folded row at the end.
        val list = all.filter { it.optBoolean("forMe", true) }
        val others = all - list.toSet()
        val unread = list.count { it.getInt("id").toString() !in h.seenNews }
        page.addView(header(h, "News", "${if (unread > 0) "$unread new · " else ""}${synced(h)}"))
        problem(h, page)
        if (all.isEmpty()) {
            val synced = SpectrumStore.lastSync(ui.context) > 0
            page.addView(empty(ui, if (synced) "No announcements in the past two weeks." else "Syncing with Spectrum…"))
            if (synced) page.addView(olderNews(h))
            return page
        }
        list.forEach { page.addView(newsCard(h, it), ui.wide(bottom = 10f)) }
        if (others.isNotEmpty()) page.addView(otherOccurrences(h, others))
        page.addView(olderNews(h))
        return page
    }

    /** Announcements for other occurrences, behind one row that opens in place. */
    private fun otherOccurrences(h: SpectrumHost, items: List<JSONObject>): View {
        val ui = h.ui
        val box = ui.column()
        val cards = ui.column().apply { items.forEach { addView(newsCard(h, it), ui.wide(bottom = 10f)) } }
        val label = ui.text("For other occurrences · ${items.size} ${if (items.size == 1) "announcement" else "announcements"}", 13.5f, ui.p.muted, ui.f.bodySemi)
        val key = "news-others"
        val chevron = Motion.chevron(ui, key in h.expanded)
        cards.visibility = if (key in h.expanded) View.VISIBLE else View.GONE
        box.addView(ui.row().apply {
            minimumHeight = ui.dp(48)
            background = ui.pressable(ui.shape(ui.p.surface2, 12f), 12f)
            setPadding(ui.dp(14), 0, ui.dp(16), 0)
            addView(label, ui.grow())
            addView(chevron, LinearLayout.LayoutParams(ui.dp(8), ui.dp(16)))
            setOnClickListener {
                Motion.resize(this)
                val open = h.expanded.add(key) || !h.expanded.remove(key)
                cards.visibility = if (open) View.VISIBLE else View.GONE
                Motion.turn(chevron, open)
            }
        }, ui.wide(bottom = 10f))
        box.addView(cards)
        return box
    }

    private fun newsCard(h: SpectrumHost, a: JSONObject): View {
        val ui = h.ui
        val p = ui.p
        val code = a.optString("code")
        val c = colorOf(h, code)
        val isNew = a.getInt("id").toString() !in h.seenNews
        val box = ui.column(gap = 6f).apply {
            background = ui.shape(p.surface, 16f, p.line)
            setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
        }
        box.addView(ui.row(gap = 8f).apply {
            addView(ui.text(code.ifEmpty { "Spectrum" }, 12f, p.courseText(c), ui.f.monoBold), ui.grow())
            addView(ui.text("${dayLabel(a.getLong("time"))} · ${hm(a.getLong("time"))}", 12f, p.muted, ui.f.mono))
            if (isNew) addView(View(ui.context).apply { background = ui.shape(p.accent, 99f) }, LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)))
        })
        box.addView(ui.text(a.optString("subject"), 16f, p.text, ui.f.bodySemi))
        val author = a.optString("author").replace(TRAILING_DOT, "")
        box.addView(ui.text(listOf(author, if (code.isEmpty()) a.optString("course") else "").filter { it.isNotBlank() }.joinToString(" · "), 12.5f, p.muted))
        box.addView(RichText.view(h, a.optString("html"), "n${a.getInt("id")}", code.ifEmpty { "Spectrum" }, 5, expanded = h.expanded))
        val files = a.optJSONArray("attachments")?.objects().orEmpty()
        if (files.isNotEmpty()) {
            val row = ui.column(gap = 6f)
            for (f in files) row.addView(fileButton(h, f, code, compact = true))
            box.addView(row)
        }
        return box
    }

    /** The end of News: it only reaches back [SpectrumSync.NEWS_DAYS] days. */
    private fun olderNews(h: SpectrumHost) = h.ui.column().apply {
        setPadding(0, h.ui.dp(10), 0, h.ui.dp(8))
        addView(h.ui.text("News only shows announcements from the past two weeks. For older ones, open the course in Courses and tap its announcements forum.", 13f, h.ui.p.muted))
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
                setOnClickListener { h.openCourse = c.getInt("id"); h.render() }
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
        if (signedIn) addView(link(ui, "Sign out") { h.signOut() })
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
