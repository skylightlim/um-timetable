package com.skylightlim.umtimetable

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Auto attendance's log ([AttendanceLog]) read back as how each class went: marked, not marked and why,
 * still being tried, nothing to mark, or not tried at all. The log page's Classes tab lists the classes this
 * way ([rows]), and Day's card for the class in progress says it in a line ([now]).
 */
object AttendanceHistory {
    enum class Kind { MARKED, NOT_MARKED, TRYING, NOTHING, NOT_TRIED }

    /**
     * How one class went: [text] in a sentence (the log page), [short] in a line (Day's card). [asleep]: an
     * alarm for it never came while the phone was on, so the phone's battery setting held the app back.
     */
    data class Outcome(val kind: Kind, val text: String, val short: String, val asleep: Boolean = false) {
        /** It should have been marked and wasn't. */
        val missed get() = kind == Kind.NOT_MARKED || kind == Kind.NOT_TRIED
    }

    /** One class on one day: when it started (minutes after midnight), its course and type as the log writes them, and how it went. */
    data class Row(val date: LocalDate, val start: Int, val code: String, val type: String, val outcome: Outcome)

    /** What the log calls a class: "WIX1001 lecture 08:00", as [AutoAttendance] writes it. */
    fun what(s: Session) = "${s.course.code} ${s.type.lowercase()} ${clockText(s.start)}"

    /** Why a class wasn't marked, from the marking script's error: for the log page and the notification. */
    fun why(error: String) = when (error) {
        "notopen", "closed" -> "No attendance session opened for it while the app kept trying."
        "password" -> "The session needs your lecturer's password."
        "qr" -> "The session needs its QR code scanned in class."
        "network" -> "Spectrum only takes this session from UM's network: join the campus Wi-Fi."
        "nostatus" -> "Spectrum offered no status to choose."
        "signedout" -> "Spectrum wants you to sign in again."
        "blocked" -> "Android blocked the app's mobile data in the background."
        "unreachable" -> "Couldn't reach Spectrum."
        else -> "Spectrum didn't confirm it."
    }

    /** The same in a few words, for a line. */
    private fun brief(error: String) = when (error) {
        "notopen", "closed" -> "no session open"
        "password" -> "needs the lecturer's password"
        "qr" -> "needs the QR code"
        "network" -> "campus Wi-Fi only"
        "nostatus" -> "no status to choose"
        "signedout" -> "sign in to Spectrum again"
        "blocked" -> "mobile data blocked"
        "unreachable" -> "Spectrum unreachable"
        else -> "not confirmed"
    }

    /** A class's step in the log: "WIX1001 lecture 08:00: {…}" (its course code, type and start, then what happened). */
    private val STEP = Regex("""^([A-Z]{2,5}\d{3,5}[A-Z]?) (.+?) (\d\d):(\d\d): (.*)$""", RegexOption.DOT_MATCHES_ALL)
    private val AGAIN = Regex("""^(\{.*\})(?:; trying again at (\d\d:\d\d))?$""", RegexOption.DOT_MATCHES_ALL)

    private class Step(val time: LocalDateTime, val code: String, val type: String, val start: Int, val text: String)

    /** An alarm that never came, as [AutoAttendance] logs it: "missed: the 08:02 alarm never came; …". */
    private val MISSED = Regex("""^missed: the (\d\d):(\d\d) alarm never came( ${Regex.escape(AutoAttendance.RESTARTED)})?""")

    /** An alarm due at [at] (minutes after midnight) that never came; [restarted]: the phone was off or restarting then. */
    private class Missed(val time: LocalDateTime, val at: Int, val restarted: Boolean)

    private fun missed(entries: List<AttendanceLog.Entry>): List<Missed> = entries.mapNotNull { e ->
        val m = MISSED.find(e.text) ?: return@mapNotNull null
        Missed(e.time, m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt(), m.groupValues[3].isNotEmpty())
    }

    /** What a missed alarm means, in words: "the phone didn't wake the app at 08:02". */
    private fun slept(m: Missed) =
        if (m.restarted) "the phone was off or restarting at ${clockText(m.at)}" else "the phone didn't wake the app at ${clockText(m.at)}"

    /** The same for a line on Day's card. */
    private fun sleptShort(m: Missed) = if (m.restarted) "the phone was off" else "the phone didn't wake the app"

    private fun steps(entries: List<AttendanceLog.Entry>): List<Step> = entries.mapNotNull { e ->
        val m = STEP.matchEntire(e.text) ?: return@mapNotNull null
        Step(e.time, m.groupValues[1], m.groupValues[2], m.groupValues[3].toInt() * 60 + m.groupValues[4].toInt(), m.groupValues[5])
    }

    private fun hm(t: LocalDateTime) = clockText(t.hour * 60 + t.minute)

    /**
     * How a class went from its [steps] in the log, oldest first; [ended]: the class is over, so no try is
     * still to come. [missed]: the alarms for its tries that never came, which say why a try didn't happen.
     */
    private fun outcome(steps: List<Step>, ended: Boolean, missed: List<Missed> = emptyList()): Outcome {
        val last = steps.lastOrNull()
        if (last == null) {
            val m = missed.firstOrNull()
            return when {
                m != null && ended -> Outcome(
                    Kind.NOT_TRIED, "Not marked: ${slept(m)} to mark it." + fix(m), "Attendance not marked · ${sleptShort(m)}", asleep = !m.restarted,
                )
                m != null -> Outcome(
                    Kind.TRYING, "Not marked yet: ${slept(m)}. It tries again when the app next runs.",
                    "Attendance not marked yet · ${sleptShort(m)}", asleep = !m.restarted,
                )
                ended -> Outcome(Kind.NOT_TRIED, "Not marked. The app didn't try to mark it.", "Attendance not marked")
                else -> Outcome(Kind.TRYING, "Not marked yet.", "Attendance not marked yet")
            }
        }
        // A try that was to come after the last one, and didn't.
        val lost = missed.lastOrNull { it.time.isAfter(last.time) }
        val text = last.text
        if (text.startsWith("no attendance activity")) {
            return if (ended || text.startsWith("no attendance activity for this class, last try")) {
                Outcome(Kind.NOTHING, "Nothing to mark: Spectrum has no attendance session for it.", "No attendance to mark for this class")
            } else Outcome(Kind.TRYING, "No attendance session on Spectrum yet: the app keeps looking.", "No attendance session on Spectrum yet")
        }
        val m = AGAIN.matchEntire(text)
        val answer = m?.let { runCatching { JSONObject(it.groupValues[1]) }.getOrNull() }
        if (answer == null) {
            // Opened, with no answer after it (the run cut short).
            return if (ended) Outcome(Kind.NOT_MARKED, "Not marked. The try at ${hm(last.time)} got no answer.", "Attendance not marked")
            else Outcome(Kind.TRYING, "Marking it now.", "Marking attendance…")
        }
        if (answer.optBoolean("ok")) {
            if (answer.optBoolean("already")) return Outcome(Kind.MARKED, "Already marked, by you or your lecturer.", "Attendance already marked")
            val status = answer.optString("status").ifBlank { "Present" }
            val how = if (answer.has("first")) ", with the QR code on its page" else ""
            return Outcome(Kind.MARKED, "Marked ${status.lowercase()} at ${hm(last.time)}$how.", "Attendance marked · $status at ${hm(last.time)}")
        }
        val error = answer.optString("error")
        val again = m.groupValues[2].takeIf { it.isNotEmpty() }
        return when {
            again == null -> Outcome(Kind.NOT_MARKED, "Not marked. ${why(error)}", "Attendance not marked · ${brief(error)}")
            ended && lost != null -> Outcome(
                Kind.NOT_MARKED, "Not marked. Last tried at ${hm(last.time)} (${brief(error)}), then ${slept(lost)} to try again." + fix(lost),
                "Attendance not marked · ${brief(error)}", asleep = !lost.restarted,
            )
            ended -> Outcome(Kind.NOT_MARKED, "Not marked. Last tried at ${hm(last.time)} (${brief(error)}), and no try came after it.", "Attendance not marked · ${brief(error)}")
            lost != null -> Outcome(
                Kind.TRYING, "Not marked yet (${brief(error)}), and ${slept(lost)} to try again. It tries again when the app next runs.",
                "Attendance not marked yet · ${sleptShort(lost)}", asleep = !lost.restarted,
            )
            else -> Outcome(Kind.TRYING, "Not marked yet (${brief(error)}). Trying again at $again.", "Attendance not marked yet · trying again at $again")
        }
    }

    /** What to do about a phone that didn't wake the app; nothing for one that was off. */
    private fun fix(m: Missed) = if (m.restarted) "" else " Set the app's battery use to Unrestricted."

    /** The missed alarms in [list] for a class from [start] to [end] (minutes after midnight). */
    private fun during(list: List<Missed>, start: Int, end: Int) = list.filter { it.at in start until end }

    /** Whether auto attendance was on at [t], by the log's "turned on" and "turned off"; a log begun while it was on, or cut short since, starts on. */
    private fun onAt(entries: List<AttendanceLog.Entry>, t: LocalDateTime): Boolean {
        val turns = entries.filter { it.text == "turned on" || it.text == "turned off" }
        turns.lastOrNull { !it.time.isAfter(t) }?.let { return it.text == "turned on" }
        return turns.firstOrNull()?.text != "turned on"
    }

    /**
     * The classes auto attendance had to mark in the [days] up to [now] (since the log begins, while it was
     * on), and how each went: newest day first, each day's in time order. [classesOn] gives a day's classes to
     * mark ([Timetable.attendanceDay], none on a holiday or in a week without classes); a class the log has
     * that day but the timetable no longer does is listed too. Today's are those started.
     */
    fun rows(entries: List<AttendanceLog.Entry>, now: LocalDateTime, days: Int, classesOn: (LocalDate) -> List<Session>): List<Row> {
        if (entries.isEmpty()) return emptyList()
        val steps = steps(entries).groupBy { it.time.toLocalDate() }
        val missed = missed(entries).groupBy { it.time.toLocalDate() }
        val today = now.toLocalDate()
        val from = maxOf(entries.first().time.toLocalDate(), today.minusDays(days - 1L))
        val out = mutableListOf<Row>()
        var date = today
        while (!date.isBefore(from)) {
            val logged = steps[date].orEmpty().groupBy { Triple(it.code, it.type, it.start) }
            val classes = classesOn(date).filter { date.atStartOfDay().plusMinutes(it.start.toLong()).let { t -> !t.isAfter(now) && onAt(entries, t) } }
            val keys = (classes.map { Triple(it.course.code, it.type.lowercase(), it.start) to it.end } +
                logged.keys.map { it to null }).distinctBy { it.first }
            val nowMin = now.hour * 60 + now.minute
            out += keys.sortedBy { it.first.third }.map { (k, end) ->
                // A class the timetable no longer has is taken to last two hours.
                val ended = date < today || nowMin >= (end ?: (k.third + 120))
                Row(date, k.third, k.first, k.second, outcome(logged[k].orEmpty(), ended, during(missed[date].orEmpty(), k.third, end ?: (k.third + 120))))
            }
            date = date.minusDays(1)
        }
        return out
    }

    /** [rows] from the app's log and timetable. */
    fun rows(c: Context, days: Int): List<Row> {
        val tt = Timetable.load(c)
        return rows(AttendanceLog.read(c), LocalDateTime.now(), days) { d ->
            if (Holidays.on(d) != null || weekOff(d) != null) emptyList() else tt.attendanceDay(d.dayOfWeek.value % 7)
        }
    }

    /**
     * How the class [s] in progress is going today, for its card on Day; null when there's nothing to say
     * (auto attendance off or signed out). Found by its course and start, or else by its course alone (a
     * course whose occurrence the user changed is marked at Spectrum's class).
     */
    fun now(c: Context, s: Session): Outcome? {
        if (!AutoAttendance.enabled(c) || !SpectrumStore.signedIn(c)) return null
        val today = LocalDate.now()
        val entries = AttendanceLog.read(c)
        val todays = steps(entries).filter { it.time.toLocalDate() == today && it.code == s.course.code }
        val own = todays.filter { it.start == s.start }.ifEmpty { todays }
        return outcome(own, ended = false, during(missed(entries).filter { it.time.toLocalDate() == today }, s.start, s.end))
    }
}
