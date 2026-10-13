package com.skylightlim.umtimetable

import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Vibration feedback, the platform's own (so it follows Android's touch-feedback setting too): a tick
 * when a selection changes (tab, day, course), a click going back, and a bump when a drag passes the
 * point where letting go would switch day or close the sheet. The user can turn it off in Courses.
 */
object Haptics {
    private fun prefs(c: Context) = c.getSharedPreferences("ui", Context.MODE_PRIVATE)

    fun on(c: Context) = prefs(c).getBoolean("haptics", true)

    fun set(c: Context, on: Boolean) = prefs(c).edit().putBoolean("haptics", on).apply()

    /** Another tab, day or page was chosen. */
    fun tick(v: View) = perform(v, if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)

    /** Back to where the user came from. */
    fun back(v: View) = perform(v, HapticFeedbackConstants.CONTEXT_CLICK)

    /** A drag crossed the point of no return ([on]) or came back before it. */
    fun threshold(v: View, on: Boolean) = perform(
        v,
        when {
            Build.VERSION.SDK_INT < 34 -> HapticFeedbackConstants.CLOCK_TICK
            on -> HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
            else -> HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
        },
    )

    /** The switch itself, turned on (it can't vibrate when turned off). */
    fun toggledOn(v: View) = perform(v, if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CONTEXT_CLICK)

    private fun perform(v: View, kind: Int) {
        if (on(v.context)) v.performHapticFeedback(kind)
    }
}
