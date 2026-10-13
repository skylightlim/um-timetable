package com.skylightlim.umtimetable

import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import java.time.LocalDate

/**
 * The first screen, as in redesign 1t: a yellow poster with one statement, the three steps and one
 * button. It shows over the app while there's no sign-in and no timetable yet.
 */
object SignInScreen {
    fun build(ui: Ui, term: String?, onSignIn: () -> Unit, onMaya: () -> Unit): View {
        val p = ui.p
        val ink = p.accentInk
        val line = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(1.5f)) }
        return ui.column().apply {
            setBackgroundColor(p.accent)
            isClickable = true // nothing under it takes a touch
            val pad = ui.dp(20)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(pad + bars.left, ui.dp(28) + bars.top, pad + bars.right, ui.dp(24) + bars.bottom)
                insets
            }
            setPadding(pad, ui.dp(28), pad, ui.dp(24))

            addView(ui.row().apply {
                addView(ui.label("UM Timetable", ink, 11f), ui.grow())
                (term ?: AcademicCalendar.semesterName(LocalDate.now()))?.let { addView(ui.label(it, ink, 11f)) }
            })
            addView(View(context).apply { setBackgroundColor(ink) }, line().apply { topMargin = ui.dp(10) })
            addView(ui.text("Sign in once. Get your whole week.", 64f, ink, ui.f.sign).apply { setLineSpacing(0f, 0.92f) }, ui.wide(top = 28f))
            addView(ui.space(), LinearLayout.LayoutParams(0, 0, 1f))

            // The three steps, with lines between them only.
            addView(ui.row(center = false).apply {
                listOf("SiswaMail", "TimeEdit week", "Due, News, Grades").forEachIndexed { i, label ->
                    if (i > 0) addView(View(context).apply { setBackgroundColor(ink) }, LinearLayout.LayoutParams(ui.dp(1.5f), ViewGroup.LayoutParams.MATCH_PARENT))
                    addView(ui.column().apply {
                        setPadding(if (i == 0) 0 else ui.dp(10), ui.dp(10), ui.dp(8), ui.dp(10))
                        addView(ui.text("${i + 1}", 20f, ink, ui.f.bodyBold))
                        addView(ui.text(label, 13.5f, ink), ui.wide(top = 2f))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
            }, ui.wide(bottom = 20f))

            addView(ui.row(gap = 12f).apply {
                background = ui.pressable(ui.shape(ink, 14f), 14f)
                minimumHeight = ui.dp(56)
                setPadding(ui.dp(18), 0, ui.dp(18), 0)
                contentDescription = "Sign in with SiswaMail"
                addView(ui.text("Sign in with SiswaMail", 16f, p.accent, ui.f.bodySemi), ui.grow())
                addView(ui.icon(R.drawable.ic_arrow, p.accent, 18f))
                setOnClickListener { Haptics.tick(this); onSignIn() }
            })
            addView(ui.text("Unofficial. Nothing leaves your phone except to Microsoft and UM.", 12.5f, ink), ui.wide(top = 12f))
            // The fallback when TimeEdit can't be reached: MAYA, signed into by hand.
            addView(ui.text("Import from MAYA instead", 13.5f, ink, ui.f.bodySemi).apply {
                paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                minHeight = ui.dp(44)
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { onMaya() }
            })
        }
    }
}
