package com.skylightlim.umtimetable

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * The class details sheet that slides up from the bottom, laid out as in redesign 1f. Its pen turns it to
 * editing ([ClassEdits]): each of the course's name and colour and the class's day and time, type, room and
 * lecturer is a row, and a tap flies a card out of it with what changes it (a field, or wheels), as
 * Settings' rows do. A change is kept at once, and Day, Week, the widget, reminders and auto attendance
 * follow it; Put back gives TimeEdit's again.
 */
object ClassSheet {
    fun show(h: Host, context: Context, course: Course, session: Session?) {
        lateinit var bottom: BottomSheet
        val sheet = build(h, context, course, session) { then -> bottom.close(then) }
        bottom = BottomSheet(context, sheet, h.ui.p.dark)
        bottom.show()
    }

    /** The sheet's content; [close] slides the sheet away and runs what it's given. [editing]: open in its edit mode. */
    fun build(h: Host, context: Context, course: Course, session: Session?, editing: Boolean = false, close: ((() -> Unit)?) -> Unit): View {
        val ui = h.ui
        val p = ui.p
        val sheet = ui.column().apply {
            background = GradientDrawable().apply {
                setColor(p.surface)
                val r = ui.dp(22f).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(28))
        }
        val code = course.code
        // The class as TimeEdit gave it: found again after a change has moved it.
        val key = session?.let(ClassEdits::key)
        var edit = editing

        fun fill() {
            sheet.removeAllViews()
            val tt = h.timetable
            val course = tt.courses.firstOrNull { it.code == code } ?: course
            val session = key?.let { k -> tt.sessions.firstOrNull { ClassEdits.key(it) == k } } ?: session

            /** A change was kept: the page behind, the widget and the alarms follow it, and the sheet shows it. */
            fun changed() {
                ClassEdits.changed(context)
                (h as? SpectrumHost)?.redraw() ?: h.render()
                Motion.resize(sheet)
                fill()
            }

            sheet.addView(View(context).apply { background = ui.shape(p.line, 4f) }, LinearLayout.LayoutParams(ui.dp(40), ui.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ui.dp(12) })
            val top = ui.row(gap = 10f)
            top.addView(View(context).apply { background = ui.shape(course.color, 99f); layoutParams = LinearLayout.LayoutParams(ui.dp(12), ui.dp(12)) })
            top.addView(ui.text(course.code, 13f, p.courseText(course.color), ui.f.monoBold))
            top.addView(ui.text("Occurrence ${course.occ}", 12f, p.muted))
            top.addView(ui.space(), ui.spacer())
            // The pen, or Done while editing; then Close.
            top.addView(roundButton(ui, if (edit) R.drawable.ic_check else R.drawable.ic_pen, if (edit) p.accentInk else p.muted, if (edit) p.accent else p.surface2,
                if (edit) "Done editing" else "Edit this class") {
                Haptics.tick(sheet)
                edit = !edit
                Motion.resize(sheet)
                fill()
            }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
            top.addView(roundButton(ui, R.drawable.ic_close, p.muted, p.surface2, "Close") { close(null) },
                LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { marginEnd = -ui.dp(7) })
            sheet.addView(top)
            sheet.addView(ui.text(course.name, 34f, p.text, ui.f.sign).apply { setLineSpacing(0f, 0.95f) }, ui.wide(top = 6f, bottom = 16f))

            // The facts on a ruled grid: labels in an 84dp column, a line under every row. Editing, each row
            // opens what changes it, with a chevron at its end.
            val facts = ui.column()
            fun rule() = facts.addView(View(context).apply { setBackgroundColor(p.line) }, ui.wide().apply { height = 1 })
            fun fact(label: String, value: View, valueTop: Float = 10f, onEdit: ((View) -> Unit)? = null) {
                facts.addView(ui.row(center = false).apply {
                    addView(ui.label(label, p.muted, 10.5f).apply { setPadding(0, ui.dp(12), 0, ui.dp(12)) }, LinearLayout.LayoutParams(ui.dp(84), ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(value.apply { setPadding(0, ui.dp(valueTop), 0, ui.dp(10)) }, ui.grow())
                    if (onEdit != null) {
                        minimumHeight = ui.dp(48)
                        addView(ui.icon(R.drawable.ic_chevron, p.faint).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(8), ui.dp(16)).apply { gravity = Gravity.CENTER_VERTICAL } })
                        background = ui.pressable(ui.shape(Color.TRANSPARENT, 8f), 8f)
                        contentDescription = "$label: ${(value as? android.widget.TextView)?.text ?: ""}. Change it."
                        setOnClickListener { onEdit(this) }
                    }
                })
                rule()
            }
            rule()
            val sessions = tt.sessions.filter { it.course == course }.sortedWith(compareBy({ it.day }, { it.start }))
            val whenText = session?.let { "${DAY_NAMES[it.day]}, ${clockText(it.start)}–${clockText(it.end)} · ${durationText(it.end - it.start)}" }
            if (edit) {
                fact("Name", ui.text(course.name, 15f)) { row ->
                    textCard(ui, row, "Name", "The course's name everywhere in the app.", course.name, InputType.TYPE_TEXT_FLAG_CAP_WORDS) { typed ->
                        ClassEdits.setCourse(context, code, ClassEdits.NAME, typed)
                        changed()
                    }
                }
                val colors = Scheme.load(context).courseColors
                val slot = colors.indexOf(course.color)
                fact("Colour", ui.row(gap = 10f).apply {
                    addView(View(context).apply { background = ui.shape(course.color, 99f) }, LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)))
                    addView(ui.text(if (slot >= 0) "Colour ${slot + 1}" else "Its own", 15f))
                }) { row ->
                    wheelCard(ui, row, "Colour", "The course's colour, from the theme's: it changes with the theme.",
                        listOf(Column("Colour", colors.indices.map { "Colour ${it + 1}" }, slot.coerceAtLeast(0), colors))) { (i) ->
                        if (i != slot) { ClassEdits.setCourse(context, code, ClassEdits.SLOT, i); changed() }
                    }
                }
                if (session != null) {
                    fact("When", ui.text(whenText!!, 15f)) { row -> whenCard(ui, row, session) { day, start, end ->
                        ClassEdits.setClass(context, key!!, ClassEdits.DAY, day)
                        ClassEdits.setClass(context, key, ClassEdits.START, start)
                        ClassEdits.setClass(context, key, ClassEdits.END, end)
                        changed()
                    } }
                    val types = (listOf("Lecture", "Tutorial", "Lab") + tt.sessions.map { it.type } + session.type).distinct()
                    fact("Type", ui.text(session.type, 15f)) { row ->
                        wheelCard(ui, row, "Type", "A tutorial shows as an outline, the rest filled.", listOf(Column("Type", types, types.indexOf(session.type)))) { (i) ->
                            if (types[i] != session.type) { ClassEdits.setClass(context, key!!, ClassEdits.TYPE, types[i]); changed() }
                        }
                    }
                    fact(if (session.room == "Online") "Where" else "Room", ui.text(session.room, 15f)) { row ->
                        textCard(ui, row, "Room", "Where this class is.", session.room, InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) { typed ->
                            ClassEdits.setClass(context, key!!, ClassEdits.ROOM, typed)
                            changed()
                        }
                    }
                    fact("Lecturer", ui.text(session.lecturer ?: "Not listed", 15f, if (session.lecturer == null) p.muted else p.text)) { row ->
                        textCard(ui, row, "Lecturer", "Who teaches this class. Leave it empty for none.", session.lecturer.orEmpty(), InputType.TYPE_TEXT_FLAG_CAP_WORDS) { typed ->
                            ClassEdits.setClass(context, key!!, ClassEdits.LECTURER, typed)
                            changed()
                        }
                    }
                }
                sheet.addView(facts)
                sheet.addView(ui.text(if (session != null) "Name and colour change the whole course; the rest, this class only." else "Name and colour change the whole course.", 12.5f, p.muted), ui.wide(top = 10f))
                if (ClassEdits.has(context, code, key)) {
                    sheet.addView(ConfirmDialog.button(ui, "Put back TimeEdit's", p.surface2, p.text) {
                        ClassEdits.reset(context, code, key)
                        changed()
                    }, ui.wide(top = 16f))
                }
                return
            }
            if (session != null) {
                fact("When", ui.text(whenText!!, 15f))
                fact("Type", ui.text(session.type, 15f))
                fact(if (session.room == "Online") "Where" else "Room", ui.column().apply {
                    addView(ui.text(session.room, 30f, p.text, ui.f.sign))
                    session.roomName?.takeIf { it != session.room }?.let { addView(ui.text(it, 12.5f, p.muted), ui.wide(top = 3f)) }
                }, valueTop = 8f)
                fact("Lecturer", ui.text(session.lecturer ?: "Not listed", 15f, if (session.lecturer == null) p.muted else p.text))
            } else {
                val names = sessions.mapNotNull { it.lecturer }.distinct()
                fact("Lecturer", ui.text(names.ifEmpty { listOf("Not listed") }.joinToString("\n"), 15f, if (names.isEmpty()) p.muted else p.text))
            }
            sheet.addView(facts)

            sheet.addView(ui.label("Every week · ${hoursText(sessions.sumOf { it.end - it.start })}", p.muted, 10.5f), ui.wide(top = 20f, bottom = 8f))
            val list = ui.column(gap = 4f)
            for (s in sessions) {
                // A session opens its day.
                val row = ui.row(gap = 8f).apply {
                    background = ui.pressable(ui.shape(p.surface2, 12f, if (s == session) course.color else 0, 2f), 12f)
                    setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                    contentDescription = "${DAY_NAMES[s.day]} ${clockText(s.start)} to ${clockText(s.end)}, ${s.type}, ${s.room}. Opens ${DAY_NAMES[s.day]}."
                    setOnClickListener { close { h.goDay(s.day) } }
                }
                row.addView(ui.text(DAY_SHORT[s.day], 15f, p.text, ui.f.bodyBold), LinearLayout.LayoutParams(ui.dp(40), ViewGroup.LayoutParams.WRAP_CONTENT))
                row.addView(ui.text("${clockText(s.start)}–${clockText(s.end)}", 12.5f, p.text, ui.f.mono), LinearLayout.LayoutParams(ui.dp(100), ViewGroup.LayoutParams.WRAP_CONTENT))
                row.addView(ui.text(s.type, 13f, p.muted), ui.grow())
                row.addView(ui.text(s.room, 18f, p.text, ui.f.sign))
                list.addView(row)
            }
            sheet.addView(list)

            // The course's page in the Courses tab, once Spectrum has synced it.
            val pageId = SpectrumStore.array(context, "courses").objects().firstOrNull { it.optString("code") == course.code }?.optInt("id")
            if (pageId != null) {
                sheet.addView(ui.row(gap = 12f).apply {
                    background = ui.pressable(ui.shape(p.surface2, 12f), 12f)
                    minimumHeight = ui.dp(48)
                    setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
                    addView(ui.text("Open course page", 15f, p.text, ui.f.bodySemi), ui.grow())
                    addView(ui.icon(R.drawable.ic_arrow, p.text, 16f))
                    setOnClickListener { close { h.showCourse(pageId) } }
                }, ui.wide(top = 16f))
            }
        }
        fill()
        return sheet
    }

    /** A 34dp circle of [fill] with [icon] in it, inside a 48dp touch area. */
    private fun roundButton(ui: Ui, icon: Int, tint: Int, fill: Int, what: String, onClick: () -> Unit) = FrameLayout(ui.context).apply {
        background = android.graphics.drawable.InsetDrawable(ui.pressable(ui.shape(fill, 99f), 99f), ui.dp(7))
        contentDescription = what
        addView(ui.icon(icon, tint, 16f), FrameLayout.LayoutParams(ui.dp(16), ui.dp(16), Gravity.CENTER))
        setOnClickListener { onClick() }
    }

    /** A wheel on a card: its [title] over it, its [options] and the one [chosen]; [colors] makes it a wheel of swatches. */
    private class Column(val title: String, val options: List<String>, val chosen: Int, val colors: List<Int>? = null)

    /**
     * A card flying out of [from] with [heading], what it changes ([about]), its wheels side by side, then
     * Cancel and Save. [done] gets what each wheel shows, on Save.
     */
    private fun wheelCard(ui: Ui, from: View, heading: String, about: String, columns: List<Column>, done: (IntArray) -> Unit) {
        val p = ui.p
        val picks = IntArray(columns.size) { columns[it].chosen }
        lateinit var close: (then: (() -> Unit)?) -> Unit
        val card = ConfirmDialog.frame(ui).apply {
            addView(ui.text(heading, 30f, p.text, ui.f.sign))
            addView(ui.text(about, 15f, p.muted), ui.wide(top = 2f))
            addView(ui.row(gap = 10f, center = false).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                for ((n, c) in columns.withIndex()) {
                    val wheel = ui.wheel(c.options, c.chosen, rows = 5, colors = c.colors) { i -> picks[n] = i }.apply { name = c.title }
                    addView(ui.column().apply {
                        if (columns.size > 1) addView(ui.label(c.title, p.muted, 10.5f), ui.wide(bottom = 8f))
                        addView(wheel, ui.wide())
                    }, if (columns.size > 1) ui.grow() else LinearLayout.LayoutParams(ui.dp(180), ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            }, ui.wide(top = 18f))
            addView(ui.row(gap = 8f).apply {
                addView(ConfirmDialog.button(ui, "Cancel", p.surface2, p.text) { close(null) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ConfirmDialog.button(ui, "Save", p.accent, p.accentInk) { close { done(picks) } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, ui.wide(top = 20f))
        }
        close = ConfirmDialog.present(ui, card, from = Motion.onScreen(from), backAlways = true)
    }

    /**
     * When the class is: its day (Monday to Friday), when it starts (each half hour from 08:00, Week's first
     * hour) and how long it is (half an hour to four), on three wheels. A time TimeEdit gave off the half
     * hour stays among them. [done] gets the day, start and end, the end no later than midnight.
     */
    private fun whenCard(ui: Ui, from: View, s: Session, done: (day: Int, start: Int, end: Int) -> Unit) {
        val starts = ((8 * 60..23 * 60 + 30 step 30).toList() + s.start).distinct().sorted()
        val lengths = ((30..240 step 30).toList() + (s.end - s.start)).distinct().sorted()
        wheelCard(ui, from, "When", "This class, every week.", listOf(
            Column("Day", (1..5).map { DAY_NAMES[it] }, s.day - 1),
            Column("Starts", starts.map(::clockText), starts.indexOf(s.start)),
            Column("How long", lengths.map(::durationText), lengths.indexOf(s.end - s.start)),
        )) { (d, st, len) ->
            val start = starts[st]
            val end = minOf(start + lengths[len], 24 * 60)
            if (d + 1 != s.day || start != s.start || end != s.end) done(d + 1, start, end)
        }
    }

    /**
     * A card flying out of [from] with [heading], what it changes ([about]) and a field holding [value], the
     * keyboard up; then Cancel and Save. [done] gets what's typed, trimmed, on Save (or the keyboard's Done)
     * when it differs from [value].
     */
    private fun textCard(ui: Ui, from: View, heading: String, about: String, value: String, caps: Int, done: (String) -> Unit) {
        val p = ui.p
        lateinit var close: (then: (() -> Unit)?) -> Unit
        lateinit var field: EditText
        fun save() {
            val typed = field.text.toString().trim().replace(Regex("""\s+"""), " ")
            close { if (typed != value) done(typed) }
        }
        field = EditText(ui.context).apply {
            setText(value)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(p.text)
            setHintTextColor(p.faint)
            typeface = ui.f.bodySemi
            background = ui.shape(p.surface2, 12f)
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            minHeight = ui.dp(52)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or caps
            imeOptions = EditorInfo.IME_ACTION_DONE
            textCursorDrawable = ui.shape(p.accentText, 1f).apply { setSize(ui.dp(2), 0) }
            contentDescription = heading
            setSelection(text.length)
            setOnEditorActionListener { _, _, _ -> save(); true }
        }
        val card = ConfirmDialog.frame(ui).apply {
            addView(ui.text(heading, 30f, p.text, ui.f.sign))
            addView(ui.text(about, 15f, p.muted), ui.wide(top = 2f))
            addView(field, ui.wide(top = 18f))
            addView(ui.row(gap = 8f).apply {
                addView(ConfirmDialog.button(ui, "Cancel", p.surface2, p.text) { close(null) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ConfirmDialog.button(ui, "Save", p.accent, p.accentInk) { save() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, ui.wide(top = 20f))
            field.requestFocus()
        }
        close = ConfirmDialog.present(ui, card, from = Motion.onScreen(from), backAlways = true, keyboard = true)
    }
}
