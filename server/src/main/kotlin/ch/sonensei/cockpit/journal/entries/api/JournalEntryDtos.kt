package ch.sonensei.cockpit.journal.entries.api

import ch.sonensei.cockpit.files.domain.FileItem
import ch.sonensei.cockpit.journal.entries.domain.JournalDay
import ch.sonensei.cockpit.journal.entries.domain.JournalEntry
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Schema(description = "The journal entry of one date. `version` is also sent as the ETag.")
data class JournalEntryResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val id: UUID,
	@get:Schema(description = "The local date of the entry; one entry per date", requiredMode = RequiredMode.REQUIRED)
	val date: LocalDate,
	@get:Schema(description = "`Journal {date}`", requiredMode = RequiredMode.REQUIRED) val displayTitle: String,
	@get:Schema(
		description = "Markdown. Inline links (type:id in double square brackets) are synced to the links; images point to cockpit-file:{fileId}",
		requiredMode = RequiredMode.REQUIRED,
	)
	val bodyMd: String,
	@get:Schema(description = "1 (bad) to 5 (great); `null` when not set", minimum = "1", maximum = "5") val mood: Int?,
	@get:Schema(description = "Ordered regardless of case", requiredMode = RequiredMode.REQUIRED) val tags: List<String>,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val createdAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val updatedAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val version: Long,
) {
	companion object {
		fun of(entry: JournalEntry) =
			JournalEntryResponse(
				id = entry.id,
				date = entry.date,
				displayTitle = entry.displayTitle,
				bodyMd = entry.bodyMd,
				mood = entry.mood,
				tags = entry.tags.sortedWith(String.CASE_INSENSITIVE_ORDER.thenBy { it }),
				createdAt = checkNotNull(entry.createdAt),
				updatedAt = checkNotNull(entry.updatedAt),
				version = entry.version,
			)
	}
}

@Schema(description = "One page of journal entries, newest date first")
data class JournalEntryPageResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val items: List<JournalEntryResponse>,
	@get:Schema(description = "Cursor of the next page; `null` on the last page") val nextCursor: String?,
)

/** The body of a PUT (documentation and type conversion). */
@Schema(description = "The complete content of a journal entry; omitted fields get their defaults (empty body, no mood, no tags)")
data class JournalEntryRequest(
	@get:Schema(description = "Markdown (default empty)", maxLength = 100_000) val bodyMd: String? = null,
	@get:Schema(types = ["integer", "null"], description = "1 (bad) to 5 (great); `null` or omitted: no mood", minimum = "1", maximum = "5")
	val mood: Int? = null,
	@get:Schema(description = "Trimmed, a leading `#` removed, each once regardless of case (at most 50, 50 characters each)")
	val tags: List<String?>? = null,
) {
	companion object {
		val FIELDS = setOf("bodyMd", "mood", "tags")
	}
}

@Schema(description = "A date with a journal entry")
data class JournalDayResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val date: LocalDate,
	@get:Schema(description = "The entry's mood, 1 to 5; `null` when not set") val mood: Int?,
) {
	companion object {
		fun of(day: JournalDay) = JournalDayResponse(day.date, day.mood)
	}
}

@Schema(description = "The dates with an entry from `from` to `to` (both included), for the calendar heatmap")
data class JournalHeatmapResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val from: LocalDate,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val to: LocalDate,
	@get:Schema(description = "By date; dates without an entry are left out", requiredMode = RequiredMode.REQUIRED)
	val items: List<JournalDayResponse>,
)

@Schema(description = "The entries of the same day in earlier years")
data class JournalOnThisDayResponse(
	@get:Schema(description = "The day asked for (default today)", requiredMode = RequiredMode.REQUIRED) val date: LocalDate,
	@get:Schema(description = "Newest first", requiredMode = RequiredMode.REQUIRED) val items: List<JournalEntryResponse>,
)

@Schema(description = "An image stored for the journal in `/Attachments/Journal`")
data class JournalImageResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val fileId: UUID,
	@get:Schema(description = "`cockpit-file:{fileId}`", requiredMode = RequiredMode.REQUIRED) val reference: String,
	@get:Schema(description = "A Markdown image of cockpit-file:{fileId}, to insert into the body", requiredMode = RequiredMode.REQUIRED)
	val markdown: String,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val name: String,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val mime: String,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val size: Long,
) {
	companion object {
		fun of(file: FileItem): JournalImageResponse {
			val reference = "cockpit-file:${file.id}"
			return JournalImageResponse(file.id, reference, "![]($reference)", file.name, file.mime, file.size)
		}
	}
}
