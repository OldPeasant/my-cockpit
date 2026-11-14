# Code Review

Date: 2026-11-14

Task: mail-action-settle - Moved, archived or deleted mail must not come back in the source list until the next 5-min sync

Scope: the task's code commits 480868337, 3aa5c98f1 and 1f4e045f8 (1f4e045f8 fixes CR-1..CR-5 of the previous review), as listed by the parent worker. The final code at HEAD 1f4e045f8 was read in `server/` and `web/e2e/mail-list`. Commit d2df50384 holds only reports and was not reviewed.

Requirements:
- tasks/mail-action-settle.md: acceptance criteria and the recorded assumption
- AGENTS.md project notes (conventions, lint, tests, evidence)
- Previous review and reviews/mail-action-settle/fixes.md
- Existing code conventions in the server `mail` module and in `web/e2e`

Git range:
- Branch: main
- HEAD: 1f4e045f8
- Base/start: 02562a63b^ (supplied by the parent worker)
- Commits reviewed: 3

Summary: 0 P0, 0 P1, 1 P2, 3 P3

Note: Bash was denied in this session (only a few read-only `grep`/`awk` calls ran). This review therefore read the current files directly. It did not compute `git diff`, run Gradle or npm, or inspect the evidence recorded with `tasks.py verify`. The test results quoted below come from fixes.md, not from my own runs. The parent worker must check the recorded criterion evidence before running `done`.

## Status of previous findings

- CR-1 (P1, tests still expected `resync.request`): fixed. `MailActionServiceTests.kt:511-524`, `:544-561`, `:695-711` and `:758-768` now verify `requestVerified` for the source and target, and verify that `request` is never called. fixes.md reports 48/48 passing.
- CR-2 (P1, no tests for the resync wiring): fixed. The new tests cover MOVE (`:575`), DELETE outside Trash (`:588`), DELETE in Trash (`:601`), ARCHIVE and SPAM (`:613`), a flag change (`:630`) and the cache-conflict path (`:645`).
- CR-3 (P2, a test locked in the stale behavior): fixed. `MailHeaderSyncServiceTests.kt:306-322` now ends with `verifyFolder` removing UID 5. The plain-sync assertion is labelled as the known limitation. The conflict test at `MailActionServiceTests.kt:645` reproduces candidate 1 and could not pass before the fix.
- CR-4 (P3, extra UID SEARCH): fixed. `MailHeaderSyncService.kt:351` limits the extra comparison to `verifyUids && qresync != null && lowest != null`. The test at `MailHeaderSyncServiceTests.kt:325` asserts a single `uidsFrom`.
- CR-5 (P3, stale log and KDoc text): mostly fixed. `MailFolderResync.kt:73,76` and the class KDoc of `MailActionService` (`:114-119`) are updated. One stale log line and KDoc remain (see CR-3 below).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | The e2e check for "archive from the read view and going back" reloads the page instead of going back**
    - File: `web/e2e/mail-list/list.spec.ts:143-157`
    - Category: Tests
    - Problem: Criterion 4 asks for the same check "for archiving from the read view and going back". After archiving from the read view, the spec runs `page.goto(`/mail/folders/${inbox.id}`)` (line 148). That is a full page load, which starts the app again with a new store. It never uses the in-app back navigation (the read view's back control, or `page.goBack()`), which keeps the client-side list state. So the spec does not cover the path where the mail could "come back" in an already loaded list after returning from the read view. The later `page.reload()` (line 156) only repeats what the `goto` already checked.
    - Why it matters: The owner's report is about the list in a running session. The current spec would pass even if the back navigation showed a stale cached list, because a full reload always reads the list fresh from the server.
    - Suggested fix: After the read-view Archive, go back the way a user would (the read view's back control, or `page.goBack()`). Assert that the note is missing right away, again after the change-event refetch, and then after `page.reload()`. Keep the API listing check.

## P3 Low

- [ ] **CR-2 | `an action that finds all its messages starts no resync` checks only half of what its name says**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailActionServiceTests.kt:566-572` (the same gap is at `:633-634`)
    - Category: Tests
    - Problem: The test runs READ with every message present but only verifies `resync.request(any())` with `exactly = 0`. It does not check that `requestVerified` was never called. The flag case at `:630` checks `requestVerified` only after its second action.
    - Why it matters: If a regression made flag changes start a UID-set resync (an extra UID SEARCH for every read or flag), these tests would not catch it.
    - Suggested fix: Add `verify(exactly = 0) { resync.requestVerified(any()) }` to both places.

- [ ] **CR-3 | A conflict log line and a KDoc still say a move's cache update is "left to the next sync"**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt:368`, `:426`
    - Category: Quality
    - Problem: The `updateCache` KDoc says a conflict is "then left to the next sync". The INFO log at `:426` says "its cache update is left to the next sync after a concurrent change". For moves and deletes, `settle` now starts a verified background resync right away (the class KDoc at `:117-119` already says so). Only flag changes are still left to the next poll.
    - Why it matters: An operator who sees this INFO line after a move is told the opposite of what happens. That makes it harder to diagnose the owner's issue if it comes back.
    - Suggested fix: Make the text depend on the operation, or keep it neutral (for example, "cache update gave up after a concurrent change; a resync repairs it"). Update the KDoc to match.

- [ ] **CR-4 | The class KDoc of `MailFolderResync` has an unwrapped 164-character line**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailFolderResync.kt:17`
    - Category: Quality
    - Problem: The fix put the `requestVerified` sentence into line 17 without rewrapping the paragraph. The line is 164 characters long and the lines around it are about 120. ktlint does not flag it, because the existing long KDoc line at `MailHeaderSyncService.kt:240` passes as well.
    - Why it matters: Readability only. It is inconsistent with the wrapping in the rest of the module.
    - Suggested fix: Rewrap the KDoc paragraph.

## Review conclusion

Material issues found; fix before completing the task. CR-1 (P2) leaves part of criterion 4 ("going back" from the read view) without a real check. Either fix it or have the parent worker record a reasoned disposition. CR-2 to CR-4 are small cleanups. The server fix is correct as far as I could read it. Moves and deletes request a UID-set resync of the source, and for a move also of the target. That resync waits for the folder's sync lock, so it runs after a sync that was racing the action. It finds vanished rows without relying on the cached HIGHESTMODSEQ. All earlier P1 findings are resolved in code. Running the tests and checking the recorded evidence remain the parent worker's job.
