# Fix Report

Date: 2026-11-08

Task: and-mail-actions - Android mail: unread, flag, move, spam, multi-select

Scope: current task. Findings CR-1 and CR-2 from `reviews/and-mail-actions/review.md`, plus three failing tests in `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailActionsViewModelTest.kt` (added by the earlier test-writer run) reported by the parent worker. Base commit 8f9e2adfe1a7843513a980fb1d351338758aecf7, task commits 672080b11, 8af826573, e1268851d.

## Fixed findings

### CR-1 - Undoing a bulk mark/flag changes mails the action never changed

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailActionsViewModelTest.kt`

Problem:
A bulk READ/UNREAD/FLAG/UNFLAG recorded every selected mail in the undo, including mails already in the target state. Undo then sent the inverse to all of them, so it removed flags or read marks the user had set before.

Fix:
`perform` now sends a marking action only to the listed mails it actually changes (`action.applyTo(m) != m`). Only those mails go into the `MailUndo`, so the undo's inverse reaches only them. When no selected mail would change, nothing is sent, no undo is offered and the selection ends.

Regression tests added: "a bulk flag of a mixed selection is sent and undone only for the mails it flagged", "a bulk mark read of a mixed selection is sent and undone only for the unread mails" and "a mark that changes no mail sends nothing, offers no undo and ends the selection".

Test changes required by this fix: two existing tests applied UNREAD to mails the fixture already has unread (`seen = false`). Under the corrected behaviour that is a no-op, so nothing is sent. The tests were changed to exercise READ, a mark that does change those mails. Their intent stays the same: "bulk flag and unflag change only the selected mails" and "an offline or refused bulk action brings every mail back with the action's message" (still MARK_FAILED). No assertion was weakened.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*'` - Pass

### CR-2 - Undo of a mark does nothing after the mailbox was switched

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailActionsViewModelTest.kt`

Problem:
The undo of a mark went through `perform`, which looks the mails up in the list currently shown. After a mailbox switch it found none (or only some), so it sent nothing (or too little) and still dropped the undo.

Fix:
A new `unmark(undo, inverse)` works like `moveBack`. It sends the inverse for every mail in `undo.mails`, whatever mailbox is shown, and changes only the rows the current list shows. When the answer comes, it re-applies the inverse to the mails that succeeded (in case a reload overtook it). For the mails that failed, it puts the original mark back and shows UNDO_FAILED.

Regression tests added: "undoing a mark after another mailbox was opened still sends the inverse for every mail" and "a partly failed undo of a mark puts the mark back on the mails it failed for".

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*'` - Pass

### T-1 - "mails the server could not move come back in their places, the others stay gone, and no undo is offered" (production fixed)

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt`

Problem:
The parent reported this failure (`expected [..02, ..05] but was [..05, ..02]`) under the test "undoing an archive of mails from two accounts moves each back to its own folder". That test passes. The failure comes from this test, at line 342. After archiving mails 1, 2 and 3 of [1, 2, 3, 5], with only 2 refused, mail 2 came back at its absolute old index 1, below mail 5. Order matters here: the list is newest first, and the test (and the KDoc of `restored`) promise the mails "come back in their places". The test is correct.

Fix:
`restored` now subtracts from a returning mail's old index the number of acted mails that stood above it and stay away (they are neither returning nor listed). Mail 2 therefore goes back above mail 5. Undo restores, where all mails return, behave as before.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*'` - Pass

### T-2 - "when an older action answers after a newer one, the newer one stays offered to undo" (production fixed)

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt`

Problem:
The `MailListUiState.undo` contract says "the last action that can be undone, offered until the next one". When an older action's server answer came after a newer one's, it replaced the newer action's undo (ARCHIVE instead of DELETE). The test is correct.

Fix:
Each action and each undo takes the next number (`actionCount`) when it starts. An answer offers its undo only while its action is still the newest.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*'` - Pass

### T-3 - "a partly failed undo of a move keeps only the mails moved back, and reloads" (production fixed)

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt`

Problem:
When an undo of a move partly failed, `moveBack` set UNDO_FAILED and then reloaded. The reload's success cleared the message, so the user was never told that a mail could not be moved back and was gone from the list. The test is correct.

Fix:
`load` takes an optional message to show after a successful load. `moveBack` passes UNDO_FAILED when some mails failed. A failed reload still shows OFFLINE/FAILED, as before.

Validation:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*'` - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*'` - Pass (after the two test adaptations described under CR-1)

Full test suite:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` - BUILD SUCCESSFUL

Lint/type-check/build:
- Included above (ktlintCheck, lintDebug) - Pass

`.taskcheck`:
- `sh scripts/check.sh` - Pass ("all checks passed")

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
