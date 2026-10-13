package com.skylightlim.umtimetable

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.AlertDialog
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Calendar
import kotlin.concurrent.thread
import kotlin.math.hypot

/**
 * The app's main screen, drawn natively: Day (with the Week grid), Grades, Due, News and Courses.
 * The timetable comes from the MAYA import.
 */
class MainActivity : Activity(), SpectrumHost {

    override lateinit var ui: Ui
    private lateinit var fonts: Fonts
    override var look = Look.AUTO
        private set
    /** The sign-in poster over the app on first run (see [showWelcome]). */
    private var welcome: View? = null
    /** The top bar's settings button: the Settings sheet opens from it, and a new theme spreads from it. */
    private var settingsButton: View? = null

    override val timetable: Timetable get() = Timetable.load(this)
    override fun now() = Now.current()

    private var view = "day"
    private var sel = 1
    /** The user chose [sel]; otherwise Day follows the next class (see [homeDay]). */
    private var pickedDay = false
    override var openCourse: Int? = null
    override var syncing = false

    /** Set by the first Spectrum sign-in: once it has synced, a new app gets its timetable from TimeEdit. */
    private var timetableAfterSync = false
    override val expanded = mutableSetOf<String>()
    override var seenNews: Set<String> = emptySet()
    override var seenGrades: Set<String> = emptySet()
    private val contents = mutableMapOf<Int, JSONArray>()
    private val contentErrors = mutableMapOf<Int, String>()
    private val loading = mutableSetOf<Int>()

    private lateinit var titleRow: LinearLayout
    private lateinit var titleBox: FrameLayout
    /** What the title says now, so a redraw that doesn't change it doesn't animate it. */
    private var titleKey: String? = null
    private lateinit var strip: LinearLayout
    private lateinit var scroll: SwipeScrollView
    private lateinit var content: FrameLayout
    private lateinit var tabs: LinearLayout
    private val tabViews = mutableMapOf<String, Triple<LinearLayout, ImageView, TextView>>()
    private val badges = mutableMapOf<String, TextView>()
    private lateinit var shell: LinearLayout
    private lateinit var stripFrame: FrameLayout
    private lateinit var stripTrack: LinearLayout
    private lateinit var stripGlider: Motion.Glider
    private lateinit var tabGlider: Motion.Glider
    private var presser: Motion.Presser? = null
    /** Where the strip's pill is (0–4 Monday–Friday, 5 Week) and which tab is lit, so a change animates. */
    private var stripIndex = -1
    private var tabShown: String? = null
    private val badgeCounts = mutableMapOf<String, Int>()
    private var pendingSlide = 0
    /** The next day arrives from the side it was swiped in from, rather than from close by (a tap). */
    private var slideFar = false
    /** onCreate has just drawn the page; drawing it again in onResume would cut its entrance short. */
    private var fresh = false
    private var lastMinute = ""

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val n = now()
            // Only screens that show the clock (countdowns, "in 2 h") need redrawing each minute.
            if ("${n.dow}-${n.min.toInt()}" != lastMinute && view in setOf("day", "week", "due")) {
                if (!pickedDay) sel = homeDay(timetable, n)
                render(keepScroll = true)
            }
            ticker.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Debug builds only: adb can show the screen over the lock screen to screenshot it
        // (am start … --ez debugShowWhenLocked true). Release builds ignore this.
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 && intent.getBooleanExtra("debugShowWhenLocked", false)) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        fonts = Fonts(this)
        look = Look.load(this)
        seenNews = prefs().getStringSet("newsSeen", emptySet()) ?: emptySet()
        seenGrades = prefs().getStringSet("gradesSeen", emptySet()) ?: emptySet()
        sel = homeDay(timetable, now())
        view = intent.getStringExtra(EXTRA_VIEW)?.takeIf { it in VIEWS }
            ?: prefs().getString("view", "day")?.takeIf { it in VIEWS } ?: "day"
        intent.getIntExtra(EXTRA_COURSE, 0).takeIf { it > 0 }?.let { view = "files"; openCourse = it }
        buildShell()
        render()
        if (!SpectrumStore.signedIn(this) && timetable.sessions.isEmpty()) showWelcome()
        fresh = true
        SpectrumSyncJob.ensure(this)
        // Launch timing: Android logs "Fully drawn" with the time since the app started.
        window.decorView.post { reportFullyDrawn() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getIntExtra(EXTRA_COURSE, 0).takeIf { it > 0 }?.let { openCourse = it; setView("files"); return }
        intent.getStringExtra(EXTRA_VIEW)?.takeIf { it in VIEWS }?.let {
            if (it == "day") { pickedDay = false; sel = homeDay(timetable, now()) }
            go(it, null)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!pickedDay) sel = homeDay(timetable, now())
        if (fresh) fresh = false else render(keepScroll = true)
        ticker.post(tick)
        // Grades arrive after the rest of a sync (they can take Spectrum much longer).
        SpectrumSync.onGrades = { if (view == "grades") render(keepScroll = true) else renderTabs() }
        if (SpectrumStore.signedIn(this) && System.currentTimeMillis() - SpectrumStore.lastSync(this) > 10 * 60_000L) refresh()
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
        SpectrumSync.onGrades = null
    }

    private fun prefs() = getSharedPreferences("ui", MODE_PRIVATE)

    /** Every touch goes past the press feedback first; it never takes one. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        presser?.onTouch(ev)
        return super.dispatchTouchEvent(ev)
    }

    // --- shell -----------------------------------------------------------------------------

    private fun buildShell() {
        ui = Ui(this, look.palette(this), fonts)
        val p = ui.p
        window.decorView.setBackgroundColor(p.ground)
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (p.dark) 0 else lightBars, lightBars)

        titleBox = FrameLayout(this)
        titleRow = ui.row().apply { addView(titleBox, ui.grow()) }
        titleKey = null
        // The day strip: a track of slots, the selection pill gliding over it, and the chips on top.
        strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stripTrack = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stripFrame = Motion.RowFrame(this).apply {
            addView(stripTrack, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(strip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        stripGlider = Motion.Glider(stripFrame, strip, ui.dp(12f).toFloat(), 1)
        stripIndex = -1
        val top = ui.column().apply {
            addView(titleRow)
            addView(stripFrame, ui.wide(top = 10f))
        }
        content = FrameLayout(this).apply { setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(24)) }
        scroll = SwipeScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tabBar = Motion.RowFrame(this).apply {
            // The ground colour with a 1px line along the top; the selected tab's pill glides behind the tabs.
            background = LayerDrawable(arrayOf(ui.shape(p.line, 0f), ui.shape(p.ground, 0f))).apply { setLayerInset(1, 0, 1, 0, 0) }
            addView(tabs, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        tabGlider = Motion.Glider(tabBar, tabs, ui.dp(12f).toFloat(), 0)
        tabShown = null
        buildTabs()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.ground)
            addView(top)
            // The pull-to-sync indicator sits behind the page, uncovered as the page is pulled down.
            val pull = PullIndicator(ui) { SpectrumScreens.lastSynced(this@MainActivity) }
            scroll.pullIndicator = pull
            addView(FrameLayout(this@MainActivity).apply {
                addView(pull, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
                addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(tabBar)
        }
        shell = root
        presser = Motion.Presser(root)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            top.setPadding(ui.dp(16) + bars.left, ui.dp(12) + bars.top, ui.dp(16) + bars.right, ui.dp(10))
            tabs.setPadding(ui.dp(16) + bars.left, ui.dp(8), ui.dp(16) + bars.right, ui.dp(8) + bars.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(root)
    }

    private fun buildTabs() {
        tabs.removeAllViews()
        tabViews.clear()
        badges.clear()
        val p = ui.p
        for ((i, t) in TABS.withIndex()) {
            val (id, label, iconRes) = t
            val icon = ui.icon(iconRes, p.muted)
            val badge = ui.text("", 10f, p.accentInk, ui.f.bodyBold).apply {
                background = ui.shape(p.accent, 99f)
                gravity = Gravity.CENTER
                minWidth = ui.dp(16)
                setPadding(ui.dp(4), ui.dp(1), ui.dp(4), ui.dp(1))
                visibility = View.GONE
            }
            val iconBox = FrameLayout(this).apply {
                clipChildren = false
                addView(icon, FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER))
                addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
            }
            val text = ui.text(label, 11.5f, p.muted, ui.f.bodySemi).apply { letterSpacing = 0.04f }
            val tab = ui.column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, ui.dp(6), 0, ui.dp(6))
                clipChildren = false
                contentDescription = label
                addView(iconBox, LinearLayout.LayoutParams(ui.dp(44), ui.dp(24)))
                addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(3) })
                setOnClickListener {
                    // Tapping Courses while inside a course goes back to the course list;
                    // tapping Day while on Day goes back to the day of the next class.
                    if (id == view && id == "files" && openCourse != null) {
                        Haptics.back(this)
                        openCourse = null
                        render()
                    } else if (id == view && id == "day" && pickedDay) {
                        Haptics.back(this)
                        pickedDay = false
                        sel = homeDay(timetable, now())
                        render()
                    } else {
                        setView(id)
                    }
                }
            }
            tabs.addView(tab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = ui.dp(8) })
            tabViews[id] = Triple(tab, icon, text)
            badges[id] = badge
        }
    }

    // --- rendering -------------------------------------------------------------------------

    /** A move to another page (a course, back, a tab tapped again): it arrives with its entrance. */
    override fun render() = render(keepScroll = false, enter = true)

    private fun render(keepScroll: Boolean, enter: Boolean = false) {
        val n = now()
        lastMinute = "${n.dow}-${n.min.toInt()}"
        renderTop(n)
        renderTabs()
        val y = scroll.scrollY
        val page = try {
            when (view) {
                "week" -> WeekScreen.build(this)
                "grades" -> SpectrumScreens.grades(this).also { markGradesSeenSoon() }
                "due" -> SpectrumScreens.due(this)
                "news" -> SpectrumScreens.news(this).also { markNewsSeenSoon() }
                "files" -> SpectrumScreens.courses(this).also { openCourse?.let { loadCourse(it) } }
                else -> DayScreen.build(this, sel)
            }
        } catch (e: Exception) {
            Log.w("UMTimetable", "render failed", e)
            ui.text("Something went wrong showing this page: ${e.message}", 15f, ui.p.muted)
        }
        content.removeAllViews()
        content.addView(page)
        scroll.onSwipe = if (view == "day") { dir -> slideFar = true; goDay(sel + dir, dir) } else null
        // Spectrum's tabs sync when pulled down.
        scroll.onPull = if (view in setOf("grades", "due", "news", "files") && SpectrumStore.signedIn(this)) ::refresh else null
        if (pendingSlide != 0) {
            // Another day: from the side it was swiped in from, or from close by after a tap.
            Motion.slideIn(page, pendingSlide * if (slideFar) scroll.width * 0.35f else ui.dp(40f).toFloat())
            pendingSlide = 0
        } else if (enter) {
            Motion.enter(page, ui.dp(16f).toFloat())
        }
        slideFar = false
        if (keepScroll) scroll.post { scroll.scrollTo(0, y) } else scroll.scrollTo(0, 0)
        updateBack()
    }

    private fun renderTop(n: Now) {
        val p = ui.p
        val tt = timetable
        showTitle(n)
        while (titleRow.childCount > 1) titleRow.removeViewAt(1)
        // Settings (theme, imports, notifications, vibration, account) open from here, as in redesign 1p.
        titleRow.addView(FrameLayout(this).apply {
            background = ui.pressable(ui.shape(Color.TRANSPARENT, 99f), 99f)
            contentDescription = "Settings"
            addView(ui.icon(R.drawable.ic_settings, p.text, 20f), FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER))
            setOnClickListener { Haptics.tick(this); SettingsSheet.show(this@MainActivity, this@MainActivity) }
            settingsButton = this
        }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)).apply { marginEnd = -ui.dp(10) })
        stripFrame.visibility = if (view == "day" || view == "week") View.VISIBLE else View.GONE
        if (stripFrame.visibility != View.VISIBLE) return
        strip.removeAllViews()
        stripTrack.removeAllViews()
        val index = if (view == "week") 5 else sel - 1
        // The pill glides; the two labels it leaves and reaches change colour with it.
        val moved = stripIndex != -1 && stripIndex != index
        fun fg(today: Boolean, selected: Boolean) = when {
            selected && today -> p.accentInk
            selected -> p.ground
            today -> p.accentText
            else -> p.text
        }
        for (i in 0..5) {
            stripTrack.addView(View(this).apply { background = ui.shape(p.surface, 12f) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, if (i == 5) 1.15f else 1f).apply { if (i > 0) marginStart = ui.dp(6) })
        }
        // Every chip is as tall as the tallest (match parent in the strip), so the pill covers the Week chip too.
        fun chip(description: String, vararg parts: View) = ui.column().apply {
            gravity = Gravity.CENTER
            background = ui.pressable(ui.shape(Color.TRANSPARENT, 12f), 12f)
            minimumHeight = ui.dp(60)
            setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8))
            contentDescription = description
            for ((k, part) in parts.withIndex()) {
                val lp = part.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(part, lp.apply { if (k > 0) topMargin = ui.dp(if (k == 1) 2 else 5) })
            }
        }
        for (d in 1..5) {
            val selected = index == d - 1
            val days = daysFromToday(tt, n, d)
            val today = days == 0
            val name = ui.text(DAY_SHORT[d], 10.5f, fg(today, selected), ui.f.bodyBold, caps = true, spacing = 0.1f)
            val date = ui.text(dateIn(days).get(Calendar.DAY_OF_MONTH).toString(), 22f, fg(today, selected), ui.f.sign)
            for (t in listOf(name, date)) {
                if (moved && selected) Motion.tint(t, fg(today, false), fg(today, true))
                if (moved && stripIndex == d - 1) Motion.tint(t, fg(today, true), fg(today, false), 90)
            }
            val dots = ui.row(gap = 3f).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(6))
                for (s in tt.day(d)) addView(View(context).apply { background = ui.shape(s.course.color, 99f) }, LinearLayout.LayoutParams(ui.dp(6), ui.dp(6)))
            }
            val description = tt.day(d).size.let { k -> "${DAY_NAMES[d]} ${dateText(days)}, $k ${if (k == 1) "class" else "classes"}" }
            strip.addView(chip(description, name, date, dots).apply {
                setOnClickListener { pickedDay = true; goDay(d) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (d > 1) marginStart = ui.dp(6) })
        }
        // The week grid lives under Day: one more chip after Friday, saying which week of the semester it is.
        val week = index == 5
        val semester = AcademicCalendar.week(LocalDate.now().plusDays(daysFromToday(tt, n, 1).toLong()))
        val (weekName, weekOf) = when (semester?.kind) {
            AcademicCalendar.Kind.TEACHING -> "W${semester.n}" to "of ${semester.of}"
            AcademicCalendar.Kind.BREAK -> "Break" to null
            AcademicCalendar.Kind.REVISION -> "Revision" to null
            AcademicCalendar.Kind.EXAMS -> "Exams" to null
            null -> "Week" to null
        }
        val weekParts = listOfNotNull(
            ui.text(weekName, 22f, if (week) p.ground else p.text, ui.f.sign),
            weekOf?.let { ui.text(it, 10.5f, if (week) p.ground else p.muted, ui.f.bodyBold, caps = true, spacing = 0.1f) },
        )
        for ((k, t) in weekParts.withIndex()) {
            val rest = if (k == 0) p.text else p.muted
            if (moved && week) Motion.tint(t, rest, p.ground)
            if (moved && stripIndex == 5) Motion.tint(t, p.ground, rest, 90)
        }
        val weekWords = if (semester?.kind == AcademicCalendar.Kind.TEACHING) "Week ${semester.n} of ${semester.of} of the semester" else weekName
        strip.addView(chip("$weekWords, ${tt.sessions.size} classes a week", *weekParts.toTypedArray()).apply {
            setOnClickListener { setView("week") }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f).apply { marginStart = ui.dp(6) })
        stripGlider.moveTo(index, if (view == "day" && daysFromToday(tt, n, sel) == 0) p.accent else p.text)
        stripIndex = index
    }

    /**
     * The top bar's title. Day: the chosen day and its date; Week: the dates of the strip's week;
     * elsewhere today's date, small. A new title's words come in from the side the day moved to (or
     * rise, for another tab) while the old one slips away.
     */
    private fun showTitle(n: Now) {
        val p = ui.p
        val tt = timetable
        val big = view == "day" || view == "week"
        val days = daysFromToday(tt, n, sel)
        val words = when (view) {
            "day" -> listOf(DAY_NAMES[sel], dateText(days))
            "week" -> listOf(weekRange(daysFromToday(tt, n, 1)))
            "grades", "due", "news", "files" -> emptyList()
            else -> listOf("${DAY_NAMES[n.dow]} ${dateText(0)}")
        }
        val today = view == "day" && days == 0
        val key = "$big $today $words"
        if (key == titleKey) return
        titleKey = key
        val title = ui.row(gap = 9f)
        for (w in words) title.addView(if (big) ui.text(w, 34f, p.text, ui.f.sign, lines = 1) else ui.label(w, p.muted, 11f))
        if (today) title.addView(ui.chip("Today", p.accent, p.accentInk, 11f).apply { setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.dp(2) })
        val old = (0 until titleBox.childCount).map(titleBox::getChildAt)
        titleBox.addView(title, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        if (old.isEmpty()) return
        if (!Motion.enabled) return old.forEach(titleBox::removeView)
        Motion.retitle(old, title, pendingSlide * ui.dp(32f).toFloat(), ui.dp(10f).toFloat())
    }

    override fun setLook(look: Look) {
        if (look != this.look) switchLook(settingsButton ?: shell, look)
    }

    /** First run (no sign-in, no timetable): the sign-in poster (redesign 1t, in UM's colours) over the app. */
    private fun showWelcome() {
        if (welcome != null) return
        val poster = SignInScreen.build(ui) { signIn() }
        findViewById<FrameLayout>(android.R.id.content).addView(poster, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        poster.requestApplyInsets()
        welcome = poster
        // Light status and navigation icons on UM blue, whatever the theme.
        val dark = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(0, dark)
    }

    /** Signed in, or a timetable imported: the poster lifts away to the app. */
    private fun hideWelcome() {
        val poster = welcome ?: return
        welcome = null
        val dark = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (ui.p.dark) 0 else dark, dark)
        fun remove() { (poster.parent as? ViewGroup)?.removeView(poster) }
        if (!Motion.enabled) { remove(); return }
        poster.animate().translationY(-poster.height.toFloat()).setInterpolator(Motion.EXIT).setDuration(320).withEndAction { remove() }.start()
    }

    /** The new theme spreads out from [from] (the settings button), over a picture of the old one. */
    private fun switchLook(from: View, to: Look) {
        val old = shell
        val y = scroll.scrollY
        val picture = if (Motion.enabled && old.width > 0 && old.height > 0)
            Bitmap.createBitmap(old.width, old.height, Bitmap.Config.ARGB_8888).also { old.draw(Canvas(it)) } else null
        val at = IntArray(2).also(from::getLocationInWindow)
        val cx = at[0] + from.width / 2
        val cy = at[1] + from.height / 2
        look = to
        Look.save(this, look)
        buildShell()
        render(keepScroll = false)
        scroll.post { scroll.scrollTo(0, y) }
        if (picture == null) return
        val holder = findViewById<FrameLayout>(android.R.id.content)
        val cover = ImageView(this).apply { setImageBitmap(picture) }
        holder.addView(cover, 0, FrameLayout.LayoutParams(old.width, old.height))
        shell.post {
            val origin = IntArray(2).also(shell::getLocationInWindow)
            val x = cx - origin[0]
            val y0 = cy - origin[1]
            val radius = hypot(maxOf(x, shell.width - x).toFloat(), maxOf(y0, shell.height - y0).toFloat())
            ViewAnimationUtils.createCircularReveal(shell, x, y0, 0f, radius).apply {
                interpolator = Motion.SMOOTH
                duration = Motion.SMOOTH.duration + 150
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        holder.removeView(cover)
                        picture.recycle()
                    }
                })
            }.start()
        }
    }

    private fun renderTabs() {
        val p = ui.p
        val unread = SpectrumStore.array(this, "news").objects().count { it.optBoolean("forMe", true) && it.getInt("id").toString() !in seenNews }
        val newMarks = SpectrumStore.array(this, "grades").objects().count { !it.isNull("raw") && !SpectrumSync.isAttendance(it) && SpectrumSync.gradeKey(it) !in seenGrades }
        val counts = mapOf("news" to unread, "grades" to newMarks, "due" to SpectrumScreens.openAttendance(this))
        var lit = 0
        for ((id, views) in tabViews) {
            val (_, icon, text) = views
            val selected = id == view || (id == "day" && view == "week")
            val color = if (selected) p.accentText else p.muted
            icon.imageTintList = ColorStateList.valueOf(color)
            text.setTextColor(color)
            if (selected) lit = TABS.indexOfFirst { it.first == id }
            val n = counts[id] ?: 0
            badges[id]?.apply {
                visibility = if (n > 0) View.VISIBLE else View.GONE
                setText(n.toString())
                // A new count pops in.
                if (n > 0 && n != (badgeCounts[id] ?: 0)) Motion.pop(this)
            }
            badgeCounts[id] = n
        }
        // The pill glides to the lit tab, whose icon gives a small hop.
        val shown = TABS[lit].first
        if (tabShown != null && tabShown != shown) tabViews[shown]?.second?.let { Motion.pop(it, 0.7f) }
        tabShown = shown
        tabGlider.moveTo(lit, p.surface)
    }

    private fun markNewsSeenSoon() {
        ticker.postDelayed({
            val ids = SpectrumStore.array(this, "news").objects().map { it.getInt("id").toString() }
            if (ids.all { it in seenNews }) return@postDelayed
            seenNews = (seenNews + ids).toList().takeLast(500).toSet()
            prefs().edit().putStringSet("newsSeen", seenNews).apply()
            renderTabs() // the dots on the cards stay until the next redraw, so they're seen first
        }, 1500)
    }

    private fun markGradesSeenSoon() {
        ticker.postDelayed({
            val keys = SpectrumStore.array(this, "grades").objects().filter { !it.isNull("raw") }.map { SpectrumSync.gradeKey(it) }
            if (keys.all { it in seenGrades }) return@postDelayed
            seenGrades = (seenGrades + keys).toList().takeLast(500).toSet()
            prefs().edit().putStringSet("gradesSeen", seenGrades).apply()
            renderTabs() // the dots on the marks stay until the next redraw, so they're seen first
        }, 1500)
    }

    // --- navigation ------------------------------------------------------------------------

    override fun setView(view: String) = go(view, Haptics::tick)

    /** Shows [view]; [feel] is the vibration for the move (none when the app is only being opened there). */
    private fun go(view: String, feel: ((View) -> Unit)?) {
        if (view !in VIEWS) return
        val changed = view != this.view
        if (changed) scroll.resetPull()
        this.view = view
        prefs().edit().putString("view", view).apply()
        if (changed) feel?.invoke(shell)
        render(keepScroll = false, enter = changed)
    }

    override fun goDay(day: Int, dir: Int) {
        val d = when {
            day < 1 -> 5
            day > 5 -> 1
            else -> day
        }
        pickedDay = true
        if (d == sel && view == "day") return
        pendingSlide = if (view != "day") 0 else if (dir != 0) dir else if (d > sel) 1 else -1
        if (view == "day" && !slideFar) Haptics.tick(shell)
        sel = d
        setView("day")
    }

    // Back: leave a course page, then return to Day; on Day, Android's own back leaves the app.
    private val back = OnBackInvokedCallback {
        if (view == "files" && openCourse != null) {
            Haptics.back(shell)
            openCourse = null
            render()
        } else if (view != "day") {
            go("day", Haptics::back)
        }
    }
    private var backRegistered = false

    private fun updateBack() {
        val want = view != "day"
        if (want == backRegistered) return
        backRegistered = want
        if (want) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, back)
        else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(back)
    }

    override fun openSheet(course: Course, session: Session?) = ClassSheet.show(this, this, course, session)

    override fun showCourse(id: Int) {
        openCourse = id
        setView("files")
    }

    // --- Spectrum --------------------------------------------------------------------------

    override fun refresh() {
        if (syncing) return
        syncing = true
        render(keepScroll = true)
        thread {
            SpectrumSync.run(applicationContext)
            runOnUiThread {
                syncing = false
                scroll.endPull()
                contents.clear() // course pages refresh the next time they're opened
                contentErrors.clear()
                render(keepScroll = true)
                // The same SiswaMail session signs TimeEdit in, so the user doesn't sign in again.
                if (timetableAfterSync) {
                    timetableAfterSync = false
                    if (Timetable.load(this).sessions.isEmpty() && SpectrumStore.occurrences(this).length() > 0) openTimeEdit()
                }
            }
        }
    }

    override fun courseContents(id: Int) = contents[id]
    override fun courseError(id: Int) = contentErrors[id]

    private fun loadCourse(id: Int) {
        if (id in contents || id in contentErrors || !loading.add(id)) return
        thread {
            val json = SpectrumSync.courseContents(applicationContext, id)
            runOnUiThread {
                loading.remove(id)
                runCatching { JSONArray(json) }
                    .onSuccess { contents[id] = it }
                    .onFailure { contentErrors[id] = runCatching { JSONObject(json).optString("error") }.getOrNull() ?: "Couldn't read this course." }
                if (view == "files" && openCourse == id) render(keepScroll = true)
            }
        }
    }

    override fun openUrl(url: String) = SpectrumWebActivity.open(this, url)

    override fun openFile(file: JSONObject, folder: String) =
        Downloads.open(this, file.optString("url"), file.optString("name"), file.optString("mime"), file.optLong("size"), folder)

    @Suppress("DEPRECATION")
    override fun signIn() = startActivityForResult(Intent(this, LoginActivity::class.java), REQ_LOGIN)

    /** One tap on a link at the bottom of Courses shouldn't wipe the sign-in: ask first. */
    override fun signOut() {
        AlertDialog.Builder(this)
            .setTitle("Sign out of Spectrum?")
            .setMessage("The app forgets your sign-in and what it saved from Spectrum: courses, announcements, deadlines and marks. Your timetable stays. TimeEdit is signed out too.")
            .setPositiveButton("Sign out") { _, _ -> signOutNow() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun signOutNow() {
        SpectrumStore.noteSignOut(this, "you tapped Sign out")
        SpectrumStore.signOut(this)
        SpectrumSyncJob.cancel(this)
        Reminders.schedule(this)
        AttendanceWatch.schedule(this)
        CookieManager.getInstance().removeAllCookies(null)
        TimetableWidget.updateAll(this)
        openCourse = null
        contents.clear()
        render()
    }

    @Suppress("DEPRECATION")
    override fun openMaya() = startActivityForResult(Intent(this, MayaActivity::class.java), REQ_MAYA)

    @Suppress("DEPRECATION")
    override fun openTimeEdit() = startActivityForResult(Intent(this, TimeEditActivity::class.java), REQ_TIMEEDIT)

    override fun enableNotifications() {
        if (!Notify.allowed(this)) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
    }

    override fun openAppSettings() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    @Deprecated("Activity result API from androidx is not used in this dependency-free app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_LOGIN -> {
                hideWelcome()
                enableNotifications()
                SpectrumSyncJob.schedule(this)
                timetableAfterSync = Timetable.load(this).sessions.isEmpty()
                refresh()
            }
            REQ_MAYA, REQ_TIMEEDIT -> {
                hideWelcome()
                go("week", null)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render(keepScroll = true)
    }

    companion object {
        const val EXTRA_VIEW = "view"
        /** A Spectrum course id to open in the Courses tab. */
        const val EXTRA_COURSE = "course"
        private const val REQ_LOGIN = 1
        private const val REQ_NOTIFY = 2
        private const val REQ_MAYA = 3
        private const val REQ_TIMEEDIT = 4
        private val TABS = listOf(
            Triple("day", "Day", R.drawable.ic_tab_day),
            Triple("grades", "Grades", R.drawable.ic_tab_grades),
            Triple("due", "Due", R.drawable.ic_tab_due),
            Triple("news", "News", R.drawable.ic_tab_news),
            Triple("files", "Courses", R.drawable.ic_tab_courses),
        )
        /** The week grid has no tab of its own; it's the Week chip under Day. */
        private val VIEWS = TABS.map { it.first }.toSet() + "week"
    }
}
