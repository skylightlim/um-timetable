package com.skylightlim.umtimetable

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Spectrum HTML (announcements, course text) shown natively. Android's HTML renderer runs no
 * scripts; script/style blocks are dropped first so their text doesn't show, and players, frames and
 * tables are made into what it can show ([SpectrumHtml]). Links open in the app's Spectrum view, the
 * browser or the mail or phone app, Spectrum file links download, and Spectrum images load through the
 * native side with the token (cached on the phone).
 */
object RichText {
    private val DROP = Regex("""(?is)<(script|style|iframe|object|embed|noscript|template|svg|math|form|select|textarea|button|video|audio|canvas)\b.*?</\1\s*>""")
    private val BLANK_RUNS = Regex("""\n[\s​ ]*\n(?:[\s​ ]*\n)+""")
    private val IMG = Regex("(?i)<img")
    private val loader = Executors.newFixedThreadPool(3)

    /** Converted text without images, by HTML: pages are rebuilt on every visit, the HTML rarely changes. */
    private val texts = LruCache<String, CharSequence>(96)
    /** Decoded Spectrum images, so a rebuilt page shows them at once instead of popping them in. */
    private val bitmaps = object : LruCache<String, android.graphics.Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount / 1024
    }

    /**
     * Rich text, clamped to [lines] (0 for no limit) with a Show more toggle when it's long. [expanded] holds the open
     * keys. The toggle changes the text in place: rebuilding the page would lose the scroll position.
     * [selectable] text takes a long press to select and copy it (a link still opens on a tap).
     */
    @SuppressLint("ClickableViewAccessibility")
    fun view(h: Host, html: String, key: String, folder: String, lines: Int, size: Float = 14f, color: Int = h.ui.p.text, expanded: MutableSet<String>, selectable: Boolean = false): View {
        val ui = h.ui
        val tv = ui.text("", size, color, ui.f.body).apply { setLineSpacing(0f, 1.35f) }
        tv.setLinkTextColor(ui.p.accentText)
        if (selectable) selectable(tv)
        tv.text = spanned(h, tv, html, folder)
        // Links are handled by touch position, not LinkMovementMethod, which would break the ellipsis.
        tv.setOnTouchListener { _, e -> linkAt(tv, e)?.let { if (e.action == MotionEvent.ACTION_UP) it.onClick(tv); true } ?: false }
        // lines 0: the whole text, no Show more.
        val long = lines > 0 && (tv.text.length > lines * 55 || IMG.findAll(html).count() > 1)
        if (!long) return tv
        val more = ui.text("", 13f, ui.p.accentText, ui.f.bodySemi).apply {
            minHeight = ui.dp(48)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, ui.dp(16), 0)
        }
        fun show(open: Boolean) {
            tv.maxLines = if (open) Int.MAX_VALUE else lines
            tv.ellipsize = if (open) null else TextUtils.TruncateAt.END
            more.text = if (open) "Show less" else "Show more"
        }
        show(key in expanded)
        more.setOnClickListener {
            Motion.resize(more)
            show(expanded.add(key) || !expanded.remove(key))
        }
        return ui.column().apply {
            addView(tv)
            addView(more, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    /**
     * [tv]'s text selectable: a long press picks a word, with handles and Copy. A tap focuses it, and the
     * page mustn't scroll a tall block's top into view for that.
     */
    fun selectable(tv: TextView) {
        tv.setTextIsSelectable(true)
        tv.revealOnFocusHint = false
    }

    /** The HTML as styled text with working links. Text without images is converted once and reused. */
    fun spanned(h: Host, tv: TextView, html: String, folder: String): CharSequence {
        val images = IMG.containsMatchIn(html)
        if (!images) texts.get("$folder\u0000$html")?.let { return it }
        val source = Html.fromHtml(SpectrumHtml.prepare(html).replace(DROP, ""), Html.FROM_HTML_MODE_COMPACT, if (images) ImageGetter(h.ui.context, tv) else null, null)
        val text = SpannableStringBuilder(source)
        // An address typed in without a link becomes one, like those that were.
        val linked = text.getSpans(0, text.length, URLSpan::class.java).map { text.getSpanStart(it) until text.getSpanEnd(it) }
        for (r in SpectrumHtml.bareLinks(text)) {
            if (linked.none { r.first in it || r.last in it }) text.setSpan(URLSpan(text.substring(r.first, r.last + 1)), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        for (span in text.getSpans(0, text.length, URLSpan::class.java)) {
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            text.removeSpan(span)
            // Web, email and phone links; a mistyped one ("Join: https://…") goes to the address inside it.
            val url = SpectrumHtml.address(span.url) ?: continue
            // The span outlives this page (it's cached), so it finds the screen through the view it's in.
            text.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    val host = widget.context as? Host ?: return
                    if (url.startsWith("${Spectrum.SITE}/") && "/pluginfile.php/" in url) {
                        val name = Uri.decode(url.substringBefore('?').substringAfterLast('/')).ifBlank { "file" }
                        host.openFile(JSONObject().put("url", url).put("name", name).put("mime", "").put("size", 0), folder)
                    } else {
                        host.openUrl(url)
                    }
                }

                override fun updateDrawState(ds: TextPaint) {
                    ds.color = ds.linkColor
                    ds.isUnderlineText = true
                }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        // Spectrum's editor leaves empty paragraphs (&nbsp;, zero-width spaces) that read as big gaps.
        for (m in BLANK_RUNS.findAll(text.toString()).toList().asReversed()) text.replace(m.range.first, m.range.last + 1, "\n\n")
        while (text.isNotEmpty() && text[text.length - 1].isWhitespace()) text.delete(text.length - 1, text.length)
        while (text.isNotEmpty() && (text[0].isWhitespace() || text[0] == '​')) text.delete(0, 1)
        if (!images) texts.put("$folder\u0000$html", text)
        return text
    }

    private fun linkAt(tv: TextView, e: MotionEvent): ClickableSpan? {
        val spanned = tv.text as? Spanned ?: return null
        val layout = tv.layout ?: return null
        val x = e.x.toInt() - tv.totalPaddingLeft + tv.scrollX
        val y = e.y.toInt() - tv.totalPaddingTop + tv.scrollY
        val line = layout.getLineForVertical(y)
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null
        val offset = layout.getOffsetForHorizontal(line, x.toFloat())
        return spanned.getSpans(offset, offset, ClickableSpan::class.java).firstOrNull()
    }

    /** A placeholder the image is put into once it has loaded. */
    private class Slot : Drawable() {
        var inner: Drawable? = null
        override fun draw(canvas: Canvas) { inner?.draw(canvas) }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Required override") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private class ImageGetter(val context: Context, val tv: TextView) : Html.ImageGetter {
        override fun getDrawable(source: String?): Drawable {
            val slot = Slot().apply { setBounds(0, 0, 0, 0) }
            if (source == null || !source.startsWith("${Spectrum.SITE}/")) return slot
            // Already decoded: sized now, so the text is laid out once, with the image in place.
            bitmaps.get(source)?.let { fit(slot, it); return slot }
            loader.execute {
                val file = image(context, source) ?: return@execute
                val bitmap = BitmapFactory.decodeFile(file.path) ?: return@execute
                bitmaps.put(source, bitmap)
                tv.post {
                    fit(slot, bitmap)
                    tv.text = tv.text // lay the text out again around the image
                }
            }
            return slot
        }

        /** Scales [bitmap] down to the text's width (or, before layout, the screen's less the page margins). */
        private fun fit(slot: Slot, bitmap: android.graphics.Bitmap) {
            val max = (tv.width - tv.totalPaddingLeft - tv.totalPaddingRight).takeIf { it > 0 }
                ?: (context.resources.displayMetrics.widthPixels - (96 * context.resources.displayMetrics.density).toInt())
            val scale = minOf(1f, max.toFloat() / bitmap.width)
            val w = (bitmap.width * scale).toInt()
            val h = (bitmap.height * scale).toInt()
            slot.inner = BitmapDrawable(context.resources, bitmap).apply { setBounds(0, 0, w, h) }
            slot.setBounds(0, 0, w, h)
        }
    }

    /** A Spectrum image fetched with the token and kept in the app's cache. */
    fun image(context: Context, url: String): File? {
        val token = SpectrumStore.token(context) ?: return null
        val dir = File(context.cacheDir, "spectrum-img").apply { mkdirs() }
        val key = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(dir, key)
        if (file.exists()) return file
        val conn = URL(Downloads.authed(url, token)).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        return try {
            if (conn.responseCode !in 200..299 || conn.contentType?.startsWith("image/") != true) return null
            conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            file
        } catch (e: Exception) {
            file.delete()
            null
        } finally {
            conn.disconnect()
        }
    }
}
