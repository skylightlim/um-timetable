package com.skylightlim.umtimetable

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.window.OnBackInvokedDispatcher
import com.skylightlim.umtimetable.Motion.spring
import java.time.format.TextStyle
import java.util.Locale

/**
 * The plan editor, out of the week grid. Letting go of the selection box on a free time (or a double
 * tap there, or a tap on a plan) pops that cell out of the grid: it lifts and swells where it is, tilting up off the page, then flies to the
 * middle of the screen, growing and turning to face the user, into a card: what the plan is, how long,
 * its colour and a reminder. Saved, the card shrinks back down into the cell, where the plan pops into
 * place; cancelled, it goes back empty; deleted, it vanishes in a spin. A tap on its time sends it back
 * down too, and the grid's selection box waits there to be dragged to another time. A time that has
 * passed asks what really happened.
 */
object PlanSheet {
    /** The card's views, and what's been chosen on it so far. */
    class Editor(val card: LinearLayout, val content: LinearLayout, val body: LinearLayout, val field: EditText) {
        var color = 0
        var end = 0
        var remind: Int? = null
        /** Set by [show]: what the card's buttons (and the keyboard's Done) do: "save", "delete" or "cancel". */
        var close: (String) -> Unit = {}
    }

    /** The card for [plan] ([isNew] if not saved yet), which can run to [maxEnd] at most. */
    fun build(ui: Ui, plan: Plan, isNew: Boolean, maxEnd: Int): Editor {
        val c = ui.context
        val p = ui.p
        var color = plan.color
        var end = plan.end
        var remind = plan.remind
        val passed = plan.startsAt <= System.currentTimeMillis()
        val content = ui.column()
        val card = ui.column().apply {
            background = ui.shape(p.surface, 22f)
            clipToOutline = true // the band keeps to the rounded top
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            isClickable = true // a tap on the card isn't a tap outside
        }

        // The band: the plan's colour, with its day and time on it.
        val band = ui.column().apply {
            setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(14))
            background = GradientDrawable().apply { setColor(color) }
        }
        val day = "${plan.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK)} ${plan.date.dayOfMonth} ${plan.date.month.getDisplayName(TextStyle.SHORT, Locale.UK)}"
        band.addView(ui.label(when { passed -> "Looking back · $day"; isNew -> "New plan · $day"; else -> "Plan · $day" }, p.inkMuted, 11f))
        val time = ui.text("", 30f, p.ink, ui.f.sign)
        fun showTime() { time.text = "${clockText(plan.start)}–${clockText(end)}" }
        showTime()
        // The time is a button: it sends the card back to the grid, where the selection box picks another.
        lateinit var editorRef: Editor
        band.addView(ui.row(gap = 10f).apply {
            addView(time, ui.grow())
            addView(ui.text("Change time", 12.5f, p.ink, ui.f.bodyBold).apply {
                background = ui.shape(Color.TRANSPARENT, 99f, p.inkMuted, 1.5f)
                setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(5))
            })
            background = ui.pressable(ui.shape(Color.TRANSPARENT, 10f), 10f)
            contentDescription = "${clockText(plan.start)} to ${clockText(end)}. Change time"
            setOnClickListener { editorRef.close("repick") }
        }, ui.wide(top = 2f))
        content.addView(band)

        val body = ui.column().apply { setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(20)) }
        content.addView(body)
        val field = EditText(c).apply {
            setText(plan.text)
            // A time that has passed is for saying what really happened.
            hint = if (passed) "What did you do? Be honest." else "What will you do?"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(p.text)
            setHintTextColor(p.faint)
            typeface = ui.f.bodySemi
            background = ui.shape(p.surface2, 12f)
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            minHeight = ui.dp(52)
            maxLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_DONE
            textCursorDrawable = ui.shape(p.accentText, 1f).apply { setSize(ui.dp(2), 0) }
            setSelection(text.length)
        }
        body.addView(field)
        val editor = Editor(card, content, body, field)
        editorRef = editor

        fun label(text: String) = body.addView(ui.label(text, p.muted, 10.5f), ui.wide(top = 18f, bottom = 8f))

        // How long: only the lengths that fit before the next class or plan (or 19:00).
        val lengths = Plan.LENGTHS.filter { plan.start + it.first <= maxEnd }.ifEmpty { listOf((maxEnd - plan.start) to "${maxEnd - plan.start} min") }
        label("How long")
        val chosenLength = lengths.indexOfFirst { plan.start + it.first == end }.takeIf { it >= 0 } ?: lengths.lastIndex.also { end = plan.start + lengths.last().first; showTime() }
        body.addView(ui.segmented(lengths.map { it.second }, chosenLength, fill = true) { i, _ ->
            end = plan.start + lengths[i].first
            editor.end = end
            showTime()
        })

        // The colour: a dot each, the chosen one ringed; picking one hops it and recolours the band.
        label("Colour")
        val dots = ui.row().apply { clipChildren = false; clipToPadding = false; setPadding(0, ui.dp(8), 0, 0) }
        val palette = Plans.palette(Scheme.load(c), p.accent).let { if (color in it) it else it + color }
        fun ring(v: View, on: Boolean, of: Int) {
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(of)
                if (on) setStroke(ui.dp(2.5f), p.text)
            }
        }
        val dotViews = palette.map { col ->
            View(c).apply {
                ring(this, col == color, col)
                contentDescription = if (col == color) "Colour, chosen" else "Colour"
            }
        }
        for ((i, col) in palette.withIndex()) {
            val v = dotViews[i]
            v.setOnClickListener {
                if (col == color) return@setOnClickListener
                val was = color
                color = col
                editor.color = col
                dotViews.forEachIndexed { k, o -> ring(o, palette[k] == color, palette[k]); o.contentDescription = if (palette[k] == color) "Colour, chosen" else "Colour" }
                Motion.hop(v, 0, ui.dp(8f).toFloat())
                Haptics.tick(v)
                if (!Motion.enabled) (band.background as GradientDrawable).setColor(col)
                else ValueAnimator.ofArgb(was, col).setDuration(220).apply { addUpdateListener { (band.background as GradientDrawable).setColor(it.animatedValue as Int) } }.start()
            }
            dots.addView(v, LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)).apply { if (i > 0) marginStart = ui.dp(10) })
        }
        body.addView(dots)

        // A reminder, before it starts; there's none to set for a time that has passed.
        label("Reminder")
        if (passed) {
            body.addView(ui.text("This time has passed: no reminder.", 13.5f, p.muted))
            remind = null
        } else {
            val reminders = Plan.REMINDERS
            body.addView(ui.segmented(reminders.map { it.second }, reminders.indexOfFirst { it.first == remind }.coerceAtLeast(0), fill = true) { i, _ -> remind = reminders[i].first; editor.remind = remind })
            if (!Notify.allowed(c)) body.addView(ui.text("Notifications are off for the app: turn them on in Settings for reminders.", 12.5f, p.muted), ui.wide(top = 8f))
        }

        // Delete (a saved plan), then Cancel and Save.
        body.addView(ui.row(gap = 8f).apply {
            if (!isNew) addView(ui.text("Delete", 15f, p.muted, ui.f.bodySemi).apply {
                gravity = Gravity.CENTER
                minHeight = ui.dp(48)
                setPadding(ui.dp(4), 0, ui.dp(12), 0)
                setOnClickListener { editor.close("delete") }
            })
            addView(ui.space(), ui.spacer())
            addView(ConfirmDialog.button(ui, "Cancel", p.surface2, p.text) { editor.close("cancel") })
            addView(ConfirmDialog.button(ui, "Save", p.accent, p.accentInk) { editor.close("save") })
        }, ui.wide(top = 22f))
        field.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { editor.close("save"); true } else false }
        return editor.apply {
            this.color = color
            this.end = end
            this.remind = remind
        }
    }

    /**
     * Shows the card for [plan] flying out of [from], the cell in the week grid (screen coordinates), an
     * hour there being [hourPx] tall; [onDone] runs after a save or delete.
     */
    fun show(ui: Ui, from: RectF, plan: Plan, isNew: Boolean, maxEnd: Int, hourPx: Float, onRepick: (Plan) -> Unit = {}, onDone: () -> Unit) {
        val c = ui.context
        val d = c.resources.displayMetrics.density
        val editor = build(ui, plan, isNew, maxEnd)
        val card = editor.card
        val content = editor.content
        val body = editor.body
        val field = editor.field
        val dialog = Dialog(c, android.R.style.Theme_Translucent_NoTitleBar)
        val scrim = View(c).apply { setBackgroundColor(0x99000000.toInt()) }

        val frame = object : FrameLayout(c) {
            private val presser = Motion.Presser(this)
            override fun dispatchTouchEvent(e: MotionEvent): Boolean {
                presser.onTouch(e)
                return super.dispatchTouchEvent(e)
            }
        }.apply {
            addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                leftMargin = ui.dp(20)
                rightMargin = ui.dp(20)
            })
            // Over the keyboard: the card stays centred in what's left.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(0, bars.top, 0, bars.bottom)
                WindowInsets.CONSUMED
            }
        }
        scrim.setOnClickListener { editor.close("cancel") }
        dialog.setContentView(frame)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(0)
            setDecorFitsSystemWindows(false)
            @Suppress("DEPRECATION") setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        /** Where the card is (its centre on screen), so the flight can start from and end at the cell. */
        fun centre(): Pair<Float, Float> {
            // From its layout in the frame, so the flight's own moves and scaling don't count.
            val at = IntArray(2).also(frame::getLocationOnScreen)
            return (at[0] + card.left + card.width / 2f) to (at[1] + card.top + card.height / 2f)
        }

        var closing = false
        val close: (String) -> Unit = close@{ how ->
            if (closing) return@close
            closing = true
            (c.getSystemService(InputMethodManager::class.java))?.hideSoftInputFromWindow(field.windowToken, 0)
            when (how) {
                "save" -> {
                    Plans.save(c, plan.copy(text = field.text.toString().trim(), color = editor.color, end = editor.end, remind = editor.remind))
                    WeekGrid.landing = plan.id
                    Haptics.tick(card)
                }
                "delete" -> { Plans.delete(c, plan.id); Haptics.back(card) }
            }
            // Change the time: what's been chosen so far goes back to the grid with the box.
            val draft = plan.copy(text = field.text.toString().trim(), color = editor.color, end = editor.end, remind = editor.remind)
            fun done() {
                dialog.dismiss()
                when (how) {
                    "repick" -> onRepick(draft)
                    "cancel" -> Unit
                    else -> onDone()
                }
            }
            if (!Motion.enabled) return@close done()
            content.animate().alpha(0f).setDuration(90).start()
            Motion.fade(scrim, scrim.alpha, 0f, 240)
            if (how == "delete") {
                // Gone in a spin.
                card.animate().scaleX(0.2f).scaleY(0.2f).rotation(28f).alpha(0f).setInterpolator(Motion.EXIT).setDuration(260).withEndAction { done() }.start()
                return@close
            }
            // Back down into its cell: for a save, as long as the plan now is.
            val (cx, cy) = centre()
            val to = RectF(from).apply { if (how == "save") bottom = top + (editor.end - plan.start) / 60f * hourPx - 2 }
            card.animate().translationX(to.centerX() - cx).translationY(to.centerY() - cy)
                .scaleX(to.width() / card.width).scaleY(to.height() / card.height)
                .rotationX(18f).rotation(-3f).translationZ(0f)
                .setInterpolator(Motion.EXIT).setDuration(260).withEndAction { done() }.start()
        }

        editor.close = close
        if (Build.VERSION.SDK_INT >= 33) {
            dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { close("cancel") }
        } else {
            dialog.setOnKeyListener { _, code, e ->
                if (code == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) close("cancel")
                code == KeyEvent.KEYCODE_BACK
            }
        }
        dialog.show()
        fun ready() {
            if (!isNew) return
            field.requestFocus()
            c.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
        if (!Motion.enabled) return ready()
        // Out of the grid: the cell as it is there, its contents hidden until the card lands.
        content.alpha = 0f
        scrim.alpha = 0f
        card.alpha = 0f
        card.post {
            val (cx, cy) = centre()
            val sx = from.width() / card.width
            val sy = from.height() / card.height
            card.pivotX = card.width / 2f
            card.pivotY = card.height / 2f
            card.cameraDistance = 9000 * d
            card.translationX = from.centerX() - cx
            card.translationY = from.centerY() - cy
            card.scaleX = sx
            card.scaleY = sy
            card.alpha = 1f
            Motion.fade(scrim, 0f, 1f, 260)
            // It pops: swells, lifts and tilts up off the page...
            card.animate().scaleX(sx * 1.3f).scaleY(sy * 1.3f).rotationX(24f).rotation(-7f).translationZ(ui.dp(10f).toFloat())
                .setInterpolator(DecelerateInterpolator(1.5f)).setDuration(130).withEndAction {
                    // ...then flies to the middle, turning to face the user, and lands with a bounce.
                    card.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).rotationX(0f).rotation(0f)
                        .translationZ(ui.dp(24f).toFloat()).spring(Motion.SETTLE).withEndAction { ready() }.start()
                    Motion.fade(content, 0f, 1f, 220)
                    Motion.enter(body, ui.dp(14f).toFloat())
                }.start()
        }
    }
}
