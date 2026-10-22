package com.skylightlim.umtimetable

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** The app's two colour schemes. */
data class Palette(
    val dark: Boolean,
    val ground: Int,
    val surface: Int,
    val surface2: Int,
    val line: Int,
    val text: Int,
    val muted: Int,
    val faint: Int,
    val accent: Int,
    val accentText: Int,
    val accentInk: Int,
    /** Text on a solid course colour (lecture cards). */
    val ink: Int,
    val inkMuted: Int,
    /** How much of a course colour survives when it's used as text (light theme darkens it to stay readable). */
    val courseTextMix: Float,
    /** How much course colour tints a tutorial card. */
    val tint: Float,
) {
    fun courseText(color: Int) = mix(color, Color.BLACK, courseTextMix)
    fun courseTint(color: Int) = mix(color, surface, tint)

    companion object {
        val DARK = Palette(
            dark = true,
            ground = 0xFF11151B.toInt(), surface = 0xFF192029.toInt(), surface2 = 0xFF222A35.toInt(), line = 0xFF2B3440.toInt(),
            text = 0xFFE8ECF1.toInt(), muted = 0xFF8F9AA9.toInt(), faint = 0xFF5F6B7A.toInt(),
            accent = 0xFFF5C947.toInt(), accentText = 0xFFF5C947.toInt(), accentInk = 0xFF1B1606.toInt(),
            ink = 0xFF10141A.toInt(), inkMuted = 0xB810141A.toInt(), courseTextMix = 1f, tint = 0.14f,
        )
        val LIGHT = Palette(
            dark = false,
            ground = 0xFFEEF1F4.toInt(), surface = 0xFFFFFFFF.toInt(), surface2 = 0xFFE8ECF0.toInt(), line = 0xFFD6DCE3.toInt(),
            text = 0xFF141A22.toInt(), muted = 0xFF5A6573.toInt(), faint = 0xFF8B95A2.toInt(),
            accent = 0xFFEFB810.toInt(), accentText = 0xFF875A00.toInt(), accentInk = 0xFF1B1606.toInt(),
            ink = 0xFF10141A.toInt(), inkMuted = 0xB810141A.toInt(), courseTextMix = 0.58f, tint = 0.16f,
        )

        /** [a] weighted by [t], the rest [b]. */
        fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
            (Color.red(a) * t + Color.red(b) * (1 - t)).toInt(),
            (Color.green(a) * t + Color.green(b) * (1 - t)).toInt(),
            (Color.blue(a) * t + Color.blue(b) * (1 - t)).toInt(),
        )
    }
}

/** A theme's own greys: page, cards, lines and the three text colours. */
data class Neutrals(val ground: Int, val surface: Int, val surface2: Int, val line: Int, val text: Int, val muted: Int, val faint: Int) {
    companion object {
        fun of(vararg c: Long) = Neutrals(c[0].toInt(), c[1].toInt(), c[2].toInt(), c[3].toInt(), c[4].toInt(), c[5].toInt(), c[6].toInt())
    }
}

/**
 * The colour theme the user picks in Settings, from UM's crest: Gold (the default, the tigers' yellow),
 * Blue (the shield) and Red (the hibiscus). Each sets the accent fill, the accent as text and the ink on
 * the fill for the light and the dark palette (text on surfaces ≥ 6:1, ink on the fill ≥ 4.8:1), and the
 * courses' colours: [courses] replaces the import palette slot by slot (null keeps it). Gold is the
 * import's mixed set; Blue a cool one (mint to violet), Red a warm one (rose to gold). Built in OKLCH:
 * the course ink is ≥ 5:1 on every colour, and the first seven stay ≥ 0.085 apart in OKLab.
 *
 * Blue and Red also tint the greys ([lightGreys], [darkGreys]) with their hue, so a theme changes the
 * whole page and not only its accents: Blue's dark is navy, Red's a deep maroon. Same lightness steps as
 * the plain greys (OKLCH, chroma 0.02–0.03): text 15.6:1 on the page, muted ≥ 5.4:1 on every surface,
 * the accent text ≥ 5.7:1. Gold keeps the plain cool greys, the app's look from the start.
 */
enum class Scheme(
    val label: String,
    val light: Triple<Int, Int, Int>,
    val dark: Triple<Int, Int, Int>,
    val courses: List<Int>?,
    val lightGreys: Neutrals? = null,
    val darkGreys: Neutrals? = null,
) {
    GOLD("Gold", Triple(0xFFEFB810.toInt(), 0xFF875A00.toInt(), 0xFF1B1606.toInt()), Triple(0xFFF5C947.toInt(), 0xFFF5C947.toInt(), 0xFF1B1606.toInt()), null),
    BLUE(
        "Blue", Triple(0xFF3559C3.toInt(), 0xFF2848A9.toInt(), 0xFFF5F8FF.toInt()), Triple(0xFF96B6FE.toInt(), 0xFF96B6FE.toInt(), 0xFF09163F.toInt()),
        listOf(0xFFB199F4, 0xFF449DF0, 0xFF54DDCE, 0xFF68CA80, 0xFFE38FDB, 0xFF2F9BB2, 0xFFCCE47F, 0xFF35B9C0, 0xFFB0C2F9).map { it.toInt() },
        Neutrals.of(0xFFE8EFFF, 0xFFFBFDFF, 0xFFDEE7F7, 0xFFCDD6E7, 0xFF111723, 0xFF535C6F, 0xFF838C9E),
        Neutrals.of(0xFF080E1A, 0xFF101724, 0xFF19202F, 0xFF242B3A, 0xFFE6EAF1, 0xFF979FAE, 0xFF656C7A),
    ),
    RED(
        "Red", Triple(0xFFD02C2E.toInt(), 0xFFB71920.toInt(), 0xFFFFF6F5.toInt()), Triple(0xFFF68482.toInt(), 0xFFF68482.toInt(), 0xFF34080B.toInt()),
        listOf(0xFFD275D8, 0xFFEF546C, 0xFFFDB171, 0xFFA3C75D, 0xFFF6A9CD, 0xFFCA6B49, 0xFFFDCE4E, 0xFFFDB5AF, 0xFFB699EB).map { it.toInt() },
        Neutrals.of(0xFFFBEBEA, 0xFFFFFCFC, 0xFFF3E1E0, 0xFFE3D1D0, 0xFF201313, 0xFF6B5655, 0xFF9A8786),
        Neutrals.of(0xFF170A0A, 0xFF211312, 0xFF2B1C1B, 0xFF362726, 0xFFEFE7E7, 0xFFAB9A99, 0xFF776867),
    );

    /** The course colours this theme shows, in import-slot order. */
    val courseColors: List<Int> get() = courses ?: MayaImport.PALETTE.map { Color.parseColor(it) }

    /** [base] with this scheme's accent fill, accent text and ink, and its greys if it has its own. */
    fun applyTo(base: Palette): Palette {
        val (fill, text, ink) = if (base.dark) dark else light
        val accented = base.copy(accent = fill, accentText = text, accentInk = ink)
        val g = (if (base.dark) darkGreys else lightGreys) ?: return accented
        return accented.copy(ground = g.ground, surface = g.surface, surface2 = g.surface2, line = g.line, text = g.text, muted = g.muted, faint = g.faint)
    }

    companion object {
        fun load(c: Context) = runCatching { valueOf(uiPrefs(c).getString("scheme", GOLD.name)!!) }.getOrDefault(GOLD)
        fun save(c: Context, scheme: Scheme) = uiPrefs(c).edit().putString("scheme", scheme.name).apply()
    }
}

/** The theme button: Auto follows the phone's dark mode; Light and Dark override it. */
enum class Look(val label: String, val icon: Int) {
    AUTO("Auto", R.drawable.ic_look_auto), LIGHT("Light", R.drawable.ic_look_light), DARK("Dark", R.drawable.ic_look_dark);

    fun palette(context: Context, scheme: Scheme = Scheme.load(context)): Palette = scheme.applyTo(when (this) {
        LIGHT -> Palette.LIGHT
        DARK -> Palette.DARK
        AUTO -> if ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) Palette.DARK else Palette.LIGHT
    })

    companion object {
        fun load(c: Context) = runCatching { valueOf(uiPrefs(c).getString("look", AUTO.name)!!) }.getOrDefault(AUTO)
        fun save(c: Context, look: Look) = uiPrefs(c).edit().putString("look", look.name).apply()
    }
}

/** Typefaces: three families, bundled as TrueType. */
class Fonts(context: Context) {
    private val r = context.resources
    /** Big Shoulders Display ExtraBold: day names, room codes, section titles. */
    val sign: Typeface = r.getFont(R.font.bigshoulders_800)
    val body: Typeface = r.getFont(R.font.instrumentsans_400)
    val bodySemi: Typeface = r.getFont(R.font.instrumentsans_600)
    val bodyBold: Typeface = r.getFont(R.font.instrumentsans_700)
    /** JetBrains Mono: course codes and times. */
    val mono: Typeface = r.getFont(R.font.jetbrainsmono_500)
    val monoBold: Typeface = r.getFont(R.font.jetbrainsmono_700)
}

/**
 * Text that shrinks to fit its width, never past [maxPx] (its size before any shrinking). By [widest]: so that
 * text fits on one line, the same size for every text shown in its place (a title doesn't jump from day to
 * day). With [words]: so its longest word fits, and a long name wraps between words, never inside one ("Entre /
 * preneurship" at a large system font size). Neither: so its own text fits on one line.
 */
// Give it a wrap-content width (or a fixed one). Measured at a width that came from its own text, as a
// column's full-width child is in the column's second pass, it fits [widest] into that and shrinks.
@android.annotation.SuppressLint("ViewConstructor")
open class FitText(context: Context, private val maxPx: Float, private val widest: String? = null, private val words: Boolean = false) : TextView(context) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        if (View.MeasureSpec.getMode(widthSpec) != View.MeasureSpec.UNSPECIFIED) {
            val room = View.MeasureSpec.getSize(widthSpec) - totalPaddingLeft - totalPaddingRight
            val all = text.toString()
            val needed = when {
                words -> all.split(Regex("\\s+")).maxOfOrNull { paint.measureText(it) } ?: 0f
                widest != null -> maxOf(paint.measureText(widest), paint.measureText(all))
                else -> paint.measureText(all)
            }
            // 2% short of the room when it shrinks: fitted to the exact width, the last letter wrapped ("Spectru / m").
            val size = if (needed > 0f) minOf(maxPx, textSize * room * 0.98f / needed) else maxPx
            if (kotlin.math.abs(size - textSize) > 0.5f) setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        }
        super.onMeasure(widthSpec, heightSpec)
    }
}

/**
 * The row of a [Ui.segmented] switch, drawing its accent [pill] behind the options. The pill sits [at] an
 * option's index, fractional while it glides between two; past either end (a spring's overshoot) it
 * keeps going at the last two options' spacing. Drawn from the options' own layout, so it needs no
 * measuring of its own and shows in an off-screen drawing too.
 */
@android.annotation.SuppressLint("ViewConstructor")
class SegmentPill(context: Context, private val pill: Drawable, private var at: Float) : LinearLayout(context) {
    private var gliding: android.animation.ValueAnimator? = null

    init { setWillNotDraw(false) }

    /** Where the pill is now, as an option's index (fractional while it moves). */
    val position get() = at

    /** Puts the pill at [position] at once (following a finger). */
    fun put(position: Float) {
        gliding?.cancel()
        at = position
        invalidate()
    }

    fun glide(to: Int, spring: Motion.Spring = Motion.BOUNCY) {
        gliding?.cancel()
        if (!Motion.enabled) { at = to.toFloat(); invalidate(); return }
        gliding = android.animation.ValueAnimator.ofFloat(at, to.toFloat()).apply {
            interpolator = spring
            duration = spring.duration
            addUpdateListener { at = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        if (childCount > 0) {
            val i = kotlin.math.floor(at).toInt().coerceIn(0, maxOf(0, childCount - 2))
            val a = getChildAt(i)
            val b = getChildAt(minOf(i + 1, childCount - 1))
            val t = at - i
            fun lerp(x: Int, y: Int) = (x + (y - x) * t).toInt()
            pill.setBounds(lerp(a.left, b.left), a.top, lerp(a.right, b.right), a.bottom)
            pill.draw(canvas)
        }
        super.dispatchDraw(canvas)
    }

    override fun onDetachedFromWindow() {
        gliding?.cancel()
        super.onDetachedFromWindow()
    }
}

/**
 * An on/off switch, drawn as iOS draws one: a pill track, the accent when on, with a white knob that
 * springs across. Held, the knob stretches toward the other side; let go on it, the knob springs over,
 * the track's colour following, with a tick, and [onChange] gets the new state. 48dp tall to touch,
 * the track 31dp of it.
 */
@android.annotation.SuppressLint("ViewConstructor")
class Toggle(private val ui: Ui, on: Boolean, private val onChange: (Boolean) -> Unit) : View(ui.context) {
    var on = on
        private set
    /** Where the knob is, 0 (off) to 1 (on), and how stretched, 0 to 1. */
    private var at = if (on) 1f else 0f
    private var stretch = 0f
    private var move: android.animation.ValueAnimator? = null
    private var hold: android.animation.ValueAnimator? = null
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val knob = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(ui.dp(2f).toFloat(), 0f, ui.dp(1f).toFloat(), 0x40000000)
    }
    private val off = Palette.mix(ui.p.text, ui.p.surface, if (ui.p.dark) 0.22f else 0.13f)
    private val rect = android.graphics.RectF()

    init {
        isClickable = true
        stateDescription = if (on) "On" else "Off"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(ui.dp(51), ui.dp(48))

    private fun tween(from: Float, to: Float, spring: Motion.Spring, set: (Float) -> Unit) =
        if (!Motion.enabled) { set(to); invalidate(); null } else android.animation.ValueAnimator.ofFloat(from, to).apply {
            interpolator = spring
            duration = spring.duration
            addUpdateListener { set(it.animatedValue as Float); invalidate() }
            start()
        }

    private fun holdTo(to: Float) {
        hold?.cancel()
        hold = tween(stretch, to, Motion.QUICK) { stretch = it }
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> holdTo(1f)
            android.view.MotionEvent.ACTION_CANCEL -> holdTo(0f)
            android.view.MotionEvent.ACTION_UP -> holdTo(0f)
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean {
        super.performClick()
        on = !on
        stateDescription = if (on) "On" else "Off"
        move?.cancel()
        move = tween(at, if (on) 1f else 0f, Motion.BOUNCY) { at = it }
        Haptics.tick(this)
        onChange(on)
        return true
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val h = ui.dp(31f).toFloat()
        val top = (height - h) / 2
        rect.set(0f, top, width.toFloat(), top + h)
        paint.color = Palette.mix(ui.p.accent, off, at.coerceIn(0f, 1f))
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        val inset = ui.dp(2f).toFloat()
        val size = h - inset * 2
        val w = size + ui.dp(7f) * stretch
        val left = inset + (width - inset * 2 - w) * at
        rect.set(left, top + inset, left + w, top + inset + size)
        canvas.drawRoundRect(rect, size / 2, size / 2, knob)
    }
}

/**
 * A [Ui.wheel]: a vertical picker, as iOS draws one. Its options (words, or colour swatches) roll up and
 * down under a fixed accent band, and the one in the band is chosen. Dragged, it follows the finger (resisting past either end);
 * let go, it carries on at the finger's speed and settles, on a spring, on the option nearest where that
 * speed would have taken it. A tap on an option above or below rolls it into the band. Each option that
 * reaches the band ticks and is chosen at once ([onPick]). Away from the band the options shrink and
 * fade, as on a drum; an option's label turns to the band's ink exactly where it enters the band. Drawn
 * rather than built of views, so a roll is one draw a frame.
 */
@android.annotation.SuppressLint("ViewConstructor")
class Wheel(private val ui: Ui, private val options: List<String>, chosen: Int, private val rows: Int = 3, private val colors: List<Int>? = null) : View(ui.context) {
    var chosen = chosen.coerceIn(0, maxOf(0, options.lastIndex))
        private set
    var onPick: (Int) -> Unit = {}
    /** What it chooses ("How long"), said before the chosen option to a screen reader. */
    var name = ""
        set(value) { field = value; describe() }
    private val rowH = ui.dp(34f).toFloat()
    private val inset = ui.dp(3f).toFloat()
    /** The option at the band's centre, fractional while it rolls. */
    private var at = this.chosen.toFloat()
    private val label = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ui.f.bodySemi
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 15f, ui.context.resources.displayMetrics)
        textAlign = android.graphics.Paint.Align.CENTER
    }
    private val track = ui.shape(ui.p.surface2, 12f)
    private val band = ui.shape(ui.p.accent, 10f)
    /** With [colors] (a wheel of swatches, [options] naming them), the band is a ring: an accent fill would hide the accent's own swatch. */
    private val ring = ui.shape(ui.p.surface, 10f, ui.p.text, 2f)
    private val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val bandRect = android.graphics.RectF()
    private val slop = android.view.ViewConfiguration.get(ui.context).scaledTouchSlop
    private var downY = 0f
    private var startAt = 0f
    private var dragging = false
    private var tracker: android.view.VelocityTracker? = null
    private var roll: android.animation.ValueAnimator? = null

    init {
        isClickable = true // takes its touches: press feedback leaves what's behind it alone
        describe()
    }

    private fun describe() {
        val option = options.getOrNull(chosen).orEmpty()
        contentDescription = if (name.isEmpty()) option else "$name: $option"
    }

    /** As wide as its widest option needs, with room around it (unless told a width): a band only as wide as the labels. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val natural = if (colors != null) ui.dp(84) else ((options.maxOfOrNull { label.measureText(it) } ?: 0f) + ui.dp(44f)).toInt().coerceAtLeast(ui.dp(84))
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val width = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> size
            MeasureSpec.AT_MOST -> minOf(natural, size)
            else -> natural
        }
        setMeasuredDimension(width, (rows * rowH + inset * 2).toInt())
    }

    /** Past either end it resists more the further it's pulled, as iOS's edges do. */
    private fun rubber(raw: Float): Float {
        val last = options.lastIndex.toFloat()
        fun band(o: Float) = o * rows * 0.55f / (rows + 0.55f * kotlin.math.abs(o))
        return when {
            raw < 0 -> -band(-raw)
            raw > last -> last + band(raw - last)
            else -> raw
        }
    }

    /** At [to] (fractional): ticks and chooses as a new option reaches the band's middle. */
    private fun moveTo(to: Float) {
        at = to
        val now = kotlin.math.round(at).toInt().coerceIn(0, options.lastIndex)
        if (now != chosen) {
            chosen = now
            describe()
            Haptics.tick(this)
            onPick(now)
        }
        invalidate()
    }

    /** Rolls to option [i] on a spring, starting at [rowsPerSecond] (a fling let go). */
    private fun settle(i: Int, rowsPerSecond: Float = 0f) {
        roll?.cancel()
        val to = i.coerceIn(0, options.lastIndex).toFloat()
        if (!Motion.enabled) return moveTo(to)
        val spring = Motion.SETTLE.toward(at, to, rowsPerSecond)
        roll = android.animation.ValueAnimator.ofFloat(at, to).apply {
            interpolator = spring
            duration = spring.duration
            addUpdateListener { moveTo(it.animatedValue as Float) }
            start()
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (options.isEmpty()) return false
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                roll?.cancel()
                // Vertical drags here roll the wheel, not the sheet or page around it.
                parent?.requestDisallowInterceptTouchEvent(true)
                downY = e.y
                startAt = at
                dragging = false
                tracker = android.view.VelocityTracker.obtain().apply { addMovement(e) }
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                val dy = e.y - downY
                if (!dragging && kotlin.math.abs(dy) > slop) { dragging = true; downY = e.y; startAt = at }
                if (dragging) moveTo(rubber(startAt - (e.y - downY) / rowH))
            }
            android.view.MotionEvent.ACTION_UP -> {
                tracker?.addMovement(e)
                if (dragging) {
                    val v = tracker?.run { computeCurrentVelocity(1000); -yVelocity / rowH } ?: 0f
                    // Where the fling would come to rest (Apple's projection, normal deceleration), then the option nearest it.
                    val rest = at + v * 0.998f / (1 - 0.998f) / 1000f
                    settle(kotlin.math.round(rest).toInt(), v)
                } else {
                    performClick()
                    settle(kotlin.math.round(at + (e.y - height / 2f) / rowH).toInt())
                }
                tracker?.recycle(); tracker = null
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                settle(kotlin.math.round(at).toInt())
                tracker?.recycle(); tracker = null
            }
        }
        return true
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        track.setBounds(0, 0, width, height)
        track.draw(canvas)
        val cy = height / 2f
        bandRect.set(inset, cy - rowH / 2, width - inset, cy + rowH / 2)
        if (colors != null) {
            ring.setBounds(bandRect.left.toInt(), bandRect.top.toInt(), bandRect.right.toInt(), bandRect.bottom.toInt())
            ring.draw(canvas)
            canvas.save()
            canvas.clipRect(inset, inset, width - inset, height - inset)
            drawSwatches(canvas, cy, colors)
            canvas.restore()
            return
        }
        band.setBounds(bandRect.left.toInt(), bandRect.top.toInt(), bandRect.right.toInt(), bandRect.bottom.toInt())
        band.draw(canvas)
        canvas.save()
        canvas.clipRect(inset, inset, width - inset, height - inset)
        drawLabels(canvas, cy, ui.p.muted, fade = true)
        canvas.restore()
        // Again inside the band, in its ink: a label changes colour right where it crosses the band's edge.
        canvas.save()
        canvas.clipRect(bandRect)
        drawLabels(canvas, cy, ui.p.accentInk, fade = false)
        canvas.restore()
    }

    /** A dot of each colour, shrinking and fading away from the band as the labels do. */
    private fun drawSwatches(canvas: android.graphics.Canvas, cy: Float, colors: List<Int>) {
        val reach = rows / 2f + 0.5f
        val first = kotlin.math.floor(at - reach).toInt().coerceAtLeast(0)
        val last = kotlin.math.ceil(at + reach).toInt().coerceAtMost(colors.lastIndex)
        val r = ui.dp(10f).toFloat()
        for (i in first..last) {
            val away = ((i - at) / reach).coerceIn(-1f, 1f)
            dot.color = colors[i]
            dot.alpha = (255 * (1 - 0.6f * kotlin.math.abs(away))).toInt()
            canvas.drawCircle(width / 2f, cy + (i - at) * rowH, r * (1 - 0.18f * away * away), dot)
        }
    }

    private fun drawLabels(canvas: android.graphics.Canvas, cy: Float, color: Int, fade: Boolean) {
        val reach = rows / 2f + 0.5f
        val first = kotlin.math.floor(at - reach).toInt().coerceAtLeast(0)
        val last = kotlin.math.ceil(at + reach).toInt().coerceAtMost(options.lastIndex)
        val baseline = -(label.ascent() + label.descent()) / 2
        for (i in first..last) {
            val away = ((i - at) / reach).coerceIn(-1f, 1f)
            val y = cy + (i - at) * rowH
            val k = 1 - 0.14f * away * away
            label.color = color
            label.alpha = if (fade) (255 * (1 - 0.7f * kotlin.math.abs(away))).toInt() else 255
            canvas.save()
            canvas.scale(k, k, width / 2f, y)
            canvas.drawText(options[i], width / 2f, y + baseline, label)
            canvas.restore()
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (chosen < options.lastIndex) info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        if (chosen > 0) info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean = when (action) {
        android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { settle(chosen + 1); true }
        android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { settle(chosen - 1); true }
        else -> super.performAccessibilityAction(action, arguments)
    }

    override fun onDetachedFromWindow() {
        roll?.cancel()
        super.onDetachedFromWindow()
    }
}

/** The app's own settings (look, scheme, vibration, reminders, the page last open), apart from Spectrum's. */
fun uiPrefs(c: Context): android.content.SharedPreferences = c.getSharedPreferences("ui", Context.MODE_PRIVATE)

/** Small view-building helpers shared by the native screens. */
class Ui(val context: Context, val p: Palette, val f: Fonts) {
    private val density = context.resources.displayMetrics.density
    fun dp(v: Float) = (v * density + 0.5f).toInt()
    fun dp(v: Int) = dp(v.toFloat())

    fun text(
        value: CharSequence,
        size: Float,
        color: Int = p.text,
        face: Typeface = f.body,
        caps: Boolean = false,
        spacing: Float = 0f,
        lines: Int = 0,
    ) = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = face
        isAllCaps = caps
        letterSpacing = spacing
        includeFontPadding = false
        setLineSpacing(0f, 1.2f)
        if (lines > 0) {
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
        }
    }

    /** [text], shrinking to fit its width: see [FitText] for [widest] and [words]. */
    fun fitText(value: CharSequence, size: Float, color: Int = p.text, face: Typeface = f.body, widest: String? = null, words: Boolean = false, lines: Int = 0): TextView {
        val maxPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, context.resources.displayMetrics)
        return FitText(context, maxPx, widest, words).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_PX, maxPx)
            setTextColor(color)
            typeface = face
            includeFontPadding = false
            setLineSpacing(0f, 1.2f)
            if (lines > 0) {
                maxLines = lines
                ellipsize = TextUtils.TruncateAt.END
            }
        }
    }

    /** Uppercase, letter-spaced label (status labels, section headings). */
    fun label(value: String, color: Int = p.muted, size: Float = 11.5f) = text(value, size, color, f.bodyBold, caps = true, spacing = 0.12f)

    fun shape(fill: Int, radius: Float = 16f, stroke: Int = 0, strokeWidth: Float = 1f, dash: Float = 0f) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != 0) {
            if (dash > 0) setStroke(dp(strokeWidth), stroke, dp(dash).toFloat(), dp(dash * 0.6f).toFloat())
            else setStroke(dp(strokeWidth), stroke)
        }
    }

    /** A background that also shows a touch ripple, clipped to its rounded shape. */
    fun pressable(background: Drawable, radius: Float = 16f): Drawable =
        RippleDrawable(ColorStateList.valueOf(if (p.dark) 0x33FFFFFF else 0x22000000), background, shape(Color.WHITE, radius))

    fun icon(res: Int, color: Int, size: Float = 22f) = ImageView(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    fun column(gap: Float = 0f) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        if (gap > 0) {
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(1, dp(gap)) }
        }
    }

    fun row(gap: Float = 0f, center: Boolean = true) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        if (center) gravity = Gravity.CENTER_VERTICAL
        if (gap > 0) {
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(dp(gap), 1) }
        }
    }

    fun space(w: Int = 0, h: Int = 0) = View(context).apply { layoutParams = LinearLayout.LayoutParams(w, h) }

    /** Takes the remaining width in a row. */
    fun grow() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    /**
     * For an empty spacer that pushes the next item to the end of a row. Its height must be 0:
     * an empty View asked to wrap its content takes all the height it's offered.
     */
    fun spacer() = LinearLayout.LayoutParams(0, 0, 1f)

    fun wide(top: Float = 0f, bottom: Float = 0f) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    /** A thin progress bar: [fraction] of the track filled. */
    fun bar(fraction: Float, fill: Int, track: Int) = LinearLayout(context).apply {
        val f = fraction.coerceIn(0f, 1f)
        background = shape(track, 4f)
        clipToOutline = true
        addView(View(context).apply { background = shape(fill, 4f) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, f))
        addView(View(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1 - f))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4))
    }

    /**
     * A segmented switch: options in a row, the chosen one on an accent pill. [fill] shares the row's
     * width equally. A tap glides the pill to the option on a spring, its label and the one it leaves
     * changing colour with it, calls [onChoose] with the option and its view, then vibrates (after, so
     * choosing a vibration strength plays the new one). The pill is the only feedback: the options have
     * no ripple and don't sink (a grey ripple on the option lagged behind the pill as it arrived). With
     * [from] (a pill position), the pill starts there and settles on [chosen]: a switch drawn again in the
     * middle of a move (a new look redraws Settings) carries it on.
     */
    fun segmented(options: List<String>, chosen: Int, fill: Boolean = false, from: Float? = null, onChoose: (Int, View) -> Unit): LinearLayout {
        var current = chosen
        val views = mutableListOf<TextView>()
        val pill = SegmentPill(context, shape(p.accent, 10f), from ?: chosen.toFloat())
        fun describe() = views.forEachIndexed { i, v -> v.contentDescription = "${options[i]}${if (i == current) ", chosen" else ""}" }
        fun glide(to: Int, leaving: Int) {
            // The label the pill leaves turns quickly: its ink colour on the bare track is barely readable.
            Motion.tint(views[leaving], p.accentInk, p.muted, 90)
            Motion.tint(views[to], p.muted, p.accentInk)
            pill.glide(to)
        }
        return pill.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(dp(2f), 1) }
            background = shape(p.surface2, 12f)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            for ((i, label) in options.withIndex()) {
                val v = text(label, 13f, if (i == chosen) p.accentInk else p.muted, f.bodySemi).apply {
                    gravity = Gravity.CENTER
                    minHeight = dp(if (fill) 40 else 36)
                    setPadding(dp(12), 0, dp(12), 0)
                    setOnClickListener {
                        if (i == current) return@setOnClickListener
                        val was = current
                        current = i
                        describe()
                        glide(i, was)
                        onChoose(i, this)
                        Haptics.tick(this)
                    }
                }
                views += v
                addView(v, if (fill) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            describe()
            if (from != null) pill.glide(chosen, Motion.QUICK)
        }
    }

    /** A [Wheel]: [options] rolling up and down under a band, [rows] of them in view; [onChoose] runs as each reaches the band. */
    fun wheel(options: List<String>, chosen: Int, rows: Int = 3, colors: List<Int>? = null, onChoose: (Int) -> Unit) = Wheel(this, options, chosen, rows, colors).apply { onPick = onChoose }

    /** A rounded pill, e.g. LECTURE / TODAY. */
    fun chip(value: String, fill: Int, color: Int, size: Float = 10.5f) = text(value, size, color, f.bodyBold, caps = true, spacing = 0.1f).apply {
        background = shape(fill, 99f)
        setPadding(dp(7), dp(2), dp(7), dp(2))
    }
}

fun hoursText(minutes: Int): String = (minutes / 60.0).let { if (it == Math.floor(it)) "${it.toInt()} h" else "${"%.1f".format(it)} h" }
