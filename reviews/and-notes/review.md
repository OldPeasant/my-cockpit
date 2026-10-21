# Code Review

Date: 2026-10-21

Task: and-notes - Android notes

Scope: the full task, `git diff c167a9fa9ecc107793e5865b7e4953e747e0b10c..273c3d9b034cf96a3380e396bed18d0d0ae3d3cf -- android` (committed changes only). Reviewed revision: 273c3d9b034cf96a3380e396bed18d0d0ae3d3cf. This is the fourth review. The third review covered c7c89efdc053944e5f454d04fb981d1150ef7daf in full. Since then the only code change is commit 273c3d9b, which adds 4 tests to `android/app/src/test/java/ch/sonensei/cockpit/notes/NoteEditorsTest.kt`. `git diff --stat c7c89efd 273c3d9b -- android/app/src/main` is empty, so no production code changed. This review checks that test delta and carries the third review's results forward.

Requirements:
- tasks/and-notes.md acceptance criteria and recorded assumptions (`python tasks.py assumptions`, and-notes #1-#12)
- SPEC.md §9.2 item 6 (Android notes)
- docs/ARCHITECTURE.md and AGENTS.md project conventions
- Third review of c7c89efd (CR-1, CR-2 below)
- Parent instructions: review the test delta of 273c3d9b and update this report for that revision

Git range:
- Branch: main
- HEAD: 273c3d9b034cf96a3380e396bed18d0d0ae3d3cf
- Base/start: c167a9fa9ecc107793e5865b7e4953e747e0b10c
- Commits reviewed: 72 in the task range (`git log --oneline c167a9fa..273c3d9b`). This round looked closely at 1 of them (273c3d9b). The rest were covered by the earlier reviews.
- Uncommitted changes: none (working tree clean at the start of this review)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Review of the test delta (c7c89efd..273c3d9b)

I checked the four new tests in `NoteEditorsTest.kt` against `NoteEditors.kt` and `NoteEditorViewModel.kt` (`settle()`, `editor()`, `unsentNewNote()`, `pending()`, `onCleared()`, `save()`):

- "an editor cleared while a conflict waits saves nothing over the other version": `save()` returns early when `conflict != null`, so the `onCleared` save makes no repository call. The test checks that `repo.save` was called exactly once in total, and so pins this down.
- "the list showing keeps an editor whose conflict waits ...": `pending()` counts a conflict, so `settle()` keeps the editor. `editor(id)` then returns the same instance. `open(id, "b")` skips the reload while something is pending, and the test checks this with `repo.get` called exactly once. The conflict and the local text are both kept. The assertions match the code.
- "a photo still uploading when the list shows keeps its editor ...": the upload is held on a `CompletableDeferred`. `settle()` keeps the editor because `uploading` is pending. The image is saved once the upload finishes, and the next `settle()` drops the editor (`assertNotSame`). This covers both the keep path and the drop path.
- "a new note that was created but not saved since ...": the create succeeds and the follow-up save fails. `unsentNewNote()` returns null because the note now has an id, and `editor(id)` moves the kept `new:x` editor to that id. The test checks both behaviors from assumption #10 and CR-2 of the second review.

The tests check behavior through the public API. They mock only the repository boundary, so the mocks do not hide the behavior under test. Each test fixes its order of steps with `runCurrent`/`advanceUntilIdle`, so none of them depends on timing. No existing test was weakened or removed: the diff only adds lines.

I ran the class myself: `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.notes.NoteEditorsTest' --offline` -> 13 tests, 0 failures, 0 errors, 0 skipped (from `app/build/test-results/testDebugUnitTest/TEST-ch.sonensei.cockpit.notes.NoteEditorsTest.xml`). I did not run the full `.taskcheck` or `tasks.py verify`. That is the parent's job.

I found no new problems in the delta.

## Status of the earlier findings

- Second review CR-1..CR-5: resolved, as the third review found. No production code changed since then.
- Third review CR-1 (behavior is not recorded as assumptions): resolved. The parent recorded all three items as and-notes assumptions #10 (one uncreated new note at a time while offline), #11 (kept editors are retried each time the list shows, and the list reloads after the retries) and #12 (leaving the whole Notes screen saves once in the app scope, and text is dropped without a message if that save fails). The finding is kept below, ticked, for traceability.
- Third review CR-2 (`settle()` runs the retries one after another and shows no loading state): open. The parent accepts it without a change. It stays P3 and does not block. Assumption #11 records that the list reloads only after the retries.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [x] **CR-1 | Behavior the fixes added is not recorded as assumptions** (resolved: and-notes assumptions #10, #11, #12)
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NotesScreen.kt:95`, `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditors.kt:81-92`, `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditorViewModel.kt:205-209`
    - Category: Quality (requirements traceability)
    - Problem: The second round of fixes made product decisions that SPEC.md does not define:
      - (a) While a new note has not been created yet, "New note" opens that note again.
      - (b) Every kept editor's save is tried again each time the list shows.
      - (c) Leaving the whole Notes screen saves once, and the text is dropped without a message if that save fails.
    - Why it matters: AGENTS.md asks for `tasks.py assume` for every decision a human might want to revisit.
    - Suggested fix: Record them as assumptions. Done.

- [ ] **CR-2 | The list reload waits for every kept editor's save retry, one after another, with no loading indicator** (accepted by the parent without a change)
    - File: `android/app/src/main/java/ch/sonensei/cockpit/notes/NotesScreen.kt:83-88`, `android/app/src/main/java/ch/sonensei/cockpit/notes/NoteEditors.kt:81-82`
    - Category: Correctness (resilience)
    - Problem: `settle()` runs `saveNow().join()` for each kept editor in turn, and `refresh()` runs only after all of them finish. On a slow or unreachable network, each retry can take up to the HTTP timeout. All that time the list shows stale content with `loading = false`.
    - Why it matters: When the network is bad, which is exactly when editors get kept, the list looks loaded but stays stale for one timeout per kept editor.
    - Suggested fix: Start all retries first and then wait for all of them (`map { saveNow() }.joinAll()`), or show the list's loading state while `settle()` runs.

## Review conclusion

No material issues found. The four new tests are correct, they check behavior through the public API, and they pass. Production code has not changed since the third review. CR-1 is resolved through assumptions #10-#12. CR-2 is an accepted P3 and does not block. The parent still has to record the acceptance-criteria evidence and run `.taskcheck` before `done`.
