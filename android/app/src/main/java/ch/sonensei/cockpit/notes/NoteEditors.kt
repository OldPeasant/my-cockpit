package ch.sonensei.cockpit.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where note saves run: as long as the app, so leaving the editor or the Notes screen never cancels or drops a save.
 */
@Singleton
class NoteSaveScope
    @Inject
    constructor() : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/**
 * The note editors of the Notes screen, each in its own store, so one can be dropped once nothing in it waits.
 *
 * An editor closed with everything saved is dropped ([settle]), so editors do not pile up. One left with text that is
 * not saved (offline, a refused save, a conflict) is kept: [settle] tries its save again each time the list is shown,
 * and opening its note again reaches it, also when it was a new note that got its id meanwhile ([editor]), or a new
 * note that could not be created yet ([unsentNewNote]). When the Notes screen goes away (also by the shell's back
 * arrow), every editor is cleared, and each saves what is left (see [NoteEditorViewModel.onCleared]).
 */
@HiltViewModel
class NoteEditors
    @Inject
    constructor(
        private val notes: NotesRepository,
        private val images: NoteImages,
        private val saves: NoteSaveScope,
    ) : ViewModel() {
        private class Editor(
            val store: ViewModelStore,
            val viewModel: NoteEditorViewModel,
        )

        private val editors = LinkedHashMap<String, Editor>()

        /**
         * The editor for [key]: a note id, or `new:<random>` for a new note. A note id also finds the editor of a new note
         * that was saved as that note and kept, which then moves to that key.
         */
        fun editor(key: String): NoteEditorViewModel {
            editors[key]?.let { return it.viewModel }
            val created =
                editors.entries.firstOrNull {
                    it.value.viewModel.uiState.value.note
                        ?.id
                        ?.toString() == key
                }
            if (created != null) {
                editors.remove(created.key)
                editors[key] = created.value
                return created.value.viewModel
            }
            val store = ViewModelStore()
            val factory = viewModelFactory { initializer { NoteEditorViewModel(notes, images, saves) } }
            val viewModel = ViewModelProvider(store, factory)[NoteEditorViewModel::class.java]
            editors[key] = Editor(store, viewModel)
            return viewModel
        }

        /** The key of a kept new note that could not be created yet, so New note goes on with its text. */
        fun unsentNewNote(): String? =
            editors.entries
                .firstOrNull { (key, editor) -> key.startsWith(NEW_NOTE_KEY) && editor.viewModel.uiState.value.note == null }
                ?.key

        /**
         * Called while the list is shown: saves what the editors still hold, waits for it, then drops every editor with
         * nothing left waiting.
         */
        suspend fun settle() {
            editors.values.toList().forEach { it.viewModel.saveNow().join() }
            editors.entries.filter { it.value.viewModel.settled }.forEach { (key, editor) ->
                editors.remove(key)
                editor.store.clear()
            }
        }

        override fun onCleared() {
            editors.values.forEach { it.store.clear() }
            editors.clear()
        }

        companion object {
            const val NEW_NOTE_KEY = "new:"
        }
    }
