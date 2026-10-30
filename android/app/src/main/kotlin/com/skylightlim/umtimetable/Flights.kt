package com.skylightlim.umtimetable

import android.animation.TimeAnimator
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * What flies over the app going between Day and Week, drawn on one layer over the whole window (it takes no
 * touches): the Week chip turning into a rocket that flies up into Week's title and back down into the strip
 * ([rocket]), and, swiping into Week, each day's dots flying out of the strip into their classes on the grid
 * ([dot]). Prototyped as a canvas drawing first; sizes are in dp, drawn at the phone's density.
 *
 * Everything keeps time on one [TimeAnimator] and the layer takes itself off the window once nothing is left
 * flying ([onIdle]). Targets are asked for each frame, so a title still rising into place is hit where it is.
 */
@SuppressLint("ViewConstructor")
class FlightLayer(private val ui: Ui, private val onIdle: () -> Unit) : View(ui.context) {
    private val p = ui.p
    private val d = resources.displayMetrics.density
    private val clock = TimeAnimator()
    /** Milliseconds since the layer started: what every flight times itself by. */
    private var now = 0L
    private val rockets = mutableListOf<Rocket>()
    private val dots = mutableListOf<Dot>()
    private val puffs = mutableListOf<Puff>()
    private val bursts = mutableListOf<Burst>()
    private val at = IntArray(2)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ui.f.sign; textSize = 11 * d; textAlign = Paint.Align.CENTER; color = p.ground }
    private val flameCore = Palette.mix(Color.WHITE, p.accent, 0.7f)

    // The rocket, pointing right with its nose at (0, 0): a body with a pointed, gold-capped nose, two gold fins
    // and a flame (drawn each frame: it flickers).
    private val body = Path().apply {
        moveTo(-L + 2 * d, -R); lineTo(-12 * d, -R)
        quadTo(-3 * d, -R, 0f, 0f); quadTo(-3 * d, R, -12 * d, R)
        lineTo(-L + 2 * d, R); quadTo(-L, R, -L, R - 2 * d); lineTo(-L, -R + 2 * d); quadTo(-L, -R, -L + 2 * d, -R)
        close()
    }
    private val nose = Path().apply { moveTo(-12 * d, -R); quadTo(-3 * d, -R, 0f, 0f); quadTo(-3 * d, R, -12 * d, R); close() }
    private val fins = Path().apply {
        for (s in listOf(-1f, 1f)) {
            moveTo(-L + 11 * d, s * R * 0.9f); lineTo(-L + d, s * (R + 7 * d))
            quadTo(-L - 2 * d, s * (R + 7 * d), -L, s * R * 0.4f); close()
        }
    }
    private val flame = Path()
    private val L get() = LENGTH * d
    private val R get() = RADIUS * d

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clock.setTimeListener { _, total, _ ->
            now = total
            step()
            invalidate()
        }
    }

    /**
     * The Week chip (or Week's "W5", going back) drawn in [picture], at [from] on screen, crouches, shrinks away as
     * a rocket labelled [label] pops out of its middle, flies in an arc over to the point [to] gives (on screen) and
     * hits it: a flash, a ring and sparks. [hit] runs then (with true), for what was hit to show what the rocket
     * brought; or at once with false if another move [land]s it first.
     */
    fun rocket(picture: Bitmap?, from: RectF, label: String, to: () -> PointF?, hit: (Boolean) -> Unit) {
        rockets += Rocket(picture, RectF(from), label, to, hit, now)
        start()
    }

    /**
     * A dot of [color] at [from] on screen (a class's, under its day in the strip) waits [delay] ms, pops up out of
     * the strip, then flies in an arc down to the point [to] gives (its class on the grid) and lands: [land] runs
     * with that point, for the class to fill in from it. A dot whose target is gone just fades.
     */
    fun dot(color: Int, from: PointF, delay: Long, to: () -> PointF?, land: (PointF) -> Unit) {
        dots += Dot(color, PointF(from.x, from.y), now + delay, to, land)
        start()
    }

    /** Every rocket still flying lands at once, without its hit (another move has begun: what it brings is shown now). */
    fun land() {
        val flying = rockets.toList()
        rockets.clear()
        flying.forEach { it.hit(false) }
    }

    private fun start() {
        if (!clock.isStarted) clock.start()
    }

    /** Moves the clock's flights on: smoke behind the rockets, the hit when one arrives, and the layer away when all is done. */
    private fun step() {
        for (r in rockets.toList()) {
            val t = now - r.born
            if (t in MORPH until MORPH + FLY && now - r.lastPuff >= PUFF_EVERY) {
                r.lastPuff = now
                r.tail()?.let { puffs += Puff(it.x, it.y, now) }
            }
            if (t >= MORPH + FLY) {
                rockets.remove(r)
                r.end?.let { bursts += Burst(it.x, it.y, now) }
                r.hit(true)
            }
        }
        for (dot in dots.toList()) {
            if (now - dot.born < DOT_OUT + DOT_FLY) continue
            dots.remove(dot)
            (dot.to() ?: dot.end)?.let(dot.land)
        }
        puffs.removeAll { now - it.born > PUFF_LIFE }
        bursts.removeAll { now - it.born > HIT }
        if (rockets.isEmpty() && dots.isEmpty() && puffs.isEmpty() && bursts.isEmpty()) {
            clock.cancel()
            (parent as? ViewGroup)?.removeView(this)
            onIdle()
        }
    }

    override fun onDetachedFromWindow() {
        clock.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(at)
        canvas.save()
        // Flights are in screen coordinates.
        canvas.translate(-at[0].toFloat(), -at[1].toFloat())
        for (puff in puffs) {
            val k = (now - puff.born) / PUFF_LIFE.toFloat()
            fill.color = p.muted
            fill.alpha = (0.35f * (1 - k) * 255).toInt()
            canvas.drawCircle(puff.x, puff.y, (2.5f + 5 * k) * d, fill)
        }
        fill.alpha = 255
        for (dot in dots) dot.draw(canvas)
        fill.alpha = 255
        for (r in rockets) r.draw(canvas)
        for (b in bursts) drawBurst(canvas, b)
        canvas.restore()
    }

    /** The hit: a flash, a ring spreading and fading, and eight sparks flying out. */
    private fun drawBurst(canvas: Canvas, b: Burst) {
        val k = (now - b.born) / HIT.toFloat()
        val out = 1 - (1 - k).pow(3)
        if (k < 0.35f) {
            fill.color = p.accent
            fill.alpha = (0.7f * (1 - k / 0.35f) * 255).toInt()
            canvas.drawCircle(b.x, b.y, (10 + 8 * k / 0.35f) * d, fill)
            fill.alpha = 255
        }
        stroke.color = p.accent
        stroke.alpha = ((1 - k) * 255).toInt()
        stroke.strokeWidth = (3 * (1 - k) + 0.5f) * d
        canvas.drawCircle(b.x, b.y, (4 + 24 * out) * d, stroke)
        stroke.strokeWidth = 2 * d
        for (i in 0 until 8) {
            val a = i * PI.toFloat() / 4 + 0.3f
            val r0 = (6 + 16 * out) * d
            val r1 = r0 + (6 * (1 - k) + 1) * d
            canvas.drawLine(b.x + cos(a) * r0, b.y + sin(a) * r0, b.x + cos(a) * r1, b.y + sin(a) * r1, stroke)
        }
    }

    /** The rocket, its nose at ([x], [y]) heading [angle] (radians), [grow] its size (popping out, 0 to a little over 1). */
    private fun drawRocket(canvas: Canvas, x: Float, y: Float, angle: Float, grow: Float, label: String) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(angle * 180 / PI.toFloat())
        canvas.scale(grow, grow, -L / 2, 0f)
        if (grow > 0.6f) {
            val k = ((grow - 0.6f) / 0.4f).coerceAtMost(1f)
            val f = (9 + 3 * sin(now * 0.09f) + 1.5f * sin(now * 0.23f)) * d * k
            fill.color = p.accent
            canvas.drawPath(flame(f, 0.65f, 0.75f, 0.7f, 1f), fill)
            fill.color = flameCore
            canvas.drawPath(flame(f, 0.35f, 0.4f, 0.4f, 0.55f), fill)
        }
        fill.color = p.accent
        canvas.drawPath(fins, fill)
        fill.color = p.text
        canvas.drawPath(body, fill)
        canvas.save()
        canvas.clipPath(nose)
        fill.color = p.accent
        canvas.drawRect(-6 * d, -R, 2 * d, R, fill)
        canvas.restore()
        // The label along its side, never upside down.
        canvas.translate(-L / 2 - 3 * d, 0f)
        if (cos(angle) < 0) canvas.rotate(180f)
        canvas.drawText(label, 0f, labelPaint.textSize * 0.36f, labelPaint)
        canvas.restore()
    }

    /** A flame [f] long out of the tail, as wide as [base] and [bulge] of the body's half height, its curve [reach] along. */
    private fun flame(f: Float, base: Float, bulge: Float, reach: Float, length: Float) = flame.apply {
        reset()
        moveTo(-L, -R * base)
        quadTo(-L - f * reach, -R * bulge, -L - f * length, 0f)
        quadTo(-L - f * reach, R * bulge, -L, R * base)
        close()
    }

    private inner class Rocket(val picture: Bitmap?, val from: RectF, val label: String, val to: () -> PointF?, val hit: (Boolean) -> Unit, val born: Long) {
        var lastPuff = born
        /** Where the target was last seen: it may be gone by the time the rocket gets there. */
        var end: PointF? = null

        /** The arc: from the nose's place at launch (in front of the chip's middle), over the top, to the target. */
        private fun path(): Triple<PointF, PointF, PointF>? {
            val e = to()?.also { end = it } ?: end ?: return null
            val s0 = PointF(from.centerX(), from.centerY())
            val c0 = control(s0, e)
            val dx = c0.x - s0.x
            val dy = c0.y - s0.y
            val n = hypot(dx, dy).coerceAtLeast(1f)
            val s = PointF(s0.x + dx / n * L / 2, s0.y + dy / n * L / 2)
            return Triple(s, control(s, e), e)
        }

        /** Over the top and in from above: above both ends, leaning towards the screen's middle (the chip and title are at its right). */
        private fun control(s: PointF, e: PointF): PointF {
            val dist = hypot(e.x - s.x, e.y - s.y)
            return PointF((s.x + e.x) / 2 - 0.25f * dist, min(s.y, e.y) - 0.6f * dist)
        }

        /** Where the flight is at [t] (0 to 1, eased): the nose's place and heading. */
        private fun at(t: Float): Pair<PointF, Float>? {
            val (s, c, e) = path() ?: return null
            val u = 1 - t
            val x = u * u * s.x + 2 * u * t * c.x + t * t * e.x
            val y = u * u * s.y + 2 * u * t * c.y + t * t * e.y
            val dx = 2 * u * (c.x - s.x) + 2 * t * (e.x - c.x)
            val dy = 2 * u * (c.y - s.y) + 2 * t * (e.y - c.y)
            return PointF(x, y) to atan2(dy, dx)
        }

        /** A missile: slow off the pad, fast into what it hits. */
        private fun eased() = ((now - born - MORPH) / FLY.toFloat()).coerceIn(0f, 1f).pow(1.7f)

        fun tail(): PointF? = at(eased())?.let { (nose, a) -> PointF(nose.x - cos(a) * (L + 4 * d), nose.y - sin(a) * (L + 4 * d)) }

        fun draw(canvas: Canvas) {
            val t = now - born
            if (t >= MORPH) {
                val (nose, a) = at(eased()) ?: return
                return drawRocket(canvas, nose.x, nose.y, a, 1f, label)
            }
            // It crouches (a squash), then shrinks away as the rocket pops out of its middle, pointing where it goes.
            val k = t / MORPH.toFloat()
            val crouch = sin(min(1f, k / 0.45f) * PI.toFloat()) * 0.14f
            val shrink = if (k < 0.45f) 1f else 1 - ((k - 0.45f) / 0.55f).pow(2)
            picture?.let { pic ->
                canvas.save()
                canvas.translate(from.centerX(), from.centerY() + from.height() * crouch * 0.4f)
                canvas.scale((1 + crouch) * shrink, (1 - crouch) * shrink)
                bitmapPaint.alpha = (min(1f, shrink * 1.5f) * 255).toInt()
                canvas.drawBitmap(pic, null, RectF(-from.width() / 2, -from.height() / 2, from.width() / 2, from.height() / 2), bitmapPaint)
                canvas.restore()
            }
            if (k > 0.45f) {
                val r = (k - 0.45f) / 0.55f
                val pop = if (r < 0.7f) 1.12f * sin(r / 0.7f * PI.toFloat() / 2) else 1.12f - 0.12f * (r - 0.7f) / 0.3f
                val (nose, a) = at(0f) ?: return
                drawRocket(canvas, nose.x, nose.y, a, pop, label)
            }
        }
    }

    private inner class Dot(val color: Int, val from: PointF, val born: Long, val to: () -> PointF?, val land: (PointF) -> Unit) {
        /** Where its class was last seen, should it go (another redraw) before the dot gets there. */
        var end: PointF? = null

        fun draw(canvas: Canvas) {
            val t = now - born
            if (t < 0) return drawDot(canvas, from.x, from.y, 1f, 255)
            if (t < DOT_OUT) {
                // Out of the strip: up a little, swelling, easing off at the top.
                val k = t / DOT_OUT.toFloat()
                val up = 1 - (1 - k) * (1 - k)
                return drawDot(canvas, from.x, from.y - DOT_RISE * d * up, 1 + 0.5f * up, 255)
            }
            val e = to()?.also { end = it } ?: end
            val k = ((t - DOT_OUT) / DOT_FLY.toFloat()).coerceIn(0f, 1f)
            if (e == null) return drawDot(canvas, from.x, from.y - DOT_RISE * d, 1.5f, ((1 - k) * 255).toInt())
            // Over a little hump and down into its class, slow to leave and quick to land.
            val sx = from.x
            val sy = from.y - DOT_RISE * d
            val cx = (sx + e.x) / 2
            val cy = sy - 24 * d
            val u = k * k * (3 - 2 * k)
            val v = 1 - u
            drawDot(canvas, v * v * sx + 2 * v * u * cx + u * u * e.x, v * v * sy + 2 * v * u * cy + u * u * e.y, 1.5f, 255)
        }

        private fun drawDot(canvas: Canvas, x: Float, y: Float, scale: Float, alpha: Int) {
            fill.color = color
            fill.alpha = alpha
            canvas.drawCircle(x, y, DOT_RADIUS * d * scale, fill)
        }
    }

    private class Puff(val x: Float, val y: Float, val born: Long)
    private class Burst(val x: Float, val y: Float, val born: Long)

    companion object {
        /** The rocket's length and half its body's height (dp). */
        private const val LENGTH = 40f
        private const val RADIUS = 8f
        /** How long the chip takes to turn into the rocket, the flight, and the hit's flash and ring (ms). */
        private const val MORPH = 200L
        private const val FLY = 420L
        private const val HIT = 320L
        /** A dot pops this far up out of the strip (dp) in [DOT_OUT] ms, then takes [DOT_FLY] ms to its class; it's 6dp across. */
        private const val DOT_RISE = 10f
        private const val DOT_OUT = 110L
        private const val DOT_FLY = 380L
        const val DOT_RADIUS = 3f
        /** A puff of smoke behind the rocket this often, each fading over its life (ms). */
        private const val PUFF_EVERY = 22L
        private const val PUFF_LIFE = 380L

        /** [v] as it looks, on a [fill] of its own (a chip's slot) with [radius] corners, for a flight to start from. */
        fun picture(v: View, fill: Int = Color.TRANSPARENT, radius: Float = 0f): Bitmap? {
            if (v.width <= 0 || v.height <= 0) return null
            val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            if (fill != Color.TRANSPARENT) canvas.drawRoundRect(RectF(0f, 0f, v.width.toFloat(), v.height.toFloat()), radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
            v.draw(canvas)
            return bitmap
        }
    }
}
