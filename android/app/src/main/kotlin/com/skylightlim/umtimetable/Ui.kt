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
        private fun prefs(c: Context) = c.getSharedPreferences("ui", Context.MODE_PRIVATE)
        fun load(c: Context) = runCatching { valueOf(prefs(c).getString("scheme", GOLD.name)!!) }.getOrDefault(GOLD)
        fun save(c: Context, scheme: Scheme) = prefs(c).edit().putString("scheme", scheme.name).apply()
    }
}

/** The theme button: Auto follows the phone's dark mode; Light and Dark override it. */
enum class Look(val label: String, val icon: Int) {
    AUTO("Auto", R.drawable.ic_look_auto), LIGHT("Light", R.drawable.ic_look_light), DARK("Dark", R.drawable.ic_look_dark);

    val next get() = entries[(ordinal + 1) % entries.size]

    fun palette(context: Context, scheme: Scheme = Scheme.load(context)): Palette = scheme.applyTo(when (this) {
        LIGHT -> Palette.LIGHT
        DARK -> Palette.DARK
        AUTO -> if ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) Palette.DARK else Palette.LIGHT
    })

    companion object {
        private fun prefs(c: Context) = c.getSharedPreferences("ui", Context.MODE_PRIVATE)
        fun load(c: Context) = runCatching { valueOf(prefs(c).getString("look", AUTO.name)!!) }.getOrDefault(AUTO)
        fun save(c: Context, look: Look) = prefs(c).edit().putString("look", look.name).apply()
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
class FitText(context: Context, private val maxPx: Float, private val widest: String? = null, private val words: Boolean = false) : TextView(context) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        if (View.MeasureSpec.getMode(widthSpec) != View.MeasureSpec.UNSPECIFIED) {
            val room = View.MeasureSpec.getSize(widthSpec) - totalPaddingLeft - totalPaddingRight
            val all = text.toString()
            val needed = when {
                words -> all.split(Regex("\\s+")).maxOfOrNull { paint.measureText(it) } ?: 0f
                widest != null -> maxOf(paint.measureText(widest), paint.measureText(all))
                else -> paint.measureText(all)
            }
            val size = if (needed > 0f) minOf(maxPx, textSize * room / needed) else maxPx
            if (kotlin.math.abs(size - textSize) > 0.5f) setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        }
        super.onMeasure(widthSpec, heightSpec)
    }
}

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
     * A segmented switch: options in a row, the chosen one filled in the accent colour. [fill] shares
     * the row's width equally. A tap restyles it in place, calls [onChoose], then vibrates (after, so
     * choosing a vibration strength plays the new one).
     */
    fun segmented(options: List<String>, chosen: Int, fill: Boolean = false, onChoose: (Int) -> Unit): LinearLayout {
        var current = chosen
        val views = mutableListOf<TextView>()
        fun style() = views.forEachIndexed { i, v ->
            val on = i == current
            v.setTextColor(if (on) p.accentInk else p.muted)
            v.background = if (on) shape(p.accent, 10f) else pressable(shape(Color.TRANSPARENT, 10f), 10f)
            v.contentDescription = "${options[i]}${if (on) ", chosen" else ""}"
        }
        return row(gap = 2f).apply {
            background = shape(p.surface2, 12f)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            for ((i, label) in options.withIndex()) {
                val v = text(label, 13f, p.muted, f.bodySemi).apply {
                    gravity = Gravity.CENTER
                    minHeight = dp(if (fill) 40 else 36)
                    setPadding(dp(12), 0, dp(12), 0)
                    setOnClickListener {
                        if (i == current) return@setOnClickListener
                        current = i
                        style()
                        onChoose(i)
                        Haptics.tick(this)
                    }
                }
                views += v
                addView(v, if (fill) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            style()
        }
    }

    /** A rounded pill, e.g. LECTURE / TODAY. */
    fun chip(value: String, fill: Int, color: Int, size: Float = 10.5f) = text(value, size, color, f.bodyBold, caps = true, spacing = 0.1f).apply {
        background = shape(fill, 99f)
        setPadding(dp(7), dp(2), dp(7), dp(2))
    }
}

fun hoursText(minutes: Int): String = (minutes / 60.0).let { if (it == Math.floor(it)) "${it.toInt()} h" else "${"%.1f".format(it)} h" }
