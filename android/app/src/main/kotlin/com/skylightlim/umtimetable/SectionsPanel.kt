package com.skylightlim.umtimetable

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.window.OnBackInvokedDispatcher
import com.skylightlim.umtimetable.Motion.spring

/**
 * A course page's sections at a glance: a panel that slides in from the left (from the Sections tab on
 * the page's left edge) over the page, listing its top sections in Spectrum's order, pinned ones with a
 * pin. A tap on one puts the panel away and goes there: a section opens if it was folded and the page
 * scrolls to it; a section Spectrum shows as a link opens its page. Fold all folds every section to its
 * heading (Open all opens them again). Back, or a tap beside the panel, puts it away.
 */
object SectionsPanel {
    /** One section in the panel: [pinned] when it's pinned now, [go] goes to it. */
    class Entry(val node: CourseOutline.Node, val pinned: () -> Boolean, val go: () -> Unit)

    /**
     * Shows the panel for the course [code] (in its [color]). [folded] is how many of the foldable
     * sections are folded now, of [foldable]; [foldAll] folds them all (true) or opens them all.
     */
    fun show(ui: Ui, code: String, color: Int, entries: List<Entry>, foldable: Int, folded: Int, foldAll: (Boolean) -> Unit) {
        val p = ui.p
        val dialog = Dialog(ui.context, android.R.style.Theme_Translucent_NoTitleBar)
        val scrim = View(ui.context).apply { setBackgroundColor(0x66000000) }
        val screen = ui.context.resources.displayMetrics.widthPixels
        val width = minOf(ui.dp(320), (screen * 0.84f).toInt())
        val panel = LinearLayout(ui.context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true // a tap on it isn't a tap on the scrim
            elevation = ui.dp(12f).toFloat()
            background = GradientDrawable().apply {
                setColor(p.surface)
                val r = ui.dp(22f).toFloat()
                cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            }
        }
        var closing = false
        fun close(then: (() -> Unit)?) {
            if (closing) return
            closing = true
            fun done() { dialog.dismiss(); then?.invoke() }
            if (!Motion.enabled) return done()
            Motion.fade(scrim, scrim.alpha, 0f, 180)
            panel.animate().translationX(-width.toFloat()).setInterpolator(Motion.EXIT).setDuration(200).withEndAction { done() }.start()
        }

        // The head: the course's code, "Sections" and how many, and Fold all / Open all.
        val head = ui.column().apply { setPadding(ui.dp(20), ui.dp(18), ui.dp(16), ui.dp(10)) }
        head.addView(ui.text(code.ifEmpty { "Course" }.uppercase(), 13f, p.courseText(color), ui.f.monoBold))
        head.addView(ui.row(gap = 10f).apply {
            addView(ui.text("Sections", 30f, p.text, ui.f.sign), ui.grow())
            if (foldable > 0) {
                val fold = folded < foldable
                addView(ui.text(if (fold) "Fold all" else "Open all", 13.5f, p.accentText, ui.f.bodySemi).apply {
                    gravity = Gravity.CENTER
                    minHeight = ui.dp(36)
                    setPadding(ui.dp(12), 0, ui.dp(12), 0)
                    background = ui.pressable(ui.shape(p.surface2, 10f), 10f)
                    contentDescription = if (fold) "Fold every section to its heading" else "Open every section"
                    setOnClickListener { Haptics.tick(this); close { foldAll(fold) } }
                })
            }
        }, ui.wide(top = 2f))
        head.addView(ui.text("${entries.size} ${if (entries.size == 1) "section" else "sections"} · tap one to go there", 12f, p.muted), ui.wide(top = 2f))
        panel.addView(head)

        // The sections, ruled inside only.
        val list = ui.column().apply { setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(16)) }
        for ((n, e) in entries.withIndex()) {
            val node = e.node
            val closed = node.restricted || node.hidden
            if (n > 0) list.addView(View(ui.context).apply { setBackgroundColor(p.line) }, ui.wide().apply { height = 1; marginStart = ui.dp(8); marginEnd = ui.dp(8) })
            val inside = listOfNotNull(
                node.activities.takeIf { it > 0 }?.let { "$it ${if (it == 1) "activity" else "activities"}" },
                node.children.size.takeIf { it > 0 }?.let { "$it ${if (it == 1) "section" else "sections"}" },
            ).joinToString(" · ").ifEmpty { if (node.hidden) "Not available" else "Empty" }
            list.addView(ui.row(gap = 10f).apply {
                minimumHeight = ui.dp(52)
                setPadding(ui.dp(8), ui.dp(9), ui.dp(8), ui.dp(9))
                background = ui.pressable(ui.shape(Color.TRANSPARENT, 10f), 10f)
                addView(ui.column().apply {
                    addView(ui.text(node.name.ifBlank { "General" }, 15f, if (closed) p.muted else p.text, ui.f.bodySemi, lines = 2))
                    addView(ui.text(if (node.link) "$inside · its own page" else inside, 11f, p.muted, ui.f.mono), ui.wide(top = 2f))
                }, ui.grow())
                if (e.pinned()) addView(ui.icon(R.drawable.ic_pin_on, p.accentText, 14f).apply { contentDescription = "Pinned" })
                if (node.restricted) addView(ui.icon(R.drawable.ic_lock, p.muted, 14f).apply { contentDescription = "Not available to you" })
                if (node.link && !closed) addView(ui.icon(R.drawable.ic_chevron, p.faint), LinearLayout.LayoutParams(ui.dp(8), ui.dp(16)))
                contentDescription = "${node.name}, $inside${if (e.pinned()) ", pinned" else ""}"
                setOnClickListener { Haptics.tick(this); close(e.go) }
            })
        }
        panel.addView(ScrollView(ui.context).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val frame = FrameLayout(ui.context).apply {
            addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(panel, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
            scrim.setOnClickListener { close(null) }
            // Under the status bar and above the navigation bar, as the app's own pages.
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                panel.setPadding(bars.left, bars.top, 0, bars.bottom)
                WindowInsets.CONSUMED
            }
        }
        dialog.setContentView(frame)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(0)
            setDecorFitsSystemWindows(false)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { close(null) }
        } else {
            dialog.setOnKeyListener { _, key, ev ->
                if (key == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP) close(null)
                key == KeyEvent.KEYCODE_BACK
            }
        }
        // It slides in from the left edge, where the tab was, as the page behind dims.
        if (Motion.enabled) {
            scrim.alpha = 0f
            panel.translationX = -width.toFloat()
            Motion.fade(scrim, 0f, 1f, 220)
            panel.animate().translationX(0f).spring(Motion.SETTLE).start()
        }
        dialog.show()
    }
}
