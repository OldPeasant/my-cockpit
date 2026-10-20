package ch.sonensei.cockpit.capture.queue

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Pins the relative due tokens of the capture syntax (SPEC.md §4.5) to the capture day. The server resolves `!today` and
 * `!tomorrow` with its own clock when the request arrives, so a capture queued offline at 23:30 and sent the next
 * morning would otherwise be due a day late. The token rules mirror the server's `CaptureParser`: only the title line
 * (the first non-blank line) counts, a token starts at the beginning of the line or after whitespace and runs to the
 * next whitespace, trailing punctuation (`.,;:!?)`) is not part of it, values are case-insensitive, and only the first
 * recognized due token (`!today`, `!tomorrow` or a valid `!YYYY-MM-DD`) sets the due date, later ones stay text.
 */
internal object RelativeDueDates {
    private val TOKEN = Regex("""(^|\s)([#@!^])(\S+)""")
    private val LINE = Regex("""[^\r\n]+""")
    private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private const val TRAILING_PUNCTUATION = ".,;:!?)"

    /** [text] with its first recognized due token rewritten to `!YYYY-MM-DD` if it is `!today` or `!tomorrow`. */
    fun pin(
        text: String,
        today: LocalDate,
    ): String {
        val line = LINE.findAll(text).firstOrNull { it.value.isNotBlank() } ?: return text
        for (match in TOKEN.findAll(line.value)) {
            if (match.groupValues[2] != "!") continue
            val token = match.groups[3]!!
            val value = token.value.trimEnd { it in TRAILING_PUNCTUATION }
            if (value.isEmpty()) continue
            val date =
                when (value.lowercase()) {
                    "today" -> today
                    "tomorrow" -> today.plusDays(1)
                    else -> if (isDate(value)) return text else continue
                }
            val start = line.range.first + token.range.first
            return text.replaceRange(start, start + value.length, date.toString())
        }
        return text
    }

    private fun isDate(value: String): Boolean =
        value.matches(ISO_DATE) &&
            try {
                LocalDate.parse(value)
                true
            } catch (_: DateTimeParseException) {
                false
            }
}
