package com.skylightlim.umtimetable

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Settings, as in redesign 1p: a sheet from the top bar's settings button with the theme, the timetable
 * imports, notifications, vibration and the Spectrum account. They used to sit under the course list.
 */
object SettingsSheet {
    fun show(h: SpectrumHost, context: Context) {
        lateinit var bottom: BottomSheet
        val sheet = build(h, context) { then -> bottom.close(then) }
        bottom = BottomSheet(context, sheet, h.ui.p.dark)
        bottom.show()
    }

    /** The sheet's content; [close] slides the sheet away and runs what it's given. */
    fun build(h: SpectrumHost, context: Context, close: ((() -> Unit)?) -> Unit): View {
        val ui = h.ui
        val p = ui.p
        val sheet = ui.column().apply {
            background = GradientDrawable().apply {
                setColor(p.surface)
                val r = ui.dp(22f).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(24))
        }
        sheet.addView(View(context).apply { background = ui.shape(p.line, 4f) }, LinearLayout.LayoutParams(ui.dp(40), ui.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ui.dp(10) })
        sheet.addView(ui.row().apply {
            addView(ui.text("Settings", 34f, p.text, ui.f.sign), ui.grow())
            addView(FrameLayout(context).apply {
                // A 34dp circle inside a 48dp touch area, as on the class sheet.
                background = android.graphics.drawable.InsetDrawable(ui.pressable(ui.shape(p.surface2, 99f), 99f), ui.dp(7))
                contentDescription = "Close"
                addView(ui.icon(R.drawable.ic_close, p.muted, 16f), FrameLayout.LayoutParams(ui.dp(16), ui.dp(16), Gravity.CENTER))
                setOnClickListener { close(null) }
            }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { marginEnd = -ui.dp(7) })
        })

        fun rule() = sheet.addView(View(context).apply { setBackgroundColor(p.line) }, ui.wide().apply { height = 1 })
        fun label(text: String, top: Float = 22f) = sheet.addView(ui.label(text, p.muted, 10.5f), ui.wide(top = top, bottom = 8f))
        /** A section of ruled rows: a line under its label and between its rows, none after the last. */
        fun section(text: String, rows: List<View>) {
            sheet.addView(ui.label(text, p.muted, 10.5f).apply { setPadding(0, 0, 0, ui.dp(6)) }, ui.wide(top = 22f))
            for (r in rows) {
                rule()
                sheet.addView(r)
            }
        }
        fun row(title: String, sub: String?, end: View?, onClick: (() -> Unit)?) = ui.row(gap = 12f).apply {
            minimumHeight = ui.dp(60)
            setPadding(0, ui.dp(10), 0, ui.dp(10))
            addView(ui.column().apply {
                addView(ui.text(title, 15f, p.text, ui.f.bodySemi))
                sub?.let { addView(ui.text(it, 12.5f, p.muted), ui.wide(top = 2f)) }
            }, ui.grow())
            end?.let { addView(it) }
            if (onClick != null) {
                background = ui.pressable(ui.shape(Color.TRANSPARENT, 8f), 8f)
                setOnClickListener { onClick() }
            }
        }
        fun chevron() = ui.icon(R.drawable.ic_chevron, p.faint).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(8), ui.dp(16)) }
        fun button(text: String, onClick: () -> Unit) = ui.text(text, 14f, p.accentText, ui.f.bodySemi).apply {
            gravity = Gravity.CENTER
            minHeight = ui.dp(44)
            setPadding(ui.dp(16), 0, ui.dp(16), 0)
            background = ui.pressable(ui.shape(p.surface2, 12f), 12f)
            setOnClickListener { onClick() }
        }

        // The theme: the sheet goes, and the new theme spreads from the settings button.
        label("Appearance", top = 14f)
        sheet.addView(ui.segmented(Look.entries.map { it.label }, h.look.ordinal, fill = true) { i -> close { h.setLook(Look.entries[i]) } })

        // The accent scheme: a swatch of each, the chosen one outlined; the sheet goes and it spreads as the theme does.
        label("Theme")
        sheet.addView(ui.row(gap = 8f).apply {
            for (scheme in Scheme.entries) {
                val chosen = scheme == h.scheme
                addView(ui.column().apply {
                    gravity = Gravity.CENTER
                    minimumHeight = ui.dp(68)
                    setPadding(0, ui.dp(10), 0, ui.dp(10))
                    background = ui.pressable(ui.shape(p.surface2, 12f, if (chosen) p.text else 0, 2f), 12f)
                    contentDescription = "${scheme.label} theme${if (chosen) ", chosen" else ""}"
                    // The theme's accent, then four of its course colours.
                    addView(ui.row(gap = 4f).apply {
                        addView(View(context).apply { background = ui.shape(scheme.applyTo(p).accent, 99f) }, LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { marginEnd = ui.dp(2) })
                        for (c in scheme.courseColors.take(4)) addView(View(context).apply { background = ui.shape(c, 99f) }, LinearLayout.LayoutParams(ui.dp(9), ui.dp(9)))
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                    addView(ui.text(scheme.label, 13f, if (chosen) p.text else p.muted, ui.f.bodySemi), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(6) })
                    if (!chosen) setOnClickListener { Haptics.tick(this); close { h.setScheme(scheme) } }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        })

        val tt = h.timetable
        section("Timetable", listOf(
            row("Get timetable from TimeEdit", if (tt.sessions.isEmpty()) "No timetable yet" else listOfNotNull("${tt.sessions.size} classes a week", tt.term).joinToString(" · "), chevron()) { close { h.openTimeEdit() } },
            row("Import from MAYA instead", "If TimeEdit can't: you sign in to MAYA yourself", chevron()) { close { h.openMaya() } },
        ))

        // Android owns the switch: off, it asks; on, it opens the app's notification settings.
        val allowed = Notify.allowed(context)
        section("Notifications", listOf(
            row("Deadlines, marks and news", null, ui.row(gap = 10f).apply {
                addView(ui.text(if (allowed) "On" else "Off", 14f, if (allowed) p.accentText else p.muted, ui.f.bodySemi))
                addView(chevron())
            }) { close { if (allowed) h.openAppSettings() else h.enableNotifications() } },
        ))

        label("Vibration")
        sheet.addView(ui.segmented(Haptics.Level.entries.map { it.label }, Haptics.level(context).ordinal, fill = true) { i -> Haptics.setLevel(context, Haptics.Level.entries[i]) })

        section("Account", listOf(
            if (SpectrumStore.signedIn(context)) {
                row(SpectrumScreens.accountName(context).ifBlank { "Signed in" }, null, button("Sign out") { close { h.signOut() } }, null)
            } else {
                row("Not signed in", "Sign in to Spectrum with your SiswaMail for courses, deadlines, marks and news.", button("Sign in") { close { h.signIn() } }, null)
            },
        ))
        return sheet
    }
}
