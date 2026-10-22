# Fix Report

Date: 2026-10-22

Task: and-mail-compose - Android mail compose

Scope: findings CR-1..CR-3 of the second review (`reviews/and-mail-compose/review.md`). The parent worker accepted all three as in scope. The first round's report (CR-1..CR-7 of the first review, fixed in commit 0a0d6220f) is in git history at commit 1266e4d10.

## Fixed findings

### CR-1 (P2) - Overwrite after 412 matched kept attachments by positional part id

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/ComposeSession.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeSecondReviewFixesTest.kt` (new)

Problem:
`overwrite` filtered `content.keep` (part ids of the stale stored version) against the part ids of the freshly fetched version. The server renumbers MIME parts whenever it rebuilds a version, so a stale id could name a different attachment. On the send-Conflict path, the result was sent right away.

Fix:
`overwrite` now looks up each kept part id in the stale version (`current.attachments`). It then finds that file in the fresh version by name, size and inline flag, and keeps one match per kept attachment. It sends the fresh version's part ids for those files as `keep`. The rule is shared with `withoutRemoved` through a new private `DraftAttachment.sameFileAs`. Both the 412 save path and `sendStored` use `overwrite`, so both are fixed.

Validation:
- `a save over a version that renumbered its parts keeps the attachments by file, not by part id` (the 412 save path: stale [a=2, b=3] with a removed, fresh [b=2, c=3], so the test expects `keep == ["2"]`) - Pass
- `a send after a conflict with a renumbered version sends the attachments kept on screen` (send Conflict: stored [b=2], fresh [c=2, b=3], so the test expects `keep == ["3"]`, then a send of the overwritten version) - Pass

### CR-2 (P3) - Leaving did not lock the screen

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/MailComposeViewModel.kt` (new `MailComposeUiState.leaving`; `editable` is false while it is set)
- `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/ComposeSession.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/MailComposeScreen.kt` (the Discard menu item is disabled while leaving)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeSecondReviewFixesTest.kt`

Problem:
`leave()` stayed in EDITING while its save ran, then set CLOSED unconditionally. A send started during the save was closed away while it was still running, and edits made during the save were lost.

Fix:
From the EDITING phase, `leave()` now sets `leaving = true` before its save starts. Edits, attach, save, send and discard are refused while the flag is set, and so is a second `leave()`. When the save finishes, the session closes and clears the flag. If the save fails, the flag is cleared and `leaveBlocked` is set, so the screen becomes editable again and still offers to leave without saving. `abandon()` is not affected.

Validation:
- `a send or an edit while leaving saves is ignored and the screen closes` - Pass
- `a leave that cannot save unlocks the screen and offers to leave without saving` - Pass

### CR-3 (P3) - A double Retry during the reply start created two drafts

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/ComposeSession.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeSecondReviewFixesTest.kt`

Problem:
`retry()` checked only `stored == null && !ended`, so two taps before the coroutine ran both called `repository.start`.

Fix:
The session keeps the load `Job` (`loading`). `retry()` does nothing while that job is active.

Validation:
- `a double retry while a reply is prepared starts it only once` (it checks that `repository.start` runs exactly twice: the first failed start plus one retry) - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintFormat ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.compose.*' --rerun-tasks` - Pass. The run had 137 tests in 14 classes, with 0 failures, 0 errors and 0 skipped. That includes the 5 new tests in `MailComposeSecondReviewFixesTest`.

Full test suite:
- Not run by the fixer. The parent runs `scripts/check.sh`.

Lint/type-check/build:
- `ktlintCheck` and `lintDebug` - Pass (in the same run).

`.taskcheck`:
- Not run, as the parent instructed. The parent runs it.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
