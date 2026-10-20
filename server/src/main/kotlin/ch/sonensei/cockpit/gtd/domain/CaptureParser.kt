package ch.sonensei.cockpit.gtd.domain

import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/** A project the capture syntax may name, see [CaptureParser]. */
data class CaptureProject(
	val id: UUID,
	val title: String,
)

/** A context the capture syntax may name, see [CaptureParser]. */
data class CaptureContext(
	val id: UUID,
	val name: String,
)

/**
 * The result of [CaptureParser.parse]: what a capture creates. [title] is the first non-blank line of the text without
 * the recognized tokens, [extraNotes] the rest of the text as it is (`null` when there is none). [list] is
 * [TaskList.WAITING] when the title line has a `^` token, else [TaskList.INBOX].
 */
data class ParsedCapture(
	val title: String,
	val extraNotes: String? = null,
	val list: TaskList = TaskList.INBOX,
	val project: CaptureProject? = null,
	val contexts: List<CaptureContext> = emptyList(),
	val dueDate: LocalDate? = null,
	val waitingFor: String? = null,
)

/**
 * The light syntax of quick capture (SPEC.md §4.5), as a pure function. Only the title line (the first non-blank line)
 * is parsed; the lines after it are notes and stay exactly as they are, tokens included. A token starts at the
 * beginning of the line or after whitespace and runs to the next whitespace; trailing punctuation (`.,;:!?)`) is not
 * part of its value and stays in the text:
 * - `#project`: an existing project by title, case-insensitive; `-` and `_` stand for spaces (`#home-renovation`).
 * - `@context`: an existing context by name (with or without its leading `@`), case-insensitive.
 * - `!today`, `!tomorrow`, `!YYYY-MM-DD`: the due date.
 * - `^waiting`: the task goes to the WAITING list; `^name` also sets "waiting for" to `name`.
 *
 * Recognized tokens are removed from the title; tokens that name nothing known (an unknown project or context, an
 * invalid date) stay as text. The first `#`, `!` and `^` token that is recognized wins (later ones stay as text);
 * contexts accumulate. Text inside words (`a#b`, `me@example.com`, URLs with `#anchor`) is never a token. When the
 * title line consists of recognized tokens only, the next non-blank line (not parsed) is the title.
 */
object CaptureParser {
	/** Titles longer than this are cut; the full first line then goes to [ParsedCapture.extraNotes]. */
	const val MAX_TITLE_LENGTH = TaskService.MAX_TITLE_LENGTH

	/** A token (sigil in group 2, rest in group 3) with the whitespace before it (group 1). */
	private val TOKEN = Regex("""(^|\s)([#@!^])(\S+)""")
	private val SPACES = Regex("""[ \t]+""")
	private const val TRAILING_PUNCTUATION = ".,;:!?)"
	private const val WAITING_KEYWORD = "waiting"

	/**
	 * Parses [text]. [projects] are the projects `#` may name, in order of preference (the first match wins);
	 * [contexts] the contexts `@` may name. [today] is the user's current date, for `!today` and `!tomorrow`.
	 */
	fun parse(
		text: String,
		today: LocalDate,
		projects: List<CaptureProject>,
		contexts: List<CaptureContext>,
	): ParsedCapture {
		var project: CaptureProject? = null
		var dueDate: LocalDate? = null
		var waiting = false
		var waitingFor: String? = null
		val foundContexts = linkedMapOf<UUID, CaptureContext>()

		val (titleLine, rest) = splitLines(text)
		val cleaned =
			TOKEN.replace(titleLine) { match ->
				val sigil = match.groupValues[2]
				val token = match.groupValues[3]
				val value = token.trimEnd { it in TRAILING_PUNCTUATION }
				val recognized =
					value.isNotEmpty() &&
						when (sigil) {
							"#" -> project == null && findProject(value, projects)?.also { project = it } != null
							"@" -> findContext(value, contexts)?.also { foundContexts.putIfAbsent(it.id, it) } != null
							"!" -> dueDate == null && parseDue(value, today)?.also { dueDate = it } != null
							else -> {
								val first = !waiting
								if (first) {
									waiting = true
									if (!value.equals(WAITING_KEYWORD, ignoreCase = true)) waitingFor = value
								}
								first
							}
						}
				val punctuation = token.removePrefix(value)
				when {
					!recognized -> match.value
					// The punctuation stays and takes the token's place: "Call @phone, then" -> "Call, then".
					punctuation.isNotEmpty() -> punctuation
					else -> match.groupValues[1]
				}
			}

		var title = normalizeLine(cleaned)
		var notes = rest
		if (title.isEmpty() && cleaned != titleLine) {
			val (nextLine, nextRest) = splitLines(rest)
			title = normalizeLine(nextLine)
			notes = nextRest
		}
		val (cut, extraNotes) = cutTitle(title, notes)
		return ParsedCapture(
			title = cut,
			extraNotes = extraNotes,
			list = if (waiting) TaskList.WAITING else TaskList.INBOX,
			project = project,
			contexts = foundContexts.values.toList(),
			dueDate = dueDate,
			waitingFor = waitingFor,
		)
	}

	/** [text] as it is, without parsing: only split into title and extra notes. */
	fun plain(text: String): ParsedCapture {
		val (titleLine, rest) = splitLines(text)
		val (title, extraNotes) = cutTitle(normalizeLine(titleLine), rest)
		return ParsedCapture(title, extraNotes)
	}

	/**
	 * The first non-blank line of [text] and the lines after it, verbatim except that leading and trailing blank lines
	 * are dropped (`""` when nothing is left). Line breaks become `\n`.
	 */
	private fun splitLines(text: String): Pair<String, String> {
		val lines = text.lines()
		val first = lines.indexOfFirst { it.isNotBlank() }
		if (first < 0) return "" to ""
		val rest =
			lines
				.drop(first + 1)
				.dropWhile { it.isBlank() }
				.dropLastWhile { it.isBlank() }
		return lines[first] to rest.joinToString("\n")
	}

	/** The title line with runs of spaces and tabs collapsed, trimmed. */
	private fun normalizeLine(line: String): String = line.replace(SPACES, " ").trim()

	/** [title] cut to at most [MAX_TITLE_LENGTH] characters; a cut title keeps its full line in front of the notes. */
	private fun cutTitle(
		title: String,
		notes: String,
	): Pair<String, String?> =
		if (title.length <= MAX_TITLE_LENGTH) {
			title to notes.ifEmpty { null }
		} else {
			title.take(MAX_TITLE_LENGTH - 1).trimEnd() + "…" to listOf(title, notes).filter { it.isNotEmpty() }.joinToString("\n")
		}

	private fun findProject(
		value: String,
		projects: List<CaptureProject>,
	): CaptureProject? {
		val wanted = normalize(value)
		return projects.firstOrNull { normalize(it.title) == wanted }
	}

	private fun normalize(name: String): String =
		name
			.replace('-', ' ')
			.replace('_', ' ')
			.replace(Regex("""\s+"""), " ")
			.trim()
			.lowercase()

	private fun findContext(
		value: String,
		contexts: List<CaptureContext>,
	): CaptureContext? {
		val wanted = value.lowercase()
		return contexts.firstOrNull {
			it.name
				.trim()
				.removePrefix("@")
				.lowercase() == wanted
		}
	}

	private fun parseDue(
		value: String,
		today: LocalDate,
	): LocalDate? =
		when (value.lowercase()) {
			"today" -> today
			"tomorrow" -> today.plusDays(1)
			else ->
				if (value.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) {
					try {
						LocalDate.parse(value)
					} catch (_: DateTimeParseException) {
						null
					}
				} else {
					null
				}
		}
}
