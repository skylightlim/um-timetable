package com.skylightlim.umtimetable

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The tour of the app for a new user: a card for each gesture worth knowing, over the app dimmed, with a
 * spotlight on what it's done to and a finger acting it out there (a tap, a double tap, a hold, a swipe, a
 * drag). Each stop opens its page first, so what's lit is the real thing. The first card can be skipped
 * (Back ends it at any stop); the others go back with Previous. Shown once, after the first sign-in or
 * timetable import; an app already in use when this came is taken to have seen it. Settings' "App tour"
 * shows it again.
 */
object Tour {
    /** What a stop lights up and points at. */
    enum class Target { CLASS_CARD, DAY_PAGE, FREE_TIME, PLAN, WEEK_CLASS, TITLE, DUE_ROW, COURSE_FOLD, COURSE_NAME, SECTIONS_TAB }

    /** What the finger does there. */
    enum class Gesture { TAP, DOUBLE_TAP, HOLD, SWIPE_UP, DRAG }

    /**
     * A stop: the [page] it opens ("day", "week", "due", "files", or "course": the first course's page), what it
     * lights, what the finger does there (in turn, over and over), and what the card says.
     */
    data class Stop(val page: String, val target: Target, val gestures: List<Gesture>, val title: String, val body: String)

    val STOPS = listOf(
        Stop("day", Target.CLASS_CARD, listOf(Gesture.TAP), "Your classes",
            "Tap a class for its details. The pen at the top lets you change its name, colour, time, room or lecturer."),
        Stop("day", Target.DAY_PAGE, listOf(Gesture.SWIPE_UP), "Swipe up for the week",
            "On Day, swipe up to see the whole week. Swipe sideways to go from Monday to Friday."),
        Stop("week", Target.FREE_TIME, listOf(Gesture.HOLD, Gesture.DOUBLE_TAP), "Add a plan",
            "Hold an empty time, or double-tap it, to add a plan there."),
        Stop("week", Target.PLAN, listOf(Gesture.DRAG), "Move a plan",
            "Hold a plan, then drag it to another time."),
        Stop("week", Target.WEEK_CLASS, listOf(Gesture.HOLD), "Delete a class",
            "Hold a class for its card, then hold Delete. Getting your timetable from TimeEdit again brings it back."),
        Stop("week", Target.TITLE, listOf(Gesture.DOUBLE_TAP), "Back to this week",
            "Swipe the week sideways to see others. Double-tap the big date title to come back to this week."),
        Stop("due", Target.DUE_ROW, listOf(Gesture.HOLD), "Dismiss a deadline",
            "Hold a deadline to dismiss it. It moves to Dismissed at the bottom, where holding it brings it back."),
        Stop("files", Target.COURSE_FOLD, listOf(Gesture.TAP), "News and grades",
            "Tap the arrow beside a course to see its news and grades."),
        Stop("files", Target.COURSE_NAME, listOf(Gesture.TAP), "Open on Spectrum",
            "Tap a course's name to open its Spectrum page."),
        Stop("course", Target.SECTIONS_TAB, listOf(Gesture.TAP), "Sections",
            "On a course page, the tab on the left edge lists every section, to jump straight to one."),
    )

    /** Where a stop's finger goes: [view], or the part of it [area] gives (in the view's own coordinates) when it gives one. */
    class Spot(val view: View, val area: (() -> RectF?)? = null)

    // What the pages mark for the tour to find ([find]), under the tag key R.id.tour.
    const val CLASS = "class"
    const val DUE = "due"
    const val COURSE = "course"
    const val FOLD = "fold"

    /** The first view under [root] marked [mark] that's on screen, in drawing order. */
    fun find(root: View?, mark: String): View? {
        if (root == null || !root.isShown) return null
        if (root.getTag(R.id.tour) == mark) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), mark)?.let { return it }
        return null
    }

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

    /** How long one go of [g] takes, from the finger coming to its going (ms). */
    fun length(g: Gesture) = when (g) {
        Gesture.TAP -> 1500L
        Gesture.DOUBLE_TAP -> 1700L
        Gesture.HOLD -> 2100L
        Gesture.SWIPE_UP -> 1800L
        Gesture.DRAG -> 2500L
    }

    /**
     * The finger [ms] into a go of [g]: how far it is from the spot ([dx], [dy], in units of [reach]: a swipe
     * and a drag move about that far), how much it shows, how far it's pressed, a tap's ripple and a hold's
     * filling ring (0 to 1, or -1 when there's none).
     */
    class Pose(var dx: Float = 0f, var dy: Float = 0f, var shown: Float = 0f, var down: Float = 0f, var ripple: Float = -1f, var hold: Float = -1f)

    fun pose(g: Gesture, ms: Long, out: Pose = Pose()): Pose {
        val t = ms.toFloat()
        fun ramp(a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
        fun press(at: Float, upAt: Float) = ramp(at, at + 80) - ramp(upAt, upAt + 80)
        fun ripple(at: Float) = if (t in at..at + 500) (t - at) / 500 else -1f
        val end = length(g).toFloat()
        out.dx = 0f
        out.dy = 0f
        out.ripple = -1f
        out.hold = -1f
        out.shown = ramp(0f, 250f) * (1 - ramp(end - 350, end - 50))
        when (g) {
            Gesture.TAP -> {
                out.down = press(300f, 420f)
                out.ripple = ripple(380f)
            }
            Gesture.DOUBLE_TAP -> {
                out.down = press(300f, 390f) + press(580f, 670f)
                out.ripple = ripple(640f).takeIf { it >= 0 } ?: ripple(360f)
            }
            Gesture.HOLD -> {
                out.down = press(300f, 1300f)
                if (t in 380f..1380f) out.hold = ramp(380f, 1150f)
            }
            Gesture.SWIPE_UP -> {
                out.down = press(300f, 1050f)
                out.dy = 1 - 2 * ramp(450f, 1050f)
            }
            Gesture.DRAG -> {
                out.down = press(300f, 1900f)
                if (t in 380f..1000f) out.hold = ramp(380f, 950f)
                out.dx = 0.6f * ramp(1000f, 1750f)
                out.dy = 0.8f * ramp(1000f, 1750f)
            }
        }
        return out
    }
}

/**
 * The tour over the app ([Tour]): the dimmed app with a hole lit around the stop's spot, a finger acting out its
 * gesture there, and the stop's card above or below it. From one stop to the next the hole and the card glide
 * together. [show] opens a page; [target] finds the spot on it (null: there's nothing there to point at);
 * [onEnd] says whether it was finished or skipped, once it has faded away.
 */
class TourView(
    private val ui: Ui,
    private val show: (String) -> Unit,
    private val target: (Tour.Target) -> Tour.Spot?,
    private val onEnd: (finished: Boolean) -> Unit,
) : FrameLayout(ui.context) {
    private val stops = Tour.STOPS
    private var index = -1
    private var ending = false

    /** The hole as drawn, where it was when this stop began, and how far along the glide to the new one is (0 to 1). */
    private val hole = RectF()
    private val from = RectF()
    private var fromY = 0f
    private var t = 1f
    private var glide: ValueAnimator? = null

    /** Where the finger touches (the middle of the spot, before the hole's room around it), and its gesture's clock. */
    private var touchX = 0f
    private var touchY = 0f
    private var pointing = false
    private var clock: ValueAnimator? = null
    private var gestureMs = 0L
    private val pose = Tour.Pose()

    private val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB8000000.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ui.dp(2f).toFloat()
        color = ui.p.accent
    }
    /** What the finger presses lights up a little while it's pressed. */
    private val pressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val radius = ui.dp(16f).toFloat()
    private var barTop = 0
    private var barBottom = 0

    // The finger: a fingertip from below and to the right, its nail, fading out down its length; a ripple for a
    // tap, a ring filling for a hold, as Android's own "show taps" dot.
    private val fingerW = ui.dp(28f).toFloat()
    private val fingerL = ui.dp(120f).toFloat()
    private val skin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, fingerL * 0.45f, 0f, fingerL, 0xFFF4F1EE.toInt(), 0x00F4F1EE, Shader.TileMode.CLAMP)
        setShadowLayer(ui.dp(6f).toFloat(), 0f, ui.dp(2f).toFloat(), 0x55000000)
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ui.dp(1f).toFloat()
        shader = LinearGradient(0f, fingerL * 0.45f, 0f, fingerL, 0x40000000, 0x00000000, Shader.TileMode.CLAMP)
    }
    private val nail = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE3DCD5.toInt() }
    private val crease = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26000000; strokeWidth = ui.dp(1f).toFloat(); strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ui.dp(2f).toFloat(); color = 0xFFFFFFFF.toInt() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ui.dp(3f).toFloat(); color = 0x59FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ui.dp(3f).toFloat(); strokeCap = Paint.Cap.ROUND; color = ui.p.accent }
    private val fingerBody = RectF()
    private val nailBox = RectF()
    private val arc = RectF()

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
    /** Skip on the first card, Previous on the rest. */
    private val backButton: TextView = ConfirmDialog.button(ui, "Skip", ui.p.surface2, ui.p.text) {
        Haptics.tick(this)
        if (index == 0) skip() else step(index - 1)
    }
    private val nextButton: TextView = ConfirmDialog.button(ui, "Next", ui.p.accent, ui.p.accentInk) { Haptics.tick(this); step(index + 1) }
    private val card = ConfirmDialog.frame(ui).apply {
        addView(words)
        addView(ui.row(gap = 8f).apply {
            addView(backButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(nextButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, ui.wide(top = 20f))
    }

    /** Each frame: the hole follows its spot (the page under it may still be arriving), and the card follows the hole. */
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
        if (ending || i < 0) return
        if (i >= stops.size) return end(true)
        val stop = stops[i]
        val first = index < 0
        index = i
        show(stop.page)
        count.text = "${i + 1} of ${stops.size}"
        title.text = stop.title
        body.text = stop.body
        backButton.text = if (i == 0) "Skip" else "Previous"
        nextButton.text = if (i == stops.size - 1) "Done" else "Next"
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
        play(stop.gestures)
        invalidate()
    }

    /** The finger plays [gestures] in turn, over and over, from the start; with "Remove animations", it rests pressed on the spot. */
    private fun play(gestures: List<Tour.Gesture>) {
        clock?.cancel()
        clock = null
        if (!Motion.enabled) {
            gestureMs = -1
            return
        }
        gestureMs = 0
        val total = gestures.sumOf { Tour.length(it) }
        clock = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = total
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                var ms = (it.animatedFraction * total).toLong()
                for (g in gestures) {
                    if (ms < Tour.length(g)) { Tour.pose(g, ms, pose); break }
                    ms -= Tour.length(g)
                }
                invalidate()
            }
            start()
        }
    }

    private fun end(finished: Boolean) {
        if (ending) return
        ending = true
        glide?.cancel()
        fun gone() { clock?.cancel(); (parent as? ViewGroup)?.removeView(this); onEnd(finished) }
        if (!Motion.enabled) return gone()
        animate().alpha(0f).setInterpolator(Motion.EXIT).setDuration(160).withEndAction { gone() }.start()
    }

    private val at = IntArray(2)
    private val me = IntArray(2)

    /** Where the stop's spot is over this view (without room around it); empty when there's nothing to point at. */
    private fun spotRect(out: RectF) {
        out.setEmpty()
        if (index < 0) return
        val spot = target(stops[index].target) ?: return
        val v = spot.view
        if (!v.isShown || v.width == 0) return
        v.getLocationInWindow(at)
        getLocationInWindow(me)
        val x = (at[0] - me[0]).toFloat()
        val y = (at[1] - me[1]).toFloat()
        val area = spot.area?.invoke()
        if (area != null) out.set(area.left + x, area.top + y, area.right + x, area.bottom + y)
        else out.set(x, y, x + v.width, y + v.height)
    }

    private val to = RectF()
    private val was = RectF()

    private fun place() {
        if (width == 0 || card.height == 0) return
        spotRect(to)
        pointing = !to.isEmpty
        if (pointing) {
            touchX = to.centerX()
            touchY = to.centerY()
            val pad = ui.dp(6f).toFloat()
            to.inset(-pad, -pad)
        }
        // Gliding to or from no spot, the hole shrinks to (or grows from) the middle of the one there is.
        val a = if (from.isEmpty && !to.isEmpty) RectF(to.centerX(), to.centerY(), to.centerX(), to.centerY()) else from
        val b = if (to.isEmpty && !from.isEmpty) RectF(from.centerX(), from.centerY(), from.centerX(), from.centerY()) else to
        was.set(hole)
        hole.set(lerp(a.left, b.left), lerp(a.top, b.top), lerp(a.right, b.right), lerp(a.bottom, b.bottom))
        if (to.isEmpty && t >= 1f) hole.setEmpty()
        if (hole != was) invalidate()
        // The card under a spot in the top half, over one in the bottom half, in the middle with none; on screen.
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
        clock?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        path.reset()
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        if (!hole.isEmpty) path.addRoundRect(hole, radius, radius, Path.Direction.CW)
        canvas.drawPath(path, scrim)
        if (hole.isEmpty) return
        canvas.drawRoundRect(hole, radius, radius, ring)
        // The finger, once the hole has arrived at its spot.
        if (!pointing || t < 0.85f) return
        if (gestureMs < 0) { pose.shown = 1f; pose.down = 1f; pose.dx = 0f; pose.dy = 0f; pose.ripple = -1f; pose.hold = -1f }
        val shown = pose.shown * ((t - 0.85f) / 0.15f).coerceIn(0f, 1f)
        if (shown <= 0f) return
        if (pose.down > 0f) {
            pressed.alpha = (36 * pose.down * shown).toInt()
            canvas.drawRoundRect(hole, radius, radius, pressed)
        }
        drawFinger(canvas, touchX + pose.dx * ui.dp(60f), touchY + pose.dy * ui.dp(60f), shown)
    }

    private fun drawFinger(canvas: Canvas, x: Float, y: Float, shown: Float) {
        val a = (255 * shown).toInt()
        val down = pose.down.coerceIn(0f, 1f)
        if (pose.ripple >= 0f) {
            wave.alpha = (a * (1 - pose.ripple)).toInt()
            canvas.drawCircle(x, y, ui.dp(14f) + ui.dp(22f) * pose.ripple, wave)
        }
        if (pose.hold >= 0f) {
            val r = ui.dp(24f).toFloat()
            arc.set(x - r, y - r, x + r, y + r)
            track.alpha = (89 * shown).toInt()
            canvas.drawOval(arc, track)
            fill.alpha = a
            canvas.drawArc(arc, -90f, 360f * pose.hold, false, fill)
        }
        // The touch itself: a soft dot under the fingertip while it's pressed.
        dot.alpha = (110 * down * shown).toInt()
        canvas.drawCircle(x, y, ui.dp(14f).toFloat(), dot)
        // Lifted, the finger is a little bigger and off to the side; pressed, its tip is on the spot.
        val lift = 1 - down
        canvas.save()
        canvas.translate(x + ui.dp(6f) * lift, y + ui.dp(10f) * lift)
        canvas.rotate(-22f)
        canvas.scale(1 + 0.08f * lift, 1 + 0.08f * lift)
        fingerBody.set(-fingerW / 2, -ui.dp(4f).toFloat(), fingerW / 2, fingerL)
        skin.alpha = a
        canvas.drawRoundRect(fingerBody, fingerW / 2, fingerW / 2, skin)
        edge.alpha = a
        canvas.drawRoundRect(fingerBody, fingerW / 2, fingerW / 2, edge)
        nailBox.set(-fingerW / 2 + ui.dp(5f), 0f, fingerW / 2 - ui.dp(5f), ui.dp(19f).toFloat())
        nail.alpha = a
        canvas.drawRoundRect(nailBox, ui.dp(6f).toFloat(), ui.dp(6f).toFloat(), nail)
        crease.alpha = (38 * shown).toInt()
        for (k in listOf(36f, 41f)) canvas.drawLine(-fingerW / 4, ui.dp(k).toFloat(), fingerW / 4, ui.dp(k).toFloat(), crease)
        canvas.restore()
    }
}
