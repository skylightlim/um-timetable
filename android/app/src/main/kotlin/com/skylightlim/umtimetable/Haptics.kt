package com.skylightlim.umtimetable

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View

/**
 * Vibration feedback at the strength the user picks in Courses (Off, Light, Medium, Strong): a click when
 * a selection changes (tab, day, course page), a click and a softer tap going back, and a click when a
 * drag passes the point where letting go would switch day or close the sheet (a tap coming back before
 * it). The platform's own haptic constants were too faint and have no strength, so these are composed
 * from the vibrator's primitives scaled to the level; a phone without them gets the predefined tick,
 * click and heavy click. Played as touch feedback, so Android's touch-feedback setting still applies.
 */
object Haptics {
    enum class Level(val label: String, val scale: Float) { OFF("Off", 0f), LIGHT("Light", 0.4f), MEDIUM("Medium", 0.7f), STRONG("Strong", 1f) }

    private enum class Kind { SELECT, BACK, CROSS, UNCROSS }

    private fun prefs(c: Context) = c.getSharedPreferences("ui", Context.MODE_PRIVATE)

    /** Medium unless chosen; the first version's on/off switch, if turned off, means Off. */
    fun level(c: Context): Level {
        val p = prefs(c)
        if (p.contains("hapticLevel")) return Level.entries.getOrElse(p.getInt("hapticLevel", 2)) { Level.MEDIUM }
        return if (p.getBoolean("haptics", true)) Level.MEDIUM else Level.OFF
    }

    fun setLevel(c: Context, level: Level) = prefs(c).edit().putInt("hapticLevel", level.ordinal).apply()

    /** The level after [level] when the row is tapped: Off → Light → Medium → Strong → Off. */
    fun next(level: Level) = Level.entries[(level.ordinal + 1) % Level.entries.size]

    /** Another tab, day or page was chosen. Also what a level feels like, when it's picked. */
    fun tick(v: View) = play(v, Kind.SELECT)

    /** Back to where the user came from. */
    fun back(v: View) = play(v, Kind.BACK)

    /** A drag crossed the point of no return ([on]) or came back before it. */
    fun threshold(v: View, on: Boolean) = play(v, if (on) Kind.CROSS else Kind.UNCROSS)

    private fun play(v: View, kind: Kind) {
        val level = level(v.context)
        if (level == Level.OFF) return
        val vibrator = v.context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        if (!vibrator.hasVibrator()) return
        val effect = effect(vibrator, kind, level)
        if (Build.VERSION.SDK_INT >= 33) vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        else vibrator.vibrate(effect)
    }

    private fun effect(vibrator: Vibrator, kind: Kind, level: Level): VibrationEffect {
        val s = level.scale
        if (vibrator.areAllPrimitivesSupported(Composition.PRIMITIVE_CLICK, Composition.PRIMITIVE_TICK)) {
            val c = VibrationEffect.startComposition()
            when (kind) {
                Kind.SELECT, Kind.CROSS -> c.addPrimitive(Composition.PRIMITIVE_CLICK, s)
                Kind.BACK -> c.addPrimitive(Composition.PRIMITIVE_CLICK, s).addPrimitive(Composition.PRIMITIVE_TICK, s * 0.7f, 50)
                Kind.UNCROSS -> c.addPrimitive(Composition.PRIMITIVE_TICK, s)
            }
            return c.compose()
        }
        val id = when {
            kind == Kind.UNCROSS || level == Level.LIGHT -> VibrationEffect.EFFECT_TICK
            kind == Kind.BACK -> VibrationEffect.EFFECT_DOUBLE_CLICK
            level == Level.STRONG -> VibrationEffect.EFFECT_HEAVY_CLICK
            else -> VibrationEffect.EFFECT_CLICK
        }
        return VibrationEffect.createPredefined(id)
    }
}
