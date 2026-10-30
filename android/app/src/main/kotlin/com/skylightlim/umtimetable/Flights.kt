package com.skylightlim.umtimetable

import android.animation.TimeAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.text.TextPaint
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * What flies over the app going between Day and Week, drawn on one layer over the whole window (it takes no
 * touches): the week's tile (the Week chip, or the tile on Week's title) turning into a rocket that flies up into
 * Week's title and back down into the strip ([rocket]), and, swiping into Week, each day's dots flying out of the strip into their classes on the grid
 * ([dot]). Prototyped as a canvas drawing first; sizes are in dp, drawn at the phone's density.
 *
 * Everything keeps time on one [TimeAnimator] and the layer takes itself off the window once nothing is left
 * flying ([onIdle]). Taken off any other way (the window's content replaced), whatever is flying lands at once,
 * so nothing it brings stays hidden. Targets are asked for each frame, so a title still rising into place is hit
 * where it is.
 */
@SuppressLint("ViewConstructor")
class FlightLayer(private val ui: Ui, private val onIdle: (FlightLayer) -> Unit) : View(ui.context) {
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
    private val flameCore = Palette.mix(Color.WHITE, p.accent, 0.7f)
    private val shadow = Color.argb(36, 0, 0, 0)

    // The rocket's parts, its nose at (0, 0) and its tail at -L: two gold fins and a flame (made each frame: it
    // flickers). Its body is the tile's, reshaped ([body]).
    private val body = Path()
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
     * The week's [tile] (the Week chip, or the tile on Week's title going back) crouches and turns into a rocket,
     * labelled with its first line ("W5"), that flies in an arc over to the point [to] gives (on screen) and hits
     * it: a flash, a ring and sparks. [hit] runs then (with true), for what was hit to show what the rocket
     * brought; or at once with false if another move [land]s it first.
     */
    fun rocket(tile: Tile, to: () -> PointF?, hit: (Boolean) -> Unit) {
        rockets += Rocket(tile, to, hit, now)
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
        if (rockets.isEmpty() && dots.isEmpty() && puffs.isEmpty() && bursts.isEmpty()) (parent as? ViewGroup)?.removeView(this)
    }

    override fun onDetachedFromWindow() {
        clock.cancel()
        land()
        dots.clear()
        puffs.clear()
        bursts.clear()
        onIdle(this)
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

    /** The rocket in flight: its nose at ([x], [y]) heading [angle] (radians), [label] along its side, never upside down. */
    private fun drawRocket(canvas: Canvas, x: Float, y: Float, angle: Float, colour: Int, label: Paint, text: String) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(angle * 180 / PI.toFloat())
        canvas.translate(-L / 2, 0f)
        drawBody(canvas, L / 2, R, R, 1f, colour, angle)
        canvas.translate(-3 * d, 0f)
        if (cos(angle) < 0) canvas.rotate(180f)
        canvas.drawText(text, 0f, label.textSize * 0.36f, label)
        canvas.restore()
    }

    /**
     * The body, its middle at (0, 0) and heading right, [hx] half long and [hy] half thick, in the tile's [colour]: a
     * tile's rounded rectangle with [corner] corners while [parts] is 0, the rocket as it grows to 1 (its nose
     * sharpening and capped in gold, the tail's corners tightening, the fins and the flame growing out). It's
     * turned [angle] (radians) on the screen, for its shadow to fall down the screen.
     */
    private fun drawBody(canvas: Canvas, hx: Float, hy: Float, corner: Float, parts: Float, colour: Int, angle: Float) {
        if (parts > 0f) {
            canvas.save()
            canvas.translate(L - hx, 0f) // the parts' tail (-L) at the body's (-hx)
            val f = (9 + 3 * sin(now * 0.09f) + 1.5f * sin(now * 0.23f)) * d * parts
            fill.color = p.accent
            canvas.drawPath(flame(f, 0.65f, 0.75f, 0.7f, 1f), fill)
            fill.color = flameCore
            canvas.drawPath(flame(f, 0.35f, 0.4f, 0.4f, 0.55f), fill)
            canvas.scale(1f, parts) // the fins grow out of the body's sides
            fill.color = p.accent
            canvas.drawPath(fins, fill)
            canvas.restore()
        }
        body(-hx, hx, hy, lerp(corner, 2 * d, parts), lerp(corner, 12 * d, parts), lerp(0f, 3 * d, parts), lerp(hy - corner, 0f, parts))
        // A soft shadow lifts it off the page: 1.5dp down the screen, whichever way it's turned.
        canvas.save()
        canvas.translate(sin(angle) * 1.5f * d, cos(angle) * 1.5f * d)
        fill.color = shadow
        canvas.drawPath(body, fill)
        canvas.restore()
        fill.color = colour
        canvas.drawPath(body, fill)
        if (parts > 0f) {
            canvas.save()
            canvas.clipPath(body)
            fill.color = p.accent
            canvas.drawRect(hx - 6 * d * parts, -hy, hx + 2 * d, hy, fill)
            canvas.restore()
        }
    }

    /**
     * Every stage's body in one shape: from the tail at [back] to the front at [front], [hy] half thick, the tail's
     * corners [tail] round; at the front, straight to [a] short of it, then curving (towards [b] short of it) to
     * ±[c] across it: a tile's corners (a = its corner, b = 0, c = hy less its corner) or a pointed nose (c = 0).
     */
    private fun body(back: Float, front: Float, hy: Float, tail: Float, a: Float, b: Float, c: Float) = body.apply {
        reset()
        moveTo(back + tail, -hy)
        lineTo(front - a, -hy)
        quadTo(front - b, -hy, front, -c)
        lineTo(front, c)
        quadTo(front - b, hy, front - a, hy)
        lineTo(back + tail, hy)
        quadTo(back, hy, back, hy - tail)
        lineTo(back, -hy + tail)
        quadTo(back, -hy, back + tail, -hy)
        close()
    }

    /** A flame [f] long out of the tail, as wide as [base] and [bulge] of the body's half height, its curve [reach] along. */
    private fun flame(f: Float, base: Float, bulge: Float, reach: Float, length: Float) = flame.apply {
        reset()
        moveTo(-L, -R * base)
        quadTo(-L - f * reach, -R * bulge, -L - f * length, 0f)
        quadTo(-L - f * reach, R * bulge, -L, R * base)
        close()
    }

    private inner class Rocket(val tile: Tile, val to: () -> PointF?, val hit: (Boolean) -> Unit, val born: Long) {
        var lastPuff = born
        /** Where the target was last seen: it may be gone by the time the rocket gets there. */
        var end: PointF? = null
        /** The tile's first line is the rocket's label ("W5"), drawn along its side in flight. */
        val word = tile.words.firstOrNull()
        val label = TextPaint(word?.paint ?: Paint(Paint.ANTI_ALIAS_FLAG)).apply { typeface = ui.f.sign; textSize = 11 * d; textAlign = Paint.Align.CENTER }
        private val from get() = tile.rect

        /** The arc: from the nose's place at launch (in front of the tile's middle), over the top, to the target. */
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

        /** Over the top and in from above: above both ends, leaning towards the screen's middle (the tiles are at its right). */
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
            val text = word?.text.orEmpty()
            if (t >= MORPH) {
                val (nose, a) = at(eased()) ?: return
                return drawRocket(canvas, nose.x, nose.y, a, tile.fill, label, text)
            }
            // The tile itself becomes the rocket: it crouches (a squash), then tips towards where it's going and
            // narrows into the body, its first line turning to run along it and shrinking, the rest fading; last
            // the nose sharpens and the fins and the flame grow out.
            val heading = at(0f)?.second ?: (-PI / 2).toFloat()
            val k = t / MORPH.toFloat()
            val crouch = if (k < 0.3f) sin(k / 0.3f * PI.toFloat()) * 0.12f else 0f
            val m = smooth((k - 0.25f) / 0.55f)
            val parts = smooth((k - 0.75f) / 0.25f)
            val cx = from.centerX()
            val cy = from.centerY()
            val sink = from.height() * crouch * 0.4f
            canvas.save()
            canvas.translate(cx, cy + sink)
            canvas.scale(1 + crouch, 1 - crouch)
            // Upright (its long side down the screen) to along its heading.
            val turn = -PI.toFloat() / 2 + wrap(heading + PI.toFloat() / 2) * m
            canvas.rotate(turn * 180 / PI.toFloat())
            drawBody(canvas, lerp(from.height() / 2, L / 2, m), lerp(from.width() / 2, R, m), lerp(tile.radius, R, m), parts, tile.fill, turn)
            canvas.restore()
            for ((i, w) in tile.words.withIndex()) {
                if (i > 0) {
                    val fade = 1 - smooth(k / 0.4f)
                    if (fade <= 0f) continue
                    w.paint.alpha = (fade * 255).toInt()
                    canvas.drawText(w.text, cx + (w.x - cx) * (1 + crouch), cy + sink + (w.baseline - cy) * (1 - crouch), w.paint)
                    w.paint.alpha = 255
                    continue
                }
                // From its place in the tile to the label's on the body, turning from level to the rocket's way.
                val endAngle = wrap(heading + if (cos(heading) < 0) PI.toFloat() else 0f)
                val size = lerp(w.paint.textSize, label.textSize, m)
                val midX = lerp(w.x, cx - cos(heading) * 3 * d, m)
                val midY = lerp(w.baseline - w.paint.textSize * 0.36f, cy - sin(heading) * 3 * d, m) + sink
                canvas.save()
                canvas.translate(midX, midY)
                canvas.rotate(endAngle * m * 180 / PI.toFloat())
                label.textSize = size
                canvas.drawText(w.text, 0f, size * 0.36f, label)
                label.textSize = 11 * d
                canvas.restore()
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

    /** A tile as it looks on screen, for a rocket to be made from: its place, corners and colour, and its lines of text. */
    class Tile(val rect: RectF, val radius: Float, val fill: Int, val words: List<Word>)

    /** A line of a tile's text as shown: its middle across and its baseline on screen, in its own paint. */
    class Word(val text: String, val x: Float, val baseline: Float, val paint: TextPaint)

    private class Puff(val x: Float, val y: Float, val born: Long)
    private class Burst(val x: Float, val y: Float, val born: Long)

    companion object {
        /** The rocket's length and half its body's height (dp). */
        private const val LENGTH = 40f
        private const val RADIUS = 8f
        /** How long the tile takes to turn into the rocket, the flight, and the hit's flash and ring (ms). */
        private const val MORPH = 280L
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

        /** [v] (a tile: a column of lines) as it looks on screen, on a [fill] with [radius] corners (its own, or its slot's). */
        fun tile(v: ViewGroup, radius: Float, fill: Int): Tile {
            val at = IntArray(2)
            val words = (0 until v.childCount).map(v::getChildAt).filterIsInstance<TextView>().filter { it.visibility == VISIBLE && it.width > 0 }.map { tv ->
                tv.getLocationOnScreen(at)
                val shown = (tv.transformationMethod?.getTransformation(tv.text, tv) ?: tv.text).toString()
                val lineLeft = tv.layout?.getLineLeft(0) ?: 0f
                val lineWidth = tv.layout?.getLineWidth(0) ?: tv.paint.measureText(shown)
                Word(shown, at[0] + tv.compoundPaddingLeft + lineLeft + lineWidth / 2, (at[1] + tv.baseline).toFloat(),
                    TextPaint(tv.paint).apply { color = tv.currentTextColor; textAlign = Paint.Align.CENTER })
            }
            return Tile(Motion.onScreen(v), radius, fill, words)
        }

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        private fun smooth(x: Float): Float = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
        /** [a] (radians) between -π and π. */
        private fun wrap(a: Float): Float {
            var r = a
            while (r > PI) r -= 2 * PI.toFloat()
            while (r < -PI) r += 2 * PI.toFloat()
            return r
        }
    }
}
