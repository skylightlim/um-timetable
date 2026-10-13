package com.skylightlim.umtimetable

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout

/** Debug-only: applies the widget's RemoteViews in-process, so a remote-call error crashes here instead of on the home screen. */
class WidgetPreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lets the preview be screenshotted over adb while the phone is locked.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val w = intent.getIntExtra("w", 340)
        val h = intent.getIntExtra("h", 420)
        val now = if (intent.hasExtra("dow")) Now(intent.getIntExtra("dow", 3), intent.getFloatExtra("min", 744f)) else Now.current()
        val views = WidgetRenderer.build(this, Timetable.load(this), now, w.toFloat(), h.toFloat())
        val density = resources.displayMetrics.density
        val frame = FrameLayout(this).apply { setBackgroundColor(0xFF5C6F8A.toInt()) }
        // --ez night true renders the dark theme without touching the phone's own setting.
        val ctx = if (intent.getBooleanExtra("night", false)) {
            createConfigurationContext(Configuration(resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            })
        } else this
        frame.addView(views.apply(ctx, frame), FrameLayout.LayoutParams((w * density).toInt(), (h * density).toInt(), Gravity.CENTER))
        setContentView(frame)
    }

    // A second `am start` is delivered here when the preview is already on top; re-render with the new extras.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }
}
