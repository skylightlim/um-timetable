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
 * --ez open true draws every fold and "Show more" opened.
 * view sheet draws a class sheet: --es code WIX1002 for the course's sheet, plus --ei session N for its
 * Nth class of the week (0 first).
 */
class DebugRenderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.hasExtra("bench")) return bench(context, intent.getIntExtra("bench", 20))
        if (intent.getStringExtra("view") == "icon") return icon(context)
        val view = intent.getStringExtra("view") ?: "files"
        val dark = intent.getBooleanExtra("dark", false)
        val width = intent.getIntExtra("width", context.resources.displayMetrics.widthPixels)
        val c = ContextThemeWrapper(context, R.style.Theme_UMTimetable)
        val now = if (intent.hasExtra("dow")) Now(intent.getIntExtra("dow", 2), intent.getIntExtra("min", 700).toFloat()) else Now.current()
        val host = Host(c, Ui(c, if (dark) Palette.DARK else Palette.LIGHT, Fonts(c)), now).apply {
            openCourse = intent.getIntExtra("course", 0).takeIf { it > 0 }
            if (intent.getBooleanExtra("open", false)) expanded = object : HashSet<String>() { override fun contains(element: String) = true }
        }
        val page = when (view) {
            "week" -> WeekScreen.build(host)
            "due" -> if (intent.getBooleanExtra("sample", false)) sampleDue(context, host) else SpectrumScreens.due(host)
            "news" -> SpectrumScreens.news(host)
            "files" -> SpectrumScreens.courses(host)
            "grades" -> if (intent.getBooleanExtra("sample", false)) SpectrumScreens.grades(host, sampleGrades(context)) else SpectrumScreens.grades(host)
            "sheet" -> {
                val tt = Timetable.load(context)
                val course = tt.courses.first { it.code == intent.getStringExtra("code") }
                val session = intent.getIntExtra("session", -1).takeIf { it >= 0 }
                    ?.let { i -> tt.sessions.filter { it.course == course }.sortedWith(compareBy({ it.day }, { it.start }))[i] }
                ClassSheet.build(host, c, course, session) {}
            }
            else -> DayScreen.build(host, intent.getIntExtra("day", homeDay(Timetable.load(context), now)))
        }
        val frame = FrameLayout(c).apply {
            setBackgroundColor(host.ui.p.ground)
            // A sheet runs edge to edge, from the bottom of the screen.
            if (view != "sheet") setPadding(host.ui.dp(16), 0, host.ui.dp(16), host.ui.dp(24))
            addView(page)
        }
        frame.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
        val bitmap = Bitmap.createBitmap(frame.measuredWidth, frame.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        frame.draw(Canvas(bitmap))
        save(context, bitmap, "$view${host.openCourse?.let { "-$it" } ?: ""}${if (dark) "-dark" else ""}")
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

    private fun save(context: Context, bitmap: Bitmap, name: String) {
        val out = File(context.cacheDir, "render/$name.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i("UMTimetable", "debug render: ${out.name} ${bitmap.width}x${bitmap.height}")
    }

    private fun icon(context: Context) {
        val icon = context.getDrawable(R.mipmap.ic_launcher) as android.graphics.drawable.AdaptiveIconDrawable
        for ((name, drawable) in listOf("icon" to icon, "icon-mono" to icon.monochrome)) {
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
    private class Host(private val context: Context, override val ui: Ui, private val at: Now? = null) : SpectrumHost {
        override val timetable get() = Timetable.load(context)
        override fun now() = at ?: Now.current()
        override fun render() = Unit
        override fun goDay(day: Int, dir: Int) = Unit
        override fun setView(view: String) = Unit
        override fun openSheet(course: Course, session: Session?) = Unit
        override fun openUrl(url: String) = Unit
        override fun openFile(file: org.json.JSONObject, folder: String) = Unit
        override fun showCourse(id: Int) = Unit
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
    }
}
