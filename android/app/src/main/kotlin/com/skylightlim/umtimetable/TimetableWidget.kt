package com.skylightlim.umtimetable

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews

/**
 * The home-screen widget. It re-renders once a minute on a non-wakeup alarm, so the
 * countdown is current whenever the screen is on and costs nothing while it's off.
 */
class TimetableWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = updateAll(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        render(context, manager, id)

    override fun onEnabled(context: Context) = scheduleTick(context)

    override fun onDisabled(context: Context) =
        context.getSystemService(AlarmManager::class.java).cancel(tickIntent(context))

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TICK,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> updateAll(context)
        }
    }

    companion object {
        private const val ACTION_TICK = "com.skylightlim.umtimetable.TICK"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TimetableWidget::class.java))
            ids.forEach { render(context, manager, it) }
            if (ids.isNotEmpty()) scheduleTick(context)
        }

        private fun render(context: Context, manager: AppWidgetManager, id: Int) {
            val options = manager.getAppWidgetOptions(id)
            val timetable = Timetable.load(context)
            val now = Now.current()
            // The launcher lists the exact sizes the widget is shown at (portrait and
            // landscape); give it a layout for each and it picks the right one.
            @Suppress("DEPRECATION")
            val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            val views = if (!sizes.isNullOrEmpty()) {
                RemoteViews(sizes.distinct().take(16).associateWith { WidgetRenderer.build(context, timetable, now, it.width, it.height) })
            } else {
                val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 320
                val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 340
                WidgetRenderer.build(context, timetable, now, w.toFloat(), h.toFloat())
            }
            manager.updateAppWidget(id, views)
        }

        private fun scheduleTick(context: Context) {
            val alarms = context.getSystemService(AlarmManager::class.java)
            val nextMinute = (System.currentTimeMillis() / 60_000 + 1) * 60_000 + 200
            if (alarms.canScheduleExactAlarms()) {
                alarms.setExact(AlarmManager.RTC, nextMinute, tickIntent(context))
            } else {
                alarms.setWindow(AlarmManager.RTC, nextMinute, 60_000, tickIntent(context))
            }
        }

        private fun tickIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, TimetableWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
