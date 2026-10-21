# Code Review

Date: 2026-10-21

Task: and-share-target - Android share target

Scope: task commit a1dbe34d on top of base 979ddb9e (`git diff 979ddb9e..a1dbe34d`), committed changes only

Requirements:
- tasks/and-share-target.md (acceptance criteria and recorded assumptions)
- SPEC.md §4.5 (quick capture, Android share target), §9.3 (offline capture), §14
- docs/ARCHITECTURE.md, AGENTS.md project conventions (lint, no secrets logged, feature folders, touches)
- Existing capture queue contract: android/.../capture/queue/CaptureQueue.kt, CaptureUploader.kt; server gtd/api/CaptureDtos.kt and gtd/domain/CaptureService.kt (limits)

Git range:
- Branch: main
- HEAD: a1dbe34db2638bcea2b09ea3918a8e5c2b9e5d99
- Base/start: 979ddb9e4ab5169cf353cf2b54bb5f601a5b2134
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 2 P2, 1 P3

Checked and found in order: intent filters for SEND and SEND_MULTIPLE with `text/plain`, `image/*`, `*/*`. The sheet edits the text and notes and saves through `CaptureQueue.capture`. The sheet closes (`saved`) only after the queue has copied the attachments and committed the row, so the copies happen while the read grant is still valid. `file:` URIs are dropped. The ViewModel survives configuration changes, and `load` runs only once, so edits are kept. Cancellation is rethrown. Nothing is logged. Strings are in resources. The focused suite passes: `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.share.*' --offline -q` exited 0 with no failures. The run included the test-writer's untracked ShareManifestTest.kt. The tests cover text, URL with subject, multiple images (copied, readable after the source is deleted), clip-data URIs, unreadable files, cancel and an empty share.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | System Back during save finishes the activity and silently discards the in-flight capture**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/capture/share/ShareActivity.kt:45` (with `CaptureSheet.kt:77` and `:170`)
    - Category: Correctness
    - Problem: While `state.saving` is true, the sheet disables Cancel and ignores taps on the scrim, so it treats a save as uninterruptible. But nothing intercepts system Back (there is no `BackHandler` or `OnBackPressedCallback`), and the default `ComponentActivity` back behavior finishes the activity. The ViewModel is cleared, `viewModelScope` is cancelled, and `CaptureQueue.capture` aborts while copying. Its `finally` block deletes the partial copies, nothing is queued, and no toast or error appears. The read grant also ends when the activity finishes, so the share cannot be resumed. Saving many or large files (for example 50 photos) takes long enough for this to happen in practice. The user pressed Save and gets no capture, and no feedback either way.
    - Why it matters: The share can be lost after the user pressed Save, and the behavior contradicts the sheet's own "saving cannot be cancelled" UI. SPEC §9.3 promises that a capture is always written locally.
    - Suggested fix: While `saving`, consume Back, for example with `BackHandler(enabled = state.saving) {}` in the sheet, so it behaves like the disabled Cancel. Alternatively, run the save in a scope that outlives the activity, but then the URI grant lifetime has to be handled. Add a Robolectric test that presses Back during a save.

- [ ] **CR-2 | Shared text is not checked against the server's 100,000-character notes limit, so long shares are "Saved" and then permanently rejected**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/capture/share/SharedContent.kt:33`, `ShareViewModel.kt:78-98`
    - Category: Correctness / Integration
    - Problem: Everything after the first line of EXTRA_TEXT (or the whole text when a subject is given) goes into `notes` with no length limit. The sheet validates only the title, through the queue's `MAX_TEXT_LENGTH` check on `text`, and `CaptureQueue.capture` does not check notes either. The server rejects notes over `TaskService.MAX_NOTES_LENGTH = 100_000` (`CaptureService.kt:62`, which returns 400). `CaptureUploader.failure` treats a 400 as `Rejected`, so the capture is never sent again. The share target is the place where unbounded text enters (sharing a long article or document text). The sheet shows "Saved to Inbox", and the item never reaches the Inbox.
    - Why it matters: Data loss that the user does not see, and the client and server disagree on a limit the server documents.
    - Suggested fix: Validate `notes.length <= 100_000` before queueing and show an error (reuse or add a `ShareError`), or truncate with a visible hint. Ideally also add the notes check to `CaptureQueue.capture` (owned by the capture-queue code) so every capture path enforces it. Add a test.

## P3 Low

- [ ] **CR-3 | Limits are duplicated as literals in the error strings**
    - File: `android/app/src/main/res/values/strings_share.xml:12-13`
    - Category: Quality
    - Problem: "at most 50" and "at most 100,000 characters" are hard-coded copies of `CaptureQueue.MAX_ATTACHMENTS` and `MAX_TEXT_LENGTH`. If a constant changes, the message silently goes out of date.
    - Why it matters: Small maintainability risk. The messages could become wrong without anyone noticing.
    - Suggested fix: Use `%1$d` (or `%1$s` for the formatted number) placeholders and pass the constants via `stringResource(id, arg)`.

## Review conclusion

Material issues found; fix before completing the task.
