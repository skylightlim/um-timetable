package com.skylightlim.umtimetable

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue.COMPLEX_UNIT_DIP
import android.view.View
import android.widget.RemoteViews
import kotlin.math.ceil

/**
 * Builds the widget's views for one size: the class in progress or the next one on a card in its course's
 * colour, as Day's own card for it (when and how long; the name and the room, large; the code, type and
 * occurrence; how far through), then the week's grid, and one line at the foot with the rest of today and
 * Spectrum's (open attendance, or the next deadline). Kept apart from the provider so a debug screen and
 * Settings' Widget can show it too.
 *
 * In the app's typefaces and the theme's colours. A widget can't load an app's fonts (the home screen
 * draws it with a restricted context, which skips font resources), so each text is a picture ([picture]):
 * white on clear, tinted where it's shown. Colours come in pairs, the theme's light and dark ones, and the
 * home screen picks by the phone's dark mode; Light or Dark chosen in Settings holds it to one.
 */
object WidgetRenderer {

    // The layout's own sizes (res/layout/widget.xml), in dp.
    private const val PAD = 12f
    private const val CARD_PAD_H = 14f
    private const val CARD_PAD_V = 12f
    private const val GRID_GAP = 10f
    private const val HEAD_H = 16f
    private const val FOOT_GAP = 10f
    private const val LABELS = 18f
    private const val COL_GAP = 2.5f

    private const val DAY_START = 8 * 60
    private const val DAY_END = 19 * 60
    private const val HOURS = 11

    private val COLS = intArrayOf(R.id.c1, R.id.c2, R.id.c3, R.id.c4, R.id.c5)
    private val HEADS = intArrayOf(R.id.h1, R.id.h2, R.id.h3, R.id.h4, R.id.h5)

    /** The theme's light and dark palettes; the same one twice when Settings holds the look to Light or Dark. */
    private class Colors(val light: Palette, val dark: Palette)

    private fun colors(c: Context, look: Look): Colors {
        val scheme = Scheme.load(c)
        val light = scheme.applyTo(Palette.LIGHT)
        val dark = scheme.applyTo(Palette.DARK)
        return when (look) {
            Look.LIGHT -> Colors(light, light)
            Look.DARK -> Colors(dark, dark)
            Look.AUTO -> Colors(light, dark)
        }
    }

    /** [id]'s picture (or shape) in the theme's colour [of]. */
    private fun RemoteViews.tint(id: Int, k: Colors, of: (Palette) -> Int) = setColorInt(id, "setColorFilter", of(k.light), of(k.dark))

    private fun RemoteViews.backTint(id: Int, k: Colors, of: (Palette) -> Int) =
        setColorStateList(id, "setBackgroundTintList", ColorStateList.valueOf(of(k.light)), ColorStateList.valueOf(of(k.dark)))

    private class Faces(c: Context) {
        val r = c.resources
        val sign: Typeface = r.getFont(R.font.bigshoulders_800)
        val body: Typeface = r.getFont(R.font.instrumentsans_400)
        val semi: Typeface = r.getFont(R.font.instrumentsans_600)
        val bold: Typeface = r.getFont(R.font.instrumentsans_700)
        val mono: Typeface = r.getFont(R.font.jetbrainsmono_500)
        val monoBold: Typeface = r.getFont(R.font.jetbrainsmono_700)
    }

    @Volatile private var faces: Faces? = null
    private fun faces(c: Context) = faces ?: Faces(c.applicationContext).also { faces = it }

    /**
     * [text] as a picture: white on clear in [face], [size] dp, no wider than [max] dp (ending in "…" past it,
     * after up to [lines] lines). Its top is the capitals' top, so pictures side by side line up by their
     * capitals, as the app's cards do. An alpha-only bitmap: a quarter of the memory, and tinted all the same.
     */
    private fun draw(c: Context, text: String, face: Typeface, size: Float, max: Float, lines: Int = 1, spacing: Float = 0f): Bitmap {
        val m = c.resources.displayMetrics
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            textSize = size * m.density
            letterSpacing = spacing
            color = Color.WHITE
        }
        val room = (max * m.density).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, room)
            .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build()
        val width = ceil((0 until layout.lineCount).maxOf { layout.getLineWidth(it) }).toInt().coerceIn(1, room)
        val caps = Rect().also { paint.getTextBounds("H", 0, 1, it) }
        val top = (layout.getLineBaseline(0) + caps.top).coerceAtLeast(0)
        return Bitmap.createBitmap(width, (layout.height - top).coerceAtLeast(1), Bitmap.Config.ALPHA_8).apply {
            density = m.densityDpi
            Canvas(this).apply {
                translate(0f, -top.toFloat())
                layout.draw(this)
            }
        }
    }

    /** How wide [text] is on one line, in dp. */
    private fun measure(c: Context, text: String, face: Typeface, size: Float): Float {
        val d = c.resources.displayMetrics.density
        return TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = size * d }.measureText(text) / d
    }

    private fun Bitmap.dp(c: Context) = height / c.resources.displayMetrics.density
    private fun Bitmap.wide(c: Context) = width / c.resources.displayMetrics.density

    /** [id] showing [text] as a picture ([draw]) in the theme's colour [of]. */
    private fun RemoteViews.picture(
        c: Context, id: Int, k: Colors, text: String, face: Typeface, size: Float, max: Float,
        lines: Int = 1, spacing: Float = 0f, of: (Palette) -> Int,
    ): Bitmap = draw(c, text, face, size, max, lines, spacing).also {
        setImageViewBitmap(id, it)
        tint(id, k, of)
        setViewVisibility(id, View.VISIBLE)
    }

    /** The widget at [widthDp] × [heightDp]; [look] instead of the one chosen in Settings (a debug drawing's). */
    fun build(context: Context, timetable: Timetable, now: Now, widthDp: Float, heightDp: Float, look: Look = Look.load(context)): RemoteViews {
        val pkg = context.packageName
        val rv = RemoteViews(pkg, R.layout.widget)
        val k = colors(context, look)
        val f = faces(context)
        val st = timetable.status(now)
        val s = st?.session
        val inner = widthDp - 2 * PAD
        val cardInner = inner - 2 * CARD_PAD_H
        rv.backTint(android.R.id.background, k) { it.surface }

        // The card: a lecture's filled with its colour, a tutorial's tinted with its colour round it (as on Day);
        // before there's a timetable, the accent.
        val tutorial = s?.isTutorial == true
        val ink: (Palette) -> Int = when {
            s == null -> { p -> p.accentInk }
            tutorial -> { p -> p.text }
            else -> { p -> p.ink }
        }
        when {
            s == null -> rv.tint(R.id.card_bg, k) { it.accent }
            tutorial -> {
                rv.tint(R.id.card_bg, k) { it.courseTint(s.course.color) }
                rv.setViewVisibility(R.id.card_ring, View.VISIBLE)
                rv.setInt(R.id.card_ring, "setColorFilter", s.course.color)
            }
            else -> rv.setInt(R.id.card_bg, "setColorFilter", s.course.color)
        }
        rv.setOnClickPendingIntent(R.id.card, Notify.openApp(context, "day"))
        // Short widgets keep the name to one line.
        val nameLines = if (heightDp >= 230f) 2 else 1
        var cardH = 2 * CARD_PAD_V
        if (st == null || s == null) {
            cardH += rv.picture(context, R.id.label, k, "NO TIMETABLE YET", f.bold, 11f, cardInner, spacing = 0.12f, of = ink).dp(context)
            cardH += 10f + rv.picture(context, R.id.name, k, "Open the app to get it", f.semi, 17f, cardInner, of = ink).dp(context)
            rv.setContentDescription(R.id.card, "No timetable yet. Open the app to get it.")
        } else {
            val label = when (st.phase) {
                Phase.IN_CLASS -> "Now · until ${clockText(s.end)}"
                Phase.NEXT_TODAY -> "Next · today ${clockText(s.start)}"
                Phase.NEXT_LATER -> "Next · ${if (st.daysAhead == 1) "tomorrow" else DAY_NAMES[s.day]} ${clockText(s.start)}"
            }
            val left = if (st.phase == Phase.IN_CLASS) "${durationText(st.minutesAway)} left" else "in ${durationText(st.minutesAway)}"
            val leftPic = rv.picture(context, R.id.countdown, k, left.uppercase(), f.bold, 11f, cardInner * 0.5f, spacing = 0.12f, of = ink)
            cardH += rv.picture(context, R.id.label, k, label.uppercase(), f.bold, 11f, cardInner - leftPic.wide(context) - 10f, spacing = 0.12f, of = ink).dp(context)
            val room = rv.picture(context, R.id.room, k, s.room, f.sign, 34f, cardInner * 0.45f, of = ink)
            val name = rv.picture(context, R.id.name, k, s.course.name, f.semi, 17f, cardInner - room.wide(context) - 10f, lines = nameLines, of = ink)
            cardH += 10f + maxOf(name.dp(context), room.dp(context))
            val code = "${s.course.code} · ${s.type} · Occ ${s.course.occ}"
            cardH += 8f + rv.picture(context, R.id.code, k, code, f.mono, 11f, cardInner, of = if (tutorial) { p -> p.muted } else ink).dp(context)
            if (st.phase == Phase.IN_CLASS) {
                rv.setViewVisibility(R.id.progress, View.VISIBLE)
                rv.setProgressBar(R.id.progress, 1000, (st.progress * 1000).toInt(), false)
                rv.setColorStateList(R.id.progress, "setProgressTintList", ColorStateList.valueOf(ink(k.light)), ColorStateList.valueOf(ink(k.dark)))
                rv.setColorStateList(R.id.progress, "setProgressBackgroundTintList",
                    ColorStateList.valueOf((ink(k.light) and 0xFFFFFF) or 0x2E000000), ColorStateList.valueOf((ink(k.dark) and 0xFFFFFF) or 0x2E000000))
                cardH += 14f
            }
            rv.setContentDescription(R.id.card, "$label, $left. ${s.course.name}, ${s.type}, ${if (s.room == "Online") "online" else "room ${s.room}"}. ${s.course.code} occurrence ${s.course.occ}.")
        }

        // The foot: the rest of today on the left, Spectrum's line on the right (in the accent while attendance is
        // open). Each as wide as it needs; when both don't fit, the longer gives way, Spectrum's keeping at least half.
        val alert = SpectrumStore.widgetAlert(context, System.currentTimeMillis())
        var footH = 0f
        val today = st?.let { todayText(it, now) }
        val gap = 12f
        val alertNeeds = alert?.let { measure(context, it.text, f.semi, 12f) } ?: 0f
        val todayNeeds = today?.let { measure(context, it, f.body, 12f) } ?: 0f
        val alertRoom = if (alert == null || today == null || alertNeeds + gap + todayNeeds <= inner) alertNeeds.coerceAtMost(inner)
            else maxOf(inner / 2, inner - gap - todayNeeds).coerceAtMost(alertNeeds)
        if (alert != null) {
            footH = rv.picture(context, R.id.alert, k, alert.text, f.semi, 12f, alertRoom + 1f, of = if (alert.live) { p -> p.accentText } else { p -> p.text }).dp(context)
            rv.setContentDescription(R.id.alert, alert.text)
            rv.setOnClickPendingIntent(R.id.alert, if (alert.live && alert.url != null) Notify.openSpectrum(context, alert.url) else Notify.openApp(context, "due"))
        }
        if (today != null) {
            val room = inner - (if (alert != null) alertRoom + gap else 0f)
            footH = maxOf(footH, rv.picture(context, R.id.today, k, today, f.body, 12f, room, of = { p -> p.muted }).dp(context))
            rv.setContentDescription(R.id.today, today)
            rv.setOnClickPendingIntent(R.id.today, Notify.openApp(context, "day"))
        }
        val left = heightDp - 2 * PAD - cardH
        // Too short for the foot as well as the card: the card alone.
        if (footH == 0f || left < FOOT_GAP + footH) rv.setViewVisibility(R.id.footer, View.GONE)
        else footH += FOOT_GAP

        // The grid gets whatever height is left; below ~9dp an hour it's too cramped to read, past 48dp too sparse.
        val hour = minOf((left - footH - GRID_GAP - HEAD_H - 2f) / HOURS, 48f)
        if (hour < 9f) return rv
        rv.setViewVisibility(R.id.grid, View.VISIBLE)
        rv.setOnClickPendingIntent(R.id.grid, Notify.openApp(context, "week"))
        rv.setViewLayoutHeight(R.id.body, hour * HOURS, COMPLEX_UNIT_DIP)
        val colW = (inner - LABELS - 5 * COL_GAP) / 5

        rv.removeAllViews(R.id.lines)
        for (h in 8..19) {
            val y = (h - 8) * hour
            rv.addView(R.id.lines, RemoteViews(pkg, R.layout.grid_line).apply {
                setViewLayoutMargin(R.id.line, RemoteViews.MARGIN_TOP, minOf(y, hour * HOURS - 1), COMPLEX_UNIT_DIP)
                setColorInt(R.id.line, "setBackgroundColor", k.light.line, k.dark.line)
            })
            if (h % 2 == 0) rv.addView(R.id.lines, RemoteViews(pkg, R.layout.grid_label).apply {
                val pic = picture(context, R.id.lbl, k, "%02d".format(h), f.mono, 8f, LABELS) { it.faint }
                // Right-aligned to the labels' column, its capitals centred on the line.
                setViewLayoutMargin(R.id.lbl, RemoteViews.MARGIN_START, LABELS - 3f - pic.wide(context), COMPLEX_UNIT_DIP)
                setViewLayoutMargin(R.id.lbl, RemoteViews.MARGIN_TOP, maxOf(0f, y - pic.dp(context) * 0.4f), COMPLEX_UNIT_DIP)
            })
        }

        for (d in 1..5) {
            val col = COLS[d - 1]
            val isToday = d == now.dow
            rv.removeAllViews(col)
            rv.backTint(col, k) { if (isToday) Palette.mix(it.accent, it.surface, 0.16f) else it.surface2 }
            rv.picture(context, HEADS[d - 1], k, DAY_SHORT[d], f.bold, 10f, colW) { if (isToday) it.accentText else it.muted }
            // In class, the ringed block already marks "now"; the line is for the gaps between classes.
            if (isToday && st?.phase != Phase.IN_CLASS && now.min >= DAY_START && now.min <= DAY_END) {
                rv.addView(col, RemoteViews(pkg, R.layout.grid_now).apply {
                    setViewLayoutMargin(R.id.now, RemoteViews.MARGIN_TOP, (now.min - DAY_START) / 60 * hour - 1, COMPLEX_UNIT_DIP)
                    setColorInt(R.id.now, "setBackgroundColor", k.light.accent, k.dark.accent)
                })
            }
            for (session in timetable.day(d)) {
                rv.addView(col, block(context, pkg, k, f, session, hour, colW, past = isToday && now.min >= session.end, highlight = session == s))
            }
        }
        return rv
    }

    private fun block(c: Context, pkg: String, k: Colors, f: Faces, s: Session, hour: Float, width: Float, past: Boolean, highlight: Boolean): RemoteViews {
        val height = (s.end - s.start) / 60f * hour - 1.5f
        val color = s.course.color
        val ink: (Palette) -> Int = when {
            past -> { p -> p.pastText(if (s.isTutorial) p.pastTint(color) else p.pastFill(color)) }
            s.isTutorial -> { p -> p.text }
            else -> { p -> p.ink }
        }
        return RemoteViews(pkg, R.layout.grid_block).apply {
            setViewLayoutMargin(R.id.blk, RemoteViews.MARGIN_TOP, (s.start - DAY_START) / 60f * hour + 0.75f, COMPLEX_UNIT_DIP)
            setViewLayoutHeight(R.id.blk, height, COMPLEX_UNIT_DIP)
            setImageViewResource(R.id.b_bg, if (s.isTutorial) R.drawable.blk_tut else R.drawable.blk_lec)
            setInt(R.id.b_bg, "setColorFilter", color)
            if (past) setInt(R.id.b_bg, "setImageAlpha", 100)
            // Short blocks show only the room, which is what you need when walking to class.
            if (height >= 28f) picture(c, R.id.b_code, k, s.course.code, f.monoBold, 8.5f, width - 7f, of = ink) else setViewVisibility(R.id.b_code, View.GONE)
            if (height >= 13f) picture(c, R.id.b_room, k, s.room, f.sign, 12f, width - 7f, of = ink) else setViewVisibility(R.id.b_room, View.GONE)
            if (highlight) {
                setViewVisibility(R.id.b_ring, View.VISIBLE)
                tint(R.id.b_ring, k) { it.accent }
            }
            setContentDescription(R.id.blk, "${DAY_NAMES[s.day]} ${clockText(s.start)}, ${s.course.code}, ${s.room}")
        }
    }

    private fun todayText(st: Status, now: Now): String = when {
        !st.isWeekday -> "No classes today"
        st.classesLeftToday == 0 -> if (now.dow == 5) "Done for the week" else "Done for today"
        else -> {
            val n = st.classesLeftToday
            "$n ${if (n == 1) "class" else "classes"} left today · ${durationText(st.minutesLeftToday)}"
        }
    }
}
