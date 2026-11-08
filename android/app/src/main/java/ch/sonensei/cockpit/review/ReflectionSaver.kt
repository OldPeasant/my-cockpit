package ch.sonensei.cockpit.review

import android.content.Context
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.journal.JournalEntry
import ch.sonensei.cockpit.journal.JournalRepository
import ch.sonensei.cockpit.journal.JournalResult
import ch.sonensei.cockpit.journal.JournalSaveResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** How saving the reflection ended. */
enum class ReflectionOutcome { SAVED, OFFLINE, FAILED }

/**
 * Saves the reflection of the review's last step in today's journal entry (SPEC.md §8.6 step 8), the same way the web
 * does: appended under a "Weekly review" heading, or as a new entry when the day has none.
 */
class ReflectionSaver internal constructor(
    private val journal: JournalRepository,
    private val heading: () -> String,
    private val clock: Clock,
    private val zone: () -> ZoneId,
) {
    @Inject
    constructor(
        journal: JournalRepository,
        @ApplicationContext context: Context,
    ) :
        this(journal, { context.getString(R.string.review_journal_heading) }, Clock.systemUTC(), ZoneId::systemDefault)

    /** Appends [text] to today's entry. An entry changed elsewhere meanwhile is read again and appended to once more. */
    suspend fun save(text: String): ReflectionOutcome {
        val date = LocalDate.now(clock.withZone(zone()))
        var base =
            when (val loaded = journal.get(date)) {
                is JournalResult.Ok -> loaded.value
                JournalResult.Offline -> return ReflectionOutcome.OFFLINE
                is JournalResult.Failed -> return ReflectionOutcome.FAILED
            }
        repeat(ATTEMPTS) {
            when (val saved = journal.save(date, base, appended(base, text))) {
                is JournalSaveResult.Saved -> return ReflectionOutcome.SAVED
                is JournalSaveResult.Conflict -> base = saved.current
                JournalSaveResult.Offline -> return ReflectionOutcome.OFFLINE
                is JournalSaveResult.Failed -> return ReflectionOutcome.FAILED
            }
        }
        return ReflectionOutcome.FAILED
    }

    private fun appended(
        base: JournalEntry?,
        text: String,
    ): String {
        val section = "## ${heading()}\n\n${text.trim()}"
        val before = base?.bodyMd?.trimEnd().orEmpty()
        return if (before.isEmpty()) "$section\n" else "$before\n\n$section\n"
    }

    private companion object {
        const val ATTEMPTS = 2
    }
}
