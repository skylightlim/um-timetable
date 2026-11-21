package com.skylightlim.umtimetable

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The tour of the app for a new user: a card for each stop, over the app dimmed, with a spotlight on what the
 * card is about (the strip, a tab). Each stop opens its page first, so what's lit is the real thing. Skip (or
 * Back) ends it at any stop. Shown once, after the first sign-in or timetable import; an app already in use
 * when this came is taken to have seen it. Settings' "App tour" shows it again.
 */
object Tour {
    /** What a stop lights up; [NONE] is a card on its own, in the middle. */
    enum class Target { NONE, STRIP, DAY_TAB, DUE_TAB, COURSES_TAB, SETTINGS_TAB }

    /** A stop: the [page] it opens ("day", "week", "due", "files", "settings"), what it lights, and what it says. */
    data class Stop(val page: String, val target: Target, val title: String, val body: String)

    val STOPS = listOf(
        Stop("day", Target.NONE, "A quick tour",
            "A minute to see where everything is. Skip it any time: it's in Settings to see again."),
        Stop("day", Target.STRIP, "Your day",
            "Day shows your next classes. Tap a day here, or swipe across the page, to step from Monday to Friday. Tap a class for everything about it."),
        Stop("week", Target.DAY_TAB, "The week",
            "Swipe up on Day, or tap this tab, for the whole week. Swipe sideways to change week; double-tap the title to come back. " +
                "Double-tap a free time to plan something there, and hold a class for its card."),
        Stop("due", Target.DUE_TAB, "Due",
            "Your Spectrum deadlines, soonest first. The badge counts attendance open to mark now. Pull the page down to sync."),
        Stop("files", Target.COURSES_TAB, "Courses",
            "Each course's news and marks fold open on the list. Tap a course for its sections and files. The badge counts what's new."),
        Stop("settings", Target.SETTINGS_TAB, "Settings",
            "Get your timetable, set class reminders, turn on Mark my attendance and pick a theme. Holding the page's title opens Settings from anywhere."),
        Stop("day", Target.NONE, "You're set",
            "See this tour again any time from Settings, under App."),
    )

    private const val KEY = "tour"
    const val PENDING = "pending"
    const val SEEN = "seen"

    /**
     * What a launch makes of the tour's [state]: a first run (the sign-in poster is up) is to see it, once
     * signed in; an app already in use, with no state yet, was in use before the tour came and has seen it.
     */
    fun onLaunch(state: String?, firstRun: Boolean): String = when {
        state == SEEN -> SEEN
        firstRun || state == PENDING -> PENDING
        else -> SEEN
    }

    /** Settles the state at launch ([onLaunch]) and says whether the tour is still to come. */
    fun launch(c: Context, firstRun: Boolean): Boolean {
        val prefs = uiPrefs(c)
        val state = onLaunch(prefs.getString(KEY, null), firstRun)
        prefs.edit().putString(KEY, state).apply()
        return state == PENDING
    }

    fun pending(c: Context) = uiPrefs(c).getString(KEY, null) == PENDING

    fun markSeen(c: Context) = uiPrefs(c).edit().putString(KEY, SEEN).apply()
}

/**
 * The tour over the app ([Tour]): the dimmed app with a hole lit around the stop's target, and the stop's card
 * above or below it. From one stop to the next the hole and the card glide together. [show] opens a page;
 * [target] finds what to light on it (null: nothing); [onEnd] says whether it was finished or skipped, once it
 * has faded away.
 */
class TourView(
    private val ui: Ui,
    private val show: (String) -> Unit,
    private val target: (Tour.Target) -> View?,
    private val onEnd: (finished: Boolean) -> Unit,
) : FrameLayout(ui.context) {
    private val stops = Tour.STOPS
    private var index = -1
    private var lit: View? = null
    private var ending = false

    /** The hole as drawn, where it was when this stop began, and how far along the glide to the new one is (0 to 1). */
    private val hole = RectF()
    private val from = RectF()
    private var fromY = 0f
    private var t = 1f
    private var glide: ValueAnimator? = null

    private val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB8000000.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ui.dp(2f).toFloat()
        color = ui.p.accent
    }
    private val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val radius = ui.dp(16f).toFloat()
    private var barTop = 0
    private var barBottom = 0

    private val count = ui.label("", ui.p.muted, 10.5f)
    private val title = ui.text("", 30f, ui.p.text, ui.f.sign)
    private val body = ui.text("", 15f, ui.p.muted).apply { setLineSpacing(0f, 1.35f) }
    private val words = ui.column().apply {
        addView(count)
        addView(title, ui.wide(top = 6f))
        addView(body, ui.wide(top = 8f))
        // TalkBack reads each stop's words as they change.
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val skipButton: TextView = ConfirmDialog.button(ui, "Skip", ui.p.surface2, ui.p.text) { Haptics.tick(this); skip() }
    private val nextButton: TextView = ConfirmDialog.button(ui, "Next", ui.p.accent, ui.p.accentInk) { Haptics.tick(this); step(index + 1) }
    private val card = ConfirmDialog.frame(ui).apply {
        addView(words)
        addView(ui.row(gap = 8f).apply {
            addView(skipButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(nextButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, ui.wide(top = 20f))
    }

    /** Each frame: the hole follows its target (the page under it may still be arriving), and the card follows the hole. */
    private val follow = ViewTreeObserver.OnPreDrawListener { place(); true }

    init {
        isClickable = true // the app under the tour takes no touches
        clipChildren = false
        setWillNotDraw(false)
        addView(card, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            leftMargin = ui.dp(20)
            rightMargin = ui.dp(20)
        })
        setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            barTop = bars.top
            barBottom = bars.bottom
            invalidate()
            insets
        }
    }

    /** Starts at the first stop, fading in over the app. */
    fun start() {
        step(0)
        if (Motion.enabled) Motion.fade(this, 0f, 1f, 220)
    }

    /** Ends the tour before its last stop (Skip, or Back). */
    fun skip() = end(false)

    private fun step(i: Int) {
        if (ending) return
        if (i >= stops.size) return end(true)
        val stop = stops[i]
        val first = index < 0
        index = i
        show(stop.page)
        lit = target(stop.target)?.takeIf { it.isShown }
        count.text = "${i + 1} of ${stops.size}"
        title.text = stop.title
        body.text = stop.body
        val last = i == stops.size - 1
        skipButton.visibility = if (last) View.GONE else View.VISIBLE
        nextButton.text = if (last) "Done" else "Next"
        // From where the hole and the card are now to the new stop's.
        from.set(hole)
        fromY = card.translationY
        glide?.cancel()
        if (first || !Motion.enabled) {
            t = 1f
        } else {
            t = 0f
            glide = ValueAnimator.ofFloat(0f, 1f).apply {
                interpolator = Motion.SETTLE
                duration = Motion.SETTLE.duration
                addUpdateListener { t = it.animatedValue as Float; invalidate() }
                start()
            }
            Motion.fade(words, 0f, 1f, 200)
        }
        invalidate()
    }

    private fun end(finished: Boolean) {
        if (ending) return
        ending = true
        glide?.cancel()
        fun gone() { (parent as? ViewGroup)?.removeView(this); onEnd(finished) }
        if (!Motion.enabled) return gone()
        animate().alpha(0f).setInterpolator(Motion.EXIT).setDuration(160).withEndAction { gone() }.start()
    }

    /** Where the stop's target is over this view, with room around it; empty when there's nothing to light. */
    private fun targetRect(out: RectF) {
        val v = lit
        if (v == null || !v.isAttachedToWindow || v.width == 0) { out.setEmpty(); return }
        val at = IntArray(2).also(v::getLocationInWindow)
        val me = IntArray(2).also(this::getLocationInWindow)
        val pad = ui.dp(6f).toFloat()
        val x = (at[0] - me[0]).toFloat()
        val y = (at[1] - me[1]).toFloat()
        out.set(x - pad, y - pad, x + v.width + pad, y + v.height + pad)
    }

    private val to = RectF()
    private val was = RectF()

    private fun place() {
        if (width == 0 || card.height == 0) return
        targetRect(to)
        // Gliding to or from no target, the hole shrinks to (or grows from) the middle of the one there is.
        val a = if (from.isEmpty && !to.isEmpty) RectF(to.centerX(), to.centerY(), to.centerX(), to.centerY()) else from
        val b = if (to.isEmpty && !from.isEmpty) RectF(from.centerX(), from.centerY(), from.centerX(), from.centerY()) else to
        was.set(hole)
        hole.set(lerp(a.left, b.left), lerp(a.top, b.top), lerp(a.right, b.right), lerp(a.bottom, b.bottom))
        if (to.isEmpty && t >= 1f) hole.setEmpty()
        if (hole != was) invalidate()
        // The card under a target in the top half, over one in the bottom half, in the middle with none; on screen.
        val gap = ui.dp(16f).toFloat()
        val lo = barTop + gap
        val hi = maxOf(lo, height - barBottom - gap - card.height)
        val y = when {
            to.isEmpty -> (height - card.height) / 2f
            to.centerY() < height / 2f -> to.bottom + gap
            else -> to.top - gap - card.height
        }.coerceIn(lo, hi)
        val cardY = if (t >= 1f) y else lerp(fromY, y)
        if (cardY != card.translationY) card.translationY = cardY
    }

    private fun lerp(a: Float, b: Float) = a + (b - a) * t

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(follow)
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(follow)
        glide?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        path.reset()
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        if (!hole.isEmpty) path.addRoundRect(hole, radius, radius, Path.Direction.CW)
        canvas.drawPath(path, scrim)
        if (!hole.isEmpty) canvas.drawRoundRect(hole, radius, radius, ring)
    }
}
