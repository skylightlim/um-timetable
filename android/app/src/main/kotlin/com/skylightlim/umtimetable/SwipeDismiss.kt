package com.skylightlim.umtimetable

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import com.skylightlim.umtimetable.Motion.spring
import kotlin.math.abs

/**
 * A row that a swipe to the left dismisses (Due's deadlines). The row follows the finger, and what's under
 * it, "Dismiss" with its cross, comes up on the right as it uncovers. Past the line (a third of the row, at
 * least [MIN_DP]) it vibrates as a swipe between days does; let go there, or flick it left, and it slides
 * off and [onDismiss] runs. Short of it, it springs back at the finger's speed. A tap and a long press on
 * the row work as before: the swipe takes the touch only once it's clearly going left.
 */
object SwipeDismiss {
    /** The line is a third of the row's width, and never shorter than this. */
    const val MIN_DP = 96f
    /** A flick left this fast (dp a second) dismisses however short it was. */
    const val FLICK_DP = 900f

    /** How far a swipe must go to dismiss (px) on a row [width] px wide. */
    fun line(width: Int, density: Float): Float = maxOf(width / 3f, MIN_DP * density)

    /** Whether a swipe let go [dx] px across (− to the left) at [vx] px a second dismisses. */
    fun goes(dx: Float, vx: Float, width: Int, density: Float): Boolean =
        dx < 0 && (-dx >= line(width, density) || vx < -FLICK_DP * density)

    /**
     * [row] in a holder with "Dismiss" under it, swiped left to run [onDismiss] (after it has slid off). The
     * holder goes in the page where the row would.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun wrap(ui: Ui, row: View, onDismiss: () -> Unit): FrameLayout {
        val density = ui.context.resources.displayMetrics.density
        val slop = ViewConfiguration.get(ui.context).scaledTouchSlop
        val hint = ui.row(gap = 6f).apply {
            alpha = 0f
            setPadding(0, 0, ui.dp(12), 0)
            addView(ui.icon(R.drawable.ic_close, ui.p.accentText, 18f))
            addView(ui.label("Dismiss", ui.p.muted, 10.5f))
        }
        val holder = FrameLayout(ui.context).apply {
            addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
            addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        var downX = 0f
        var downY = 0f
        var swiping = false
        var past = false
        var gone = false
        var tracker: VelocityTracker? = null

        fun show(tx: Float) {
            row.translationX = tx
            val f = (-tx / line(row.width, density)).coerceIn(0f, 1f)
            row.alpha = 1f - 0.5f * f
            hint.alpha = f
        }
        fun springBack(vx: Float) {
            val from = row.translationX
            if (!Motion.enabled || from == 0f) return show(0f)
            val s = Motion.SETTLE.toward(from, 0f, vx)
            row.animate().translationX(0f).alpha(1f).setInterpolator(s).setDuration(s.duration)
                .setUpdateListener { hint.alpha = (-row.translationX / line(row.width, density)).coerceIn(0f, 1f) }
                .withEndAction { hint.alpha = 0f }.start()
        }
        fun slideOff(vx: Float) {
            gone = true
            if (!Motion.enabled) { show(-row.width.toFloat()); onDismiss(); return }
            val to = -row.width.toFloat()
            val s = Motion.QUICK.toward(row.translationX, to, vx)
            row.animate().translationX(to).alpha(0f).setInterpolator(s).setDuration(minOf(s.duration, 260L))
                .setUpdateListener(null)
                .withEndAction { onDismiss() }.start()
        }

        row.setOnTouchListener { v, e ->
            if (gone) return@setOnTouchListener true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    swiping = false
                    past = false
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().apply { addMovement(e) }
                    // Caught springing back: it's put home, and the finger starts from there.
                    if (row.translationX != 0f) { v.animate().cancel(); show(0f) }
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(e)
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!swiping && dx < -slop && abs(dx) > abs(dy) * 1.5f) {
                        swiping = true
                        // From here the swipe has the touch: the page doesn't scroll, and the row's own press and
                        // long press are called off.
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }.also { v.onTouchEvent(it) }.recycle()
                    }
                    if (swiping) {
                        // Left only; pulled back past where it started, it stops there.
                        show(minOf(0f, dx + slop))
                        val over = -row.translationX >= line(row.width, density)
                        if (over != past) {
                            past = over
                            Haptics.threshold(v, past)
                        }
                    }
                    swiping
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracker?.addMovement(e)
                    if (!swiping) return@setOnTouchListener false
                    swiping = false
                    val vx = tracker?.run { computeCurrentVelocity(1000); xVelocity } ?: 0f
                    tracker?.recycle()
                    tracker = null
                    val dx = row.translationX
                    if (e.actionMasked == MotionEvent.ACTION_UP && goes(dx, vx, row.width, density)) {
                        if (!past) Haptics.threshold(v, true) // a short flick: the bump it skipped
                        slideOff(vx)
                    } else springBack(vx)
                    true
                }
                else -> swiping
            }
        }
        return holder
    }
}
