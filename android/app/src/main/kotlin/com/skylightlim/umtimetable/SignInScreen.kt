package com.skylightlim.umtimetable

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Paint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView
import com.skylightlim.umtimetable.Motion.spring

/**
 * The first screen, laid out as in redesign 1t (one statement, the three steps, one button) in UM's
 * colours. It shows over the app while there's no sign-in and no timetable.
 *
 * One field and one accent: a deep UM blue at the app icon's hue (266°; the crest's #3E4095 sits at 278°
 * and reads violet full-bleed), type in tints of that blue, and the logo's yellow kept for the two
 * things that should catch the eye: the red-notched brand bar and the sign-in button.
 *
 * Over the statement, in the room the rest leaves, the cow and the horse fight for five seconds
 * ([DuelScene]) as the poster comes in: the bar draws itself across, the statement rises line by line,
 * the steps' numbers pop, the button rises and its arrow nudges now and then. A tap on the pair plays
 * the fight again. With "Remove animations" it's all simply there.
 */
object SignInScreen {
    private const val FIELD = 0xFF132B7C.toInt()   // oklch(0.33 0.14 266)
    private const val TEXT = 0xFFF1F5FE.toInt()    // oklch(0.97 0.012 266), 11.6:1 on the field
    private const val SUB = 0xFFB8C7E9.toInt()     // oklch(0.83 0.05 266), 7.4:1
    private const val HAIR = 0xFF4E66A3.toInt()    // oklch(0.52 0.10 266), lines between the steps
    private const val YELLOW = 0xFFFFDE20.toInt()  // UM's yellow; the field on it is 9.5:1
    private const val RED = 0xFFED3237.toInt()     // UM's red, the bar's notch only

    /** The statement, one line each: they rise in one after another. */
    private val STATEMENT = listOf("Sign in once.", "Your whole semester", "in one place.")
    private const val STATEMENT_SP = 48f

    /** What happens after the button, in order: the sign-in, the timetable it brings, then Spectrum. */
    private val STEPS = listOf(
        "SiswaMail" to "Microsoft's page",
        "Timetable" to "From TimeEdit",
        "Spectrum" to "Due, marks, news",
    )

    fun build(ui: Ui, onSignIn: () -> Unit): View {
        val pad = ui.dp(20)
        val poster = Poster(ui.context)
        poster.apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(FIELD)
            isClickable = true // nothing under it takes a touch
            clipToPadding = false // the fight runs in from the screen's edges
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(pad + bars.left, ui.dp(28) + bars.top, pad + bars.right, ui.dp(40) + bars.bottom)
                insets
            }
            // 40dp under the note, not 24: the button sits a little higher, nearer the thumb.
            setPadding(pad, ui.dp(28), pad, ui.dp(40))
        }

        val brand = ui.label("UM Timetable", SUB, 11f)
        poster.addView(brand)
        // The rule starts red, as the U and M of the wordmark do.
        val rule = ui.row().apply {
            addView(View(ui.context).apply { setBackgroundColor(RED) }, LinearLayout.LayoutParams(ui.dp(28), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(View(ui.context).apply { setBackgroundColor(YELLOW) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        poster.addView(rule, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(2)).apply { topMargin = ui.dp(10) })

        // The stage takes the room the rest leaves, across the whole screen (out into the padding).
        val stage = DuelView(ui, TEXT, FIELD, stage = true)
        val scene = DuelScene(stage)
        stage.setOnClickListener { if (!scene.busy) { Haptics.tick(stage); scene.play() } }
        poster.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            leftMargin = -pad
            rightMargin = -pad
            topMargin = ui.dp(8)
        })
        poster.stage = stage

        // One size for the three lines, as large as the longest fits the width, up to 48sp.
        val lines = STATEMENT.map { ui.text(it, STATEMENT_SP, TEXT, ui.f.sign).apply { setLineSpacing(0f, 0.92f); maxLines = 1 } }
        poster.statement = lines
        poster.addView(ui.column().apply { lines.forEach { addView(it) } }, ui.wide(top = 8f))

        // The three steps, with lines between them only. The numbers are large: they're the order of things.
        val dividers = mutableListOf<View>()
        val numbers = mutableListOf<TextView>()
        val words = mutableListOf<View>()
        poster.addView(ui.row(center = false).apply {
            STEPS.forEachIndexed { i, (title, sub) ->
                if (i > 0) addView(View(context).apply { setBackgroundColor(HAIR); dividers += this }, LinearLayout.LayoutParams(ui.dp(1.5f), ViewGroup.LayoutParams.MATCH_PARENT))
                addView(ui.column().apply {
                    setPadding(if (i == 0) 0 else ui.dp(12), ui.dp(4), ui.dp(8), ui.dp(4))
                    clipChildren = false
                    addView(ui.text("${i + 1}", 44f, TEXT, ui.f.sign).apply { numbers += this })
                    val text = ui.column().apply {
                        // Shrinks rather than break inside the word on a narrow phone with a large font ("Timetabl / e").
                        addView(ui.fitText(title, 15f, TEXT, ui.f.bodyBold, words = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                        // Balanced, so a narrow phone breaks it in two even halves rather than leave one word alone.
                        addView(ui.text(sub, 12.5f, SUB).apply { breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED }, ui.wide(top = 2f))
                    }
                    words += text
                    addView(text, ui.wide(top = 2f))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }, ui.wide(top = 22f, bottom = 24f))

        val arrow = ui.icon(R.drawable.ic_arrow, FIELD, 18f)
        val button = ui.row(gap = 12f).apply {
            background = ui.pressable(ui.shape(YELLOW, 14f), 14f)
            minimumHeight = ui.dp(56)
            setPadding(ui.dp(18), 0, ui.dp(18), 0)
            contentDescription = "Sign in with SiswaMail"
            addView(ui.text("Sign in with SiswaMail", 16f, FIELD, ui.f.bodyBold), ui.grow())
            addView(arrow)
            setOnClickListener { Haptics.tick(this); onSignIn() }
        }
        poster.addView(button)
        val note = ui.text("Unofficial. Nothing leaves your phone except to Microsoft and UM.", 12.5f, SUB)
        poster.addView(note, ui.wide(top = 12f))
        val nudging = nudge(arrow, ui.dp(5f).toFloat())

        // The entrance, once the poster is on screen (an off-screen drawing shows it all, still).
        poster.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                poster.removeOnAttachStateChangeListener(this)
                if (!Motion.enabled) return
                rise(brand, 0, ui.dp(8f).toFloat())
                rule.pivotX = 0f
                rule.scaleX = 0f
                rule.animate().scaleX(1f).setStartDelay(60).setDuration(420).setInterpolator(Motion.SMOOTH).start()
                scene.play(delay = 200)
                lines.forEachIndexed { i, line -> rise(line, 260L + i * 80L, ui.dp(28f).toFloat()) }
                dividers.forEachIndexed { i, d ->
                    d.pivotY = 0f
                    d.scaleY = 0f
                    d.animate().scaleY(1f).setStartDelay(620L + i * 110L).setDuration(360).setInterpolator(Motion.SMOOTH).start()
                }
                numbers.forEachIndexed { i, n -> pop(n, 560L + i * 110L) }
                words.forEachIndexed { i, w -> rise(w, 620L + i * 110L, ui.dp(10f).toFloat()) }
                rise(button, 820, ui.dp(24f).toFloat())
                rise(note, 960, ui.dp(8f).toFloat())
                arrow.postDelayed(nudging, 1600)
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })
        poster.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                scene.stop()
                arrow.removeCallbacks(nudging)
            }
        })
        return poster
    }

    /** Rises [by] px into place and fades in, after [delay] ms. */
    private fun rise(v: View, delay: Long, by: Float) {
        v.translationY = by
        v.alpha = 0f
        v.animate().translationY(0f).setStartDelay(delay).spring(Motion.SETTLE).start()
        ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f).setDuration(240).apply { startDelay = delay }.start()
    }

    /** Pops in from small, overshooting a little, after [delay] ms; it grows from its bottom left, as type sits. */
    private fun pop(v: View, delay: Long) {
        v.alpha = 0f
        v.post {
            v.pivotX = 0f
            v.pivotY = v.height.toFloat()
            v.scaleX = 0.3f
            v.scaleY = 0.3f
            v.animate().scaleX(1f).scaleY(1f).setStartDelay(delay).spring(Motion.BOUNCY).start()
            ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f).setDuration(160).apply { startDelay = delay }.start()
        }
    }

    /** The arrow nudging toward where the button goes, twice; it runs again every 3.2 s once posted. */
    private fun nudge(arrow: View, by: Float): Runnable = object : Runnable {
        override fun run() {
            ObjectAnimator.ofFloat(arrow, View.TRANSLATION_X, 0f, by, 0f, by * 0.6f, 0f).setDuration(700).start()
            arrow.postDelayed(this, 3200)
        }
    }

    /**
     * The poster's column: press feedback for its button and the fight, and a statement that shrinks when
     * a short phone (or a large font) leaves the stage too little room, so nothing is pushed off the bottom.
     */
    private class Poster(context: Context) : LinearLayout(context) {
        private val presser = Motion.Presser(this)
        var stage: View? = null
        var statement: List<TextView> = emptyList()

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            presser.onTouch(e)
            return super.dispatchTouchEvent(e)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val dm = resources.displayMetrics
            val room = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            // As large as the longest line fits the width.
            val paint = Paint().apply { typeface = statement.firstOrNull()?.typeface }
            var px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, STATEMENT_SP, dm)
            paint.textSize = px
            val widest = STATEMENT.maxOf { paint.measureText(it) }
            if (room > 0 && widest > room) px *= room / widest
            // Then smaller (down to half) while the stage would be shorter than about a fifth of the screen.
            val least = MeasureSpec.getSize(heightMeasureSpec) / 5
            for (pass in 0 until 6) {
                statement.forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_PX, px) }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                val left = stage?.measuredHeight ?: break
                if (left >= least || MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) break
                px *= 0.88f
            }
        }
    }
}
