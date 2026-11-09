package ch.sonensei.cockpit.journal

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** Text helpers of the journal, the same rules as the web app's (`journal-dates.ts`). */
internal object JournalText {
    private val IMAGE = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val ENTITY_LINK = Regex("""\[\[[A-Za-z_]+:[0-9A-Fa-f-]{36}]]""")
    private val LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
    private val LINE_MARKS = Regex("""^\s{0,3}(#{1,6}\s+|[-*+]\s+(\[[ xX]]\s+)?|>\s?|\d+[.)]\s+)""", RegexOption.MULTILINE)
    private val EMPHASIS = Regex("""[*_`~]+""")
    private val SPACE = Regex("""\s+""")

    /** The start of a Markdown body as plain text (at most [length] characters), for a preview line. */
    fun excerpt(
        bodyMd: String,
        length: Int = 160,
    ): String {
        val text =
            bodyMd
                .replace(IMAGE, "")
                .replace(ENTITY_LINK, "")
                .replace(LINK, "$1")
                .replace(LINE_MARKS, "")
                .replace(EMPHASIS, "")
                .replace(SPACE, " ")
                .trim()
        return if (text.length > length) text.take(length - 1).trimEnd() + "…" else text
    }

    /**
     * The weeks of [month] starting on [firstDay], each seven days; days outside the month are `null`, so the
     * first and last weeks are padded.
     */
    fun monthWeeks(
        month: YearMonth,
        firstDay: DayOfWeek,
    ): List<List<LocalDate?>> {
        val lead = (month.atDay(1).dayOfWeek.value - firstDay.value + DAYS) % DAYS
        val days: List<LocalDate?> = List(lead) { null } + (1..month.lengthOfMonth()).map(month::atDay)
        return days.chunked(DAYS).map { week -> week + List(DAYS - week.size) { null } }
    }

    private const val DAYS = 7
}
