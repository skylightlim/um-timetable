package com.skylightlim.umtimetable

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * 马大牛逼's loader and pull-to-sync mark: the cow (牛, left) and the horse (马, right) in one colour of
 * rounded line art, each raising a front hoof with a sword. [progress] 0 → 1 draws the swords out and
 * swings them in until the blades cross over the middle, where a spark flies: the pull has gone far
 * enough. [loop] keeps them clashing while something loads; [finish] lands one last clash.
 *
 * Drawn in a 136 × 60 box (y down), scaled to the view, with [ink] for every line and fill and [paper]
 * to mask what's behind the heads and necks. The animals stand at the two ends with the middle clear;
 * the swords start held back over their shoulders and sweep forward to cross at (68, 11).
 */
@SuppressLint("ViewConstructor")
class DuelView(private val ui: Ui, private val ink: Int = ui.p.text, private val paper: Int = ui.p.ground) : View(ui.context) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.2f
        color = ink
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = ink }
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = paper }
    private val path = Path()
    private val rect = RectF()

    /** 0: standing, swords sheathed; 0.15–0.5: drawing them; 0.5–1: swinging in; 1: the blades cross. */
    var progress = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            // Reaching the cross: a spark (once per crossing).
            if (v >= 1f && field < 1f) spark()
            field = v
            invalidate()
        }

    /** The spark's life, 0 (just struck) to 1 (gone); above 1 there's none. Settable for still previews. */
    internal var sparkAge = 2f
    private var looper: ValueAnimator? = null
    private var sparker: ValueAnimator? = null

    init {
        contentDescription = "A cow and a horse sword fighting"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(ui.dp(124), ui.dp(56))

    /** Keeps them fighting: swing in, clash with a spark, recoil, again, about every 0.9 s. */
    fun loop() {
        stop()
        if (!Motion.enabled) { progress = 1f; return }
        looper = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                // In fast (0–0.4), hold the clash (0.4–0.55), back to the guard (0.55–1).
                progress = when {
                    t < 0.4f -> 0.6f + 0.4f * ease(t / 0.4f)
                    t < 0.55f -> 1f
                    else -> 1f - 0.4f * ease((t - 0.55f) / 0.45f)
                }
            }
            start()
        }
    }

    /** The loading is done: one last clash, and the spark stays a moment longer. */
    fun finish() {
        stop()
        if (progress >= 1f) spark()
        if (!Motion.enabled) { progress = 1f; return }
        ValueAnimator.ofFloat(progress, 1f).apply {
            duration = 180
            interpolator = Motion.SMOOTH
            addUpdateListener { progress = it.animatedValue as Float }
            start()
        }
    }

    fun stop() {
        looper?.cancel()
        looper = null
    }

    private fun spark() {
        if (!Motion.enabled) return
        sparker?.cancel()
        sparker = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            addUpdateListener { sparkAge = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** As a loader: fight for as long as it's on screen. */
    var loopWhileShown = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (loopWhileShown) loop()
    }

    override fun onDetachedFromWindow() {
        stop()
        sparker?.cancel()
        super.onDetachedFromWindow()
    }

    private fun ease(x: Float) = 1 - (1 - x) * (1 - x)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    override fun onDraw(canvas: Canvas) {
        val k = width / 136f
        canvas.save()
        canvas.scale(k, k)
        val draw = ((progress - 0.15f) / 0.35f).coerceIn(0f, 1f)   // the blades come out
        val swing = ((progress - 0.5f) / 0.5f).coerceIn(0f, 1f)    // and sweep forward
        val lean = 2.5f * ease(swing)
        canvas.drawLine(3f, 55.5f, 133f, 55.5f, line) // the ground
        canvas.save(); canvas.translate(lean, 0f); cow(canvas); canvas.restore()
        canvas.save(); canvas.translate(-lean, 0f); horse(canvas); canvas.restore()
        // The sword legs and swords last, over everything: from held back to crossed in the middle.
        val e = ease(swing)
        canvas.save(); canvas.translate(lean, 0f)
        arm(canvas, 33f, 37f, lerp(40f, 50f, e), lerp(45f, 35f, e), lerp(-128f, -56.3f, e), draw)
        canvas.restore()
        canvas.save(); canvas.translate(-lean, 0f)
        arm(canvas, 103f, 37f, lerp(96f, 86f, e), lerp(45f, 35f, e), lerp(-52f, -123.7f, e), draw)
        canvas.restore()
        spark(canvas)
        canvas.restore()
    }

    // --- the cow, at the left, facing right -------------------------------------------------

    private fun cow(c: Canvas) {
        path.reset(); path.moveTo(8f, 31f); path.quadTo(3f, 35f, 5f, 43f); c.drawPath(path, line)
        c.drawCircle(5f, 45f, 2.4f, fill)
        c.drawLine(12f, 42f, 12f, 54f, line); c.drawLine(18f, 42f, 18f, 54f, line); c.drawLine(31f, 42f, 31f, 54f, line)
        rect.set(8f, 27f, 38f, 43f); c.drawRoundRect(rect, 8f, 8f, mask); c.drawRoundRect(rect, 8f, 8f, line)
        path.reset(); path.moveTo(15f, 28f); path.cubicTo(24f, 27f, 25f, 34f, 21f, 36f); path.cubicTo(17f, 38f, 13f, 34f, 15f, 28f); c.drawPath(path, fill)
        path.reset(); path.moveTo(37.5f, 16f); path.quadTo(34.5f, 13f, 36f, 8.5f); c.drawPath(path, line)
        path.reset(); path.moveTo(45f, 16f); path.quadTo(48f, 13f, 46.5f, 8.5f); c.drawPath(path, line)
        rect.set(34f, 14.5f, 48f, 30f); c.drawRoundRect(rect, 6f, 6f, mask); c.drawRoundRect(rect, 6f, 6f, line)
        rect.set(35.5f, 23f, 50f, 32f); c.drawRoundRect(rect, 4.5f, 4.5f, mask); c.drawRoundRect(rect, 4.5f, 4.5f, line)
        c.drawCircle(40f, 27.5f, 1f, fill); c.drawCircle(45.5f, 27.5f, 1f, fill)
        c.drawCircle(43f, 19f, 1.3f, fill)
    }

    // --- the horse, at the right, facing left -----------------------------------------------

    private fun horse(c: Canvas) {
        path.reset(); path.moveTo(131f, 29f); path.cubicTo(139f, 31f, 139f, 41f, 134f, 48f); path.cubicTo(133f, 41f, 131f, 37f, 128f, 35f); path.close(); c.drawPath(path, fill)
        c.drawLine(128f, 41f, 128f, 54f, line); c.drawLine(121f, 41f, 121f, 54f, line); c.drawLine(107f, 41f, 107f, 54f, line)
        rect.set(102f, 27f, 132f, 42f); c.drawRoundRect(rect, 8f, 8f, mask); c.drawRoundRect(rect, 8f, 8f, line)
        rect.set(112f, 25f, 122f, 32f); c.drawRoundRect(rect, 2.5f, 2.5f, mask); c.drawRoundRect(rect, 2.5f, 2.5f, line)
        path.reset(); path.moveTo(105f, 33f); path.lineTo(97f, 15f); path.lineTo(103.5f, 11f); path.lineTo(113f, 28f); path.close()
        c.drawPath(path, mask); c.drawPath(path, line)
        path.reset(); path.moveTo(103.5f, 11f); path.lineTo(113f, 28f); path.lineTo(109.5f, 29.5f); path.lineTo(101.5f, 14f); path.close(); c.drawPath(path, fill)
        path.reset(); path.moveTo(102.5f, 9.5f); path.lineTo(103.5f, 4.5f); path.lineTo(105.5f, 10f); c.drawPath(path, line)
        path.reset(); path.moveTo(104f, 10f); path.cubicTo(98f, 7f, 92f, 10f, 87.5f, 16.5f); path.quadTo(86.5f, 21f, 90.5f, 22f)
        path.quadTo(95f, 22f, 99f, 18f); path.close()
        c.drawPath(path, mask); c.drawPath(path, line)
        c.drawCircle(96f, 13f, 1.3f, fill); c.drawCircle(90f, 18f, 0.9f, fill)
    }

    /** A raised front leg from the shoulder ([sx], [sy]) to the hoof ([hx], [hy]), holding a sword at [deg]. */
    private fun arm(c: Canvas, sx: Float, sy: Float, hx: Float, hy: Float, deg: Float, drawn: Float) {
        c.drawLine(sx, sy, hx, hy, line)
        c.drawCircle(hx, hy, 1.9f, fill) // the hoof
        sword(c, hx, hy, deg, drawn)
    }

    /** A sword held at ([x], [y]), pointing [deg] degrees, [drawn] 0–1 of its blade out. */
    private fun sword(c: Canvas, x: Float, y: Float, deg: Float, drawn: Float) {
        val a = Math.toRadians(deg.toDouble())
        val dx = cos(a).toFloat(); val dy = sin(a).toFloat()
        // Pommel and grip, then the crossguard square to the blade.
        c.drawCircle(x - dx * 2.8f, y - dy * 2.8f, 1.3f, fill)
        c.drawLine(x - dx * 1.6f, y - dy * 1.6f, x + dx * 2.2f, y + dy * 2.2f, line)
        val gx = x + dx * 2.2f; val gy = y + dy * 2.2f
        c.drawLine(gx - dy * 3.4f, gy + dx * 3.4f, gx + dy * 3.4f, gy - dx * 3.4f, line)
        if (drawn <= 0f) return
        val len = 29f * drawn
        c.drawLine(gx, gy, gx + dx * len, gy + dy * len, line)
    }

    /** Where the blades cross: a burst of short rays that flies out and fades. */
    private fun spark(c: Canvas) {
        if (sparkAge > 1f) return
        val cx = 68f; val cy = 11f
        val reach = 3f + 7f * ease(sparkAge)
        val alpha = ((1f - sparkAge) * 255).toInt().coerceIn(0, 255)
        line.alpha = alpha
        for (i in 0 until 8) {
            val a = Math.toRadians((i * 45 + 22.5).toDouble())
            val inner = reach * 0.45f
            c.drawLine(cx + cos(a).toFloat() * inner, cy + sin(a).toFloat() * inner, cx + cos(a).toFloat() * reach, cy + sin(a).toFloat() * reach, line)
        }
        line.alpha = 255
    }
}
