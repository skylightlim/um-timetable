package com.skylightlim.umtimetable

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout

/**
 * The first screen, laid out as in redesign 1t (one statement, the three steps, one button) in the
 * colours of UM's crest and wordmark. It shows over the app while there's no sign-in and no timetable.
 */
object SignInScreen {
    // From UM's logo: the shield's blue, the tigers' yellow, the hibiscus red, the rim's gold.
    private const val BLUE = 0xFF3E4095.toInt()
    private const val YELLOW = 0xFFFFDE20.toInt()
    private const val RED = 0xFFED3237.toInt()
    private const val GOLD = 0xFFFF9F2D.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    fun build(ui: Ui, onSignIn: () -> Unit): View = ui.column().apply {
        setBackgroundColor(BLUE)
        isClickable = true // nothing under it takes a touch
        val pad = ui.dp(20)
        setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(pad + bars.left, ui.dp(28) + bars.top, pad + bars.right, ui.dp(24) + bars.bottom)
            insets
        }
        setPadding(pad, ui.dp(28), pad, ui.dp(24))

        addView(ui.label("UM Timetable", YELLOW, 11f))
        // The rule starts red, as the U and M of the wordmark do.
        addView(ui.row().apply {
            addView(View(context).apply { setBackgroundColor(RED) }, LinearLayout.LayoutParams(ui.dp(28), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(View(context).apply { setBackgroundColor(YELLOW) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(2)).apply { topMargin = ui.dp(10) })
        val first = "Sign in once. "
        val statement = SpannableString(first + "Your whole semester in one place.").apply {
            setSpan(ForegroundColorSpan(YELLOW), first.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        addView(ui.text(statement, 64f, WHITE, ui.f.sign).apply { setLineSpacing(0f, 0.92f) }, ui.wide(top = 28f))
        addView(ui.space(), LinearLayout.LayoutParams(0, 0, 1f))

        // The three steps, with gold lines between them only.
        addView(ui.row(center = false).apply {
            listOf("SiswaMail", "TimeEdit week", "Due, News, Grades").forEachIndexed { i, label ->
                if (i > 0) addView(View(context).apply { setBackgroundColor(GOLD) }, LinearLayout.LayoutParams(ui.dp(1.5f), ViewGroup.LayoutParams.MATCH_PARENT))
                addView(ui.column().apply {
                    setPadding(if (i == 0) 0 else ui.dp(10), ui.dp(10), ui.dp(8), ui.dp(10))
                    addView(ui.text("${i + 1}", 20f, YELLOW, ui.f.bodyBold))
                    addView(ui.text(label, 13.5f, WHITE), ui.wide(top = 2f))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }, ui.wide(bottom = 20f))

        addView(ui.row(gap = 12f).apply {
            background = ui.pressable(ui.shape(YELLOW, 14f), 14f)
            minimumHeight = ui.dp(56)
            setPadding(ui.dp(18), 0, ui.dp(18), 0)
            contentDescription = "Sign in with SiswaMail"
            addView(ui.text("Sign in with SiswaMail", 16f, BLUE, ui.f.bodyBold), ui.grow())
            addView(ui.icon(R.drawable.ic_arrow, BLUE, 18f))
            setOnClickListener { Haptics.tick(this); onSignIn() }
        })
        addView(ui.text("Unofficial. Nothing leaves your phone except to Microsoft and UM.", 12.5f, 0xD9FFFFFF.toInt()), ui.wide(top = 12f))
    }
}
