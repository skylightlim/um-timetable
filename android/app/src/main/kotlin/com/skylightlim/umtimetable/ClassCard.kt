package com.skylightlim.umtimetable

import android.app.Dialog
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.window.OnBackInvokedDispatcher

/**
 * A class's card, out of the week grid: a long press on a class pops its cell out of the grid into a card,
 * the flight the plan card takes ([PlanSheet]). On it, the course's colour with the class's day and time,
 * the course and where; and Delete, held as Sign out is ([HoldButton]), which deletes the class every week
 * ([ClassEdits.delete]) until the timetable is got from TimeEdit again. Deleted, the card vanishes in a spin;
 * closed, it goes back down into its cell.
 */
object ClassCard {
    /** The card's views: [close] is what its buttons do, "delete" or "close" (set by [show]). */
    class Card(val card: LinearLayout, val content: LinearLayout, val body: LinearLayout) {
        var close: (String) -> Unit = {}
    }

    fun build(ui: Ui, s: Session): Card {
        val p = ui.p
        val content = ui.column()
        val card = ui.column().apply {
            background = ui.shape(p.surface, 22f)
            clipToOutline = true // the band keeps to the rounded top
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            isClickable = true // a tap on the card isn't a tap outside
        }
        // The band: the course's colour, with the class's day and time on it.
        content.addView(ui.column().apply {
            setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(14))
            setBackgroundColor(s.course.color)
            addView(ui.label("${s.type} · ${DAY_NAMES[s.day]}s", p.inkMuted, 11f))
            addView(ui.text("${clockText(s.start)}–${clockText(s.end)}", 30f, p.ink, ui.f.sign), ui.wide(top = 2f))
        })
        val body = ui.column().apply {
            setPadding(ui.dp(20), ui.dp(16), ui.dp(20), 0)
            addView(ui.text(s.course.name, 20f, p.text, ui.f.bodyBold).apply { setLineSpacing(0f, 1.1f) })
            addView(ui.text(listOfNotNull(s.course.code, s.room, s.lecturer).joinToString(" · "), 13.5f, p.muted), ui.wide(top = 4f))
        }
        content.addView(body)
        val c = Card(card, content, body)

        // What Delete does, then Close and Delete side by side as on the Sign out card: Delete only once it's held
        // (the phone sobs while it fills, see [HoldButton]).
        body.addView(ui.text("Deleting takes it off every week: Day, Week, the widget, reminders and auto attendance. To bring it back, get the timetable from TimeEdit again in Settings.", 12.5f, p.muted).apply { setLineSpacing(0f, 1.25f) }, ui.wide(top = 14f))
        content.addView(ui.row(gap = 8f).apply {
            setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(20))
            addView(ConfirmDialog.button(ui, "Close", p.surface2, p.text) { c.close("close") }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(HoldButton(ui, "Hold to delete", "Delete", p.accent, p.accentInk) { c.close("delete") }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f))
        })
        return c
    }

    /** Shows [s]'s card flying out of [from], its cell in the week grid (screen coordinates); [onDeleted] runs once it's deleted. */
    fun show(ui: Ui, from: RectF, s: Session, onDeleted: () -> Unit) {
        val ctx = ui.context
        val c = build(ui, s)
        val card = c.card
        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        val scrim = View(ctx).apply { setBackgroundColor(0x99000000.toInt()) }
        // No press feedback over it (PlanSheet's Presser): it would sink the held button against its own sinking.
        val frame = FrameLayout(ctx).apply {
            clipChildren = false
            addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                leftMargin = ui.dp(20)
                rightMargin = ui.dp(20)
            })
        }
        dialog.setContentView(frame)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(0)
        }

        var closing = false
        val close: (String) -> Unit = close@{ how ->
            if (closing) return@close
            closing = true
            // (The held button has already clicked.)
            if (how == "delete") ClassEdits.delete(ctx, ClassEdits.key(s))
            fun done() {
                dialog.dismiss()
                if (how == "delete") onDeleted()
            }
            if (!Motion.enabled) return@close done()
            c.content.animate().alpha(0f).setDuration(90).start()
            Motion.fade(scrim, scrim.alpha, 0f, 240)
            if (how == "delete") {
                // Gone in a spin.
                card.animate().scaleX(0.2f).scaleY(0.2f).rotation(28f).alpha(0f).setInterpolator(Motion.EXIT).setDuration(260).withEndAction { done() }.start()
                return@close
            }
            Motion.flyBack(card, from) { done() }
        }
        c.close = close
        scrim.setOnClickListener { close("close") }
        if (Build.VERSION.SDK_INT >= 33) {
            dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { close("close") }
        } else {
            dialog.setOnKeyListener { _, code, e ->
                if (code == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) close("close")
                code == KeyEvent.KEYCODE_BACK
            }
        }
        dialog.show()
        if (!Motion.enabled) return
        scrim.alpha = 0f
        Motion.fade(scrim, 0f, 1f, 260)
        Motion.flyOut(card, from, c.content) { Motion.enter(c.body, ui.dp(14f).toFloat()) }
    }
}
