# Code Review

Date: 2026-10-21

Task: and-share-target - Android share target

Scope: the full task diff, base to HEAD (`git diff 10ad344f..10d50e0a`), committed changes only. The task branch was rebased while this review ran. The hashes the parent supplied (base 103cf937; commits 7413be39, 124491bf, 211dcba3) were replaced by the current hashes below. The commits have the same three subjects, and the last one is the fix commit. Reviewed revision: 10d50e0a5c5462fdf66ab0eb6f77afa49878db04.

Requirements:
- tasks/and-share-target.md (acceptance criteria and recorded assumptions)
- SPEC.md §4.5 (quick capture, Android share target), §9.3 (offline capture)
- docs/ARCHITECTURE.md, AGENTS.md project conventions (lint, feature folders, touches, no secrets in logs)
- Existing contracts: android/.../capture/queue/CaptureQueue.kt; server gtd/domain/CaptureService.kt, CaptureParser.kt, TaskService.kt (limits)
- Previous review (findings CR-1..CR-3) and reviews/and-share-target/fixes.md

Git range:
- Branch: main
- HEAD: 10d50e0a5c5462fdf66ab0eb6f77afa49878db04
- Base/start: 10ad344f60c546f6dd94cf094cbe242ca9d56271 (`git merge-base HEAD origin/main`)
- Commits reviewed: 3 (9b75e099, 6a098d5a, 10d50e0a)
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Previous findings, checked against the current code:
- Old CR-1 (system Back during save): fixed. `CaptureSheet.kt:76` registers `BackHandler(enabled = state.saving) {}`. `CaptureSheetTest` sends Back through the real `OnBackPressedDispatcher`. One test checks that the activity does not finish while saving. The other checks that Back finishes it again after a failed save.
- Old CR-2 (notes limit): fixed for the notes field. `ShareViewModel.kt:87` refuses notes over `MAX_NOTES_LENGTH = 100_000` before anything is queued, using `ShareError.NOTES_TOO_LONG`, and editing the notes clears the error. Tests cover a note exactly at the limit and one character over it, followed by a retry. A remaining edge case is listed as CR-1 below.
- Old CR-3 (literal limits in strings): fixed. `strings_share.xml` uses `%1$d` and `%1$,d` plurals, and `ShareError.message()` passes the constants. The message test pins the constant values.

Acceptance criteria:
1. Met in code. The manifest has SEND and SEND_MULTIPLE filters for `text/plain`, `image/*` and `*/*` (`AndroidManifest.xml:33-46`), and `ShareManifestTest` covers them. The compact sheet edits the title and notes and saves through `CaptureQueue.capture`. `ShareActivityTest` checks the queue against the real Room database.
2. Met in code. The activity finishes only after `saved` is set. `saved` is set only after `CaptureQueue.capture` has copied the files and committed the row. Cancellation cannot interrupt a save, because Back and the scrim are blocked and the queue uses `NonCancellable` after the copy. Only `content:` URIs are accepted. The multiple-images test deletes the source files after the sheet closes and reads the copies back.
3. Covered. The tests exercise text, a URL with a subject, multiple images, clip data, `file:` URIs, an empty share, errors, a configuration change and the sheet UI. I could not run Gradle myself: the sandbox denied the command. The latest JUnit results in `android/app/build/test-results/testDebugUnitTest` are from 03:43 on 2026-10-21. They show 0 failures and 0 errors: CaptureSheetTest 11, ShareViewModelTest 22, ShareActivityTest 11, ShareIntentParserTest 14, ShareManifestTest 4. These counts match the fixed suites described in fixes.md. `python tasks.py verify and-share-target` still shows all three criteria unrecorded. The parent has to record evidence before `done`.

Also checked, no problems found: cancellation is rethrown in `save()`, and a double tap queues only once. Loading and error states always end in a recoverable sheet. Edits survive recreation, because `load` runs only once. Nothing is logged. Strings are in resources. Changes stay inside the task's touches, plus `strings_share.xml`, which a recorded assumption covers.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The notes check ignores the part of a long title that the server moves into the notes**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/capture/share/ShareViewModel.kt:87`
    - Category: Correctness / Integration
    - Problem: The share check compares only `form.notes.length` with 100,000. The server checks the combined notes instead (`CaptureService.kt:61-63`): `parsed.extraNotes` plus `"\n\n"` plus the request notes. When the title is longer than `TaskService.MAX_TITLE_LENGTH` (500), `CaptureParser.cutTitle` puts the full title line into `extraNotes`. A share with a title over 500 characters and notes close to the limit therefore passes the client check. The server then rejects it with a 400, which the uploader treats as permanent. For example, a 600-character title with 99,500 characters of notes gives 100,102 characters. The KDoc on `MAX_NOTES_LENGTH` says the server checks only the notes, which is not quite accurate.
    - Why it matters: This is the same silent loss that the old CR-2 fixed: the sheet says "Saved to Inbox" and the item never arrives. It only happens when a title over 500 characters meets notes within a few hundred characters of the limit, so it is rare.
    - Suggested fix: Count the moved title line as well. If `text.trim().length > 500`, check `text.length + 2 + notes.length` instead of `notes.length`. Adjust the KDoc and add a boundary test. A simpler alternative is to document the approximation. Ideally the queue owner centralizes this check later.

## Review conclusion

No material issues found. The three earlier findings are fixed and covered by tests. The only remaining finding is a low-priority edge case (CR-1). Before `done`, the parent worker must record the acceptance-criteria evidence with `tasks.py verify` and confirm that the focused suite passes on HEAD 10d50e0a.
