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

    /**
     * Holding a button for something that can't be undone (Sign out): the app sobs for [ms]. Little bursts
     * of hics ("hic-hic-hic"), each burst stronger and quicker, the pauses between them shorter, until the
     * last stretch is one wail rising to the end. [hush] stops it when the button is let go.
     */
    fun cry(v: View, ms: Long) {
        val level = level(v.context)
        if (level == Level.OFF || ms <= 0) return
        val vibrator = vibrator(v) ?: return
        vibrate(vibrator, sobs(vibrator, ms, level.scale))
    }

    /** Stops [cry] (or anything else the app is playing). */
    fun hush(v: View) = vibrator(v)?.cancel()

    private fun sobs(vibrator: Vibrator, ms: Long, s: Float): VibrationEffect {
        val tick = Composition.PRIMITIVE_TICK
        val low = Composition.PRIMITIVE_LOW_TICK
        val rise = Composition.PRIMITIVE_QUICK_RISE
        if (vibrator.areAllPrimitivesSupported(tick, low, rise)) {
            val (tickMs, lowMs, riseMs) = vibrator.getPrimitiveDurations(tick, low, rise).toList()
            val c = VibrationEffect.startComposition()
            var t = 0L
            var burst = 0
            // Bursts until a wail would just fill what's left.
            while (t + riseMs + 120 < ms) {
                val strength = (0.3f + 0.14f * burst).coerceAtMost(1f) * s
                val gap = (70 - 8 * burst).coerceAtLeast(38)
                for (hic in 0 until 3) {
                    val wait = if (hic == 0) (if (burst == 0) 0 else (190 - 45 * burst).coerceAtLeast(50)) else gap
                    c.addPrimitive(if (hic == 0) low else tick, (strength * (0.8f + 0.1f * hic)).coerceAtMost(1f), wait)
                    t += wait + if (hic == 0) lowMs else tickMs
                }
                burst++
            }
            // The wail: rising to the end, as loud as the level allows.
            c.addPrimitive(rise, s, (ms - t - riseMs).coerceIn(0, 200).toInt())
            return c.compose()
        }
        // Without primitives: the same bursts as pulses, stronger if the motor can vary its strength.
        val timings = mutableListOf(0L)
        val amps = mutableListOf(0)
        var t = 0L
        var burst = 0
        while (t < ms - 250) {
            val a = ((0.35f + 0.15f * burst).coerceAtMost(1f) * s * 255).toInt().coerceIn(1, 255)
            repeat(3) { hic ->
                timings += 22L; amps += a
                val wait = if (hic < 2) (60L - 6 * burst).coerceAtLeast(35) else (180L - 40 * burst).coerceAtLeast(45)
                timings += wait; amps += 0
                t += 22 + wait
            }
            burst++
        }
        timings += (ms - t).coerceAtLeast(120); amps += (s * 255).toInt().coerceIn(1, 255)
        return if (vibrator.hasAmplitudeControl()) VibrationEffect.createWaveform(timings.toLongArray(), amps.toIntArray(), -1)
        else VibrationEffect.createWaveform(timings.toLongArray(), -1)
    }

    private fun vibrator(v: View) = v.context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.takeIf { it.hasVibrator() }

    private fun vibrate(vibrator: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= 33) vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        else vibrator.vibrate(effect)
    }

    private fun play(v: View, kind: Kind) {
        val level = level(v.context)
        if (level == Level.OFF) return
        val vibrator = vibrator(v) ?: return
        vibrate(vibrator, effect(vibrator, kind, level))
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
