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

    @Test fun readsACodeFromACameraFrame() {
        val text = "$site/mod/attendance/attendance.php?qrpass=aB3dE6&sessid=48213"
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 240, 240, mapOf(EncodeHintType.MARGIN to 2))
        // A 640 × 480 frame, its rows padded to 704 bytes as camera frames often are; the code a little off centre on grey.
        val width = 640
        val height = 480
        val stride = 704
        val y = ByteArray(stride * height) { 0x80.toByte() }
        for (row in 0 until matrix.height) for (col in 0 until matrix.width) {
            y[(row + 150) * stride + col + 260] = if (matrix[col, row]) 0x10 else 0xF0.toByte()
        }
        val decoder = QrDecoder()
        assertEquals(text, decoder.decode(y, stride, width, height))
        // And again: the harder look on every other frame reads it too.
        assertEquals(text, decoder.decode(y, stride, width, height))
        assertNull(decoder.decode(ByteArray(stride * height) { 0x80.toByte() }, stride, width, height))
    }
}
