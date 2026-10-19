package com.skylightlim.umtimetable

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import java.io.File

/**
 * Debug only: draws one page of the app into a PNG without showing anything on screen, so pages can
 * be checked while the phone is in use. The page content only (no top bar or tabs), at the phone's
 * full width, from cached data.
 *
 *   adb shell am broadcast -n com.skylightlim.umtimetable/.DebugRenderReceiver \
 *     --es view files [--ei course 151] [--ei day 1] [--ez dark true] [--ei width 1280] [--ei dow 2 --ei min 700]
 *   adb shell run-as com.skylightlim.umtimetable cat cache/render/files.png > files.png
 *
 * view: day, week, due, news, files (the Courses tab); course opens one course page from its cache.
 * view icon draws the launcher icon with the phone's mask (icon.png) and its themed layer (icon-mono.png).
 * dow (0 Sunday … 6 Saturday) and min (minutes after midnight) pretend it's another time of the week.
 * view grades with --ez sample true draws made-up marks for the timetable's first courses instead of the
 * synced ones (to check the mark cards while Spectrum has none).
 * view due with --ez sample true draws made-up deadlines (late, minutes, hours, days) and attendance
 * (open, about to open, not in Spectrum's calendar).
 * --ef fontScale 1.3 draws at a larger system font size (this render only).
 * --ei weekShift N draws day and week N weeks on from the strip's own week (as swiping on Week does).
 * --es scheme gold|blue|red draws in that accent scheme instead of the chosen one.
 * --ez open true draws every fold and "Show more" opened.
 * view webbar draws the web screens' top bar (--es bar spectrum|maya|timeedit|login).
 * view week or day with --ez sample true draws made-up plans on it (--ei boxDay 2 --ei boxAt 900: with the selection box
 * waiting on Tuesday 15:00); view plan draws the plan editor's card
 * (--ez new false: a saved plan's, with Delete; --ei ahead 3: dated 3 days from today, so its reminders show;
 * --ei maxEnd 1140: room until 19:00, so How long's wheel runs on; --ei cap 260: its middle at most 260dp, scrolling, as
 * when the keyboard leaves too little room).
 * view scene draws the sign-in poster's fight as a contact sheet, a frame every --ei step ms (200).
 * view pinmenu draws the card a long press on a course page's section name brings up (--ez pinned true: Unpin).
 * view signout draws the Sign out question's card. view settings draws the Settings sheet (--ez signedIn true|false
 * for either Account row); view signin the first-run sign-in poster, at screen height (or --ei height px).
 * view sheet draws a class sheet: --es code WIX1002 for the course's sheet, plus --ei session N for its
 * Nth class of the week (0 first).
 */
class DebugRenderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.hasExtra("bench")) return bench(context, intent.getIntExtra("bench", 20))
        if (intent.getStringExtra("view") == "icon") return icon(context)
        if (intent.getStringExtra("view") == "tabicons") return tabIcons(context)
        if (intent.getStringExtra("view") == "duel") return duel(context, intent.getBooleanExtra("dark", false))
        if (intent.getStringExtra("view") == "duelloop") return duelLoop(context)
        if (intent.getStringExtra("view") == "scene") return scene(context, intent.getIntExtra("step", 200))
        val view = intent.getStringExtra("view") ?: "files"
        val dark = intent.getBooleanExtra("dark", false)
        val width = intent.getIntExtra("width", context.resources.displayMetrics.widthPixels)
        // --ef fontScale 1.3: as with Android's larger font sizes, without touching the phone's setting.
        val scaled = intent.getFloatExtra("fontScale", 0f).takeIf { it > 0f }?.let { k ->
            context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply { fontScale = k })
        } ?: context
        val c = ContextThemeWrapper(scaled, R.style.Theme_UMTimetable)
        val now = if (intent.hasExtra("dow")) Now(intent.getIntExtra("dow", 2), intent.getIntExtra("min", 700).toFloat()) else Now.current()
        val forced = intent.getStringExtra("scheme")?.let { name -> Scheme.entries.firstOrNull { it.name.equals(name, true) } }
        val scheme = forced ?: Scheme.load(context)
        val host = Host(c, Ui(c, scheme.applyTo(if (dark) Palette.DARK else Palette.LIGHT), Fonts(c)), now, forced).apply {
            openCourse = intent.getIntExtra("course", 0).takeIf { it > 0 }
            weekShift = intent.getIntExtra("weekShift", 0)
            if (intent.getBooleanExtra("open", false)) expanded = object : HashSet<String>() { override fun contains(element: String) = true }
        }
        val page = when (view) {
            "week" -> WeekScreen.build(host, if (intent.getBooleanExtra("sample", false)) samplePlans(host) else null)
            // The plan editor's card: --ez new false for a saved plan's (with Delete), --ei ahead, --ei maxEnd
            "plan" -> PlanSheet.build(host.ui, samplePlans(host)[1].let {
                val dated = if (intent.hasExtra("ahead")) it.copy(date = java.time.LocalDate.now().plusDays(intent.getIntExtra("ahead", 0).toLong())) else it
                if (intent.getBooleanExtra("new", true)) dated.copy(text = "") else dated
            }, intent.getBooleanExtra("new", true), intent.getIntExtra("maxEnd", 16 * 60 + 30)).also { e ->
                // --ei cap N: the card's middle at most N dp, as when the keyboard leaves too little room.
                intent.getIntExtra("cap", 0).takeIf { it > 0 }?.let { e.scroll.cap = host.ui.dp(it) }
            }.card
            // The card a long press on a course page's section name brings up (--ez pinned true: "Unpin").
            "pinmenu" -> FrameLayout(c).apply {
                setPadding(host.ui.dp(16), host.ui.dp(16), host.ui.dp(16), host.ui.dp(24))
                addView(SpectrumScreens.pinCard(host.ui, intent.getBooleanExtra("pinned", false)), FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            }
            "due" -> if (intent.getBooleanExtra("sample", false)) sampleDue(context, host) else SpectrumScreens.due(host)
            "news" -> SpectrumScreens.news(host)
            "files" -> SpectrumScreens.courses(host)
            "grades" -> if (intent.getBooleanExtra("sample", false)) SpectrumScreens.grades(host, sampleGrades(context)) else SpectrumScreens.grades(host)
            "settings" -> SettingsSheet.also { it.occurrencesOpen = intent.getBooleanExtra("open", false) }.build(host, c, intent.getBooleanExtra("signedIn", SpectrumStore.signedIn(context))) {}
            "signin" -> SignInScreen.build(host.ui) {}
            // The top bar as MainActivity lays it out: --es view title --es page Grades|day|week (day and week
            // with their widest titles)
            "title" -> host.ui.row().apply {
                setPadding(host.ui.dp(0), host.ui.dp(6), 0, host.ui.dp(10))
                val page = intent.getStringExtra("page") ?: "Grades"
                addView(when (page) {
                    "day" -> dayTitle(host.ui, "Wednesday", "30 Sep", true, dayGlance(Timetable.load(context).day(2)))
                    "week" -> weekTitle(host.ui, "28 Sep – 2 Oct", "3 deadlines this week")
                    else -> pageTitle(host.ui, page, SpectrumScreens.summary(host, mapOf("Courses" to "files").getOrElse(page) { page.lowercase() }))
                }, host.ui.grow())
            }
            // The web screens' top bar: --es bar spectrum|maya|timeedit|login
            "webbar" -> when (intent.getStringExtra("bar") ?: "maya") {
                "spectrum" -> WebBar(host.ui, "Spectrum", "Assignment 2: Group report (Week 6)", "Done", null, null, compact = true) {}
                "login" -> WebBar(host.ui, "SiswaMail", "Sign in to Spectrum", "Cancel", "Sign in with your SiswaMail. Your login stays on this phone; the app only talks to Spectrum.", null, compact = false) {}
                "timeedit" -> WebBar(host.ui, "TimeEdit", "Get your timetable", "Close", "Reading your 5 courses on TimeEdit…", "Get timetable", compact = false) {}.also { it.action.isEnabled = false }
                else -> WebBar(host.ui, "MAYA", "Import your timetable", "Close", "Sign in to MAYA, and the app opens your timetable and reads it. Your password goes only to MAYA or Microsoft; this app never sees it.", "Read this page", compact = false) {}
            }.view.apply { setBackgroundColor(host.ui.p.ground) }
            "signout" -> ConfirmDialog.card(host.ui, MainActivity.SIGN_OUT_TITLE, MainActivity.SIGN_OUT_BODY, "Sign out", {}, {}, hold = true)
            "sheet" -> {
                val tt = Timetable.load(context)
                val course = tt.courses.first { it.code == intent.getStringExtra("code") }
                val session = intent.getIntExtra("session", -1).takeIf { it >= 0 }
                    ?.let { i -> tt.sessions.filter { it.course == course }.sortedWith(compareBy({ it.day }, { it.start }))[i] }
                ClassSheet.build(host, c, course, session) {}
            }
            else -> DayScreen.build(host, intent.getIntExtra("day", homeDay(Timetable.load(context), now)), if (intent.getBooleanExtra("sample", false)) samplePlans(host) else null)
        }
        // --ez tapMore true: taps the first "Show more" before drawing, to check the toggle itself.
        if (intent.getBooleanExtra("tapMore", false)) {
            fun find(v: View): View? = if (v is android.widget.TextView && v.text.toString() == "Show more") v
                else (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
            Log.i("UMTimetable", "debug render: tapMore ${find(page)?.performClick()}")
        }
        val frame = FrameLayout(c).apply {
            setBackgroundColor(host.ui.p.ground)
            // A sheet runs edge to edge, from the bottom of the screen.
            if (view != "sheet" && view != "settings" && view != "signin" && view != "webbar") setPadding(host.ui.dp(16), 0, host.ui.dp(16), host.ui.dp(24))
            addView(page)
        }
        frame.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            if (view == "signin") View.MeasureSpec.makeMeasureSpec(intent.getIntExtra("height", context.resources.displayMetrics.heightPixels), View.MeasureSpec.EXACTLY) else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
        // --ez bounds true: every view's place and size (and a text's baseline) in logcat, as "bounds".
        if (intent.getBooleanExtra("bounds", false)) logBounds(frame, 0)
        // Week with --ez sample true --ei boxDay D --ei boxAt MIN: the selection box waiting there (taken or free).
        if (intent.hasExtra("boxDay")) {
            fun grid(v: View): WeekGrid? = v as? WeekGrid ?: (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { grid(g.getChildAt(it)) } }
            grid(frame)?.previewBox(samplePlans(host)[1], intent.getIntExtra("boxDay", 2), intent.getIntExtra("boxAt", 15 * 60))
        }
        // Settings with --ef hopAt 0.36: the chosen theme's dots posed that far into their hop.
        if (intent.hasExtra("hopAt")) {
            fun find(v: View): android.view.ViewGroup? =
                if (v is android.view.ViewGroup && v.contentDescription?.endsWith("theme, chosen") == true) v
                else (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
            val dots = find(frame)?.getChildAt(0) as? android.view.ViewGroup
            for (i in 0 until (dots?.childCount ?: 0)) Motion.hopAnimator(dots!!.getChildAt(i), host.ui.dp(if (i == 0) 9f else 8f).toFloat()).setCurrentFraction(intent.getFloatExtra("hopAt", 0.36f))
        }
        val bitmap = Bitmap.createBitmap(frame.measuredWidth, frame.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        frame.draw(Canvas(bitmap))
        save(context, bitmap, "$view${host.openCourse?.let { "-$it" } ?: ""}${if (dark) "-dark" else ""}")
    }

    private fun logBounds(v: View, depth: Int) {
        val text = (v as? android.widget.TextView)?.text?.toString()?.take(24)?.let { " \"$it\" baseline ${v.baseline}" }.orEmpty()
        Log.i("bounds", "${"  ".repeat(depth)}${v.javaClass.simpleName} ${v.left},${v.top} ${v.width}x${v.height} measured ${v.measuredHeight}$text")
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) logBounds(v.getChildAt(i), depth + 1)
    }

    /** Made-up plans in the week drawn: study, the gym (every week), reading (none of them the user's). */
    private fun samplePlans(host: Host): List<Plan> {
        val palette = Plans.palette(Scheme.load(host.ui.context), host.ui.p.accent)
        val monday = host.dateOf(1)
        return listOf(
            Plan("s1", monday.plusDays(1), 10 * 60, 11 * 60, "Group study for the quiz", palette[0], 10),
            Plan("s2", monday.plusDays(2), 14 * 60, 16 * 60, "Gym", palette[3], weekly = true, from = monday.plusDays(2).minusWeeks(1)),
            Plan("s3", monday.plusDays(4), 11 * 60, 11 * 60 + 30, "Read chapter 3", palette[7], 0),
        )
    }

    /** Made-up grade items: marks (one a scale word, one with feedback), a course total, unmarked items. */
    private fun sampleGrades(context: Context): List<org.json.JSONObject> {
        val courses = Timetable.load(context).courses.take(3)
        var id = 900_000
        val now = System.currentTimeMillis()
        fun item(c: Course, i: Int, name: String, kind: String, raw: Double?, max: Double, daysAgo: Int, word: String = "", feedback: String = "", total: Boolean = false) =
            org.json.JSONObject().put("id", id++).put("courseId", i).put("code", c.code).put("course", c.name).put("total", total)
                .put("name", name).put("kind", kind).put("raw", raw ?: org.json.JSONObject.NULL).put("max", max).put("word", word)
                .put("percent", "").put("feedback", feedback).put("gradedAt", if (raw == null) 0 else now - daysAgo * 86_400_000L).put("url", "")
        val out = mutableListOf<org.json.JSONObject>()
        courses.getOrNull(0)?.let { c ->
            out += item(c, 1, "Quiz 1", "Quiz", 8.5, 10.0, 0)
            out += item(c, 1, "Tutorial 1", "Assignment", 9.0, 10.0, 5, feedback = "<p>Good working, but show every step in question 3.</p>")
            out += item(c, 1, "Quiz 2", "Quiz", null, 10.0, 0)
        }
        courses.getOrNull(1)?.let { c ->
            out += item(c, 2, "Lab 1", "Assignment", 18.0, 20.0, 8)
            out += item(c, 2, "Course total", "", 18.0, 100.0, 8, total = true)
            out += item(c, 2, "Lab 2", "Assignment", null, 20.0, 0)
        }
        courses.getOrNull(2)?.let { c ->
            out += item(c, 3, "Presentation", "Assignment", 1.0, 1.0, 2, word = "Pass")
        }
        return out
    }

    private fun sampleDue(context: Context, host: Host): View {
        val codes = Timetable.load(context).courses.map { it.code }.ifEmpty { listOf("ABC1001") }
        val now = System.currentTimeMillis()
        fun code(i: Int) = codes[i % codes.size]
        fun item(i: Int, name: String, inMin: Long, kind: String) = org.json.JSONObject().put("code", code(i)).put("course", "").put("name", name)
            .put("due", now + inMin * 60_000L).put("overdue", inMin < 0).put("moduleLabel", kind).put("url", "")
        val due = listOf(
            item(0, "Lab 2 report", -90, "Assignment"), item(1, "Quiz 3", 40, "Quiz"),
            item(2, "Tutorial 4 submission", 12 * 60, "Assignment"), item(0, "Project proposal", 10 * 1440, "Assignment"),
        )
        fun session(i: Int, startMin: Long, lengthMin: Long, listed: Boolean) = org.json.JSONObject().put("code", code(i)).put("name", "Attendance OCC 1")
            .put("start", now + startMin * 60_000L).put("end", now + (startMin + lengthMin) * 60_000L).put("listed", listed).put("url", "")
        return SpectrumScreens.due(host, due, listOf(session(0, -20, 120, true), session(1, 50, 60, true), session(2, -10, 60, false)))
    }

    /** The cow and horse duel at steps of the pull (duel.png), the last with its spark half grown. */
    private fun duel(context: Context, dark: Boolean) {
        val c = ContextThemeWrapper(context, R.style.Theme_UMTimetable)
        val ui = Ui(c, Scheme.load(context).applyTo(if (dark) Palette.DARK else Palette.LIGHT), Fonts(c))
        val steps = listOf(0f, 0.5f, 0.8f, 0.9f, 1f)
        val view = DuelView(ui)
        view.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val pad = ui.dp(8)
        val sheet = Bitmap.createBitmap(view.measuredWidth + pad * 2, (view.measuredHeight + pad) * steps.size + pad, Bitmap.Config.ARGB_8888).apply { eraseColor(ui.p.ground) }
        val canvas = Canvas(sheet)
        steps.forEachIndexed { i, p ->
            view.progress = p
            view.sparkAge = if (p >= 1f) 0.35f else 2f
            canvas.save(); canvas.translate(pad.toFloat(), (pad + i * (view.measuredHeight + pad)).toFloat()); view.draw(canvas); canvas.restore()
        }
        save(context, sheet, "duel${if (dark) "-dark" else ""}")
    }

    /** The duel's loading loop (duelloop.png): a frame every 100 ms for 1.2 s, then [DuelView.finish]. */
    private fun duelLoop(context: Context) {
        val pending = goAsync()
        val c = ContextThemeWrapper(context, R.style.Theme_UMTimetable)
        val ui = Ui(c, Scheme.load(context).applyTo(Palette.LIGHT), Fonts(c))
        val view = DuelView(ui)
        view.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val frames = 14
        val cols = 2
        val w = view.measuredWidth; val h = view.measuredHeight
        val sheet = Bitmap.createBitmap(w * cols, h * ((frames + cols - 1) / cols), Bitmap.Config.ARGB_8888).apply { eraseColor(ui.p.ground) }
        val canvas = Canvas(sheet)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        view.loop()
        val start = android.os.SystemClock.uptimeMillis()
        for (i in 0 until frames) handler.postAtTime({
            if (i == 12) view.finish()
            canvas.save(); canvas.translate((i % cols * w).toFloat(), (i / cols * h).toFloat()); view.draw(canvas); canvas.restore()
            if (i == frames - 1) { view.stop(); save(context, sheet, "duelloop"); pending.finish() }
        }, start + i * 100L)
    }

    /**
     * The tab icons' animations as a contact sheet (tabicons.png): one row per icon, a frame every 80 ms
     * from the start. Drawn on a software canvas, so each animation runs on this thread.
     */
    /** The sign-in poster's fight ([DuelScene]) as a contact sheet: a frame every [step] ms, 0 to 5 s, on the poster's blue. */
    private fun scene(context: Context, step: Int) {
        val c = ContextThemeWrapper(context, R.style.Theme_UMTimetable)
        val ui = Ui(c, Palette.LIGHT, Fonts(c))
        val view = DuelView(ui, 0xFFF1F5FE.toInt(), 0xFF132B7C.toInt(), stage = true)
        val scene = DuelScene(view)
        val w = 480
        val h = 270
        val times = (0..5000 step step).toList()
        val cols = 4
        val rows = (times.size + cols - 1) / cols
        val sheet = Bitmap.createBitmap(w * cols, h * rows, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF132B7C.toInt()) }
        val canvas = Canvas(sheet)
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB8C7E9.toInt(); textSize = 22f }
        val grid = android.graphics.Paint().apply { color = 0xFF4E66A3.toInt(); style = android.graphics.Paint.Style.STROKE }
        for ((i, t) in times.withIndex()) {
            view.poses = scene.at(t.toFloat())
            scene.sparkAt(t.toFloat())?.let { (x, y, age) -> view.sparkStill(x, y, age) } ?: view.sparkStill(60f, 0f, 2f)
            canvas.save()
            canvas.translate((i % cols) * w.toFloat(), (i / cols) * h.toFloat())
            view.draw(canvas)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), grid)
            canvas.drawText("$t ms", 10f, 28f, label)
            canvas.restore()
        }
        save(context, sheet, "scene")
    }

    private fun tabIcons(context: Context) {
        val pending = goAsync()
        val ids = listOf(R.drawable.avd_tab_day, R.drawable.avd_tab_week, R.drawable.avd_tab_grades, R.drawable.avd_tab_due, R.drawable.avd_tab_news, R.drawable.avd_tab_courses)
        val times = (0..9).map { it * 80L }
        val cell = 120
        val sheet = Bitmap.createBitmap(cell * times.size, cell * ids.size, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFEEF1F4.toInt()) }
        val canvas = Canvas(sheet)
        val icons = ids.map { id ->
            (context.getDrawable(id) as android.graphics.drawable.AnimatedVectorDrawable).apply {
                setBounds(0, 0, cell - 24, cell - 24)
                setTint(0xFF141A22.toInt())
            }
        }
        // A first software draw moves each animation onto this thread; then start them together.
        val scratch = Canvas(Bitmap.createBitmap(cell, cell, Bitmap.Config.ARGB_8888))
        icons.forEach { it.start(); it.draw(scratch) }
        val start = android.os.SystemClock.uptimeMillis()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        times.forEachIndexed { col, t ->
            handler.postAtTime({
                icons.forEachIndexed { row, d ->
                    canvas.save()
                    canvas.translate(col * cell + 12f, row * cell + 12f)
                    d.draw(canvas)
                    canvas.restore()
                }
                if (col == times.lastIndex) {
                    save(context, sheet, "tabicons")
                    pending.finish()
                }
            }, start + t)
        }
    }

    private fun save(context: Context, bitmap: Bitmap, name: String) {
        val out = File(context.cacheDir, "render/$name.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i("UMTimetable", "debug render: ${out.name} ${bitmap.width}x${bitmap.height}")
    }

    private fun icon(context: Context) {
        val icon = context.getDrawable(R.mipmap.ic_launcher) as android.graphics.drawable.AdaptiveIconDrawable
        // The themed (monochrome) layer exists from Android 13.
        for ((name, drawable) in listOf("icon" to icon, "icon-mono" to if (android.os.Build.VERSION.SDK_INT >= 33) icon.monochrome else null)) {
            val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
            drawable?.apply { setBounds(0, 0, 432, 432); draw(Canvas(bitmap)) }
            save(context, bitmap, name)
        }
    }

    /**
     * Times what a page draw costs, averaged over [n] runs: `--ei bench 20`. Logs one line per item.
     * Each page is built, measured, laid out and drawn into a bitmap, as on screen.
     */
    private fun bench(context: Context, n: Int) {
        fun time(label: String, block: () -> Unit) {
            block() // warm-up
            val t0 = System.nanoTime()
            repeat(n) { block() }
            Log.i("UMTimetable", "bench %-22s %8.3f ms".format(label, (System.nanoTime() - t0) / 1e6 / n))
        }
        val c = ContextThemeWrapper(context, R.style.Theme_UMTimetable)
        val ui = Ui(c, Palette.LIGHT, Fonts(c))
        val width = context.resources.displayMetrics.widthPixels
        time("SecureStore token") { SpectrumStore.token(context) }
        time("signedIn") { SpectrumStore.signedIn(context) }
        time("read news.json") { SpectrumStore.array(context, "news") }
        time("Fonts()") { Fonts(c) }
        val news = SpectrumStore.array(context, "news").objects()
        val host0 = Host(c, ui)
        time("news: html → text") { for (a in news) RichText.spanned(host0, android.widget.TextView(c), a.optString("html"), "x") }
        time("news: Html.fromHtml") { for (a in news) android.text.Html.fromHtml(a.optString("html"), android.text.Html.FROM_HTML_MODE_COMPACT) }
        time("news: RichText.view") { for (a in news) RichText.view(host0, a.optString("html"), "k", "x", 5, expanded = mutableSetOf()) }
        time("news: html chars") { Log.i("UMTimetable", "news html total ${news.sumOf { it.optString("html").length }} chars, ${news.sumOf { Regex("(?i)<img").findAll(it.optString("html")).count() }} images") }
        run {
            var build = 0L; var measure = 0L; var draw = 0L
            repeat(n + 1) { i ->
                val t0 = System.nanoTime()
                val page = SpectrumScreens.news(Host(c, ui))
                val frame = FrameLayout(c).apply { addView(page) }
                val t1 = System.nanoTime()
                frame.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
                val t2 = System.nanoTime()
                frame.draw(Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)))
                val t3 = System.nanoTime()
                if (i > 0) { build += t1 - t0; measure += t2 - t1; draw += t3 - t2 }
            }
            Log.i("UMTimetable", "bench news split: build %.2f, measure+layout %.2f, draw %.2f ms".format(build / 1e6 / n, measure / 1e6 / n, draw / 1e6 / n))
        }
        val t = System.currentTimeMillis()
        time("x10 hm()") { repeat(10) { SpectrumStore.hm(t) } }
        time("x10 dayWord()") { repeat(10) { SpectrumStore.dayWord(t, t) } }
        time("x10 ui.text") { repeat(10) { ui.text("Hello", 14f) } }
        time("x10 ui.pressable") { repeat(10) { ui.pressable(ui.shape(ui.p.surface, 12f), 12f) } }
        time("x10 ui.shape") { repeat(10) { ui.shape(ui.p.surface, 12f, ui.p.line) } }
        time("x10 ui.icon") { repeat(10) { ui.icon(R.drawable.ic_file, ui.p.muted, 18f) } }
        time("x10 Regex compile") { repeat(10) { Regex("(?i)<img") } }
        time("x10 plain()") { for (a in news) SpectrumSync.plain(a.optString("html")) }
        time("widget render") { WidgetRenderer.build(context, Timetable.load(context), Now.current(), 320f, 420f) }
        // A week swipe: the Week page built for another week, laid out and drawn; then the grid's draw alone.
        run {
            val host = Host(c, ui)
            var build = 0L; var measure = 0L; var draw = 0L
            val canvas = Canvas(Bitmap.createBitmap(width, 4000, Bitmap.Config.ARGB_8888))
            var grid: WeekGrid? = null
            repeat(n + 1) { i ->
                host.weekShift = i % 8
                val t0 = System.nanoTime()
                val page = WeekScreen.build(host)
                val t1 = System.nanoTime()
                val frame = FrameLayout(c).apply { addView(page) }
                frame.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
                val t2 = System.nanoTime()
                frame.draw(canvas)
                val t3 = System.nanoTime()
                if (i > 0) { build += t1 - t0; measure += t2 - t1; draw += t3 - t2 }
                fun find(v: View): WeekGrid? = v as? WeekGrid ?: (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
                grid = find(frame)
            }
            Log.i("UMTimetable", "bench week page built: build %.2f ms, measure+layout %.2f ms, draw %.2f ms".format(build / 1e6 / n, measure / 1e6 / n, draw / 1e6 / n))
            grid?.let { g -> time("week grid draw") { g.draw(canvas) } }
            // What a swipe does now: the same page shifted in place (a new grid in the carousel), laid out and drawn.
            host.weekShift = 0
            val page = WeekScreen.build(host)
            val frame = FrameLayout(c).apply { addView(page) }
            fun lay() {
                frame.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
            }
            lay()
            var shift = 0L; var layout = 0L; var paint = 0L
            repeat(n + 1) { i ->
                host.weekShift = i % 8 + 1
                val t0 = System.nanoTime()
                WeekScreen.shift(page, host, 1)
                val t1 = System.nanoTime()
                lay()
                val t2 = System.nanoTime()
                frame.draw(canvas)
                val t3 = System.nanoTime()
                (WeekScreen.grid(page)?.parent as? android.view.ViewGroup)?.let { box -> while (box.childCount > 1) box.removeViewAt(0) }
                if (i > 0) { shift += t1 - t0; layout += t2 - t1; paint += t3 - t2 }
            }
            Log.i("UMTimetable", "bench week swipe now: shift %.2f ms, measure+layout %.2f ms, draw %.2f ms".format(shift / 1e6 / n, layout / 1e6 / n, paint / 1e6 / n))
        }
        for (view in listOf("day", "week", "grades", "due", "news", "files")) time("page $view") {
            val host = Host(c, ui)
            val page = when (view) {
                "week" -> WeekScreen.build(host)
                "due" -> SpectrumScreens.due(host)
                "news" -> SpectrumScreens.news(host)
                "files" -> SpectrumScreens.courses(host)
                "grades" -> SpectrumScreens.grades(host)
                else -> DayScreen.build(host, 1)
            }
            val frame = FrameLayout(c).apply { addView(page) }
            frame.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
            frame.draw(Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)))
        }
    }

    /** Just enough of MainActivity for the screens to build: cached data, no actions. */
    private class Host(private val context: Context, override val ui: Ui, private val at: Now? = null, private val scheme0: Scheme? = null) : SpectrumHost {
        override val timetable get() = scheme0?.let { Timetable.parse(Timetable.json(context)).recolored(it) } ?: Timetable.load(context)
        override fun now() = at ?: Now.current()
        override var weekShift = 0
        override fun render() = Unit
        override fun goDay(day: Int, dir: Int) = Unit
        override fun setView(view: String) = Unit
        override fun openSheet(course: Course, session: Session?) = Unit
        override fun openUrl(url: String) = Unit
        override fun openFile(file: org.json.JSONObject, folder: String) = Unit
        override fun showCourse(id: Int) = Unit
        override val look = Look.AUTO
        override fun setLook(look: Look) = Unit
        override val scheme get() = Scheme.load(context)
        override fun setScheme(scheme: Scheme) = Unit
        override val syncing = false
        override var openCourse: Int? = null
        override var expanded: MutableSet<String> = mutableSetOf()
        override val seenNews: Set<String> = context.getSharedPreferences("ui", Context.MODE_PRIVATE).getStringSet("newsSeen", emptySet()) ?: emptySet()
        override val seenGrades: Set<String> = context.getSharedPreferences("ui", Context.MODE_PRIVATE).getStringSet("gradesSeen", emptySet()) ?: emptySet()
        override fun courseContents(id: Int): JSONArray? = SpectrumStore.read(context, "contents-$id")?.let { runCatching { JSONArray(it) }.getOrNull() }
        override fun courseError(id: Int): String? = null
        override fun signIn() = Unit
        override fun signOut() = Unit
        override fun refresh() = Unit
        override fun openMaya() = Unit
        override fun openTimeEdit() = Unit
        override fun enableNotifications() = Unit
        override fun openAppSettings() = Unit
        override fun checkForUpdates() = Unit
    }
}
