package ch.sonensei.cockpit.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Why what is shown may be outdated or missing. */
enum class ContactsMessage { OFFLINE, FAILED }

data class ContactsListUiState(
    val contacts: List<Contact> = emptyList(),
    /** The search text as typed. */
    val query: String = "",
    val loading: Boolean = false,
    /** A list for the current search arrived. */
    val loaded: Boolean = false,
    val message: ContactsMessage? = null,
)

/** The contacts list with its search (SPEC.md §8.3). */
@HiltViewModel
class ContactsListViewModel
    @Inject
    constructor(
        private val contacts: ContactsRepository,
    ) : ViewModel() {
        private val state = MutableStateFlow(ContactsListUiState())
        val uiState: StateFlow<ContactsListUiState> = state.asStateFlow()

        private var loadJob: Job? = null

        /** Loads the list for the current search; a load still running for an earlier one is dropped. */
        fun refresh() = load(debounce = false)

        /** Searches for [query] once typing pauses. */
        fun search(query: String) {
            if (query == state.value.query) return
            // Nothing has arrived for the new search yet, so its empty-match text must not show before it does.
            state.update { it.copy(query = query, loaded = false) }
            load(debounce = true)
        }

        private fun load(debounce: Boolean) {
            loadJob?.cancel()
            loadJob =
                viewModelScope.launch {
                    if (debounce) delay(SEARCH_DELAY_MS)
                    state.update { it.copy(loading = true) }
                    val result = contacts.list(state.value.query)
                    state.update {
                        it.copy(
                            contacts = (result as? ContactsResult.Ok)?.value ?: it.contacts,
                            loading = false,
                            loaded = it.loaded || result is ContactsResult.Ok,
                            message = result.message(),
                        )
                    }
                }
        }

        internal companion object {
            const val SEARCH_DELAY_MS = 300L
        }
    }

data class ContactDetailUiState(
    val contact: Contact? = null,
    /** `null` until loaded (or when it could not be). */
    val related: ContactRelated? = null,
    val loading: Boolean = false,
    /** The contact itself could not be loaded. */
    val message: ContactsMessage? = null,
    /** The contact is shown, but its related items could not be loaded. */
    val relatedMessage: ContactsMessage? = null,
)

/** One contact with its Waiting For tasks, mails, events and linked items. */
@HiltViewModel
class ContactDetailViewModel
    @Inject
    constructor(
        private val contacts: ContactsRepository,
    ) : ViewModel() {
        private val state = MutableStateFlow(ContactDetailUiState())
        val uiState: StateFlow<ContactDetailUiState> = state.asStateFlow()

        private var id: UUID? = null
        private var loadJob: Job? = null

        /** Shows contact [id], loaded again; opening another contact first clears the one shown. */
        fun open(id: UUID) {
            if (id != this.id) {
                this.id = id
                loadJob?.cancel()
                state.value = ContactDetailUiState()
            }
            refresh()
        }

        fun refresh() {
            val id = id ?: return
            loadJob?.cancel()
            state.update { it.copy(loading = true) }
            loadJob =
                viewModelScope.launch {
                    val related = async { contacts.related(id) }
                    val contact = contacts.get(id)
                    val relatedResult = related.await()
                    state.update {
                        it.copy(
                            contact = (contact as? ContactsResult.Ok)?.value ?: it.contact,
                            related = (relatedResult as? ContactsResult.Ok)?.value ?: it.related,
                            loading = false,
                            message = contact.message(),
                            relatedMessage = relatedResult.message(),
                        )
                    }
                }
        }
    }

private fun ContactsResult<*>.message() =
    when (this) {
        is ContactsResult.Ok -> null
        ContactsResult.Offline -> ContactsMessage.OFFLINE
        is ContactsResult.Failed -> ContactsMessage.FAILED
    }

/** Why saving or deleting did not work. */
enum class ContactSaveError { OFFLINE, FAILED, CONFLICT, NOT_ALLOWED }

data class ContactEditUiState(
    val form: ContactForm = ContactForm.empty(),
    /** The contact being edited; `null` for a new one. */
    val base: Contact? = null,
    val saving: Boolean = false,
    /** Shown only after a save was tried, so an empty new form starts without errors. */
    val problem: ContactFormProblem? = null,
    val error: ContactSaveError? = null,
    /** The saved (or, for a delete, the trashed) contact: the screen then closes the form. */
    val saved: Contact? = null,
    val deleted: Boolean = false,
)

/** Creates, edits and deletes (to the Trash) contacts, like the web's contact form. */
@HiltViewModel
class ContactEditViewModel
    @Inject
    constructor(
        private val contacts: ContactsRepository,
    ) : ViewModel() {
        private val state = MutableStateFlow(ContactEditUiState())
        val uiState: StateFlow<ContactEditUiState> = state.asStateFlow()

        /** Starts editing [contact] (`null`: a new one), dropping any earlier form. */
        fun start(contact: Contact?) {
            state.value = ContactEditUiState(form = contact?.let(ContactForm::of) ?: ContactForm.empty(), base = contact)
        }

        fun change(form: ContactForm) {
            state.update { it.copy(form = form, problem = if (it.problem != null) form.problem() else null) }
        }

        fun save() {
            val current = state.value
            if (current.saving) return
            val problem = current.form.problem()
            if (problem != null) {
                state.update { it.copy(problem = problem) }
                return
            }
            state.update { it.copy(saving = true, problem = null, error = null) }
            viewModelScope.launch {
                val request = current.form.toRequest()
                val result = current.base?.let { contacts.update(it, request) } ?: contacts.create(request)
                if (result is ContactsResult.Ok) {
                    state.update { it.copy(saving = false, saved = result.value, base = result.value) }
                    return@launch
                }
                val error = result.saveError()
                // After a conflict the next Save goes against the current version; the form keeps the user's edits.
                val reloaded = if (error == ContactSaveError.CONFLICT) current.base?.let { contacts.get(it.id) } else null
                state.update {
                    it.copy(saving = false, error = error, base = (reloaded as? ContactsResult.Ok)?.value ?: it.base)
                }
            }
        }

        /** Moves [contact] to the Trash (the screen asks first). */
        fun delete(contact: Contact) {
            if (state.value.saving) return
            state.update { it.copy(base = contact, saving = true, error = null) }
            viewModelScope.launch {
                val result = contacts.delete(contact)
                state.update {
                    it.copy(
                        saving = false,
                        deleted = result is ContactsResult.Ok,
                        error = if (result is ContactsResult.Ok) null else result.saveError(),
                    )
                }
            }
        }

        /** The screen handled [ContactEditUiState.saved] or [ContactEditUiState.deleted]; the next screen starts clean. */
        fun finished() = state.update { it.copy(saved = null, deleted = false, error = null) }
    }

private fun ContactsResult<*>.saveError() =
    when (this) {
        is ContactsResult.Ok -> null
        ContactsResult.Offline -> ContactSaveError.OFFLINE
        is ContactsResult.Failed ->
            when (status) {
                CONFLICT, PRECONDITION_FAILED -> ContactSaveError.CONFLICT
                FORBIDDEN, METHOD_NOT_ALLOWED -> ContactSaveError.NOT_ALLOWED
                else -> ContactSaveError.FAILED
            }
    }

private const val FORBIDDEN = 403
private const val METHOD_NOT_ALLOWED = 405
private const val CONFLICT = 409
private const val PRECONDITION_FAILED = 412
