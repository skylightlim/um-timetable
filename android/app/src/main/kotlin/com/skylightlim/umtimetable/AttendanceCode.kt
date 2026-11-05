package com.skylightlim.umtimetable

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What a lecturer's attendance QR code says: Spectrum's attendance plugin (mod_attendance) shows a link
 * to the session, `…/mod/attendance/attendance.php?qrpass=…&sessid=…`, where qrpass is the session's
 * password (a fixed one, or with "Rotate QR code" a new one every 15 seconds or so). Opening it signed in
 * marks the student at once, or shows the session's form with the password filled in. Auto attendance
 * reads one put on the session's page as a picture ([AutoAttendance]).
 *
 * Only the session and the password are taken from the code: [link] is always on Spectrum itself, so a
 * code made on another site never sends the app, or the user's sign-in, anywhere else.
 */
data class AttendanceCode(val sessid: Long, val qrpass: String?) {
    /** The session's link on Spectrum, as the code gives it. */
    fun link(site: String = Spectrum.SITE): String =
        "$site/mod/attendance/attendance.php?" + (qrpass?.let { "qrpass=${URLEncoder.encode(it, "UTF-8")}&" } ?: "") + "sessid=$sessid"

    companion object {
        /**
         * The attendance session [text] (a scanned QR code) links to, or null when it isn't one of UM's. Read
         * as a browser and Spectrum would: Moodle puts a lecturer's own password into the link as typed (a
         * space, say), so the link isn't always a valid URI; the query ends at a '#', and '+' is a space.
         */
        fun parse(text: String): AttendanceCode? {
            val m = LINK.matchEntire(text.trim()) ?: return null
            val (_, host, path, query) = m.destructured
            if (!hostIn(host, "um.edu.my") || !path.trimEnd('/').endsWith("/mod/attendance/attendance.php")) return null
            val params = query.substringBefore('#').split('&').filter { it.isNotEmpty() }.associate { part ->
                val (k, v) = part.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                decode(k) to decode(v)
            }
            val sessid = params["sessid"]?.takeIf { it.isNotEmpty() && it.length <= 12 && it.all { c -> c in '0'..'9' } }?.toLong() ?: return null
            return AttendanceCode(sessid, params["qrpass"]?.takeIf { it.isNotEmpty() })
        }

        private val LINK = Regex("""(https?)://([^/?#:\s]+)(?::\d+)?(/[^?#]*)\?(.*)""", RegexOption.IGNORE_CASE)

        private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    }
}

/**
 * Reads a QR code from a picture's pixels ([argb], row after row): a lecturer's upload of the session's
 * code, or a screenshot with the code somewhere in it. A plain code on white reads as a "pure" one when a
 * photo-style look misses it, and a light-on-dark one is tried inverted.
 */
object QrDecoder {
    private val formats = listOf(BarcodeFormat.QR_CODE)
    private val looks = listOf(
        mapOf(DecodeHintType.POSSIBLE_FORMATS to formats, DecodeHintType.TRY_HARDER to true),
        mapOf(DecodeHintType.POSSIBLE_FORMATS to formats, DecodeHintType.PURE_BARCODE to true),
    )

    /** The code's text, or null when the picture has none that reads. */
    fun decode(argb: IntArray, width: Int, height: Int): String? {
        val picture = RGBLuminanceSource(width, height, argb)
        for (source in listOf(picture, picture.invert())) for (hints in looks) {
            try {
                return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
            } catch (e: ReaderException) {
                // not with this look
            }
        }
        return null
    }
}
