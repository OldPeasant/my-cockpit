# Fix Report

Date: 2026-10-22

Task: and-journal-habits (Android journal and habits screens)

Scope: current task, finding CR-1 from `reviews/and-journal-habits/review.md` and finding TW-1 from
`reviews/and-journal-habits/tests.md` (both assigned by the parent worker)

## Fixed findings

### CR-1 — Creating today's entry when another device already created it fails permanently (428 is not handled as a conflict)

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/journal/JournalRepository.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/journal/JournalRepositoryTest.kt`

Problem:
A save sent without `If-Match` (no entry was loaded) for a day that has an entry by now is refused by the server
with 428 Precondition Required (`ETags.checkIfMatch`, `JournalEntryController`; documented in `api/openapi.yaml`).
The repository counted only 404/409/412 as conflicts, so it returned `JournalSaveResult.Failed(428)`, every retry
failed the same way, and the conflict banner never appeared. The repository test mocked 409 instead of the status
the server really sends.

Fix:
Added 428 to `CONFLICTS` in `JournalRepository`, so the refusal loads the server's current entry and returns
`JournalSaveResult.Conflict(current)` like 404/409/412. Updated the KDoc of `save` to name 428. 428 is only sent
for a request without `If-Match`, i.e. a create, so no other path changes.
In `JournalRepositoryTest`, the test `a create refused because the day got an entry elsewhere is a conflict` now
enqueues 428; a new test `a create that loses the race against a concurrent create is a conflict` keeps the 409
case (the unique-index race on the server) covered.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.journal.*' --tests 'ch.sonensei.cockpit.habits.*'`
  — Pass (JournalRepositoryTest 11/11, including both create-conflict tests)

### TW-1 — Keeping my text when the server already has the same text stays "Unsaved changes"

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/journal/JournalViewModel.kt`

Problem:
`keepMine()` always set `status = SaveStatus.UNSAVED`. When the text written here equals the server's current
entry (for example the first save reached the server but its answer was lost), `save()` returns early because
there is nothing to save, so the status stayed "Unsaved changes" for good.

Fix:
`keepMine()` now derives the status from the text with the file's existing `withStatus()` helper
(`SAVED` when the body equals the adopted entry, `UNSAVED` otherwise), as `useTheirs()` already does. The save is
still scheduled; it does nothing when the text is already saved. The test-writer's test was not changed.

Validation:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.journal.*' --tests 'ch.sonensei.cockpit.habits.*'`
  — Pass (`JournalViewModelTest > keeping my text when the server already has the same text ends as saved` passes;
  JournalViewModelTest 25/25)

## Not fixed

None of the assigned findings. CR-2 (P3) was not assigned to this fixer.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.journal.*' --tests 'ch.sonensei.cockpit.habits.*'`
  — BUILD SUCCESSFUL, ktlint clean. Results: JournalRepositoryTest 11, JournalViewModelTest 25, JournalScreenTest 13,
  JournalRouteInAppTest 2, HabitsRepositoryTest 4, HabitsViewModelTest 10, HabitsScreenTest 8,
  HabitsRouteInAppTest 1; 74 tests, 0 failures, 0 errors.

Full test suite:
- Not run (left to the parent worker).

Lint/type-check/build:
- `ktlintCheck` — Pass (in the command above). `lintDebug` not run.

`.taskcheck`:
- Not run (left to the parent worker).

Remaining unresolved findings:
0 of the assigned findings.

## Final status

All validated findings fixed and validation passed.
