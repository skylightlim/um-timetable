package com.skylightlim.umtimetable

import android.Manifest
import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
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
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.skylightlim.umtimetable.Motion.spring
import org.json.JSONObject
import java.time.LocalDate
import java.util.Calendar
import kotlin.concurrent.thread
import kotlin.math.hypot

/**
 * The app's main screen, drawn natively: Day (with the Week grid), Due, Courses (with each course's news
 * and grades) and Settings.
 * The timetable comes from the MAYA import.
 */
class MainActivity : Activity(), SpectrumHost {

    override lateinit var ui: Ui
    private lateinit var fonts: Fonts
    override var look = Look.AUTO
        private set
    override var scheme = Scheme.GOLD
        private set
    /** The sign-in poster over the app on first run (see [showWelcome]). */
    private var welcome: View? = null
    /** Settings redrawn after a new look or theme: what moves on it (the look's pill, the theme's dots). */
    private var settingsMoved: SettingsSheet.Moved? = null
    /** Where a new look or theme spreads from: what was tapped in Settings, else the title. */
    private var spreadFrom: View? = null
    /** The attendance log is open over Settings, which were scrolled to [settingsY]. */
    private var settingsLog = false
    private var settingsY = 0

    override val timetable: Timetable get() = Timetable.load(this)
    override fun now() = Now.current()

    private var view = "day"
    private var sel = 1
    /** The user chose [sel]; otherwise Day follows the next class (see [homeDay]). */
    private var pickedDay = false
    /** Weeks from the strip's own week to the one shown, moved by swiping on Week; back to 0 off Day and Week. */
    override var weekShift = 0
    override var openCourse: Int? = null
        set(value) {
            if (value != field) sectionPages.clear()
            field = value
        }
    /** The section pages open over the course page, innermost last, each with the scroll of the page it covers. */
    private val sectionPages = ArrayList<Pair<Int, Int>>()
    override val openSection get() = sectionPages.lastOrNull()?.first
    override var syncing = false

    /** Set by the first Spectrum sign-in: once it has synced, a new app gets its timetable from TimeEdit. */
    private var timetableAfterSync = false
    override val expanded = mutableSetOf<String>()
    override var seenNews: Set<String> = emptySet()
    override var seenGrades: Set<String> = emptySet()
    private val contents = mutableMapOf<Int, JSONObject>()
    private val contentErrors = mutableMapOf<Int, String>()
    private val loading = mutableSetOf<Int>()

    private lateinit var titleRow: Motion.RowFrame
    private lateinit var titleBox: FrameLayout
    /** What the title says now, so a redraw that doesn't change it doesn't animate it. */
    private var titleKey: String? = null
    private lateinit var strip: LinearLayout
    private lateinit var scroll: SwipeScrollView
    private lateinit var content: FrameLayout
    private lateinit var tabs: LinearLayout
    /** The Sections tab on the left edge, over a course page, and what a tap on it opens ([sectionsTab]). */
    private lateinit var sectionsTabView: View
    private var sectionsOpen: (() -> Unit)? = null
    private val tabViews = mutableMapOf<String, Triple<LinearLayout, ImageView, TextView>>()
    private val badges = mutableMapOf<String, TextView>()
    private lateinit var shell: LinearLayout
    private lateinit var stripFrame: FrameLayout
    private lateinit var stripTrack: LinearLayout
    private lateinit var stripGlider: Motion.Glider
    private lateinit var tabGlider: Motion.Glider
    private var presser: Motion.Presser? = null
    /** Where the strip's pill is (0–4 Monday–Friday; 5 on Week, where it's put away) and which tab is lit, so a change animates. */
    private var stripIndex = -1
    /** The strip's day chips (the chip, its day name and its date), for a week shift to change in place. */
    private val stripDays = mutableListOf<Triple<View, TextView, TextView>>()
    /** Each day chip's dots, one a class in that day's order (Day's strip; none on Week's). */
    private val stripDots = mutableListOf<List<View>>()
    /** Whether the strip shows Week's days (true) or Day's (false); null while it's hidden (the other tabs). */
    private var stripWeek: Boolean? = null
    /** Day's Week chip, after Friday (Week's strip has none: its label is in the title). */
    private var weekChip: View? = null
    /** What flies over the app between Day and Week ([FlightLayer]), while something does. */
    private var flights: FlightLayer? = null
    /**
     * A rocket is on its way to Week's title ([ROCKET_TITLE]) or Day's strip ([ROCKET_STRIP]) with the week of the
     * semester: what it brings (the title's "W5", the Week chip) stays hidden, even through a redraw, until it hits.
     */
    private var rocketTo: String? = null
    /** When Week's title's week of the semester was last tapped: a second tap soon after, on another week, brings back this one. */
    private var weekMarkTap = 0L
    private var tabShown: String? = null
    /** Whether the Day tab says Week (on the week grid); null after the tabs are built, so it's set without animating. */
    private var dayTabWeek: Boolean? = null
    private val badgeCounts = mutableMapOf<String, Int>()
    private var pendingSlide = 0
    /** The next day arrives from the side it was swiped in from, rather than from close by (a tap). */
    private var slideFar = false
    /** The next page rises from below (1: Day swiped up to Week) or drops from above (-1: Week swiped down to Day). */
    private var pendingRise = 0
    /** Day is being swiped to Week (up, or on past Monday or Friday): the strip's dots fly into the grid's classes. */
    private var dotsFly = false
    /** onCreate has just drawn the page; drawing it again in onResume would cut its entrance short. */
    private var fresh = false
    private var lastMinute = ""

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val n = now()
            // Only screens that show the clock (countdowns, "in 2 h") need redrawing each minute.
            // Not under a selection box on Week: redrawing would drop it.
            if ("${n.dow}-${n.min.toInt()}" != lastMinute && view in setOf("day", "week", "due") && !WeekGrid.holding) {
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
        // Draw behind the status and navigation bars (the insets listener pads for them), as Android 15+
        // does by itself: on 12 to 14 the bars would otherwise be bare grey strips.
        window.setDecorFitsSystemWindows(false)
        fonts = Fonts(this)
        look = Look.load(this)
        scheme = Scheme.load(this)
        seenNews = uiPrefs(this).getStringSet("newsSeen", emptySet()) ?: emptySet()
        seenGrades = uiPrefs(this).getStringSet("gradesSeen", emptySet()) ?: emptySet()
        sel = homeDay(timetable, now())
        view = viewOf(intent.getStringExtra(EXTRA_VIEW)) ?: viewOf(uiPrefs(this).getString("view", "day")) ?: "day"
        intent.getIntExtra(EXTRA_COURSE, 0).takeIf { it > 0 }?.let { view = "files"; openCourse = it }
        intent.getIntExtra(EXTRA_FOLD, 0).takeIf { it > 0 }?.let { view = "files"; openFold(it) }
        buildShell()
        render()
        if (!SpectrumStore.signedIn(this) && timetable.sessions.isEmpty()) showWelcome()
        // A newer release on GitHub? Asked at most every 12 hours, and only said when there is one.
        else Updater.check(this, ui, quiet = true)
        fresh = true
        SpectrumSyncJob.ensure(this)
        // Launch timing: Android logs "Fully drawn" with the time since the app started.
        window.decorView.post { reportFullyDrawn() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getIntExtra(EXTRA_COURSE, 0).takeIf { it > 0 }?.let { openCourse = it; setView("files"); return }
        intent.getIntExtra(EXTRA_FOLD, 0).takeIf { it > 0 }?.let { openFold(it); if (view == "files") render() else setView("files"); return }
        viewOf(intent.getStringExtra(EXTRA_VIEW))?.let {
            if (it == "day") { pickedDay = false; weekShift = 0; sel = homeDay(timetable, now()) }
            go(it, null)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!pickedDay) sel = homeDay(timetable, now())
        // Back from what a Settings row opened (TimeEdit, Android's settings, the sign-in), the page shows how it went.
        if (fresh) fresh = false else render(keepScroll = true)
        ticker.post(tick)
        // Grades arrive after the rest of a sync (they can take Spectrum much longer).
        SpectrumSync.onGrades = { if (view == "files" && openCourse == null) render(keepScroll = true) else renderTabs() }
        if (SpectrumStore.signedIn(this) && System.currentTimeMillis() - SpectrumStore.lastSync(this) > 10 * 60_000L) refresh()
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
        SpectrumSync.onGrades = null
    }

    /** Every touch goes past the press feedback first; it never takes one. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        presser?.onTouch(ev)
        return super.dispatchTouchEvent(ev)
    }

    // --- shell -----------------------------------------------------------------------------

    private fun buildShell() {
        ui = Ui(this, look.palette(this, scheme), fonts)
        val p = ui.p
        window.decorView.setBackgroundColor(p.ground)
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (p.dark) 0 else lightBars, lightBars)

        // Settings are the last tab; a long press on the title opens them too, as does pulling the Courses
        // list up. TalkBack offers the long press as "Open settings".
        titleBox = FrameLayout(this).apply {
            isHapticFeedbackEnabled = false // the app's own vibration instead, at the user's level
            setOnLongClickListener { Haptics.threshold(it, true); openSettings(); true }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, "Open settings"))
                }
            }
        }
        // Swiping across the title moves one tab, as across the tab bar; a long press still opens Settings.
        titleRow = Motion.RowFrame(this).apply {
            addView(titleBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            onSwipe = ::swipeTab
        }
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
        stripWeek = null
        val top = ui.column().apply {
            addView(titleRow)
            // 16dp under the title, as the Spectrum tabs' pages start 10dp + 6dp under theirs.
            addView(stripFrame, ui.wide(top = 16f))
        }
        // Not clipped to its padding: a card sliding in with another day, or springing back from a press,
        // passes through the 16dp margins, and was cut off there as if it went behind something.
        content = FrameLayout(this).apply {
            setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(24))
            clipToPadding = false
        }
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
        // Swiping across the tab bar moves one tab, and the page slides in from that side.
        tabBar.onSwipe = ::swipeTab
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
            // And the settings gear below it, uncovered as the Courses list is pulled up.
            val pullUp = PullUpIndicator(ui)
            scroll.pullUpIndicator = pullUp
            // A course page's Sections tab peeks out of the left edge, over the page, halfway down.
            sectionsTabView = FrameLayout(this@MainActivity).apply {
                val r = ui.dp(14f).toFloat()
                background = ui.pressable(android.graphics.drawable.GradientDrawable().apply {
                    setColor(p.surface)
                    setStroke(1, p.line)
                    cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                }, 14f)
                elevation = ui.dp(4f).toFloat()
                contentDescription = "Sections: every section of this course, to go to one"
                addView(ui.icon(R.drawable.ic_sections, p.text, 18f), FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER))
                visibility = View.GONE
                setOnClickListener { Haptics.tick(it); sectionsOpen?.invoke() }
            }
            addView(FrameLayout(this@MainActivity).apply {
                addView(pull, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
                addView(pullUp, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
                addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(sectionsTabView, FrameLayout.LayoutParams(ui.dp(30), ui.dp(60), Gravity.START or Gravity.CENTER_VERTICAL))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(tabBar)
        }
        shell = root
        presser = Motion.Presser(root)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            // 6dp under the status bar (was 22): the title's own font already leaves 10dp over its capitals.
            top.setPadding(ui.dp(16) + bars.left, ui.dp(6) + bars.top, ui.dp(16) + bars.right, ui.dp(10))
            tabs.setPadding(ui.dp(16) + bars.left, ui.dp(8), ui.dp(16) + bars.right, ui.dp(8) + bars.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(root)
    }

    /** A swipe across the tab bar or the title: the next tab ([dir] 1) or the one before (-1), sliding in from that side. */
    private fun swipeTab(dir: Int) {
        val here = TABS.indexOfFirst { it.first == if (view == "week") "day" else view }
        TABS.getOrNull(here + dir)?.let { next ->
            pendingSlide = dir
            slideFar = true
            setView(next.first)
        }
    }

    private fun buildTabs() {
        tabs.removeAllViews()
        tabViews.clear()
        badges.clear()
        dayTabWeek = null
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
            // One line, shrinking if it must: at a large system font size on a narrow phone "Courses" wrapped to "Course / s".
            val text = ui.fitText(label, 11.5f, p.muted, ui.f.bodySemi, lines = 1).apply { letterSpacing = 0.04f }
            val tab = ui.column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, ui.dp(6), 0, ui.dp(6))
                clipChildren = false
                contentDescription = label
                addView(iconBox, LinearLayout.LayoutParams(ui.dp(44), ui.dp(24)))
                addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(3) })
                setOnClickListener {
                    // Tapping Courses while inside a course goes back to the course list. The first tab
                    // toggles: on Day ("Day") it opens Week, on Week ("Week") it opens Day, each sliding
                    // in as along the strip.
                    if (id == view && id == "files" && openCourse != null) {
                        Haptics.back(this)
                        openCourse = null
                        render()
                    } else if (id == view && id == "settings" && settingsLog) {
                        logBack()
                    } else if (id == "day" && view == "day") {
                        goCell(WEEK_CELL)
                    } else if (id == "day" && view == "week") {
                        goCell(sel)
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

    override fun redraw() = render(keepScroll = true)

    private fun render(keepScroll: Boolean, enter: Boolean = false) {
        val n = now()
        lastMinute = "${n.dow}-${n.min.toInt()}"
        // Where the dots are before the strip changes, and the grid to come waiting for them.
        val dots = flyingDots()
        renderTop(n)
        renderTabs()
        val y = scroll.scrollY
        // A course page puts its Sections tab back as it's built (sectionsTab).
        val hadTab = sectionsOpen != null
        sectionsOpen = null
        val page = try {
            when (view) {
                "week" -> WeekScreen.build(this)
                "due" -> SpectrumScreens.due(this)
                "files" -> SpectrumScreens.courses(this).also { openCourse?.let { loadCourse(it) } }
                "settings" -> if (settingsLog) AttendanceLogPage.build(this) else settingsPage()
                else -> DayScreen.build(this, sel)
            }
        } catch (e: Exception) {
            Log.w("UMTimetable", "render failed", e)
            ui.text("Something went wrong showing this page: ${e.message}", 15f, ui.p.muted)
        }
        content.removeAllViews()
        showSectionsTab(hadTab)
        // Nor is the page clipped: its cards rise into place, and spring back after a press, a little past its edges.
        (page as? ViewGroup)?.clipChildren = false
        content.addView(page)
        // Swipes step along the strip's cells, the Week grid among them (after Friday, before Monday).
        // Swipes on Day step along the strip's cells, the Week grid among them (after Friday, before Monday).
        // On Week, a swipe on the grid goes to the next or the previous week (only the grid moves, a
        // carousel); a swipe anywhere else steps to Monday or Friday, as from the Week cell.
        scroll.swipeTarget = if (view == "week") { { content.getChildAt(content.childCount - 1)?.let(WeekScreen::grid) } } else null
        scroll.onSwipeTarget = if (view == "week") { dir -> slideFar = true; shiftWeek(dir, scroll.releaseVelocity) } else null
        scroll.onSwipe = when (view) {
            "day" -> { dir -> slideFar = true; dotsFly = stripCell(sel, dir) == WEEK_CELL; goCell(stripCell(sel, dir), dir) }
            "week" -> { dir -> slideFar = true; goCell(stripCell(WEEK_CELL, dir), dir) }
            else -> null
        }
        // Up from Day is Week, and down from Week the day it came from: the page goes as the swipe passes the line.
        scroll.onFlipUp = if (view == "day") { { flip(WEEK_CELL, 1) } } else null
        scroll.onFlipDown = if (view == "week") { { flip(sel, -1) } } else null
        // Spectrum's tabs sync when pulled down.
        scroll.onPull = if (view in setOf("due", "files") && SpectrumStore.signedIn(this)) ::refresh else null
        // The Courses list (where Settings used to sit, under the courses) opens them when pulled up.
        scroll.onPullUp = if (view == "files" && openCourse == null) ::openSettings else null
        if (pendingRise != 0) {
            // Swiped up or down to: the page carries on from the side the finger took the last one, at its speed.
            Motion.riseIn(page, pendingRise * scroll.height * 0.35f, scroll.flipVelocity)
            pendingRise = 0
        } else if (pendingSlide != 0) {
            // Another day: from the side it was swiped in from, or from close by after a tap.
            // A swipe's page carries on at the finger's speed; a tap's comes from close by, from rest.
            Motion.slideIn(page, pendingSlide * if (slideFar) scroll.width * 0.35f else ui.dp(40f).toFloat(), if (slideFar) scroll.releaseVelocity else 0f)
            pendingSlide = 0
        } else if (enter) {
            Motion.enter(page, ui.dp(16f).toFloat())
        }
        slideFar = false
        if (keepScroll) scroll.post { scroll.scrollTo(0, y) } else scroll.scrollTo(0, 0)
        dots?.invoke()
        updateBack()
    }

    /**
     * Swiped from Day to Week: each class's dot under its day in the strip pops out and flies down into its class
     * on the grid, which fills in from where it lands; a day after the one before, and the dots of a day one after
     * another. Taken from the strip before it changes, launched once the grid is on the page.
     */
    private fun flyingDots(): (() -> Unit)? {
        val swiped = dotsFly
        dotsFly = false
        if (!swiped || view != "week" || stripWeek != false || !Motion.enabled || stripDots.size != 5) return null
        val starts = stripDots.mapIndexed { k, row ->
            val day = timetable.day(k + 1)
            row.mapIndexedNotNull { i, v -> day.getOrNull(i)?.let { s -> Triple(i, s.course.color, Motion.onScreen(v).let { r -> android.graphics.PointF(r.centerX(), r.centerY()) }) } }
        }
        if (starts.all { it.isEmpty() }) return null
        WeekGrid.incoming = true
        return {
            val layer = flights()
            for ((k, row) in starts.withIndex()) for ((i, colour, at) in row) {
                val day = k + 1
                layer.dot(colour, at, k * 30L + i * 25L, { weekGrid()?.classAt(day, i) }) { landed -> weekGrid()?.land(day, i, landed) }
            }
        }
    }

    private fun renderTop(n: Now) {
        val p = ui.p
        val tt = timetable
        // Before the title and the strip change: what's leaving them flies as a rocket.
        val launch = rocketLaunch()
        showTitle(n)
        stripFrame.visibility = if (view == "day" || view == "week") View.VISIBLE else View.GONE
        if (stripFrame.visibility != View.VISIBLE) { stripWeek = null; launch?.invoke(); return }
        // On Week the strip is its five days only, each over its column of the grid (which has no row of day
        // names of its own); the Week chip's label is in the title. On Day, the days and the Week chip.
        val onWeek = view == "week"
        // Between the two, each day glides from where it was to its new place.
        val from = if (stripWeek != null && stripWeek != onWeek && stripDays.size == 5) stripDays.map { (chip, _, _) -> chip.left to chip.width } else null
        stripWeek = onWeek
        strip.removeAllViews()
        stripTrack.removeAllViews()
        stripDays.clear()
        stripDots.clear()
        val index = if (onWeek) 5 else sel - 1
        // The pill glides; the two labels it leaves and reaches change colour with it.
        val moved = stripIndex != -1 && stripIndex != index
        fun fg(today: Boolean, selected: Boolean) = when {
            selected && today -> p.accentInk
            selected -> p.ground
            today -> p.accentText
            else -> p.text
        }
        // On Week, the days start where Monday's column does and are as far apart as the columns.
        val inset = if (onWeek) ui.dp(WeekGrid.AXIS + WeekGrid.GAP - WeekGrid.BLEED) else 0
        val gap = ui.dp(if (onWeek) WeekGrid.GAP else 6f)
        strip.setPaddingRelative(inset, 0, 0, 0)
        stripTrack.setPaddingRelative(inset, 0, 0, 0)
        for (i in 0..(if (onWeek) 4 else 5)) {
            stripTrack.addView(View(this).apply { background = ui.shape(p.surface, 12f) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, if (i == 5) 1.15f else 1f).apply { if (i > 0) marginStart = gap })
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
            val days = daysTo(d)
            val today = days == 0
            val name = ui.text(DAY_SHORT[d], 10.5f, fg(today, selected), ui.f.bodyBold, caps = true, spacing = 0.1f)
            val date = ui.text(dateIn(days).get(Calendar.DAY_OF_MONTH).toString(), 22f, fg(today, selected), ui.f.sign)
            for (t in listOf(name, date)) {
                if (moved && selected) Motion.tint(t, fg(today, false), fg(today, true))
                if (moved && stripIndex == d - 1) Motion.tint(t, fg(today, true), fg(today, false), 90)
            }
            // A dot for each class in its course's colour. On Week they're in the grid below, and their row stays
            // empty, so the strip is as tall as on Day.
            val dots = ui.row(gap = 3f).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(6))
                if (!onWeek) for (s in tt.day(d)) addView(View(context).apply { background = ui.shape(s.course.color, 99f) }, LinearLayout.LayoutParams(ui.dp(6), ui.dp(6)))
            }
            val dayChip = chip(dayWords(d, days), name, date, dots).apply {
                setOnClickListener { pickedDay = true; goDay(d) }
            }
            strip.addView(dayChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (d > 1) marginStart = gap })
            stripDays += Triple(dayChip, name, date)
            stripDots += (0 until dots.childCount).map(dots::getChildAt)
        }
        from?.let { old ->
            val days = stripDays.map { it.first }
            val slots = (0 until 5).map(stripTrack::getChildAt)
            Motion.reflow(days + slots, old + old, slots.toSet())
        }
        weekChip = null
        if (onWeek) {
            stripGlider.hide()
            stripIndex = index
            launch?.invoke()
            return
        }
        // The week grid lives under Day: one more chip after Friday, saying which week of the semester it is.
        val (weekName, weekOf, weekWords) = semesterLabel()
        // One line, shrinking to fit the chip: "Revision" at 22sp is wider than it. "of 14" only in a teaching week.
        val nameText = ui.fitText(weekName, 22f, p.text, ui.f.sign, lines = 1)
        val ofText = ui.text(weekOf.orEmpty(), 10.5f, p.muted, ui.f.bodyBold, caps = true, spacing = 0.1f).apply { if (weekOf == null) visibility = View.GONE }
        val chipView = chip("$weekWords, ${tt.sessions.size} classes a week", nameText, ofText).apply {
            setOnClickListener { goCell(WEEK_CELL) }
        }
        strip.addView(chipView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f).apply { marginStart = gap })
        weekChip = chipView
        // Back from Week: the Week chip and its slot wait for the rocket bringing them back.
        if (rocketTo == ROCKET_STRIP) listOf(chipView, stripTrack.getChildAt(5)).forEach { it.alpha = 0f }
        stripGlider.moveTo(index, if (daysTo(sel) == 0) p.accent else p.text)
        stripIndex = index
        launch?.invoke()
    }

    /**
     * Going from Day to Week, the Week chip turns into a rocket and flies up into Week's title, where its "W5 of 14"
     * then shows; going back, it flies from there down into the strip, where the Week chip comes back. Any way
     * between the two (a tap, a swipe, the tab, Back). Taken before the title and the strip change, launched after.
     */
    private fun rocketLaunch(): (() -> Unit)? {
        val to = when {
            stripWeek == false && view == "week" -> ROCKET_TITLE
            stripWeek == true && view == "day" -> ROCKET_STRIP
            else -> return null
        }
        // One still on its way lands where it was going.
        flights?.land()
        if (!Motion.enabled) return null
        val source = (if (to == ROCKET_TITLE) weekChip else titleMark())?.takeIf { it.isAttachedToWindow && it.width > 0 } ?: return null
        val from = Motion.onScreen(source)
        // The chip on its slot; the title's week as it is.
        val picture = FlightLayer.picture(source, if (to == ROCKET_TITLE) ui.p.surface else Color.TRANSPARENT, ui.dp(12f).toFloat())
        // The rocket comes out of it: the title's week (fading with its title) goes now; the chip goes with the strip's change.
        if (to == ROCKET_STRIP) source.alpha = 0f
        val label = semesterLabel().first
        rocketTo = to
        return { flights().rocket(picture, from, label, { rocketTarget(to) }, { arrived -> rocketHit(to, arrived) }) }
    }

    private fun flights(): FlightLayer = flights ?: FlightLayer(ui) { flights = null }.also { layer ->
        findViewById<FrameLayout>(android.R.id.content).addView(layer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        flights = layer
    }

    /** Week's title's week of the semester, on the title shown now (the newest, while an old one slips away). */
    private fun titleMark(): View? = titleBox.getChildAt(titleBox.childCount - 1)?.findViewWithTag(WEEK_MARK)

    /** Where the rocket going [to] the title or the strip hits, on screen: the middle of what it brings back. */
    private fun rocketTarget(to: String): android.graphics.PointF? {
        val v = (if (to == ROCKET_TITLE) titleMark() else weekChip)?.takeIf { it.isAttachedToWindow && it.width > 0 } ?: return null
        val r = Motion.onScreen(v)
        return android.graphics.PointF(r.centerX(), r.centerY())
    }

    /** The rocket going [to] the title or the strip hit ([arrived]), or was landed by another move: what it brought shows. */
    private fun rocketHit(to: String, arrived: Boolean) {
        if (rocketTo == to) rocketTo = null
        val here = if (to == ROCKET_TITLE) view == "week" else stripWeek == false
        val brought = if (!here) emptyList() else if (to == ROCKET_TITLE) listOfNotNull(titleMark()) else listOfNotNull(weekChip, stripTrack.getChildAt(5))
        brought.forEach { it.alpha = 1f }
        if (!arrived || brought.isEmpty()) return
        // It pops out where it hit, which takes a little knock, with a light tap.
        brought.forEach { Motion.pop(it, 0.5f) }
        val struck = if (to == ROCKET_TITLE) titleBox else stripFrame
        struck.animate().translationY(ui.dp(3f).toFloat()).setDuration(50).setInterpolator(Motion.EXIT).withEndAction {
            struck.animate().translationY(0f).spring(Motion.BOUNCY).start()
        }.start()
        Haptics.threshold(shell, false)
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
        val log = view == "settings" && settingsLog
        val page = if (log) "Attendance log" else PAGE_TITLES[view]
        val days = daysTo(sel)
        val semester = semesterLabel()
        val words = when {
            view == "day" -> listOf(DAY_NAMES[sel], dateText(days))
            view == "week" -> listOf(weekRange(daysTo(1)), semester.first, semester.second.orEmpty())
            page != null -> listOf(page)
            else -> listOf("${DAY_NAMES[n.dow]} ${dateText(0)}")
        }
        // The small line under the title, each page's own summary. The Spectrum tabs: what's on them
        // ("3 due · next in 1d"); Day: the day at a glance; Week: its Spectrum deadlines.
        val line = when {
            log -> AttendanceLogPage.summary(this)
            page != null -> SpectrumScreens.summary(this, view)
            view == "day" -> dayGlance(tt.day(sel))
            view == "week" -> weekLine(daysTo(1))
            else -> null
        }
        val today = view == "day" && days == 0
        val key = "$big $today $words $line"
        if (key == titleKey) return
        titleKey = key
        val title: ViewGroup = when {
            page != null -> pageTitle(ui, page, line)
            view == "day" -> dayTitle(ui, DAY_NAMES[sel], dateText(days), today, line)
            view == "week" -> weekTitle(ui, words.first(), semester.first, semester.second, line).also { t ->
                t.findViewWithTag<View>(WEEK_MARK)?.apply {
                    // Brought up by the rocket: there once it hits.
                    if (rocketTo == ROCKET_TITLE) alpha = 0f
                    contentDescription = semester.third
                    // On another week, a double tap brings back this one (as on the Week chip, before it moved up here).
                    setOnClickListener {
                        val at = android.os.SystemClock.uptimeMillis()
                        if (view == "week" && weekShift != 0 && at - weekMarkTap < android.view.ViewConfiguration.getDoubleTapTimeout()) {
                            weekMarkTap = 0L
                            Haptics.back(this)
                            shiftWeek(-weekShift)
                        } else weekMarkTap = at
                    }
                }
            }
            else -> ui.row().apply { addView(ui.label(words.first(), p.muted, 11f)) }
        }
        val old = (0 until titleBox.childCount).map(titleBox::getChildAt)
        titleBox.addView(title, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        if (old.isEmpty()) return
        if (!Motion.enabled) return old.forEach(titleBox::removeView)
        // Swiped down to, the new title drops in from above rather than rising.
        Motion.retitle(old, title, pendingSlide * ui.dp(32f).toFloat(), (if (pendingRise < 0) -1 else 1) * ui.dp(10f).toFloat())
    }

    /** Settings (theme, imports, notifications, vibration, account), as in redesign 1p: the last tab. */
    private fun openSettings() = setView("settings")

    /**
     * The Settings tab's page. A new look or theme spreads over the app from what was tapped, and the
     * page drawn in it shows what moved ([settingsMoved]).
     */
    private fun settingsPage(): View {
        val moved = settingsMoved
        settingsMoved = null
        return SettingsSheet.build(this, this, moved = moved, restyle = { from, m, change ->
            spreadFrom = from
            settingsMoved = m
            change()
            spreadFrom = null
            settingsMoved = null
        }, openLog = {
            Haptics.tick(shell)
            settingsY = scroll.scrollY
            settingsLog = true
            render()
        })
    }

    /** Back from the attendance log to Settings, where they were scrolled to. */
    private fun logBack() {
        Haptics.back(shell)
        settingsLog = false
        render()
        scroll.post { scroll.scrollTo(0, settingsY) }
    }

    override fun setLook(look: Look) {
        if (look != this.look) restyle(spreadFrom ?: titleBox) { this.look = look; Look.save(this, look) }
    }

    override fun setScheme(scheme: Scheme) {
        if (scheme != this.scheme) restyle(spreadFrom ?: titleBox) {
            this.scheme = scheme
            Scheme.save(this, scheme)
            TimetableWidget.updateAll(this) // its classes take the theme's course colours too
        }
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

    /**
     * The line under Week's title: a week of UM's calendar without classes, the week's public holidays
     * ("Deepavali Mon"), then its Spectrum deadlines.
     */
    private fun weekLine(monday: Int): String? {
        val first = LocalDate.now().plusDays(monday.toLong())
        val holidays = (0..4).mapNotNull { k -> Holidays.on(first.plusDays(k.toLong()))?.let { "${it.name} ${DAY_SHORT[k + 1]}" } }
        return listOfNotNull(weekOff(first), holidays.joinToString(", ").ifEmpty { null }, weekDeadlines(monday)).joinToString(" · ").ifEmpty { null }
    }

    /**
     * The Spectrum deadlines in the week shown (Monday [monday] days from today, to Sunday): those not yet
     * passed ("3 deadlines this week", "next week"), or all of them for a week that's over ("last week");
     * null when not signed in.
     */
    private fun weekDeadlines(monday: Int): String? {
        if (!SpectrumStore.signedIn(this)) return null
        val from = startOfDay(dateIn(monday).timeInMillis)
        val to = startOfDay(dateIn(monday + 7).timeInMillis)
        val now = System.currentTimeMillis()
        val n = SpectrumStore.due(this).count { it.getLong("due") in (if (to <= now) from else maxOf(from, now)) until to }
        val which = when (monday) {
            in -6..0 -> " this week"
            in 1..7 -> " next week"
            in -13..-7 -> " last week"
            else -> ""
        }
        return when (n) {
            0 -> "No deadlines$which"
            1 -> "1 deadline$which"
            else -> "$n deadlines$which"
        }
    }

    /** A new look or scheme ([change]) spreads out from [from] (what was tapped in Settings), over a picture of the old one. */
    private fun restyle(from: View, change: () -> Unit) {
        val old = shell
        val y = scroll.scrollY
        val picture = if (Motion.enabled && old.width > 0 && old.height > 0)
            Bitmap.createBitmap(old.width, old.height, Bitmap.Config.ARGB_8888).also { old.draw(Canvas(it)) } else null
        val at = IntArray(2).also(from::getLocationInWindow)
        val cx = at[0] + from.width / 2
        val cy = at[1] + from.height / 2
        change()
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
        // Courses: the announcements and marks not seen yet, in the courses' folds.
        val counts = mapOf("files" to SpectrumScreens.newCount(this), "due" to SpectrumScreens.openAttendance(this))
        // On the week grid the Day tab is Week, with the grid's icon: the label rolls over and the icon draws itself in.
        val week = view == "week"
        if (week != dayTabWeek) tabViews["day"]?.let { (tab, icon, text) ->
            val animate = dayTabWeek != null && Motion.enabled
            dayTabWeek = week
            val label = if (week) "Week" else "Day"
            icon.setImageResource(if (week) R.drawable.avd_tab_week else R.drawable.avd_tab_day)
            tab.contentDescription = label
            rollLabel(text, label, animate)
            if (animate) (icon.drawable as? android.graphics.drawable.AnimatedVectorDrawable)?.start()
        }
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
        // The pill glides to the lit tab, whose icon plays its little scene: Day's list writes itself in,
        // Due's tick draws, a page drops into Courses' folder, Settings' sliders slide.
        val shown = TABS[lit].first
        if (tabShown != null && tabShown != shown && Motion.enabled) (tabViews[shown]?.second?.drawable as? android.graphics.drawable.AnimatedVectorDrawable)?.start()
        tabShown = shown
        tabGlider.moveTo(lit, p.surface)
    }

    /** A tab's label changing in place: the old one slips up and out, the new one rises in under it. */
    private fun rollLabel(text: TextView, to: String, animate: Boolean) =
        if (animate) roll(text, to, 0f, ui.dp(6f).toFloat()) else { text.animate().cancel(); text.text = to; text.translationX = 0f; text.translationY = 0f; text.alpha = 1f }

    /** A text changing in place: the old one slips out away from ([dx], [dy]), the new one comes in from there. */
    private fun roll(text: TextView, to: String, dx: Float, dy: Float) {
        text.animate().cancel()
        if (!Motion.enabled || text.text.toString() == to) {
            text.text = to
            text.translationX = 0f
            text.translationY = 0f
            text.alpha = 1f
            return
        }
        text.animate().translationX(-dx).translationY(-dy).alpha(0f).setDuration(90).setInterpolator(Motion.EXIT).withEndAction {
            text.text = to
            text.translationX = dx
            text.translationY = dy
            text.animate().translationX(0f).translationY(0f).spring(Motion.SETTLE).start()
            Motion.fade(text, 0f, 1f, 140)
        }.start()
    }

    /**
     * A course's fold on the Courses list was opened: what's new in it is seen after a moment. Its New tags
     * (and the top line's count) stay until the page is next drawn, so they're seen first; the tab's badge goes now.
     */
    override fun markSeen(news: Collection<String>, grades: Collection<String>) {
        if (news.isEmpty() && grades.isEmpty()) return
        ticker.postDelayed({
            if (news.any { it !in seenNews }) {
                seenNews = (seenNews + news).toList().takeLast(500).toSet()
                uiPrefs(this).edit().putStringSet("newsSeen", seenNews).apply()
            }
            if (grades.any { it !in seenGrades }) {
                seenGrades = (seenGrades + grades).toList().takeLast(500).toSet()
                uiPrefs(this).edit().putStringSet("gradesSeen", seenGrades).apply()
            }
            renderTabs()
        }, 1500)
    }

    /** Course [id]'s fold of news and grades opens on the Courses list (a notification about it was tapped). */
    private fun openFold(id: Int) {
        openCourse = null
        expanded += SpectrumScreens.foldKey(id)
    }

    // --- navigation ------------------------------------------------------------------------

    override fun setView(view: String) = go(view, Haptics::tick)

    /** Shows [view]; [feel] is the vibration for the move (none when the app is only being opened there). */
    private fun go(view: String, feel: ((View) -> Unit)?) {
        if (view !in VIEWS) return
        val changed = view != this.view
        if (changed) scroll.resetPull()
        // Another tab: Day and Week start again from the strip's own week.
        if (view != "day" && view != "week") weekShift = 0
        // Another tab: Settings open at the top of their page again, not on the log.
        if (view != "settings") settingsLog = false
        this.view = view
        uiPrefs(this).edit().putString("view", view).apply()
        if (changed) feel?.invoke(shell)
        render(keepScroll = false, enter = changed)
    }

    /**
     * [by] weeks on (or back, negative) from the week shown on Week. Only what changes changes: the
     * grid slides over to the new week as a carousel ([WeekScreen.shift]), and the title and the strip's
     * dates roll over from the same side. (Drawing the whole page again dropped frames as it began.)
     */
    private fun shiftWeek(by: Int, pxPerSecond: Float = 0f) {
        if (by == 0) return
        val dir = Integer.signum(by)
        weekShift += by
        pickedDay = true // the day the strip lands on stays put when Day is opened from this week
        val page = content.getChildAt(content.childCount - 1)
        if (view == "week" && page != null && WeekScreen.shift(page, this, dir, pxPerSecond)) {
            pendingSlide = dir
            showTitle(now())
            pendingSlide = 0
            slideFar = false
            shiftStrip(dir)
            return
        }
        pendingSlide = dir
        render(keepScroll = true)
    }

    /** The strip for the week shown now, in place: each date rolls over from the side the week came from (the week's label is in the title). */
    private fun shiftStrip(dir: Int) {
        val p = ui.p
        if (stripDays.size != 5) return renderTop(now())
        val dx = dir * ui.dp(12f).toFloat()
        for ((k, views) in stripDays.withIndex()) {
            val (chip, name, date) = views
            val d = k + 1
            val days = daysTo(d)
            val colour = if (days == 0) p.accentText else p.text // none is chosen on Week; today's in the accent
            name.setTextColor(colour)
            date.setTextColor(colour)
            roll(date, dateIn(days).get(Calendar.DAY_OF_MONTH).toString(), dx, 0f)
            chip.contentDescription = dayWords(d, days)
        }
    }

    /** A day chip's words for TalkBack: "Monday 9 Nov, 3 classes". */
    private fun dayWords(d: Int, days: Int) = timetable.day(d).size.let { k -> "${DAY_NAMES[d]} ${dateText(days)}, $k ${if (k == 1) "class" else "classes"}" }

    /** The Week chip's label for the week shown: "W3" and "of 14", or "Break", "Revision", "Exams"; and its words for TalkBack. */
    private fun semesterLabel(): Triple<String, String?, String> {
        val semester = AcademicCalendar.week(LocalDate.now().plusDays(daysTo(1).toLong()))
        val (name, of) = when (semester?.kind) {
            AcademicCalendar.Kind.TEACHING -> "W${semester.n}" to "of ${semester.of}"
            AcademicCalendar.Kind.BREAK -> "Break" to null
            AcademicCalendar.Kind.REVISION -> "Revision" to null
            AcademicCalendar.Kind.EXAMS -> "Exams" to null
            null -> "Week" to null
        }
        val words = if (semester?.kind == AcademicCalendar.Kind.TEACHING) "Week ${semester.n} of ${semester.of} of the semester" else name
        return Triple(name, of, words)
    }

    override fun goDay(day: Int, dir: Int) {
        val d = when {
            day < 1 -> 5
            day > 5 -> 1
            else -> day
        }
        goCell(d, dir)
    }

    /** A swipe up ([rise] 1) or down (-1) went past its line, which already vibrated: [cell] comes from that side. */
    private fun flip(cell: Int, rise: Int) {
        pendingRise = rise
        dotsFly = cell == WEEK_CELL
        slideFar = true
        goCell(cell)
    }

    /**
     * Shows the strip's [cell]: a day (1–5) or the Week grid ([WEEK_CELL]). From another cell the page slides
     * in from the side it's on ([dir] for a swipe), as from one day to the next; from another tab it rises.
     */
    private fun goCell(cell: Int, dir: Int = 0) {
        val week = cell == WEEK_CELL
        if (!week) pickedDay = true
        val here = when (view) {
            "week" -> WEEK_CELL
            "day" -> sel
            else -> null
        }
        if (here == cell) { pendingRise = 0; return }
        pendingSlide = if (here == null || pendingRise != 0) 0 else if (dir != 0) dir else if (cell > here) 1 else -1
        // A tap vibrates (a swipe already bumped at its threshold). Day to day here: the view stays "day", so go doesn't.
        if (view == "day" && !week && !slideFar) Haptics.tick(shell)
        if (!week) sel = cell
        go(if (week) "week" else "day", if (slideFar) null else Haptics::tick)
    }

    // Back: leave a course page or the attendance log, then return to Day; on Day (false), Android's own back leaves the app.
    private fun stepBack(): Boolean {
        if (view == "settings" && settingsLog) { logBack(); return true }
        if (view == "files" && openCourse != null && sectionBack()) return true
        if (view == "files" && openCourse != null) {
            Haptics.back(shell)
            openCourse = null
            render()
        } else if (view != "day") {
            weekShift = 0
            go("day", Haptics::back)
        } else return false
        return true
    }

    // Android 13+ sends back to this callback. Android 12 has no such type (making one there
    // crashes), so it's made on first use and back comes through onBackPressed instead.
    private val back by lazy { OnBackInvokedCallback { stepBack() } }
    private var backRegistered = false

    @SuppressLint("GestureBackNavigation") // gestures on Android 13+ go to the callback above
    @Deprecated("Android 12 (and 13 to 15 when the callback isn't used) still calls this")
    override fun onBackPressed() {
        if (!stepBack()) super.onBackPressed()
    }

    private fun updateBack() {
        if (Build.VERSION.SDK_INT < 33) return
        val want = view != "day"
        if (want == backRegistered) return
        backRegistered = want
        if (want) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, back)
        else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(back)
    }

    override fun openSheet(course: Course, session: Session?) = ClassSheet.show(this, this, course, session)

    /** A plan's editor, out of its cell in the week grid; once it's saved or deleted, the week shows it. */
    override fun openPlan(from: android.graphics.RectF, plan: Plan, isNew: Boolean, maxEnd: Int, hourPx: Float) =
        PlanSheet.show(ui, from, plan, isNew, maxEnd, hourPx, onRepick = ::repick) { render(keepScroll = true) }

    /** "Change time" on a plan's card: the selection box waits on Week, in the plan's week (opened if need be, from Day). */
    private fun repick(draft: Plan) {
        if (view == "week" && Plan.weekOf(draft.date) == Plan.weekOf(dateOf(1))) return weekGrid()?.startRepick(draft) ?: Unit
        val stripMonday = LocalDate.now().plusDays(daysFromToday(timetable, now(), 1).toLong())
        weekShift = java.time.temporal.ChronoUnit.WEEKS.between(stripMonday, Plan.weekOf(draft.date)).toInt()
        if (view == "week") render(keepScroll = true) else goCell(WEEK_CELL)
        // Once the grid is laid out: the box is placed from its size.
        content.post { weekGrid()?.startRepick(draft) }
    }

    /** The week grid on the page now (the minute's redraw may have replaced the one a card came from; mid-slide, the newer of two). */
    private fun weekGrid(): WeekGrid? = content.getChildAt(content.childCount - 1)?.let(WeekScreen::grid) as? WeekGrid

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

    override fun sectionsTab(open: (() -> Unit)?) { sectionsOpen = open }

    /** The Sections tab, there while a course page wants it; it slides out of the edge when it first comes. */
    private fun showSectionsTab(had: Boolean) {
        val show = sectionsOpen != null
        sectionsTabView.visibility = if (show) View.VISIBLE else View.GONE
        if (!show || had || !Motion.enabled) return
        sectionsTabView.translationX = -ui.dp(30f).toFloat()
        sectionsTabView.animate().translationX(0f).spring(Motion.BOUNCY).start()
    }

    override fun scrollTo(v: View) {
        // Where [v] is on the page: its tops added up to the page.
        var y = 0
        var at: View? = v
        while (at != null && at !== content) {
            y += at.top
            at = at.parent as? View
        }
        if (at == null) return
        scroll.smoothScrollTo(0, maxOf(0, y - ui.dp(8)))
    }

    /** Reads a course's layout again behind its page, and redraws the page if it changed. */
    private fun refreshLayout(id: Int) = thread {
        val fresh = CourseLayout.refresh(applicationContext, id) ?: return@thread
        runOnUiThread {
            val course = contents[id] ?: return@runOnUiThread
            course.remove("layoutStale")
            if (course.optJSONObject("layout")?.toString() == fresh.toString()) return@runOnUiThread
            course.put("layout", fresh)
            if (view == "files" && openCourse == id) render(keepScroll = true)
        }
    }

    override fun showSection(id: Int) {
        Haptics.tick(shell)
        sectionPages += id to scroll.scrollY
        render()
    }

    /** Back from a section page to the page it covered, where it was scrolled to. False when none is open. */
    private fun sectionBack(): Boolean {
        val (_, y) = sectionPages.removeLastOrNull() ?: return false
        Haptics.back(shell)
        render()
        scroll.post { scroll.scrollTo(0, y) }
        return true
    }
    override fun courseError(id: Int) = contentErrors[id]

    private fun loadCourse(id: Int) {
        if (id in contents || id in contentErrors || !loading.add(id)) return
        thread {
            val json = SpectrumSync.courseContents(applicationContext, id)
            runOnUiThread {
                loading.remove(id)
                val course = runCatching { JSONObject(json) }.getOrNull()
                if (course != null && course.has("sections")) contents[id] = course
                else contentErrors[id] = course?.optString("error")?.ifEmpty { null } ?: "Couldn't read this course."
                if (view == "files" && openCourse == id) render(keepScroll = true)
                if (course?.optBoolean("layoutStale") == true) refreshLayout(id)
            }
        }
    }

    override fun openUrl(url: String) = SpectrumWebActivity.open(this, url)

    override fun openFile(file: JSONObject, folder: String) =
        Downloads.open(this, file.optString("url"), file.optString("name"), file.optString("mime"), file.optLong("size"), folder)

    @Suppress("DEPRECATION")
    override fun signIn() = startActivityForResult(Intent(this, LoginActivity::class.java), REQ_LOGIN)

    /** Sign out can't be undone: ask first, and only a held button signs out. */
    override fun signOut(from: View?) = ConfirmDialog.show(ui, SIGN_OUT_TITLE, SIGN_OUT_BODY, "Sign out", hold = true, from = from?.let(Motion::onScreen)) { signOutNow() }

    /** Sign out deletes everything, and the app starts again at the first-run poster. */
    private fun signOutNow() {
        AppReset.run(this)
        SpectrumStore.noteSignOut(this, "you tapped Sign out")
        intent = Intent(this, MainActivity::class.java)
        recreate()
    }

    @Suppress("DEPRECATION")
    override fun openMaya() = startActivityForResult(Intent(this, MayaActivity::class.java), REQ_MAYA)

    @Suppress("DEPRECATION")
    override fun openTimeEdit() = startActivityForResult(Intent(this, TimeEditActivity::class.java), REQ_TIMEEDIT)

    override fun enableNotifications() {
        // Android 12 has no notification permission to ask for: they're on unless turned off in Settings.
        if (Build.VERSION.SDK_INT >= 33 && !Notify.allowed(this)) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
    }

    override fun checkForUpdates() = Updater.check(this, ui, quiet = false)

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
        /** Where a rocket is going ([rocketTo]): Week's title, or Day's strip. */
        private const val ROCKET_TITLE = "title"
        private const val ROCKET_STRIP = "strip"
        /** The Sign out question (redesign 1q). Sign out deletes everything ([AppReset]). */
        const val SIGN_OUT_TITLE = "Sign out?"
        const val SIGN_OUT_BODY = "All your data in the app will be deleted.\nYou can sign in again any time."
        /** A Spectrum course id to open in the Courses tab. */
        const val EXTRA_COURSE = "course"
        /** A Spectrum course id whose fold of news and grades to open on the Courses list. */
        const val EXTRA_FOLD = "fold"
        private const val REQ_LOGIN = 1
        private const val REQ_NOTIFY = 2
        private const val REQ_MAYA = 3
        private const val REQ_TIMEEDIT = 4
        private val TABS = listOf(
            // Animated icons (res/drawable/avd_tab_*): at rest each is its ic_tab_* drawing.
            Triple("day", "Day", R.drawable.avd_tab_day),
            Triple("due", "Due", R.drawable.avd_tab_due),
            Triple("files", "Courses", R.drawable.avd_tab_courses),
            Triple("settings", "Settings", R.drawable.avd_tab_settings),
        )
        /** The tabs' names after Day, shown large in the top bar. */
        private val PAGE_TITLES = mapOf("due" to "Due", "files" to "Courses", "settings" to "Settings")
        /** The week grid has no tab of its own; it's the Week chip under Day. */
        private val VIEWS = TABS.map { it.first }.toSet() + "week"

        /** [name] as a page shown now: Grades and News were tabs, and are in Courses now (an old notification, the page last open). */
        private fun viewOf(name: String?) = when (name) {
            "grades", "news" -> "files"
            in VIEWS -> name
            else -> null
        }
    }
}
