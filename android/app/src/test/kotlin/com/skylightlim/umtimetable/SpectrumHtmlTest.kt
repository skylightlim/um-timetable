package com.skylightlim.umtimetable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spectrum's HTML made ready for Android's renderer without losing a link. */
class SpectrumHtmlTest {
    @Test fun playersBecomeLinks() {
        val html = """<p><audio controls="true" title="Podcast: Week 3">
            <source src="https://spectrum.um.edu.my/pluginfile.php/1/mod_label/intro/week3.mp3">https://spectrum.um.edu.my/pluginfile.php/1/mod_label/intro/week3.mp3
            </audio></p><video controls><source src="https://example.com/Intro%20video.mp4"></video>"""
        val out = SpectrumHtml.prepare(html)
        assertTrue(out.contains("""<a href="https://spectrum.um.edu.my/pluginfile.php/1/mod_label/intro/week3.mp3">▶ Listen: Podcast: Week 3</a>"""))
        assertTrue(out.contains("""<a href="https://example.com/Intro%20video.mp4">▶ Watch: Intro video.mp4</a>"""))
        assertFalse(out.contains("<audio"))
    }

    @Test fun framesBecomeLinks() {
        val out = SpectrumHtml.prepare("""<iframe width="560" src="https://www.youtube.com/embed/abc" title="Lecture 1"></iframe><iframe src="https://forms.example.com/x"/>""")
        assertTrue(out.contains("""<a href="https://www.youtube.com/embed/abc">Open: Lecture 1</a>"""))
        assertTrue(out.contains("""<a href="https://forms.example.com/x">Open: forms.example.com</a>"""))
    }

    @Test fun tableRowsBecomeLines() {
        val out = SpectrumHtml.prepare("<table><tr><th>Class</th><th>Where</th></tr><tr><td>Lecture</td>\n<td>DK1</td></tr></table>")
        assertEquals("<table><p><th>Class · Where</th></p><p><td>Lecture · DK1</td></p></table>", out)
    }

    @Test fun addresses() {
        assertEquals("https://example.com/a", SpectrumHtml.address(" https://example.com/a "))
        assertEquals("mailto:lecturer@example.com", SpectrumHtml.address("mailto:lecturer@example.com"))
        assertEquals("tel:+60312345678", SpectrumHtml.address("tel:+60312345678"))
        // A lecturer pasted the label with the link: a browser goes nowhere, the app to the meeting.
        assertEquals("https://teams.microsoft.com/meet/123?p=abc", SpectrumHtml.address("Join: https://teams.microsoft.com/meet/123?p=abc"))
        assertNull(SpectrumHtml.address("#top"))
        assertNull(SpectrumHtml.address("javascript:void(0)"))
    }

    @Test fun bareAddressesBecomeLinks() {
        val text = "Occ 4: https://chat.example.com/AbC123 (join it). Notes at https://example.com/notes."
        assertEquals(listOf("https://chat.example.com/AbC123", "https://example.com/notes"), SpectrumHtml.bareLinks(text).map { text.substring(it.first, it.last + 1) })
        assertTrue(SpectrumHtml.bareLinks("No address here, only www-less text.").isEmpty())
    }

    @Test fun aLoneLineIsARule() {
        assertTrue(SpectrumHtml.isRule("""<div class="no-overflow"><hr></div>"""))
        assertTrue(SpectrumHtml.isRule("<p>&nbsp;</p><hr/>"))
        assertFalse(SpectrumHtml.isRule("<hr><p>Week 2</p>"))
        assertFalse(SpectrumHtml.isRule("<p>Notes</p>"))
    }
}
