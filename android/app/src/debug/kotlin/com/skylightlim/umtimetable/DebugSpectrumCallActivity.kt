package com.skylightlim.umtimetable

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug only: makes one web-service call in the foreground (where the phone allows the app's
 * network even with background data off), saves the reply under no_backup/spectrum/debug-<out>.json,
 * and closes. "$userid" in args is replaced with the signed-in user's id.
 *   adb shell am start -n com.skylightlim.umtimetable/.DebugSpectrumCallActivity \
 *     --es fn core_group_get_course_user_groups --es args '{"courseid":0,"userid":"$userid"}' --es out groups
 */
class DebugSpectrumCallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Checking Spectrum…"; setPadding(48, 160, 48, 48) })
        val fn = intent.getStringExtra("fn") ?: return finish()
        if (fn == "sync") {
            // A full Spectrum sync in the foreground, where the phone allows the app's network.
            thread {
                Log.i("UMTimetable", "debug sync: ${SpectrumSync.run(applicationContext) ?: "ok"}")
                runOnUiThread { finish() }
            }
            return
        }
        val out = intent.getStringExtra("out") ?: fn
        val args = (intent.getStringExtra("args") ?: "{}").replace("\"\$userid\"", SpectrumStore.userId(this).toString())
        thread {
            try {
                val token = SpectrumStore.token(this) ?: error("not signed in")
                @Suppress("UNCHECKED_CAST")
                val reply = Spectrum.call(token, fn, toKotlin(JSONObject(args)) as Map<String, Any?>).toString()
                File(noBackupFilesDir, "spectrum/debug-$out.json").writeText(reply)
                Log.i("UMTimetable", "debug call $fn ok: ${reply.length} chars -> debug-$out.json")
            } catch (e: Exception) {
                Log.w("UMTimetable", "debug call $fn failed: ${e.message}")
            }
            runOnUiThread { finish() }
        }
    }

    private fun toKotlin(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().associateWith { toKotlin(v.get(it)) }
        is JSONArray -> (0 until v.length()).map { toKotlin(v.get(it)) }
        JSONObject.NULL -> null
        else -> v
    }
}
