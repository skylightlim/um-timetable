package com.skylightlim.umtimetable

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 马大牛逼's loader and pull-to-sync mark: the cow (牛, left) and the horse (马, right) duelling with
 * lightsabers, drawn in a round, chunky cartoon style (after Duolingo's owl): big eyes under angry brows,
 * six-packs and a bicep on the saber arm, the cow shouting with its tongue out, the horse grinning with
 * buck teeth. One colour: [ink] for the outlines, spots, hooves, manes and the blades' glow, [paper]
 * inside, and a faint [ink] over the paper for the horse's coat and the cow's horns and muzzle. A blade
 * has a white core outlined on a light page and a bright one on a dark page, in its glow. [progress]
 * 0 → 1 swings the sabers in from en garde until the blades cross over the middle, where a spark flies:
 * the pull has gone far enough. [loop] keeps them clashing while something loads; [finish] lands one
 * last clash.
 *
 * Drawn in a 120 × 60 box (y down), scaled to the view. Each piece (a body with its legs, a head with
 * its horns or ears, an arm) is its shapes' union outlined like a sticker: a stroke twice the line
 * width under the fill, so one line width shows outside and the pieces behind are hidden.
 */
@SuppressLint("ViewConstructor")
class DuelView(
    private val ui: Ui,
    private val ink: Int = ui.p.text,
    private val paper: Int = ui.p.ground,
    /** As a stage ([DuelScene]): as large as it's laid out, the pair drawn to fit its height with room above for a jump, and the sides open for running in. */
    private val stage: Boolean = false,
) : View(ui.context) {
    private val outline = stroke(2 * LINE)
    private val line = stroke(LINE)
    private val fine = stroke(LINE * 0.85f)
    private val muscle = stroke(LINE * 0.75f)
    private val brow = stroke(2.3f)
    private val toothEdge = stroke(LINE * 1.6f)
    /** On a light page a blade is a white core outlined; on a dark one the ink itself is the bright core. */
    private val lightPage = Color.luminance(paper) > Color.luminance(ink)
    private val glowWide = stroke(2 * Saber.HALF + 6f).apply { alpha = 26 }
    private val glow = stroke(2 * Saber.HALF + 3f).apply { alpha = 56 }
    private val coreEdge = stroke(2 * Saber.HALF + 2 * LINE)
    private val core = stroke(2 * Saber.HALF).apply { if (lightPage) color = paper }
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

    /**
     * One fighter's pose, in the box's units: [x] across from its place (+ to the right), [y] down (a jump
     * is negative), [lean] in degrees toward the other from its feet, [swing] its saber arm (0 en garde, 1
     * swung in to the cross), [squash] its height (1 standing, below it crouching or landing).
     */
    data class Pose(val x: Float = 0f, val y: Float = 0f, val lean: Float = 0f, val swing: Float = 0f, val squash: Float = 1f)

    /** The cow's and the horse's poses, set by a scene; null draws [progress]'s. */
    internal var poses: Pair<Pose, Pose>? = null
        set(value) {
            field = value
            invalidate()
        }

    /** The spark's life, 0 (just struck) to 1 (gone); above 1 there's none. Settable for still previews. */
    internal var sparkAge = 2f
    /** Where the spark flies, in the box's units: over the middle unless a scene's clash is elsewhere. */
    private var sparkX = 60f
    private var sparkY = CROSS_Y
    private var looper: ValueAnimator? = null
    private var sparker: ValueAnimator? = null

    init {
        contentDescription = "A cow and a horse fighting with lightsabers"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        if (stage) setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        else setMeasuredDimension(ui.dp(112), ui.dp(56))

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

    /** For still previews: the spark at ([x], [y]), [age] into its life. */
    internal fun sparkStill(x: Float, y: Float, age: Float) {
        sparkX = x
        sparkY = y
        sparkAge = age
        invalidate()
    }

    /** A scene's clash at ([x], [y]) in the box's units: a spark flies there. */
    internal fun strike(x: Float, y: Float) {
        sparkX = x
        sparkY = y
        spark()
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
        canvas.save()
        if (stage) {
            // The 120 × 60 box plus 18 above for a jump and 6 to spare, centred; wider views leave the sides open.
            val k = minOf(height / 84f, width / 150f)
            if (k * 60f < ui.dp(40)) return canvas.restore() // too short to show them
            canvas.translate((width - 120f * k) / 2f, (height - 84f * k) / 2f + 21f * k)
            canvas.scale(k, k)
        } else {
            canvas.scale(width / 120f, width / 120f)
        }
        val (cow, horse) = poses ?: run {
            // Slow at first, fastest into the cross: the blades only meet near the end, at 0.8.
            val e = progress * progress
            val step = 4f * (e - 1f) // each steps in 4
            val lean = 4f * e        // and leans in 4°, from the feet
            Pose(x = step, lean = lean, swing = e) to Pose(x = -step, lean = lean, swing = e)
        }
        fighter(canvas, cow, 28f, 1f) {
            cow(canvas)
            saberArm(canvas, cow.swing, blank)
        }
        fighter(canvas, horse, 92f, -1f) {
            horse(canvas)
            canvas.scale(-1f, 1f, 60f, 0f) // its saber arm is the cow's, mirrored
            saberArm(canvas, horse.swing, coat)
        }
        spark(canvas)
        canvas.restore()
    }

    /**
     * One of them at [pose], standing at [feet] across, facing [facing] (+1 right): its shadow stays on
     * the ground, shrinking as it jumps; it leans and squashes from its feet.
     */
    private inline fun fighter(c: Canvas, pose: Pose, feet: Float, facing: Float, draw: () -> Unit) {
        c.save()
        c.translate(pose.x, 0f)
        val air = (1f + pose.y / 40f).coerceIn(0.4f, 1f)
        c.drawOval(feet - 12f * air, 56.4f, feet + 12f * air, 58.6f, shadow)
        c.translate(0f, pose.y)
        c.rotate(facing * pose.lean, feet, 57f)
        if (pose.squash != 1f) c.scale(1f + (1f - pose.squash) * 0.6f, pose.squash, feet, 57f)
        draw()
        c.restore()
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
        c.drawRect(0f, 54.6f, 120f, 60f, solid)
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
        piece(c, blank, legs, body, muzzle)
        hooves(c, legs)
        inside(c, body, spots, solid)
        sixPack(c, pecs, pack, packLines)
        c.drawPath(ears, solid)
        piece(c, coat, horns)
        c.drawPath(muzzle, coat)
        eyes(c, eyes, pupils, brows)
        c.drawPath(nostrils, solid)
        c.drawPath(mouth, solid)
        inside(c, mouth, tongue, coat)
    }

    // --- the horse, at the right, facing left --------------------------------------------------

    private fun horse(c: Canvas) = with(Horse) {
        c.drawPath(tail, solid)
        piece(c, coat, legs, body, neck)
        hooves(c, legs)
        sixPack(c, pecs, pack, packLines)
        c.drawPath(mane, solid)
        piece(c, coat, ears, crown, face)
        inside(c, head, muzzle, blank)
        c.drawPath(crest, solid)
        eyes(c, eyes, pupils, brows)
        c.drawPath(nostrils, solid)
        c.drawPath(teeth, toothEdge)
        c.drawPath(teeth, blank)
        c.drawPath(mouth, fine)
    }

    /** The chest's two arcs, and the six-pack under them: a rounded block in thirds and halves. */
    private fun sixPack(c: Canvas, pecs: Path, pack: Path, lines: Path) {
        c.drawPath(pecs, muscle)
        piece(c, blank, pack)
        c.drawPath(lines, muscle)
    }

    /** Big round eyes (white, outlined), pupils turned to the other one with a glint each, and angry brows. */
    private fun eyes(c: Canvas, eyes: Path, pupils: FloatArray, brows: Path) {
        piece(c, blank, eyes)
        for (i in pupils.indices step 2) {
            c.drawCircle(pupils[i], pupils[i + 1], 2.4f, solid)
            c.drawCircle(pupils[i] + 0.8f, pupils[i + 1] - 1f, 0.75f, blank)
        }
        c.drawPath(brows, brow)
    }

    /**
     * The cow's saber arm (the horse's is its mirror image) from the shoulder, a bicep on it and the hoof
     * round the hilt: [e] 0 low with the blade held up, 1 raised with the blade swung in to the cross.
     */
    private fun saberArm(c: Canvas, e: Float, fill: Paint) {
        val arm = 20f - 40f * e
        val blade = -74f + 22f * e
        c.save()
        c.translate(35f, 41f)
        c.rotate(arm)
        piece(c, fill, Saber.arm, Saber.bicep)
        c.translate(8f, 0f)
        c.rotate(blade - arm)
        // The blade's glow, then its core.
        c.drawLine(Saber.FROM, 0f, Saber.TO, 0f, glowWide)
        c.drawLine(Saber.FROM, 0f, Saber.TO, 0f, glow)
        if (lightPage) c.drawLine(Saber.FROM, 0f, Saber.TO, 0f, coreEdge)
        c.drawLine(Saber.FROM, 0f, Saber.TO, 0f, core)
        c.drawPath(Saber.hilt, solid)
        c.drawPath(Saber.bands, blank)
        c.drawPath(Saber.emitter, solid)
        c.drawCircle(0f, 0f, 3.4f + LINE, solid) // the hoof
        c.restore()
    }

    /** Where the blades cross: a flash, and five short rays flying out between the tips as they fade. */
    private fun spark(c: Canvas) {
        if (sparkAge > 1f) return
        val grow = ease(sparkAge)
        if (sparkAge < 0.35f) {
            shadow.alpha = ((1f - sparkAge / 0.35f) * 110).toInt()
            c.drawCircle(sparkX, sparkY, 2.5f + 5f * grow, shadow)
            shadow.alpha = 31
        }
        val from = 8f + 2.5f * grow
        val to = 10.5f + 4.5f * grow
        line.alpha = ((1f - sparkAge) * 255).toInt().coerceIn(0, 255)
        for (deg in intArrayOf(-150, -120, -90, -60, -30)) {
            val a = Math.toRadians(deg.toDouble())
            val dx = cos(a).toFloat(); val dy = sin(a).toFloat()
            c.drawLine(sparkX + dx * from, sparkY + dy * from, sparkX + dx * to, sparkY + dy * to, line)
        }
        line.alpha = 255
    }

    /** The cow, at the left, facing right: a round body with a six-pack, big eyes, shouting. */
    private object Cow {
        val tail = svg("M14,46 C8,48 4.6,44 5.8,38.6")
        val tuft = svg("M5.8,40.2 C3.4,38.8 3.4,35.2 6,33.2 C8.4,35.2 8.4,38.8 5.8,40.2 Z")
        val legs = join(rrect(17.5f, 47f, 24f, 57.4f, 3f), rrect(30f, 47f, 36.5f, 57.4f, 3f))
        val body = join(oval(11f, 14.5f, 43f, 55f))
        val muzzle = oval(29.6f, 25.6f, 44.2f, 34.6f)
        val horns = join(svg("M17,18 C13.8,15.2 13.2,11.2 14.8,8.4 C17.4,10.2 19.4,13.4 20.4,16.4 Z"), svg("M33.4,15.6 C34.2,12.2 36.4,9.6 39.2,8.6 C39.8,11.4 38.8,14.4 36.8,16.8 Z"))
        val ears = svg("M13.6,22.4 C8.6,20.2 4.4,21.6 3.6,25 C7.4,27.8 11.8,27.2 14.6,25.4 Z M40.6,19.2 C44,16 48.4,15.6 50.6,17.2 C49.2,21 45.4,22.6 41.4,22.4 Z")
        val spots = join(oval(7.5f, 39.5f, 16.5f, 49f), svg("M9.5,22 C11.5,16.6 17.6,14.2 21.6,16.4 C20.8,21.2 16,24.8 10,26.4 Z"))
        val pack = rrect(22.6f, 39f, 33.4f, 51.4f, 3.4f)
        val packLines = svg("M28,39.6 L28,50.8 M23,43.2 Q28,44.4 33,43.2 M23,47.2 Q28,48.4 33,47.2")
        val pecs = svg("M19.4,35.6 C21.4,38.2 25.2,38.8 27.6,36.8 M28.4,36.8 C31,38.8 34.6,38.2 36.6,35.6")
        val eyes = join(oval(23.8f, 16.4f, 31.6f, 25.8f), oval(31.2f, 16.4f, 39f, 25.8f))
        val pupils = floatArrayOf(28.8f, 21.8f, 36.2f, 21.8f)
        val brows = svg("M23.6,14.6 L30.6,17.6 M32.4,17.6 L39.4,14.4")
        val nostrils = join(oval(34.4f, 27.2f, 36.6f, 29.2f), oval(39.2f, 27.2f, 41.4f, 29.2f))
        val mouth = svg("M34,30.6 Q38.4,31.8 42.6,30.2 C42.4,33.4 40.4,35.2 38.2,35 C35.8,34.8 34.2,33 34,30.6 Z")
        val tongue = oval(35.6f, 32.6f, 40.8f, 36.2f)
    }

    /** The horse, at the right, facing left: a long face with its chin tucked in, a crest, buck teeth. */
    private object Horse {
        val tail = svg("M105.6,39.6 C113,38 117.4,44.2 116.8,53.8 C113.6,49.8 110,47.8 105.4,48.2 Z")
        val legs = join(rrect(83.5f, 47f, 90f, 57.4f, 3f), rrect(96f, 47f, 102.5f, 57.4f, 3f))
        val body = join(oval(77f, 25f, 107f, 55f))
        val neck = svg("M89.6,22 L87.4,38 L103.6,38 L103.4,19 Z")
        val mane = svg("M101.6,8.6 C104.6,11.6 106,15.2 106.2,19.2 C106.8,25.4 105.8,31.4 103.4,36.8 C101.2,35.4 99.8,33.6 99,31.4 C100.6,26.2 100.6,19.4 98.4,14.2 Z")
        val pack = rrect(86.6f, 39.6f, 97.4f, 51.8f, 3.4f)
        val packLines = svg("M92,40.2 L92,51.2 M87,43.8 Q92,45 97,43.8 M87,47.8 Q92,49 97,47.8")
        val pecs = svg("M83.4,37 C85.4,39.4 89.2,40 91.6,38 M92.4,38 C95,40 98.6,39.4 100.6,37")
        val ears = svg("M87.8,8.6 C86.6,6 86.8,3.6 88,2 C90.6,2.9 92.6,5 93.4,7.2 Z M97.2,6.4 C98,3.8 99.8,2.2 102,1.8 C102.4,4.2 101.6,6.6 100.2,8.4 Z")
        val crown = oval(83.3f, 4.2f, 103.7f, 24.6f)
        val face = svg("M92.63,11.63 C96.38,14.55 95.99,21.3 91.78,26.69 C87.56,32.09 81.11,34.1 77.37,31.17 C73.62,28.25 74.01,21.5 78.22,16.11 C82.44,10.71 88.89,8.7 92.63,11.63 Z")
        /** The crown and the face together, for what's drawn inside the head. */
        val head = join(crown, face)
        val muzzle = svg("M84.76,20.57 C87.63,22.81 87.92,27.24 85.4,30.46 C82.88,33.68 78.52,34.48 75.64,32.23 C72.77,29.99 72.48,25.56 75,22.34 C77.52,19.12 81.88,18.32 84.76,20.57 Z")
        val crest = svg("M91.4,6.4 C91.4,3.8 93,1.8 95.6,1 C95,2.2 95,3.2 95.6,4.2 C96.8,2.4 99,1.8 101.2,2.2 C99.8,3 99,4.2 98.8,5.6 C100.6,5.2 102.6,5.8 104,7.4 C101.4,7.4 99.6,8.2 98.6,9.6 Z")
        val eyes = join(oval(82f, 8.8f, 89.6f, 18f), oval(89.2f, 8.8f, 96.8f, 18f))
        val pupils = floatArrayOf(84.8f, 14f, 92f, 14f)
        val brows = svg("M83.4,7.6 L89,9.8 M90.6,9.8 L96.6,7.4")
        val nostrils = join(svg("M77.5,23.85 C77.8,24.38 77.47,25.14 76.75,25.55 C76.03,25.97 75.2,25.88 74.9,25.35 C74.6,24.82 74.93,24.06 75.65,23.65 C76.37,23.23 77.2,23.32 77.5,23.85 Z"), svg("M81.3,25.25 C81.6,25.78 81.27,26.54 80.55,26.95 C79.83,27.37 79,27.28 78.7,26.75 C78.4,26.22 78.73,25.46 79.45,25.05 C80.17,24.63 81,24.72 81.3,25.25 Z"))
        val mouth = svg("M74.6,28.2 Q79.4,31.8 85,29.2")
        val teeth = join(rrect(77f, 29.4f, 79.6f, 33.2f, 0.9f), rrect(79.8f, 29.7f, 82.4f, 33.3f, 0.9f))
    }

    /** In the arm's own frame: the shoulder at 0, the hoof 8 along x; then the saber's, the hoof round its hilt at 0. */
    private object Saber {
        val arm = rrect(-3.4f, -3.4f, 11f, 3.4f, 3.4f)
        val bicep = oval(2.4f, -5.2f, 9.4f, 1.2f)
        val hilt = rrect(-5.6f, -1.7f, 4.4f, 1.7f, 1f)
        val bands = join(rrect(-3.6f, -1.7f, -2.6f, 1.7f, 0.3f), rrect(-1.6f, -1.7f, -0.6f, 1.7f, 0.3f))
        val emitter = rrect(3.6f, -2.3f, 5.8f, 2.3f, 0.8f)
        /** The blade, from the emitter to its tip, and half its width. */
        const val FROM = 5.8f
        const val TO = 31f
        const val HALF = 1.6f
    }

    internal companion object {
        /** The outline's width, in the box's units. */
        private const val LINE = 1.6f
        /** Where the blades cross, over the middle (x 60), when [progress] is 1. */
        const val CROSS_Y = 21.3f
        private val CW = Path.Direction.CW

        /** A path from SVG path data: absolute M, L, Q, C and Z only, each segment with its letter. */
        private fun oval(l: Float, t: Float, r: Float, b: Float) = Path().apply { addOval(l, t, r, b, CW) }
        private fun rrect(l: Float, t: Float, r: Float, b: Float, rad: Float) = Path().apply { addRoundRect(l, t, r, b, rad, rad, CW) }
        /** Shapes as one path: ones that don't overlap, or wind the same way (so their union fills). */
        private fun join(vararg shapes: Path) = Path().apply { shapes.forEach { addPath(it) } }

        private fun svg(d: String) = Path().apply {
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

/**
 * The sign-in poster's fight, five seconds on a [DuelView] stage: the cow and the horse run in from the
 * sides, square up, clash, clash twice more in a flurry, leap and clash in the air, lock blades while the
 * cow pushes the horse back, spring apart and bow. Then they stand en garde, breathing, until the scene
 * is tapped and plays again. Each move is a track of keyframes over the time (ms) with its own easing;
 * the sparks fly where the blades meet.
 */
class DuelScene(private val view: DuelView) {
    private var playing: ValueAnimator? = null
    private var idling: ValueAnimator? = null

    init {
        view.poses = rest(0f)
    }

    /** Plays the fight from the start (after [delay] ms), then idles. With "Remove animations": stands en garde. */
    fun play(delay: Long = 0) {
        stop()
        if (!Motion.enabled) { view.poses = rest(0f); return }
        var sparked = 0
        playing = ValueAnimator.ofFloat(0f, LENGTH).apply {
            duration = LENGTH.toLong()
            startDelay = delay
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                view.poses = at(t)
                // Every clash passed since the last frame flies its spark (one per clash, however long a frame is).
                while (sparked < CLASHES.size && CLASHES[sparked] <= t) {
                    val c = CLASHES[sparked++]
                    view.strike(60f + SHIFT.at(c), DuelView.CROSS_Y + Y.at(c))
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (playing === animation) idle()
                }
            })
            start()
        }
    }

    val busy get() = playing?.isRunning == true

    fun stop() {
        playing?.let { playing = null; it.cancel() }
        idling?.cancel()
        idling = null
    }

    /** En garde, breathing: each rises and settles a little, out of step with the other. */
    private fun idle() {
        idling = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { view.poses = rest(it.animatedValue as Float) }
            start()
        }
    }

    private fun rest(phase: Float): Pair<DuelView.Pose, DuelView.Pose> {
        val a = (2 * Math.PI * phase).toFloat()
        return DuelView.Pose(x = -REST_GAP, squash = 1f + 0.018f * sin(a)) to DuelView.Pose(x = REST_GAP, squash = 1f + 0.018f * sin(a + 2f))
    }

    /** For still previews: the latest clash at or before [t] ms, as where its spark flies and how old it is (0–1); null when none is showing. */
    internal fun sparkAt(t: Float): Triple<Float, Float, Float>? {
        val c = CLASHES.lastOrNull { it <= t } ?: return null
        val age = (t - c) / 420f
        return if (age > 1f) null else Triple(60f + SHIFT.at(c), DuelView.CROSS_Y + Y.at(c), age)
    }

    /** Both poses at [t] ms. The pair keeps [GAP] apart from where their blades cross, and moves [SHIFT] together. */
    internal fun at(t: Float): Pair<DuelView.Pose, DuelView.Pose> {
        val gap = GAP.at(t)
        // Blades locked (3150–3700 ms): the push judders.
        val shift = SHIFT.at(t) + if (t in 3150f..3700f) 0.7f * sin(t / 22f) else 0f
        // Running in: five little hops.
        val run = if (t < 650f) abs(sin(t / 650f * 5f * Math.PI.toFloat())) else 0f
        val y = Y.at(t) - 4f * run
        val squash = SQUASH.at(t)
        val swing = SWING.at(t)
        return DuelView.Pose(shift - gap, y, COW_LEAN.at(t), swing, squash) to DuelView.Pose(shift + gap, y, HORSE_LEAN.at(t), swing, squash)
    }

    /** A value over time: straight between keyframes, each stretch shaped by the easing of the keyframe it ends at. */
    private class Track(vararg val keys: Triple<Float, Float, (Float) -> Float>) {
        fun at(t: Float): Float {
            if (t <= keys.first().first) return keys.first().second
            for (i in 1 until keys.size) {
                val (t1, v1, ease) = keys[i]
                if (t <= t1) {
                    val (t0, v0) = keys[i - 1]
                    return v0 + (v1 - v0) * ease((t - t0) / (t1 - t0))
                }
            }
            return keys.last().second
        }
    }

    private companion object {
        const val LENGTH = 5000f
        /** How far apart they stand en garde, each from where the blades would cross. */
        const val REST_GAP = 11f

        val linear: (Float) -> Float = { it }
        /** Easing in (speeding up: into a strike), out (slowing: a landing, a stop) and both. */
        val easeIn: (Float) -> Float = { it * it * it }
        val easeOut: (Float) -> Float = { 1 - (1 - it) * (1 - it) * (1 - it) }
        val smooth: (Float) -> Float = { it * it * (3 - 2 * it) }
        fun k(t: Int, v: Float, ease: (Float) -> Float = smooth) = Triple(t.toFloat(), v, ease)

        /** Each one's distance from the clash: in from off stage, the strikes (0), recoils and the bow's stance. */
        val GAP = Track(
            k(0, 80f), k(650, 14f, easeOut), k(1000, 17f), k(1200, 0f, easeIn), k(1400, 0f),
            k(1650, 10f, easeOut), k(1790, 1f, easeIn), k(1900, 6f, easeOut), k(2080, 1f, easeIn), k(2350, 12f, easeOut),
            k(2500, 12f), k(2720, 0f, easeOut), k(2950, 9f), k(3150, 0f, easeIn), k(3700, 0f), k(4050, REST_GAP, easeOut), k(5000, REST_GAP),
        )
        /** The pair moving together: the cow pushes the horse back while the blades are locked, then both return. */
        val SHIFT = Track(k(0, 0f), k(3150, 0f), k(3450, 9f), k(3700, 5f), k(4050, 0f), k(5000, 0f))
        /** Up (negative) and down: a hop back after the first clash, the leap, a hop apart. */
        val Y = Track(
            k(0, 0f), k(1400, 0f), k(1520, -3f, easeOut), k(1650, 0f, easeIn), k(2500, 0f), k(2720, -18f, easeOut), k(2950, 0f, easeIn),
            k(3700, 0f), k(3880, -6f, easeOut), k(4050, 0f, easeIn), k(5000, 0f),
        )
        /** Crouching before a dash or a leap, stretching off the ground, squashing on landing. */
        val SQUASH = Track(
            k(0, 1f), k(650, 1f), k(1000, 0.9f), k(1150, 1.04f, easeOut), k(1250, 1f), k(2350, 1f), k(2500, 0.84f), k(2560, 1.08f, easeOut),
            k(2720, 1f), k(2950, 1f), k(3010, 0.84f, easeOut), k(3100, 1f), k(4050, 1f), k(4110, 0.9f, easeOut), k(4200, 1f), k(5000, 1f),
        )
        /** Saber arms: en garde (0) to the cross (1), fastest into each strike. */
        val SWING = Track(
            k(0, 0f), k(1000, 0.1f), k(1200, 1f, easeIn), k(1400, 1f), k(1650, 0.3f, easeOut), k(1790, 1f, easeIn), k(1900, 0.45f, easeOut),
            k(2080, 1f, easeIn), k(2350, 0f), k(2500, 0f), k(2720, 1f, easeIn), k(2950, 0.4f), k(3150, 1f, easeIn), k(3700, 1f),
            k(4050, 0f), k(5000, 0f),
        )
        /** Leaning in running, rearing back before the dash, into each strike; then the bow (4550–4800). */
        private val LEAN = listOf(
            k(0, 10f), k(650, 10f), k(1000, -6f), k(1200, 6f, easeIn), k(1400, 6f), k(1650, 0f), k(1790, 5f, easeIn), k(1900, 1f),
            k(2080, 5f, easeIn), k(2350, 0f), k(2500, -4f), k(2720, 4f, easeIn), k(2950, 0f),
        )
        private val AFTER = listOf(k(3700, 4f), k(4050, -4f, easeOut), k(4300, 0f), k(4550, 14f), k(4800, 14f), k(5000, 0f))
        /** The cow leans into the push; the horse, pushed, leans back. */
        val COW_LEAN = Track(*(LEAN + listOf(k(3150, 8f, easeIn)) + AFTER).toTypedArray())
        val HORSE_LEAN = Track(*(LEAN + listOf(k(3150, -2f, easeIn)) + AFTER).toTypedArray())
        /** When the blades meet: the first strike, the flurry's two, the leap, and three flickers in the lock. */
        val CLASHES = listOf(1200f, 1790f, 2080f, 2720f, 3150f, 3420f, 3640f)
    }
}
