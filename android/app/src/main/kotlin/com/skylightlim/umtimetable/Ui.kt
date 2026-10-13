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

/** The theme button: Auto follows the phone's dark mode; Light and Dark override it. */
enum class Look(val label: String, val icon: Int) {
    AUTO("Auto", R.drawable.ic_look_auto), LIGHT("Light", R.drawable.ic_look_light), DARK("Dark", R.drawable.ic_look_dark);

    val next get() = entries[(ordinal + 1) % entries.size]

    fun palette(context: Context): Palette = when (this) {
        LIGHT -> Palette.LIGHT
        DARK -> Palette.DARK
        AUTO -> if ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) Palette.DARK else Palette.LIGHT
    }

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

    /** A rounded pill, e.g. LECTURE / TODAY. */
    fun chip(value: String, fill: Int, color: Int, size: Float = 10.5f) = text(value, size, color, f.bodyBold, caps = true, spacing = 0.1f).apply {
        background = shape(fill, 99f)
        setPadding(dp(7), dp(2), dp(7), dp(2))
    }
}

fun hoursText(minutes: Int): String = (minutes / 60.0).let { if (it == Math.floor(it)) "${it.toInt()} h" else "${"%.1f".format(it)} h" }
