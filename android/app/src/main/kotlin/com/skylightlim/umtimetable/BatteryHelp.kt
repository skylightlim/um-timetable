package com.skylightlim.umtimetable

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * The popup that lets auto attendance's alarms wake the app ([AutoAttendance]). Two steps, each shown
 * only while it's needed:
 *  1. Android's battery optimisation: Allow opens Android's own "Let the app always run in the
 *     background?" question, which sets the app's battery use to Unrestricted in one tap.
 *  2. The phone maker's own switch, which Android's doesn't cover and the app can't read: Autostart on
 *     Xiaomi, OPPO, vivo and Huawei phones, Sleeping apps on Samsung's. Open goes to that screen where the
 *     phone has it, else to the app's page in Android's settings.
 *
 * Offered when Mark my attendance is turned on, and when the app comes to the front with a step still to
 * do. Not now, or opening the maker's screen, keeps it quiet until an alarm is missed after that
 * ([AutoAttendance.lastMissed]); once both are done it never shows.
 */
object BatteryHelp {
    private const val LATER = "batteryLaterAt"
    private const val MAKER_DONE = "batteryMakerAt"

    private enum class Step { ALLOW, MAKER }

    /** Android still limits the app's battery use (Optimised or Restricted). */
    fun optimised(c: Context) = !c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

    /** The phone's maker, when it has its own switch that stops alarms waking an app; null for the rest (Pixel among them). */
    private val maker: String?
        get() = when (Build.MANUFACTURER.lowercase()) {
            "xiaomi", "redmi", "poco" -> "Xiaomi"
            "oppo", "realme", "oneplus" -> "OPPO"
            "vivo", "iqoo" -> "vivo"
            "huawei", "honor" -> "Huawei"
            "samsung" -> "Samsung"
            else -> null
        }

    /** Where each maker keeps its switch, newest software first: these screens move between versions. */
    private val SCREENS = mapOf(
        "Xiaomi" to listOf(
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
        "OPPO" to listOf(
            "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe/com.oppo.safe.permission.startup.StartupAppListActivity",
        ),
        "vivo" to listOf(
            "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        ),
        "Huawei" to listOf(
            "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.hihonor.systemmanager/com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager/com.huawei.systemmanager.optimize.process.ProtectActivity",
        ),
        "Samsung" to listOf(
            "com.samsung.android.lool/com.samsung.android.sm.battery.ui.BatteryActivity",
        ),
    )

    /** The step still to do, or null: none needed, or quiet since Not now with no alarm missed after it ([force]: asked from Settings). */
    private fun step(c: Context, force: Boolean): Step? {
        if (!AutoAttendance.enabled(c) || !SpectrumStore.signedIn(c)) return null
        val prefs = uiPrefs(c)
        val missed = AutoAttendance.lastMissed(c)
        if (!force && prefs.getLong(LATER, 0) > missed) return null
        if (optimised(c)) return Step.ALLOW
        if (maker != null && prefs.getLong(MAKER_DONE, 0) <= missed) return Step.MAKER
        return null
    }

    /** One popup at a time: the app coming back from Android's question mustn't stack another on it. */
    private var showing = false

    /** Shows the popup over the screen if a step is still to do; [force] when Mark my attendance was just turned on. */
    fun offer(ui: Ui, force: Boolean = false) {
        val c = ui.context
        val step = step(c, force) ?: return
        if (showing) return
        showing = true
        val p = ui.p
        val name = c.applicationInfo.loadLabel(c.packageManager)
        val (title, body, action) = when (step) {
            Step.ALLOW -> Triple(
                "Wake for class",
                "To mark your attendance as each class starts, $name has to wake up while your phone is asleep, and Android's battery saving can stop it. " +
                    "Allow it to run in the background: it only wakes for your classes and a sync every half hour.",
                "Allow",
            )
            Step.MAKER -> Triple(
                "One more switch",
                if (maker == "Samsung") "Samsung phones put apps to sleep on their own. On the next screen, open Background usage limits and take $name out of Sleeping apps and Deep sleeping apps."
                else "$maker phones also stop apps that aren't open. On the next screen, turn on Autostart for $name.",
                "Open",
            )
        }
        lateinit var close: (then: (() -> Unit)?) -> Unit
        val card = ConfirmDialog.frame(ui).apply {
            addView(ui.text(title, 30f, p.text, ui.f.sign))
            addView(ui.text(body, 15f, p.muted).apply { setLineSpacing(0f, 1.35f) }, ui.wide(top = 8f, bottom = 20f))
            addView(ui.row(gap = 8f).apply {
                addView(ConfirmDialog.button(ui, "Not now", p.surface2, p.text) {
                    uiPrefs(c).edit().putLong(LATER, System.currentTimeMillis()).apply()
                    close(null)
                }, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ConfirmDialog.button(ui, action, p.accent, p.accentInk) { close { open(c) } },
                    android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        close = ConfirmDialog.present(ui, card, onClosed = { showing = false })
    }

    /**
     * Opens the setting for the step still to do: Android's question while the battery is optimised, then
     * the maker's screen, else the app's page in Android's settings. Started rather than looked up first:
     * package visibility hides other apps' screens from a lookup.
     */
    fun open(c: Context) {
        fun start(i: Intent) = runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        val app = Uri.parse("package:${c.packageName}")
        if (optimised(c) && start(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, app))) return
        uiPrefs(c).edit().putLong(MAKER_DONE, System.currentTimeMillis()).apply()
        for (s in maker?.let { SCREENS[it] }.orEmpty()) if (start(Intent().setComponent(ComponentName.unflattenFromString(s)))) return
        start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app))
    }
}
