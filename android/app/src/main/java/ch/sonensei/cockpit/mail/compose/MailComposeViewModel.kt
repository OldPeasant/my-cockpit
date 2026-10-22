package ch.sonensei.cockpit.mail.compose

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.mail.list.MailAccount
import ch.sonensei.cockpit.mail.list.MailResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
) {
    val editable: Boolean get() = phase == ComposePhase.EDITING
    val listedAttachments: List<DraftAttachment> get() = attachments.filterNot { it.inline }
}

/**
 * Compose, reply, reply to all and forward (SPEC.md §7.3): the draft lives in the account's Drafts folder, saved 10 s
 * after a change, before an upload, before sending and when leaving. Writes run one at a time, each with the version
 * the previous one returned.
 */
@HiltViewModel
class MailComposeViewModel
    @Inject
    constructor(
        private val repository: MailComposeRepository,
    ) : ViewModel() {
        private val state = MutableStateFlow(MailComposeUiState())
        val uiState: StateFlow<MailComposeUiState> = state.asStateFlow()

        private val writes = Mutex()
        private var stored: Draft? = null
        private var quotedHtml = ""
        private var edits = 0
        private var savedEdits = 0

        /** The user changed something (a reply never touched is discarded when leaving). */
        private var touched = false
        private var autosave: Job? = null

        private val dirty get() = edits != savedEdits

        /**
         * Opens [mode]; again for the same mode while it is open it keeps the current state (configuration change). One
         * view model serves every compose of the Mail screen, so a closed one starts over.
         */
        fun start(mode: ComposeMode) {
            val s = state.value
            if (s.mode == mode && s.phase != ComposePhase.SENT && s.phase != ComposePhase.CLOSED) return
            autosave?.cancel()
            stored = null
            quotedHtml = ""
            edits = 0
            savedEdits = 0
            touched = false
            state.value = MailComposeUiState(mode = mode)
            load(mode)
        }

        /** Tries again what failed while starting. */
        fun retry() {
            state.value.mode
                ?.takeIf { stored == null }
                ?.let { load(it) }
        }

        private fun load(mode: ComposeMode) {
            viewModelScope.launch {
                if (mode is ComposeMode.New) {
                    val accounts = repository.accounts()
                    state.update { s ->
                        val list = (accounts as? MailResult.Ok)?.value ?: s.accounts
                        val chosen = s.accountId ?: mode.accountId?.takeIf { id -> list.isEmpty() || list.any { it.id == id } }
                        s.copy(
                            phase = ComposePhase.EDITING,
                            accounts = list,
                            accountId = chosen ?: list.firstOrNull()?.id,
                            problem =
                                when {
                                    accounts is MailResult.Offline -> ComposeProblem.OFFLINE
                                    accounts is MailResult.Ok && list.isEmpty() -> ComposeProblem.NO_ACCOUNT
                                    accounts is MailResult.Failed -> ComposeProblem.START_FAILED
                                    else -> null
                                },
                        )
                    }
                    return@launch
                }
                state.update { it.copy(phase = ComposePhase.STARTING, problem = null) }
                when (val result = repository.start(mode)) {
                    is MailResult.Ok -> {
                        val draft = result.value
                        stored = draft
                        quotedHtml = draft.html
                        state.update {
                            it.copy(
                                phase = ComposePhase.EDITING,
                                accountId = draft.accountId,
                                accountLocked = true,
                                to = formatRecipients(draft.to),
                                cc = formatRecipients(draft.cc),
                                bcc = formatRecipients(draft.bcc),
                                showCcBcc = draft.cc.isNotEmpty() || draft.bcc.isNotEmpty(),
                                subject = draft.subject,
                                quotedText = draft.text.trim(),
                                attachments = draft.attachments,
                                draft = DraftState.SAVED,
                            )
                        }
                    }
                    MailResult.Offline -> state.update { it.copy(problem = ComposeProblem.OFFLINE) }
                    is MailResult.Failed -> state.update { it.copy(problem = ComposeProblem.START_FAILED) }
                }
            }
        }

        fun setTo(value: String) = edit { it.copy(to = value, invalid = it.invalid - RecipientField.TO) }

        fun setCc(value: String) = edit { it.copy(cc = value, invalid = it.invalid - RecipientField.CC) }

        fun setBcc(value: String) = edit { it.copy(bcc = value, invalid = it.invalid - RecipientField.BCC) }

        fun setSubject(value: String) = edit { it.copy(subject = value) }

        /** A change of only the selection or composition is not an edit. */
        fun setBody(value: TextFieldValue) {
            if (value.text == state.value.body.text) {
                state.update { it.copy(body = value) }
            } else {
                edit { it.copy(body = value) }
            }
        }

        fun format(format: TextFormat) = edit { it.copy(body = applyFormat(it.body, format)) }

        fun showCcBcc() = state.update { it.copy(showCcBcc = true) }

        fun selectAccount(id: UUID) {
            if (state.value.accountLocked || state.value.accountId == id) return
            state.update { it.copy(accountId = id, problem = it.problem.takeUnless { p -> p == ComposeProblem.NO_ACCOUNT }) }
        }

        fun removeAttachment(partId: String) = edit { s -> s.copy(attachments = s.attachments.filterNot { it.partId == partId }) }

        private fun edit(change: (MailComposeUiState) -> MailComposeUiState) {
            if (!state.value.editable) return
            edits++
            touched = true
            state.update { change(it).copy(draft = DraftState.UNSAVED, leaveBlocked = false) }
            scheduleAutosave()
        }

        private fun scheduleAutosave() {
            if (autosave?.isActive == true) return
            autosave =
                viewModelScope.launch {
                    delay(AUTOSAVE_MILLIS)
                    if (dirty && state.value.editable) store()
                }
        }

        /** Saves now (the screen's save action). */
        fun save() {
            viewModelScope.launch { store() }
        }

        /**
         * Stores the current content: creates the draft on the first save, else replaces it. Returns whether the draft
         * on the server now holds what is on screen.
         */
        private suspend fun store(): Boolean =
            writes.withLock {
                val current = stored
                if (current != null && !dirty) return@withLock true
                val content = content() ?: return@withLock false
                val at = edits
                state.update { it.copy(draft = DraftState.SAVING) }
                val result =
                    if (current == null) {
                        val account = state.value.accountId ?: return@withLock failSave(ComposeProblem.NO_ACCOUNT)
                        repository.create(account, content)
                    } else {
                        update(current, content)
                    }
                when (result) {
                    is MailResult.Ok -> {
                        stored = result.value
                        savedEdits = at
                        state.update {
                            it.copy(
                                attachments = result.value.attachments,
                                accountLocked = true,
                                draft = if (dirty) DraftState.UNSAVED else DraftState.SAVED,
                                problem = it.problem.takeUnless { p -> p in SAVE_PROBLEMS },
                            )
                        }
                        if (dirty) scheduleAutosave()
                        true
                    }
                    MailResult.Offline -> failSave(ComposeProblem.OFFLINE)
                    is MailResult.Failed -> failSave(ComposeProblem.SAVE_FAILED)
                }
            }

        /** Replaces the stored version; when another client saved meanwhile, this content replaces theirs. */
        private suspend fun update(
            current: Draft,
            content: DraftContent,
        ): MailResult<Draft> {
            val result = repository.update(current.id, current.etag, content)
            if (result !is MailResult.Failed || result.status != MailComposeRepository.PRECONDITION_FAILED) return result
            return when (val fresh = repository.get(current.id)) {
                is MailResult.Ok -> {
                    val kept = fresh.value.attachments.map { it.partId }
                    repository.update(current.id, fresh.value.etag, content.copy(keep = content.keep.filter { it in kept }))
                }
                else -> fresh
            }
        }

        private fun failSave(problem: ComposeProblem): Boolean {
            state.update { it.copy(draft = if (stored == null && !dirty) DraftState.NONE else DraftState.UNSAVED, problem = problem) }
            return false
        }

        /** What a save stores, or `null` (and the fields marked) when a recipient is not an address. */
        private fun content(): DraftContent? {
            val s = state.value
            val to = parseRecipients(s.to)
            val cc = parseRecipients(s.cc)
            val bcc = parseRecipients(s.bcc)
            val invalid =
                buildSet {
                    if (to.invalid.isNotEmpty()) add(RecipientField.TO)
                    if (cc.invalid.isNotEmpty()) add(RecipientField.CC)
                    if (bcc.invalid.isNotEmpty()) add(RecipientField.BCC)
                }
            if (invalid.isNotEmpty()) {
                state.update { it.copy(invalid = invalid, problem = ComposeProblem.INVALID_ADDRESS) }
                return null
            }
            return DraftContent(
                to = to.recipients,
                cc = cc.recipients,
                bcc = bcc.recipients,
                subject = s.subject,
                html = markupToHtml(s.body.text) + quotedHtml,
                keep = s.attachments.map { it.partId },
            )
        }

        /** Uploads [files] one after the other into the draft (saved first, so it exists). */
        fun attach(files: List<PickedFile>) {
            if (files.isEmpty() || !state.value.editable) return
            touched = true
            state.update { it.copy(uploading = it.uploading + files.map { f -> f.name }) }
            viewModelScope.launch {
                for (file in files) {
                    upload(file)
                    state.update { it.copy(uploading = it.uploading - file.name) }
                }
            }
        }

        private suspend fun upload(file: PickedFile) {
            if (stored == null && !store()) return
            writes.withLock {
                val draft = stored ?: return
                when (val result = repository.attach(draft.id, draft.etag, file)) {
                    is MailResult.Ok -> {
                        stored = result.value
                        // The upload stored the attachments the server had: the ones removed here since stay removed.
                        state.update { it.copy(attachments = result.value.attachments, problem = null) }
                    }
                    MailResult.Offline -> state.update { it.copy(problem = ComposeProblem.OFFLINE) }
                    is MailResult.Failed ->
                        state.update {
                            it.copy(problem = if (result.status in TOO_LARGE) ComposeProblem.TOO_LARGE else ComposeProblem.ATTACH_FAILED)
                        }
                }
            }
        }

        /** Saves what is on screen, then sends it; the screen closes once the server accepted it. */
        fun send() {
            val s = state.value
            if (!s.editable || s.uploading.isNotEmpty()) return
            if (listOf(s.to, s.cc, s.bcc).all { it.isBlank() }) {
                state.update { it.copy(problem = ComposeProblem.NO_RECIPIENT, invalid = it.invalid + RecipientField.TO) }
                return
            }
            state.update { it.copy(phase = ComposePhase.SENDING, problem = null) }
            autosave?.cancel()
            viewModelScope.launch {
                if (!store()) {
                    state.update { it.copy(phase = ComposePhase.EDITING) }
                    return@launch
                }
                val outcome =
                    writes.withLock {
                        val draft = stored ?: return@withLock SendOutcome.Failed(null)
                        repository.send(draft.id, draft.etag)
                    }
                val problem =
                    when (outcome) {
                        SendOutcome.Sent -> null
                        SendOutcome.Invalid -> ComposeProblem.NO_RECIPIENT
                        SendOutcome.Rejected -> ComposeProblem.REJECTED
                        SendOutcome.Offline -> ComposeProblem.OFFLINE
                        SendOutcome.Conflict, is SendOutcome.Failed -> ComposeProblem.SEND_FAILED
                    }
                state.update {
                    if (problem == null) it.copy(phase = ComposePhase.SENT) else it.copy(phase = ComposePhase.EDITING, problem = problem)
                }
            }
        }

        /** Deletes the draft from the Drafts folder and closes. */
        fun discard() {
            val s = state.value
            if (s.phase == ComposePhase.SENDING || s.phase == ComposePhase.SENT || s.phase == ComposePhase.CLOSED) return
            autosave?.cancel()
            viewModelScope.launch {
                val done =
                    writes.withLock {
                        val draft = stored ?: return@withLock MailResult.Ok(Unit)
                        repository.discard(draft.id, draft.etag)
                    }
                state.update {
                    when (done) {
                        is MailResult.Ok -> it.copy(phase = ComposePhase.CLOSED)
                        MailResult.Offline -> it.copy(problem = ComposeProblem.OFFLINE)
                        is MailResult.Failed -> it.copy(problem = ComposeProblem.DISCARD_FAILED)
                    }
                }
            }
        }

        /**
         * Back: keeps the draft (saved first) and closes. A reply or forward left untouched is discarded; when saving
         * fails the screen stays and offers [abandon].
         */
        fun leave() {
            val s = state.value
            when (s.phase) {
                ComposePhase.SENDING, ComposePhase.SENT, ComposePhase.CLOSED -> return
                ComposePhase.STARTING -> {
                    state.update { it.copy(phase = ComposePhase.CLOSED) }
                    return
                }
                ComposePhase.EDITING -> Unit
            }
            autosave?.cancel()
            viewModelScope.launch {
                if (!touched && stored != null && s.mode !is ComposeMode.New) {
                    writes.withLock { stored?.let { repository.discard(it.id, it.etag) } }
                    state.update { it.copy(phase = ComposePhase.CLOSED) }
                    return@launch
                }
                val saved = !dirty || store()
                state.update { if (saved) it.copy(phase = ComposePhase.CLOSED) else it.copy(leaveBlocked = true) }
            }
        }

        /** Leaves without saving the last changes (after [leave] could not save them). */
        fun abandon() {
            autosave?.cancel()
            state.update { it.copy(phase = ComposePhase.CLOSED) }
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
