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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.skylightlim.umtimetable.Motion.spring

/**
 * The tour of the app for a new user: a card for each thing worth knowing, over the app dimmed, with a
 * spotlight on what it's done to and a finger doing it there, over and over, and what it does playing out:
 * the class's sheet opening and its pen tapped, a plan's card popping up, a plan carried to another time, a
 * class's card and its Delete held, the week coming back, a deadline's Dismiss, a course's news folding
 * open, the Sections panel. Cards and menus that would open are drawn in the tour as they look, and nothing
 * is saved, deleted or dismissed. Each stop opens its page first, so what's lit is the real thing ([TourScenes]
 * plays each one). The first card can be skipped (Back ends it at any stop); the others go back with
 * Previous. Shown once, after the first sign-in or timetable import; an app already in use when this came is
 * taken to have seen it. Settings' "App tour" shows it again.
 */
object Tour {
    /** What a stop shows, played by [TourScenes]. */
    enum class Act { CLASS, EDIT, SWIPE_UP, ADD_PLAN, MOVE_PLAN, DELETE_CLASS, THIS_WEEK, DISMISS, NEWS, SPECTRUM, SECTIONS }

    /** What the finger does; [LOOK] is no finger, only the spotlight on what's come up. */
    enum class Gesture { TAP, DOUBLE_TAP, HOLD, SWIPE_UP, DRAG, LOOK }

    /** A stop: the [page] it opens ("day", "week", "due", "files", or "course": the first course's page), what it shows, and what the card says. */
    data class Stop(val page: String, val act: Act, val title: String, val body: String)

    val STOPS = listOf(
        Stop("day", Act.CLASS, "Your classes",
            "Tap a class for everything about it: its room, lecturer and attendance."),
        Stop("day", Act.EDIT, "Edit a class",
            "Its card has a pen at the top. Tap it to change the class's name, colour, time, room or lecturer."),
        Stop("day", Act.SWIPE_UP, "Swipe up for the week",
            "On Day, swipe up to see the whole week. Swipe sideways to go from Monday to Friday."),
        Stop("week", Act.ADD_PLAN, "Add a plan",
            "Hold an empty time, or double-tap it, and a plan's card opens there."),
        Stop("week", Act.MOVE_PLAN, "Move a plan",
            "Hold a plan until it lifts, then drag it to another time."),
        Stop("week", Act.DELETE_CLASS, "Delete a class",
            "Hold a class for its card, then hold Delete. Getting your timetable from TimeEdit again brings it back."),
        Stop("week", Act.THIS_WEEK, "Back to this week",
            "Swipe the week sideways to see others. Double-tap the big date title to come back to this week."),
        Stop("due", Act.DISMISS, "Dismiss a deadline",
            "Hold a deadline, then tap Dismiss. It moves to Dismissed at the bottom, where holding it brings it back."),
        Stop("files", Act.NEWS, "News and grades",
            "Tap the arrow beside a course to open its news and grades under it."),
        Stop("files", Act.SPECTRUM, "Open on Spectrum",
            "Tap a course's name to open its Spectrum page."),
        Stop("course", Act.SECTIONS, "Sections",
            "On a course page, tap the tab on the left edge for every section, then tap one to jump there."),
    )

    /** Where the finger goes: [view], or the part of it [area] gives (in the view's own coordinates) when it gives one. */
    class Spot(val view: View, val area: (() -> RectF?)? = null)

    // What the pages mark for the tour to find ([find]), under the tag key R.id.tour. A Day class card also
    // carries its Session under R.id.tour_session.
    const val CLASS = "class"
    const val PEN = "pen"
    const val DUE = "due"
    const val COURSE = "course"
    const val FOLD = "fold"
    const val NEWS = "news"
    const val DELETE = "delete"

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
        Gesture.LOOK -> 2200L
    }

    /** When [g] does what it does (ms into it): a tap's touch, the second tap, a hold full, a swipe's end, a drag's lift. */
    fun actAt(g: Gesture) = when (g) {
        Gesture.TAP -> 400L
        Gesture.DOUBLE_TAP -> 680L
        Gesture.HOLD -> 1150L
        Gesture.SWIPE_UP -> 1050L
        Gesture.DRAG -> 950L
        Gesture.LOOK -> 0L
    }

    /**
     * The finger [ms] into a go of [g]: how far it is from the spot ([dx], [dy], in units of a swipe's
     * reach), how much it shows, how far it's pressed, a tap's ripple and a hold's filling ring (0 to 1, or
     * -1 when there's none).
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
        out.down = 0f
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
            Gesture.LOOK -> out.shown = 0f
        }
        return out
    }
}

/**
 * One move of a stop: [gesture] at what [aim] finds (null: nothing to point at now). [press] runs when it
 * lands ([Tour.actAt]); [holding] each frame with how full a hold is; [after] once it and its [rest] are over.
 * [carry]: the colour of a plan a drag carries along with the finger.
 */
class Beat(
    val gesture: Tour.Gesture,
    val aim: () -> Tour.Spot?,
    val rest: Long = 600,
    val press: (() -> Unit)? = null,
    val holding: ((Float) -> Unit)? = null,
    val after: (() -> Unit)? = null,
    val carry: Int? = null,
)

/**
 * A stop played out: its [beats], over and over. [reset] runs before each go (it puts back what the last go
 * did), [leave] when the stop is left. [card]: where the stop's card goes: -1 at the top, 1 at the bottom, 0
 * by its first beat's spot (under it in the top half, over it in the bottom half).
 */
class Scene(val beats: List<Beat>, val card: Int = 0, val reset: () -> Unit = {}, val leave: () -> Unit = {})

/**
 * The tour over the app ([Tour]): the dimmed app with a hole lit around the beat's spot, a finger acting out
 * its gesture there, what opens shown on the [stage] (cards, a menu, a panel, as they look, and not to be
 * touched), and the stop's card. [show] opens a page; [scene] plays a stop on it; [onEnd] says whether it was
 * finished or skipped, once it has faded away.
 */
class TourView(
    private val ui: Ui,
    private val show: (String) -> Unit,
    private val scene: (Tour.Act, TourView) -> Scene,
    private val onEnd: (finished: Boolean) -> Unit,
) : FrameLayout(ui.context) {
    private val stops = Tour.STOPS
    private var index = -1
    private var ending = false
    private var playing: Scene? = null

    /** The hole as drawn, where it was when the beat began, and how far along the glide to the new one is (0 to 1). */
    private val hole = RectF()
    private val from = RectF()
    private var t = 1f
    private var glide: ValueAnimator? = null
    /** The card, where it was when the stop began, and its own glide. */
    private var fromY = 0f
    private var ct = 1f
    private var cardGlide: ValueAnimator? = null

    /** The beat playing, its clock, whether it has pressed yet, and where the finger touches (the spot's middle). */
    private var beat = -1
    private var pressDone = false
    private var lastMs = -1L
    private var beatMs = 0L
    private var clock: ValueAnimator? = null
    private var still = false
    private val pose = Tour.Pose()
    private val spot = RectF()
    private var pointing = false

    private val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB8000000.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ui.dp(2f).toFloat()
        color = ui.p.accent
    }
    /** What the finger presses lights up a little while it's pressed. */
    private val pressedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val radius = ui.dp(16f).toFloat()
    private var barTop = 0
    private var barBottom = 0

    // The finger: a fingertip from below and to the right, its nail, fading out down its length; a ripple for a
    // tap, a ring filling for a hold, as Android's own "show taps" dot.
    private val fingerW = ui.dp(40f).toFloat()
    private val fingerL = ui.dp(170f).toFloat()
    private val skin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, fingerL * 0.45f, 0f, fingerL, 0xFFF4F1EE.toInt(), 0x00F4F1EE, Shader.TileMode.CLAMP)
        setShadowLayer(ui.dp(8f).toFloat(), 0f, ui.dp(3f).toFloat(), 0x55000000)
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ui.dp(1f).toFloat()
        shader = LinearGradient(0f, fingerL * 0.45f, 0f, fingerL, 0x40000000, 0x00000000, Shader.TileMode.CLAMP)
    }
    private val nail = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE3DCD5.toInt() }
    private val crease = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26000000; strokeWidth = ui.dp(1.2f).toFloat(); strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ui.dp(2.5f).toFloat(); color = 0xFFFFFFFF.toInt() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ui.dp(3.5f).toFloat(); color = 0x59FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ui.dp(3.5f).toFloat(); strokeCap = Paint.Cap.ROUND; color = ui.p.accent }
    private val carried = Paint(Paint.ANTI_ALIAS_FLAG)
    private val carriedText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ui.f.bodySemi; textSize = ui.dp(11f).toFloat(); color = ui.p.ink }
    private val fingerBody = RectF()
    private val nailBox = RectF()
    private val arc = RectF()
    private val block = RectF()

    /** Where what would open is shown: on top of the dimmed app, under the card; it takes no touches. */
    private val stage = object : FrameLayout(ui.context) {
        override fun onInterceptTouchEvent(ev: MotionEvent) = true
        override fun onTouchEvent(event: MotionEvent) = true
    }.apply { descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS } // a plan's text field mustn't take focus

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

    /** Each frame: the hole follows its spot (the page under it may still be arriving), and the card its place. */
    private val follow = ViewTreeObserver.OnPreDrawListener { place(); true }

    init {
        isClickable = true // the app under the tour takes no touches
        clipChildren = false
        setWillNotDraw(false)
        addView(stage, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
        stopScene()
        show(stop.page)
        count.text = "${i + 1} of ${stops.size}"
        title.text = stop.title
        body.text = stop.body
        backButton.text = if (i == 0) "Skip" else "Previous"
        nextButton.text = if (i == stops.size - 1) "Done" else "Next"
        // The card glides from where it is to the new stop's place.
        fromY = card.translationY
        cardGlide?.cancel()
        if (first || !Motion.enabled) ct = 1f else {
            ct = 0f
            cardGlide = ValueAnimator.ofFloat(0f, 1f).apply {
                interpolator = Motion.SETTLE
                duration = Motion.SETTLE.duration
                addUpdateListener { ct = it.animatedValue as Float; invalidate() }
                start()
            }
            Motion.fade(words, 0f, 1f, 200)
        }
        val s = scene(stop.act, this)
        playing = s
        play(s, first)
        invalidate()
    }

    /** The stop playing is left: what it opened goes, and what it changed is put back. */
    private fun stopScene() {
        clock?.cancel()
        clock = null
        playing?.leave?.invoke()
        playing = null
        stage.removeAllViews()
        beat = -1
    }

    /** Plays [s]'s beats in turn, over and over; with "Remove animations", the finger rests pressed on its first. */
    private fun play(s: Scene, first: Boolean) {
        lastMs = -1L
        s.reset()
        if (s.beats.isEmpty()) return
        if (!Motion.enabled) {
            still = true
            toBeat(0, first = true)
            s.beats[0].press?.invoke()
            return
        }
        still = false
        val lens = s.beats.map { Tour.length(it.gesture) + it.rest }
        val total = lens.sum()
        toBeat(0, first)
        clock = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = total
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { tick(s, (it.animatedFraction * total).toLong(), lens) }
            start()
        }
    }

    private fun tick(s: Scene, at: Long, lens: List<Long>) {
        if (s !== playing) return
        if (at < lastMs) {
            // Round again: the last beat is over, and the next go starts from the scene as it was.
            s.beats.getOrNull(beat)?.after?.invoke()
            s.reset()
            beat = -1
        }
        lastMs = at
        var ms = at
        var i = 0
        while (i < lens.size - 1 && ms >= lens[i]) { ms -= lens[i]; i++ }
        if (i != beat) {
            s.beats.getOrNull(beat)?.after?.invoke()
            toBeat(i, first = false)
        }
        beatMs = ms
        val b = s.beats[i]
        if (ms < Tour.length(b.gesture)) Tour.pose(b.gesture, ms, pose) else Tour.pose(b.gesture, Tour.length(b.gesture), pose)
        if (!pressDone && ms >= Tour.actAt(b.gesture)) {
            pressDone = true
            b.press?.invoke()
        }
        b.holding?.invoke(pose.hold.coerceAtLeast(0f))
        invalidate()
    }

    /** On to beat [i]: the hole glides there (it starts there on a stop's first beat). */
    private fun toBeat(i: Int, first: Boolean) {
        beat = i
        pressDone = false
        from.set(hole)
        glide?.cancel()
        if (first || !Motion.enabled) { t = 1f; return }
        t = 0f
        glide = ValueAnimator.ofFloat(0f, 1f).apply {
            interpolator = Motion.SETTLE
            duration = Motion.SETTLE.duration
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun end(finished: Boolean) {
        if (ending) return
        ending = true
        glide?.cancel()
        stopScene()
        fun gone() { (parent as? ViewGroup)?.removeView(this); onEnd(finished) }
        if (!Motion.enabled) return gone()
        animate().alpha(0f).setInterpolator(Motion.EXIT).setDuration(160).withEndAction { gone() }.start()
    }

    // --- what opens, shown on the stage --------------------------------------------------------

    /** [v] as a sheet up from the bottom (the class's sheet). */
    fun sheet(v: View): View {
        v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom + barBottom)
        v.elevation = ui.dp(12f).toFloat()
        stage.addView(v, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        if (Motion.enabled) {
            v.translationY = ui.dp(480f).toFloat()
            v.animate().translationY(0f).spring(Motion.SETTLE).start()
        }
        return v
    }

    /** [v] as a card popping up over the top half of the page (a plan's card, a class's card). */
    fun card(v: View): View {
        val box = FrameLayout(context).apply {
            setPadding(ui.dp(20), 0, ui.dp(20), 0)
            clipToPadding = false
            addView(v, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        v.elevation = ui.dp(12f).toFloat()
        stage.addView(box, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { topMargin = barTop + ui.dp(64) })
        pop(box, 0.5f)
        return box
    }

    /** [v] as a menu that grows out of [under], what was held, from its top left (Due's Dismiss). */
    fun menu(v: View, under: View): View {
        val a = IntArray(2).also(under::getLocationInWindow)
        val me = IntArray(2).also(this::getLocationInWindow)
        stage.addView(v, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            leftMargin = a[0] - me[0]
            topMargin = a[1] - me[1] + under.height - ui.dp(6)
        })
        pop(v, 0f)
        return v
    }

    /** [v], [width] wide, as a panel sliding in from the left edge (Sections). */
    fun panel(v: View, width: Int): View {
        v.setPadding(v.paddingLeft, barTop, v.paddingRight, barBottom)
        stage.addView(v, LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        if (Motion.enabled) {
            v.translationX = -width.toFloat()
            v.animate().translationX(0f).spring(Motion.SETTLE).start()
        }
        return v
    }

    /** [v] goes from the stage, fading. */
    fun dismiss(v: View) {
        if (v.parent !== stage) return
        if (!Motion.enabled) return stage.removeView(v)
        v.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setInterpolator(Motion.EXIT).setDuration(150).withEndAction { stage.removeView(v) }.start()
    }

    private fun pop(v: View, pivotX: Float) {
        if (!Motion.enabled) return
        v.post {
            v.pivotX = v.width * pivotX
            v.pivotY = 0f
        }
        v.scaleX = 0.85f
        v.scaleY = 0.85f
        v.alpha = 0f
        v.animate().scaleX(1f).scaleY(1f).spring(Motion.BOUNCY).start()
        Motion.fade(v, 0f, 1f, 160)
    }

    // --- placing -------------------------------------------------------------------------------

    private val at = IntArray(2)
    private val me = IntArray(2)

    /** Where [find]'s spot is over this view, as it is now; empty when there's nothing to point at. */
    private fun spotRect(find: (() -> Tour.Spot?)?, out: RectF) {
        out.setEmpty()
        val s = find?.invoke() ?: return
        val v = s.view
        if (!v.isShown || v.width == 0) return
        v.getLocationInWindow(at)
        getLocationInWindow(me)
        val x = (at[0] - me[0]).toFloat()
        val y = (at[1] - me[1]).toFloat()
        val area = s.area?.invoke()
        if (area != null) out.set(area.left + x, area.top + y, area.right + x, area.bottom + y)
        else out.set(x, y, x + v.width, y + v.height)
    }

    private val to = RectF()
    private val was = RectF()
    private val firstSpot = RectF()

    private fun place() {
        if (width == 0 || card.height == 0) return
        val s = playing
        spotRect(s?.beats?.getOrNull(beat)?.aim, to)
        pointing = !to.isEmpty
        if (pointing) spot.set(to)
        val pad = ui.dp(6f).toFloat()
        if (pointing) to.inset(-pad, -pad)
        // Gliding to or from no spot, the hole shrinks to (or grows from) the middle of the one there is.
        val a = if (from.isEmpty && !to.isEmpty) RectF(to.centerX(), to.centerY(), to.centerX(), to.centerY()) else from
        val b = if (to.isEmpty && !from.isEmpty) RectF(from.centerX(), from.centerY(), from.centerX(), from.centerY()) else to
        was.set(hole)
        hole.set(lerp(a.left, b.left, t), lerp(a.top, b.top, t), lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t))
        if (to.isEmpty && t >= 1f) hole.setEmpty()
        if (hole != was) invalidate()
        // The card: where the scene puts it, or by its first spot; on screen.
        val gap = ui.dp(16f).toFloat()
        val lo = barTop + gap
        val hi = maxOf(lo, height - barBottom - gap - card.height)
        if (s?.card == 0) spotRect(s.beats.firstOrNull()?.aim, firstSpot) else firstSpot.setEmpty()
        val y = when {
            s?.card == -1 -> lo
            s?.card == 1 -> hi
            firstSpot.isEmpty -> (height - card.height) / 2f
            firstSpot.centerY() < height / 2f -> firstSpot.bottom + pad + gap
            else -> firstSpot.top - pad - gap - card.height
        }.coerceIn(lo, hi)
        val cardY = if (ct >= 1f) y else lerp(fromY, y, ct)
        if (cardY != card.translationY) card.translationY = cardY
    }

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(follow)
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(follow)
        glide?.cancel()
        cardGlide?.cancel()
        clock?.cancel()
        super.onDetachedFromWindow()
    }

    // --- drawing -------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        path.reset()
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        if (!hole.isEmpty) path.addRoundRect(hole, radius, radius, Path.Direction.CW)
        canvas.drawPath(path, scrim)
        if (!hole.isEmpty) canvas.drawRoundRect(hole, radius, radius, ring)
    }

    // The finger goes over what opens on the stage, under the card.
    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        val drawn = super.drawChild(canvas, child, drawingTime)
        if (child === stage) drawHand(canvas)
        return drawn
    }

    private fun drawHand(canvas: Canvas) {
        // Once the hole has arrived at its spot.
        if (!pointing || t < 0.85f || hole.isEmpty) return
        val b = playing?.beats?.getOrNull(beat) ?: return
        if (still) { pose.shown = 1f; pose.down = 1f; pose.dx = 0f; pose.dy = 0f; pose.ripple = -1f; pose.hold = -1f }
        val shown = pose.shown * ((t - 0.85f) / 0.15f).coerceIn(0f, 1f)
        if (shown <= 0f) return
        if (pose.down > 0f) {
            pressedFill.alpha = (36 * pose.down * shown).toInt()
            canvas.drawRoundRect(hole, radius, radius, pressedFill)
        }
        val reach = ui.dp(60f).toFloat()
        val x = spot.centerX() + pose.dx * reach
        val y = spot.centerY() + pose.dy * reach
        // A drag carries a plan once it has lifted: a block the spot's size, under the finger.
        val color = b.carry
        if (color != null && pose.down > 0f && pose.hold < 0f && beatMs >= Tour.actAt(b.gesture)) {
            block.set(spot)
            block.offset(x - spot.centerX(), y - spot.centerY())
            carried.color = color
            carried.alpha = (235 * shown).toInt()
            carried.setShadowLayer(ui.dp(10f).toFloat(), 0f, ui.dp(4f).toFloat(), 0x66000000)
            val r = ui.dp(6f).toFloat()
            canvas.drawRoundRect(block, r, r, carried)
            carriedText.alpha = (255 * shown).toInt()
            canvas.drawText("Plan", block.left + ui.dp(6f), block.top + ui.dp(15f), carriedText)
        }
        drawFinger(canvas, x, y, shown)
    }

    private fun drawFinger(canvas: Canvas, x: Float, y: Float, shown: Float) {
        val a = (255 * shown).toInt()
        val down = pose.down.coerceIn(0f, 1f)
        if (pose.ripple >= 0f) {
            wave.alpha = (a * (1 - pose.ripple)).toInt()
            canvas.drawCircle(x, y, ui.dp(20f) + ui.dp(28f) * pose.ripple, wave)
        }
        if (pose.hold >= 0f) {
            val r = ui.dp(32f).toFloat()
            arc.set(x - r, y - r, x + r, y + r)
            track.alpha = (89 * shown).toInt()
            canvas.drawOval(arc, track)
            fill.alpha = a
            canvas.drawArc(arc, -90f, 360f * pose.hold, false, fill)
        }
        // The touch itself: a soft dot under the fingertip while it's pressed.
        dot.alpha = (110 * down * shown).toInt()
        canvas.drawCircle(x, y, ui.dp(20f).toFloat(), dot)
        // Lifted, the finger is a little bigger and off to the side; pressed, its tip is on the spot.
        val lift = 1 - down
        canvas.save()
        canvas.translate(x + ui.dp(8f) * lift, y + ui.dp(14f) * lift)
        canvas.rotate(-22f)
        canvas.scale(1 + 0.08f * lift, 1 + 0.08f * lift)
        fingerBody.set(-fingerW / 2, -ui.dp(6f).toFloat(), fingerW / 2, fingerL)
        skin.alpha = a
        canvas.drawRoundRect(fingerBody, fingerW / 2, fingerW / 2, skin)
        edge.alpha = a
        canvas.drawRoundRect(fingerBody, fingerW / 2, fingerW / 2, edge)
        nailBox.set(-fingerW / 2 + ui.dp(7f), 0f, fingerW / 2 - ui.dp(7f), ui.dp(26f).toFloat())
        nail.alpha = a
        canvas.drawRoundRect(nailBox, ui.dp(9f).toFloat(), ui.dp(9f).toFloat(), nail)
        crease.alpha = (38 * shown).toInt()
        for (k in listOf(52f, 59f)) canvas.drawLine(-fingerW / 4, ui.dp(k).toFloat(), fingerW / 4, ui.dp(k).toFloat(), crease)
        canvas.restore()
    }
}
