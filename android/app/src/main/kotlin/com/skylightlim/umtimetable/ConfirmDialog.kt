package com.skylightlim.umtimetable

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.window.OnBackInvokedDispatcher
import com.skylightlim.umtimetable.Motion.spring

/**
 * A question before something that can't be taken back, as redesign 1q asks for Sign out: a card over
 * a dark scrim with the title, what will happen, and Cancel beside the action. Tapping outside, Back
 * or Cancel leaves everything as it was.
 */
object ConfirmDialog {
    /** One of the card's two buttons, side by side at the bottom: [fill] behind, its label centred. */
    fun button(ui: Ui, label: String, fill: Int, color: Int, onClick: () -> Unit) = ui.text(label, 15f, color, ui.f.bodySemi).apply {
        gravity = Gravity.CENTER
        minHeight = ui.dp(48)
        setPadding(ui.dp(16), 0, ui.dp(16), 0)
        background = ui.pressable(ui.shape(fill, 12f), 12f)
        setOnClickListener { onClick() }
    }

    /** The card itself, empty: a rounded surface for a title, what will happen and the buttons. */
    fun frame(ui: Ui) = ui.column().apply {
        background = ui.shape(ui.p.surface, 22f)
        elevation = ui.dp(12f).toFloat()
        // 16dp over the title reads as 20dp: the display face's line box sits above its capitals.
        setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(20))
        isClickable = true // a tap on the card isn't a tap outside
    }

    /**
     * The card: title, what will happen, then Cancel and [action] side by side. With [hold], the action
     * only happens when its button is held ("Hold to sign out", see [HoldButton]); it gets the wider half.
     */
    fun card(ui: Ui, title: String, body: String, action: String, onCancel: () -> Unit, onConfirm: () -> Unit, hold: Boolean = false): LinearLayout {
        val p = ui.p
        return frame(ui).apply {
            addView(ui.text(title, 30f, p.text, ui.f.sign))
            addView(ui.text(body, 15f, p.muted).apply { setLineSpacing(0f, 1.35f) }, ui.wide(top = 8f, bottom = 20f))
            addView(ui.row(gap = 8f).apply {
                addView(button(ui, "Cancel", p.surface2, p.text, onCancel), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val act = if (hold) HoldButton(ui, "Hold to ${action.replaceFirstChar { it.lowercase() }}", action, p.accent, p.accentInk, onConfirm)
                else button(ui, action, p.accent, p.accentInk, onConfirm)
                addView(act, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, if (hold) 1.5f else 1f))
            })
        }
    }

    fun show(ui: Ui, title: String, body: String, action: String, hold: Boolean = false, onConfirm: () -> Unit) {
        lateinit var close: (then: (() -> Unit)?) -> Unit
        close = present(ui, card(ui, title, body, action, onCancel = { close(null) }, onConfirm = { close(onConfirm) }, hold = hold))
    }

    /**
     * Shows [card] over a dark scrim and returns what closes it. Closing runs its argument once the card
     * has gone: the action may restart the screen (Sign out does), and a dialog can't be dismissed from a
     * screen that has been replaced. [onClosed] runs however it closes (outside, Back or a button).
     */
    fun present(ui: Ui, card: View, onClosed: (() -> Unit)? = null): (then: (() -> Unit)?) -> Unit {
        val dialog = Dialog(ui.context, android.R.style.Theme_Translucent_NoTitleBar)
        val scrim = View(ui.context).apply { setBackgroundColor(0x99000000.toInt()) }
        var closing = false
        fun close(then: (() -> Unit)?) {
            if (closing) return
            closing = true
            fun done() { dialog.dismiss(); onClosed?.invoke(); then?.invoke() }
            if (!Motion.enabled) return done()
            card.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setInterpolator(Motion.EXIT).setDuration(150).withEndAction { done() }.start()
            Motion.fade(scrim, scrim.alpha, 0f, 150)
        }
        val frame = FrameLayout(ui.context).apply {
            addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                leftMargin = ui.dp(20)
                rightMargin = ui.dp(20)
            })
            scrim.setOnClickListener { close(null) }
        }
        dialog.setContentView(frame)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(0)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { close(null) }
        } else {
            dialog.setOnKeyListener { _, code, e ->
                if (code == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) close(null)
                code == KeyEvent.KEYCODE_BACK
            }
        }
        if (Motion.enabled) {
            scrim.alpha = 0f
            card.alpha = 0f
            card.scaleX = 0.94f
            card.scaleY = 0.94f
            Motion.fade(scrim, 0f, 1f, 180)
            Motion.fade(card, 0f, 1f, 180)
            card.animate().scaleX(1f).scaleY(1f).spring(Motion.SETTLE).start()
        }
        dialog.show()
        return ::close
    }
}

/**
 * A button that acts only when held, for what can't be undone (Sign out deletes everything). Pressed, a
 * darker fill sweeps across it in [HOLD_MS] while it sinks a little; at the end it pops, vibrates and runs
 * [onHeld]. Let go early (or slide off) and the fill drains back; a quick tap shakes it, as its label says
 * to hold. TalkBack's double tap runs it at once ([action] is its name there). One line, shrinking if the
 * half card is narrow.
 */
@SuppressLint("ViewConstructor")
class HoldButton(ui: Ui, label: String, action: String, fill: Int, ink: Int, private val onHeld: () -> Unit) :
    FitText(ui.context, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, ui.context.resources.displayMetrics)) {
    private val sweep = ColorDrawable(Palette.mix(ink, fill, 0.22f))
    private var progress = 0f
    private var filling: ValueAnimator? = null
    private var downAt = 0L
    private var held = false
    private var holding = false
    private val timer = Handler(Looper.getMainLooper())
    private val done = Runnable { finish() }
    private val slop = ViewConfiguration.get(ui.context).scaledTouchSlop

    init {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(ink)
        typeface = ui.f.bodySemi
        includeFontPadding = false
        maxLines = 1
        gravity = Gravity.CENTER
        minHeight = ui.dp(48)
        setPadding(ui.dp(16), 0, ui.dp(16), 0)
        background = ui.shape(fill, 12f)
        clipToOutline = true // the fill keeps to the rounded shape
        contentDescription = "$action. Hold to confirm"
        // Only TalkBack clicks it (touches are handled below): it can't hold, so the click is the confirmation.
        setOnClickListener { finish() }
    }

    override fun onDraw(canvas: Canvas) {
        if (progress > 0f) {
            sweep.setBounds(0, 0, (width * progress).toInt(), height)
            sweep.draw(canvas)
        }
        super.onDraw(canvas)
    }

    private fun fillTo(to: Float, ms: Long, ease: android.animation.TimeInterpolator?) {
        filling?.cancel()
        if (!Motion.enabled) { progress = to; invalidate(); return }
        filling = ValueAnimator.ofFloat(progress, to).apply {
            duration = ms
            interpolator = ease
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (held) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                holding = true
                downAt = SystemClock.uptimeMillis()
                parent?.requestDisallowInterceptTouchEvent(true)
                Haptics.tick(this)
                // Linear, so it reads as time running out; a little sink over the same time.
                fillTo(1f, (HOLD_MS * (1 - progress)).toLong(), null)
                animate().scaleX(0.97f).scaleY(0.97f).setDuration(HOLD_MS).setInterpolator(null).start()
                timer.postDelayed(done, (HOLD_MS * (1 - progress)).toLong())
            }
            MotionEvent.ACTION_MOVE -> if (holding && (e.x < -slop || e.y < -slop || e.x > width + slop || e.y > height + slop)) letGo(tap = false)
            MotionEvent.ACTION_UP -> if (holding) letGo(tap = SystemClock.uptimeMillis() - downAt < 300)
            MotionEvent.ACTION_CANCEL -> if (holding) letGo(tap = false)
        }
        return true
    }

    /** Let go before the end: the fill drains back; after a quick tap it shakes, to say "hold". */
    private fun letGo(tap: Boolean) {
        holding = false
        timer.removeCallbacks(done)
        fillTo(0f, 260, Motion.QUICK)
        animate().scaleX(1f).scaleY(1f).spring(Motion.BOUNCY).start()
        if (!tap) return
        Haptics.threshold(this, false)
        if (!Motion.enabled) return
        val d = resources.displayMetrics.density
        val shake = floatArrayOf(0f, -9f, 8f, -6f, 4f, -2f, 0f).map { it * d }.toFloatArray()
        ObjectAnimator.ofPropertyValuesHolder(this, PropertyValuesHolder.ofFloat(View.TRANSLATION_X, *shake)).setDuration(380).start()
    }

    private fun finish() {
        if (held) return
        held = true
        holding = false
        timer.removeCallbacks(done)
        fillTo(1f, 80, null)
        Haptics.threshold(this, true)
        if (!Motion.enabled) return onHeld()
        // A pop, then the action once it's seen.
        animate().scaleX(1.05f).scaleY(1.05f).setDuration(90).setInterpolator(Motion.EXIT).withEndAction {
            animate().scaleX(1f).scaleY(1f).spring(Motion.BOUNCY).start()
            postDelayed({ onHeld() }, 140)
        }.start()
    }

    override fun onDetachedFromWindow() {
        timer.removeCallbacks(done)
        filling?.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        const val HOLD_MS = 1100L
    }
}
