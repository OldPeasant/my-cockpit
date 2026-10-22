package ch.sonensei.cockpit.mail.compose

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.mail.list.MailAccount
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject

/** Where the screen is. */
enum class ComposePhase {
    /** A reply or forward is being prepared by the server. */
    STARTING,
    EDITING,
    SENDING,

    /** Sent: the screen closes. */
    SENT,

    /** Discarded or left: the screen closes. */
    CLOSED,
}

/** Whether what is on screen is stored in the Drafts folder. */
enum class DraftState {
    /** Nothing typed yet, nothing stored. */
    NONE,

    /** Changes are not stored yet (autosave follows within 10 s). */
    UNSAVED,
    SAVING,
    SAVED,
}

/** Why something did not work; shown until the next success. */
enum class ComposeProblem {
    START_FAILED,
    OFFLINE,
    SAVE_FAILED,
    INVALID_ADDRESS,
    NO_ACCOUNT,
    NO_RECIPIENT,
    REJECTED,
    SEND_FAILED,
    ATTACH_FAILED,
    TOO_LARGE,
    DISCARD_FAILED,
}

enum class RecipientField { TO, CC, BCC }

data class MailComposeUiState(
    val mode: ComposeMode? = null,
    /** Which compose of the view model this state belongs to ([MailComposeViewModel.start] returns it). */
    val session: Int = 0,
    val phase: ComposePhase = ComposePhase.STARTING,
    val accounts: List<MailAccount> = emptyList(),
    val accountId: UUID? = null,
    /** The account can be changed only before the first save. */
    val accountLocked: Boolean = false,
    val to: String = "",
    val cc: String = "",
    val bcc: String = "",
    val showCcBcc: Boolean = false,
    val subject: String = "",
    val body: TextFieldValue = TextFieldValue(),
    /** The quoted original of a reply or forward, as text; it is kept below the body. */
    val quotedText: String = "",
    /** Every attachment of the stored draft, inline ones included (they are not listed). */
    val attachments: List<DraftAttachment> = emptyList(),
    /** Names of the files being uploaded. */
    val uploading: List<String> = emptyList(),
    val draft: DraftState = DraftState.NONE,
    val problem: ComposeProblem? = null,
    /** Fields with text that is not an address. */
    val invalid: Set<RecipientField> = emptySet(),
    /** Leaving failed to save: the screen offers to leave without saving. */
    val leaveBlocked: Boolean = false,
    /** Back was pressed and the draft is being saved: edits, send and discard wait for the screen to close. */
    val leaving: Boolean = false,
) {
    val editable: Boolean get() = phase == ComposePhase.EDITING && !leaving

    /** Sent, discarded or left: the screen closes. */
    val ended: Boolean get() = phase == ComposePhase.SENT || phase == ComposePhase.CLOSED
    val listedAttachments: List<DraftAttachment> get() = attachments.filterNot { it.inline }
}

/**
 * Compose, reply, reply to all and forward (SPEC.md §7.3): the draft lives in the account's Drafts folder, saved 10 s
 * after a change, before an upload, before sending and when leaving. Writes run one at a time, each with the version
 * the previous one returned.
 *
 * One view model serves every compose of the Mail screen. Each compose is a [ComposeSession] of its own: work still
 * running for an earlier one (a save when leaving, an upload, a reply the server prepares) finishes with that compose's
 * draft and never reaches the screen of the next one.
 */
@HiltViewModel
class MailComposeViewModel
    @Inject
    constructor(
        private val repository: MailComposeRepository,
    ) : ViewModel() {
        private val state = MutableStateFlow(MailComposeUiState())
        val uiState: StateFlow<MailComposeUiState> = state.asStateFlow()

        private var sessions = 0
        private var current: ComposeSession? = null

        /**
         * Opens [mode] and returns the id of its compose (the [MailComposeUiState.session] of its state). Again for the
         * same mode while it is open it keeps the current compose (configuration change); a closed one starts over.
         */
        fun start(mode: ComposeMode): Int {
            current?.takeIf { it.mode == mode && !it.value.ended }?.let { return it.id }
            val session = ComposeSession(++sessions, mode, repository, viewModelScope) { s -> if (current === s) state.value = s.value }
            current = session
            state.value = session.value
            session.load()
            return session.id
        }

        /** Tries again what failed while starting. */
        fun retry() {
            current?.retry()
        }

        fun setTo(value: String) = edit { it.copy(to = value, invalid = it.invalid - RecipientField.TO) }

        fun setCc(value: String) = edit { it.copy(cc = value, invalid = it.invalid - RecipientField.CC) }

        fun setBcc(value: String) = edit { it.copy(bcc = value, invalid = it.invalid - RecipientField.BCC) }

        fun setSubject(value: String) = edit { it.copy(subject = value) }

        /** A change of only the selection or composition is not an edit. */
        fun setBody(value: TextFieldValue) {
            val session = current ?: return
            if (value.text == session.value.body.text) {
                session.update { it.copy(body = value) }
            } else {
                session.edit { it.copy(body = value) }
            }
        }

        fun format(format: TextFormat) = edit { it.copy(body = applyFormat(it.body, format)) }

        fun showCcBcc() {
            current?.update { it.copy(showCcBcc = true) }
        }

        fun selectAccount(id: UUID) {
            val session = current ?: return
            if (session.value.accountLocked || session.value.accountId == id) return
            session.update { it.copy(accountId = id, problem = it.problem.takeUnless { p -> p == ComposeProblem.NO_ACCOUNT }) }
        }

        fun removeAttachment(partId: String) = edit { s -> s.copy(attachments = s.attachments.filterNot { it.partId == partId }) }

        private fun edit(change: (MailComposeUiState) -> MailComposeUiState) {
            current?.edit(change)
        }

        /** Saves now (the screen's save action). */
        fun save() {
            current?.save()
        }

        /** Uploads [files] one after the other into the draft (saved first, so it exists and holds what is on screen). */
        fun attach(files: List<PickedFile>) {
            current?.attach(files)
        }

        /** Saves what is on screen, then sends it; the screen closes once the server accepted it. */
        fun send() {
            current?.send()
        }

        /** Deletes the draft from the Drafts folder and closes. */
        fun discard() {
            current?.discard()
        }

        /**
         * Back: keeps the draft (saved first) and closes. A reply or forward left untouched is discarded; when saving
         * fails the screen stays and offers [abandon].
         */
        fun leave() {
            current?.leave()
        }

        /** Leaves without saving the last changes (after [leave] could not save them). */
        fun abandon() {
            current?.abandon()
        }

        internal companion object {
            /** SPEC.md §7.3: drafts autosave every 10 s. */
            const val AUTOSAVE_MILLIS = 10_000L
            val TOO_LARGE = setOf(400, 413)

            /** What a successful save resolves; a send or upload problem stays until its own next try. */
            val SAVE_PROBLEMS =
                setOf(ComposeProblem.OFFLINE, ComposeProblem.SAVE_FAILED, ComposeProblem.INVALID_ADDRESS, ComposeProblem.NO_ACCOUNT)
        }
    }
