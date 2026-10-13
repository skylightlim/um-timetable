package com.skylightlim.umtimetable

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.util.Calendar
import kotlin.math.ceil

/** What the screens need from the activity. */
interface Host {
    val ui: Ui
    val timetable: Timetable
    fun now(): Now
    fun render()
    fun goDay(day: Int, dir: Int = 0)
    fun setView(view: String)
    fun openSheet(course: Course, session: Session?)
    fun openUrl(url: String)
    fun openFile(file: org.json.JSONObject, folder: String)
    /** Shows a Spectrum course's page (its id) in the Courses tab. */
    fun showCourse(id: Int)
}

/**
 * The day Day opens on: today while it still has a class to come, otherwise the next weekday with
 * classes (at 23:50 on Monday that's Tuesday, on Saturday it's Monday).
 */
fun homeDay(tt: Timetable, now: Now): Int = (now.dow + homeAhead(tt, now)) % 7

/** How many days from today [homeDay] is: 0 to 7 (7 when today's weekday is the only one with classes). */
private fun homeAhead(tt: Timetable, now: Now): Int {
    if (now.dow in 1..5 && tt.day(now.dow).any { it.end > now.min }) return 0
    return (1..7).firstOrNull { k -> (now.dow + k) % 7 in 1..5 && tt.day((now.dow + k) % 7).isNotEmpty() }
        ?: if (now.dow in 1..5) 0 else (8 - now.dow) % 7 // no classes at all: today, or the coming Monday
}

/**
 * The day strip shows the week [homeDay] is in: this week, or next week once this week's classes
 * are over and at the weekend. Days from today to [day] (1 Monday … 5 Friday) in that week: 0 is
 * today, -1 yesterday.
 */
fun daysFromToday(tt: Timetable, now: Now, day: Int): Int {
    val ahead = homeAhead(tt, now)
    return ahead + day - (now.dow + ahead) % 7
}

fun dateIn(days: Int): Calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, days) }

/** "13 Oct", [days] from today. */
fun dateText(days: Int): String = dateIn(days).let { "${it.get(Calendar.DAY_OF_MONTH)} ${MONTHS[it.get(Calendar.MONTH)]}" }

/** "12–16 Oct" (or "28 Sep – 2 Oct"): Monday to Friday of the week whose Monday is [monday] days from today. */
fun weekRange(monday: Int): String =
    if (dateIn(monday).get(Calendar.MONTH) == dateIn(monday + 4).get(Calendar.MONTH)) "${dateIn(monday).get(Calendar.DAY_OF_MONTH)}–${dateText(monday + 4)}"
    else "${dateText(monday)} – ${dateText(monday + 4)}"

/** Top-bar titles: Big Shoulders 56sp everywhere (as 1i and 1m's headings). */
private const val TITLE_SIZE = 56f

private fun titleText(ui: Ui, text: String) = ui.text(text, TITLE_SIZE, ui.p.text, ui.f.sign, lines = 1).apply { setLineSpacing(0f, 1f) }

/** A Spectrum tab's name for the top bar, over a small line (the week of the semester and today's date). */
fun pageTitle(ui: Ui, name: String, line: String?): LinearLayout = ui.column().apply {
    addView(titleText(ui, name))
    line?.let { addView(ui.label(it, ui.p.muted, 11f), ui.wide(top = 4f)) }
}

/**
 * Day's title on one line: the short day name and the date ("Wed 14 Oct"), with the Today chip. The full
 * name doesn't fit at 56sp ("Wednesday 30 Sep" is 387dp of the 361dp beside the settings button);
 * "Wed 30 Sep" and the chip take 313dp.
 */
fun dayTitle(ui: Ui, day: String, date: String, today: Boolean): LinearLayout = ui.row(gap = 12f).apply {
    addView(titleText(ui, "$day $date"))
    if (today) addView(ui.chip("Today", ui.p.accent, ui.p.accentInk, 11f).apply { setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3)) })
}

/** Week's title: the dates of the strip's week on one line. */
fun weekTitle(ui: Ui, range: String): LinearLayout = ui.column().apply { addView(titleText(ui, range)) }

/** Today in the semester: "Week 2 of 14", the break, revision or exams; null outside the calendar. */
fun semesterWeek(): String? = AcademicCalendar.week(java.time.LocalDate.now())?.let { w ->
    when (w.kind) {
        AcademicCalendar.Kind.TEACHING -> "Week ${w.n} of ${w.of}"
        AcademicCalendar.Kind.BREAK -> "Mid-semester break"
        AcademicCalendar.Kind.REVISION -> "Revision week"
        AcademicCalendar.Kind.EXAMS -> "Exams"
    }
}

object DayScreen {
    fun build(h: Host, day: Int): View {
        val ui = h.ui
        val p = ui.p
        val now = h.now()
        val list = h.timetable.day(day)
        val isToday = daysFromToday(h.timetable, now, day) == 0
        // The day's name and date are in the top bar.
        val page = ui.column().apply { setPadding(0, ui.dp(2), 0, 0) }
        if (day == homeDay(h.timetable, now)) page.addView(status(h, now), ui.wide(bottom = 18f))
        if (list.isEmpty()) page.addView(ui.text("No classes", 15f, p.muted), ui.wide(top = 12f))

        val agenda = ui.column(gap = 8f)
        list.forEachIndexed { i, s ->
            val prev = list.getOrNull(i - 1)
            if (prev != null && s.start > prev.end) agenda.addView(gap(ui, prev, s, isToday, now))
            val state = when {
                !isToday -> ""
                now.min >= s.end -> "past"
                now.min >= s.start -> "live"
                else -> ""
            }
            agenda.addView(slot(h, s, state))
        }
        page.addView(agenda)
        return page
    }

    /** The class in progress, or the next one, as a big yellow block with its course name. */
    private fun status(h: Host, now: Now): View {
        val ui = h.ui
        val p = ui.p
        val ink = p.accentInk
        val block = ui.column().apply {
            background = ui.pressable(ui.shape(p.accent, 18f), 18f)
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(18))
        }
        fun labels(left: String, right: String?) = block.addView(ui.row().apply {
            addView(ui.label(left, ink, 11f), ui.grow())
            right?.let { addView(ui.label(it, ink, 11f)) }
        })
        val st = h.timetable.status(now)
        if (st == null) {
            labels("No timetable yet", null)
            block.addView(ui.text("Open the Courses tab and sign in to Spectrum with your SiswaMail: the app then gets your timetable from TimeEdit.", 17f, ink, ui.f.bodySemi), ui.wide(top = 8f))
            return block
        }
        val s = st.session
        when (st.phase) {
            Phase.IN_CLASS -> labels("Now", "${durationText(st.minutesAway)} left")
            Phase.NEXT_TODAY -> labels("Next", "in ${durationText(st.minutesAway)}")
            Phase.NEXT_LATER -> labels("Next class", "in ${durationText(st.minutesAway)}")
        }
        block.addView(ui.text(s.course.name, 40f, ink, ui.f.sign, lines = 3).apply { setLineSpacing(0f, 0.95f) }, ui.wide(top = 10f))
        val info = ui.row(gap = 12f).apply { gravity = Gravity.BOTTOM }
        info.addView(ui.text("${s.course.code} · ${s.type} · ${clockText(s.start)}–${clockText(s.end)}", 12f, ink, ui.f.monoBold, caps = true), ui.grow())
        info.addView(ui.text(s.room, 30f, ink, ui.f.sign).apply { contentDescription = "${if (s.room == "Online") "Where" else "Room"}: ${s.room}" })
        block.addView(info, ui.wide(top = 8f))
        if (st.phase == Phase.IN_CLASS) {
            block.addView(ui.bar(st.progress, ink, (ink and 0xFFFFFF) or 0x2E000000), ui.wide(top = 14f).apply { height = ui.dp(4) })
            h.timetable.day(now.dow).firstOrNull { it.start > now.min }?.let { next ->
                block.addView(ui.text("Then ${next.course.code} ${next.type.lowercase()} at ${clockText(next.start)} · ${next.room}", 13f, p.inkMuted), ui.wide(top = 12f))
            }
        }
        block.setOnClickListener { h.openSheet(s.course, s) }
        return block
    }

    /** Free time between two classes, as in redesign 1a: a quiet line between its length and its times. */
    private fun gap(ui: Ui, prev: Session, next: Session, isToday: Boolean, now: Now): View {
        val p = ui.p
        val inGap = isToday && now.min >= prev.end && now.min < next.start
        val color = if (inGap) p.accentText else p.muted
        return ui.row(gap = 10f).apply {
            setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4))
            addView(ui.text("${if (inGap) "Free now" else "Free"} · ${durationText(next.start - prev.end)}", 12f, color, ui.f.mono))
            addView(View(ui.context).apply { setBackgroundColor(if (inGap) p.accent else p.line) }, LinearLayout.LayoutParams(0, ui.dp(1), 1f))
            addView(ui.text(if (inGap) "next in ${durationText(ceil(next.start - now.min).toInt())}" else "${clockText(prev.end)}–${clockText(next.start)}", 12f, color, ui.f.mono))
        }
    }

    /**
     * One class, as in redesign 1b: its times, then code · type · occurrence, the course name and the
     * lecturer, and the room large on the right. The yellow block above counts down the class in progress.
     */
    private fun slot(h: Host, s: Session, state: String): View {
        val ui = h.ui
        val p = ui.p
        val c = s.course.color
        val tut = s.isTutorial
        val fg = if (tut) p.text else p.ink
        val fgMuted = if (tut) p.muted else p.inkMuted

        val card = ui.row(gap = 10f).apply {
            background = ui.pressable(if (tut) ui.shape(p.courseTint(c), 16f, c, 1.5f) else ui.shape(c, 16f))
            setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
            setOnClickListener { h.openSheet(s.course, s) }
        }
        card.addView(ui.column().apply {
            addView(ui.text(clockText(s.start), 14f, fg, ui.f.monoBold))
            addView(ui.text(clockText(s.end), 12f, fg, ui.f.mono).apply { alpha = 0.7f }, ui.wide(top = 2f))
        }, LinearLayout.LayoutParams(ui.dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(ui.column().apply {
            addView(ui.text("${s.course.code} · ${s.type.uppercase()} · Occ ${s.course.occ}", 11f, if (tut) p.courseText(c) else p.ink, ui.f.monoBold))
            addView(ui.text(s.course.name, 15f, fg, ui.f.bodySemi), ui.wide(top = 3f))
            addView(ui.text(s.lecturer ?: "Lecturer not listed", 13f, fgMuted).apply { if (s.lecturer == null) setTypeface(ui.f.body, Typeface.ITALIC) }, ui.wide(top = 3f))
        }, ui.grow())
        card.addView(ui.text(s.room, 30f, fg, ui.f.sign))

        if (state == "past") card.alpha = 0.42f
        if (state != "live") return card
        // The class in progress gets an accent ring a little outside the card.
        return FrameLayout(ui.context).apply {
            background = ui.shape(Color.TRANSPARENT, 19f, p.accent, 2f)
            setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
            addView(card)
        }
    }
}

object WeekScreen {
    fun build(h: Host): View {
        val ui = h.ui
        val p = ui.p
        val tt = h.timetable
        // The week's dates are in the top bar and its week of the semester on the Week chip.
        val page = ui.column().apply { setPadding(0, ui.dp(2), 0, 0) }
        val card = ui.column().apply {
            background = ui.shape(p.surface, 18f, p.line)
            setPadding(ui.dp(2), ui.dp(10), ui.dp(6), ui.dp(8))
        }
        card.addView(WeekGrid(ui, tt, h.now()) { s -> h.openSheet(s.course, s) })
        val key = ui.row(gap = 16f).apply { setPadding(ui.dp(30), ui.dp(4), 0, 0) }
        fun keyItem(swatch: View, label: String) = ui.row(gap = 6f).apply {
            addView(swatch)
            addView(ui.text(label, 12f, p.muted))
        }
        key.addView(keyItem(View(ui.context).apply { background = ui.shape(p.text, 3f); layoutParams = LinearLayout.LayoutParams(ui.dp(14), ui.dp(10)) }, "Lecture"))
        key.addView(keyItem(View(ui.context).apply { background = ui.shape(Color.TRANSPARENT, 3f, p.text, 1.5f); layoutParams = LinearLayout.LayoutParams(ui.dp(14), ui.dp(10)) }, "Tutorial"))
        key.addView(keyItem(View(ui.context).apply { setBackgroundColor(p.accent); layoutParams = LinearLayout.LayoutParams(ui.dp(14), ui.dp(3)) }, "Now"))
        card.addView(key)
        page.addView(card)
        if (tt.sessions.isEmpty()) {
            page.addView(ui.text("No timetable yet · sign in to Spectrum in Courses", 13.5f, p.muted), ui.wide(top = 12f))
            return page
        }
        page.addView(courses(h), ui.wide(top = 30f))
        return page
    }

    /**
     * Each course with its hours a week, laid out and ordered as the Courses tab (by code): code and
     * occurrence, the name, the hours by kind of class, and the week's hours on the right; lines between
     * courses only; then the totals. A course opens its class sheet.
     */
    private fun courses(h: Host): View {
        val ui = h.ui
        val p = ui.p
        val tt = h.timetable
        val list = ui.column()
        list.addView(ui.text("COURSES", 28f, p.text, ui.f.sign).apply { letterSpacing = 0.02f }, ui.wide(bottom = 4f))
        val order = listOf("Lecture", "Tutorial")
        fun byType(sessions: List<Session>) = sessions.groupBy { it.type }.toList()
            .sortedWith(compareBy({ order.indexOf(it.first).let { i -> if (i < 0) order.size else i } }, { it.first }))
            .map { (type, ss) -> "$type ${hoursText(ss.sumOf { it.end - it.start })}" }
        tt.courses.sortedWith(compareBy({ it.code.ifEmpty { "~" } }, { it.name })).forEachIndexed { n, course ->
            if (n > 0) list.addView(View(ui.context).apply { setBackgroundColor(p.line) }, ui.wide().apply { height = 1 })
            val sessions = tt.sessions.filter { it.course == course }
            val total = sessions.sumOf { it.end - it.start }
            val sub = (byType(sessions) + listOfNotNull("online".takeIf { sessions.any { it.room == "Online" } })).joinToString(" · ")
            val codeLine = ui.row().apply {
                addView(ui.text(course.code, 12f, p.courseText(course.color), ui.f.monoBold))
                addView(ui.text(" · Occ ${course.occ}", 12f, p.faint, ui.f.mono))
                setPadding(0, 0, 0, ui.dp(2))
            }
            list.addView(ui.row(gap = 12f).apply {
                minimumHeight = ui.dp(60)
                setPadding(0, ui.dp(10), 0, ui.dp(10))
                background = ui.pressable(ui.shape(Color.TRANSPARENT, 8f), 8f)
                contentDescription = "${course.code}, occurrence ${course.occ}, ${course.name}: $sub, ${hoursText(total)} a week"
                setOnClickListener { h.openSheet(course, null) }
                addView(View(ui.context).apply { background = ui.shape(course.color, 99f) }, LinearLayout.LayoutParams(ui.dp(12), ui.dp(12)))
                addView(ui.column().apply {
                    addView(codeLine)
                    addView(ui.text(course.name, 15f, p.text, ui.f.bodySemi))
                    addView(ui.text(sub, 12.5f, p.muted), ui.wide(top = 2f))
                }, ui.grow())
                addView(ui.text(hoursText(total), 22f, p.text, ui.f.sign))
            })
        }
        // The week in total, under a heavier line.
        list.addView(View(ui.context).apply { setBackgroundColor(p.text) }, ui.wide(top = 4f).apply { height = ui.dp(1.5f) })
        val all = tt.sessions.sumOf { it.end - it.start }
        list.addView(ui.row(gap = 12f).apply {
            minimumHeight = ui.dp(60)
            setPadding(ui.dp(24), ui.dp(10), 0, ui.dp(10))
            contentDescription = "Total: ${tt.sessions.size} classes, ${hoursText(all)} a week"
            addView(ui.column().apply {
                addView(ui.text("Total", 15f, p.text, ui.f.bodyBold))
                addView(ui.text("${tt.sessions.size} ${if (tt.sessions.size == 1) "class" else "classes"} a week · ${byType(tt.sessions).joinToString(" · ")}", 12.5f, p.muted), ui.wide(top = 2f))
            }, ui.grow())
            addView(ui.text(hoursText(all), 26f, p.text, ui.f.sign))
        })
        return list
    }
}

/** The Mon–Fri grid, drawn in one view: hour lines, today's column, class blocks and the now line. */
@SuppressLint("ViewConstructor")
class WeekGrid(private val ui: Ui, private val tt: Timetable, private val now: Now, private val onTap: (Session) -> Unit) : View(ui.context) {
    private val p = ui.p
    private val hour = ui.dp(48f).toFloat()
    private val head = ui.dp(22f).toFloat()
    private val axis = ui.dp(28f).toFloat()
    private val gapX = ui.dp(4f).toFloat()
    private val padY = ui.dp(8f).toFloat()
    private val startMin = 8 * 60
    private val blocks = mutableListOf<Pair<RectF, Session>>()
    /** Today's column, when the week shown is this week (see [daysFromToday]). */
    private val today = now.dow.takeIf { it in 1..5 && daysFromToday(tt, now, it) == 0 }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ui.dp(1.5f).toFloat() }
    private val dayPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ui.f.bodyBold; textSize = sp(12f); textAlign = Paint.Align.CENTER }
    private val axisPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ui.f.mono; textSize = sp(10f); color = p.faint; textAlign = Paint.Align.RIGHT }
    private val codePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ui.f.monoBold; textSize = sp(9f) }
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ui.f.bodySemi; textSize = sp(10f) }
    private val roomPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ui.f.sign; textSize = sp(15f) }
    private val nowPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ui.f.monoBold; textSize = sp(9.5f); color = p.accentInk; textAlign = Paint.Align.CENTER }

    init {
        contentDescription = "Week timetable: " + tt.sessions.joinToString("; ") { "${DAY_NAMES[it.day]} ${clockText(it.start)} ${it.course.code} ${it.type} ${it.room}" }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (head + padY * 2 + hour * 11).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        blocks.clear()
        val colW = (width - axis - gapX * 5) / 5
        val top = head + padY
        val bottom = top + hour * 11
        // Now: on a weekday between 08:00 and 19:00.
        val nowY = if (today != null && now.min >= startMin && now.min <= 19 * 60) top + (now.min - startMin) / 60 * hour else null
        val pillH = ui.dp(15f).toFloat()
        for (h in 8..19) {
            val y = top + (h - 8) * hour
            if (nowY != null && kotlin.math.abs(y - nowY) < pillH) continue // the time pill is there
            canvas.drawText("%02d".format(h), axis - ui.dp(3f), y + axisPaint.textSize * 0.35f, axisPaint)
        }
        for (d in 1..5) {
            val left = axis + gapX + (d - 1) * (colW + gapX)
            val isToday = d == today
            dayPaint.color = if (isToday) p.accentText else p.muted
            canvas.drawText(DAY_SHORT[d], left + colW / 2, head - ui.dp(8f), dayPaint)
            if (isToday) {
                fill.color = Palette.mix(p.accent, p.surface, 0.07f)
                canvas.drawRoundRect(RectF(left, top, left + colW, bottom), ui.dp(6f).toFloat(), ui.dp(6f).toFloat(), fill)
            }
            fill.color = p.line
            for (h in 0..11) canvas.drawRect(left, top + h * hour, left + colW, top + h * hour + 1, fill)
            // A faint guide across the other days, under their classes, so "now" lines up across the week.
            if (nowY != null && !isToday) {
                fill.color = p.accent
                fill.alpha = 120
                canvas.drawRect(left, nowY - ui.dp(0.5f), left + colW, nowY + ui.dp(0.5f), fill)
                fill.alpha = 255
            }
            for (s in tt.day(d)) drawBlock(canvas, s, RectF(left + ui.dp(1f), top + (s.start - startMin) / 60f * hour + 1, left + colW - ui.dp(1f), top + (s.end - startMin) / 60f * hour - 1), isToday && now.min >= s.end)
        }
        if (nowY == null || today == null) return
        // Today's line goes over the classes (a class in progress is when it matters most), in the app's
        // yellow, on a halo in the card's colour so it reads on any course colour.
        val left = axis + gapX + (today - 1) * (colW + gapX)
        fill.color = p.surface
        canvas.drawRect(left - ui.dp(2f), nowY - ui.dp(3f), left + colW, nowY + ui.dp(3f), fill)
        canvas.drawCircle(left, nowY, ui.dp(6.5f).toFloat(), fill)
        fill.color = p.accent
        canvas.drawRect(left, nowY - ui.dp(1.5f), left + colW, nowY + ui.dp(1.5f), fill)
        canvas.drawCircle(left, nowY, ui.dp(5f).toFloat(), fill)
        // The time, on the hour axis.
        val pill = RectF(0f, nowY - pillH / 2, axis + gapX / 2, nowY + pillH / 2)
        fill.color = p.accent
        canvas.drawRoundRect(pill, pillH / 2, pillH / 2, fill)
        canvas.drawText(clockText(now.min.toInt()), pill.centerX(), nowY + nowPaint.textSize * 0.35f, nowPaint)
    }

    private fun drawBlock(canvas: Canvas, s: Session, r: RectF, past: Boolean) {
        blocks += r to s
        val c = s.course.color
        val radius = ui.dp(7f).toFloat()
        val alpha = if (past) 115 else 255
        if (s.isTutorial) {
            fill.color = p.courseTint(c); fill.alpha = alpha
            canvas.drawRoundRect(r, radius, radius, fill)
            stroke.color = c; stroke.alpha = alpha
            canvas.drawRoundRect(RectF(r.left + 0.75f, r.top + 0.75f, r.right - 0.75f, r.bottom - 0.75f), radius, radius, stroke)
        } else {
            fill.color = c; fill.alpha = alpha
            canvas.drawRoundRect(r, radius, radius, fill)
        }
        fill.alpha = 255
        val textColor = if (s.isTutorial) p.text else p.ink
        val pad = ui.dp(4f).toFloat()
        val w = (r.width() - pad * 2).toInt().coerceAtLeast(1)
        canvas.save()
        canvas.clipRect(r)
        codePaint.color = textColor; codePaint.alpha = alpha
        canvas.drawText(TextUtils.ellipsize(s.course.code, codePaint, w.toFloat(), TextUtils.TruncateAt.END).toString(), r.left + pad, r.top + pad + codePaint.textSize, codePaint)
        // The name gets the lines that fit between the code and the room: the whole name where there's
        // room, else the short name, cut short only when even that doesn't fit.
        val nameTop = r.top + pad + codePaint.textSize + ui.dp(3f)
        val lines = ((r.bottom - pad - roomPaint.textSize - ui.dp(2f) - nameTop) / namePaint.fontSpacing).toInt()
        if (lines >= 1) {
            namePaint.color = textColor; namePaint.alpha = alpha
            fun layout(text: String) = StaticLayout.Builder.obtain(text, 0, text.length, namePaint, w)
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL).build()
            val full = layout(s.course.name)
            val name = if (full.getEllipsisCount(full.lineCount - 1) > 0 && s.course.short != s.course.name) layout(s.course.short) else full
            canvas.save()
            canvas.translate(r.left + pad, nameTop)
            name.draw(canvas)
            canvas.restore()
        }
        roomPaint.color = textColor; roomPaint.alpha = alpha
        if (r.height() >= roomPaint.textSize + codePaint.textSize + pad * 2) {
            canvas.drawText(TextUtils.ellipsize(s.room, roomPaint, w.toFloat(), TextUtils.TruncateAt.END).toString(), r.left + pad, r.bottom - pad, roomPaint)
        }
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) return blocks.any { it.first.contains(e.x, e.y) }
        if (e.action == MotionEvent.ACTION_UP) blocks.firstOrNull { it.first.contains(e.x, e.y) }?.let { onTap(it.second); performClick() }
        return true
    }

    override fun performClick() = super.performClick()
}

/** The class details sheet that slides up from the bottom, laid out as in redesign 1f. */
object ClassSheet {
    fun show(h: Host, context: Context, course: Course, session: Session?) {
        lateinit var bottom: BottomSheet
        val sheet = build(h, context, course, session) { then -> bottom.close(then) }
        bottom = BottomSheet(context, sheet, h.ui.p.dark)
        bottom.show()
    }

    /** The sheet's content; [close] slides the sheet away and runs what it's given. */
    fun build(h: Host, context: Context, course: Course, session: Session?, close: ((() -> Unit)?) -> Unit): View {
        val ui = h.ui
        val p = ui.p
        val sheet = ui.column().apply {
            background = GradientDrawable().apply {
                setColor(p.surface)
                val r = ui.dp(22f).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(28))
        }
        sheet.addView(View(context).apply { background = ui.shape(p.line, 4f) }, LinearLayout.LayoutParams(ui.dp(40), ui.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ui.dp(12) })
        val top = ui.row(gap = 10f)
        top.addView(View(context).apply { background = ui.shape(course.color, 99f); layoutParams = LinearLayout.LayoutParams(ui.dp(12), ui.dp(12)) })
        top.addView(ui.text(course.code, 13f, p.courseText(course.color), ui.f.monoBold))
        top.addView(ui.text("Occurrence ${course.occ}", 12f, p.muted))
        top.addView(ui.space(), ui.spacer())
        top.addView(FrameLayout(context).apply {
            // A 34dp circle inside a 48dp touch area.
            background = android.graphics.drawable.InsetDrawable(ui.pressable(ui.shape(p.surface2, 99f), 99f), ui.dp(7))
            contentDescription = "Close"
            addView(ui.icon(R.drawable.ic_close, p.muted, 16f), FrameLayout.LayoutParams(ui.dp(16), ui.dp(16), Gravity.CENTER))
            setOnClickListener { close(null) }
        }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { marginEnd = -ui.dp(7) })
        sheet.addView(top)
        sheet.addView(ui.text(course.name, 34f, p.text, ui.f.sign).apply { setLineSpacing(0f, 0.95f) }, ui.wide(top = 6f, bottom = 16f))

        // The facts on a ruled grid: labels in an 84dp column, a line under every row.
        val facts = ui.column()
        fun rule() = facts.addView(View(context).apply { setBackgroundColor(p.line) }, ui.wide().apply { height = 1 })
        fun fact(label: String, value: View, valueTop: Float = 10f) {
            facts.addView(ui.row(center = false).apply {
                addView(ui.label(label, p.muted, 10.5f).apply { setPadding(0, ui.dp(12), 0, ui.dp(12)) }, LinearLayout.LayoutParams(ui.dp(84), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(value.apply { setPadding(0, ui.dp(valueTop), 0, ui.dp(10)) }, ui.grow())
            })
            rule()
        }
        rule()
        val sessions = h.timetable.sessions.filter { it.course == course }.sortedWith(compareBy({ it.day }, { it.start }))
        if (session != null) {
            fact("When", ui.text("${DAY_NAMES[session.day]}, ${clockText(session.start)}–${clockText(session.end)} · ${durationText(session.end - session.start)}", 15f))
            fact("Type", ui.text(session.type, 15f))
            fact(if (session.room == "Online") "Where" else "Room", ui.column().apply {
                addView(ui.text(session.room, 30f, p.text, ui.f.sign))
                session.roomName?.takeIf { it != session.room }?.let { addView(ui.text(it, 12.5f, p.muted), ui.wide(top = 3f)) }
            }, valueTop = 8f)
            fact("Lecturer", ui.text(session.lecturer ?: "Not listed", 15f, if (session.lecturer == null) p.muted else p.text))
        } else {
            val names = sessions.mapNotNull { it.lecturer }.distinct()
            fact("Lecturer", ui.text(names.ifEmpty { listOf("Not listed") }.joinToString("\n"), 15f, if (names.isEmpty()) p.muted else p.text))
        }
        sheet.addView(facts)

        sheet.addView(ui.label("Every week · ${hoursText(sessions.sumOf { it.end - it.start })}", p.muted, 10.5f), ui.wide(top = 20f, bottom = 8f))
        val list = ui.column(gap = 4f)
        for (s in sessions) {
            // A session opens its day.
            val row = ui.row(gap = 8f).apply {
                background = ui.pressable(ui.shape(p.surface2, 12f, if (s == session) course.color else 0, 2f), 12f)
                setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                contentDescription = "${DAY_NAMES[s.day]} ${clockText(s.start)} to ${clockText(s.end)}, ${s.type}, ${s.room}. Opens ${DAY_NAMES[s.day]}."
                setOnClickListener { close { h.goDay(s.day) } }
            }
            row.addView(ui.text(DAY_SHORT[s.day], 15f, p.text, ui.f.bodyBold), LinearLayout.LayoutParams(ui.dp(40), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(ui.text("${clockText(s.start)}–${clockText(s.end)}", 12.5f, p.text, ui.f.mono), LinearLayout.LayoutParams(ui.dp(100), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(ui.text(s.type, 13f, p.muted), ui.grow())
            row.addView(ui.text(s.room, 18f, p.text, ui.f.sign))
            list.addView(row)
        }
        sheet.addView(list)

        // The course's page in the Courses tab, once Spectrum has synced it.
        val pageId = SpectrumStore.array(context, "courses").objects().firstOrNull { it.optString("code") == course.code }?.optInt("id")
        if (pageId != null) {
            sheet.addView(ui.row(gap = 12f).apply {
                background = ui.pressable(ui.shape(p.surface2, 12f), 12f)
                minimumHeight = ui.dp(48)
                setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
                addView(ui.text("Open course page", 15f, p.text, ui.f.bodySemi), ui.grow())
                addView(ui.icon(R.drawable.ic_arrow, p.text, 16f))
                setOnClickListener { close { h.showCourse(pageId) } }
            }, ui.wide(top = 16f))
        }
        return sheet
    }
}
