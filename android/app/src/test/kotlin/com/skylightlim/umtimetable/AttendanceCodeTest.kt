package com.skylightlim.umtimetable

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading the lecturer's attendance QR codes: what's taken from them, and where the app then goes. */
class AttendanceCodeTest {
    private val site = "https://spectrum.um.edu.my"

    @Test fun rotatingCode() {
        // As mod_attendance's rotating code builds it: the page's own address, then qrpass and sessid.
        val code = AttendanceCode.parse("$site/mod/attendance/attendance.php?qrpass=aB3dE6&sessid=48213")
        assertEquals(AttendanceCode(48213, "aB3dE6"), code)
        assertEquals("$site/mod/attendance/attendance.php?qrpass=aB3dE6&sessid=48213", code!!.link())
    }

    @Test fun codeWithoutPassword() {
        assertEquals(AttendanceCode(48213, null), AttendanceCode.parse("$site/mod/attendance/attendance.php?sessid=48213"))
        assertEquals("$site/mod/attendance/attendance.php?sessid=48213", AttendanceCode(48213, null).link())
    }

    @Test fun lecturersPasswordAsTyped() {
        // Moodle puts a fixed password into the link unescaped: a space, or a '+', which Spectrum reads as a space.
        assertEquals(AttendanceCode(7, "Kelas 2"), AttendanceCode.parse("$site/mod/attendance/attendance.php?qrpass=Kelas 2&sessid=7"))
        assertEquals(AttendanceCode(7, "Kelas 2"), AttendanceCode.parse("$site/mod/attendance/attendance.php?qrpass=Kelas+2&sessid=7"))
        assertEquals(AttendanceCode(7, "a&b"), AttendanceCode.parse("$site/mod/attendance/attendance.php?qrpass=a%26b&sessid=7"))
        // Sent on escaped, so Spectrum reads the same password back.
        assertEquals("$site/mod/attendance/attendance.php?qrpass=Kelas+2&sessid=7", AttendanceCode(7, "Kelas 2").link())
        assertEquals("$site/mod/attendance/attendance.php?qrpass=a%26b&sessid=7", AttendanceCode(7, "a&b").link())
    }

    @Test fun anyOrderAndSpacing() {
        assertEquals(AttendanceCode(9, "x"), AttendanceCode.parse("  HTTPS://Spectrum.UM.edu.my/mod/attendance/attendance.php?sessid=9&qrpass=x#top\n"))
        assertEquals(AttendanceCode(9, "x"), AttendanceCode.parse("http://spectrum.um.edu.my:443/mod/attendance/attendance.php?sessid=9&qrpass=x"))
    }

    @Test fun onlyUmsAttendanceLinks() {
        // Another site's code never sends the user's sign-in anywhere: the app only ever opens Spectrum.
        assertNull(AttendanceCode.parse("https://moodle.example.edu/mod/attendance/attendance.php?qrpass=a&sessid=1"))
        assertNull(AttendanceCode.parse("https://spectrum.um.edu.my.example.com/mod/attendance/attendance.php?sessid=1"))
        assertNull(AttendanceCode.parse("https://evilum.edu.my/mod/attendance/attendance.php?sessid=1"))
        assertNull(AttendanceCode.parse("$site/mod/attendance/view.php?id=1"))
        assertNull(AttendanceCode.parse("$site/mod/attendance/attendance.php?qrpass=a"))
        assertNull(AttendanceCode.parse("$site/mod/attendance/attendance.php?sessid=12a"))
        assertNull(AttendanceCode.parse("$site/mod/attendance/attendance.php?sessid=1234567890123"))
        assertNull(AttendanceCode.parse("javascript:alert(1)"))
        assertNull(AttendanceCode.parse("WIFI:S:UM-WiFi;T:WPA;P:secret;;"))
        assertNull(AttendanceCode.parse(""))
    }

    /** [text] as a QR code drawn into an ARGB picture of [width] × [height] on [ground], [module] px a square, its corner at x, y. */
    private fun picture(text: String, width: Int, height: Int, x: Int, y: Int, module: Int, ground: Int = WHITE, ink: Int = BLACK): IntArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
        val px = IntArray(width * height) { ground }
        for (row in 0 until matrix.height) for (col in 0 until matrix.width) if (matrix[col, row]) {
            for (dy in 0 until module) for (dx in 0 until module) px[(y + row * module + dy) * width + x + col * module + dx] = ink
        }
        return px
    }

    private val link = "$site/mod/attendance/attendance.php?qrpass=Kelas2&sessid=48213"

    @Test fun readsALecturersUploadedCode() {
        // The code alone, as uploaded to the session's description: 8 px a square (41 squares, its margin too).
        val px = picture(link, 400, 400, 0, 0, 8)
        assertEquals(link, QrDecoder.decode(px, 400, 400))
        assertEquals(AttendanceCode(48213, "Kelas2"), AttendanceCode.parse(QrDecoder.decode(px, 400, 400)!!))
    }

    @Test fun readsACodeOnASlide() {
        // A screenshot of a slide: a grey page with a title bar, the code small in a corner.
        val w = 1280
        val h = 720
        val px = picture(link, w, h, 900, 380, 3, ground = GREY)
        for (y in 40 until 120) for (x in 60 until 1100) px[y * w + x] = BLACK
        assertEquals(link, QrDecoder.decode(px, w, h))
    }

    @Test fun readsALightCodeOnDark() {
        val px = picture(link, 400, 400, 0, 0, 8, ground = BLACK, ink = WHITE)
        assertEquals(link, QrDecoder.decode(px, 400, 400))
    }

    @Test fun aPictureWithoutACode() {
        val px = IntArray(400 * 300) { i -> if ((i / 400 / 20 + i % 400 / 20) % 2 == 0) WHITE else BLACK }
        assertNull(QrDecoder.decode(px, 400, 300))
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val GREY = 0xFFD8D8D8.toInt()
    }
}
