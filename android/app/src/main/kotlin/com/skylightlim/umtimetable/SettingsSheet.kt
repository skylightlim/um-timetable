package com.skylightlim.umtimetable

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * The Settings tab's page: three groups of one-line rows, each saying what's chosen, that fit one screen.
 * What takes more than a glance is one level down: a card flies out of its row (the timetable's sources,
 * a wheel of choices), and the occurrences fold open.
 */
object SettingsSheet {
    /** What a redrawn page shows moving: the look's pill settling from [lookAt] (where it was); the theme's dots hopping ([hop]). */
    data class Moved(val lookAt: Float? = null, val hop: Boolean = false)

    /** How long the look's pill glides in the old colours before the new look spreads: by then it has landed. */
    private const val LAND_MS = 380L

    /** A row: its title (with a [note] under it, if it needs one), then what's at its end. A tap runs [onClick]. */
    private fun row(ui: Ui, title: String, note: String? = null, end: View? = null, onClick: (() -> Unit)? = null) = ui.row(gap = 12f).apply {
        minimumHeight = ui.dp(48)
        setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(4))
        addView(ui.column().apply {
            addView(ui.text(title, 15f, ui.p.text, ui.f.bodySemi))
            note?.let { addView(ui.text(it, 12.5f, ui.p.muted), ui.wide(top = 2f)) }
        }, ui.grow())
        end?.let { addView(it) }
        if (onClick != null) {
            background = ui.pressable(ui.shape(Color.TRANSPARENT, 0f), 0f)
            setOnClickListener { onClick() }
        }
    }

    /** What a row has chosen, in the accent when it's on, and a chevron: there's more behind the row. */
    private fun chosen(ui: Ui, value: android.widget.TextView, chevron: View = chevron(ui)) = ui.row(gap = 10f).apply {
        addView(value)
        addView(chevron)
    }

    private fun value(ui: Ui, text: String, on: Boolean = false) = ui.text(text, 14f, if (on) ui.p.accentText else ui.p.muted, ui.f.body, lines = 1)

    private fun chevron(ui: Ui) = ui.icon(R.drawable.ic_chevron, ui.p.faint).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(8), ui.dp(16)) }

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

        /** A group: its name, then its rows on one card, with lines between them only. */
        fun group(name: String, rows: List<View>) {
            page.addView(ui.label(name, p.muted, 10.5f), ui.wide(top = if (page.childCount == 0) 0f else 18f, bottom = 8f).apply { marginStart = ui.dp(14) })
            page.addView(ui.column().apply {
                background = ui.shape(p.surface, 16f)
                clipToOutline = true // a row's ripple keeps to the card's corners
                rows.forEachIndexed { i, r ->
                    if (i > 0) addView(View(context).apply { setBackgroundColor(p.line) }, ui.wide().apply { height = 1; marginStart = ui.dp(14) })
                    addView(r)
                }
            }, ui.wide())
        }

        // The look: it spreads over the app from the option tapped.
        var landing: Runnable? = null
        val look = ui.segmented(Look.entries.map { it.label }, h.look.ordinal, from = moved?.lookAt) { i, v ->
            // The pill lands first, then the new look spreads from it, the redrawn pill carrying on from where
            // it is. Spreading at once left the old pill standing in the old page's picture, behind the glide.
            landing?.let(v::removeCallbacks)
            val spread = Runnable {
                landing = null
                restyle(v, Moved(lookAt = (v.parent as? SegmentPill)?.position)) { h.setLook(Look.entries[i]) }
            }
            landing = spread
            if (Motion.enabled) v.postDelayed(spread, LAND_MS) else spread.run()
        }

        // The accent scheme: a dot of each, the chosen one ringed; it spreads as the look does, from the dot,
        // then the dots hop one after another, the chosen one first.
        val themes = ui.row(gap = 4f).apply {
            clipChildren = false
            val first = Scheme.entries.indexOf(h.scheme)
            for ((i, scheme) in Scheme.entries.withIndex()) {
                val isChosen = scheme == h.scheme
                addView(android.widget.FrameLayout(context).apply {
                    clipChildren = false
                    background = ui.pressable(ui.shape(Color.TRANSPARENT, 99f, if (isChosen) p.text else 0, 2f), 99f)
                    contentDescription = "${scheme.label} theme${if (isChosen) ", chosen" else ""}"
                    val dot = View(context).apply { background = ui.shape(scheme.applyTo(p).accent, 99f) }
                    addView(dot, android.widget.FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER))
                    if (moved?.hop == true) Motion.hop(dot, 90L + kotlin.math.abs(i - first) * 70L, ui.dp(9f).toFloat())
                    if (!isChosen) setOnClickListener { Haptics.tick(this); restyle(this, Moved(hop = true)) { h.setScheme(scheme) } }
                }, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
            }
        }

        val levels = Haptics.Level.entries
        group("Look and feel", listOf(
            row(ui, "Appearance", end = look),
            row(ui, "Theme", end = themes),
            // Rolling the wheel plays each strength; Cancel puts the old one back.
            wheelRow(ui, "Vibration", "How strong the app's taps and clicks feel.", levels.map { it.label }, Haptics.level(context).ordinal, { it > 0 },
                roll = { i, wheel -> Haptics.setLevel(context, levels[i]); wheel?.let(Haptics::tick) }),
            row(ui, "Week until midnight", end = Toggle(ui, DayEnd.late(context)) { on -> DayEnd.setLate(context, on) }.apply { contentDescription = "Week until midnight" }),
        ))

        val tt = h.timetable
        val reminders = ClassReminders.CHOICES
        group("Classes", listOfNotNull(
            timetable(h, tt),
            if (signedIn) occurrences(h, context) else null,
            wheelRow(ui, "Class reminders", "A notification before each of your classes, with its time and room. None on holidays or in the break.",
                reminders.map { it.second }, reminders.indexOfFirst { it.first == ClassReminders.before(context) }.coerceAtLeast(0), { reminders[it].first != null },
                choose = { i -> ClassReminders.setBefore(context, reminders[i].first) },
                // Asked once the card is back: choosing a time with notifications off asks for them.
                after = { i -> if (reminders[i].first != null && !Notify.allowed(context)) h.enableNotifications() }),
            // Marking attendance by itself, as each class starts (AutoAttendance), and its log: each try and what went wrong.
            if (signedIn) row(ui, "Mark my attendance", "Settles your attendance as each class starts",
                Toggle(ui, AutoAttendance.enabled(context)) { on -> AutoAttendance.setEnabled(context, on) }.apply { contentDescription = "Mark my attendance" }) else null,
            if (signedIn) row(ui, "Attendance log", end = chosen(ui, value(ui, AttendanceLogPage.short(context)))) { openLog() } else null,
        ))

        // Android owns the switch: off, it asks; on, it opens the app's notification settings.
        // Android 12 can't ask (no permission there), so off opens the settings too.
        val allowed = Notify.allowed(context)
        val ask = !allowed && Build.VERSION.SDK_INT >= 33
        group("App", listOf(
            row(ui, "Notifications", end = chosen(ui, value(ui, if (allowed) "On" else "Off", allowed))) { if (ask) h.enableNotifications() else h.openAppSettings() },
            // The newest version on GitHub (Updater); the installed one is under the page's title.
            row(ui, "Check for updates", end = chevron(ui)) { h.checkForUpdates() },
            if (signedIn) {
                lateinit var out: View
                out = button(ui, "Sign out") { h.signOut(out) }
                row(ui, SpectrumScreens.accountName(context).ifBlank { "Signed in" }, end = out)
            } else {
                row(ui, "Not signed in", "For courses, deadlines, marks and news", button(ui, "Sign in") { h.signIn() })
            },
        ))
        return page
    }

    private fun button(ui: Ui, text: String, onClick: () -> Unit) = ui.text(text, 14f, ui.p.accentText, ui.f.bodySemi).apply {
        gravity = Gravity.CENTER
        minHeight = ui.dp(38)
        setPadding(ui.dp(14), 0, ui.dp(14), 0)
        background = ui.pressable(ui.shape(ui.p.surface2, 12f), 12f)
        setOnClickListener { onClick() }
    }

    /** The timetable's row says how many classes; a tap flies a card out of it with where to get them from. */
    private fun timetable(h: SpectrumHost, tt: Timetable): View {
        val ui = h.ui
        val p = ui.p
        lateinit var line: LinearLayout
        val classes = tt.sessions.size
        line = row(ui, "Timetable", end = chosen(ui, value(ui, if (classes == 0) "None yet" else "$classes ${if (classes == 1) "class" else "classes"}"))) {
            lateinit var close: (then: (() -> Unit)?) -> Unit
            val card = ConfirmDialog.frame(ui).apply {
                addView(ui.text("Timetable", 30f, p.text, ui.f.sign))
                addView(ui.text(listOfNotNull(if (classes == 0) "No timetable yet." else "$classes classes a week", tt.term).joinToString(" · "), 15f, p.muted), ui.wide(top = 2f))
                addView(ConfirmDialog.button(ui, "Get from TimeEdit", p.accent, p.accentInk) { close { h.openTimeEdit() } }, ui.wide(top = 20f))
                addView(ConfirmDialog.button(ui, "Import from MAYA", p.surface2, p.text) { close { h.openMaya() } }, ui.wide(top = 8f))
                addView(ui.text("If TimeEdit can't: you sign in to MAYA yourself.", 13f, p.muted).apply { gravity = Gravity.CENTER }, ui.wide(top = 12f))
            }
            close = ConfirmDialog.present(ui, card, from = Motion.onScreen(line))
        }
        return line
    }

    /**
     * A row saying which of [options] is chosen; a tap flies a card out of it with them on a wheel, Cancel and
     * Choose, and it flies back. [roll] runs as the wheel reaches each option (with the wheel) and, with no wheel,
     * when the card has closed, on the option then in force; [choose] when one's chosen, and [after] once it's back.
     */
    private fun wheelRow(
        ui: Ui,
        title: String,
        about: String,
        options: List<String>,
        start: Int,
        lit: (Int) -> Boolean,
        roll: (Int, View?) -> Unit = { _, _ -> },
        choose: (Int) -> Unit = {},
        after: (Int) -> Unit = {},
    ): View {
        val p = ui.p
        var current = start
        val shown = value(ui, options[start], lit(start))
        lateinit var line: LinearLayout
        line = row(ui, title, end = chosen(ui, shown)) {
            var pick = current
            lateinit var close: (then: (() -> Unit)?) -> Unit
            val card = ConfirmDialog.frame(ui).apply {
                addView(ui.text(title, 30f, p.text, ui.f.sign))
                addView(ui.text(about, 15f, p.muted), ui.wide(top = 2f))
                addView(ui.column().apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(ui.wheel(options, pick, rows = 5) { i ->
                        pick = i
                        roll(i, this)
                    }.apply { name = title }, LinearLayout.LayoutParams(ui.dp(180), LinearLayout.LayoutParams.WRAP_CONTENT))
                }, ui.wide(top = 18f))
                addView(ui.row(gap = 8f).apply {
                    addView(ConfirmDialog.button(ui, "Cancel", p.surface2, p.text) { close(null) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(ConfirmDialog.button(ui, "Choose", p.accent, p.accentInk) {
                        if (pick == current) return@button close(null)
                        current = pick
                        choose(pick)
                        shown.text = options[pick]
                        shown.setTextColor(if (lit(pick)) p.accentText else p.muted)
                        close {
                            Motion.pop(shown, 0.6f)
                            after(pick)
                        }
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                }, ui.wide(top = 20f))
            }
            close = ConfirmDialog.present(ui, card, from = Motion.onScreen(line), backAlways = true, onClosed = { roll(current, null) })
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
     * Occurrences, a fold in Classes: each course with its occurrence, Spectrum's unless the user chose
     * another; a tap on a course flies a card out of it ([occurrenceCard]) with a wheel of those TimeEdit
     * lists for it, over the page, blurred. A choice is saved at once; the timetable keeps
     * its classes until it's got again from TimeEdit (the button under the courses), whose answer is
     * shown before it's used. The courses are built when the fold first opens: it's usually shut.
     */
    private fun occurrences(h: SpectrumHost, context: Context): View? {
        val ui = h.ui
        val p = ui.p
        val chosen = TimeEditLookup.occurrences(context)
        if (chosen.length() == 0) return null
        fun summary() = SpectrumStore.occurrencePicks(context).length().let { n -> if (n == 0) "From Spectrum" else "$n chosen" }

        // A 16dp box: turned on its side the 8×16 chevron is 16dp wide.
        val chevron = Motion.chevron(ui, occurrencesOpen, p.faint).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)) }
        val shown = value(ui, summary())
        val head = row(ui, "Occurrences", end = chosen(ui, shown, chevron))
        val fold = ui.column().apply { setPadding(ui.dp(14), ui.dp(2), ui.dp(14), ui.dp(12)) }

        fun fill() {
            val spectrum = SpectrumStore.spectrumOccurrences(context)
            val offered = SpectrumStore.occurrenceOptions(context)
            val names = SpectrumStore.array(context, "courses").objects().associate { it.optString("code") to it.optString("name") }
            // In the timetable's order, then any course only Spectrum has.
            val codes = (h.timetable.courses.map { it.code } + chosen.keys().asSequence()).distinct().filter { chosen.has(it) }
            fun inTimetable(code: String) = h.timetable.courses.firstOrNull { it.code == code }?.occ
            fun stale() = codes.any { code -> inTimetable(code).let { it != null && it != chosen.optInt(code) } }
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
                    addView(chevron(ui))
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
                            shown.text = summary()
                            update.visibility = if (stale()) View.VISIBLE else View.GONE
                            // Back into its row, whose value then pops.
                            close { Motion.pop(value, 0.6f) }
                        }
                        close = ConfirmDialog.present(ui, card, from = Motion.onScreen(row), backAlways = true)
                    }
                })
            }
            fold.addView(update, ui.wide(top = 16f))
        }

        fold.visibility = if (occurrencesOpen) View.VISIBLE else View.GONE
        if (occurrencesOpen) fill()
        head.background = ui.pressable(ui.shape(Color.TRANSPARENT, 0f), 0f)
        head.setOnClickListener {
            Motion.resize(head)
            occurrencesOpen = !occurrencesOpen
            if (occurrencesOpen && fold.childCount == 0) fill()
            fold.visibility = if (occurrencesOpen) View.VISIBLE else View.GONE
            Motion.turn(chevron, occurrencesOpen)
        }
        return ui.column().apply {
            addView(head)
            addView(fold)
        }
    }
}
