package com.skylightlim.umtimetable

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * 马大牛逼's loader and pull-to-sync mark: the cow (牛, left) and the horse (马, right), small, round and
 * standing up, each holding a sword, after the cow-and-horse sketch for the app icon. One colour: [ink]
 * for the outlines, spots, hooves and mane, [paper] inside, and a faint [ink] over the paper for the
 * horse's coat and the cow's horns and muzzle. [progress] 0 → 1 swings the swords in from en garde
 * until the blades cross over the middle, where a spark flies: the pull has gone far enough. [loop]
 * keeps them clashing while something loads; [finish] lands one last clash.
 *
 * Drawn in a 120 × 60 box (y down), scaled to the view. Each piece (a body with its legs, a head with
 * its horns or ears, an arm, a blade) is its shapes' union outlined like a sticker: a stroke twice the
 * line width under the fill, so one line width shows outside and the pieces behind are hidden.
 */
@SuppressLint("ViewConstructor")
class DuelView(private val ui: Ui, private val ink: Int = ui.p.text, private val paper: Int = ui.p.ground) : View(ui.context) {
    private val outline = stroke(2 * LINE)
    private val line = stroke(LINE)
    private val fine = stroke(LINE * 0.85f)
    private val solid = fill(ink)
    private val blank = fill(paper)
    private val coat = fill(Palette.mix(ink, paper, 0.14f))
    private val shadow = fill(ink).apply { alpha = 31 }

    /** 0: en garde, blades up and apart; 1: swung in, the blades crossed over the middle. */
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
        setMeasuredDimension(ui.dp(112), ui.dp(56))

    /** Keeps them fighting: swing in, clash with a spark, back to en garde, again, about every 0.9 s. */
    fun loop() {
        stop()
        if (!Motion.enabled) { progress = 1f; return }
        looper = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                // Strike (0–0.4), hold the clash (0.4–0.55), back to en garde (0.55–1).
                progress = when {
                    t < 0.4f -> t / 0.4f
                    t < 0.55f -> 1f
                    else -> 1f - ease((t - 0.55f) / 0.45f)
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

    private fun stroke(width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = width
        color = ink
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }

    override fun onDraw(canvas: Canvas) {
        val k = width / 120f
        canvas.save()
        canvas.scale(k, k)
        // Slow at first, fastest into the cross: the blades only meet near the end, at 0.8.
        val e = progress * progress
        val step = 4f * (e - 1f) // each steps in 4
        val lean = 4f * e        // and leans in 4°, from the feet
        canvas.save()
        canvas.translate(step, 0f)
        canvas.drawOval(16f, 56.4f, 40f, 58.6f, shadow)
        canvas.rotate(lean, 28f, 57f)
        cow(canvas)
        swordArm(canvas, e, blank)
        canvas.restore()
        canvas.save()
        canvas.translate(-step, 0f)
        canvas.drawOval(80f, 56.4f, 104f, 58.6f, shadow)
        canvas.rotate(-lean, 92f, 57f)
        horse(canvas)
        canvas.scale(-1f, 1f, 60f, 0f) // its sword arm is the cow's, mirrored
        swordArm(canvas, e, coat)
        canvas.restore()
        spark(canvas)
        canvas.restore()
    }

    /** One piece: [shapes] outlined as one, filled with [paint]. */
    private fun piece(c: Canvas, paint: Paint, vararg shapes: Path) {
        for (s in shapes) c.drawPath(s, outline)
        for (s in shapes) c.drawPath(s, paint)
    }

    /** Solid hooves: the bottom of each of [legs]. */
    private fun hooves(c: Canvas, legs: Path) {
        c.save()
        c.clipPath(legs)
        c.drawRect(0f, 55f, 120f, 60f, solid)
        c.restore()
    }

    /** [shape] in [paint], only inside [within]. */
    private fun inside(c: Canvas, within: Path, shape: Path, paint: Paint) {
        c.save()
        c.clipPath(within)
        c.drawPath(shape, paint)
        c.restore()
    }

    // --- the cow, at the left, facing right ----------------------------------------------------

    private fun cow(c: Canvas) = with(Cow) {
        c.drawPath(tail, line)
        c.drawPath(tuft, solid)
        piece(c, blank, legs, body)
        hooves(c, legs)
        inside(c, body, spots, solid)
        c.drawPath(ears, solid)
        piece(c, blank, horns, head, muzzle)
        c.drawPath(horns, coat)
        inside(c, head, patch, solid)
        c.drawPath(muzzle, coat)
        c.drawPath(muzzleTop, fine)
        c.drawCircle(32.5f, 18.5f, 1.35f, solid)
        c.drawCircle(41f, 18f, 1.35f, solid)
        c.drawPath(nostrils, solid)
        c.drawPath(smile, fine)
    }

    // --- the horse, at the right, facing left --------------------------------------------------

    private fun horse(c: Canvas) = with(Horse) {
        c.drawPath(tail, solid)
        piece(c, coat, legs, body, neck)
        hooves(c, legs)
        piece(c, coat, ears, head)
        inside(c, head, muzzle, blank)
        c.drawPath(muzzleTop, fine)
        c.drawCircle(91f, 16.5f, 1.35f, solid)
        c.drawPath(nostrils, solid)
        c.drawPath(smile, fine)
        c.drawPath(mane, solid)
    }

    /**
     * The cow's front leg (the horse's is its mirror image) from the shoulder, its hoof round the grip of
     * a sword: [e] 0 low with the blade held up, 1 raised with the blade swung in to the cross.
     */
    private fun swordArm(c: Canvas, e: Float, fill: Paint) {
        val arm = 20f - 40f * e
        val blade = -74f + 22f * e
        c.save()
        c.translate(35f, 41f)
        c.rotate(arm)
        piece(c, fill, Sword.arm)
        c.translate(8f, 0f)
        c.rotate(blade - arm)
        piece(c, blank, Sword.blade)
        c.drawRoundRect(-4.6f, -1.1f, 3.1f, 1.1f, 1.1f, 1.1f, solid) // the grip
        c.drawCircle(-4.2f, 0f, 1.5f, solid)                          // the pommel
        c.drawRoundRect(2.4f, -4.3f, 4.6f, 4.3f, 1.1f, 1.1f, solid)   // the crossguard
        c.drawCircle(0f, 0f, 2.6f + LINE, solid)                      // the hoof
        c.restore()
    }

    /** Where the blades cross: three short rays fanning up between the tips, flying out as they fade. */
    private fun spark(c: Canvas) {
        if (sparkAge > 1f) return
        val grow = ease(sparkAge)
        val from = 8.5f + 2f * grow
        val to = 10.5f + 4f * grow
        line.alpha = ((1f - sparkAge) * 255).toInt().coerceIn(0, 255)
        for (deg in intArrayOf(-118, -90, -62)) {
            val a = Math.toRadians(deg.toDouble())
            val dx = cos(a).toFloat(); val dy = sin(a).toFloat()
            c.drawLine(60f + dx * from, CROSS_Y + dy * from, 60f + dx * to, CROSS_Y + dy * to, line)
        }
        line.alpha = 255
    }

    private object Cow {
        val tail = svg("M18,46 C11,48 7.5,45 8.5,39")
        val tuft = svg("M8.5,40.5 C6,39 6,35.5 8.6,33.5 C11,35.5 11,39 8.5,40.5 Z")
        val legs = Path().apply { addRoundRect(19.5f, 47f, 25f, 57.4f, 2.7f, 2.7f, CW); addRoundRect(30.5f, 47f, 36f, 57.4f, 2.7f, 2.7f, CW) }
        val body = Path().apply { addOval(17f, 33f, 39.5f, 55f, CW) }
        val spots = Path().apply { addOval(13f, 37f, 23f, 47f, CW); addOval(29f, 46.5f, 36f, 52f, CW) }
        val ears = svg("M20,17.5 C14,14.5 8.5,16.5 7.5,20.5 C11.5,24 17,23.5 21,21.5 Z " +
            "M41.5,13.5 C45,9.5 50.5,9.5 53,11.5 C51,16.5 46.5,18 42,17.5 Z")
        val horns = svg("M22,12.5 C20.6,10.4 20.4,8.2 21.4,6.4 C23.4,7.4 25,9.4 25.6,11.6 Z " +
            "M36.6,11.2 C37.4,9 39,7.3 41,6.5 C41.6,8.6 41,10.7 39.8,12.5 Z")
        val head = Path().apply { addOval(18f, 9.5f, 45f, 33f, CW) }
        val patch = svg("M17,8 C25,8 28.5,13 27.5,19 C26.5,25 22,28.5 16,27 Z")
        val muzzle = Path().apply { addOval(30f, 22f, 46.5f, 32.5f, CW) }
        val muzzleTop = svg("M31,24.5 C35,21.8 41.5,21.8 45.8,24.5")
        val nostrils = Path().apply { addOval(35.3f, 25.6f, 37.3f, 27.4f, CW); addOval(40.7f, 25.6f, 42.7f, 27.4f, CW) }
        val smile = svg("M36.5,29.6 Q39,31.3 41.5,29.6")
    }

    private object Horse {
        val tail = svg("M100,40 C108,38.5 113,44.5 112.5,54 C109.5,50 106,48 100.5,48.5 Z")
        val legs = Path().apply { addRoundRect(95f, 47f, 100.5f, 57.4f, 2.7f, 2.7f, CW); addRoundRect(84f, 47f, 89.5f, 57.4f, 2.7f, 2.7f, CW) }
        val body = Path().apply { addOval(80.5f, 33f, 103f, 55f, CW) }
        val neck = svg("M89,27 C88.5,32 87.5,36 86.5,40 L100,40 C101,34 101.5,28 101,23 Z")
        val ears = svg("M88,12 C87.6,9.4 88.4,7 90,5.4 C92,7.2 93.2,9.2 93.6,11.4 Z " +
            "M96.5,10 C97.6,7.8 99.2,6.2 101.4,5.4 C101.8,7.8 101.4,10.2 100,12.4 Z")
        val head = svg("M97,8.5 C90,8.5 84,13 79.5,19 C75.5,24.5 75,31 80.5,32.6 C85.5,34 90.5,31 94,29 " +
            "C100,27 104,22 103.5,16 C103,11 100.5,8.5 97,8.5 Z")
        val muzzle = svg("M80.5,21 C84,22.5 87.5,26 87.5,29.5 C85,31.6 82.5,32.8 80.5,32.6 C75,31 75.6,24.5 80.5,21 Z")
        val muzzleTop = svg("M80.5,21 C84,22.5 87.5,26 87.5,29.5")
        val nostrils = Path().apply { addOval(77.6f, 25f, 79.4f, 26.9f, CW); addOval(81.4f, 26.3f, 83.2f, 28.2f, CW) }
        val smile = svg("M80,30.3 Q82.4,31.3 84.6,29.6")
        /** The forelock over the brow, and down the back of the neck. */
        val mane = svg("M101.5,11 C98,7.2 92,7.4 87.5,11.8 C90.5,11.4 92.6,12.6 93.8,14.8 C95,12.8 97.4,12.4 99.4,13.6 " +
            "C100.6,19 100.2,26 98.4,31.5 C99.8,34.5 101.8,37 104.2,38.5 C107,32 107,18 101.5,11 Z")
    }

    /** In the arm's own frame: the shoulder at 0, the hoof 8 along x; then the sword's, the hoof at 0. */
    private object Sword {
        val arm = Path().apply { addRoundRect(-2.6f, -2.6f, 10.6f, 2.6f, 2.6f, 2.6f, CW) }
        val blade = svg("M4.6,-1.5 L26,-1.5 L29.5,0 L26,1.5 L4.6,1.5 Z")
    }

    private companion object {
        /** The outline's width, in the box's units. */
        const val LINE = 1.6f
        /** Where the blades cross, over the middle (x 60), when [progress] is 1. */
        const val CROSS_Y = 21.3f
        val CW = Path.Direction.CW

        /** A path from SVG path data: absolute M, L, Q, C and Z only, each segment with its letter. */
        fun svg(d: String) = Path().apply {
            val t = Regex("[MLQCZ]|-?[0-9.]+").findAll(d).map { it.value }.toList()
            var i = 0
            fun n() = t[i++].toFloat()
            while (i < t.size) when (t[i++]) {
                "M" -> moveTo(n(), n())
                "L" -> lineTo(n(), n())
                "Q" -> quadTo(n(), n(), n(), n())
                "C" -> cubicTo(n(), n(), n(), n(), n(), n())
                "Z" -> close()
                else -> error("Not a path command in \"$d\"")
            }
        }
    }
}
