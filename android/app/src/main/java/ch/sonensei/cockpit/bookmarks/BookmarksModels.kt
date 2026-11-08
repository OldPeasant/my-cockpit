package ch.sonensei.cockpit.bookmarks

import ch.sonensei.cockpit.core.network.generated.models.BookmarkResponse
import java.net.URI
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** A bookmark as the app shows it (SPEC.md §8.4). */
data class Bookmark(
    val id: UUID,
    val url: String,
    /** The title as set or fetched; `null` when there is none. */
    val title: String?,
    /** The title, or else the host of the URL (computed by the server). */
    val displayTitle: String,
    /** The bookmark's notes: its description, as set or fetched from the page. */
    val description: String?,
    /** Absolute URL of the site's icon, fetched by the server; `null` when there is none. */
    val favicon: String?,
    val tags: List<String>,
    val readLater: Boolean,
    /** When it was marked as read; `null` while unread. */
    val readAt: Instant?,
    val createdAt: Instant,
    /** Sent back as `If-Match` when it is changed. */
    val version: Long,
    /** The file with the readable copy the server saved of the page; `null` when there is none. */
    val snapshotFileId: UUID? = null,
) {
    val read: Boolean get() = readAt != null

    /** In the read-later queue: in the list and not read yet. */
    val inQueue: Boolean get() = readLater && readAt == null

    /** The host of the URL without `www.`, or the URL itself when it has none. */
    val site: String get() = hostOf(url)
}

/** A tag in use and its number of bookmarks. */
data class BookmarkTag(
    val tag: String,
    val count: Long,
)

/** The editable part of a bookmark. */
data class BookmarkDraft(
    /** Blank: no title (the list then shows the site). */
    val title: String,
    /** Tags as typed: `reading, #rust kotlin`. */
    val tags: String,
    /** Blank: no notes. */
    val notes: String,
)

/** A new bookmark as typed in the add dialog. */
data class NewBookmark(
    val url: String,
    /** Blank: the server takes the page's title. */
    val title: String = "",
    /** Tags as typed: `reading, #rust kotlin`. */
    val tags: String = "",
    val readLater: Boolean = false,
)

/** The readable copy of a page as the server stores it: sanitized HTML (no scripts, styles, forms or images). */
data class BookmarkSnapshot(
    val html: String,
    /** Only the start of a longer copy was sent. */
    val truncated: Boolean,
)

internal fun BookmarkResponse.toBookmark() =
    Bookmark(
        id = id,
        url = url,
        title = title,
        displayTitle = displayTitle,
        description = description,
        favicon = favicon,
        tags = tags,
        readLater = readLater,
        readAt = readAt?.toInstant(),
        createdAt = createdAt.toInstant(),
        version = version,
        snapshotFileId = snapshotFileId,
    )

/** The server's order: newest first. */
internal val bookmarkOrder: Comparator<Bookmark> = compareByDescending<Bookmark> { it.createdAt }.thenByDescending { it.id }

/** The `If-Match` value of [version] (the server's strong ETag). */
internal fun etag(version: Long) = "\"$version\""

/** The host of [url] without `www.`, or [url] itself when it cannot be parsed. */
internal fun hostOf(url: String): String =
    runCatching { URI(url).host }
        .getOrNull()
        ?.removePrefix("www.")
        ?.takeIf { it.isNotEmpty() } ?: url

/** Tags typed as `reading, #rust  kotlin`: split at commas and spaces, `#` removed, each once (regardless of case). */
internal fun parseTags(input: String): List<String> {
    val tags = mutableListOf<String>()
    for (raw in input.split(Regex("[\\s,]+"))) {
        val tag = raw.trimStart('#').trim()
        if (tag.isNotEmpty() && tags.none { it.equals(tag, ignoreCase = true) }) tags += tag
    }
    return tags
}

/** Whether every word of [query] occurs in the title, site, URL, notes or tags (regardless of case). */
internal fun Bookmark.matches(query: String): Boolean {
    val words =
        query
            .trim()
            .lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val text =
        listOfNotNull(title, displayTitle, url, description, tags.joinToString(" ") { "#$it" })
            .joinToString("\n")
            .lowercase(Locale.ROOT)
    return words.all { it in text }
}

private val WEB_ADDRESS = Regex("""^https?://([^/?#]*)""", RegexOption.IGNORE_CASE)

/**
 * Whether [url] (trimmed) looks like an absolute http(s) address with a host. Deliberately lenient: the server is the
 * authority and also accepts addresses as users copy them from a browser (a host in Unicode or with `_`, spaces or `|`
 * in the path or query), which it percent-encodes. Only text that cannot be such an address is refused here.
 */
internal fun isWebAddress(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.any { it.isISOControl() }) return false
    val authority = WEB_ADDRESS.find(trimmed)?.groupValues?.get(1) ?: return false
    val hostPort = authority.substringAfterLast('@')
    val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']', "") else hostPort.substringBefore(':')
    return host.isNotEmpty() && host.none { it.isWhitespace() }
}

/** Whether the tags typed as [input] stay within the server's limits (at most 50 tags of at most 50 characters). */
internal fun tagsWithinLimits(input: String): Boolean {
    val tags = parseTags(input)
    return tags.size <= MAX_TAGS && tags.all { it.length <= MAX_TAG_LENGTH && '\u0000' !in it }
}

/** The server's tag limits (`BookmarkService`). */
private const val MAX_TAGS = 50
private const val MAX_TAG_LENGTH = 50

/**
 * Where a link in the readable copy of [base] leads: resolved against the page's address, and only http(s)
 * addresses (`null` for any other link, e.g. `javascript:` or `mailto:`).
 */
internal fun resolveLink(
    base: String,
    link: String,
): String? {
    val resolved = runCatching { URI(base).resolve(link.trim()) }.getOrNull() ?: return null
    return resolved.toString().takeIf { isWebAddress(it) }
}
