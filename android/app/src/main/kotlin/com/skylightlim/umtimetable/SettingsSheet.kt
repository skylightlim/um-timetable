package com.skylightlim.umtimetable

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * Settings, as in redesign 1p: the theme, the timetable imports, notifications, vibration and the
 * Spectrum account. They're a tab of their own (the last one), drawn as a page under the top bar's
 * "Settings"; they used to be a sheet opened from a long press on the title, and before that sat under
 * the course list.
 */
object SettingsSheet {
    /** A row's padding above and below its text. */
    private const val ROW_PAD = 12f

    /** What a redrawn page shows moving: the look's pill settling from [lookAt] (where it was); the chosen theme's dots hopping ([hop]). */
    data class Moved(val lookAt: Float? = null, val hop: Boolean = false)

    /** How long the look's pill glides in the old colours before the new look spreads: by then it has landed. */
    private const val LAND_MS = 380L

    /**
     * The Settings tab's page. [restyle] makes a change of look or theme, given what was tapped and what
     * the redrawn page should show moving; [moved] is that, when this is the redrawn page. [openLog] opens
     * auto attendance's log ([AttendanceLogPage]).
     */
    fun build(
        h: SpectrumHost,
        context: Context,
        signedIn: Boolean = SpectrumStore.signedIn(context),
        moved: Moved? = null,
        restyle: (View, Moved, () -> Unit) -> Unit = { _, _, change -> change() },
        openLog: () -> Unit = {},
    ): View {
        val ui = h.ui
        val p = ui.p
        // The page's name is in the top bar; the content already has the 16dp margins.
        val page = ui.column().apply { setPadding(0, ui.dp(2), 0, ui.dp(8)) }

        fun rule() = page.addView(View(context).apply { setBackgroundColor(p.line) }, ui.wide().apply { height = 1 })
        // Every label sits the same 24dp under what's above it. Rows carry 12dp of their own padding
        // (it's part of what can be tapped), so after rows the label's margin is the other 12dp.
        var afterRows = false
        fun label(text: String, top: Float = 24f, bottom: Float = 10f) {
            page.addView(ui.label(text, p.muted, 10.5f), ui.wide(top = if (afterRows) top - ROW_PAD else top, bottom = bottom))
            afterRows = false
        }
        /** A section of rows, with lines between them only (none under the label or after the last). */
        fun section(text: String, rows: List<View>) {
            label(text, bottom = 0f) // the first row's own padding is the gap
            rows.forEachIndexed { i, r ->
                if (i > 0) rule()
                page.addView(r)
            }
            afterRows = true
        }
        fun row(title: String, sub: String?, end: View?, onClick: (() -> Unit)?) = ui.row(gap = 12f).apply {
            minimumHeight = ui.dp(48)
            setPadding(0, ui.dp(ROW_PAD), 0, ui.dp(ROW_PAD))
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

        // The look: it spreads over the app from the option tapped.
        label("Appearance", top = 0f)
        var landing: Runnable? = null
        page.addView(ui.segmented(Look.entries.map { it.label }, h.look.ordinal, fill = true, from = moved?.lookAt) { i, v ->
            // The pill lands first, then the new look spreads from it, the redrawn pill carrying on from where
            // it is. Spreading at once left the old pill standing in the old page's picture, behind the glide.
            landing?.let(v::removeCallbacks)
            val spread = Runnable {
                landing = null
                restyle(v, Moved(lookAt = (v.parent as? SegmentPill)?.position)) { h.setLook(Look.entries[i]) }
            }
            landing = spread
            if (Motion.enabled) v.postDelayed(spread, LAND_MS) else spread.run()
        })

        // The accent scheme: a swatch of each, the chosen one outlined; it spreads as the look does, from the
        // swatch, whose dots then hop.
        label("Theme")
        page.addView(ui.row(gap = 8f).apply {
            for (scheme in Scheme.entries) {
                val chosen = scheme == h.scheme
                addView(ui.column().apply {
                    gravity = Gravity.CENTER
                    minimumHeight = ui.dp(68)
                    setPadding(0, ui.dp(10), 0, ui.dp(10))
                    background = ui.pressable(ui.shape(p.surface2, 12f, if (chosen) p.text else 0, 2f), 12f)
                    contentDescription = "${scheme.label} theme${if (chosen) ", chosen" else ""}"
                    // The theme's accent, then four of its course colours.
                    // The dots hop up into the swatch's padding: neither its bounds nor its padding cut them off.
                    clipChildren = false
                    clipToPadding = false
                    val dots = ui.row(gap = 4f).apply {
                        clipChildren = false
                        addView(View(context).apply { background = ui.shape(scheme.applyTo(p).accent, 99f) }, LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { marginEnd = ui.dp(2) })
                        for (c in scheme.courseColors.take(4)) addView(View(context).apply { background = ui.shape(c, 99f) }, LinearLayout.LayoutParams(ui.dp(9), ui.dp(9)))
                    }
                    addView(dots, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                    // Just chosen: its dots jump one after another, left to right, as the new colours spread from here.
                    if (chosen && moved?.hop == true) for (i in 0 until dots.childCount) Motion.hop(dots.getChildAt(i), 90L + i * 70L, ui.dp(if (i == 0) 9f else 8f).toFloat())
                    addView(ui.text(scheme.label, 13f, if (chosen) p.text else p.muted, ui.f.bodySemi), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(6) })
                    if (!chosen) setOnClickListener { Haptics.tick(this); restyle(this, Moved(hop = true)) { h.setScheme(scheme) } }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        })

        val tt = h.timetable
        section("Timetable", listOfNotNull(
            row("Get timetable from TimeEdit", if (tt.sessions.isEmpty()) "No timetable yet" else listOfNotNull("${tt.sessions.size} classes a week", tt.term).joinToString(" · "), chevron()) { h.openTimeEdit() },
            row("Import from MAYA instead", "If TimeEdit can't: you sign in to MAYA yourself", chevron()) { h.openMaya() },
            // Week's grid (and plans) to midnight, for evening plans; classes end by 19:00 anyway.
            row("Week until midnight", "Week's grid runs to 00:00 instead of 19:00, for plans in the evening",
                Toggle(ui, DayEnd.late(context)) { on -> DayEnd.setLate(context, on) }.apply { contentDescription = "Week until midnight" }, null),
            if (signedIn) occurrences(h, context, ::row) else null,
        ))

        // Android owns the switch: off, it asks; on, it opens the app's notification settings.
        // Android 12 can't ask (no permission there), so off opens the settings too.
        val allowed = Notify.allowed(context)
        val ask = !allowed && Build.VERSION.SDK_INT >= 33
        section("Notifications", listOf(
            row("Deadlines, marks and news", null, ui.row(gap = 10f).apply {
                addView(ui.text(if (allowed) "On" else "Off", 14f, if (allowed) p.accentText else p.muted, ui.f.bodySemi))
                addView(chevron())
            }) { if (ask) h.enableNotifications() else h.openAppSettings() },
            classReminders(ui, context, ::row, ::chevron) { if (ask) h.enableNotifications() },
        ))

        // Marking attendance by itself, as each class starts (AutoAttendance), and its log: each try and what went wrong.
        if (signedIn) section("Attendance", listOf(
            row("Mark my attendance", "Settles your attendance for you: as each class starts, the app marks you present on Spectrum. If it can't, a notification says why.",
                Toggle(ui, AutoAttendance.enabled(context)) { on -> AutoAttendance.setEnabled(context, on) }.apply { contentDescription = "Mark my attendance" }, null),
            row("Attendance log", AttendanceLogPage.summary(context), chevron()) { openLog() },
        ))

        label("Vibration")
        page.addView(ui.segmented(Haptics.Level.entries.map { it.label }, Haptics.level(context).ordinal, fill = true) { i, _ -> Haptics.setLevel(context, Haptics.Level.entries[i]) })

        // The app's version, and the newest one on GitHub (Updater).
        section("About", listOf(
            row("Check for updates", "Version ${Updater.installed(context).second}", chevron()) { h.checkForUpdates() },
        ))

        section("Account", listOf(
            if (signedIn) {
                lateinit var out: View
                out = button("Sign out") { h.signOut(out) }
                row(SpectrumScreens.accountName(context).ifBlank { "Signed in" }, null, out, null)
            } else {
                row("Not signed in", "Sign in to Spectrum with your SiswaMail for courses, deadlines, marks and news.", button("Sign in") { h.signIn() }, null)
            },
        ))
        return page
    }

    /**
     * Class reminders ([ClassReminders]): the row says how long before; a tap flies a card out of it with
     * the choices on a wheel. Choosing one with notifications off asks for them ([allow]).
     */
    private fun classReminders(
        ui: Ui,
        context: Context,
        row: (String, String?, View?, (() -> Unit)?) -> LinearLayout,
        chevron: () -> View,
        allow: () -> Unit,
    ): View {
        val p = ui.p
        val value = ui.text(ClassReminders.label(context), 14f, if (ClassReminders.before(context) != null) p.accentText else p.muted, ui.f.bodySemi)
        lateinit var line: LinearLayout
        line = row("Class reminders", "Before each class, with its time and room", ui.row(gap = 10f).apply {
            addView(value)
            addView(chevron())
        }) {
            val choices = ClassReminders.CHOICES
            var pick = choices.indexOfFirst { it.first == ClassReminders.before(context) }.coerceAtLeast(0)
            lateinit var close: (then: (() -> Unit)?) -> Unit
            val card = ConfirmDialog.frame(ui).apply {
                addView(ui.text("Class reminders", 30f, p.text, ui.f.sign))
                addView(ui.text("A notification before each of your classes, with its time and room. None on holidays or in the break.", 15f, p.muted), ui.wide(top = 2f))
                addView(ui.column().apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(ui.wheel(choices.map { it.second }, pick, rows = 5) { pick = it }.apply { name = "Class reminders" },
                        LinearLayout.LayoutParams(ui.dp(180), LinearLayout.LayoutParams.WRAP_CONTENT))
                }, ui.wide(top = 18f))
                addView(ui.row(gap = 8f).apply {
                    addView(ConfirmDialog.button(ui, "Cancel", p.surface2, p.text) { close(null) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(ConfirmDialog.button(ui, "Choose", p.accent, p.accentInk) {
                        val minutes = choices[pick].first
                        if (minutes == ClassReminders.before(context)) return@button close(null)
                        ClassReminders.setBefore(context, minutes)
                        value.text = choices[pick].second
                        value.setTextColor(if (minutes != null) p.accentText else p.muted)
                        close {
                            Motion.pop(value, 0.6f)
                            if (minutes != null && !Notify.allowed(context)) allow()
                        }
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                }, ui.wide(top = 20f))
            }
            close = ConfirmDialog.present(ui, card, from = Motion.onScreen(line), backAlways = true)
        }
        return line
    }

    /** Whether the Occurrences fold is open: it stays so when the page is redrawn (back from TimeEdit). */
    var occurrencesOpen = false

    /**
     * The card for choosing [code]'s occurrence: the course's code large in its colour and its name, its
     * occurrences on a wheel ([mine], the one in use, chosen), what Spectrum says ([theirs]), then Cancel
     * and Choose. [done] gets the occurrence chosen, or null for Cancel.
     */
    fun occurrenceCard(ui: Ui, code: String, name: String, color: Int, options: List<Int>, mine: Int, theirs: Int?, done: (Int?) -> Unit): View {
        val p = ui.p
        var pick = mine
        fun says(occ: Int) = when {
            theirs == null -> "Spectrum doesn't say which is yours."
            occ == theirs -> "Your group on Spectrum."
            else -> "Spectrum puts you in Occ $theirs."
        }
        val note = ui.text(says(mine), 13f, p.muted).apply { gravity = Gravity.CENTER }
        return ConfirmDialog.frame(ui).apply {
            addView(ui.text(code, 30f, p.courseText(color), ui.f.sign))
            addView(ui.text(name, 15f, p.muted), ui.wide(top = 2f))
            addView(ui.column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(ui.wheel(options.map { "Occ $it" }, options.indexOf(mine).coerceAtLeast(0), rows = 5) { i ->
                    pick = options[i]
                    note.text = says(pick)
                }.apply { this.name = "$code occurrence" }, LinearLayout.LayoutParams(ui.dp(150), LinearLayout.LayoutParams.WRAP_CONTENT))
            }, ui.wide(top = 18f))
            addView(note, ui.wide(top = 12f))
            addView(ui.row(gap = 8f).apply {
                addView(ConfirmDialog.button(ui, "Cancel", p.surface2, p.text) { done(null) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(ConfirmDialog.button(ui, "Choose", p.accent, p.accentInk) { done(pick) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }, ui.wide(top = 20f))
        }
    }

    /**
     * Occurrences, a fold in Timetable: each course with its occurrence, Spectrum's unless the user chose
     * another; a tap on a course flies a card out of it ([occurrenceCard]) with a wheel of those TimeEdit
     * lists for it, over the page, blurred. A choice is saved at once; the timetable keeps
     * its classes until it's got again from TimeEdit (the button under the courses), whose answer is
     * shown before it's used. [row] makes the page's rows.
     */
    private fun occurrences(h: SpectrumHost, context: Context, row: (String, String?, View?, (() -> Unit)?) -> LinearLayout): View? {
        val ui = h.ui
        val p = ui.p
        val chosen = TimeEditLookup.occurrences(context)
        if (chosen.length() == 0) return null
        val spectrum = SpectrumStore.spectrumOccurrences(context)
        val offered = SpectrumStore.occurrenceOptions(context)
        val names = SpectrumStore.array(context, "courses").objects().associate { it.optString("code") to it.optString("name") }
        // In the timetable's order, then any course only Spectrum has.
        val codes = (h.timetable.courses.map { it.code } + chosen.keys().asSequence()).distinct().filter { chosen.has(it) }
        fun inTimetable(code: String) = h.timetable.courses.firstOrNull { it.code == code }?.occ
        fun stale() = codes.any { code -> inTimetable(code).let { it != null && it != chosen.optInt(code) } }
        fun summary() = SpectrumStore.occurrencePicks(context).length().let { n -> if (n == 0) "Yours from Spectrum" else "$n chosen by you" }

        // A 16dp box: turned on its side the 8×16 chevron is 16dp wide.
        val chevron = Motion.chevron(ui, occurrencesOpen, p.faint).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)) }
        val head = row("Occurrences", summary(), chevron, null)
        val sub = (head.getChildAt(0) as LinearLayout).getChildAt(1) as android.widget.TextView
        val fold = ui.column().apply { visibility = if (occurrencesOpen) View.VISIBLE else View.GONE; setPadding(0, ui.dp(2), 0, ui.dp(12)) }
        val update = ui.text("Update timetable from TimeEdit", 14f, p.accentInk, ui.f.bodySemi).apply {
            gravity = Gravity.CENTER
            minHeight = ui.dp(44)
            background = ui.pressable(ui.shape(p.accent, 12f), 12f)
            visibility = if (stale()) View.VISIBLE else View.GONE
            setOnClickListener { h.openTimeEdit() }
        }
        // Each course a row with the occurrence in use; a tap flies a card out of it with that course's wheel.
        for ((n, code) in codes.withIndex()) {
            val course = h.timetable.courses.firstOrNull { it.code == code }
            val name = course?.short ?: names[code].orEmpty()
            val theirs = spectrum.optInt(code).takeIf { it > 0 }
            val known = offered.optJSONArray(code)?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty()
            // Said only when the choice isn't Spectrum's: the fold's head already says the rest are.
            val note = ui.text("", 12.5f, p.muted)
            fun showNote(occ: Int) {
                note.text = if (theirs == null || occ == theirs) "" else "Spectrum: $theirs"
                note.visibility = if (note.text.isEmpty()) View.GONE else View.VISIBLE
            }
            showNote(chosen.getInt(code))
            val value = ui.text("Occ ${chosen.getInt(code)}", 14f, p.text, ui.f.bodySemi)
            if (n > 0) fold.addView(View(context).apply { setBackgroundColor(p.line) }, ui.wide().apply { height = 1 })
            fold.addView(ui.row(gap = 12f).apply {
                minimumHeight = ui.dp(56)
                setPadding(0, ui.dp(10), 0, ui.dp(10))
                background = ui.pressable(ui.shape(Color.TRANSPARENT, 8f), 8f)
                contentDescription = "$code, $name. Change its occurrence."
                addView(ui.column().apply {
                    addView(ui.text(code, 13f, p.courseText(course?.color ?: p.muted), ui.f.monoBold))
                    addView(ui.text(name, 14f, p.text, ui.f.bodySemi).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, ui.wide(top = 2f))
                    addView(note, ui.wide(top = 3f))
                }, ui.grow())
                addView(value)
                addView(ui.icon(R.drawable.ic_chevron, p.faint).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(8), ui.dp(16)) })
                setOnClickListener { row ->
                    val mine = chosen.getInt(code)
                    // TimeEdit's list (and the ones in use, in case it left one out); before any lookup, 1 to 30.
                    val options = (known.ifEmpty { (1..30).toList() } + listOfNotNull(mine, theirs)).distinct().sorted()
                    lateinit var close: (then: (() -> Unit)?) -> Unit
                    val card = occurrenceCard(ui, code, name, course?.color ?: p.muted, options, mine, theirs) { occ ->
                        if (occ == null || occ == mine) return@occurrenceCard close(null)
                        SpectrumStore.pickOccurrence(context, code, occ)
                        chosen.put(code, occ)
                        value.text = "Occ $occ"
                        showNote(occ)
                        sub.text = summary()
                        update.visibility = if (stale()) View.VISIBLE else View.GONE
                        // Back into its row, whose value then pops.
                        close { Motion.pop(value, 0.6f) }
                    }
                    close = ConfirmDialog.present(ui, card, from = Motion.onScreen(row), backAlways = true)
                }
            })
        }
        fold.addView(update, ui.wide(top = 16f))
        head.background = ui.pressable(ui.shape(Color.TRANSPARENT, 8f), 8f)
        head.setOnClickListener {
            Motion.resize(head)
            occurrencesOpen = !occurrencesOpen
            fold.visibility = if (occurrencesOpen) View.VISIBLE else View.GONE
            Motion.turn(chevron, occurrencesOpen)
        }
        return ui.column().apply {
            addView(head)
            addView(fold)
        }
    }
}
