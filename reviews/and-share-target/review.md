# Code Review

Date: 2026-10-21

Task: and-share-target - Android share target

Scope: the full task diff, base to HEAD (`git diff 10ad344f..d9467d8c`), committed changes only. The production code was reviewed in full at the fix commit 10d50e0a. This pass looks at the final commit d9467d8c, which adds 3 tests by the test-writer plus the review and test reports, and confirms that the production code did not change after 10d50e0a. Reviewed revision: d9467d8ca9d0f014595b018f072631ae20efcf4c.

Requirements:
- tasks/and-share-target.md (acceptance criteria and recorded assumptions)
- SPEC.md §4.5 (quick capture, Android share target), §9.3 (offline capture)
- docs/ARCHITECTURE.md, AGENTS.md project conventions (lint, feature folders, touches, no secrets in logs)
- Existing contracts: android/.../capture/queue/CaptureQueue.kt; server gtd/domain/CaptureService.kt, CaptureParser.kt, TaskService.kt (limits)
- Earlier reviews (findings CR-1..CR-3 from the first review, CR-1 from the second) and reviews/and-share-target/fixes.md

Git range:
- Branch: main
- HEAD: d9467d8ca9d0f014595b018f072631ae20efcf4c
- Base/start: 10ad344f60c546f6dd94cf094cbe242ca9d56271 (`git merge-base HEAD origin/main`)
- Commits reviewed: 4 (9b75e099, 6a098d5a, 10d50e0a, d9467d8c)
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Changes since the previous review (10d50e0a..d9467d8c):
- `git show --stat d9467d8c` lists only `ShareIntentParserTest.kt`, `ShareViewModelTest.kt`, `reviews/and-share-target/review.md` and `reviews/and-share-target/tests.md`. `git diff 10d50e0a d9467d8c -- android/app/src/main` is empty, so the production code is the same code reviewed at 10d50e0a.
- `ShareIntentParserTest` "with a subject, multi-line text goes to the notes in full" checks the subject branch (`SharedContent.kt:32`): the subject becomes the title and the whole multi-line text becomes the notes, not just the text after the first line. The test asserts behavior and would fail if the text were split like a share without a subject.
- `ShareIntentParserTest` "multiple images given only as clip data are attached" checks `SEND_MULTIPLE` with no `EXTRA_STREAM` list, so only the clip data path (`SharedContent.kt:57`) supplies the URIs. It also checks their order. This is a real gap the earlier tests did not cover, since the existing clip data test used `ACTION_SEND`.
- `ShareViewModelTest` "a title typed while the file names load is kept when they arrive" uses the existing `GatedProvider` to hold the name query open. It edits the title and notes while loading, then releases the gate. It checks that `ShareViewModel.kt:68` (`s.text.ifBlank { defaultTitle(files) }`) keeps the typed title instead of putting the file name there, and that save queues exactly the edited values with the named attachment. This covers a real race between the async name lookup and user edits. The test resets `GatedProvider.gate` before use, so the shared static latch cannot leak between tests.
- The new tests follow the style of the surrounding tests (backtick names, fictitious data, no mocks hiding the behavior under test). They contain no debug output and no skips.
- Test results: I could not run Gradle myself because the sandbox does not allow it. The JUnit XML in `android/app/build/test-results/testDebugUnitTest` (timestamp 2026-10-21T01:51Z, after the fix commit) shows 0 failures and 0 errors for CaptureSheetTest 11, ShareViewModelTest 23, ShareActivityTest 11, ShareIntentParserTest 16 and ShareManifestTest 4. The counts went up by exactly the 3 added tests compared with the earlier run (22 and 14).

Earlier findings, checked against the current code:
- First review CR-1 (system Back during save): fixed. `CaptureSheet.kt:76` registers `BackHandler(enabled = state.saving) {}`. `CaptureSheetTest` sends Back through the real `OnBackPressedDispatcher`, both while saving and after a failed save.
- First review CR-2 (notes limit): fixed for the notes field. `ShareViewModel.kt:87` refuses notes over `MAX_NOTES_LENGTH = 100_000` before anything is queued, and boundary tests cover it. The remaining edge case is CR-1 below.
- First review CR-3 (literal limits in strings): fixed. `strings_share.xml` uses `%1$d` and `%1$,d` placeholders, and the code passes in the constants.
- Second review CR-1 (long title moved into the notes by the server): still open, carried over as CR-1 below. d9467d8c does not change production code.

Acceptance criteria:
1. Met in code. The manifest has SEND and SEND_MULTIPLE filters for `text/plain`, `image/*` and `*/*`, and `ShareManifestTest` covers them. The compact sheet edits the title and notes and saves through `CaptureQueue.capture`. `ShareActivityTest` checks the result against the real Room database.
2. Met in code. The activity finishes only after `CaptureQueue.capture` has copied the files and committed the row. Back and the scrim are blocked while saving, and the queue finishes under `NonCancellable`. Only `content:` URIs are accepted. The multiple-images test deletes the source files after the sheet closes and then reads the copies back.
3. Covered. The tests exercise text, a URL with a subject, a subject with multi-line text, multiple images (as extras and as clip data only), `file:` URIs, an empty share, errors, edits made while loading, a configuration change and the sheet UI. The parent still has to record evidence for every criterion with `tasks.py verify` before `done`.

Also checked, no problems found: cancellation is rethrown in `save()`, and a double tap queues only once. Loading and error states always end in a recoverable sheet. Edits survive recreation and the async file name load. Nothing is logged. Strings are in resources. Changes stay inside the task's touches, plus `strings_share.xml`, which a recorded assumption covers.

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
    - Problem: The share check compares only `form.notes.length` with 100,000. The server checks the combined notes instead (`CaptureService.kt:61-63`): `parsed.extraNotes` plus `"\n\n"` plus the request notes. When the title is longer than `TaskService.MAX_TITLE_LENGTH` (500), `CaptureParser.cutTitle` moves the full title line into `extraNotes`. So a share with a title over 500 characters and notes close to the limit passes the client check, and the server then rejects it with a 400. The uploader treats a 400 as permanent. For example, a 600-character title with 99,500 characters of notes gives 100,102 characters. The KDoc on `MAX_NOTES_LENGTH` says the server checks only the notes, which is not quite accurate.
    - Why it matters: This is the same silent loss that the first review's CR-2 fixed: the sheet says "Saved to Inbox", but the item never arrives. It needs a title over 500 characters and notes within a few hundred characters of the limit, so it is rare.
    - Suggested fix: Also count the moved title line. If `text.trim().length > 500`, check `text.length + 2 + notes.length` instead of `notes.length`. Adjust the KDoc and add a boundary test. The simpler alternative is to document the approximation. Ideally the queue owner centralizes this check later.

## Review conclusion

No material issues found. The 3 tests added in d9467d8c are meaningful, cover real gaps and follow the project conventions. The production code has not changed since the reviewed fix commit. The only open finding is the low-priority edge case CR-1. Before `done`, the parent worker must record the evidence for each acceptance criterion with `tasks.py verify` and confirm that the focused suite passes on HEAD d9467d8c.
