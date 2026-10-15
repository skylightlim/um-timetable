package com.skylightlim.umtimetable

import android.annotation.SuppressLint
import android.app.Dialog
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.window.OnBackInvokedDispatcher
import com.skylightlim.umtimetable.Motion.spring
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * A bottom sheet holding [content]: it springs up over a fading scrim and follows a downward drag
 * (from anywhere on it once its content is scrolled to the top). Dragged a quarter of its height or
 * flicked down, tapped outside, or Back, and it slides away, faster than it came.
 */
class BottomSheet(context: Context, private var content: View, darkSheet: Boolean) {
    // Full screen and see-through (not a floating dialog), so the scrim covers the status bar too.
    private val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
    private val scrim = View(context).apply { setBackgroundColor(0x8C000000.toInt()) }
    private val body = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(content)
    }
    private val frame = Frame(context)
    private var closing = false
    private val contentBottom = content.paddingBottom
    /** The navigation bar's height, which the content's bottom padding adds to. */
    private var barsBottom = 0

    init {
        scrim.setOnClickListener { close() }
        frame.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        // Drawn edge to edge: the sheet's colour runs under the navigation bar, and it stops below the status bar.
        frame.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            barsBottom = bars.bottom
            content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, contentBottom + bars.bottom)
            (body.layoutParams as FrameLayout.LayoutParams).topMargin = bars.top
            WindowInsets.CONSUMED
        }
        dialog.setContentView(frame)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(0)
            setDecorFitsSystemWindows(false)
        }
        barIcons(darkSheet)
    }

    /** Light status icons over the scrim; navigation icons to suit the sheet under them. */
    private fun barIcons(darkSheet: Boolean) = dialog.window?.insetsController?.setSystemBarsAppearance(
        if (darkSheet) 0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
    )

    /** Open, and not on its way out. */
    val showing get() = dialog.isShowing && !closing

    /** Where [v], a view on the sheet, has its centre, in the sheet's own coordinates. */
    fun centreOf(v: View): Point {
        val at = IntArray(2).also(v::getLocationInWindow)
        val origin = IntArray(2).also(body::getLocationInWindow)
        return Point(at[0] - origin[0] + v.width / 2, at[1] - origin[1] + v.height / 2)
    }

    /**
     * Puts [next] in place of the content, at the same scroll, with navigation icons to suit [darkSheet].
     * From [at] (a point on the sheet, see [centreOf]) it spreads in a circle over a picture of the old
     * content, as a new theme spreads over the page; without [at] it's just swapped.
     */
    fun replace(next: View, at: Point?, darkSheet: Boolean) {
        if (closing) return
        val picture = if (at != null && Motion.enabled && body.width > 0 && body.height > 0)
            // Drawn at its scroll: a view drawn by hand doesn't apply its own scroll offset.
            Bitmap.createBitmap(body.width, body.height, Bitmap.Config.ARGB_8888).also { b ->
                Canvas(b).apply { translate(-body.scrollX.toFloat(), -body.scrollY.toFloat()) }.let(body::draw)
            } else null
        next.setPadding(next.paddingLeft, next.paddingTop, next.paddingRight, contentBottom + barsBottom)
        // The scroll view keeps its scrollY across the swap; it's only clamped to the new content's height.
        body.removeAllViews()
        body.addView(next)
        content = next
        barIcons(darkSheet)
        if (picture == null || at == null) return
        val cover = ImageView(content.context).apply { setImageBitmap(picture); translationY = body.translationY }
        frame.addView(cover, frame.indexOfChild(body), FrameLayout.LayoutParams(body.width, body.height, Gravity.BOTTOM))
        val radius = hypot(max(at.x, body.width - at.x).toFloat(), max(at.y, body.height - at.y).toFloat())
        ViewAnimationUtils.createCircularReveal(body, at.x, at.y, 0f, radius).apply {
            interpolator = Motion.SMOOTH
            duration = Motion.SMOOTH.duration + 150
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    frame.removeView(cover)
                    picture.recycle()
                }
            })
        }.start()
    }

    /** Gone at once, without sliding away: the screen under it is being replaced (Sign out). */
    fun dismiss() {
        closing = true
        dialog.dismiss()
    }

    fun show() {
        if (Motion.enabled) {
            body.translationY = content.resources.displayMetrics.heightPixels.toFloat()
            scrim.alpha = 0f
            body.post {
                body.translationY = body.height.toFloat()
                body.animate().translationY(0f).spring(Motion.SETTLE).start()
                Motion.fade(scrim, 0f, 1f, 220)
            }
        }
        dialog.show()
        if (Build.VERSION.SDK_INT >= 33) {
            dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { close() }
        } else {
            dialog.setOnKeyListener { _, code, e ->
                if (code == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) close()
                code == KeyEvent.KEYCODE_BACK
            }
        }
    }

    /** Slides the sheet away; [then] runs at once, so what it opens arrives behind the leaving sheet. */
    fun close(then: (() -> Unit)? = null) {
        if (closing) return
        closing = true
        then?.invoke()
        if (!Motion.enabled) return dialog.dismiss()
        body.animate().translationY(body.height.toFloat()).setInterpolator(Motion.EXIT).setDuration(200).withEndAction { dialog.dismiss() }.start()
        Motion.fade(scrim, scrim.alpha, 0f, 200)
    }

    /** Press feedback for the sheet's buttons, and dragging it down. */
    private inner class Frame(context: Context) : FrameLayout(context) {
        private val presser = Motion.Presser(this)
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val density = context.resources.displayMetrics.density
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        /** Dragged past the point where letting go closes it (vibrates on crossing it either way). */
        private var past = false
        private var velocity: VelocityTracker? = null

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            presser.onTouch(e)
            if (e.actionMasked == MotionEvent.ACTION_DOWN) velocity = (velocity ?: VelocityTracker.obtain()).apply { clear() }
            velocity?.addMovement(e)
            return super.dispatchTouchEvent(e)
        }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = false; past = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (!closing && dy > slop && dy > abs(e.rawX - downX) && !body.canScrollVertically(-1) && onBody(e)) {
                        dragging = true
                        return true
                    }
                }
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!dragging) return super.onTouchEvent(e)
            val dy = max(0f, e.rawY - downY - slop)
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    body.translationY = dy
                    scrim.alpha = 1f - (dy / body.height.coerceAtLeast(1)).coerceAtMost(1f) * 0.8f
                    if ((dy > body.height / 4f) != past) {
                        past = !past
                        Haptics.threshold(this, past)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    velocity?.computeCurrentVelocity(1000)
                    val vy = velocity?.yVelocity ?: 0f
                    if (e.actionMasked == MotionEvent.ACTION_UP && (dy > body.height / 4f || vy > 1000 * density)) close()
                    else {
                        body.animate().translationY(0f).spring(Motion.SETTLE).start()
                        Motion.fade(scrim, scrim.alpha, 1f)
                    }
                }
            }
            return true
        }

        private fun onBody(e: MotionEvent): Boolean {
            val at = IntArray(2).also(body::getLocationOnScreen)
            return e.rawY >= at[1] + body.translationY
        }

        override fun onDetachedFromWindow() {
            velocity?.recycle()
            velocity = null
            super.onDetachedFromWindow()
        }
    }
}
