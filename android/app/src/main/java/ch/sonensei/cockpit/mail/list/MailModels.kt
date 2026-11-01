package ch.sonensei.cockpit.mail.list

import ch.sonensei.cockpit.core.network.generated.models.MailFolderResponse
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** The outcome of a mail call. */
sealed interface MailResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : MailResult<T>

    /** No connection (or the server or the mail server is down): nothing changed. */
    data object Offline : MailResult<Nothing>

    /** The server refused it; [status] is `null` for an unreadable answer. */
    data class Failed(
        val status: Int?,
    ) : MailResult<Nothing>
}

data class MailAccount(
    val id: UUID,
    /** The sender name ("Your name" in Settings); compose and identities use it. */
    val name: String,
    val address: String,
) {
    /** How the mail screens label the account: its email address, so accounts of the same person differ. */
    val label: String get() = address.ifBlank { name }
}

/** The special folders of SPEC.md §7.2, in the order the folder list shows them. */
enum class FolderRole { INBOX, DRAFTS, SENT, ARCHIVE, SPAM, TRASH }

data class MailFolder(
    val id: UUID,
    val accountId: UUID,
    val name: String,
    val path: String,
    val role: FolderRole?,
    /** Nesting depth from the path (0 for a top-level folder). */
    val depth: Int,
    /** The ETag value sent as If-Match when the folder is renamed or deleted. */
    val version: Long = 0,
    /** The server's hierarchy delimiter; `null` for a flat namespace. */
    val delimiter: String? = null,
) {
    /** INBOX keeps its name; the server refuses to rename it. */
    val canRename: Boolean get() = role != FolderRole.INBOX

    /** Special folders stay; the server refuses to delete them. */
    val canDelete: Boolean get() = role == null

    /** [other] lies inside this folder (at any depth). */
    fun contains(other: MailFolder): Boolean = other.accountId == accountId && delimiter != null && other.path.startsWith(path + delimiter)
}

/** An account with its selectable folders: special folders first, the others by path. */
data class MailAccountFolders(
    val account: MailAccount,
    val folders: List<MailFolder>,
)

/** What the list shows: the unified Inbox of all accounts, or one folder. */
sealed interface Mailbox {
    data object Unified : Mailbox

    data class Folder(
        val accountId: UUID,
        val folderId: UUID,
    ) : Mailbox
}

/** A list entry (cached headers). */
data class MailSummary(
    val id: UUID,
    val accountId: UUID?,
    val sender: String,
    val subject: String,
    val snippet: String,
    val date: Instant?,
    val seen: Boolean,
    val flagged: Boolean,
    val hasAttachments: Boolean,
)

/** One page of a folder, newest first; [nextCursor] is `null` on the last page. */
data class MailPage(
    val items: List<MailSummary>,
    val nextCursor: String?,
)

/** The swipe actions of the list (SPEC.md §7.3). */
enum class MailListAction(
    internal val wire: String,
) {
    ARCHIVE("ARCHIVE"),
    DELETE("DELETE"),
    SPAM("SPAM"),
}

internal fun MailAccountDto.toAccount() =
    MailAccount(
        id = UUID.fromString(id),
        name = displayName?.takeIf { it.isNotBlank() } ?: emailAddress.orEmpty(),
        address = emailAddress.orEmpty(),
    )

internal fun MailFolderDto.toFolder(): MailFolder {
    val folderPath = path ?: name.orEmpty()
    val separator = delimiter?.takeIf { it.isNotEmpty() }
    return MailFolder(
        id = UUID.fromString(id),
        accountId = UUID.fromString(accountId),
        name = name?.takeIf { it.isNotBlank() } ?: folderPath,
        path = folderPath,
        role = role?.let { r -> FolderRole.entries.firstOrNull { it.name == r } },
        depth = separator?.let { folderPath.split(it).size - 1 } ?: 0,
        version = version ?: 0,
        delimiter = separator,
    )
}

internal fun MailFolderResponse.toFolder() =
    MailFolderDto(
        id = id.toString(),
        accountId = accountId.toString(),
        name = name,
        path = path,
        delimiter = delimiter,
        role = role?.value,
        selectable = selectable,
        version = version,
    ).toFolder()

/** Special folders in [FolderRole] order, then the others by path (so subfolders follow their parent). */
internal val folderOrder: Comparator<MailFolder> =
    compareBy<MailFolder> { it.role?.ordinal ?: FolderRole.entries.size }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.path }

internal fun MailSummaryDto.toSummary() =
    MailSummary(
        id = UUID.fromString(id),
        accountId = accountId?.let(UUID::fromString),
        sender = from.firstOrNull()?.display().orEmpty(),
        subject = subject.orEmpty(),
        snippet = snippet.orEmpty(),
        date = parseDate(receivedAt) ?: parseDate(sentAt),
        seen = seen,
        flagged = flagged,
        hasAttachments = hasAttachments,
    )

/** "Name" when the address has one, else the address itself. */
fun MailAddressDto.display(): String = name?.takeIf { it.isNotBlank() } ?: address.orEmpty()

/** An ISO-8601 date of the server; an unreadable one is treated as missing. */
fun parseDate(value: String?): Instant? = value?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
