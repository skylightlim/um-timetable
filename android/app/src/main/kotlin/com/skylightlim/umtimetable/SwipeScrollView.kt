package com.skylightlim.umtimetable

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.ScrollView
import com.skylightlim.umtimetable.Motion.spring
import kotlin.math.abs
import kotlin.math.sign

/**
 * A vertical scroll view that also takes sideways swipes (for changing days), whether the swipe
 * starts on content or on empty space. The page follows the finger; let go past 60dp (or with a
 * flick) and it moves on to the next day, otherwise it springs back. Its children are clipped to its
 * bounds, so a sliding page can never widen the screen.
 *
 * Where there's something to sync ([onPull]), the page can also be pulled down from the top: past
 * 72dp and let go, it syncs, and the page waits lowered over [pullIndicator] until [endPull].
 */
class SwipeScrollView(context: Context) : ScrollView(context) {
    /** Called with +1 for a swipe to the left (next) and -1 for a swipe to the right (previous). */
    var onSwipe: ((Int) -> Unit)? = null
    /** Called when the page is pulled down past the threshold and let go. */
    var onPull: (() -> Unit)? = null
    /** What the pull uncovers above the page. */
    var pullIndicator: PullIndicator? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = context.resources.displayMetrics.density
    private val minDistance = 60 * density
    private val pullDistance = 72 * density
    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    private var pulling = false
    /** Let go past the threshold: the page stays lowered while the sync runs. */
    private var held = false
    /** The drag is past the point where letting go moves on (vibrates on crossing it either way). */
    private var past = false
    private var velocity: VelocityTracker? = null

    private val page: View? get() = getChildAt(0)

    private fun startsSwipe(e: MotionEvent): Boolean {
        if (onSwipe == null) return false
        val dx = e.x - downX
        val dy = e.y - downY
        return abs(dx) > slop && abs(dx) > abs(dy) * 1.5f
    }

    private fun startsPull(e: MotionEvent): Boolean {
        if (onPull == null || held || scrollY > 0) return false
        val dx = e.x - downX
        val dy = e.y - downY
        return dy > slop && dy > abs(dx) * 1.5f
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (onSwipe != null) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) velocity = (velocity ?: VelocityTracker.obtain()).apply { clear() }
            velocity?.addMovement(e)
        }
        return super.dispatchTouchEvent(e)
    }

    /** A new touch: the drag state starts afresh. */
    private fun down(e: MotionEvent) {
        downX = e.x
        downY = e.y
        swiping = false
        pulling = false
        past = false
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            // A touch on a card never reaches onTouchEvent's DOWN, so the drag state starts afresh
            // here too: left over from the last swipe, it swallowed a quick flick's vibration.
            MotionEvent.ACTION_DOWN -> down(e)
            MotionEvent.ACTION_MOVE -> if (!swiping && !pulling) {
                if (startsSwipe(e)) { swiping = true; return true }
                if (startsPull(e)) { pulling = true; return true }
            }
        }
        return super.onInterceptTouchEvent(e)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> down(e)
            MotionEvent.ACTION_MOVE -> if (!swiping && !pulling) {
                if (startsSwipe(e)) swiping = true else if (startsPull(e)) pulling = true
            }
        }
        if (pulling) return pull(e)
        val swipe = onSwipe
        if (!swiping || swipe == null) return super.onTouchEvent(e)
        val dx = e.x - downX
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> page?.apply {
                // Follows the finger from where the swipe was recognised, a touch slower.
                translationX = (dx - sign(dx) * slop) * 0.9f
                alpha = 1f - (abs(translationX) / width.coerceAtLeast(1)) * 0.5f
                if ((abs(dx) > minDistance) != past) {
                    past = !past
                    Haptics.threshold(this@SwipeScrollView, past)
                }
            }
            MotionEvent.ACTION_UP -> {
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                val flick = abs(vx) > 900 * density && sign(vx) == sign(dx)
                swiping = false
                if (abs(dx) > minDistance || flick) {
                    if (!past) Haptics.threshold(this, true) // a short flick: the bump it skipped
                    page?.apply { animate().cancel(); translationX = 0f; alpha = 1f }
                    swipe(if (dx < 0) 1 else -1)
                } else {
                    settle()
                }
            }
            MotionEvent.ACTION_CANCEL -> { swiping = false; settle() }
        }
        return true
    }

    /** The page follows a downward drag at half speed; past the threshold, letting go syncs. */
    private fun pull(e: MotionEvent): Boolean {
        val page = page ?: return true
        val d = ((e.y - downY - slop) * 0.5f).coerceIn(0f, pullDistance * 1.6f)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                page.animate().cancel()
                page.translationY = d
                pullIndicator?.pulled(d, d / pullDistance)
                if ((d >= pullDistance) != past) {
                    past = !past
                    Haptics.threshold(this, past)
                }
            }
            MotionEvent.ACTION_UP -> {
                pulling = false
                if (past) {
                    held = true
                    pullIndicator?.syncing()
                    lower(pullIndicator?.height?.toFloat() ?: pullDistance)
                    onPull?.invoke()
                } else {
                    lower(0f)
                }
            }
            MotionEvent.ACTION_CANCEL -> { pulling = false; lower(0f) }
        }
        return true
    }

    /** Moves the page to [y] below the top, the indicator following it. */
    private fun lower(y: Float) {
        val page = page ?: return
        page.animate().translationY(y).spring(Motion.SETTLE)
            .setUpdateListener { pullIndicator?.follow(page.translationY) }.start()
        if (!Motion.enabled) pullIndicator?.follow(y)
    }

    /** The sync has finished: the indicator says so for a moment, then the page goes back up. */
    fun endPull() {
        if (!held) return
        held = false
        pullIndicator?.done()
        postDelayed({ if (!held && !pulling) lower(0f) }, 600)
    }

    /** Puts the page straight back (another page is showing). */
    fun resetPull() {
        held = false
        pulling = false
        page?.apply { animate().cancel(); translationY = 0f }
        pullIndicator?.follow(0f)
    }

    /** Not far enough: back to where it was. */
    private fun settle() {
        val page = page ?: return
        page.animate().translationX(0f).spring(Motion.BOUNCY).start()
        Motion.fade(page, page.alpha, 1f)
    }

    override fun onDetachedFromWindow() {
        velocity?.recycle()
        velocity = null
        super.onDetachedFromWindow()
    }
}

/**
 * What pulling a page down uncovers: the cow and the horse ([DuelView]) drawing their swords as the
 * page comes down, crossing them when it's far enough to sync, fighting while the sync runs and
 * clashing once more when it's done; with what letting go will do and when Spectrum last synced.
 */
@SuppressLint("ViewConstructor")
class PullIndicator(private val ui: Ui, private val lastSync: () -> String) : LinearLayout(ui.context) {
    private val duel = DuelView(ui)
    private val title = ui.text("Pull to sync", 13f, ui.p.text, ui.f.bodySemi)
    private val sub = ui.text("", 12f, ui.p.muted)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, ui.dp(10), 0, ui.dp(12))
        addView(duel)
        addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(4) })
        addView(sub, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(2) })
        alpha = 0f
    }

    /** Sits just above the page's top edge, [y] below the top, showing as it comes into view. */
    fun follow(y: Float) {
        translationY = y - height
        alpha = if (height == 0) 0f else (y / height).coerceIn(0f, 1f)
    }

    /** Being pulled, [y] down, [fraction] of the way to syncing (1 and over: let go to sync). */
    fun pulled(y: Float, fraction: Float) {
        duel.stop()
        duel.progress = fraction
        title.text = if (fraction >= 1f) "Release to sync" else "Pull to sync"
        sub.text = lastSync()
        follow(y)
    }

    fun syncing() {
        title.text = "Syncing…"
        duel.loop()
    }

    fun done() {
        duel.finish()
        title.text = "Synced"
        sub.text = lastSync()
    }
}
