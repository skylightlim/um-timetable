package com.skylightlim.umtimetable

import android.app.NotificationManager
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import java.io.File

/**
 * Sign out: everything the app keeps on the phone goes, and it starts again as if just installed.
 * That's the Spectrum sign-in and its data, the timetable, the settings, and the web pages' cookies,
 * storage and cache (so TimeEdit, MAYA and Microsoft are signed out too). Files the user saved to
 * Downloads are theirs and stay. Runs on the main thread (the web cache needs a WebView).
 */
object AppReset {
    fun run(c: Context) {
        SpectrumSyncJob.cancel(c)
        SpectrumStore.signOut(c)
        SecureStore.clear(c)
        Timetable.setLocal(c, null)
        // Every preference file of the app's: settings, the news and marks already seen, sync times.
        // WebView keeps its own there too (WebViewChromiumPrefs, AwOriginVisitLoggerPrefs): not data, left alone.
        File(c.dataDir, "shared_prefs").list()?.map { it.removeSuffix(".xml") }
            ?.filterNot { it.startsWith("WebView") || it.startsWith("Aw") }
            ?.forEach { name ->
                c.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
                c.deleteSharedPreferences(name)
            }
        // WebView's own cache folder is left to clearCache: deleting it under a running WebView breaks it.
        // The external files folder holds a downloaded update, if any.
        for (dir in listOfNotNull(c.filesDir, c.noBackupFilesDir, c.cacheDir, c.getExternalFilesDir(null))) {
            dir.listFiles()?.forEach { if (it.name != "WebView") it.deleteRecursively() }
        }
        CookieManager.getInstance().apply { removeAllCookies(null); flush() }
        WebStorage.getInstance().deleteAllData()
        WebView(c).apply { clearCache(true); destroy() }
        c.getSystemService(NotificationManager::class.java).cancelAll()
        // With nothing left, these cancel their alarms.
        Reminders.schedule(c)
        AttendanceWatch.schedule(c)
        TimetableWidget.updateAll(c)
    }
}
