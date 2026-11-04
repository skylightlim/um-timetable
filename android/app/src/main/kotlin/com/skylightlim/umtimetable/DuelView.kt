package com.skylightlim.umtimetable

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 马大牛逼's loader and pull-to-sync mark: the cow (牛, left) and the horse (马, right) duelling with
 * lightsabers, in thick round line art (after an icon of a cow on four legs, stood up): boxy bodies,
 * front-facing heads with small muzzles, dot eyes under cross brows. The cow has horns, ears out to the
 * side, patches and an udder; the horse pointed ears, a forelock, a mane and a flowing tail. One colour:
 * [ink] for the outlines, patches, hooves, mane and the blades' glow, [paper] inside. A blade has a white
 * core outlined on a light page and a bright one on a dark page, in its glow.
 *
 * Their sabers are their own: the cow reaches round, yanks its tail off (a pop where it was) and the tail
 * is the hilt; the horse reaches down and draws its member out as the hilt. [progress] 0 → 0.5 draws them
 * (reach, pull, the blade lights), 0.5 → 1 swings them in from en garde until the blades cross over the
 * middle, where a spark flies: the pull has gone far enough. The cow chops; the horse holds its blade up,
 * steeper, a guard. [loop] keeps them clashing while something loads, sabers drawn; [finish] lands one
 * last clash.
 *
 * Drawn in a 120 × 60 box (y down), scaled to the view. Each piece (a body with its legs, a head with its
 * ears) is its shapes outlined like a sticker: a stroke twice the line width under the fill, so one line
 * width shows outside and what's behind is hidden.
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
    private val fine = stroke(1.2f)
    private val brow = stroke(1.6f)
    private val tailLine = stroke(2.2f)
    private val horn = stroke(2.4f)
    private val hilt = stroke(2.6f)
    private val maneTail = stroke(3f)
    private val maneTail2 = stroke(2f)
    /** The horse's member: an outlined tube, [memberEdge] round a [memberCore] of the paper. */
    private val memberEdge = stroke(3.6f)
    private val memberCore = stroke(1.4f).apply { color = paper }
    private val armEdge = stroke(ARM_W + 2 * LINE)
    private val armFill = stroke(ARM_W).apply { color = paper }
    /** On a light page a blade is a white core outlined; on a dark one the ink itself is the bright core. */
    private val lightPage = Color.luminance(paper) > Color.luminance(ink)
    private val glowWide = stroke(2 * HALF + 6f).apply { alpha = 26 }
    private val glow = stroke(2 * HALF + 3f).apply { alpha = 56 }
    private val coreEdge = stroke(2 * HALF + 2 * LINE)
    private val core = stroke(2 * HALF).apply { if (lightPage) color = paper }
    private val solid = fill(ink)
    private val blank = fill(paper)
    private val faint = fill(ink).apply { alpha = 46 }
    /** A rotor turning, as a blur: a disc of the ink, fainter the slower. */
    private val blur = fill(ink)
    private val beamWide = stroke(5f).apply { alpha = 26 }
    private val beamGlow = stroke(3f).apply { alpha = 56 }
    private val beamEdge = stroke(1.2f + 2.4f)
    private val beamCore = stroke(1.2f).apply { if (lightPage) color = paper }
    private val shadow = fill(ink).apply { alpha = 31 }

    /** 0 → 0.5: drawing their sabers; 0.5: en garde, blades up and apart; 1: swung in, the blades crossed over the middle. */
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
     * swung in to the cross), [squash] its height (1 standing, below it crouching or landing), [draw] how
     * far it has drawn its saber (0 not yet, 1 drawn and lit). Flying: [rotor] how fast its rotor turns (0
     * still: the cow's teats and the horse's tail as they are; 1 a blur), at [spin] degrees round. The cow's
     * [laser]: how far its teats' beams reach the horse (0 none, 1 there); the horse's [block]: its saber
     * held up in front of it against them (0 en garde, 1 the guard).
     */
    data class Pose(
        val x: Float = 0f, val y: Float = 0f, val lean: Float = 0f, val swing: Float = 0f, val squash: Float = 1f, val draw: Float = 1f,
        val rotor: Float = 0f, val spin: Float = 0f, val laser: Float = 0f, val block: Float = 0f,
    )

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

    /**
     * Keeps them fighting, sabers drawn: swing in, clash with a spark, back to en garde, again, about every
     * 0.9 s. Not drawn yet (a loader appearing), they draw them first.
     */
    fun loop() {
        stop()
        if (!Motion.enabled) { progress = 1f; return }
        val from = progress
        val drawing = if (from < 0.5f) 500L else 0L
        looper = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            var drawn = drawing == 0L
            val started = android.os.SystemClock.uptimeMillis()
            addUpdateListener {
                if (!drawn) {
                    val t = (android.os.SystemClock.uptimeMillis() - started) / drawing.toFloat()
                    if (t < 1f) { progress = from + (0.5f - from) * t; return@addUpdateListener }
                    drawn = true
                    currentPlayTime = 0
                }
                val t = it.animatedValue as Float
                // Strike (0–0.4), hold the clash (0.4–0.55), back to en garde (0.55–1).
                progress = 0.5f + 0.5f * when {
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
    private fun smooth(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

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
            // The first half draws the sabers; the second swings them in, slow at first, fastest into the cross.
            val d = (progress / 0.5f).coerceIn(0f, 1f)
            val e = ((progress - 0.5f) / 0.5f).coerceIn(0f, 1f).let { it * it }
            val step = 4f * (e - 1f) // each steps in 4
            val lean = 4f * e        // and leans in 4°, from the feet
            Pose(x = step, lean = lean, swing = e, draw = d) to Pose(x = -step, lean = lean, swing = e, draw = d)
        }
        fighter(canvas, cow, 28f, 1f) {
            if (cow.draw < COW_GRAB) canvas.drawPath(Cow.tail, tailLine) // on its rump, behind the body
            cow(canvas, cow)
            cowSaber(canvas, cow.draw, cow.swing)
        }
        fighter(canvas, horse, 92f, -1f) {
            horse(canvas, horse)
            canvas.scale(-1f, 1f, 60f, 0f) // its saber is drawn in the cow's frame, mirrored
            horseSaber(canvas, horse.draw, horse.swing, horse.block)
        }
        if (cow.laser > 0f) beams(canvas, cow, horse)
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
        c.drawRect(0f, 54.5f, 120f, 60f, solid)
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

    private fun cow(c: Canvas, pose: Pose) = with(Cow) {
        piece(c, blank, legs, body)
        hooves(c, legs)
        inside(c, body, patches, solid)
        piece(c, blank, udder)
        // Flying, the teats turn under the udder as a rotor, flat (seen from the side); firing, they glow.
        if (pose.rotor > 0f) rotor(c, UDDER_X, UDDER_Y, 10.5f, 0.3f, 3, pose, tailLine)
        else c.drawPath(teats, line)
        if (pose.laser > 0f) for (i in TEATS.indices step 2) c.drawCircle(TEATS[i], TEATS[i + 1], 1.3f * pose.laser.coerceAtMost(1f), solid)
        c.drawPath(horns, horn)
        piece(c, blank, ears, head)
        c.drawPath(earsInside, faint)
        piece(c, blank, muzzle)
        c.drawPath(nostrils, solid)
        c.drawPath(mouth, fine)
        c.drawPath(eyes, solid)
        c.drawPath(brows, brow)
    }

    // --- the horse, at the right, facing left --------------------------------------------------

    private fun horse(c: Canvas, pose: Pose) = with(Horse) {
        // Flying, the tail turns as a tail rotor, upright; else it flows.
        if (pose.rotor > 0f) rotor(c, TAIL_ROOT_X, TAIL_ROOT_Y, 11f, 1f, 2, pose, maneTail)
        else {
            c.drawPath(tail, maneTail)
            c.drawPath(tail2, maneTail2)
        }
        piece(c, blank, legs, body)
        hooves(c, legs)
        c.drawPath(mane, solid)
        piece(c, blank, ears, face)
        c.drawPath(forelock, solid)
        piece(c, blank, muzzle)
        c.drawPath(nostrils, solid)
        c.drawPath(mouth, fine)
        c.drawPath(eyes, solid)
        c.drawPath(brows, brow)
    }

    /**
     * A rotor round ([cx], [cy]), [r] across: [blades] blades at the pose's spin, [flat] squashing its circle
     * into an ellipse (1 upright, seen face on), and a faint disc for the blur, the faster the darker.
     */
    private fun rotor(c: Canvas, cx: Float, cy: Float, r: Float, flat: Float, blades: Int, pose: Pose, paint: Paint) {
        blur.alpha = (64 * pose.rotor).toInt()
        c.drawOval(cx - r, cy - r * flat, cx + r, cy + r * flat, blur)
        for (i in 0 until blades) {
            val a = Math.toRadians((pose.spin + 360.0 * i / blades))
            c.drawLine(cx, cy, cx + r * cos(a).toFloat(), cy + r * flat * sin(a).toFloat(), paint)
        }
    }

    /** The cow's teats' beams to the horse's guard ([laserHit]), reaching [Pose.laser] of the way, lit like the blades. */
    private fun beams(c: Canvas, cow: Pose, horse: Pose) {
        val (tx, ty) = laserHit(horse)
        val k = cow.laser.coerceIn(0f, 1f)
        for (i in TEATS.indices step 2) {
            val x = TEATS[i] + cow.x
            val y = TEATS[i + 1] + cow.y
            val ex = x + (tx - x) * k
            val ey = y + (ty - y) * k
            c.drawLine(x, y, ex, ey, beamWide)
            c.drawLine(x, y, ex, ey, beamGlow)
            if (lightPage) c.drawLine(x, y, ex, ey, beamEdge)
            c.drawLine(x, y, ex, ey, beamCore)
        }
    }

    /** Where the cow's beams meet the horse's guard (its saber held up, [Pose.block] 1), in the box's units. */
    internal fun laserHit(horse: Pose): Pair<Float, Float> = 120f - (SH_X + ARM) + horse.x to 27f + horse.y

    // --- their sabers: in the cow's frame (the horse's mirrored), the shoulder at SH -------------

    /** The arm from the shoulder at [deg], [length] long, the hoof at its end; where the hoof is. */
    private fun arm(c: Canvas, deg: Float, length: Float): Pair<Float, Float> {
        val a = Math.toRadians(deg.toDouble())
        val hx = SH_X + length * cos(a).toFloat()
        val hy = SH_Y + length * sin(a).toFloat()
        c.drawLine(SH_X, SH_Y, hx, hy, armEdge)
        c.drawLine(SH_X, SH_Y, hx, hy, armFill)
        c.drawCircle(hx, hy, ARM_W / 2 + 0.6f, solid)
        return hx to hy
    }

    /** A blade from the hoof at ([x], [y]) at [deg], lit [length] of the way, with its glow; [hilt] drawn in its frame first. */
    private fun saber(c: Canvas, x: Float, y: Float, deg: Float, length: Float, hilt: (Canvas) -> Unit) {
        c.save()
        c.translate(x, y)
        c.rotate(deg)
        if (length > 0.01f) {
            val to = FROM + (TO - FROM) * length
            c.drawLine(FROM, 0f, to, 0f, glowWide)
            c.drawLine(FROM, 0f, to, 0f, glow)
            if (lightPage) c.drawLine(FROM, 0f, to, 0f, coreEdge)
            c.drawLine(FROM, 0f, to, 0f, core)
        }
        hilt(c)
        c.restore()
    }

    /**
     * The cow's saber at [d] drawn and [e] swung: it reaches round to its tail's root (0–COW_GRAB), yanks
     * it off, the tail hanging from the hoof, a pop where it was (to PULL), then brings it round to en
     * garde as the blade lights; then the chop, from raised back to forward.
     */
    private fun cowSaber(c: Canvas, d: Float, e: Float) {
        val reach = Math.toDegrees(atan2(TAIL_Y - SH_Y, TAIL_X - SH_X).toDouble()).toFloat()
        val far = hypot(TAIL_X - SH_X, TAIL_Y - SH_Y) - 1f
        when {
            d < COW_GRAB -> {
                val t = smooth(d / COW_GRAB)
                arm(c, lerp(75f, reach, t), lerp(ARM, far, t))
            }
            d < PULL -> {
                val t = smooth((d - COW_GRAB) / (PULL - COW_GRAB))
                pop(c, (d - COW_GRAB) / 0.15f)
                val (hx, hy) = arm(c, lerp(reach, COW_ARM_0 + 40f, t), lerp(far, ARM, t))
                // The tail, hanging from the hoof.
                c.save()
                c.translate(hx, hy)
                c.rotate(lerp(200f, 150f, t))
                c.drawPath(Cow.loose, tailLine)
                c.restore()
                c.drawCircle(hx, hy, ARM_W / 2 + 0.6f, solid)
            }
            else -> {
                val t = smooth((d - PULL) / (1f - PULL))
                val a = if (e == 0f) lerp(COW_ARM_0 + 40f, COW_ARM_0, t) else lerp(COW_ARM_0, COW_ARM_1, e)
                val b = if (e == 0f) lerp(150f, COW_BLADE_0 + 360f, t) - 360f else lerp(COW_BLADE_0, COW_BLADE_1, e)
                val (hx, hy) = hoof(a)
                saber(c, hx, hy, b, if (e == 0f) ((d - 0.7f) / 0.3f).coerceIn(0f, 1f) else 1f) { it.drawPath(Cow.hilt, hilt) }
                arm(c, a, ARM)
            }
        }
    }

    /**
     * The horse's saber, in the cow's frame: it reaches down to its member between its legs, the arm
     * stretching (0–HORSE_GRAB), draws it out, the member stretching with the hoof (to PULL), and it lights
     * at en garde; then its guard, held up steeper than the cow's chop.
     */
    private fun horseSaber(c: Canvas, d: Float, e: Float, block: Float) {
        val reach = Math.toDegrees(atan2(MEMBER_Y - SH_Y, MEMBER_X - SH_X).toDouble()).toFloat()
        val far = hypot(MEMBER_X - SH_X, MEMBER_Y - SH_Y) - 1f
        when {
            d < HORSE_GRAB -> {
                // At rest between the legs, a short tube.
                c.drawLine(MEMBER_X, MEMBER_Y - 1.5f, MEMBER_X, MEMBER_Y + 4f, memberEdge)
                c.drawLine(MEMBER_X, MEMBER_Y - 1.5f, MEMBER_X, MEMBER_Y + 4f, memberCore)
                val t = smooth(d / HORSE_GRAB)
                arm(c, lerp(75f, reach, t), lerp(ARM, far, t))
            }
            d < PULL -> {
                val t = smooth((d - HORSE_GRAB) / (PULL - HORSE_GRAB))
                val a = lerp(reach, HORSE_ARM_0 + 30f, t)
                val length = lerp(far, ARM, t)
                val (hx, hy) = hoof(a, length)
                c.drawLine(MEMBER_X, MEMBER_Y - 1.5f, hx, hy, memberEdge)
                c.drawLine(MEMBER_X, MEMBER_Y - 1.5f, hx, hy, memberCore)
                arm(c, a, length)
            }
            else -> {
                val t = smooth((d - PULL) / (1f - PULL))
                // The guard against the beams: the arm straight out in front, the blade upright.
                val a = lerp(if (e == 0f) lerp(HORSE_ARM_0 + 30f, HORSE_ARM_0, t) else lerp(HORSE_ARM_0, HORSE_ARM_1, e), 0f, block)
                val b = lerp(if (e == 0f) lerp(-150f, HORSE_BLADE_0, t) else lerp(HORSE_BLADE_0, HORSE_BLADE_1, e), -90f, block)
                val (hx, hy) = hoof(a)
                saber(c, hx, hy, b, if (e == 0f) ((d - 0.7f) / 0.3f).coerceIn(0f, 1f) else 1f) {
                    it.drawLine(-5f, 0f, FROM, 0f, memberEdge)
                    it.drawLine(-5f, 0f, FROM, 0f, memberCore)
                }
                arm(c, a, ARM)
            }
        }
    }

    private fun hoof(deg: Float, length: Float = ARM): Pair<Float, Float> {
        val a = Math.toRadians(deg.toDouble())
        return SH_X + length * cos(a).toFloat() to SH_Y + length * sin(a).toFloat()
    }

    /** Where the cow's tail came off: three short lines flying out behind, fading over [t] 0–1. */
    private fun pop(c: Canvas, t: Float) {
        if (t < 0f || t > 1f) return
        fine.alpha = ((1f - t) * 255).toInt()
        for (deg in intArrayOf(150, 190, 230)) {
            val a = Math.toRadians(deg.toDouble())
            val dx = cos(a).toFloat(); val dy = sin(a).toFloat()
            c.drawLine(TAIL_X + dx * (2 + 2 * t), TAIL_Y + dy * (2 + 2 * t), TAIL_X + dx * (4 + 3 * t), TAIL_Y + dy * (4 + 3 * t), fine)
        }
        fine.alpha = 255
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

    /** The cow, at the left, facing right: horns, ears out to the side, patches, an udder, a plain tail. */
    private object Cow {
        val legs = join(rrect(19f, 46f, 25.5f, 57.5f, 2.5f), rrect(30.5f, 46f, 37f, 57.5f, 2.5f))
        val body = rrect(14f, 25f, 42f, 50f, 9f)
        val patches = join(
            svg("M14,31 C18,28 23,30 22,35 C21,39 16,38 14,37 Z"), svg("M27,26 C31,25 36,27 34,31 C32,34 27,32 27,26 Z"),
            svg("M18,43 C21,41 25,43 24,47 C23,50 18,49 17,47 Z"), el(37.5f, 35.5f, 2.6f, 2.2f),
        )
        val udder = svg("M33,44 C33,40 41,40 41,44 C41,47.5 33,47.5 33,44 Z")
        val teats = svg("M35,47.2 L35,49.4 M37,47.6 L37,49.8 M39,47.2 L39,49.4")
        val horns = svg("M21,9 C19,6 15.5,5 15,1.5 M35,9 C37,6 40.5,5 41,1.5")
        val ears = join(el(13.5f, 13f, 5f, 2.8f), el(42.5f, 13f, 5f, 2.8f))
        val earsInside = join(el(13.5f, 13f, 2.4f, 1.1f), el(42.5f, 13f, 2.4f, 1.1f))
        val head = rrect(17f, 7f, 39f, 27f, 8f)
        val muzzle = rrect(21.5f, 18.5f, 34.5f, 25.5f, 3.5f)
        val nostrils = join(el(25.6f, 21.6f, 0.9f, 1.1f), el(30.4f, 21.6f, 0.9f, 1.1f))
        val mouth = svg("M26.5,24 Q28,24.8 29.5,24")
        val eyes = join(el(24.2f, 13.8f, 1.5f, 1.7f), el(32.2f, 13.8f, 1.5f, 1.7f))
        val brows = svg("M21.6,10.8 L26,12.2 M30.4,12.2 L34.8,10.8")
        /** On its rump, before it's yanked off. */
        val tail = svg("M15,41 C9,43 6,40 6.5,34")
        /** Off, hanging from the hoof (in the hoof's frame), and held as the hilt (in the blade's). */
        val loose = svg("M0,0 Q5,2 9,0")
        val hilt = svg("M-5,0 Q0,-1.2 4,0")
    }

    /** The horse, at the right, facing left: a long face, pointed ears, a forelock, a mane, a flowing tail. */
    private object Horse {
        val tail = svg("M105,38 C112,37 115,43 114,51")
        val tail2 = svg("M106,41 C111,42 112.5,47 111,53")
        val legs = join(rrect(83f, 46f, 89.5f, 57.5f, 2.5f), rrect(94.5f, 46f, 101f, 57.5f, 2.5f))
        val body = rrect(78f, 25f, 106f, 50f, 9f)
        val mane = svg("M100,6 C106,9 108,18 106,30 C103,28 101.5,24 101.5,19 Z")
        val ears = svg("M84.5,7 L83,0.5 L88.5,4.5 Z M99.5,7 L101,0.5 L95.5,4.5 Z")
        val face = svg("M83,9 C83,3 101,3 101,9 L100,22 C100,29 84,29 84,22 Z")
        val forelock = svg("M88,4.8 C90,2.4 94,2.4 96,4.8 C95,8 93,9.4 92,10 C91,9.4 89,8 88,4.8 Z")
        val muzzle = rrect(85f, 19.5f, 99f, 27.5f, 4f)
        val nostrils = join(el(89.2f, 23f, 0.9f, 1.2f), el(94.8f, 23f, 0.9f, 1.2f))
        val mouth = svg("M90.5,25.6 Q92,26.4 93.5,25.6")
        val eyes = join(el(88f, 13.5f, 1.5f, 1.7f), el(96f, 13.5f, 1.5f, 1.7f))
        val brows = svg("M85.4,10.6 L89.6,12 M94.4,12 L98.6,10.6")
    }

    internal companion object {
        /** The outline's width, in the box's units. */
        private const val LINE = 1.8f
        /** Where the blades cross, over the middle (x 60), when [progress] is 1. */
        const val CROSS_Y = 16.6f

        /** The saber arm: its shoulder (in the cow's frame), length and width; en garde → the cross, in degrees. */
        private const val SH_X = 36.5f
        private const val SH_Y = 35f
        private const val ARM = 9f
        private const val ARM_W = 6.8f
        private const val COW_ARM_0 = 30f
        private const val COW_ARM_1 = -18f
        private const val COW_BLADE_0 = -100f
        private const val COW_BLADE_1 = -56f
        private const val HORSE_ARM_0 = 18f
        private const val HORSE_ARM_1 = -2f
        private const val HORSE_BLADE_0 = -86f
        private const val HORSE_BLADE_1 = -60f
        /** The blade, from the hilt's end at the hoof to its tip, and half its width. */
        private const val FROM = 4f
        private const val TO = 29f
        private const val HALF = 1.6f
        /** Drawing: the hoof reaches the tail or member by COW_GRAB / HORSE_GRAB, and has it out by PULL. */
        private const val COW_GRAB = 0.35f
        private const val HORSE_GRAB = 0.35f
        private const val PULL = 0.6f
        /** The cow's tail's root, and the horse's member (in the horse's frame mirrored onto the cow's). */
        private const val TAIL_X = 15f
        private const val TAIL_Y = 41f
        private const val MEMBER_X = 28f
        private const val MEMBER_Y = 50f
        /** The cow's rotor under its udder, its teats' tips (where the beams start), the horse's tail's root. */
        private const val UDDER_X = 37f
        private const val UDDER_Y = 49.6f
        private val TEATS = floatArrayOf(35f, 49.4f, 37f, 49.8f, 39f, 49.4f)
        private const val TAIL_ROOT_X = 106f
        private const val TAIL_ROOT_Y = 39f
        private val CW = Path.Direction.CW

        private fun el(cx: Float, cy: Float, rx: Float, ry: Float) = Path().apply { addOval(cx - rx, cy - ry, cx + rx, cy + ry, CW) }
        private fun rrect(l: Float, t: Float, r: Float, b: Float, rad: Float) = Path().apply { addRoundRect(l, t, r, b, rad, rad, CW) }
        /** Shapes as one path: ones that don't overlap, or wind the same way (so their union fills). */
        private fun join(vararg shapes: Path) = Path().apply { shapes.forEach { addPath(it) } }

        /** A path from SVG path data: absolute M, L, Q, C and Z only, each segment with its letter. */
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
 * The sign-in poster's fight, about five and a half seconds on a [DuelView] stage: the cow and the horse fly
 * in like helicopters from either side, the cow's teats turning under its udder as its rotor and the horse's
 * tail as its tail rotor, and land. They draw their sabers (the cow's tail, the horse's member), clash, clash
 * again, then the cow hops back and fires its teats' beams; the horse holds its saber up against them, sparks
 * flying where they hit, pushed back a little; the beams stop, both settle en garde and bow. Then they stand
 * en garde, breathing, until the scene is tapped and plays again. Each move is a track of keyframes over the
 * time (ms) with its own easing; the sparks fly where the blades meet and where the beams hit.
 */
class DuelScene(private val view: DuelView) {
    private var playing: ValueAnimator? = null
    private var idling: ValueAnimator? = null

    init {
        // Off stage until it plays (as the poster comes in), en garde without animations.
        view.poses = if (Motion.enabled) at(0f) else rest(0f)
    }

    /** Plays the fight from the start (after [delay] ms), then idles. With "Remove animations": stands en garde. */
    fun play(delay: Long = 0) {
        stop()
        if (!Motion.enabled) { view.poses = rest(0f); return }
        var sparked = 0
        view.poses = at(0f)
        playing = ValueAnimator.ofFloat(0f, LENGTH).apply {
            duration = LENGTH.toLong()
            startDelay = delay
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                view.poses = at(t)
                // Every clash or hit passed since the last frame flies its spark (one each, however long a frame is).
                while (sparked < SPARKS.size && SPARKS[sparked] <= t) {
                    val (x, y) = sparkPlace(SPARKS[sparked++])
                    view.strike(x, y)
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

    /** Where the spark at [t] flies: over the crossed blades for a clash, on the horse's guard for a hit. */
    private fun sparkPlace(t: Float): Pair<Float, Float> =
        if (t in HITS) view.laserHit(at(t).second) else 60f + SHIFT.at(t) to DuelView.CROSS_Y + Y.at(t)

    /** For still previews: the latest spark at or before [t] ms, as where it flies and how old it is (0–1); null when none is showing. */
    internal fun sparkAt(t: Float): Triple<Float, Float, Float>? {
        val c = SPARKS.lastOrNull { it <= t } ?: return null
        val age = (t - c) / 420f
        val (x, y) = sparkPlace(c)
        return if (age > 1f) null else Triple(x, y, age)
    }

    /** Both poses at [t] ms. The pair keeps [GAP] apart from the middle, and moves [SHIFT] together. */
    internal fun at(t: Float): Pair<DuelView.Pose, DuelView.Pose> {
        val gap = GAP.at(t)
        val shift = SHIFT.at(t)
        // Hovering in: a bob, out of step, until they land.
        val bob = if (t < LANDED) 1.6f * sin(t / 95f) * (1f - t / LANDED) else 0f
        val y = Y.at(t)
        val rotor = ROTOR.at(t)
        // The rotors turn fast, then wind down after landing.
        val spin = 1.7f * if (t < LANDED) t else LANDED + (minOf(t, LANDED + 400f) - LANDED) * 0.45f
        val common = DuelView.Pose(swing = SWING.at(t), squash = SQUASH.at(t), draw = DRAW.at(t), rotor = rotor)
        return common.copy(x = shift - gap, y = y + bob, lean = COW_LEAN.at(t), spin = spin, laser = LASER.at(t)) to
            common.copy(x = shift + gap, y = y - bob, lean = HORSE_LEAN.at(t), spin = -spin * 1.3f, block = BLOCK.at(t))
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
        const val LENGTH = 5600f
        /** When they touch down. */
        const val LANDED = 1100f
        /** How far apart they stand en garde, each from the middle. */
        const val REST_GAP = 11f

        /** Easing in (speeding up: into a strike), out (slowing: a landing, a stop) and both. */
        val easeIn: (Float) -> Float = { it * it * it }
        val easeOut: (Float) -> Float = { 1 - (1 - it) * (1 - it) * (1 - it) }
        val smooth: (Float) -> Float = { it * it * (3 - 2 * it) }
        fun k(t: Int, v: Float, ease: (Float) -> Float = smooth) = Triple(t.toFloat(), v, ease)

        /** Down from above the stage, landing at LANDED; a hop back before the beams. */
        val Y = Track(k(0, -64f), k(LANDED.toInt(), 0f, easeOut), k(3200, 0f), k(3290, -6f, easeOut), k(3400, 0f, easeIn), k(5600, 0f))
        /** Each one's distance from the middle: in from the sides as they fly, the clashes (0), apart for the beams. */
        val GAP = Track(
            k(0, 52f), k(LANDED.toInt(), 14f, easeOut), k(2250, 14f), k(2550, 0f, easeIn), k(2700, 0f), k(2850, 8f, easeOut), k(2980, 0f, easeIn),
            k(3200, 8f, easeOut), k(3400, 16f, easeOut), k(4300, 16f), k(4700, REST_GAP, easeOut), k(5600, REST_GAP),
        )
        /** The pair moving together: the beams push the horse (and so the middle) back, then both return. */
        val SHIFT = Track(k(0, 0f), k(3500, 0f), k(4200, 5f), k(4600, 0f, easeOut), k(5600, 0f))
        /** The rotors: turning as they fly, still after landing. */
        val ROTOR = Track(k(0, 1f), k(LANDED.toInt(), 1f), k(1500, 0f, easeOut), k(5600, 0f))
        /** Landing's squash, and the hop's. */
        val SQUASH = Track(
            k(0, 1f), k(1050, 1f), k(1160, 0.84f, easeOut), k(1300, 1f), k(3200, 1f), k(3250, 0.88f, easeOut), k(3300, 1f),
            k(3400, 1f), k(3450, 0.9f, easeOut), k(3520, 1f), k(5600, 1f),
        )
        /** Drawing the sabers, once landed. */
        val DRAW = Track(k(0, 0f), k(1450, 0f), k(2250, 1f), k(5600, 1f))
        /** Saber arms: en garde (0) to the cross (1), fastest into each strike. */
        val SWING = Track(
            k(0, 0f), k(2250, 0f), k(2550, 1f, easeIn), k(2700, 1f), k(2850, 0.3f, easeOut), k(2980, 1f, easeIn), k(3200, 0f, easeOut), k(5600, 0f),
        )
        /** The cow's beams: out to the horse, held, off. */
        val LASER = Track(k(0, 0f), k(3450, 0f), k(3560, 1f, easeOut), k(4250, 1f), k(4330, 0f), k(5600, 0f))
        /** The horse's guard against them. */
        val BLOCK = Track(k(0, 0f), k(3300, 0f), k(3480, 1f, easeOut), k(4300, 1f), k(4550, 0f), k(5600, 0f))
        /** Leaning into each strike, the horse leaning into the beams; then the bow (5000–5300). */
        private val LEAN = listOf(k(0, 0f), k(2250, 0f), k(2550, 5f, easeIn), k(2700, 5f), k(2850, 0f), k(2980, 5f, easeIn), k(3200, 0f))
        private val BOW = listOf(k(4700, 0f), k(5000, 14f), k(5300, 14f), k(5600, 0f))
        val COW_LEAN = Track(*(LEAN + listOf(k(3500, -3f), k(4300, -3f)) + BOW).toTypedArray())
        val HORSE_LEAN = Track(*(LEAN + listOf(k(3500, 4f), k(4300, 4f)) + BOW).toTypedArray())
        /** When the blades meet, and when the beams hit the horse's guard. */
        val CLASHES = listOf(2550f, 2980f)
        val HITS = listOf(3560f, 3720f, 3880f, 4040f, 4200f)
        val SPARKS = (CLASHES + HITS).sorted()
    }
}
