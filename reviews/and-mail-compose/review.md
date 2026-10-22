# Code Review

Date: 2026-10-22

Task: and-mail-compose - Android mail compose

Scope: committed task changes `git diff e6a9288b5f2fb4c5cafea94705d0b44da12906a7..58171348df62d8b9d3fdd06648069d373a2637eb -- android` (commits 5a09f26f0, 9bcb7b2fa, 6822849a4, 905b442ed, b89550fef, 58171348d). This round focuses on the fix commit 58171348d and on whether CR-1..CR-3 of the second review are resolved. Uncommitted test files from the concurrent test-writer were ignored.

Requirements:
- tasks/and-mail-compose.md acceptance criteria and the five recorded assumptions (`python tasks.py assumptions`)
- the second review's findings CR-1..CR-3 and reviews/and-mail-compose/fixes.md
- server contract: server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftService.kt, DraftController.kt
- AGENTS.md "Project notes"

Git range:
- Branch: main
- HEAD: 58171348df62d8b9d3fdd06648069d373a2637eb
- Base/start: e6a9288b5f2fb4c5cafea94705d0b44da12906a7
- Commits reviewed: 6

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Verification of the previous findings

- CR-1 (P2, overwrite after 412 matched kept attachments by part id): resolved. `overwrite` (`ComposeSession.kt:197-213`) now looks up each kept part id in the stale version (`current.attachments`). It then matches that file in the fresh version by name, size and inline flag, one match per kept file (`sameFileAs`, `:420-421`, shared with `withoutRemoved`). This matches the server's semantics: `DraftService.update` resolves `keep` part ids against the version named by `If-Match`, so the fresh version's ids are the right ones to send. The 412 save path and the send-Conflict path (`sendStored`, `:317-330`) both go through `overwrite`. Two tests in `MailComposeSecondReviewFixesTest` cover them with renumbered versions.
- CR-2 (P3, leave did not lock the screen): resolved. `leave()` sets `leaving = true` before its save (`:380`), and `MailComposeUiState.editable` is false while it is set (`MailComposeViewModel.kt:86`). So `edit`, `attach`, `send` and `save` are refused, and so are `discard` (`:347`) and a second `leave` (`:368`). A failed save clears `leaving` and sets `leaveBlocked`, which makes the screen editable again and offers to abandon. The screen disables Discard while leaving (`MailComposeScreen.kt:255`). Tests cover the locked state and the failed leave.
- CR-3 (P3, double Retry created two reply drafts): resolved. `load()` keeps its `Job` in `loading`, and `retry()` does nothing while that job is active (`:53-55`). The assignment happens after `launch` returns, but the check runs only on a later user tap, so it holds on `Dispatchers.Main.immediate` too. A test checks that `repository.start` is called exactly twice.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Leaving while uploads are queued closes the screen; their failures are never shown**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/ComposeSession.kt:366-392` (with `:246-256`)
    - Category: Correctness
    - Problem: `send()` refuses while `uploading` is not empty (`:281`), but `leave()` does not check it. While a file uploads, `leave()` sets `leaving`. Its `store()` waits for the mutex the upload holds, then finds nothing dirty and returns true, and the session becomes CLOSED. The `attach` coroutine keeps uploading the remaining queued files into the closed session. If one of them fails (`ATTACH_FAILED`, `TOO_LARGE`, `OFFLINE`), the problem is written to a session that is no longer shown. The user left believing that every picked file is in the draft.
    - Why it matters: A failed attachment goes unnoticed, so the user may later send the draft (from the web) without the file. This is the same kind of unseen failure as the CR-2 that was just fixed, but rarer, because it needs a slow upload and back pressed during it.
    - Suggested fix: Treat queued uploads as pending work while leaving. Either keep `leaving` set until `uploading` is empty and close afterwards (showing a failure and unlocking like a failed save), or refuse `leave()` while `uploading` is not empty, as `send()` does. Add a test: leave during a held upload whose result is `Failed`.

## Notes (not findings)

- The Android API paths, methods and `If-Match` headers (`MailComposeApi.kt`) match `DraftController` (`/api/v1/mail/drafts`, `/{id}`, `/{id}/attachments`, `/{id}/send`, `/messages/{id}/reply|reply-all|forward`).
- An untouched reply left during STARTING or EDITING is discarded. If that discard fails, the session still closes and the server-side draft stays in Drafts. This behavior is unchanged from earlier rounds and is consistent with the recorded assumption, so it is not reported.
- This review did not run Gradle, because another helper was writing tests in the same clone at the same time. The fixer reports 137 compose tests passing, with `ktlintCheck` and `lintDebug` clean. Recording evidence for criteria 1 and 2 (`tasks.py verify`) is the parent worker's job.

## Review conclusion

No material issues found.

CR-1..CR-3 of the second review are resolved. One low-priority finding remains (CR-1: failures of uploads still running when the user leaves are not shown). It can be fixed or accepted with a disposition in `quality.json`.
