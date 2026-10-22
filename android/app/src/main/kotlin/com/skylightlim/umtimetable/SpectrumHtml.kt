package com.skylightlim.umtimetable

import java.net.URLDecoder

/**
 * Spectrum's HTML made ready for Android's renderer ([RichText]), which plays nothing, frames nothing and
 * has no tables, without losing a link a browser would offer:
 * - an audio or video player becomes a link to what it plays (a podcast on a course page), and an
 *   embedded page (iframe) a link to it;
 * - a table's rows become lines, their cells joined by " · " (Android ran a row's cells together);
 * - a web address typed into the text without a link ([bareLinks]) becomes one; in a browser it's only text.
 */
object SpectrumHtml {
    private val MEDIA = Regex("""(?is)<(audio|video)\b([^>]*)>(.*?)</\1\s*>""")
    private val FRAME = Regex("""(?is)<iframe\b([^>]*)>.*?</iframe\s*>|<iframe\b([^>]*)/?>""")
    private val SRC = Regex("""(?i)\bsrc\s*=\s*["']([^"']+)["']""")
    private val TITLE = Regex("""(?i)\btitle\s*=\s*["']([^"']+)["']""")
    private val CELL_BREAK = Regex("""(?is)</t[dh]\s*>\s*<t[dh]\b[^>]*>""")
    private val ROW_START = Regex("""(?i)<tr\b[^>]*>""")
    private val ROW_END = Regex("""(?i)</tr\s*>""")
    private val WEB = Regex("""(?i)https?://[^\s"'<>]+""")
    private val TAGS = Regex("<[^>]*>")
    private val BARE = Regex("""(?i)\bhttps?://[^\s<>"]+""")

    fun prepare(html: String): String {
        var s = MEDIA.replace(html) { m ->
            val src = SRC.find(m.groupValues[2])?.groupValues?.get(1) ?: SRC.find(m.groupValues[3])?.groupValues?.get(1) ?: return@replace ""
            val title = TITLE.find(m.groupValues[2])?.groupValues?.get(1) ?: fileName(src)
            "<p><a href=\"$src\">${if (m.groupValues[1].equals("audio", true)) "▶ Listen" else "▶ Watch"}: $title</a></p>"
        }
        s = FRAME.replace(s) { m ->
            val attrs = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val src = SRC.find(attrs)?.groupValues?.get(1) ?: return@replace ""
            "<p><a href=\"$src\">Open: ${TITLE.find(attrs)?.groupValues?.get(1) ?: src.substringAfter("://").substringBefore('/')}</a></p>"
        }
        s = CELL_BREAK.replace(s, " · ")
        s = ROW_START.replace(s, "<p>")
        return ROW_END.replace(s, "</p>")
    }

    /**
     * Where a link goes: the address as written (web, email or phone), or the web address inside a mistyped
     * one (a lecturer's "Join: https://teams…" goes nowhere in a browser); null when there's none.
     */
    fun address(href: String): String? {
        val h = href.trim()
        if (listOf("http://", "https://", "mailto:", "tel:").any { h.startsWith(it, ignoreCase = true) }) return h
        return WEB.find(h)?.value
    }

    /** Where the text has a web address of its own, not made a link (a WhatsApp group's, pasted in): each address's range. */
    fun bareLinks(text: CharSequence): List<IntRange> = BARE.findAll(text).map { m ->
        // A full stop or a bracket right after an address ends the sentence, not the address.
        var end = m.range.last
        while (end > m.range.first && text[end] in ".,;:!?)]}'\"") end--
        m.range.first..end
    }.toList()

    /** Only a dividing line (<hr>), nothing to read: a course page draws it as a line. */
    fun isRule(html: String): Boolean =
        html.contains("<hr", ignoreCase = true) && !html.contains("<img", ignoreCase = true) &&
            TAGS.replace(html, "").replace("&nbsp;", "").none { !it.isWhitespace() && it != '​' }

    private fun fileName(src: String) = runCatching { URLDecoder.decode(src.substringBefore('?').substringAfterLast('/'), "UTF-8") }.getOrDefault(src)
}
