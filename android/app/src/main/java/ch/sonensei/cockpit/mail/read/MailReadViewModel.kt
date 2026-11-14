package ch.sonensei.cockpit.mail.read

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.core.settings.MailImagePolicy
import ch.sonensei.cockpit.core.settings.SettingsUpdateResult
import ch.sonensei.cockpit.core.settings.UserSettingsChange
import ch.sonensei.cockpit.core.settings.UserSettingsRepository
import ch.sonensei.cockpit.mail.list.MailListAction
import ch.sonensei.cockpit.mail.list.MailNextAction
import ch.sonensei.cockpit.mail.list.MailRepository
import ch.sonensei.cockpit.mail.list.MailResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Why the message is not shown. */
enum class MailReadError { OFFLINE, FAILED, GONE }

/** The outcome of an action, shown below the toolbar until the next one. */
sealed interface MailReadNotice {
    data class Created(
        val action: MailGtdAction,
        val title: String,
    ) : MailReadNotice

    data class GtdFailed(
        val action: MailGtdAction,
    ) : MailReadNotice

    /** [errorCode] is the server's reason for refusing (e.g. `NO_ROLE_FOLDER`), `null` when it gave none or was unreachable. */
    data class ActionFailed(
        val action: MailListAction,
        val errorCode: String? = null,
    ) : MailReadNotice

    /** "Next action & archive" could not create the task: the mail stays where it is. */
    data object NextActionFailed : MailReadNotice

    /** "Next action & archive" created the task [title] but could not archive the mail (server reason [errorCode]). */
    data class NotArchived(
        val title: String,
        val errorCode: String? = null,
    ) : MailReadNotice

    /** "Load images" failed: the mail stays shown without its remote images. */
    data object ImagesFailed : MailReadNotice

    /** "Always for this sender" could not save the sender in the settings (the images of this mail still load). */
    data object SenderNotSaved : MailReadNotice
}

data class MailReadUiState(
    val message: MailMessage? = null,
    val loading: Boolean = false,
    val error: MailReadError? = null,
    /** An action is running: the buttons wait for it. */
    val busy: Boolean = false,
    val notice: MailReadNotice? = null,
    /** The message was archived, deleted, moved or moved to spam: the view closes. */
    val removed: Boolean = false,
    /** The message was marked unread: the view returns to the list. */
    val unread: Boolean = false,
    /** The last flag change the server took, for the list to show; [MailMark.seq] tells two equal ones apart. */
    val mark: MailMark? = null,
    /** "Next action & archive" created this task: its detail opens over the mail. */
    val nextAction: MailNextAction? = null,
)

/** [action] (FLAG or UNFLAG) was done on the open mail. */
data class MailMark(
    val action: MailListAction,
    val seq: Int,
)

/**
 * One message (SPEC.md §7.3): opening it marks it read; archive, delete, spam, "→ Inbox task" and "→ Waiting For".
 * Remote images stay blocked unless the synced settings allow them (`mailImagePolicy = allow`, or the sender is in
 * `mailImageAllowedSenders`) or the user asks for them ([loadImages], [alwaysLoadImages]); they then come through the
 * server's image proxy. [images] loads the server's images (inline parts and proxied ones) into the mail page.
 */
@HiltViewModel
class MailReadViewModel
    @Inject
    constructor(
        private val reader: MailReadRepository,
        private val mail: MailRepository,
        private val settings: UserSettingsRepository,
        val images: MailImageFetcher,
    ) : ViewModel() {
        private val state = MutableStateFlow(MailReadUiState())
        val uiState: StateFlow<MailReadUiState> = state.asStateFlow()

        private var id: UUID? = null

        /** Counts the openings: answers for an earlier one are dropped. */
        private var session = 0

        /** Marks the open mail read on the server (opening it does that). */
        private var markReadJob: Job? = null

        /**
         * Shows [messageId], loaded once while it stays open (again with [retry]); a mail shown before is dropped. One
         * view model serves every mail of the list, so a closed mail does not stay in memory.
         */
        fun open(messageId: UUID) {
            if (id == messageId) return
            reset(messageId)
            load()
        }

        /** The read view closed: drops the mail, so opening it again loads its current state. */
        fun close() = reset(null)

        private fun reset(messageId: UUID?) {
            id = messageId
            session++
            markReadJob = null
            state.value = MailReadUiState()
        }

        /** Applies [change] unless the mail it belongs to was closed or another one opened meanwhile. */
        private fun updateFor(
            opened: Int,
            change: (MailReadUiState) -> MailReadUiState,
        ) {
            if (opened == session) state.update(change)
        }

        fun retry() = load()

        fun gtd(action: MailGtdAction) =
            run { messageId, opened ->
                val result = reader.gtd(messageId, action)
                updateFor(opened) {
                    it.copy(
                        notice =
                            if (result is MailResult.Ok) {
                                MailReadNotice.Created(
                                    action,
                                    result.value,
                                )
                            } else {
                                MailReadNotice.GtdFailed(action)
                            },
                    )
                }
            }

        /**
         * "Next action & archive": a Next action linked to the mail, then the mail is archived. The task's detail opens
         * ([MailReadUiState.nextAction]); when archiving failed the mail stays and a notice says so.
         */
        fun nextAction() =
            run { messageId, opened ->
                val result = mail.toNextActions(listOf(messageId))
                val created = (result as? MailResult.Ok)?.value?.firstOrNull()
                updateFor(opened) {
                    when {
                        created == null -> it.copy(notice = MailReadNotice.NextActionFailed)
                        created.archived -> it.copy(nextAction = created)
                        else ->
                            it.copy(
                                nextAction = created,
                                notice =
                                    MailReadNotice.NotArchived(
                                        it.message?.subject?.takeIf { s -> s.isNotBlank() } ?: created.title,
                                        created.archiveErrorCode,
                                    ),
                            )
                    }
                }
            }

        /** The task detail opened by [nextAction] closed. */
        fun nextActionShown() = state.update { it.copy(nextAction = null) }

        /**
         * Archive, delete, spam and move ([target] for MOVE) close the view; mark unread returns to the list; flag and
         * unflag show at once and are taken back if the server refuses. A failure shows as a notice. Marking read is
         * not offered here: opening the mail does it.
         */
        fun act(
            action: MailListAction,
            target: UUID? = null,
        ) {
            if (action == MailListAction.READ || (action == MailListAction.MOVE && target == null)) return
            val flagging = action == MailListAction.FLAG || action == MailListAction.UNFLAG
            run { messageId, opened ->
                if (flagging) updateFor(opened) { it.copy(message = it.message?.copy(flagged = action == MailListAction.FLAG)) }
                // Opening marked the mail read: "unread" must reach the server after that.
                if (action == MailListAction.UNREAD) markReadJob?.join()
                val outcome = (reader.act(messageId, action, target) as? MailResult.Ok)?.value
                updateFor(opened) {
                    when {
                        outcome?.ok != true ->
                            it.copy(
                                message = if (flagging) it.message?.copy(flagged = action != MailListAction.FLAG) else it.message,
                                notice = MailReadNotice.ActionFailed(action, outcome?.errorCode),
                            )
                        action.removes -> it.copy(removed = true)
                        action == MailListAction.UNREAD -> it.copy(unread = true)
                        else -> it.copy(mark = MailMark(action, (it.mark?.seq ?: 0) + 1))
                    }
                }
            }
        }

        /** "Load images": shows the mail again with its remote images through the server's proxy. */
        fun loadImages() = run { messageId, opened -> showWithImages(messageId, opened) }

        /** "Always for this sender": adds the sender to `mailImageAllowedSenders`, then loads the images. */
        fun alwaysLoadImages() {
            val sender = state.value.message?.fromAddress ?: return
            run { messageId, opened ->
                val allowed =
                    settings.settings.value
                        ?.mailImageAllowedSenders
                        .orEmpty()
                val saved =
                    allowed.any { it.equals(sender, ignoreCase = true) } ||
                        settings.update(UserSettingsChange(mailImageAllowedSenders = allowed + sender)) == SettingsUpdateResult.Updated
                showWithImages(messageId, opened)
                if (!saved) updateFor(opened) { it.copy(notice = it.notice ?: MailReadNotice.SenderNotSaved) }
            }
        }

        private suspend fun showWithImages(
            messageId: UUID,
            opened: Int,
        ) {
            val result = reader.message(messageId, proxyImages = true)
            updateFor(opened) {
                if (result is MailResult.Ok) it.copy(message = result.value) else it.copy(notice = MailReadNotice.ImagesFailed)
            }
        }

        /** Whether the settings load remote images of [message] without asking. */
        private fun imagesAllowed(message: MailMessage?): Boolean {
            val current = settings.settings.value ?: return false
            if (current.mailImagePolicy == MailImagePolicy.ALLOW) return true
            val sender = message?.fromAddress ?: return false
            return current.mailImageAllowedSenders.any { it.equals(sender, ignoreCase = true) }
        }

        private fun run(block: suspend (messageId: UUID, opened: Int) -> Unit) {
            val messageId = state.value.message?.id ?: return
            if (state.value.busy) return
            val opened = session
            state.update { it.copy(busy = true, notice = null) }
            viewModelScope.launch {
                block(messageId, opened)
                updateFor(opened) { it.copy(busy = false) }
            }
        }

        private fun load() {
            val messageId = id ?: return
            val opened = session
            state.update { it.copy(loading = true, error = null) }
            viewModelScope.launch {
                when (val result = loadMessage(messageId)) {
                    is MailResult.Ok -> {
                        updateFor(opened) { it.copy(message = result.value, loading = false) }
                        // Read state is the mail server's: a failure shows on the next load. Only a mail still open
                        // (so shown) counts as read.
                        if (opened == session && !result.value.seen) {
                            markReadJob = launch { mail.markRead(result.value.id) }
                        }
                    }
                    MailResult.Offline -> updateFor(opened) { it.copy(loading = false, error = MailReadError.OFFLINE) }
                    is MailResult.Failed ->
                        updateFor(opened) {
                            it.copy(loading = false, error = if (result.status == NOT_FOUND) MailReadError.GONE else MailReadError.FAILED)
                        }
                }
            }
        }

        /**
         * [messageId] with remote images as the settings want: `allow` asks for proxied images right away; an allowed
         * sender is only known from the mail, so its blocked images are asked for again (as the web app does). If that
         * second request fails, the blocked copy is shown (its banner still offers "Load images").
         */
        private suspend fun loadMessage(messageId: UUID): MailResult<MailMessage> {
            if (imagesAllowed(null)) return reader.message(messageId, proxyImages = true)
            val result = reader.message(messageId)
            if (result !is MailResult.Ok || result.value.remoteImagesBlocked == 0 || !imagesAllowed(result.value)) return result
            return reader.message(result.value.id, proxyImages = true) as? MailResult.Ok ?: result
        }

        private companion object {
            const val NOT_FOUND = 404
        }
    }
