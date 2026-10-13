package com.skylightlim.umtimetable

import android.content.Context
import android.util.TypedValue.COMPLEX_UNIT_DIP
import android.view.View
import android.widget.RemoteViews

/** Builds the widget's views for one size. Kept apart from the provider so a debug screen can render it too. */
object WidgetRenderer {

    // These must match res/layout/widget.xml.
    private const val PAD = 14f
    private const val STATUS_H = 102f
    private const val GRID_GAP = 10f
    private const val HEAD_H = 16f

    private const val DAY_START = 8 * 60
    private const val DAY_END = 19 * 60
    private const val HOURS = 11

    private val COLS = intArrayOf(R.id.c1, R.id.c2, R.id.c3, R.id.c4, R.id.c5)
    private val HEADS = intArrayOf(R.id.h1, R.id.h2, R.id.h3, R.id.h4, R.id.h5)

    // Theme-dependent colours set at runtime, as (light, dark) pairs.
    private const val ACCENT_TEXT_LIGHT = 0xFF875A00.toInt()
    private const val ACCENT_TEXT_DARK = 0xFFF5C947.toInt()
    private const val PAST_TEXT_LIGHT = 0x66141A22
    private const val PAST_TEXT_DARK = 0x66E8ECF1
    private const val PAST_INK = 0x6610141A

    fun build(context: Context, timetable: Timetable, now: Now, widthDp: Float, heightDp: Float): RemoteViews {
        val pkg = context.packageName
        val rv = RemoteViews(pkg, R.layout.widget)
        val st = timetable.status(now)
        val s = st?.session
        rv.setOnClickPendingIntent(R.id.status, Notify.openApp(context, "day"))

        if (st == null || s == null) {
            rv.setTextViewText(R.id.label, "No timetable yet")
            rv.setViewVisibility(R.id.swatch, View.GONE)
            rv.setTextViewText(R.id.code, "")
            rv.setTextViewText(R.id.name, "Open the app to get it")
            rv.setTextViewText(R.id.room, "")
            rv.setTextViewText(R.id.countdown, "")
            rv.setViewVisibility(R.id.progress, View.GONE)
            rv.setTextViewText(R.id.today, "")
        } else {
            rv.setTextViewText(
                R.id.label,
                when (st.phase) {
                    Phase.IN_CLASS -> "Now · until ${clockText(s.end)}"
                    Phase.NEXT_TODAY -> "Next · today ${clockText(s.start)}"
                    Phase.NEXT_LATER ->
                        "Next · ${if (st.daysAhead == 1) "tomorrow" else DAY_NAMES[s.day]} ${clockText(s.start)}"
                },
            )
            rv.setInt(R.id.swatch, "setColorFilter", s.course.color)
            rv.setTextViewText(R.id.code, "${s.course.code} · ${s.type} · Occ ${s.course.occ}")
            rv.setTextViewText(R.id.name, s.course.name)
            rv.setTextViewText(R.id.room, s.room)
            rv.setTextViewText(
                R.id.countdown,
                if (st.phase == Phase.IN_CLASS) "${durationText(st.minutesAway)} left" else "in ${durationText(st.minutesAway)}",
            )
            rv.setViewVisibility(R.id.progress, if (st.phase == Phase.IN_CLASS) View.VISIBLE else View.GONE)
            rv.setProgressBar(R.id.progress, 1000, (st.progress * 1000).toInt(), false)
            rv.setTextViewText(R.id.today, todayText(st, now))
        }

        val alert = SpectrumStore.widgetAlert(context, System.currentTimeMillis())
        if (alert != null) {
            rv.setViewVisibility(R.id.alert, View.VISIBLE)
            rv.setTextViewText(R.id.alert, alert.text)
            if (alert.live) rv.setColorInt(R.id.alert, "setTextColor", ACCENT_TEXT_LIGHT, ACCENT_TEXT_DARK)
            rv.setOnClickPendingIntent(
                R.id.alert,
                if (alert.live && alert.url != null) Notify.openSpectrum(context, alert.url) else Notify.openApp(context, "due"),
            )
        }
        val statusH = STATUS_H + if (alert != null) 20f else 0f

        // The grid gets whatever height is left; below ~9dp an hour it's too cramped to read.
        val hour = minOf((heightDp - 2 * PAD - statusH - GRID_GAP - HEAD_H) / HOURS, 40f)
        if (hour < 9f) {
            rv.setViewVisibility(R.id.grid, View.GONE)
            return rv
        }
        rv.setViewVisibility(R.id.grid, View.VISIBLE)
        rv.setOnClickPendingIntent(R.id.grid, Notify.openApp(context, "week"))
        rv.setViewLayoutHeight(R.id.body, hour * HOURS, COMPLEX_UNIT_DIP)

        rv.removeAllViews(R.id.lines)
        for (h in 8..19) {
            val y = (h - 8) * hour
            rv.addView(R.id.lines, RemoteViews(pkg, R.layout.grid_line).apply {
                setViewLayoutMargin(R.id.line, RemoteViews.MARGIN_TOP, minOf(y, hour * HOURS - 1), COMPLEX_UNIT_DIP)
            })
            if (h % 2 == 0) rv.addView(R.id.lines, RemoteViews(pkg, R.layout.grid_label).apply {
                setTextViewText(R.id.lbl, "%02d".format(h))
                setViewLayoutMargin(R.id.lbl, RemoteViews.MARGIN_TOP, maxOf(0f, y - 5f), COMPLEX_UNIT_DIP)
            })
        }

        for (d in 1..5) {
            val col = COLS[d - 1]
            val isToday = d == now.dow
            rv.removeAllViews(col)
            rv.setInt(col, "setBackgroundResource", if (isToday) R.drawable.col_today else R.drawable.col)
            if (isToday) rv.setColorInt(HEADS[d - 1], "setTextColor", ACCENT_TEXT_LIGHT, ACCENT_TEXT_DARK)
            // In class, the outlined block already marks "now"; the line is for the gaps between classes.
            if (isToday && st?.phase != Phase.IN_CLASS && now.min >= DAY_START && now.min <= DAY_END) {
                rv.addView(col, RemoteViews(pkg, R.layout.grid_now).apply {
                    setViewLayoutMargin(R.id.now, RemoteViews.MARGIN_TOP, (now.min - DAY_START) / 60 * hour - 1, COMPLEX_UNIT_DIP)
                })
            }
            for (session in timetable.day(d)) {
                rv.addView(col, block(pkg, session, hour, past = isToday && now.min >= session.end, highlight = session == s))
            }
        }
        return rv
    }

    private fun block(pkg: String, s: Session, hour: Float, past: Boolean, highlight: Boolean): RemoteViews {
        val height = (s.end - s.start) / 60f * hour - 1.5f
        return RemoteViews(pkg, if (s.isTutorial) R.layout.grid_block_tut else R.layout.grid_block_lec).apply {
            setViewLayoutMargin(R.id.blk, RemoteViews.MARGIN_TOP, (s.start - DAY_START) / 60f * hour + 0.75f, COMPLEX_UNIT_DIP)
            setViewLayoutHeight(R.id.blk, height, COMPLEX_UNIT_DIP)
            setInt(R.id.b_bg, "setColorFilter", s.course.color)
            setTextViewText(R.id.b_code, s.course.code)
            setTextViewText(R.id.b_room, s.room)
            // Short blocks show only the room, which is what you need when walking to class.
            setViewVisibility(R.id.b_code, if (height >= 28f) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.b_room, if (height >= 13f) View.VISIBLE else View.GONE)
            if (highlight) setViewVisibility(R.id.b_ring, View.VISIBLE)
            if (past) {
                setInt(R.id.b_bg, "setImageAlpha", 100)
                for (id in intArrayOf(R.id.b_code, R.id.b_room)) {
                    if (s.isTutorial) setColorInt(id, "setTextColor", PAST_TEXT_LIGHT, PAST_TEXT_DARK)
                    else setTextColor(id, PAST_INK)
                }
            }
        }
    }

    private fun todayText(st: Status, now: Now): String = when {
        !st.isWeekday -> "No classes today"
        st.classesLeftToday == 0 -> if (now.dow == 5) "Done for the week" else "Done for today"
        else -> {
            val n = st.classesLeftToday
            "Today · $n ${if (n == 1) "class" else "classes"} left · ${durationText(st.minutesLeftToday)} of class"
        }
    }
}
