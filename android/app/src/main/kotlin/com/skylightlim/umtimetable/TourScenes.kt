package com.skylightlim.umtimetable

import android.view.View
import android.widget.FrameLayout
import com.skylightlim.umtimetable.Tour.Gesture

/** What the tour needs of the app's screen ([MainActivity]) to play its stops. */
interface TourHost : Host {
    /** The page shown, and the scroll it's in. */
    fun tourPage(): View?
    fun tourScroll(): View
    fun tourGrid(): WeekGrid?
    fun tourTitle(): View
    fun tourTab(id: String): View?
    fun tourSectionsTab(): View
    /** What a tap on a course page's Sections tab does. */
    fun tourSections()
    /** The week shown, [by] weeks on (or back), as a swipe across Week moves it. */
    fun tourWeek(by: Int)
}

/**
 * Each stop of the tour played out ([Tour.Act]): where the finger goes, what it does, and what that does,
 * shown as it would be. Cards, menus and panels that would open are built as the app builds them and shown
 * on the tour's stage ([TourView.card] and the like), where nothing on them can be touched, and nothing
 * happens to the user's data: no plan is saved, no class deleted, no deadline dismissed. What's real is
 * only what a look changes: a course's fold opened (and shut again), the week moved (and back).
 */
object TourScenes {
    fun scene(act: Tour.Act, h: TourHost, v: TourView): Scene {
        val ui = h.ui
        fun spot(view: View?) = view?.let { Tour.Spot(it) }
        fun page() = h.tourPage()
        fun tab(id: String) = spot(h.tourTab(id))
        /** A free hour on the week grid, as a cell. */
        fun free(): Tour.Spot? = h.tourGrid()?.let { g -> g.tourFree()?.let { Tour.Spot(g) { g.tourFree() } } }
        /** Something on the grid that [area] finds, or a free hour when there's none. */
        fun onGrid(area: (WeekGrid) -> android.graphics.RectF?): Tour.Spot? = h.tourGrid()?.let { g ->
            if (area(g) != null) Tour.Spot(g) { area(g) ?: g.tourFree() } else free()
        }
        return when (act) {
            Tour.Act.CLASS -> Scene(listOf(Beat(Gesture.TAP, { spot(Tour.find(page(), Tour.CLASS)) ?: tab("day") }, rest = 900)))

            // The class's sheet, up from the bottom; the pen is tapped and it turns to editing, then back.
            Tour.Act.EDIT -> {
                val s = Tour.find(page(), Tour.CLASS)?.getTag(R.id.tour_session) as? Session ?: h.timetable.sessions.firstOrNull()
                    ?: return Scene(listOf(Beat(Gesture.TAP, { tab("day") })))
                val sheet = ClassSheet.build(h, ui.context, s.course, s) { _ -> }
                v.sheet(sheet)
                var editing = false
                fun pen() = Tour.find(sheet, Tour.PEN)
                fun tapPen() { pen()?.let { Haptics.quietly { it.performClick() } }; editing = !editing }
                Scene(
                    listOf(Beat(Gesture.TAP, { spot(pen()) }, rest = 2000, press = ::tapPen)),
                    card = -1,
                    reset = { if (editing) tapPen() },
                )
            }

            Tour.Act.SWIPE_UP -> Scene(listOf(Beat(Gesture.SWIPE_UP, {
                val scroll = h.tourScroll()
                Tour.Spot(scroll) { android.graphics.RectF(scroll.width * 0.2f, scroll.height * 0.3f, scroll.width * 0.8f, scroll.height * 0.8f) }
            }, rest = 700)))

            // Held, then double-tapped: a new plan's card pops up each time, and goes.
            Tour.Act.ADD_PLAN -> {
                var open: View? = null
                fun shut() { open?.let(v::dismiss); open = null }
                fun card() {
                    shut()
                    val plan = h.tourGrid()?.tourNewPlan() ?: return
                    open = v.card(PlanSheet.build(ui, plan, true, plan.end + 120).card)
                }
                val at = { free() ?: tab("day") }
                Scene(
                    listOf(
                        Beat(Gesture.HOLD, at, rest = 1600, press = ::card, after = ::shut),
                        Beat(Gesture.DOUBLE_TAP, at, rest = 1600, press = ::card, after = ::shut),
                    ),
                    card = 1, reset = ::shut, leave = ::shut,
                )
            }

            // A plan (or, with none, a free hour) lifted and carried down and across.
            Tour.Act.MOVE_PLAN -> {
                val color = Plans.palette(Scheme.load(ui.context), ui.p.accent).first()
                Scene(listOf(Beat(Gesture.DRAG, { onGrid { it.tourPlan() } ?: tab("day") }, rest = 700, carry = color)), card = 1)
            }

            // Held, the class's card pops up; its Delete is held until it fills, and the card goes.
            Tour.Act.DELETE_CLASS -> {
                var open: View? = null
                var delete: HoldButton? = null
                fun shut() { open?.let(v::dismiss); open = null; delete = null }
                fun card() {
                    shut()
                    val s = h.tourGrid()?.tourClassSession() ?: return
                    val c = ClassCard.build(ui, s)
                    open = v.card(c.card)
                    delete = Tour.find(c.card, Tour.DELETE) as? HoldButton
                }
                Scene(
                    listOf(
                        Beat(Gesture.HOLD, { onGrid { it.tourClass() } ?: tab("day") }, rest = 500, press = ::card),
                        Beat(Gesture.HOLD, { spot(delete) }, rest = 900, holding = { f -> delete?.preview(f) }, press = ::shut),
                    ),
                    card = 1, reset = ::shut, leave = ::shut,
                )
            }

            // Another week first; the title double-tapped brings this one back.
            Tour.Act.THIS_WEEK -> {
                fun away() { if (h.weekShift == 0) h.tourWeek(1) }
                fun back() { if (h.weekShift != 0) h.tourWeek(-h.weekShift) }
                Scene(listOf(Beat(Gesture.DOUBLE_TAP, { spot(h.tourTitle()) }, rest = 1600, press = ::back)), reset = ::away, leave = ::back)
            }

            // Held, the Dismiss menu grows out of the row; tapped, the row fades as it would go.
            Tour.Act.DISMISS -> {
                var menu: View? = null
                var row: View? = null
                fun shut() { menu?.let(v::dismiss); menu = null }
                fun open() {
                    shut()
                    val r = Tour.find(page(), Tour.DUE) ?: return
                    row = r
                    // With room around the card for its shadow, as the real menu has.
                    val room = FrameLayout(ui.context).apply {
                        setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(16))
                        clipChildren = false
                        addView(SpectrumScreens.menuCard(ui, R.drawable.ic_close, "Dismiss"))
                    }
                    menu = v.menu(room, r)
                }
                fun putBack() { row?.animate()?.cancel(); row?.alpha = 1f }
                Scene(
                    listOf(
                        Beat(Gesture.HOLD, { spot(Tour.find(page(), Tour.DUE)) ?: tab("due") }, rest = 300, press = ::open),
                        Beat(Gesture.TAP, { spot((menu as? FrameLayout)?.getChildAt(0)) }, rest = 1300, press = {
                            shut()
                            row?.animate()?.alpha(0.25f)?.setDuration(220)?.start()
                        }),
                    ),
                    reset = { shut(); putBack() }, leave = { shut(); putBack() },
                )
            }

            // The first course's arrow opens its fold, and its news is lit; shut again for the next go.
            Tour.Act.NEWS -> {
                fun arrow() = Tour.find(page(), Tour.FOLD)
                fun isOpen() = Tour.find(page(), Tour.NEWS) != null
                val wasOpen = isOpen()
                fun tap() { arrow()?.let { Haptics.quietly { it.performClick() } } }
                Scene(
                    listOf(
                        Beat(Gesture.TAP, { spot(arrow()) ?: tab("files") }, rest = 400, press = { if (!isOpen()) tap() }),
                        Beat(Gesture.LOOK, { spot(Tour.find(page(), Tour.NEWS)) }, rest = 0),
                    ),
                    reset = { if (isOpen()) tap() },
                    leave = { if (isOpen() != wasOpen) tap() },
                )
            }

            Tour.Act.SPECTRUM -> Scene(listOf(Beat(Gesture.TAP, { spot(Tour.find(page(), Tour.COURSE)) ?: tab("files") }, rest = 900)))

            // The Sections tab tapped: its panel slides in from the left edge, and is lit.
            Tour.Act.SECTIONS -> {
                var open: View? = null
                fun shut() { open?.let(v::dismiss); open = null }
                fun panel() {
                    shut()
                    SectionsPanel.intercept = { p, width -> open = v.panel(p, width) }
                    try { h.tourSections() } finally { SectionsPanel.intercept = null }
                }
                Scene(
                    listOf(
                        Beat(Gesture.TAP, { spot(h.tourSectionsTab().takeIf { it.isShown }) ?: tab("files") }, rest = 400, press = ::panel),
                        Beat(Gesture.LOOK, { spot(open) }, rest = 0, after = ::shut),
                    ),
                    card = 1, reset = ::shut, leave = ::shut,
                )
            }
        }
    }
}
