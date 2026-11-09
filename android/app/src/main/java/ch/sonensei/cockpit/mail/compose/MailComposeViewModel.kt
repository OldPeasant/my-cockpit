package ch.sonensei.cockpit.mail.compose

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.files.DriveEntry
import ch.sonensei.cockpit.files.FolderCrumb
import ch.sonensei.cockpit.mail.list.MailAccount
import ch.sonensei.cockpit.mail.list.MailResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    /** The sending identities of [accountId] (empty: not loaded, or it has none: the account address is used). */
    val identities: List<MailIdentity> = emptyList(),
    /** The identity chosen in the From picker (`null`: the account's default one). */
    val identityId: UUID? = null,
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
        private val lookup: ComposeLookup,
    ) : ViewModel() {
        /** Without suggestions and Files (tests of the draft logic). */
        internal constructor(repository: MailComposeRepository) : this(repository, ComposeLookup.None)

        private val state = MutableStateFlow(MailComposeUiState())
        val uiState: StateFlow<MailComposeUiState> = state.asStateFlow()

        private val suggestionState = MutableStateFlow(RecipientSuggestions())

        /** Suggestions for the recipient field typed in last. */
        val suggestions: StateFlow<RecipientSuggestions> = suggestionState.asStateFlow()

        private val pickerState = MutableStateFlow<FilePickerState?>(null)

        /** The cockpit Files picker while it is open. */
        val filePicker: StateFlow<FilePickerState?> = pickerState.asStateFlow()

        private var suggesting: Job? = null
        private var listing: Job? = null

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
            clearSuggestions()
            closeFiles()
            session.load()
            return session.id
        }

        /** Tries again what failed while starting. */
        fun retry() {
            current?.retry()
        }

        fun setTo(value: String) {
            edit { it.copy(to = value, invalid = it.invalid - RecipientField.TO) }
            suggest(RecipientField.TO, value)
        }

        fun setCc(value: String) {
            edit { it.copy(cc = value, invalid = it.invalid - RecipientField.CC) }
            suggest(RecipientField.CC, value)
        }

        fun setBcc(value: String) {
            edit { it.copy(bcc = value, invalid = it.invalid - RecipientField.BCC) }
            suggest(RecipientField.BCC, value)
        }

        /**
         * Looks up suggestions for the text typed in [field] ([value] is the whole field) once typing paused for
         * [SUGGEST_DEBOUNCE_MILLIS]; only the latest lookup shows, a failed one shows none, recipients already in the field
         * are left out.
         */
        private fun suggest(
            field: RecipientField,
            value: String,
        ) {
            suggesting?.cancel()
            val query = recipientEntry(value).pending.trim()
            if (query.isEmpty() || current?.value?.editable != true) {
                clearSuggestions()
                return
            }
            suggesting =
                viewModelScope.launch {
                    delay(SUGGEST_DEBOUNCE_MILLIS)
                    val chosen = chosenAddresses(value)
                    val items = (lookup.suggestions(query) as? MailResult.Ok)?.value.orEmpty()
                    suggestionState.value = RecipientSuggestions(field, items.filterNot { it.address.lowercase() in chosen })
                }
        }

        /** Hides the suggestions (a field lost focus, or one was chosen). */
        fun clearSuggestions() {
            suggesting?.cancel()
            suggestionState.value = RecipientSuggestions()
        }

        /** Opens the cockpit Files picker at the drive root. */
        fun openFiles() {
            if (current?.value?.editable != true) return
            list(emptyList())
        }

        fun openFolder(folder: DriveEntry.Folder) {
            val picker = pickerState.value ?: return
            list(picker.path + FolderCrumb(folder.id, folder.name))
        }

        /** The parent folder; at the root it stays. */
        fun folderUp() {
            val picker = pickerState.value ?: return
            if (picker.path.isNotEmpty()) list(picker.path.dropLast(1))
        }

        fun retryFiles() {
            pickerState.value?.let { list(it.path) }
        }

        fun closeFiles() {
            listing?.cancel()
            pickerState.value = null
        }

        /** Attaches [file] of the drive to the draft and closes the picker. */
        fun pickFile(file: DriveEntry.File) {
            closeFiles()
            current?.attachFile(file)
        }

        private fun list(path: List<FolderCrumb>) {
            listing?.cancel()
            pickerState.value = FilePickerState(path = path, loading = true)
            listing =
                viewModelScope.launch {
                    val result = lookup.folder(path.lastOrNull()?.id)
                    pickerState.value =
                        when (result) {
                            is MailResult.Ok -> FilePickerState(path = path, entries = result.value, loading = false)
                            MailResult.Offline -> FilePickerState(path = path, loading = false, problem = FilePickerProblem.OFFLINE)
                            is MailResult.Failed -> FilePickerState(path = path, loading = false, problem = FilePickerProblem.FAILED)
                        }
                }
        }

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
            session.update {
                it.copy(
                    accountId = id,
                    identities = emptyList(),
                    identityId = null,
                    problem = it.problem.takeUnless { p -> p == ComposeProblem.NO_ACCOUNT },
                )
            }
            session.loadIdentities(id)
        }

        /** Sends as identity [id] of the current account; the draft keeps it with the next save. */
        fun selectIdentity(id: UUID) {
            val session = current ?: return
            if (session.value.identityId == id || session.value.identities.none { it.id == id }) return
            session.edit { it.copy(identityId = id) }
        }

        /**
         * Sends from the account address itself (no identity). Offered only while the account has no default identity
         * (the server sends from the default one otherwise).
         */
        fun selectAccountAddress() {
            val session = current ?: return
            val s = session.value
            if (s.identityId == null || s.identities.any { it.isDefault }) return
            session.edit { it.copy(identityId = null) }
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

            /** Wait this long after the last keystroke before asking for suggestions (as the web does). */
            const val SUGGEST_DEBOUNCE_MILLIS = 200L
            val TOO_LARGE = setOf(400, 413)

            /** What a successful save resolves; a send or upload problem stays until its own next try. */
            val SAVE_PROBLEMS =
                setOf(ComposeProblem.OFFLINE, ComposeProblem.SAVE_FAILED, ComposeProblem.INVALID_ADDRESS, ComposeProblem.NO_ACCOUNT)
        }
    }
