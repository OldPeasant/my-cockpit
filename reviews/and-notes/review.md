# Code Review

Date: 2026-10-21

Task: and-notes - Android notes

Scope: `git diff c167a9fa9ecc107793e5865b7e4953e747e0b10c..af0780bf -- android` (committed changes only). Reviewed revision: af0780bfdfc00b087d5346ad1a50b4e002679c62. This is a re-review after the fixes for the previous review (CR-1..CR-6). The current HEAD bb99aee8 is the same code after a rebase: `git diff af0780bf HEAD -- android` is empty.

Requirements:
- tasks/and-notes.md acceptance criteria and recorded assumptions (`python tasks.py assumptions`)
- SPEC.md §9.2 item 6 (Android notes), §4.2 (API conventions)
- docs/ARCHITECTURE.md and AGENTS.md project conventions
- Previous review (CR-1..CR-6) and reviews/and-notes/fixes.md
- Parent instructions: check that each earlier finding is resolved and look for issues the fixes introduced

Git range:
- Branch: main
- HEAD: bb99aee8228b93c0587df382a5913f702164c7ca (same android content as af0780bf)
- Base/start: c167a9fa9ecc107793e5865b7e4953e747e0b10c
- Commits reviewed: 4 (c6f41c46 implementation, 1d07ddef and 17f9578f tests, af0780bf fixes)

Summary: 0 P0, 0 P1, 2 P2, 3 P3

## Status of the earlier findings

- CR-1 (a failed load let the user create a separate note): resolved. The `unloaded` flag blocks `editable`, `save()` and `addPhoto`, and `retryLoad` now keys on it. The view-model test `after a failed load nothing can be edited, saved or uploaded until the retry loads the note` covers it.
- CR-2 (reopening a note showed stale text): resolved for existing notes. Each opening gets a unique `<id>#<uuid>` token, and `open()` reloads unless something is pending (`loading`, `uploading`, `conflict`, `status != SAVED`, or `saving.isLocked`). A configuration change keeps the same token, so nothing reloads. View-model and route tests cover this. A view model is still kept for every new note created (see CR-5 below).
- CR-3 (web images went through the authenticated, server-rewriting client): resolved. `ImageSource.Web.url()` is `null`, and `MdImage` shows a placeholder with the address. A test checks that the client's interceptor never sees the request. The assumption is recorded.
- CR-4 (the list reloaded before the save): resolved for the editor's back arrow and the system back. `saveNow()` returns the save job, `BackHandler` moved into `NoteEditorRoute`, and the list joins the job before `refresh()`. The shell's own top-bar back arrow still skips this (see CR-1 below).
- CR-5 (a photo uploaded during a conflict was dropped): resolved. `pendingImage` is inserted after "Keep my version" or "Use the other version", and it is dropped when the user discards a deleted note. Both decisions are tested.
- CR-6 (missing race tests): resolved. There is a failed-load test, and `NotesListViewModelTest` has `a list for an earlier filter that answers last is dropped`.

No evidence is recorded yet: `tasks.py verify and-notes` shows both criteria unticked. The parent must record it before `done`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | The shell's top-bar back arrow leaves the editor without saving and cancels a running save**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt:61-63`, `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorScreen.kt:114-115`, `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorViewModel.kt:180-187`
    - Category: Correctness / Integration
    - Problem: Notes is a `NavSection.More` destination. While the editor is open, the shell's `TopAppBar` shows its own back arrow (`navController.navigateUp()`) above the editor's back arrow. The CR-4 fix only routes the editor's arrow and the system back through `saveNow()`. `navigateUp()` pops the Notes back stack entry, and that clears its `ViewModelStore`. `onCleared` then cancels `viewModelScope`, which cancels the pending 1 s autosave delay and any save in progress, so the save is never started or is aborted.
    - Why it matters: Text typed in the last second before tapping the more visible top arrow is silently lost. A PATCH that is cancelled mid-flight may or may not have been committed by the server. This is the "leaving the editor saves" behavior that CR-4 and the recorded assumption promise.
    - Suggested fix: Make every way of leaving go through the same save. One option: a `DisposableEffect` in `NoteEditorRoute` whose `onDispose` starts the save in a scope that outlives the view model (an application-scoped `CoroutineScope` injected into the view model). Another option: let `onCleared` hand any unsaved draft to such a scope. Add a test that pops the Notes entry with unsaved text and asserts that `repo.save` is called.

- [ ] **CR-2 | Text left unsaved in a new note can never be reached again**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NotesScreen.kt:91`, `NotesScreen.kt:101`
    - Category: Correctness
    - Problem: A new note's editor uses the view-model key `new:<uuid>`. Opening a note from the list uses its id as the key. Suppose the user leaves a new note while its save is OFFLINE or FAILED, either before the first create or after the note got id Y and later text failed to save. The unsaved text then stays only in the `new:<uuid>` view model. No UI route leads back to that view model, and nothing retries its save. Opening Y from the list creates a different view model (key `Y`) that loads the older server text. Existing notes now keep their pending text across reopenings (CR-2 fix), but new notes do not.
    - Why it matters: Typed text is silently lost in a normal offline case. This contradicts the recorded assumption "unsaved text stays in the editor until saved".
    - Suggested fix: Once a new note is created, move the editor onto the note's id key (for example, switch `open` to `<id>#<token>` after the first save and keep the same view model). Alternatively, do not let the user leave a new note with unsaved text without a warning or a retry. Add a view-model or route test for "new note, save offline, leave, open again".

## P3 Low

- [ ] **CR-3 | No test checks that the list waits for the editor's save before reloading**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NotesScreen.kt:81-85`, `android/app/src/test/java/ch/sonensei/cockpit/notes/NotesScreenInAppTest.kt:61`
    - Category: Tests
    - Problem: The route test checks that `onClose` receives a save job. Nothing checks that `NotesRoute` joins that job before `viewModel.refresh()`, which is the actual CR-4 behavior. Removing the `saving?.join()` would leave every test green.
    - Why it matters: This ordering is easy to regress during later changes.
    - Suggested fix: Add a `NotesRoute` test with a deferred `repo.save`. Close the editor and assert that `list()` is not called until the save completes.

- [ ] **CR-4 | The comment on `insertPendingImage` does not match what the code does**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorViewModel.kt:249-258`
    - Category: Quality
    - Problem: The KDoc says the image goes "at its cursor when the text is still the one it was added to, else at the end". The code chooses only by the caller: `keepMine` always uses the stored cursor, even when the body changed between the photo pick and the conflict (the test inserts at 0 into "Mine"). `useTheirs` always appends.
    - Why it matters: The next maintainer may believe the code checks whether the text changed.
    - Suggested fix: Reword the comment to say what happens (keep mine: the stored cursor, clamped; use theirs: the end). Or actually check that the body is unchanged.

- [ ] **CR-5 | Editor view models still pile up, one per new note and one per note opened**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NotesScreen.kt:101`, `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorScreen.kt:109`
    - Category: Quality
    - Problem: `hiltViewModel(key = "note:$key")` is scoped to the Notes back stack entry. Every "new note" (`new:<uuid>`) and every distinct note id opened leaves a view model in that store, with its state and body text, until the Notes entry is popped. The fix report says the number "does not grow with each opening". That holds for reopening the same note, but not for new notes or for different notes.
    - Why it matters: Memory grows slowly during a long session. This is also the root of CR-2.
    - Suggested fix: Scope the editor view model to the editor itself (a nested `ViewModelStoreOwner` that is cleared after its save completes), or clear finished new-note view models once their save is done.

## Review conclusion

Material issues found; fix before completing the task.
