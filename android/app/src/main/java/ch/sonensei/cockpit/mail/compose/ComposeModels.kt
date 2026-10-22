package ch.sonensei.cockpit.mail.compose

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.util.UUID

/** What the compose screen was opened for. */
sealed interface ComposeMode {
    /** A new mail from [accountId] (`null`: the first account). */
    data class New(
        val accountId: UUID? = null,
    ) : ComposeMode

    data class Reply(
        val messageId: UUID,
    ) : ComposeMode

    data class ReplyAll(
        val messageId: UUID,
    ) : ComposeMode

    data class Forward(
        val messageId: UUID,
    ) : ComposeMode

    /** A string that survives configuration changes (`rememberSaveable`), read back by [parse]. */
    fun encode(): String =
        when (this) {
            is New -> "new:${accountId ?: ""}"
            is Reply -> "reply:$messageId"
            is ReplyAll -> "reply-all:$messageId"
            is Forward -> "forward:$messageId"
        }

    companion object {
        fun parse(value: String): ComposeMode? {
            val kind = value.substringBefore(':')
            val id = value.substringAfter(':', "").takeIf { it.isNotEmpty() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            return when (kind) {
                "new" -> New(id)
                "reply" -> id?.let(::Reply)
                "reply-all" -> id?.let(::ReplyAll)
                "forward" -> id?.let(::Forward)
                else -> null
            }
        }
    }
}

data class Recipient(
    val address: String,
    val name: String? = null,
)

/** An attachment of the stored draft; [inline] ones (images of a forwarded body) are kept but not listed. */
data class DraftAttachment(
    val partId: String,
    val name: String,
    val sizeBytes: Long?,
    val inline: Boolean = false,
)

/** A draft as stored in the Drafts folder, with its version ([etag]) for the next write. */
data class Draft(
    val id: UUID,
    val accountId: UUID?,
    val etag: String,
    val to: List<Recipient>,
    val cc: List<Recipient>,
    val bcc: List<Recipient>,
    val subject: String,
    val html: String,
    val text: String,
    val attachments: List<DraftAttachment>,
)

/** What a save stores. */
data class DraftContent(
    val to: List<Recipient>,
    val cc: List<Recipient>,
    val bcc: List<Recipient>,
    val subject: String,
    val html: String,
    /** `partId`s of the current version to keep. */
    val keep: List<String>,
)

/** A file picked on the device, streamed when uploaded. */
interface PickedFile {
    val name: String
    val mime: String

    /** Bytes when known (a provider may not tell). */
    val sizeBytes: Long?

    fun open(): java.io.InputStream?
}

/** The recipients of a field, or the parts that are not an address. */
data class ParsedRecipients(
    val recipients: List<Recipient>,
    val invalid: List<String>,
)

private val ADDRESS = Regex("^[^\\s@<>,;\"]+@[^\\s@<>,;\"]+$")
private val NAMED = Regex("^(.*?)<([^<>]*)>$")

/** Reads "Ann Example <ann@example.org>, ben@example.org; \"Doe, Carl\" <carl@example.org>". */
fun parseRecipients(field: String): ParsedRecipients {
    val recipients = mutableListOf<Recipient>()
    val invalid = mutableListOf<String>()
    splitRecipients(field).forEach { token ->
        val named = NAMED.matchEntire(token)
        val address = (named?.groupValues?.get(2) ?: token).trim()
        val name =
            named
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.removeSurrounding("\"")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        if (ADDRESS.matches(address)) recipients += Recipient(address, name) else invalid += token
    }
    return ParsedRecipients(recipients, invalid)
}

/** Splits at `,` and `;` outside quotes; blank parts are dropped. */
private fun splitRecipients(field: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    for (c in field) {
        when {
            c == '"' -> {
                quoted = !quoted
                current.append(c)
            }
            (c == ',' || c == ';') && !quoted -> {
                parts += current.toString()
                current.clear()
            }
            else -> current.append(c)
        }
    }
    parts += current.toString()
    return parts.map { it.trim() }.filter { it.isNotEmpty() }
}

/** The field text for [recipients], readable back by [parseRecipients]. */
fun formatRecipients(recipients: List<Recipient>): String =
    recipients.joinToString(", ") { r ->
        val name = r.name?.takeIf { it.isNotBlank() }
        when {
            name == null -> r.address
            name.any { it == ',' || it == ';' || it == '<' || it == '>' } -> "\"${name.replace("\"", "")}\" <${r.address}>"
            else -> "$name <${r.address}>"
        }
    }

/** The formatting buttons of the editor. */
enum class TextFormat { BOLD, ITALIC, BULLET }

/**
 * Applies [format] to the selection of [value]: bold and italic wrap it in `**`/`_` (or insert an empty pair with the
 * cursor inside), a bullet starts each selected line with `- ` (or removes it when all already have one).
 */
fun applyFormat(
    value: TextFieldValue,
    format: TextFormat,
): TextFieldValue {
    val text = value.text
    val start = value.selection.min
    val end = value.selection.max
    return when (format) {
        TextFormat.BOLD -> wrap(text, start, end, "**")
        TextFormat.ITALIC -> wrap(text, start, end, "_")
        TextFormat.BULLET -> bullets(text, start, end)
    }
}

private fun wrap(
    text: String,
    start: Int,
    end: Int,
    marker: String,
): TextFieldValue {
    val wrapped = text.substring(0, start) + marker + text.substring(start, end) + marker + text.substring(end)
    val selection = if (start == end) TextRange(start + marker.length) else TextRange(start + marker.length, end + marker.length)
    return TextFieldValue(wrapped, selection)
}

private fun bullets(
    text: String,
    start: Int,
    end: Int,
): TextFieldValue {
    val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (start == 0 || it < 0) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
    val lines = text.substring(lineStart, lineEnd).split('\n')
    val remove = lines.all { it.startsWith(BULLET) }
    val changed = lines.joinToString("\n") { if (remove) it.removePrefix(BULLET) else BULLET + it }
    val result = text.substring(0, lineStart) + changed + text.substring(lineEnd)
    return TextFieldValue(result, TextRange(lineStart + changed.length))
}

private const val BULLET = "- "

private val BOLD_MARK = Regex("\\*\\*(.+?)\\*\\*")
private val ITALIC_MARK = Regex("(?<![\\p{L}\\p{N}_])_(.+?)_(?![\\p{L}\\p{N}_])")

/**
 * The HTML of the text typed: blank lines separate paragraphs, line breaks stay, `- ` lines form a list, `**bold**` and
 * `_italic_` are marked. Everything else is escaped, so typed `<` stays text.
 */
fun markupToHtml(text: String): String {
    if (text.isBlank()) return ""
    val html = StringBuilder()
    val paragraph = mutableListOf<String>()
    val list = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) html.append("<p>").append(paragraph.joinToString("<br>") { inline(it) }).append("</p>")
        paragraph.clear()
    }

    fun flushList() {
        if (list.isNotEmpty()) html.append("<ul>").append(list.joinToString("") { "<li>${inline(it)}</li>" }).append("</ul>")
        list.clear()
    }
    text.replace("\r\n", "\n").split('\n').forEach { line ->
        when {
            line.startsWith(BULLET) -> {
                flushParagraph()
                list += line.removePrefix(BULLET)
            }
            line.isBlank() -> {
                flushParagraph()
                flushList()
            }
            else -> {
                flushList()
                paragraph += line
            }
        }
    }
    flushParagraph()
    flushList()
    return html.toString()
}

private fun inline(line: String): String {
    val escaped = escapeHtml(line)
    val bold = BOLD_MARK.replace(escaped) { "<strong>${it.groupValues[1]}</strong>" }
    return ITALIC_MARK.replace(bold) { "<em>${it.groupValues[1]}</em>" }
}

private fun escapeHtml(text: String): String =
    buildString {
        text.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }
