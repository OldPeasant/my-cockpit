package ch.sonensei.cockpit.capture.share

import java.time.LocalDate
import java.time.format.DateTimeParseException

/** A project the capture syntax may name. */
data class SyntaxProject(
    val id: String,
    val title: String,
)

/** A context the capture syntax may name. */
data class SyntaxContext(
    val id: String,
    val name: String,
)

/**
 * The projects and contexts `#` and `@` may name, in the server's order of preference: projects ACTIVE first, then on
 * hold and someday (completed and dropped ones are not offered); contexts in their order.
 */
data class CaptureVocabulary(
    val projects: List<SyntaxProject> = emptyList(),
    val contexts: List<SyntaxContext> = emptyList(),
) {
    companion object {
        val EMPTY = CaptureVocabulary()
    }
}

/** What a capture of the typed text would create, as far as the syntax goes: the sheet shows it as chips. */
data class CapturePreview(
    /** The title without the recognized tokens. */
    val title: String,
    val project: SyntaxProject? = null,
    val contexts: List<SyntaxContext> = emptyList(),
    val dueDate: LocalDate? = null,
    /** A `^` token was recognized: the task goes to the Waiting list. */
    val waiting: Boolean = false,
    /** Who it waits for: `^name`; null for `^waiting`. */
    val waitingFor: String? = null,
) {
    /** Whether any token was recognized, i.e. whether there is anything to show. */
    val hasTokens: Boolean get() = project != null || contexts.isNotEmpty() || dueDate != null || waiting
}

/**
 * The light syntax of quick capture (SPEC.md §4.5) on the device, for the live preview without a server call. The rules
 * are the server's `CaptureParser` (which also backs the web preview), so the chips show what the server will create
 * from the same projects and contexts: only the title line (the first non-blank line) is parsed; a token starts at the
 * beginning of the line or after whitespace and runs to the next whitespace; trailing punctuation (`.,;:!?)`) is not part
 * of its value. `#project` names a project by title (case-insensitive, `-`/`_` stand for spaces), `@context` a context
 * by name (with or without its `@`), `!today`/`!tomorrow`/`!YYYY-MM-DD` the due date, `^waiting` or `^name` the Waiting
 * list. The first recognized `#`, `!` and `^` wins; contexts accumulate; unknown names stay text.
 */
object CaptureSyntax {
    // Explicit ASCII whitespace instead of \s/\S: Android's ICU regex also counts Unicode spaces (e.g. U+00A0) as \s,
    // the server's java.util.regex does not, and the preview must split tokens exactly like the server.
    private val TOKEN = Regex("""(^|[ \t\n\u000B\f\r])([#@!^])([^ \t\n\u000B\f\r]+)""")
    private val SPACES = Regex("""[ \t]+""")
    private val WHITESPACE = Regex("""[ \t\n\u000B\f\r]+""")
    private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private const val TRAILING_PUNCTUATION = ".,;:!?)"
    private const val WAITING_KEYWORD = "waiting"

    fun parse(
        text: String,
        today: LocalDate,
        vocabulary: CaptureVocabulary,
    ): CapturePreview {
        var project: SyntaxProject? = null
        var dueDate: LocalDate? = null
        var waiting = false
        var waitingFor: String? = null
        val contexts = linkedMapOf<String, SyntaxContext>()

        val (titleLine, rest) = splitLines(text)
        val cleaned =
            TOKEN.replace(titleLine) { match ->
                val sigil = match.groupValues[2]
                val token = match.groupValues[3]
                val value = token.trimEnd { it in TRAILING_PUNCTUATION }
                val recognized =
                    value.isNotEmpty() &&
                        when (sigil) {
                            "#" -> project == null && findProject(value, vocabulary.projects)?.also { project = it } != null
                            "@" -> findContext(value, vocabulary.contexts)?.also { contexts.putIfAbsent(it.id, it) } != null
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
                    punctuation.isNotEmpty() -> punctuation
                    else -> match.groupValues[1]
                }
            }
        var title = normalizeLine(cleaned)
        // A title line of tokens only: the next line is the title, as on the server.
        if (title.isEmpty() && cleaned != titleLine) title = normalizeLine(splitLines(rest).first)
        return CapturePreview(title, project, contexts.values.toList(), dueDate, waiting, waitingFor)
    }

    private fun splitLines(text: String): Pair<String, String> {
        val lines = text.lines()
        val first = lines.indexOfFirst { it.isNotBlank() }
        if (first < 0) return "" to ""
        return lines[first] to lines.drop(first + 1).joinToString("\n")
    }

    private fun normalizeLine(line: String): String = line.replace(SPACES, " ").trim()

    private fun findProject(
        value: String,
        projects: List<SyntaxProject>,
    ): SyntaxProject? {
        val wanted = normalizeName(value)
        return projects.firstOrNull { normalizeName(it.title) == wanted }
    }

    private fun normalizeName(name: String): String =
        name
            .replace('-', ' ')
            .replace('_', ' ')
            .replace(WHITESPACE, " ")
            .trim()
            .lowercase()

    private fun findContext(
        value: String,
        contexts: List<SyntaxContext>,
    ): SyntaxContext? {
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
                if (value.matches(ISO_DATE)) {
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
