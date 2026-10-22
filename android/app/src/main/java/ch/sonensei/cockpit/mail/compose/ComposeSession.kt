package ch.sonensei.cockpit.mail.compose

import ch.sonensei.cockpit.mail.compose.MailComposeViewModel.Companion.AUTOSAVE_MILLIS
import ch.sonensei.cockpit.mail.compose.MailComposeViewModel.Companion.SAVE_PROBLEMS
import ch.sonensei.cockpit.mail.compose.MailComposeViewModel.Companion.TOO_LARGE
import ch.sonensei.cockpit.mail.list.MailResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One compose of [MailComposeViewModel]: its screen state ([value]), its draft and the writes for it, one at a time.
 * Every change of [value] is handed to [publish], which shows it only while this is the current compose.
 */
internal class ComposeSession(
    val id: Int,
    val mode: ComposeMode,
    private val repository: MailComposeRepository,
    private val scope: CoroutineScope,
    private val publish: (ComposeSession) -> Unit,
) {
    var value = MailComposeUiState(mode = mode, session = id)
        private set

    private val writes = Mutex()
    private var stored: Draft? = null
    private var quotedHtml = ""
    private var edits = 0
    private var savedEdits = 0

    /** The user changed something (a reply never touched is discarded when leaving). */
    private var touched = false

    /** Discarded: uploads still queued are dropped. */
    private var discarded = false

    /** The autosave while it waits; `null` once its save runs, so that save is never cancelled half-way. */
    private var autosave: Job? = null

    /** The start while it runs: a retry meanwhile would prepare a second reply draft. */
    private var loading: Job? = null

    private val dirty get() = edits != savedEdits

    fun update(change: (MailComposeUiState) -> MailComposeUiState) {
        value = change(value)
        publish(this)
    }

    fun retry() {
        if (stored == null && !value.ended && loading?.isActive != true) load()
    }

    fun load() {
        loading =
            scope.launch {
                if (mode is ComposeMode.New) {
                    val accounts = repository.accounts()
                    if (value.ended) return@launch
                    update { s ->
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
                update { it.copy(phase = ComposePhase.STARTING, problem = null) }
                when (val result = repository.start(mode)) {
                    is MailResult.Ok -> prepared(result.value)
                    MailResult.Offline -> if (!value.ended) update { it.copy(problem = ComposeProblem.OFFLINE) }
                    is MailResult.Failed -> if (!value.ended) update { it.copy(problem = ComposeProblem.START_FAILED) }
                }
            }
    }

    /** The reply or forward the server prepared; left meanwhile, it is discarded like any untouched one. */
    private suspend fun prepared(draft: Draft) {
        if (value.ended) {
            writes.withLock { discardStored(draft) }
            return
        }
        stored = draft
        quotedHtml = draft.html
        update {
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

    fun edit(change: (MailComposeUiState) -> MailComposeUiState) {
        if (!value.editable) return
        edits++
        touched = true
        update { change(it).copy(draft = DraftState.UNSAVED, leaveBlocked = false) }
        scheduleAutosave()
    }

    private fun scheduleAutosave() {
        if (autosave?.isActive == true) return
        autosave =
            scope.launch {
                delay(AUTOSAVE_MILLIS)
                // From here on an edit schedules the next autosave, and leaving or sending waits for this save.
                autosave = null
                if (dirty && value.editable) store()
            }
    }

    /** Cancels a waiting autosave; one that already saves finishes (the server may have stored it already). */
    private fun cancelAutosave() {
        autosave?.cancel()
        autosave = null
    }

    fun save() {
        scope.launch { store() }
    }

    /**
     * Stores the current content: creates the draft on the first save, else replaces it. Returns whether the draft on
     * the server now holds what is on screen.
     */
    private suspend fun store(): Boolean =
        writes.withLock {
            val current = stored
            if (current != null && !dirty) return@withLock true
            val content = content() ?: return@withLock false
            val at = edits
            val before = value.attachments
            update { it.copy(draft = DraftState.SAVING) }
            val result =
                if (current == null) {
                    val account = value.accountId ?: return@withLock failSave(ComposeProblem.NO_ACCOUNT)
                    repository.create(account, content)
                } else {
                    update(current, content)
                }
            when (result) {
                is MailResult.Ok -> {
                    stored = result.value
                    savedEdits = at
                    update {
                        it.copy(
                            attachments = result.value.attachments.withoutRemoved(before, it.attachments),
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
        return if (result.isStale()) overwrite(current, content) else result
    }

    /**
     * Stores [content] over the newest version of [current], keeping those of its attachments that still exist. Part ids
     * change between versions, so the kept ones (part ids of [current]) are found in the newest version by name, size
     * and kind; of equal ones, one is kept per kept one.
     */
    private suspend fun overwrite(
        current: Draft,
        content: DraftContent,
    ): MailResult<Draft> =
        when (val fresh = repository.get(current.id)) {
            is MailResult.Ok -> {
                val wanted = content.keep.mapNotNull { id -> current.attachments.firstOrNull { it.partId == id } }.toMutableList()
                val keep =
                    fresh.value.attachments
                        .filter { a ->
                            val match = wanted.firstOrNull { it.sameFileAs(a) }
                            match != null && wanted.remove(match)
                        }.map { it.partId }
                repository.update(current.id, fresh.value.etag, content.copy(keep = keep))
            }
            else -> fresh
        }

    private fun failSave(problem: ComposeProblem): Boolean {
        update { it.copy(draft = if (stored == null && !dirty) DraftState.NONE else DraftState.UNSAVED, problem = problem) }
        return false
    }

    /** What a save stores, or `null` (and the fields marked) when a recipient is not an address. */
    private fun content(): DraftContent? {
        val s = value
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
            update { it.copy(invalid = invalid, problem = ComposeProblem.INVALID_ADDRESS) }
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

    fun attach(files: List<PickedFile>) {
        if (files.isEmpty() || !value.editable) return
        touched = true
        update { it.copy(uploading = it.uploading + files.map { f -> f.name }) }
        scope.launch {
            for (file in files) {
                upload(file)
                update { it.copy(uploading = it.uploading - file.name) }
            }
        }
    }

    private suspend fun upload(file: PickedFile) {
        if (discarded) return
        // Saved first, so the upload adds to what is on screen: an attachment removed here does not come back.
        if ((stored == null || dirty) && !store()) return
        writes.withLock {
            val draft = stored?.takeUnless { discarded } ?: return
            val before = value.attachments
            when (val result = repository.attach(draft.id, draft.etag, file)) {
                is MailResult.Ok -> {
                    stored = result.value
                    update { it.copy(attachments = result.value.attachments.withoutRemoved(before, it.attachments), problem = null) }
                }
                MailResult.Offline -> update { it.copy(problem = ComposeProblem.OFFLINE) }
                is MailResult.Failed ->
                    update {
                        it.copy(problem = if (result.status in TOO_LARGE) ComposeProblem.TOO_LARGE else ComposeProblem.ATTACH_FAILED)
                    }
            }
        }
    }

    fun send() {
        val s = value
        if (!s.editable || s.uploading.isNotEmpty()) return
        if (listOf(s.to, s.cc, s.bcc).all { it.isBlank() }) {
            update { it.copy(problem = ComposeProblem.NO_RECIPIENT, invalid = it.invalid + RecipientField.TO) }
            return
        }
        update { it.copy(phase = ComposePhase.SENDING, problem = null) }
        cancelAutosave()
        scope.launch {
            if (!store()) {
                update { it.copy(phase = ComposePhase.EDITING) }
                return@launch
            }
            val outcome =
                writes.withLock {
                    val draft = stored ?: return@withLock SendOutcome.Failed(null)
                    sendStored(draft)
                }
            val problem =
                when (outcome) {
                    SendOutcome.Sent -> null
                    // Recipients are checked before sending, so with recipients the server refused a too large mail.
                    SendOutcome.Invalid -> if (hasRecipients()) ComposeProblem.TOO_LARGE else ComposeProblem.NO_RECIPIENT
                    SendOutcome.Rejected -> ComposeProblem.REJECTED
                    SendOutcome.Offline -> ComposeProblem.OFFLINE
                    SendOutcome.Conflict, is SendOutcome.Failed -> ComposeProblem.SEND_FAILED
                }
            update {
                if (problem == null) it.copy(phase = ComposePhase.SENT) else it.copy(phase = ComposePhase.EDITING, problem = problem)
            }
        }
    }

    /**
     * Sends the stored version. When another client changed the draft meanwhile, what is on screen is stored over the
     * newest version first (as a save does), then sent.
     */
    private suspend fun sendStored(draft: Draft): SendOutcome {
        val outcome = repository.send(draft.id, draft.etag)
        if (outcome != SendOutcome.Conflict) return outcome
        val content = content() ?: return outcome
        return when (val fresh = overwrite(draft, content)) {
            is MailResult.Ok -> {
                stored = fresh.value
                update { it.copy(attachments = fresh.value.attachments) }
                repository.send(fresh.value.id, fresh.value.etag)
            }
            MailResult.Offline -> SendOutcome.Offline
            is MailResult.Failed -> outcome
        }
    }

    private fun hasRecipients() = listOf(value.to, value.cc, value.bcc).any { parseRecipients(it).recipients.isNotEmpty() }

    /** Deletes [draft], also when another client changed it meanwhile; one already gone counts as discarded. */
    private suspend fun discardStored(draft: Draft): MailResult<Unit> {
        val result = repository.discard(draft.id, draft.etag)
        if (!result.isStale()) return result
        return when (val fresh = repository.get(draft.id)) {
            is MailResult.Ok -> repository.discard(draft.id, fresh.value.etag)
            MailResult.Offline -> MailResult.Offline
            is MailResult.Failed -> if (fresh.status == MailComposeRepository.NOT_FOUND) MailResult.Ok(Unit) else fresh
        }
    }

    fun discard() {
        val s = value
        if (s.phase == ComposePhase.SENDING || s.ended || s.leaving) return
        cancelAutosave()
        scope.launch {
            val done =
                writes.withLock {
                    val draft = stored ?: return@withLock MailResult.Ok(Unit)
                    discardStored(draft)
                }
            if (done is MailResult.Ok) discarded = true
            update {
                when (done) {
                    is MailResult.Ok -> it.copy(phase = ComposePhase.CLOSED)
                    MailResult.Offline -> it.copy(problem = ComposeProblem.OFFLINE)
                    is MailResult.Failed -> it.copy(problem = ComposeProblem.DISCARD_FAILED)
                }
            }
        }
    }

    fun leave() {
        val s = value
        if (s.leaving) return
        when (s.phase) {
            ComposePhase.SENDING, ComposePhase.SENT, ComposePhase.CLOSED -> return
            ComposePhase.STARTING -> {
                // A reply the server still prepares is discarded once it exists (see prepared).
                update { it.copy(phase = ComposePhase.CLOSED) }
                return
            }
            ComposePhase.EDITING -> Unit
        }
        cancelAutosave()
        // Locked while the draft is saved: a send or an edit meanwhile would be closed away unseen.
        update { it.copy(leaving = true, leaveBlocked = false) }
        scope.launch {
            if (!touched && stored != null && mode !is ComposeMode.New) {
                writes.withLock { stored?.let { discardStored(it) } }
                update { it.copy(phase = ComposePhase.CLOSED, leaving = false) }
                return@launch
            }
            val saved = !dirty || store()
            update {
                if (saved) it.copy(phase = ComposePhase.CLOSED, leaving = false) else it.copy(leaving = false, leaveBlocked = true)
            }
        }
    }

    fun abandon() {
        cancelAutosave()
        update { it.copy(phase = ComposePhase.CLOSED) }
    }
}

private fun MailResult<*>.isStale() = this is MailResult.Failed && status == MailComposeRepository.PRECONDITION_FAILED

/**
 * The attachments of a version the server returned, without those removed on screen while it was written: [before]
 * is what the screen had when the write started, [now] what it has. Part ids change between versions, so a removed
 * attachment is matched by name, size and kind; of equal ones, one is dropped per removal.
 */
internal fun List<DraftAttachment>.withoutRemoved(
    before: List<DraftAttachment>,
    now: List<DraftAttachment>,
): List<DraftAttachment> {
    val removed = before.filterNot { b -> now.any { it.partId == b.partId } }.toMutableList()
    if (removed.isEmpty()) return this
    return filterNot { a ->
        val match = removed.firstOrNull { it.sameFileAs(a) }
        match != null && removed.remove(match)
    }
}

/** The same file in another version of the draft (part ids change between versions). */
private fun DraftAttachment.sameFileAs(other: DraftAttachment) =
    name == other.name && sizeBytes == other.sizeBytes && inline == other.inline
