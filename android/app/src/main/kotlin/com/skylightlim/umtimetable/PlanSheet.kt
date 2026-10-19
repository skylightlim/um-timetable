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
import android.view.WindowInsetsAnimation
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.window.OnBackInvokedDispatcher
import com.skylightlim.umtimetable.Motion.spring
import java.time.format.TextStyle
import java.util.Locale

/**
 * The plan editor, out of the week grid. Letting go of the selection box on a free time (or a double
 * tap there, or a tap on a plan) pops that cell out of the grid: it lifts and swells where it is, tilting up off the page, then flies to the
 * middle of the screen (of what's left over a new plan's keyboard, which comes up with it), growing and turning to face the user, into a card: what the plan is, how long,
 * its colour and a reminder. Saved, the card shrinks back down into the cell, where the plan pops into
 * place; cancelled, it goes back empty; deleted, it vanishes in a spin. A tap on its time sends it back
 * down too, and the grid's selection box waits there to be dragged to another time. A time that has
 * passed asks what really happened.
 */
object PlanSheet {
    /** The card's views, and what's been chosen on it so far. */
    class Editor(val card: LinearLayout, val content: LinearLayout, val body: LinearLayout, val field: EditText, val scroll: Capped) {
        var color = 0
        var end = 0
        var remind: Int? = null
        var weekly = false
        /** Set by [show]: what the card's buttons (and the keyboard's Done) do: "save", "delete", "skip" (this week of a weekly plan) or "cancel". */
        var close: (String) -> Unit = {}
    }

    /**
     * The card's middle (what, how long, reminder, repeat, colour) between its time band and its buttons:
     * no taller than [cap], scrolling inside when it's more. With the keyboard up there may not be room
     * for the whole card; the band and the buttons then stay in view and only the middle gives way.
     */
    class Capped(context: Context) : ScrollView(context) {
        var cap = Int.MAX_VALUE
            set(value) {
                if (field == value) return
                field = value
                requestLayout()
            }

        init {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
            // Where it's cut, it fades out, rather than ending in a hard edge.
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength((20 * resources.displayMetrics.density).toInt())
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val given = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(heightMeasureSpec)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(minOf(cap, given, (1 shl 30) - 1), MeasureSpec.AT_MOST))
        }
    }

    /** Whether the card's Every week starts on: a weekly plan that goes on after this week (not stopped here). */
    private fun repeats(plan: Plan) = plan.weekly && (plan.until == null || plan.date < plan.until)

    /**
     * [plan] as the card leaves it: what's written, its colour, length and reminder, and, when Every week
     * was switched, repeating from this week to the end of the semester, or (switched off on a later week
     * of a weekly plan) stopping after this week; switched off on its first week, it's just that day.
     */
    fun edited(plan: Plan, editor: Editor): Plan {
        val base = plan.copy(text = editor.field.text.toString().trim(), color = editor.color, end = editor.end, remind = editor.remind)
        return when {
            editor.weekly == repeats(plan) -> base
            editor.weekly && plan.weekly -> base.copy(until = null)
            editor.weekly -> base.copy(weekly = true, from = plan.date, until = null, skip = emptySet())
            plan.date != plan.from -> base.copy(until = plan.date)
            else -> base.copy(weekly = false)
        }
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

        val body = ui.column().apply { setPadding(ui.dp(20), ui.dp(16), ui.dp(20), 0) }
        val scroll = Capped(c).apply { addView(body) }
        content.addView(scroll)
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
        val editor = Editor(card, content, body, field, scroll)
        editorRef = editor

        fun label(text: String) = body.addView(ui.label(text, p.muted, 10.5f), ui.wide(top = 18f, bottom = 8f))

        // How long and the reminder, side by side on two wheels: one row instead of two full-width tapes
        // (with the keyboard up, the card ran under it).
        fun column(title: String, content: View) = ui.column().apply {
            addView(ui.label(title, p.muted, 10.5f), ui.wide(bottom = 8f))
            // As wide as it needs, under its label: a wheel's band only as wide as its options.
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // How long: every half hour that fits before the next class or plan (or 19:00).
        val lengths = Plan.lengths(maxEnd - plan.start).ifEmpty { listOf((maxEnd - plan.start) to "${maxEnd - plan.start} min") }
        val chosenLength = lengths.indexOfFirst { plan.start + it.first == end }.takeIf { it >= 0 } ?: lengths.lastIndex.also { end = plan.start + lengths.last().first; showTime() }
        val howLong = column("How long", ui.wheel(lengths.map { it.second }, chosenLength) { i ->
            end = plan.start + lengths[i].first
            editor.end = end
            showTime()
        }.apply { name = "How long" })
        // A reminder, before it starts; there's none to set for a time that has passed. Only the ones still
        // to come (and the one set): a reminder already past would never go off.
        val reminder = if (passed) {
            remind = null
            column("Reminder", ui.text("This time has passed: no reminder.", 13.5f, p.muted))
        } else {
            val now = System.currentTimeMillis()
            val reminders = Plan.REMINDERS.filter { (m, _) -> m == null || m == remind || plan.startsAt - m * 60_000L > now }
            column("Reminder", ui.wheel(reminders.map { it.second }, reminders.indexOfFirst { it.first == remind }.coerceAtLeast(0)) { i -> remind = reminders[i].first; editor.remind = remind }.apply { name = "Reminder" })
        }
        body.addView(ui.row(gap = 12f, center = false).apply {
            addView(howLong, ui.grow())
            addView(reminder, ui.grow())
        }, ui.wide(top = 18f))
        if (!passed && !Notify.allowed(c)) body.addView(ui.text("Notifications are off for the app: turn them on in Settings for reminders.", 12.5f, p.muted), ui.wide(top = 8f))

        // Every week: the plan comes back on this weekday, at this time, to the end of the semester.
        val weekday = plan.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK)
        val last = (if (plan.weekly) plan.from else plan.date).let { AcademicCalendar.semesterEnd(it) ?: it.plusWeeks(13) }
        val repeatNote = ui.text("", 12.5f, p.muted)
        fun showRepeat(on: Boolean) {
            repeatNote.text = when {
                on -> "${weekday}s until ${last.dayOfMonth} ${last.month.getDisplayName(TextStyle.SHORT, Locale.UK)}, the semester's end"
                plan.weekly && plan.date != plan.from -> "Stops after this $weekday"
                else -> "Just this $weekday"
            }
        }
        editor.weekly = repeats(plan)
        showRepeat(editor.weekly)
        body.addView(ui.row(gap = 12f).apply {
            addView(ui.column().apply {
                addView(ui.text("Every week", 15f, p.text, ui.f.bodySemi))
                addView(repeatNote, ui.wide(top = 2f))
            }, ui.grow())
            addView(Toggle(ui, editor.weekly) { on -> editor.weekly = on; showRepeat(on) }.apply { contentDescription = "Every week" })
        }, ui.wide(top = 14f))

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

        // Delete (a saved plan), then Cancel and Save. A weekly plan's Delete asks which: this week's alone,
        // or every week's; the buttons give way to that question in place.
        val buttons = ui.row(gap = 8f)
        val which = ui.row(gap = 8f).apply {
            visibility = View.GONE
            addView(ui.text("Delete", 15f, p.muted, ui.f.bodySemi).apply { gravity = Gravity.CENTER_VERTICAL; minHeight = ui.dp(48) })
            addView(ui.space(), ui.spacer())
            addView(ConfirmDialog.button(ui, "This week", p.surface2, p.text) { editor.close("skip") })
            addView(ConfirmDialog.button(ui, "Every week", p.surface2, p.text) { editor.close("delete") })
        }
        buttons.apply {
            if (!isNew) addView(ui.text("Delete", 15f, p.muted, ui.f.bodySemi).apply {
                gravity = Gravity.CENTER
                minHeight = ui.dp(48)
                setPadding(ui.dp(4), 0, ui.dp(12), 0)
                setOnClickListener {
                    if (!plan.weekly) return@setOnClickListener editor.close("delete")
                    Haptics.tick(this)
                    buttons.visibility = View.GONE
                    which.visibility = View.VISIBLE
                    Motion.enter(which, ui.dp(8f).toFloat())
                }
            })
            addView(ui.space(), ui.spacer())
            addView(ConfirmDialog.button(ui, "Cancel", p.surface2, p.text) { editor.close("cancel") })
            addView(ConfirmDialog.button(ui, "Save", p.accent, p.accentInk) { editor.close("save") })
        }
        // Under the middle, never scrolled away.
        content.addView(ui.column().apply {
            setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(20))
            addView(buttons)
            addView(which)
        })
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
        val editor = build(ui, plan, isNew, maxEnd)
        val card = editor.card
        val content = editor.content
        val body = editor.body
        val field = editor.field
        val dialog = Dialog(c, android.R.style.Theme_Translucent_NoTitleBar)
        val scrim = View(c).apply { setBackgroundColor(0x99000000.toInt()) }
        // The card's place, lifted over the keyboard as the keyboard rises, frame by frame. The card flies
        // inside it, so a flight and the keyboard add up: the card goes straight to where it rests over the
        // keyboard, and never lands first and then gets pushed up.
        val holder = FrameLayout(c)
        var nav = 0 // the navigation bar under the card's place
        var ime = 0 // the keyboard, as it is or as it will be when it has finished moving
        var imeMoving = false
        var riding = true // until the card heads back down to the grid

        /**
         * The card's middle no taller than leaves the whole card, 12dp clear above and below, in what a
         * keyboard [keyboard] px tall leaves of its place. Only while the card rides the keyboard: on its way
         * back down to the grid it keeps its size.
         */
        fun fit(keyboard: Int) {
            if (!riding || holder.height == 0 || card.height == 0) return
            val room = holder.height - maxOf(0, keyboard - nav) - ui.dp(24)
            val fixed = card.height - editor.scroll.height // the time band and the buttons
            editor.scroll.cap = (room - fixed).coerceAtLeast(ui.dp(120))
        }

        /** How far the card sits above its place with a keyboard [keyboard] px tall: centred in what's left, its top in view. */
        fun liftFor(keyboard: Int): Float {
            val room = holder.height - maxOf(0, keyboard - nav)
            return maxOf(0f, (room - card.height) / 2f) - card.top
        }

        val frame = object : FrameLayout(c) {
            private val presser = Motion.Presser(this)
            override fun dispatchTouchEvent(e: MotionEvent): Boolean {
                presser.onTouch(e)
                return super.dispatchTouchEvent(e)
            }
        }.apply {
            clipChildren = false // a lifted place no longer covers a low cell the card flies out of
            addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(holder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            holder.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                leftMargin = ui.dp(20)
                rightMargin = ui.dp(20)
            })
            // Laid out between the system bars only; the keyboard lifts the holder instead.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
                nav = bars.bottom
                // While the keyboard moves, this is where it will end; onProgress follows it there.
                ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                // Sized for the keyboard as it will be, then placed once that size is laid out.
                v.post {
                    fit(ime)
                    holder.post { if (riding && !imeMoving) holder.translationY = liftFor(ime) }
                }
                WindowInsets.CONSUMED
            }
            setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_STOP) {
                override fun onPrepare(a: WindowInsetsAnimation) {
                    if (a.typeMask and WindowInsets.Type.ime() != 0) imeMoving = true
                }

                override fun onProgress(insets: WindowInsets, running: List<WindowInsetsAnimation>): WindowInsets {
                    if (imeMoving && riding) holder.translationY = liftFor(insets.getInsets(WindowInsets.Type.ime()).bottom)
                    return insets
                }

                override fun onEnd(a: WindowInsetsAnimation) {
                    if (a.typeMask and WindowInsets.Type.ime() == 0) return
                    imeMoving = false
                    if (riding) holder.translationY = liftFor(ime)
                }
            })
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

        var closing = false
        val close: (String) -> Unit = close@{ how ->
            if (closing) return@close
            closing = true
            riding = false // the card's place stays put as the keyboard goes, so it lands right on its cell
            (c.getSystemService(InputMethodManager::class.java))?.hideSoftInputFromWindow(field.windowToken, 0)
            when (how) {
                "save" -> {
                    Plans.save(c, edited(plan, editor))
                    WeekGrid.landing = plan.id
                    Haptics.tick(card)
                }
                "delete" -> { Plans.delete(c, plan.id); Haptics.back(card) }
                "skip" -> { Plans.skip(c, plan, plan.date); Haptics.back(card) }
            }
            // Change the time: what's been chosen so far goes back to the grid with the box.
            val draft = edited(plan, editor)
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
            if (how == "delete" || how == "skip") {
                // Gone in a spin.
                card.animate().scaleX(0.2f).scaleY(0.2f).rotation(28f).alpha(0f).setInterpolator(Motion.EXIT).setDuration(260).withEndAction { done() }.start()
                return@close
            }
            // Back down into its cell: for a save, as long as the plan now is.
            Motion.flyBack(card, RectF(from).apply { if (how == "save") bottom = top + (editor.end - plan.start) / 60f * hourPx - 2 }) { done() }
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
        // A new plan's keyboard comes up as the card pops out (the window shows it as it takes focus), and
        // the card rides up on it; before, it came up only after the card had landed, and pushed it up.
        if (isNew) {
            @Suppress("DEPRECATION") dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
        if (isNew) field.requestFocus()
        if (!Motion.enabled) return
        // Out of the grid: the cell as it is there, its contents hidden until the card lands. It pops, flies to
        // the middle turning to face the user, and lands with a bounce (the flight other cards share).
        scrim.alpha = 0f
        Motion.fade(scrim, 0f, 1f, 260)
        Motion.flyOut(card, from, content) { Motion.enter(body, ui.dp(14f).toFloat()) }
    }
}
