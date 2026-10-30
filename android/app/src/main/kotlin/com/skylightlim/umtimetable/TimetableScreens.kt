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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
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
import com.skylightlim.umtimetable.Motion.spring
import java.time.LocalDate
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
    /** Weeks from the strip's own week ([daysFromToday]) to the one shown: swiping on Week moves it. */
    val weekShift: Int get() = 0
    /**
     * Opens the plan editor ([PlanSheet]) out of the week grid's cell [from] (screen coordinates) for
     * [plan] ([isNew] if it isn't saved yet); it can run to [maxEnd] at most; an hour is [hourPx] tall.
     */
    fun openPlan(from: RectF, plan: Plan, isNew: Boolean, maxEnd: Int, hourPx: Float) = Unit
}

/** Days from today to [day] (1 Monday … 5 Friday) of the week shown, [Host.weekShift] weeks on from the strip's own. */
fun Host.daysTo(day: Int): Int = daysFromToday(timetable, now(), day) + 7 * weekShift

/** The date of [day] (1 Monday … 5 Friday) in the week shown. */
fun Host.dateOf(day: Int): LocalDate = LocalDate.now().plusDays(daysTo(day).toLong())

/** A week of UM's calendar with no classes: "Mid-semester break", "Revision week", "Exams"; null for a teaching week. */
fun weekOff(date: LocalDate): String? = when (AcademicCalendar.week(date)?.kind) {
    AcademicCalendar.Kind.BREAK -> "Mid-semester break"
    AcademicCalendar.Kind.REVISION -> "Revision week"
    AcademicCalendar.Kind.EXAMS -> "Exams"
    else -> null
}

/**
 * Why a day or week has no classes: a public holiday in Kuala Lumpur ([holiday]), or UM's break,
 * revision or exam week ([week]). A card in the accent's tint, above the day's classes or the week grid.
 */
fun offCard(ui: Ui, holiday: Holidays.Holiday?, week: String?): View = ui.column().apply {
    val p = ui.p
    background = ui.shape(Palette.mix(p.accent, p.surface, 0.16f), 18f)
    setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(16))
    addView(ui.label(if (holiday != null) "Public holiday" else "UM calendar", p.accentText, 11f))
    addView(ui.text(holiday?.label ?: week.orEmpty(), 26f, p.text, ui.f.sign), ui.wide(top = 4f))
    val sub = if (holiday != null) listOfNotNull("In Kuala Lumpur", week, "the date may still move".takeIf { holiday.tentative })
    else listOf("No classes this week")
    addView(ui.text(sub.joinToString(" · "), 13f, p.muted), ui.wide(top = 2f))
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

/** The day strip's last cell, after Monday to Friday (1–5): the Week grid. */
const val WEEK_CELL = 6

/**
 * The strip cell a swipe of [dir] (+1 left, -1 right) moves to from [cell]: Monday to Friday, then the
 * Week grid, round again to Monday (and back from Monday to Week).
 */
fun stripCell(cell: Int, dir: Int): Int = Math.floorMod(cell - 1 + dir, WEEK_CELL) + 1

fun dateIn(days: Int): Calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, days) }

/** "13 Oct", [days] from today. */
fun dateText(days: Int): String = dateIn(days).let { "${it.get(Calendar.DAY_OF_MONTH)} ${MONTHS[it.get(Calendar.MONTH)]}" }

/** "12–16 Oct" (or "28 Sep – 2 Oct"): Monday to Friday of the week whose Monday is [monday] days from today. */
fun weekRange(monday: Int): String =
    if (dateIn(monday).get(Calendar.MONTH) == dateIn(monday + 4).get(Calendar.MONTH)) "${dateIn(monday).get(Calendar.DAY_OF_MONTH)}–${dateText(monday + 4)}"
    else "${dateText(monday)} – ${dateText(monday + 4)}"

/** Top-bar titles: Big Shoulders 56sp everywhere (as 1i and 1m's headings). */
private const val TITLE_SIZE = 56f

/** Day's widest title: the longest day name with a wide date. Every tab's title is sized by it. */
private const val DAY_WIDEST = "Wednesday 30 Sep"

/**
 * A top-bar title at 56sp, or smaller when [widest] (the longest title shown in its place) wouldn't fit the
 * width it's given. Every tab's is sized by Day's widest, so each title is the same size as Day's and the
 * top bar doesn't change height going from tab to tab (Day's shrank to fit while "Due" stayed at 56sp).
 * At 360dp they come out at about 47sp; a title wider than Day's widest ("Attendance log" isn't) shrinks
 * further to fit.
 */
/**
 * The small line's place under a title: wrapping its text too. A full-width child in a column whose other
 * child (the title) wraps gets the column's width, which comes from the title alone, and was cut short.
 */
private fun underTitle(ui: Ui) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(4) }

private fun titleText(ui: Ui, text: String, widest: String = DAY_WIDEST) =
    ui.fitText(text, TITLE_SIZE, ui.p.text, ui.f.sign, widest = widest, lines = 1).apply {
        setLineSpacing(0f, 1f)
        // Wrapping its text: as a column's default full-width child, the column measured it again at its own
        // text's width, and every title shorter than "Wednesday 30 Sep" shrank (Friday's most).
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

/** A Spectrum tab's name for the top bar, over a small line (the week of the semester and today's date). */
fun pageTitle(ui: Ui, name: String, line: String?): LinearLayout = ui.column().apply {
    addView(titleText(ui, name))
    line?.let { addView(ui.label(it, ui.p.muted, 11f), underTitle(ui)) }
}

/**
 * Day's title on one line: the full day name and the date ("Wednesday 14 Oct"). "Today" leads the small
 * line under it, in the accent colour: beside the title, as a chip, it left the name no room.
 */
fun dayTitle(ui: Ui, day: String, date: String, today: Boolean, line: String?): LinearLayout = ui.column().apply {
    addView(titleText(ui, "$day $date", DAY_WIDEST))
    addView(smallLine(ui, line).apply {
        if (!today) return@apply
        val lead = "TODAY"
        text = android.text.SpannableString(if (line == null) lead else "$lead · ${line.uppercase()}").apply {
            setSpan(android.text.style.ForegroundColorSpan(ui.p.accentText), 0, lead.length, 0)
        }
        isAllCaps = false
        visibility = View.VISIBLE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }, underTitle(ui))
}

/** Week's title: the dates of the strip's week, with [line] (its deadlines) under them. */
fun weekTitle(ui: Ui, range: String, line: String?): LinearLayout = ui.column().apply {
    addView(titleText(ui, range))
    addView(smallLine(ui, line), underTitle(ui))
}

/**
 * The small line under a title, as the other tabs have. With nothing to say it stays, unseen, so every
 * title is as tall and the day strip starts where those tabs' first panel does.
 */
private fun smallLine(ui: Ui, text: String?) = ui.label(text ?: "Week", ui.p.muted, 11f).apply {
    if (text == null) {
        visibility = View.INVISIBLE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}

/**
 * Where on a plan from [start] to [end] (minutes) a finger at half hour [at] holds it: how far below its
 * start, in minutes, within the plan (its last half hour at most).
 */
internal fun heldAt(at: Int, start: Int, end: Int) = (at - start).coerceIn(0, maxOf(0, end - start - 30))

/**
 * Where a plan held [grab] minutes below its start begins with the finger at half hour [at]: 08:00 to the grid's
 * [last] half hour (18:30, or 23:30 with Week until midnight: a plan can go in the evening's slots too).
 */
internal fun draggedStart(at: Int, grab: Int, last: Int = 18 * 60 + 30) = (at - grab).coerceIn(8 * 60, last)

/** Day at a glance: "3 classes · 08:00–19:00", or "No classes". */
fun dayGlance(list: List<Session>): String =
    if (list.isEmpty()) "No classes" else "${list.size} ${if (list.size == 1) "class" else "classes"} · ${clockText(list.first().start)}–${clockText(list.last().end)}"

object DayScreen {
    /** [day]'s page; [sample] instead of the saved plans (a still drawing's made-up ones). */
    fun build(h: Host, day: Int, sample: List<Plan>? = null): View {
        val ui = h.ui
        val p = ui.p
        val now = h.now()
        val list = h.timetable.day(day)
        val isToday = h.daysTo(day) == 0
        // A public holiday, or a week of UM's calendar without classes: said first, and the classes faded.
        val holiday = Holidays.on(h.dateOf(day))
        val week = weekOff(h.dateOf(day))
        val off = holiday != null || week != null
        // The day's name and date are in the top bar.
        val page = ui.column().apply { setPadding(0, ui.dp(2), 0, 0) }
        if (off) page.addView(offCard(ui, holiday, week), ui.wide(bottom = 14f))
        // The Now block is for the week the strip starts on, not another one swiped to, nor a day off.
        if (!off && h.weekShift == 0 && day == homeDay(h.timetable, now)) page.addView(status(h, now), ui.wide(bottom = 18f))
        if (list.isEmpty()) page.addView(ui.text("No classes", 15f, p.muted), ui.wide(top = 12f))

        // The day's classes and the user's plans for that date, in time order, with the free time between.
        val date = h.dateOf(day)
        val plans = Plans.on(sample ?: Plans.all(ui.context), date)
        val items = (list.map { Triple(it.start, it.end, it as Any) } + plans.map { Triple(it.start, it.end, it as Any) }).sortedBy { it.first }
        val agenda = ui.column(gap = 8f)
        var until: Int? = null
        for ((start, end, item) in items) {
            until?.let { if (start > it) agenda.addView(gap(ui, it, start, isToday, now)) }
            until = maxOf(until ?: end, end)
            when (item) {
                is Session -> agenda.addView(slot(h, item, when {
                    off -> "past"
                    !isToday -> ""
                    now.min >= item.end -> "past"
                    now.min >= item.start -> "live"
                    else -> ""
                }))
                is Plan -> agenda.addView(planSlot(h, item, items, live = isToday && now.min >= item.start && now.min < item.end))
            }
        }
        page.addView(agenda)
        return page
    }

    /**
     * One of the user's plans, among the classes and set out as one: filled with its colour as a lecture
     * is with its course's, the times on the left, what it is in bold, then "Plan" and how it repeats and
     * reminds, where a class has its type and lecturer. Faded once it's over; ringed in the accent while
     * it's on ([live]), as the class in progress is. A tap opens its card out of this one.
     */
    private fun planSlot(h: Host, plan: Plan, day: List<Triple<Int, Int, Any>>, live: Boolean): View {
        val ui = h.ui
        val p = ui.p
        val over = plan.endsAt <= System.currentTimeMillis()
        // The ink a fill of its colour takes: the accent's own on the accent, else the course colours'.
        val fg = if (plan.color == p.accent) p.accentInk else p.ink
        val fgMuted = if (plan.color == p.accent) Palette.mix(p.accentInk, p.accent, 0.7f) else p.inkMuted
        val card = agendaCard(ui, ui.shape(plan.color, 16f)).apply {
            contentDescription = "Plan, ${clockText(plan.start)} to ${clockText(plan.end)}: ${plan.title}"
        }
        card.addView(times(ui, plan.start, plan.end, fg))
        val remind = plan.remind?.let { r -> Plan.REMINDERS.firstOrNull { it.first == r }?.second }
        val meta = SpannableStringBuilder(if (over) "Plan · done" else "Plan").apply {
            setSpan(TypefaceSpan(ui.f.bodySemi), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            listOfNotNull("every week".takeIf { plan.weekly }, remind?.let { "reminder ${it.lowercase()}" }).forEach { append(" · ").append(it) }
        }
        card.addView(ui.column().apply {
            addView(title(ui, plan.title, fg))
            addView(ui.text(meta, 13.5f, fgMuted, lines = 1), ui.wide(top = 4f))
            baselineAlignedChildIndex = 0
        }, ui.grow())
        card.setOnClickListener {
            // The free time after it (to the next class or plan, or the end of the day), and the card's height per hour for the way back.
            val maxEnd = minOf(DayEnd.minutes(ui.context), day.filter { it.third !== plan && it.first >= plan.end }.minOfOrNull { it.first } ?: Int.MAX_VALUE)
            val at = IntArray(2).also(card::getLocationOnScreen)
            val from = RectF(at[0].toFloat(), at[1].toFloat(), (at[0] + card.width).toFloat(), (at[1] + card.height).toFloat())
            h.openPlan(from, plan, false, maxEnd, card.height / ((plan.end - plan.start) / 60f))
        }
        if (over) card.alpha = 0.42f
        // Just saved from this card: it pops back into place.
        if (WeekGrid.landing == plan.id) {
            WeekGrid.landing = null
            Motion.pop(card, 0.85f)
        }
        if (!live) return card
        return FrameLayout(ui.context).apply {
            background = ui.shape(Color.TRANSPARENT, 19f, p.accent, 2f)
            setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
            addView(card)
        }
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
        block.addView(ui.fitText(s.course.name, 40f, ink, ui.f.sign, words = true, lines = 3).apply { setLineSpacing(0f, 0.95f) }, ui.wide(top = 10f))
        // Bottoms lined up, not baselines: when the class line wraps (a large system font size), its first
        // line's baseline pushed the room up out of the row, cut in half.
        val info = ui.row(gap = 12f).apply { gravity = Gravity.BOTTOM; isBaselineAligned = false }
        info.addView(ui.text("${s.course.code} · ${s.type} · ${clockText(s.start)}–${clockText(s.end)}", 12f, ink, ui.f.monoBold, caps = true), ui.grow())
        info.addView(ui.text(s.room, 30f, ink, ui.f.sign).apply { contentDescription = "${if (s.room == "Online") "Where" else "Room"}: ${s.room}" })
        block.addView(info, ui.wide(top = 8f))
        if (st.phase == Phase.IN_CLASS) {
            block.addView(ui.bar(st.progress, ink, (ink and 0xFFFFFF) or 0x2E000000), ui.wide(top = 14f).apply { height = ui.dp(4) })
            h.timetable.day(now.dow).firstOrNull { it.start > now.min }?.let { next ->
                // In the block's own ink: the course cards' dark ink was unreadable on Blue's and Red's fills
                // (and softened, Red's light fill fell to 3.5:1).
                block.addView(ui.text("Then ${next.course.code} ${next.type.lowercase()} at ${clockText(next.start)} · ${next.room}", 13f, ink), ui.wide(top = 12f))
            }
        }
        block.setOnClickListener { h.openSheet(s.course, s) }
        return block
    }

    /** Free time between two classes, as in redesign 1a: a quiet line between its length and its times. */
    /** The free time from [from] to [to] (minutes) between two classes or plans. */
    private fun gap(ui: Ui, from: Int, to: Int, isToday: Boolean, now: Now): View {
        val p = ui.p
        val inGap = isToday && now.min >= from && now.min < to
        val color = if (inGap) p.accentText else p.muted
        return ui.row(gap = 10f).apply {
            setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4))
            addView(ui.text("${if (inGap) "Free now" else "Free"} · ${durationText(to - from)}", 12f, color, ui.f.mono))
            addView(View(ui.context).apply { setBackgroundColor(if (inGap) p.accent else p.line) }, LinearLayout.LayoutParams(0, ui.dp(1), 1f))
            addView(ui.text(if (inGap) "next in ${durationText(ceil(to - now.min).toInt())}" else "${clockText(from)}–${clockText(to)}", 12f, color, ui.f.mono))
        }
    }

    /**
     * One class, as in redesign 1b, set out so it reads in one glance: a first line of when, what and
     * where (the start time, the course name in bold and the room large, on one baseline), then quieter
     * lines for the rest: the type and lecturer, and smallest the code and occurrence. One text face
     * for the words and times (tabular figures), the display face for the room only.
     */
    private fun slot(h: Host, s: Session, state: String): View {
        val ui = h.ui
        val p = ui.p
        val c = s.course.color
        val tut = s.isTutorial
        val fg = if (tut) p.text else p.ink
        val fgMuted = if (tut) p.muted else p.inkMuted

        val card = agendaCard(ui, if (tut) ui.shape(p.courseTint(c), 16f, c, 1.5f) else ui.shape(c, 16f))
        card.setOnClickListener { h.openSheet(s.course, s) }
        card.contentDescription = "${clockText(s.start)} to ${clockText(s.end)}, ${s.course.name}, ${s.type}, " +
            "${if (s.room == "Online") "online" else "room ${s.room}"}${s.lecturer?.let { ", $it" }.orEmpty()}, ${s.course.code} occurrence ${s.course.occ}"
        card.addView(times(ui, s.start, s.end, fg))
        val meta = SpannableStringBuilder(s.type).apply {
            setSpan(TypefaceSpan(ui.f.bodySemi), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(" · ")
            val at = length
            append(s.lecturer ?: "Lecturer not listed")
            if (s.lecturer == null) setSpan(StyleSpan(Typeface.ITALIC), at, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val name = title(ui, s.course.name, fg)
        card.addView(ui.column().apply {
            addView(name)
            // One line: a long name runs out of room (it's whole on the class sheet) rather than making the card taller.
            addView(ui.text(meta, 13.5f, fgMuted, lines = 1), ui.wide(top = 4f))
            addView(ui.text("${s.course.code} · Occ ${s.course.occ}", 11f, if (tut) p.courseText(c) else fgMuted, ui.f.mono, caps = true), ui.wide(top = 6f))
            baselineAlignedChildIndex = 0
        }, ui.grow())
        card.addView(room(ui, s.room, fg, name))

        if (state == "past") card.alpha = 0.42f
        if (state != "live") return card
        // The class in progress gets an accent ring a little outside the card.
        return FrameLayout(ui.context).apply {
            background = ui.shape(Color.TRANSPARENT, 19f, p.accent, 2f)
            setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
            addView(card)
        }
    }

    /** A class's or plan's card on Day: a row whose columns share their first line's baseline. */
    private fun agendaCard(ui: Ui, fill: android.graphics.drawable.Drawable) = BaselineRow(ui.context).apply {
        orientation = LinearLayout.HORIZONTAL
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply { setSize(ui.dp(12f), 1) }
        background = ui.pressable(fill, 16f)
        setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(15))
        gravity = Gravity.TOP // baselines line up only with top gravity
        isBaselineAligned = true
    }

    /**
     * A row whose children share their first baseline, as tall as they are once lined up. LinearLayout
     * lines them up but, wrapping its content, measures them as if they weren't: a short first line
     * beside the room's tall one moved the class's details down, and the last line was cut off.
     */
    private class BaselineRow(context: Context) : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) return
            val shown = (0 until childCount).map(::getChildAt).filter { it.visibility != GONE }
            val line = shown.maxOfOrNull { it.baseline } ?: return
            if (line < 0) return
            // Where layout puts each child (top gravity): down by how far its baseline is above the highest.
            val need = paddingTop + paddingBottom + shown.maxOf { v ->
                val lp = v.layoutParams as LayoutParams
                (if (v.baseline >= 0) line - v.baseline else 0) + lp.topMargin + v.measuredHeight + lp.bottomMargin
            }
            if (need > measuredHeight) setMeasuredDimension(measuredWidth, need)
        }
    }

    /** The start time, firm, over the end time, faint; as wide as "00:00" so names line up card to card. */
    private fun times(ui: Ui, start: Int, end: Int, color: Int): View {
        val first = ui.text(clockText(start), 16f, color, ui.f.bodySemi).apply { fontFeatureSettings = "tnum" }
        val wide = kotlin.math.ceil(first.paint.measureText("00:00")).toInt()
        return ui.column().apply {
            addView(first)
            addView(ui.text(clockText(end), 13f, color).apply { fontFeatureSettings = "tnum"; alpha = 0.6f }, ui.wide(top = 3f))
            baselineAlignedChildIndex = 0 // after the children: it must name one that's there
            layoutParams = LinearLayout.LayoutParams(wide, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * The room, large in the display face, the tops of its capitals level with those of the course [name]
     * beside it. On the name's baseline instead, its taller capitals stood well above the name, out of
     * line with it. It reports a baseline higher than its own by the difference in capital heights, so the
     * row lowers it by that much.
     */
    private fun room(ui: Ui, room: String, color: Int, name: android.widget.TextView): android.widget.TextView {
        fun capHeight(paint: Paint) = android.graphics.Rect().also { paint.getTextBounds("H", 0, 1, it) }.height()
        return object : android.widget.TextView(ui.context) {
            override fun getBaseline(): Int = super.getBaseline().let { if (it < 0) it else it - (capHeight(paint) - capHeight(name.paint)) }
        }.apply {
            text = room
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setTextColor(color)
            typeface = ui.f.sign
            includeFontPadding = false
        }
    }

    /** A card's title: bold, a touch tight, as large text wants. */
    private fun title(ui: Ui, text: String, color: Int) =
        ui.text(text, 18f, color, ui.f.bodyBold, spacing = -0.01f).apply { setLineSpacing(0f, 1.1f) }
}

/**
 * How late the day runs for the week grid and plans: 19:00, or (Settings' "Week until midnight") 24:00,
 * for plans in the evening. Classes end by 19:00 either way.
 */
object DayEnd {
    private const val KEY = "weekToMidnight"
    fun late(c: android.content.Context) = uiPrefs(c).getBoolean(KEY, false)
    fun setLate(c: android.content.Context, on: Boolean) = uiPrefs(c).edit().putBoolean(KEY, on).apply()
    /** The end of the day in minutes after midnight: 19:00 or 24:00. */
    fun minutes(c: android.content.Context) = if (late(c)) 24 * 60 else 19 * 60
}

object WeekScreen {
    /** The parts of a Week page that change with the week, so going to another week changes only them ([shift]). */
    class Parts(val offSlot: FrameLayout, val gridBox: FrameLayout)

    /** The week grid and the courses; [plans] instead of the saved ones (a still drawing's made-up plans). */
    fun build(h: Host, plans: List<Plan>? = null): View {
        val ui = h.ui
        val p = ui.p
        val tt = h.timetable
        // The week's dates are in the top bar and its week of the semester on the Week chip.
        val page = ui.column().apply { setPadding(0, ui.dp(2), 0, 0) }
        // A break, revision or exam week says so above the grid (whose classes are then faded).
        val offSlot = FrameLayout(ui.context)
        offCard(h)?.let { offSlot.addView(it) }
        page.addView(offSlot, ui.wide())
        val card = ui.column().apply {
            background = ui.shape(p.surface, 18f, p.line)
            setPadding(ui.dp(2), ui.dp(10), ui.dp(6), ui.dp(8))
        }
        // The grid in a box of its own: going to another week, the grids slide through it as a carousel.
        val gridBox = FrameLayout(ui.context)
        gridBox.addView(grid(h, plans))
        card.addView(gridBox, ui.wide())
        val key = ui.row(gap = 16f).apply { setPadding(ui.dp(30), ui.dp(4), 0, 0) }
        fun keyItem(swatch: View, label: String) = ui.row(gap = 6f).apply {
            addView(swatch)
            addView(ui.text(label, 12f, p.muted))
        }
        key.addView(keyItem(View(ui.context).apply { background = ui.shape(p.text, 3f); layoutParams = LinearLayout.LayoutParams(ui.dp(14), ui.dp(10)) }, "Lecture"))
        key.addView(keyItem(View(ui.context).apply { background = ui.shape(Color.TRANSPARENT, 3f, p.text, 1.5f); layoutParams = LinearLayout.LayoutParams(ui.dp(14), ui.dp(10)) }, "Tutorial"))
        key.addView(keyItem(View(ui.context).apply { setBackgroundColor(p.accent); layoutParams = LinearLayout.LayoutParams(ui.dp(14), ui.dp(3)) }, "Now"))
        card.addView(key)
        // Plans: what a long press on a free time adds.
        card.addView(ui.row(gap = 6f).apply {
            setPadding(ui.dp(30), ui.dp(8), 0, ui.dp(2))
            addView(View(ui.context).apply { background = ui.shape(Palette.mix(p.text, p.surface, 0.12f), 3f, p.muted, 1.2f, dash = 2.5f); layoutParams = LinearLayout.LayoutParams(ui.dp(14), ui.dp(10)) })
            addView(ui.text("Plan · long-press a free time to add one", 12f, p.muted))
        })
        page.addView(card)
        if (tt.sessions.isEmpty()) page.addView(ui.text("No timetable yet · sign in to Spectrum in Courses", 13.5f, p.muted), ui.wide(top = 12f))
        // Each course's hours are on its course page now, and the week's total under the Courses list.
        page.tag = Parts(offSlot, gridBox)
        return page
    }

    private fun offCard(h: Host): View? = weekOff(h.dateOf(1))?.let { FrameLayout(h.ui.context).apply { addView(offCard(h.ui, null, it)); setPadding(0, 0, 0, h.ui.dp(12)) } }

    private fun grid(h: Host, plans: List<Plan>?) =
        WeekGrid(h.ui, h.timetable, h.now(), h.daysTo(1), plans ?: Plans.inWeek(h.ui.context, h.dateOf(1)), h::openPlan) { s -> h.openSheet(s.course, s) }

    /** The week's grid now, for the swipe to drag. */
    fun grid(page: View): View? = (page.tag as? Parts)?.gridBox?.let { it.getChildAt(it.childCount - 1) }

    /**
     * [page] going to the week shown now ([Host.weekShift] has just moved, [dir] +1 for a later week):
     * only what changes changes. The grid's new week slides in from that side as the old one carries on
     * out from where the finger left it (at its speed, [pxPerSecond]), the two together like a carousel; the break card follows.
     * False if [page] isn't Week.
     */
    fun shift(page: View, h: Host, dir: Int, pxPerSecond: Float = 0f): Boolean {
        val parts = page.tag as? Parts ?: return false
        val box = parts.gridBox
        val old = box.getChildAt(box.childCount - 1)
        val grid = grid(h, null)
        box.addView(grid)
        val step = box.width + h.ui.dp(16f).toFloat()
        if (Motion.enabled && old != null && box.width > 0) {
            val from = old.translationX
            grid.translationX = from + dir * step
            // Both on one spring, already moving at the finger's speed when it let go.
            val spring = Motion.SETTLE.toward(from, -dir * step, pxPerSecond)
            old.animate().translationX(-dir * step).alpha(0.3f).spring(spring).withEndAction { box.removeView(old) }.start()
            grid.animate().translationX(0f).spring(spring).start()
        } else if (old != null) {
            box.removeView(old)
        }
        // The break card: there for a week without classes.
        parts.offSlot.removeAllViews()
        offCard(h)?.let { card ->
            parts.offSlot.addView(card)
            Motion.fade(card, 0f, 1f, 200)
        }
        return true
    }
}

/** The Mon–Fri grid, drawn in one view: hour lines, today's column, class blocks and the now line. */
@SuppressLint("ViewConstructor")
class WeekGrid(
    private val ui: Ui,
    private val tt: Timetable,
    private val now: Now,
    monday: Int,
    /** The user's plans in this week (see [Plan]): drawn in their free times, opened by a tap. */
    private val plans: List<Plan>,
    private val onPlan: (from: RectF, plan: Plan, isNew: Boolean, maxEnd: Int, hourPx: Float) -> Unit,
    private val onTap: (Session) -> Unit,
) : View(ui.context) {
    private val p = ui.p
    private val hour = ui.dp(48f).toFloat()
    private val head = ui.dp(22f).toFloat()
    private val axis = ui.dp(28f).toFloat()
    private val gapX = ui.dp(4f).toFloat()
    private val padY = ui.dp(8f).toFloat()
    private val startMin = 8 * 60
    /** 19:00, or midnight with Settings' Week until midnight ([DayEnd]); and the grid's hours from 08:00. */
    private val endMin = DayEnd.minutes(ui.context)
    private val hours = (endMin - startMin) / 60
    private val blocks = mutableListOf<Pair<RectF, Session>>()
    private val planBlocks = mutableListOf<Pair<RectF, Plan>>()
    /** Each column's date, Monday first. */
    private val dates = (0..4).map { LocalDate.now().plusDays((monday + it).toLong()) }
    /** Today's column, when the week shown (its Monday [monday] days from today) is this week. */
    private val today = now.dow.takeIf { it in 1..5 && monday + it - 1 == 0 }
    /** Each day's public holiday in Kuala Lumpur, Monday first; and whether the week is one of UM's without classes. */
    private val holidays = dates.map { Holidays.on(it) }
    private val weekOff = weekOff(LocalDate.now().plusDays(monday.toLong())) != null

    // The grid's boxes are as tall as their hours and a fifth of the width, so its text follows the system
    // font size only up to 1.15×: past that, names broke into syllables ("Pro- / gram- / ming"). A class's
    // sheet (tap it) shows everything at full size.
    private fun sp(v: Float) = v * resources.displayMetrics.density * minOf(resources.configuration.fontScale, 1.15f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ui.dp(1.5f).toFloat() }
    private val dayPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ui.f.bodyBold; textSize = sp(12f); textAlign = Paint.Align.CENTER }
    private val axisPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ui.f.mono; textSize = sp(10f); color = p.faint; textAlign = Paint.Align.RIGHT }
    private val codePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ui.f.monoBold; textSize = sp(9f) }
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ui.f.bodySemi; textSize = sp(10f) }
    private val roomPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ui.f.sign; textSize = sp(15f) }
    private val nowPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ui.f.monoBold; textSize = sp(9.5f); color = p.accentInk; textAlign = Paint.Align.CENTER }
    private val holidayPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ui.f.bodyBold; textSize = sp(10.5f); color = p.accentInk; textAlign = Paint.Align.CENTER; letterSpacing = 0.1f }
    private val hatch = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.accent; alpha = 70; strokeWidth = ui.dp(1f).toFloat() }
    private val planEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ui.dp(1.5f).toFloat()
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(ui.dp(4f).toFloat(), ui.dp(3f).toFloat()), 0f)
    }
    private val repeatIcon = context.getDrawable(R.drawable.ic_repeat)!!.mutate()
    /** A plan just saved pops into its place ([landing]). */
    private var landId = landing.also { landing = null }
    private var landScale = 1f

    init {
        contentDescription = "Week timetable: " + tt.sessions.joinToString("; ") { "${DAY_NAMES[it.day]} ${clockText(it.start)} ${it.course.code} ${it.type} ${it.room}" }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (head + padY * 2 + hour * hours).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        blocks.clear()
        planBlocks.clear()
        val colW = (width - axis - gapX * 5) / 5
        val top = head + padY
        val bottom = top + hour * hours
        // Now: on a weekday between 08:00 and the end of the day.
        val nowY = if (today != null && now.min >= startMin && now.min <= endMin) top + (now.min - startMin) / 60 * hour else null
        val pillH = ui.dp(15f).toFloat()
        for (h in startMin / 60..endMin / 60) {
            val y = top + (h - startMin / 60) * hour
            if (nowY != null && kotlin.math.abs(y - nowY) < pillH) continue // the time pill is there
            // Midnight at the bottom is 00.
            canvas.drawText("%02d".format(h % 24), axis - ui.dp(3f), y + axisPaint.textSize * 0.35f, axisPaint)
        }
        for (d in 1..5) {
            val left = axis + gapX + (d - 1) * (colW + gapX)
            val isToday = d == today
            val holiday = holidays[d - 1]
            dayPaint.color = if (isToday || holiday != null) p.accentText else p.muted
            canvas.drawText(DAY_SHORT[d], left + colW / 2, head - ui.dp(8f), dayPaint)
            if (isToday) {
                fill.color = Palette.mix(p.accent, p.surface, 0.07f)
                canvas.drawRoundRect(RectF(left, top, left + colW, bottom), ui.dp(6f).toFloat(), ui.dp(6f).toFloat(), fill)
            }
            if (holiday != null) drawHoliday(canvas, holiday, RectF(left, top, left + colW, bottom))
            fill.color = p.line
            for (h in 0..hours) canvas.drawRect(left, top + h * hour, left + colW, top + h * hour + 1, fill)
            // A faint guide across the other days, under their classes, so "now" lines up across the week.
            if (nowY != null && !isToday) {
                fill.color = p.accent
                fill.alpha = 120
                canvas.drawRect(left, nowY - ui.dp(0.5f), left + colW, nowY + ui.dp(0.5f), fill)
                fill.alpha = 255
            }
            // (Not the one being moved with the selection box: it's in the box.)
            for (plan in plans.filter { it.date == dates[d - 1] && it.id != picking?.id }) drawPlan(canvas, plan, RectF(left + ui.dp(1f), top + (plan.start - startMin) / 60f * hour + 1, left + colW - ui.dp(1f), top + (plan.end - startMin) / 60f * hour - 1))
            // Faded: today's classes that are over, and those on a holiday or in a week without classes.
            val cancelled = holiday != null || weekOff
            for (s in tt.day(d)) drawBlock(canvas, s, RectF(left + ui.dp(1f), top + (s.start - startMin) / 60f * hour + 1, left + colW - ui.dp(1f), top + (s.end - startMin) / 60f * hour - 1), cancelled || (isToday && now.min >= s.end))
            // The holiday's name goes over its (faded) classes, so it can always be read.
            if (holiday != null) drawHolidayName(canvas, holiday, RectF(left, top, left + colW, bottom))
        }
        if (nowY == null || today == null) return drawBox(canvas)
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
        drawBox(canvas)
    }

    /**
     * A name laid out in [w] px on at most [lines] lines, kept for the next grids: a week's classes are
     * the same every week, so a swipe to another week lays out nothing new. (Laying them out was most of
     * a grid's first draw.) Drawn with its own paint, coloured just before.
     */
    private fun layout(text: String, w: Int, lines: Int, hyphens: Boolean): StaticLayout {
        val key = "$text|$w|$lines|$hyphens|${namePaint.textSize}|${namePaint.typeface.hashCode()}"
        LAYOUTS.get(key)?.let { return it }
        val paint = TextPaint(namePaint)
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
            .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false)
            .setHyphenationFrequency(if (hyphens) Layout.HYPHENATION_FREQUENCY_NORMAL else Layout.HYPHENATION_FREQUENCY_NONE)
            .build().also { LAYOUTS.put(key, it) }
    }

    /** A holiday's column: tinted and hatched in the accent, under its (faded) classes. */
    private fun drawHoliday(canvas: Canvas, @Suppress("UNUSED_PARAMETER") holiday: Holidays.Holiday, col: RectF) {
        val radius = ui.dp(6f).toFloat()
        fill.color = Palette.mix(p.accent, p.surface, 0.12f)
        canvas.drawRoundRect(col, radius, radius, fill)
        canvas.save()
        canvas.clipRect(col)
        val step = ui.dp(10f).toFloat()
        var x = col.left - col.height()
        while (x < col.right) {
            canvas.drawLine(x, col.bottom, x + col.height(), col.top, hatch)
            x += step
        }
        canvas.restore()
    }

    /** The holiday's name up the middle of its column, on a solid accent pill (ink on the accent, as the Now block). */
    private fun drawHolidayName(canvas: Canvas, holiday: Holidays.Holiday, col: RectF) {
        val name = TextUtils.ellipsize(holiday.label.uppercase(), holidayPaint, col.height() - ui.dp(40f), TextUtils.TruncateAt.END).toString()
        val w = holidayPaint.measureText(name) + ui.dp(18f)
        val h = holidayPaint.textSize + ui.dp(12f)
        canvas.save()
        canvas.rotate(-90f, col.centerX(), col.centerY())
        val pill = RectF(col.centerX() - w / 2, col.centerY() - h / 2, col.centerX() + w / 2, col.centerY() + h / 2)
        fill.color = p.surface // a ring in the card's colour lifts it off the classes under it
        canvas.drawRoundRect(RectF(pill).apply { inset(-ui.dp(2f).toFloat(), -ui.dp(2f).toFloat()) }, h, h, fill)
        fill.color = p.accent
        canvas.drawRoundRect(pill, h / 2, h / 2, fill)
        canvas.drawText(name, col.centerX(), col.centerY() + holidayPaint.textSize * 0.36f, holidayPaint)
        canvas.restore()
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
            val full = layout(s.course.name, w, lines, true)
            val name = if (full.getEllipsisCount(full.lineCount - 1) > 0 && s.course.short != s.course.name) layout(s.course.short, w, lines, true) else full
            name.paint.color = textColor; name.paint.alpha = alpha
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

    /** A plan: its colour's tint with a dashed edge in the colour, what it is and when. Faded once it's over. */
    private fun drawPlan(canvas: Canvas, plan: Plan, r: RectF) {
        planBlocks += r to plan
        val radius = ui.dp(7f).toFloat()
        val alpha = if (plan.endsAt <= System.currentTimeMillis()) 115 else 255
        canvas.save()
        if (plan.id == landId) canvas.scale(landScale, landScale, r.centerX(), r.centerY())
        fill.color = Palette.mix(plan.color, p.surface, 0.24f); fill.alpha = alpha
        canvas.drawRoundRect(r, radius, radius, fill)
        fill.alpha = 255
        planEdge.color = plan.color; planEdge.alpha = alpha
        canvas.drawRoundRect(RectF(r.left + 0.75f, r.top + 0.75f, r.right - 0.75f, r.bottom - 0.75f), radius, radius, planEdge)
        val pad = ui.dp(4f).toFloat()
        val w = (r.width() - pad * 2).coerceAtLeast(1f)
        canvas.clipRect(r)
        codePaint.color = p.courseText(plan.color); codePaint.alpha = alpha
        canvas.drawText(clockText(plan.start), r.left + pad, r.top + pad + codePaint.textSize, codePaint)
        // A weekly plan: the repeat arrows in its top corner, where the time leaves room.
        val mark = codePaint.textSize.toInt()
        if (plan.weekly && r.width() >= pad * 2 + codePaint.measureText(clockText(plan.start)) + ui.dp(3f) + mark) {
            repeatIcon.setTint(codePaint.color)
            repeatIcon.alpha = alpha
            val top = (r.top + pad + codePaint.textSize * 0.12f).toInt()
            repeatIcon.setBounds((r.right - pad).toInt() - mark, top, (r.right - pad).toInt(), top + mark)
            repeatIcon.draw(canvas)
        }
        val nameTop = r.top + pad + codePaint.textSize + ui.dp(3f)
        val lines = ((r.bottom - pad - nameTop) / namePaint.fontSpacing).toInt()
        if (lines >= 1) {
            val layout = layout(plan.title, w.toInt(), lines, false)
            layout.paint.color = p.text; layout.paint.alpha = alpha
            canvas.translate(r.left + pad, nameTop)
            layout.draw(canvas)
        }
        canvas.restore()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (landId == null || !Motion.enabled) return
        landScale = 0.55f
        android.animation.ValueAnimator.ofFloat(0.55f, 1f).apply {
            interpolator = Motion.BOUNCY
            duration = Motion.BOUNCY.duration
            addUpdateListener { landScale = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    // --- touch: a tap opens a class or a plan; a long press brings up a selection box to place one ----

    private var downX = 0f
    private var downY = 0f
    private var pressedLong = false
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val longPress = Runnable { pressedLong = true; pickUp(downX, downY) }
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    /** The plan being placed with the selection box: a new one, or a saved one being moved. */
    private var picking: Plan? = null
    /** The box follows the finger; or, after "change the time" on the card, it waits to be dragged ([waiting]). */
    private var dragging = false
    private var waiting = false
    /** Where the box is: a day (1–5) and a start; whether that's free, and where the plan would end there. */
    private var pickDay = 0
    private var pickStart = -1
    private var pickEnd = 0
    private var pickFree = false
    /**
     * How far below the box's start the finger took hold, in minutes (whole half hours): dragged, the box
     * keeps that much above the finger, so a plan held by its bottom doesn't jump down to put its top
     * under the finger. 0 for a new plan, whose start is under the finger.
     */
    private var grab = 0
    private var lastFree: Pair<Int, Int>? = null
    private val box = RectF()
    private val boxFrom = RectF()
    private val boxTo = RectF()
    private var boxMove: android.animation.ValueAnimator? = null
    private var boxScale = 1f
    private val boxEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ui.dp(2.5f).toFloat() }
    private val boxDash = android.graphics.DashPathEffect(floatArrayOf(ui.dp(5f).toFloat(), ui.dp(4f).toFloat()), 0f)
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ui.f.bodyBold; textSize = sp(10.5f); color = p.accentInk; textAlign = Paint.Align.CENTER }

    private val colW get() = (width - axis - gapX * 5) / 5
    private fun colLeft(d: Int) = axis + gapX + (d - 1) * (colW + gapX)
    private val gridTop get() = head + padY
    private fun yOf(min: Int) = gridTop + (min - startMin) / 60f * hour
    private fun cell(d: Int, start: Int, end: Int) = RectF(colLeft(d) + ui.dp(1f), yOf(start) + 1, colLeft(d) + colW - ui.dp(1f), yOf(end) - 1)

    /** The day column (1–5) under [x], or null between columns or on the hour axis. */
    private fun columnAt(x: Float): Int? {
        val k = ((x - axis - gapX) / (colW + gapX)).toInt()
        return (k + 1).takeIf { k in 0..4 && x >= colLeft(k + 1) && x <= colLeft(k + 1) + colW }
    }

    /** The half hour under ([x], [y]), kept inside the grid: the nearest day, 08:00 to the last half hour before the end of the day. */
    private fun halfHourAt(x: Float, y: Float): Pair<Int, Int> {
        val d = (((x - axis - gapX) / (colW + gapX)).toInt() + 1).coerceIn(1, 5)
        val start = (startMin + ((y - gridTop) / hour * 2).toInt().coerceIn(0, hours * 2 - 1) * 30)
        return d to start
    }

    /** What's taken on day [d]: its classes and plans (but [except], the plan being moved). */
    private fun busy(d: Int, except: String?) = tt.day(d).map { it.start to it.end } +
        plans.filter { it.date == dates[d - 1] && it.id != except }.map { it.start to it.end }

    /** Starting at [start] on day [d]: whether it's free, how far it can run (to the end of the day at most), and where a plan of [length] would end. */
    private fun room(d: Int, start: Int, length: Int, except: String?): Triple<Boolean, Int, Int> {
        val taken = busy(d, except)
        val free = taken.none { (a, b) -> start in a until b }
        val maxEnd = minOf(endMin, taken.map { it.first }.filter { it > start }.minOrNull() ?: Int.MAX_VALUE)
        return Triple(free, maxEnd, minOf(start + length, maxEnd))
    }

    /** The box to the half hour [grab] above the one under ([x], [y]): it moves with the finger from where it was taken. */
    private fun dragTo(x: Float, y: Float) {
        val (d, at) = halfHourAt(x, y)
        moveBox(d, draggedStart(at, grab, endMin - 30))
    }

    /**
     * A long press: the box comes up for the plan pressed, where it is, or for a new one under the finger,
     * and follows the finger, held where it was pressed.
     */
    private fun pickUp(x: Float, y: Float) {
        val plan = planBlocks.firstOrNull { it.first.contains(x, y) }?.second ?: run {
            val palette = Plans.palette(Scheme.load(context), p.accent)
            Plan.new(dates[0], startMin, startMin + 60, palette[(plans.size + halfHourAt(x, y).first) % palette.size])
        }
        startPicking(plan, wait = false)
        val saved = plans.any { it.id == plan.id }
        val (d, start) = if (saved) plan.date.dayOfWeek.value to plan.start else halfHourAt(x, y)
        grab = if (saved) heldAt(halfHourAt(x, y).second, plan.start, plan.end) else 0
        moveBox(d, start, feel = false)
        Haptics.threshold(this, true)
    }

    /** For a still drawing: the box at day [d], [start] for [plan], as it waits after "change the time". */
    internal fun previewBox(plan: Plan, d: Int, start: Int) {
        picking = plan
        waiting = true
        boxScale = 1f
        pickStart = -1
        val enabled = Motion.enabled
        moveBox(d, start, feel = false)
        if (enabled) { boxMove?.cancel(); box.set(boxTo) }
        holding = false
    }

    /** After "change the time" on [plan]'s card: the box waits where the plan is, to be dragged to another time. */
    fun startRepick(plan: Plan) {
        startPicking(plan, wait = true)
        val d = plan.date.dayOfWeek.value.takeIf { plan.date in dates } ?: 1
        moveBox(d, if (plan.date in dates) plan.start else startMin, feel = false)
        Haptics.tick(this)
    }

    private fun startPicking(plan: Plan, wait: Boolean) {
        picking = plan
        waiting = wait
        dragging = !wait
        holding = true
        pickStart = -1
        lastFree = null
        if (!wait) parent?.requestDisallowInterceptTouchEvent(true)
        // It pops up, a little too big, then settles.
        boxScale = 0.6f
        if (!Motion.enabled) { boxScale = 1f; return }
        android.animation.ValueAnimator.ofFloat(0.6f, 1f).apply {
            interpolator = Motion.BOUNCY
            duration = Motion.BOUNCY.duration
            addUpdateListener { boxScale = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** The box to half hour [start] of day [d], gliding there, with a tick (a softer one where it's taken). */
    private fun moveBox(d: Int, start: Int, feel: Boolean = true) {
        val plan = picking ?: return
        if (d == pickDay && start == pickStart) return
        val (free, _, end) = room(d, start, plan.end - plan.start, plan.id)
        pickDay = d
        pickStart = start
        pickFree = free
        pickEnd = if (free) end else minOf(start + (plan.end - plan.start), endMin)
        if (free) lastFree = d to start
        if (feel) { if (free) Haptics.tick(this) else Haptics.threshold(this, false) }
        boxFrom.set(box)
        boxTo.set(cell(d, start, pickEnd))
        boxMove?.cancel()
        if (box.isEmpty || !Motion.enabled) { box.set(boxTo); invalidate(); return }
        boxMove = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            interpolator = Motion.FOLD
            duration = Motion.FOLD.duration
            addUpdateListener {
                val t = it.animatedValue as Float
                box.set(boxFrom.left + (boxTo.left - boxFrom.left) * t, boxFrom.top + (boxTo.top - boxFrom.top) * t,
                    boxFrom.right + (boxTo.right - boxFrom.right) * t, boxFrom.bottom + (boxTo.bottom - boxFrom.bottom) * t)
                invalidate()
            }
            start()
        }
    }

    /** Let go: on a free time the card opens out of the box; elsewhere the waiting box springs back, a held one drops. */
    private fun drop() {
        val plan = picking ?: return
        if (pickFree) {
            val (_, maxEnd, _) = room(pickDay, pickStart, plan.end - plan.start, plan.id)
            val placed = plan.copy(date = dates[pickDay - 1], start = pickStart, end = pickEnd)
            val from = cell(pickDay, pickStart, pickEnd)
            endPicking()
            Haptics.threshold(this, true)
            open(from, placed, plans.none { it.id == plan.id }, maxEnd)
            return
        }
        val back = lastFree
        if (waiting && back != null) return moveBox(back.first, back.second)
        endPicking()
    }

    private fun endPicking() {
        picking = null
        dragging = false
        waiting = false
        holding = false
        boxMove?.cancel()
        box.setEmpty()
        invalidate()
    }

    /** A double tap on a free time: its card at once (an hour, or what's free). */
    private fun openNewAt(x: Float, y: Float) {
        val (d, start) = halfHourAt(x, y)
        val (free, maxEnd, end) = room(d, start, 60, null)
        if (!free) return
        val palette = Plans.palette(Scheme.load(context), p.accent)
        Haptics.threshold(this, true)
        open(cell(d, start, end), Plan.new(dates[d - 1], start, end, palette[(plans.size + d) % palette.size]), true, maxEnd)
    }

    /** Opens [plan] out of its cell [r] (in this view), which becomes screen coordinates for the flight. */
    private fun open(r: RectF, plan: Plan, isNew: Boolean, maxEnd: Int? = null) {
        val at = IntArray(2).also(::getLocationOnScreen)
        val free = maxEnd ?: room(plan.date.dayOfWeek.value, plan.start, plan.end - plan.start, plan.id).second
        onPlan(RectF(r).apply { offset(at[0].toFloat(), at[1].toFloat()) }, plan, isNew, free, hour)
    }

    /** The selection box: lifted on a shadow, in the plan's colour where it's free, grey and dashed where it's taken. */
    private fun drawBox(canvas: Canvas) {
        val plan = picking ?: return
        if (box.isEmpty) return
        val radius = ui.dp(8f).toFloat()
        canvas.save()
        canvas.scale(boxScale, boxScale, box.centerX(), box.centerY())
        fill.color = Color.BLACK; fill.alpha = 40
        canvas.drawRoundRect(RectF(box).apply { offset(0f, ui.dp(3f).toFloat()) }, radius, radius, fill)
        fill.alpha = 255
        fill.color = if (pickFree) Palette.mix(plan.color, p.surface, 0.55f) else p.surface2
        canvas.drawRoundRect(box, radius, radius, fill)
        boxEdge.color = if (pickFree) plan.color else p.muted
        boxEdge.pathEffect = if (pickFree) null else boxDash
        canvas.drawRoundRect(RectF(box).apply { inset(ui.dp(1.25f).toFloat(), ui.dp(1.25f).toFloat()) }, radius, radius, boxEdge)
        codePaint.color = p.text; codePaint.alpha = 255
        // The start, and under it the end (a narrow column cut "15:00–17:00" short): or "Taken".
        val x = box.left + ui.dp(4f)
        val y = box.top + ui.dp(4f) + codePaint.textSize
        canvas.drawText(if (pickFree) clockText(pickStart) else "Taken", x, y, codePaint)
        if (pickFree && box.height() > codePaint.fontSpacing * 2 + ui.dp(8f)) canvas.drawText("–${clockText(pickEnd)}", x, y + codePaint.fontSpacing, codePaint)
        canvas.restore()
        if (!waiting || dragging) return
        // Waiting to be dragged: a pill above it says so.
        val text = "Drag to a free time"
        val w = hintPaint.measureText(text) + ui.dp(16f)
        val h = hintPaint.textSize + ui.dp(10f)
        val cx = box.centerX().coerceIn(w / 2, width - w / 2)
        val bottom = (box.top - ui.dp(6f)).coerceAtLeast(h)
        fill.color = p.accent
        canvas.drawRoundRect(RectF(cx - w / 2, bottom - h, cx + w / 2, bottom), h / 2, h / 2, fill)
        canvas.drawText(text, cx, bottom - h / 2 + hintPaint.textSize * 0.36f, hintPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                pressedLong = false
                // A box waiting after "change the time": the next touch takes it straight away.
                if (waiting && picking != null) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    // Taken by the box itself, it moves from where it's held; touched elsewhere, its start comes under the finger.
                    grab = if (box.contains(e.x, e.y)) heldAt(halfHourAt(e.x, e.y).second, pickStart, pickEnd) else 0
                    dragTo(e.x, e.y)
                    return true
                }
                if (columnAt(e.x) == null) return false
                postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) dragTo(e.x, e.y)
                else if (kotlin.math.hypot(e.x - downX, e.y - downY) > slop) removeCallbacks(longPress)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (dragging) { dragging = false; drop(); return true }
                if (pressedLong) return true
                planBlocks.firstOrNull { it.first.contains(e.x, e.y) }?.let { (r, plan) -> open(r, plan, false); performClick(); return true }
                blocks.firstOrNull { it.first.contains(e.x, e.y) }?.let { onTap(it.second); performClick(); return true }
                // A free time: a second tap soon after, close by, opens a plan there.
                val t = e.eventTime
                if (t - lastTapAt < android.view.ViewConfiguration.getDoubleTapTimeout() && kotlin.math.hypot(e.x - lastTapX, e.y - lastTapY) < slop * 4) {
                    lastTapAt = 0L
                    openNewAt(e.x, e.y)
                } else {
                    lastTapAt = t
                    lastTapX = e.x
                    lastTapY = e.y
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragging && !waiting) endPicking() else dragging = false
            }
        }
        return true
    }

    override fun performClick() = super.performClick()

    override fun onDetachedFromWindow() {
        removeCallbacks(longPress)
        if (picking != null) holding = false
        super.onDetachedFromWindow()
    }

    companion object {
        /** The id of a plan just saved, for the next grid drawn to pop it into place. */
        var landing: String? = null
        /** A selection box is up: the page shouldn't be redrawn under it (the minute's redraw waits). */
        var holding = false
        private val LAYOUTS = android.util.LruCache<String, StaticLayout>(96)
    }
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
