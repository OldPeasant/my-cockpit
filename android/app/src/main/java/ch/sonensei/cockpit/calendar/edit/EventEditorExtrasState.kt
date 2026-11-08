package ch.sonensei.cockpit.calendar.edit

import java.util.UUID

/**
 * The prompt sending invitations (iTIP REQUEST) or a cancellation (CANCEL) for an event of a local calendar to its
 * guests by mail, over a mail account the user picks (SPEC.md §6.2).
 */
data class InvitationUiState(
    /** The event to send: a series master goes out with its overrides, an override on its own. */
    val eventId: UUID,
    val title: String,
    /** The guests of the saved event (the organizer among them gets nothing). */
    val recipients: List<String>,
    /** Guests removed by the save just done: they can be told they are no longer invited. */
    val removed: List<String>,
    /** Offered right after a save: closing the prompt closes the editor. */
    val afterSave: Boolean,
    val method: InvitationMethod = InvitationMethod.REQUEST,
    /** Also send a cancellation to [removed]. */
    val tellRemoved: Boolean = true,
    /** `null` while loading. */
    val accounts: List<MailAccount>? = null,
    val loadFailed: Boolean = false,
    val accountId: UUID? = null,
    val sending: Boolean = false,
    /** The last send failed (nothing or only part was sent). */
    val failed: Boolean = false,
) {
    /** Something to send, an account to send it over, and no send running. */
    val canSend: Boolean
        get() =
            !sending &&
                accountId != null &&
                accounts?.any { it.id == accountId } == true &&
                (recipients.isNotEmpty() || (tellRemoved && removed.isNotEmpty()))
}

/** What the last invitation mail sent from the editor did, and to how many addresses. */
data class InvitationSent(
    val method: InvitationMethod,
    val count: Int,
)

/** What "Create … from event" creates. */
enum class LinkedKind { TASK, NOTE }

/** The outcome of the last "Create … from event". */
data class LinkedResult(
    val kind: LinkedKind,
    val result: LinkedCreateResult,
)

/** The linked items panel of an existing event (read-only: links are added and removed elsewhere). */
data class LinksUiState(
    val loading: Boolean = false,
    val failed: Boolean = false,
    val items: List<LinkedItem> = emptyList(),
    /** A task or note is being created. */
    val creating: LinkedKind? = null,
    val result: LinkedResult? = null,
)
