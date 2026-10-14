package com.skylightlim.umtimetable

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
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
    /** The card: title, what will happen, then Cancel and [action] side by side. */
    fun card(ui: Ui, title: String, body: String, action: String, onCancel: () -> Unit, onConfirm: () -> Unit): LinearLayout {
        val p = ui.p
        fun button(label: String, fill: Int, color: Int, onClick: () -> Unit) = ui.text(label, 15f, color, ui.f.bodySemi).apply {
            gravity = Gravity.CENTER
            minHeight = ui.dp(48)
            setPadding(ui.dp(16), 0, ui.dp(16), 0)
            background = ui.pressable(ui.shape(fill, 12f), 12f)
            setOnClickListener { onClick() }
        }
        return ui.column().apply {
            background = ui.shape(p.surface, 22f)
            elevation = ui.dp(12f).toFloat()
            // 16dp over the title reads as 20dp: the display face's line box sits above its capitals.
            setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(20))
            isClickable = true // a tap on the card isn't a tap outside
            addView(ui.text(title, 30f, p.text, ui.f.sign))
            addView(ui.text(body, 15f, p.muted).apply { setLineSpacing(0f, 1.35f) }, ui.wide(top = 8f, bottom = 20f))
            addView(ui.row(gap = 8f).apply {
                addView(button("Cancel", p.surface2, p.text, onCancel), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(button(action, p.accent, p.accentInk, onConfirm), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
    }

    fun show(ui: Ui, title: String, body: String, action: String, onConfirm: () -> Unit) {
        val dialog = Dialog(ui.context, android.R.style.Theme_Translucent_NoTitleBar)
        val scrim = View(ui.context).apply { setBackgroundColor(0x99000000.toInt()) }
        var closing = false
        lateinit var card: LinearLayout
        // [then] runs once the card has gone: the action may restart the screen (Sign out does), and a
        // dialog can't be dismissed from a screen that has been replaced.
        fun close(then: (() -> Unit)? = null) {
            if (closing) return
            closing = true
            fun done() { dialog.dismiss(); then?.invoke() }
            if (!Motion.enabled) return done()
            card.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setInterpolator(Motion.EXIT).setDuration(150).withEndAction { done() }.start()
            Motion.fade(scrim, scrim.alpha, 0f, 150)
        }
        card = card(ui, title, body, action, onCancel = { close() }, onConfirm = { close(onConfirm) })
        val frame = FrameLayout(ui.context).apply {
            addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                leftMargin = ui.dp(20)
                rightMargin = ui.dp(20)
            })
            scrim.setOnClickListener { close() }
        }
        dialog.setContentView(frame)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(0)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { close() }
        } else {
            dialog.setOnKeyListener { _, code, e ->
                if (code == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) close()
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
    }
}
