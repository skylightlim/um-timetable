package com.skylightlim.umtimetable

import android.animation.Animator
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
     * A spring as an interpolator, from 0 to rest at 1. [damping] below 1 overshoots (0.55 a visible
     * bounce, 0.8 a hint); 1 doesn't. [response] is the undamped period in seconds: how quick it feels.
     * [velocity] is how fast it's already moving at the start, in distances a second (0: from rest), so a
     * move let go by the finger carries on at the finger's speed, as on iOS ([toward]). [duration] is
     * when it has settled to within half a percent.
     */
    class Spring(private val damping: Float, private val response: Float, private val velocity: Float = 0f) : TimeInterpolator {
        private val w0 = 2 * PI.toFloat() / response
        private val wd = w0 * sqrt(max(0f, 1 - damping * damping))
        // Away from rest y = x - 1, starting at -1 with speed [velocity]: e^(-ζωt)(A cos ωd t + B sin ωd t), or
        // (A + Bt) e^(-ωt) when critically damped.
        private val a = -1f
        private val b = if (damping < 1f) (velocity + damping * w0 * a) / wd else velocity + w0 * a
        // Until it's within half a percent for good: the envelope of y drops below 0.005. (Critically damped,
        // the old ln(200)/ω stopped about 3% short, and the last frame jumped the rest of the way.)
        val duration = if (damping < 1f) (ln(200f * sqrt(a * a + b * b)) / (damping * w0) * 1000).toLong()
        else {
            var t = 0f
            while (t < 3f && (abs(a) + abs(b) * t) * exp(-w0 * t) >= 0.005f) t += 0.001f
            (t * 1000).toLong()
        }

        override fun getInterpolation(input: Float): Float {
            if (input >= 1f) return 1f
            val t = input * duration / 1000f
            return if (damping < 1f) 1 + exp(-damping * w0 * t) * (a * cos(wd * t) + b * sin(wd * t))
            else 1 + (a + b * t) * exp(-w0 * t)
        }

        /** This spring for a move from [from] to [to] (px) let go at [pxPerSecond], already heading there or away. */
        fun toward(from: Float, to: Float, pxPerSecond: Float): Spring =
            if (to == from || pxPerSecond == 0f) this else Spring(damping, response, (pxPerSecond / (to - from)).coerceIn(-8f, 20f))
    }

    // Tuned like iOS's springs: enough damping that nothing wobbles back after overshooting (the old
    // 0.55 overshot 13% and swung back; 0.8 crept past and snapped back at the end, which read as lag).
    /** Selections gliding, presses let go, badges: a little life, about 2% past and settled. */
    val BOUNCY = Spring(0.78f, 0.38f)
    /** Pages and the sheet arriving: no visible overshoot, quick to settle. */
    val SETTLE = Spring(0.92f, 0.42f)
    /** Growing and shrinking in place, the theme spreading: no overshoot. */
    val SMOOTH = Spring(1f, 0.5f)
    /** Something opening or closing in place (a fold, Show more): quick, about 250 ms, no overshoot. */
    val QUICK = Spring(1f, 0.3f)
    /** A fold's chevron turning: quick, a hint of overshoot. */
    val FOLD = Spring(0.85f, 0.3f)
    /** Leaving is quicker than arriving, and doesn't bounce. */
    val EXIT = PathInterpolator(0.4f, 0f, 1f, 1f)
    private val PRESS = PathInterpolator(0.2f, 0f, 0f, 1f)
    /** The tint animation running on a view, so a new one cancels it: two over one view could end in the wrong colour. */
    private val tints = java.util.WeakHashMap<View, ValueAnimator>()

    fun ViewPropertyAnimator.spring(s: Spring): ViewPropertyAnimator = setInterpolator(s).setDuration(s.duration)

    /** False with "Remove animations": then views are just put where they end. */
    val enabled get() = ValueAnimator.areAnimatorsEnabled()

    /** Opacity eases on its own (a spring would carry it past fully opaque). Returns the running animator, so a finger can cancel it. */
    fun fade(v: View, from: Float, to: Float, ms: Long = 180): Animator {
        val a = ObjectAnimator.ofFloat(v, View.ALPHA, from, to).setDuration(ms).apply { interpolator = PRESS }
        a.start()
        return a
    }

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
        /** The touch landed on something that takes it (a card, a scrolling panel): nothing under it is pressed. */
        private var taken = false

        fun onTouch(e: MotionEvent) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    taken = false
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

        private fun hit(v: View, x: Int, y: Int): Boolean {
            if (v.visibility != View.VISIBLE) return false
            val r = Rect()
            return v.getGlobalVisibleRect(r) && r.contains(x, y)
        }

        private fun clickableAt(v: View, x: Int, y: Int): View? {
            if (!hit(v, x, y)) return null
            if (v is ViewGroup) for (i in v.childCount - 1 downTo 0) {
                val child = v.getChildAt(i)
                clickableAt(child, x, y)?.let { return it }
                // A scrolling panel, or anything else that takes touches (a dialog's card), takes every touch
                // on it, so nothing under it is pressed. Without this, pressing a sheet's or a card's empty
                // space sank the scrim behind it, uncovering light strips at the edges. Found at any depth:
                // the plan card sits in a full-screen holder (lifted over the keyboard), and stopping only
                // that level let the search go on past the holder to the scrim.
                if (taken) return null
                if ((child is ScrollView || child.isClickable) && hit(child, x, y)) { taken = true; return null }
            }
            // A switch's options don't sink: its gliding pill is their feedback, and a sunk label left the pill behind.
            // Nor does a backdrop (most of the screen: a scrim tapped to close): sunk, it too uncovered light strips.
            return v.takeIf {
                it.isClickable && it.isEnabled && it.hasOnClickListeners() && it.parent !is SegmentPill &&
                    it.width.toLong() * it.height < root.width.toLong() * root.height * 0.8f
            }
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
        /** Put away ([hide]): the next [moveTo] puts it straight there and fades it in, rather than gliding from where it was. */
        private var hidden = false
        private var fading: Animator? = null

        init {
            frame.addView(pill, index, FrameLayout.LayoutParams(0, 0))
        }

        /** Nothing is chosen (Week's strip, its days only): the pill fades away. */
        fun hide() {
            // After any move still waiting for the row's layout, as a move is.
            row.post {
                if (hidden) return@post
                hidden = true
                fading?.cancel()
                if (!placed || !enabled) { pill.alpha = 0f; return@post }
                fading = fade(pill, pill.alpha, 0f, 120)
            }
        }

        /** Glides to the row's item at [index], turning [color]; the first time (or after [hide]) it's just put there. */
        fun moveTo(index: Int, color: Int) {
            row.post {
                val target = row.getChildAt(index) ?: return@post
                if (target.width == 0) return@post
                if (hidden) {
                    hidden = false
                    placed = false
                    fading?.cancel()
                    pill.animate().cancel()
                    pill.alpha = 1f
                    if (enabled) fading = fade(pill, 0f, 1f, 200)
                }
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

    /**
     * [views] laid out anew in other places (the strip's days between Day and Week): after the coming layout each
     * starts where it was ([from]: its old left and width in the same parent) and glides to its new place. Those in
     * [stretch] (plain shapes; text would stretch with them) also stretch from their old width.
     */
    fun reflow(views: List<View>, from: List<Pair<Int, Int>>, stretch: Set<View> = emptySet()) {
        val first = views.firstOrNull() ?: return
        if (!enabled) return
        val observer = first.viewTreeObserver
        // Before the first frame of the new layout is drawn, or it would show there for a frame and then jump back.
        observer.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (observer.isAlive) observer.removeOnPreDrawListener(this) else first.viewTreeObserver.removeOnPreDrawListener(this)
                for ((v, old) in views.zip(from)) {
                    val (left, width) = old
                    if (v.width == 0 || width == 0) continue
                    v.animate().cancel()
                    if (v in stretch) {
                        v.pivotX = 0f
                        v.translationX = (left - v.left).toFloat()
                        v.scaleX = width / v.width.toFloat()
                        v.animate().translationX(0f).scaleX(1f).spring(SETTLE).start()
                    } else {
                        v.translationX = (left + width / 2f) - (v.left + v.width / 2f)
                        v.animate().translationX(0f).spring(SETTLE).start()
                    }
                }
                return true
            }
        })
    }

    /** An icon's tint changing with the pill gliding under it. */
    fun tint(icon: ImageView, from: Int, to: Int, ms: Long = 220) {
        tints.remove(icon)?.cancel()
        icon.imageTintList = android.content.res.ColorStateList.valueOf(if (!enabled) to else from)
        if (!enabled || from == to) return
        val a = ValueAnimator.ofArgb(from, to).setDuration(ms).apply { addUpdateListener { icon.imageTintList = android.content.res.ColorStateList.valueOf(it.animatedValue as Int) } }
        a.start()
        tints[icon] = a
    }

    /**
     * A text's colour changing with the pill gliding under it. The label the pill leaves should change
     * faster ([ms] 90): its light colour on the bare slot is barely readable.
     */
    fun tint(text: android.widget.TextView, from: Int, to: Int, ms: Long = 220) {
        tints.remove(text)?.cancel()
        if (!enabled || from == to) return text.setTextColor(to)
        text.setTextColor(from)
        val a = ValueAnimator.ofArgb(from, to).setDuration(ms).apply { addUpdateListener { text.setTextColor(it.animatedValue as Int) } }
        a.start()
        tints[text] = a
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

    /** A page sliding in sideways from [dx] (another day), already moving at [pxPerSecond] (a swipe let go). */
    fun slideIn(page: View, dx: Float, pxPerSecond: Float = 0f) {
        if (!enabled) return
        page.translationX = dx
        page.animate().translationX(0f).spring(SETTLE.toward(dx, 0f, pxPerSecond)).start()
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

    /** Where [v] is on screen, as a rectangle (a card flies out of it and back into it). */
    fun onScreen(v: View): android.graphics.RectF {
        val at = IntArray(2).also(v::getLocationOnScreen)
        return android.graphics.RectF(at[0].toFloat(), at[1].toFloat(), (at[0] + v.width).toFloat(), (at[1] + v.height).toFloat())
    }

    /** [card]'s centre on screen where it rests: from its layout in its parent, so its own moves and scaling don't count. */
    private fun restingCentre(card: View): Pair<Float, Float> {
        val at = IntArray(2).also { (card.parent as View).getLocationOnScreen(it) }
        return (at[0] + card.left + card.width / 2f) to (at[1] + card.top + card.height / 2f)
    }

    /**
     * A card flying out of [from] (where it came from, on screen) into its place, as the plan card does out of
     * the week grid: it shows at [from]'s size there, pops (swells, lifts and tilts up off the page), then flies
     * to its place on a spring, turning to face the user, [content] fading in as it goes; [landing] runs as it
     * sets off. Out of something big (a whole row) it swells less, so it never pops past the screen.
     */
    fun flyOut(card: View, from: android.graphics.RectF, content: View? = null, landing: () -> Unit = {}) {
        if (!enabled) return
        val d = card.resources.displayMetrics.density
        // Without a [content], what's on the card (its children) is what fades in.
        val parts = content?.let { listOf(it) } ?: (card as? ViewGroup)?.let { g -> (0 until g.childCount).map(g::getChildAt) }.orEmpty()
        parts.forEach { it.alpha = 0f }
        card.alpha = 0f
        card.post {
            val (cx, cy) = restingCentre(card)
            val sx = from.width() / card.width.coerceAtLeast(1)
            val sy = from.height() / card.height.coerceAtLeast(1)
            val swell = minOf(1.3f, 1.08f / maxOf(sx, sy, 0.01f))
            card.pivotX = card.width / 2f
            card.pivotY = card.height / 2f
            card.cameraDistance = 9000 * d
            card.translationX = from.centerX() - cx
            card.translationY = from.centerY() - cy
            card.scaleX = sx
            card.scaleY = sy
            card.alpha = 1f
            card.animate().scaleX(sx * swell).scaleY(sy * swell).rotationX(24f).rotation(-7f).translationZ(10 * d)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f)).setDuration(130).withEndAction {
                    card.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).rotationX(0f).rotation(0f)
                        .translationZ(24 * d).spring(SETTLE).start()
                    parts.forEach { fade(it, 0f, 1f, 220) }
                    landing()
                }.start()
        }
    }

    /** [flyOut] backwards: [card] shrinks back down into [to], tilting as it goes, [content] (or its children) fading first; then [end]. */
    fun flyBack(card: View, to: android.graphics.RectF, content: View? = null, end: () -> Unit) {
        if (!enabled) return end()
        val parts = content?.let { listOf(it) } ?: (card as? ViewGroup)?.let { g -> (0 until g.childCount).map(g::getChildAt) }.orEmpty()
        parts.forEach { it.animate().alpha(0f).setDuration(90).start() }
        val (cx, cy) = restingCentre(card)
        card.animate().translationX(to.centerX() - cx).translationY(to.centerY() - cy)
            .scaleX(to.width() / card.width.coerceAtLeast(1)).scaleY(to.height() / card.height.coerceAtLeast(1))
            .rotationX(18f).rotation(-3f).translationZ(0f)
            .setInterpolator(EXIT).setDuration(260).withEndAction(end).start()
    }

    /** A pop for something that just appeared or changed (a badge's count). */
    fun pop(v: View, from: Float = 0.4f) {
        if (!enabled) return
        v.scaleX = from
        v.scaleY = from
        v.animate().scaleX(1f).scaleY(1f).spring(BOUNCY).start()
    }

    /**
     * A little jump on the spot after [delay] ms: it crouches, leaps [height] px, lands with a squash, then
     * a smaller hop, about 0.7 s in all. Its parent must not clip it (clipChildren false up to where
     * there's room). Squashes from its bottom edge, so it looks to land on something.
     */
    fun hop(v: View, delay: Long, height: Float) {
        if (!enabled) return
        v.post {
            hopAnimator(v, height).apply { startDelay = delay }.start()
        }
    }

    /** [hop]'s animation, not started (a still preview can set its fraction). Needs [v] laid out. */
    fun hopAnimator(v: View, height: Float): ObjectAnimator {
        // A keyframe's interpolator shapes the stretch that ends at it: rising slows, falling speeds up.
        val up = android.view.animation.DecelerateInterpolator(1.6f)
        val down = android.view.animation.AccelerateInterpolator(1.6f)
        fun key(t: Float, value: Float, ease: TimeInterpolator? = null) = android.animation.Keyframe.ofFloat(t, value).apply { ease?.let { interpolator = it } }
        fun keys(property: android.util.Property<View, Float>, vararg k: android.animation.Keyframe) = android.animation.PropertyValuesHolder.ofKeyframe(property, *k)
        val y = keys(View.TRANSLATION_Y, key(0f, 0f), key(0.1f, 0f), key(0.36f, -height, up), key(0.58f, 0f, down), key(0.7f, -height * 0.32f, up), key(0.82f, 0f, down), key(1f, 0f))
        // Crouch, stretch on the way up, squash on landing, and less on the second.
        val sy = keys(View.SCALE_Y, key(0f, 1f), key(0.1f, 0.78f), key(0.22f, 1.12f), key(0.36f, 1f), key(0.58f, 1f), key(0.63f, 0.8f), key(0.7f, 1.04f), key(0.82f, 1f), key(0.86f, 0.92f), key(1f, 1f))
        val sx = keys(View.SCALE_X, key(0f, 1f), key(0.1f, 1.16f), key(0.22f, 0.92f), key(0.36f, 1f), key(0.58f, 1f), key(0.63f, 1.15f), key(0.7f, 0.98f), key(0.82f, 1f), key(0.86f, 1.05f), key(1f, 1f))
        v.pivotX = v.width / 2f
        v.pivotY = v.height.toFloat()
        return ObjectAnimator.ofPropertyValuesHolder(v, y, sy, sx).apply {
            duration = 720
            interpolator = null
        }
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
