package com.skylightlim.umtimetable

import android.animation.ObjectAnimator
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The app's motion. Everything moves on springs (a damped oscillator; no library), so it settles like
 * a physical thing and a new move can start from wherever the last one is. Durations follow Android's
 * animator scale: with "Remove animations" on, every change lands at once and nothing is held back.
 */
object Motion {

    /**
     * A spring as an interpolator, from rest at 0 to rest at 1. [damping] below 1 overshoots (0.55 a
     * visible bounce, 0.8 a hint); 1 doesn't. [response] is the undamped period in seconds: how quick it
     * feels. [duration] is when it has settled to within half a percent.
     */
    class Spring(private val damping: Float, response: Float) : TimeInterpolator {
        private val w0 = 2 * PI.toFloat() / response
        private val wd = w0 * sqrt(max(0f, 1 - damping * damping))
        val duration = (ln(200f) / (damping.coerceAtMost(1f) * w0) * 1000).toLong()

        override fun getInterpolation(input: Float): Float {
            if (input >= 1f) return 1f
            val t = input * duration / 1000f
            val decay = exp(-damping * w0 * t)
            return if (damping < 1f) 1 - decay * (cos(wd * t) + damping * w0 / wd * sin(wd * t))
            else 1 - decay * (1 + w0 * t)
        }
    }

    /** Selections gliding, presses let go, badges: a visible bounce. */
    val BOUNCY = Spring(0.55f, 0.42f)
    /** Pages and the sheet arriving: a hint of overshoot. */
    val SETTLE = Spring(0.8f, 0.5f)
    /** Growing and shrinking in place, the theme spreading: no overshoot. */
    val SMOOTH = Spring(1f, 0.5f)
    /** Something opening or closing in place (a fold, Show more): quick, about 250 ms, no overshoot. */
    val QUICK = Spring(1f, 0.3f)
    /** A fold's chevron turning: quick, a hint of overshoot. */
    val FOLD = Spring(0.75f, 0.28f)
    /** Leaving is quicker than arriving, and doesn't bounce. */
    val EXIT = PathInterpolator(0.4f, 0f, 1f, 1f)
    private val PRESS = PathInterpolator(0.2f, 0f, 0f, 1f)

    fun ViewPropertyAnimator.spring(s: Spring): ViewPropertyAnimator = setInterpolator(s).setDuration(s.duration)

    /** False with "Remove animations": then views are just put where they end. */
    val enabled get() = ValueAnimator.areAnimatorsEnabled()

    /** Opacity eases on its own (a spring would carry it past fully opaque). */
    fun fade(v: View, from: Float, to: Float, ms: Long = 180) =
        ObjectAnimator.ofFloat(v, View.ALPHA, from, to).setDuration(ms).apply { interpolator = PRESS }.start()

    /**
     * Press feedback for everything tappable under a root: the view under the finger sinks a little
     * (small things more than big cards) and springs back when let go or when the finger starts to
     * scroll. Feed it every touch event from dispatchTouchEvent; it never consumes them.
     */
    class Presser(private val root: View) {
        private var pressed: View? = null
        private var downX = 0f
        private var downY = 0f
        private val slop = ViewConfiguration.get(root.context).scaledTouchSlop

        fun onTouch(e: MotionEvent) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    pressed = clickableAt(root, e.rawX.toInt(), e.rawY.toInt())?.also(::sink)
                }
                MotionEvent.ACTION_MOVE -> if (hypot(e.rawX - downX, e.rawY - downY) > slop) release()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> release()
            }
        }

        private fun sink(v: View) {
            val k = (v.resources.displayMetrics.density * 7 / max(v.width, v.height).coerceAtLeast(1)).coerceIn(0.015f, 0.08f)
            v.animate().scaleX(1 - k).scaleY(1 - k).setInterpolator(PRESS).setDuration(110).start()
        }

        private fun release() {
            pressed?.animate()?.scaleX(1f)?.scaleY(1f)?.spring(BOUNCY)?.start()
            pressed = null
        }

        private fun clickableAt(v: View, x: Int, y: Int): View? {
            if (v.visibility != View.VISIBLE) return null
            val r = Rect()
            if (!v.getGlobalVisibleRect(r) || !r.contains(x, y)) return null
            if (v is ViewGroup) for (i in v.childCount - 1 downTo 0) clickableAt(v.getChildAt(i), x, y)?.let { return it }
            return v.takeIf { it.isClickable && it.isEnabled && it.hasOnClickListeners() }
        }
    }

    /**
     * A frame as tall as its last child (a row of items), with the children behind it (a track of
     * slots, a [Glider]'s pill) fitted to that height. A plain FrameLayout would let a full-height
     * track stretch it to the whole screen.
     */
    class RowFrame(context: android.content.Context) : FrameLayout(context) {
        /**
         * A sideways swipe across the row, one item per swipe: +1 for a swipe to the left (the next
         * item), -1 to the right. A tap still reaches the item under it.
         */
        var onSwipe: ((Int) -> Unit)? = null
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val density = context.resources.displayMetrics.density
        private var downX = 0f
        private var downY = 0f
        private var swiping = false
        private var velocity: android.view.VelocityTracker? = null

        private fun startsSwipe(e: MotionEvent) = (e.x - downX).let { dx -> abs(dx) > slop && abs(dx) > abs(e.y - downY) * 1.5f }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            if (onSwipe == null) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    swiping = false
                    velocity = (velocity ?: android.view.VelocityTracker.obtain()).apply { clear(); addMovement(e) }
                }
                MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(e)
                    if (!swiping && startsSwipe(e)) { swiping = true; return true }
                }
            }
            return false
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val swipe = onSwipe ?: return super.onTouchEvent(e)
            velocity?.addMovement(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    swiping = false
                    velocity = (velocity ?: android.view.VelocityTracker.obtain()).apply { clear(); addMovement(e) }
                }
                MotionEvent.ACTION_MOVE -> if (!swiping && startsSwipe(e)) swiping = true
                MotionEvent.ACTION_UP -> {
                    val dx = e.x - downX
                    velocity?.computeCurrentVelocity(1000)
                    val flick = abs(velocity?.xVelocity ?: 0f) > 700 * density
                    if (swiping && (abs(dx) > 40 * density || flick)) swipe(if (dx < 0) 1 else -1)
                    swiping = false
                }
                MotionEvent.ACTION_CANCEL -> swiping = false
            }
            return true
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val row = getChildAt(childCount - 1) ?: return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            measureChild(row, widthMeasureSpec, heightMeasureSpec)
            val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec)
            else row.measuredWidth + paddingLeft + paddingRight
            val height = row.measuredHeight + paddingTop + paddingBottom
            val exactWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
            val exactHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            for (i in 0 until childCount - 1) measureChild(getChildAt(i), exactWidth, exactHeight)
            setMeasuredDimension(width, height)
        }
    }

    /**
     * A selection pill that glides between the items of [row] on a spring. It sits in [frame] behind
     * the row, so the row's items must let it show through (no fill of their own).
     */
    class Glider(private val frame: FrameLayout, private val row: ViewGroup, radius: Float, index: Int) {
        private val shape = GradientDrawable().apply { cornerRadius = radius }
        private val pill = View(frame.context).apply { background = shape }
        private var color: Int? = null
        private var placed = false

        init {
            frame.addView(pill, index, FrameLayout.LayoutParams(0, 0))
        }

        /** Glides to the row's item at [index], turning [color]; the first time it's just put there. */
        fun moveTo(index: Int, color: Int) {
            row.post {
                val target = row.getChildAt(index) ?: return@post
                if (target.width == 0) return@post
                // One width for the pill, scaled from its left edge to each item's (the Week chip is wider):
                // changing its layout width mid-glide would jump.
                val lp = pill.layoutParams
                if (lp.width == 0 || lp.height != target.height) {
                    lp.width = target.width
                    lp.height = target.height
                    pill.layoutParams = lp
                    pill.pivotX = 0f
                }
                val scale = target.width / lp.width.toFloat()
                val x = (row.left + target.left).toFloat()
                val y = (row.top + target.top).toFloat()
                val from = this.color
                this.color = color
                if (!placed || !enabled) {
                    placed = true
                    pill.translationX = x
                    pill.translationY = y
                    pill.scaleX = scale
                    shape.setColor(color)
                    return@post
                }
                pill.animate().translationX(x).translationY(y).scaleX(scale).spring(BOUNCY).start()
                if (from != null && from != color) {
                    ValueAnimator.ofArgb(from, color).setDuration(220).apply { addUpdateListener { shape.setColor(it.animatedValue as Int) } }.start()
                }
            }
        }
    }

    /** An icon's tint changing with the pill gliding under it. */
    fun tint(icon: ImageView, from: Int, to: Int, ms: Long = 220) {
        icon.imageTintList = android.content.res.ColorStateList.valueOf(if (!enabled) to else from)
        if (!enabled || from == to) return
        ValueAnimator.ofArgb(from, to).setDuration(ms).apply { addUpdateListener { icon.imageTintList = android.content.res.ColorStateList.valueOf(it.animatedValue as Int) } }.start()
    }

    /**
     * A text's colour changing with the pill gliding under it. The label the pill leaves should change
     * faster ([ms] 90): its light colour on the bare slot is barely readable.
     */
    fun tint(text: android.widget.TextView, from: Int, to: Int, ms: Long = 220) {
        if (!enabled || from == to) return text.setTextColor(to)
        text.setTextColor(from)
        ValueAnimator.ofArgb(from, to).setDuration(ms).apply { addUpdateListener { text.setTextColor(it.animatedValue as Int) } }.start()
    }

    /**
     * A page arriving: each of its parts rises into place a moment after the one before (28 ms
     * apart, none waiting more than 240 ms, so a long list's tail rises together), starting already
     * half visible so the page reads at once.
     */
    fun enter(page: View, rise: Float) {
        if (!enabled) return
        val parts = (page as? ViewGroup)?.let { g -> (0 until g.childCount).map(g::getChildAt) }.orEmpty()
        for ((i, v) in parts.withIndex()) {
            val delay = minOf(i * 28L, 240L)
            v.translationY = rise
            v.animate().translationY(0f).setStartDelay(delay).spring(SETTLE).start()
            v.alpha = 0.35f
            ObjectAnimator.ofFloat(v, View.ALPHA, 0.35f, 1f).setDuration(200).apply { interpolator = PRESS; startDelay = delay }.start()
        }
    }

    /** A page sliding in sideways from [dx] (another day). */
    fun slideIn(page: View, dx: Float) {
        if (!enabled) return
        page.translationX = dx
        page.animate().translationX(0f).spring(SETTLE).start()
        fade(page, 0.4f, 1f, 200)
    }

    /**
     * A title changing: the [old] one slips away while the [new] one's parts (words, a chip) follow
     * each other in, 45 ms apart, from [dx] sideways, or rising by [rise] when [dx] is 0. The old views
     * are removed from their parent when they're gone.
     */
    fun retitle(old: List<View>, new: ViewGroup, dx: Float, rise: Float) {
        for (v in old) v.animate().translationX(-dx * 0.6f).translationY(if (dx == 0f) -rise * 0.6f else 0f).alpha(0f)
            .setStartDelay(0).setDuration(140).setInterpolator(EXIT).withEndAction { (v.parent as? ViewGroup)?.removeView(v) }.start()
        for (i in 0 until new.childCount) {
            val v = new.getChildAt(i)
            val delay = 40L + i * 45L
            v.translationX = dx
            v.translationY = if (dx == 0f) rise else 0f
            v.alpha = 0f
            v.animate().translationX(0f).translationY(0f).setStartDelay(delay).spring(SETTLE).start()
            ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f).setDuration(200).apply { interpolator = PRESS; startDelay = delay }.start()
        }
    }

    /**
     * Something about to grow or shrink in place (Show more, a folder, a fold): what changes size
     * animates there, and everything below it moves along. Call just before changing it.
     */
    fun resize(from: View) {
        if (!enabled) return
        // From the view itself up (casting a TextView like Show more to ViewParent crashed the app).
        val root = generateSequence(from) { it.parent as? View }.firstOrNull { it.parent is ScrollView } as? ViewGroup ?: return
        TransitionManager.beginDelayedTransition(
            root,
            TransitionSet().apply {
                ordering = TransitionSet.ORDERING_TOGETHER
                addTransition(ChangeBounds())
                addTransition(Fade())
                duration = QUICK.duration
                interpolator = QUICK
            },
        )
    }

    /** A pop for something that just appeared or changed (a badge's count). */
    fun pop(v: View, from: Float = 0.4f) {
        if (!enabled) return
        v.scaleX = from
        v.scaleY = from
        v.animate().scaleX(1f).scaleY(1f).spring(BOUNCY).start()
    }

    /** The drawn chevron of a fold: pointing down when shut, up when open. */
    fun chevron(ui: Ui, open: Boolean, color: Int = ui.p.muted) = ImageView(ui.context).apply {
        setImageResource(R.drawable.ic_chevron)
        imageTintList = android.content.res.ColorStateList.valueOf(color)
        rotation = if (open) -90f else 90f
    }

    fun turn(chevron: View, open: Boolean) {
        val to = if (open) -90f else 90f
        if (!enabled) chevron.rotation = to else chevron.animate().rotation(to).spring(FOLD).start()
    }
}
