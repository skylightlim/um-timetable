package com.skylightlim.umtimetable

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.ScrollView
import com.skylightlim.umtimetable.Motion.spring
import kotlin.math.abs
import kotlin.math.sign

/**
 * A vertical scroll view that also takes sideways swipes (for changing days), whether the swipe
 * starts on content or on empty space. The page follows the finger; let go past 60dp (or with a
 * flick) and it moves on to the next day, otherwise it springs back. Its children are clipped to its
 * bounds, so a sliding page can never widen the screen.
 */
class SwipeScrollView(context: Context) : ScrollView(context) {
    /** Called with +1 for a swipe to the left (next) and -1 for a swipe to the right (previous). */
    var onSwipe: ((Int) -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = context.resources.displayMetrics.density
    private val minDistance = 60 * density
    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    /** The drag is past the point where letting go moves on (vibrates on crossing it either way). */
    private var past = false
    private var velocity: VelocityTracker? = null

    private val page: View? get() = getChildAt(0)

    private fun startsSwipe(e: MotionEvent): Boolean {
        val dx = e.x - downX
        val dy = e.y - downY
        return abs(dx) > slop && abs(dx) > abs(dy) * 1.5f
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (onSwipe != null) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) velocity = (velocity ?: VelocityTracker.obtain()).apply { clear() }
            velocity?.addMovement(e)
        }
        return super.dispatchTouchEvent(e)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (onSwipe != null) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; swiping = false }
                MotionEvent.ACTION_MOVE -> if (!swiping && startsSwipe(e)) { swiping = true; return true }
            }
        }
        return super.onInterceptTouchEvent(e)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val swipe = onSwipe ?: return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; swiping = false; past = false }
            MotionEvent.ACTION_MOVE -> if (!swiping && startsSwipe(e)) swiping = true
        }
        if (!swiping) return super.onTouchEvent(e)
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
