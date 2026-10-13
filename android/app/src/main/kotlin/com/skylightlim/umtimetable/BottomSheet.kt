package com.skylightlim.umtimetable

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ScrollView
import android.window.OnBackInvokedDispatcher
import com.skylightlim.umtimetable.Motion.spring
import kotlin.math.abs
import kotlin.math.max

/**
 * A bottom sheet holding [content]: it springs up over a fading scrim and follows a downward drag
 * (from anywhere on it once its content is scrolled to the top). Dragged a quarter of its height or
 * flicked down, tapped outside, or Back, and it slides away, faster than it came.
 */
class BottomSheet(context: Context, private val content: View, darkSheet: Boolean) {
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

    init {
        scrim.setOnClickListener { close() }
        frame.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        // Drawn edge to edge: the sheet's colour runs under the navigation bar, and it stops below the status bar.
        frame.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
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
            // Light status icons over the scrim; navigation icons to suit the sheet under them.
            insetsController?.setSystemBarsAppearance(
                if (darkSheet) 0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
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
        private var velocity: VelocityTracker? = null

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            presser.onTouch(e)
            if (e.actionMasked == MotionEvent.ACTION_DOWN) velocity = (velocity ?: VelocityTracker.obtain()).apply { clear() }
            velocity?.addMovement(e)
            return super.dispatchTouchEvent(e)
        }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = false }
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
