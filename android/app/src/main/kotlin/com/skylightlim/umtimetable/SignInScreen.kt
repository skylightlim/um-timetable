package com.skylightlim.umtimetable

import android.text.Layout
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout

/**
 * The first screen, laid out as in redesign 1t (one statement, the three steps, one button) in UM's
 * colours. It shows over the app while there's no sign-in and no timetable.
 *
 * One field and one accent: a deep UM blue at the app icon's hue (266°; the crest's #3E4095 sits at 278°
 * and reads violet full-bleed), type in tints of that blue, and the logo's yellow kept for the two
 * things that should catch the eye: the red-notched brand bar and the sign-in button.
 */
object SignInScreen {
    private const val FIELD = 0xFF132B7C.toInt()   // oklch(0.33 0.14 266)
    private const val TEXT = 0xFFF1F5FE.toInt()    // oklch(0.97 0.012 266), 11.6:1 on the field
    private const val SUB = 0xFFB8C7E9.toInt()     // oklch(0.83 0.05 266), 7.4:1
    private const val HAIR = 0xFF4E66A3.toInt()    // oklch(0.52 0.10 266), lines between the steps
    private const val YELLOW = 0xFFFFDE20.toInt()  // UM's yellow; the field on it is 9.5:1
    private const val RED = 0xFFED3237.toInt()     // UM's red, the bar's notch only

    fun build(ui: Ui, onSignIn: () -> Unit): View = ui.column().apply {
        setBackgroundColor(FIELD)
        isClickable = true // nothing under it takes a touch
        val pad = ui.dp(20)
        setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(pad + bars.left, ui.dp(28) + bars.top, pad + bars.right, ui.dp(24) + bars.bottom)
            insets
        }
        setPadding(pad, ui.dp(28), pad, ui.dp(24))

        addView(ui.label("UM Timetable", SUB, 11f))
        // The rule starts red, as the U and M of the wordmark do.
        addView(ui.row().apply {
            addView(View(context).apply { setBackgroundColor(RED) }, LinearLayout.LayoutParams(ui.dp(28), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(View(context).apply { setBackgroundColor(YELLOW) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(2)).apply { topMargin = ui.dp(10) })
        // The statement takes the room the rest leaves, at up to 64sp: on a short phone (640dp tall) it
        // shrinks rather than push the last line off the screen. It sits at the bottom of that room, on
        // the steps and the button it leads to; the brand line stays alone at the top. Balanced breaks
        // keep a lone word ("place.") off the last line.
        addView(ui.text("Sign in once.\nYour whole semester in one place.", 64f, TEXT, ui.f.sign).apply {
            setLineSpacing(0f, 0.92f)
            setAutoSizeTextTypeUniformWithConfiguration(28, 64, 1, TypedValue.COMPLEX_UNIT_SP)
            gravity = Gravity.BOTTOM
            breakStrategy = Layout.BREAK_STRATEGY_BALANCED
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = ui.dp(28); bottomMargin = ui.dp(24) })

        // The three steps, with lines between them only.
        addView(ui.row(center = false).apply {
            listOf("SiswaMail", "TimeEdit week", "Due, News, Grades").forEachIndexed { i, label ->
                if (i > 0) addView(View(context).apply { setBackgroundColor(HAIR) }, LinearLayout.LayoutParams(ui.dp(1.5f), ViewGroup.LayoutParams.MATCH_PARENT))
                addView(ui.column().apply {
                    setPadding(if (i == 0) 0 else ui.dp(10), ui.dp(10), ui.dp(8), ui.dp(10))
                    addView(ui.text("${i + 1}", 20f, TEXT, ui.f.bodyBold))
                    addView(ui.text(label, 13.5f, SUB), ui.wide(top = 2f))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }, ui.wide(bottom = 20f))

        addView(ui.row(gap = 12f).apply {
            background = ui.pressable(ui.shape(YELLOW, 14f), 14f)
            minimumHeight = ui.dp(56)
            setPadding(ui.dp(18), 0, ui.dp(18), 0)
            contentDescription = "Sign in with SiswaMail"
            addView(ui.text("Sign in with SiswaMail", 16f, FIELD, ui.f.bodyBold), ui.grow())
            addView(ui.icon(R.drawable.ic_arrow, FIELD, 18f))
            setOnClickListener { Haptics.tick(this); onSignIn() }
        })
        addView(ui.text("Unofficial. Nothing leaves your phone except to Microsoft and UM.", 12.5f, SUB), ui.wide(top = 12f))
    }
}
