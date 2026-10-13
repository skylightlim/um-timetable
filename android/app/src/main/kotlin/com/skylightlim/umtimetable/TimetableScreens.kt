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
}

/**
 * The day Day opens on: today while it still has a class to come, otherwise the next weekday with
 * classes (at 23:50 on Monday that's Tuesday, on Saturday it's Monday).
 */
fun homeDay(tt: Timetable, now: Now): Int {
    if (now.dow in 1..5 && tt.day(now.dow).any { it.end > now.min }) return now.dow
    return (1..7).map { (now.dow + it) % 7 }.firstOrNull { it in 1..5 && tt.day(it).isNotEmpty() } ?: if (now.dow in 1..5) now.dow else 1
}

/** Big day-name heading with its one-line summary. */
fun pageHeader(ui: Ui, title: String, meta: String, chip: View? = null, action: View? = null): View {
    val top = ui.row(gap = 10f)
    top.addView(ui.text(title.uppercase(), 46f, ui.p.text, ui.f.sign).apply { letterSpacing = 0.01f })
    chip?.let { top.addView(it) }
    if (action != null) {
        top.addView(ui.space(), ui.spacer())
        top.addView(action)
    }
    return ui.column().apply {
        setPadding(0, ui.dp(18), 0, ui.dp(14))
        addView(top)
        addView(ui.text(meta, 13.5f, ui.p.muted), ui.wide(top = 8f))
    }
}

object DayScreen {
    fun build(h: Host, day: Int): View {
        val ui = h.ui
        val p = ui.p
        val now = h.now()
        val list = h.timetable.day(day)
        val isToday = day == now.dow
        val meta = if (list.isEmpty()) "No classes" else
            "${list.size} ${if (list.size == 1) "class" else "classes"} · ${hoursText(list.sumOf { it.end - it.start })} · ${clockText(list.first().start)}–${clockText(list.last().end)}"
        val page = ui.column()
        page.addView(pageHeader(ui, DAY_NAMES[day], meta, chip = if (isToday) ui.chip("Today", p.accent, p.accentInk, 11f).apply { setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3)) } else null))
        if (day == homeDay(h.timetable, now)) page.addView(status(h, now), ui.wide(bottom = 18f))

        val agenda = ui.column(gap = 10f)
        list.forEachIndexed { i, s ->
            val prev = list.getOrNull(i - 1)
            if (prev != null && s.start > prev.end) agenda.addView(gap(ui, prev, s, isToday, now))
            val state = when {
                !isToday -> ""
                now.min >= s.end -> "past"
                now.min >= s.start -> "live"
                else -> ""
            }
            agenda.addView(slot(h, s, state, now))
        }
        page.addView(agenda)
        return page
    }

    private fun status(h: Host, now: Now): View {
        val ui = h.ui
        val p = ui.p
        val card = ui.column().apply {
            background = ui.pressable(ui.shape(p.surface, 18f, p.line), 18f)
            setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(16))
        }
        val st = h.timetable.status(now)
        if (st == null) {
            card.addView(ui.label("No timetable yet", p.accentText))
            card.addView(ui.text("Open the Courses tab and sign in to Spectrum with your SiswaMail: the app then gets your timetable from TimeEdit.", 17f, p.text, ui.f.bodySemi), ui.wide(top = 8f))
            return card
        }
        val s = st.session
        val label = when (st.phase) {
            Phase.IN_CLASS -> "Now · ends in ${durationText(st.minutesAway)}"
            Phase.NEXT_TODAY -> "Next · in ${durationText(st.minutesAway)}"
            Phase.NEXT_LATER -> "Next class · ${if (st.daysAhead == 1) "tomorrow" else DAY_NAMES[s.day]} at ${clockText(s.start)}"
        }
        val time = if (st.phase == Phase.NEXT_LATER) "${DAY_SHORT[s.day]} ${clockText(s.start)}–${clockText(s.end)}" else "${clockText(s.start)}–${clockText(s.end)}"
        card.addView(ui.label(label, p.accentText))
        val main = ui.row(gap = 12f).apply { gravity = Gravity.BOTTOM }
        main.addView(ui.column().apply {
            addView(ui.text("${s.course.code} · ${s.type} · $time", 12f, p.muted, ui.f.mono))
            addView(ui.text(s.course.name, 18f, p.text, ui.f.bodySemi), ui.wide(top = 4f))
        }, ui.grow())
        main.addView(ui.column().apply {
            gravity = Gravity.END
            addView(ui.label(if (s.room == "Online") "Where" else "Room", p.muted, 10.5f))
            addView(ui.text(s.room, 42f, p.accentText, ui.f.sign))
        })
        card.addView(main, ui.wide(top = 8f))
        if (st.phase == Phase.IN_CLASS) {
            card.addView(ui.bar(st.progress, p.accent, p.line), ui.wide(top = 12f).apply { height = ui.dp(4) })
            h.timetable.day(now.dow).firstOrNull { it.start > now.min }?.let { next ->
                card.addView(ui.space(h = 1).apply { setBackgroundColor(p.line) }, ui.wide(top = 12f).apply { height = 1 })
                card.addView(ui.text("Then ${next.course.code} ${next.type.lowercase()} at ${clockText(next.start)} · ${next.room}", 13f, p.muted), ui.wide(top = 10f))
            }
        }
        card.setOnClickListener { h.openSheet(s.course, s) }
        return card
    }

    private fun gap(ui: Ui, prev: Session, next: Session, isToday: Boolean, now: Now): View {
        val p = ui.p
        val inGap = isToday && now.min >= prev.end && now.min < next.start
        val box = ui.row().apply {
            background = ui.shape(Color.TRANSPARENT, 12f, if (inGap) p.accent else p.line, 1f, dash = 4f)
            setPadding(ui.dp(14), ui.dp(9), ui.dp(14), ui.dp(9))
        }
        val color = if (inGap) p.accentText else p.muted
        box.addView(ui.text("${if (inGap) "Free now" else "Free"} · ${durationText(next.start - prev.end)}", 13f, color), ui.grow())
        box.addView(ui.text(if (inGap) "next in ${durationText(ceil(next.start - now.min).toInt())}" else "${clockText(prev.end)}–${clockText(next.start)}", 13f, color))
        return ui.row(gap = 10f, center = false).apply {
            addView(ui.space(w = ui.dp(46)))
            addView(box, ui.grow())
        }
    }

    private fun slot(h: Host, s: Session, state: String, now: Now): View {
        val ui = h.ui
        val p = ui.p
        val c = s.course.color
        val tut = s.isTutorial
        val fg = if (tut) p.text else p.ink
        val fgMuted = if (tut) p.muted else p.inkMuted

        val card = ui.column(gap = 5f).apply {
            background = ui.pressable(if (tut) ui.shape(p.courseTint(c), 16f, c, 1.5f) else ui.shape(c, 16f))
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(13))
            // Every card fits its content; the times beside it and the free gaps show how long a class
            // is. (A 46dp-per-hour minimum only ever lengthened 3-hour classes, by empty space.)
            setOnClickListener { h.openSheet(s.course, s) }
        }
        val top = ui.row(gap = 8f)
        top.addView(ui.text(s.course.code, 12f, if (tut) p.courseText(c) else p.ink, ui.f.monoBold))
        top.addView(ui.chip(s.type, if (tut) Palette.mix(c, p.surface, 0.22f) else 0x2410141A, if (tut) p.courseText(c) else p.ink))
        top.addView(ui.text("Occ ${s.course.occ}", 11f, fg, ui.f.mono).apply { alpha = 0.75f })
        top.addView(ui.space(), ui.spacer())
        top.addView(ui.text(s.room, 24f, fg, ui.f.sign))
        card.addView(top)
        card.addView(ui.text(s.course.name, 17f, fg, ui.f.bodySemi))
        card.addView(ui.text(s.lecturer ?: "Lecturer not listed", 13f, fgMuted).apply { if (s.lecturer == null) setTypeface(ui.f.body, Typeface.ITALIC) })
        if (state == "live") {
            card.addView(ui.space(h = ui.dp(2)))
            card.addView(ui.label("Ends in ${durationText(ceil(s.end - now.min).toInt())}", fg))
            card.addView(ui.bar((now.min - s.start) / (s.end - s.start), if (tut) c else p.ink, if (tut) p.line else 0x2E10141A))
        }

        val time = ui.column().apply {
            setPadding(0, ui.dp(12), 0, 0)
            addView(ui.text(clockText(s.start), 12.5f, p.text, ui.f.monoBold))
            addView(ui.text(clockText(s.end), 12.5f, p.faint, ui.f.mono), ui.wide(top = 2f))
        }
        val cardSlot: View = if (state == "live") {
            // The class in progress gets an accent ring a little outside the card.
            FrameLayout(ui.context).apply {
                background = ui.shape(Color.TRANSPARENT, 19f, p.accent, 2f)
                setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
                addView(card)
            }
        } else card
        if (state == "past") {
            card.alpha = 0.42f
            time.alpha = 0.42f
        }
        return ui.row(gap = 10f, center = false).apply {
            addView(time, LinearLayout.LayoutParams(ui.dp(46), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(cardSlot, ui.grow())
        }
    }
}

object WeekScreen {
    fun build(h: Host): View {
        val ui = h.ui
        val p = ui.p
        val tt = h.timetable
        val page = ui.column()
        page.addView(pageHeader(ui, "Week", if (tt.sessions.isEmpty()) "No timetable yet · sign in to Spectrum in Courses" else "${tt.sessions.size} classes · ${hoursText(tt.sessions.sumOf { it.end - it.start })} · Mon–Fri · tap a class for details"))
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

        return page
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
        val nowY = if (now.dow in 1..5 && now.min >= startMin && now.min <= 19 * 60) top + (now.min - startMin) / 60 * hour else null
        val pillH = ui.dp(15f).toFloat()
        for (h in 8..19) {
            val y = top + (h - 8) * hour
            if (nowY != null && kotlin.math.abs(y - nowY) < pillH) continue // the time pill is there
            canvas.drawText("%02d".format(h), axis - ui.dp(3f), y + axisPaint.textSize * 0.35f, axisPaint)
        }
        for (d in 1..5) {
            val left = axis + gapX + (d - 1) * (colW + gapX)
            val isToday = d == now.dow
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
        if (nowY == null) return
        // Today's line goes over the classes (a class in progress is when it matters most), in the app's
        // yellow, on a halo in the card's colour so it reads on any course colour.
        val left = axis + gapX + (now.dow - 1) * (colW + gapX)
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
        if (s.end - s.start >= 120) {
            namePaint.color = textColor; namePaint.alpha = alpha
            val layout = StaticLayout.Builder.obtain(s.course.short, 0, s.course.short.length, namePaint, w)
                .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL).build()
            canvas.save()
            canvas.translate(r.left + pad, r.top + pad + codePaint.textSize + ui.dp(3f))
            layout.draw(canvas)
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

/** The class details sheet that slides up from the bottom. */
object ClassSheet {
    fun show(h: Host, context: Context, course: Course, session: Session?) {
        val ui = h.ui
        val p = ui.p
        lateinit var bottom: BottomSheet
        val sheet = ui.column().apply {
            background = GradientDrawable().apply {
                setColor(p.surface)
                val r = ui.dp(22f).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(28))
        }
        sheet.addView(View(context).apply { background = ui.shape(p.line, 4f) }, LinearLayout.LayoutParams(ui.dp(40), ui.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ui.dp(14) })
        val top = ui.row(gap = 10f)
        top.addView(View(context).apply { background = ui.shape(course.color, 99f); layoutParams = LinearLayout.LayoutParams(ui.dp(10), ui.dp(10)) })
        top.addView(ui.text(course.code, 13f, p.courseText(course.color), ui.f.monoBold))
        top.addView(ui.text("Occurrence ${course.occ}", 12f, p.muted))
        top.addView(ui.space(), ui.spacer())
        top.addView(FrameLayout(context).apply {
            // A 34dp circle inside a 48dp touch area.
            background = android.graphics.drawable.InsetDrawable(ui.pressable(ui.shape(p.surface2, 99f), 99f), ui.dp(7))
            contentDescription = "Close"
            addView(ui.icon(R.drawable.ic_close, p.muted, 16f), FrameLayout.LayoutParams(ui.dp(16), ui.dp(16), Gravity.CENTER))
            setOnClickListener { bottom.close() }
        }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { marginEnd = -ui.dp(7) })
        sheet.addView(top)
        sheet.addView(ui.text(course.name, 24f, p.text, ui.f.bodyBold), ui.wide(top = 10f, bottom = 16f))

        val facts = ui.column(gap = 10f)
        fun fact(label: String, value: View) = facts.addView(ui.row(gap = 16f, center = false).apply {
            addView(ui.label(label, p.muted).apply { setPadding(0, ui.dp(3), 0, 0) }, LinearLayout.LayoutParams(ui.dp(76), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(value, ui.grow())
        })
        val sessions = h.timetable.sessions.filter { it.course == course }.sortedWith(compareBy({ it.day }, { it.start }))
        if (session != null) {
            fact("When", ui.text("${DAY_NAMES[session.day]}, ${clockText(session.start)}–${clockText(session.end)} · ${durationText(session.end - session.start)}", 15f))
            fact("Type", ui.text(session.type, 15f))
            fact(if (session.room == "Online") "Where" else "Room", ui.column().apply {
                addView(ui.text(session.room, 30f, p.accentText, ui.f.sign))
                session.roomName?.takeIf { it != session.room }?.let { addView(ui.text(it, 12.5f, p.muted), ui.wide(top = 3f)) }
            })
            fact("Lecturer", ui.text(session.lecturer ?: "Not listed", 15f, if (session.lecturer == null) p.muted else p.text))
        } else {
            val names = sessions.mapNotNull { it.lecturer }.distinct()
            fact("Lecturer", ui.text(names.ifEmpty { listOf("Not listed") }.joinToString("\n"), 15f, if (names.isEmpty()) p.muted else p.text))
        }
        sheet.addView(facts, ui.wide(bottom = 22f))
        sheet.addView(ui.label("Every week · ${hoursText(sessions.sumOf { it.end - it.start })}", p.muted), ui.wide(bottom = 8f))
        val list = ui.column(gap = 6f)
        for (s in sessions) {
            val row = ui.row(gap = 8f).apply {
                background = ui.pressable(ui.shape(p.surface2, 12f, if (s == session) course.color else 0, 1.5f), 12f)
                setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                setOnClickListener { bottom.close { h.goDay(s.day) } }
            }
            row.addView(ui.text(DAY_SHORT[s.day], 15f, p.text, ui.f.bodyBold), LinearLayout.LayoutParams(ui.dp(36), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(ui.text("${clockText(s.start)}–${clockText(s.end)}", 12.5f, p.text, ui.f.mono), LinearLayout.LayoutParams(ui.dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(ui.text(s.type, 13f, p.muted), ui.grow())
            row.addView(ui.text(s.room, 18f, p.text, ui.f.sign))
            list.addView(row)
        }
        sheet.addView(list)
        sheet.addView(ui.text("Tap a session to open that day.", 12f, p.faint), ui.wide(top = 10f))

        bottom = BottomSheet(context, sheet, p.dark)
        bottom.show()
    }
}
