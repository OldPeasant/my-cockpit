# Fix Report

Date: 2026-11-08

Task: and-mail-compose-plus - Android compose: recipient autocomplete, attach from Files

Scope: current task. Findings from reviews/and-mail-compose-plus/review.md (CR-1 to CR-4) and the production bug reported by the test-writer (failing tests in MailComposeRecipientsFilesEdgeCasesTest). Base 71c6a82d, task commits b85926f2, 21060804, 7445fa05.

## Fixed findings

### TEST-FAIL - A display name with one stray `"` gives a chip that never finishes

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/ComposeModels.kt`

Problem:
`formatRecipients` wrote a name such as `Pat "Quill` without quotes. The unpaired quote then hid the separator after it from the quote-aware splitting, so the chip never finished and saves failed with INVALID_ADDRESS (for suggestions and for reply recipients).

Fix:
A name with an odd number of `"` is now quoted with its inner quotes removed, the same way as a name containing `,;<>`. Names with paired quotes keep the old output. That way the existing round-trip test `a name with quotes but no separator reads back unchanged` (ComposeModelsEdgeCasesTest, `The "Boss"`) still holds. Quoting every `"` as the finding suggested would have broken that test, and paired quotes never break the splitting.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.compose.*'` - Pass (both previously failing tests now pass)

### CR-1 - Suggestions hide only when the Subject text changes

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/RecipientInput.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/MailComposeScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeRecipientsFilesScreenEdgeCasesTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeRecipientsFilesReviewFixesScreenTest.kt` (new)

Problem:
Nothing hid the suggestions when focus left a recipient field. Only typing into Subject did.

Fix:
`RecipientInput` takes an `onLeave` callback. An `onFocusChanged` on its text field calls it when the field goes from focused to unfocused (the first unfocused report at composition does not count). `RecipientRow` passes `callbacks.leaveRecipients` (wired to `viewModel::clearSuggestions`) for To, Cc and Bcc. The Subject special case is gone. The edge-case test that pinned the old Subject behavior now asserts that typing in Subject does not leave by itself. New Compose tests: focus moves from To to Subject -> leave; from To to Cc -> leave; fields that never had focus report no leave.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.compose.*'` - Pass

### CR-2 - An autosave or attach during a recipient search fails with INVALID_ADDRESS

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/ComposeSession.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeRecipientsFilesReviewFixesTest.kt` (new)

Problem:
`content()` parsed the whole field, including the text still being typed. So an autosave, a device upload or an attach from Files made while a fragment like "an" was typed marked the field invalid and failed.

Fix:
`store(strict)` and `content(strict)`. The autosave, `upload` and `addFile` use `strict = false`: when the pending text of a field does not parse as an address, it is left out. Finished recipients are still validated, and a finished invalid one still fails. A pending text that is a valid address is kept. Explicit save, send (including the conflict overwrite in `sendStored`) and leave stay strict. To keep a left-out fragment from being lost or sent unchecked, a `partial` flag keeps the session dirty after such a save. Explicit save, send and leave therefore re-run the strict check and report INVALID_ADDRESS, and the draft shows UNSAVED. No new autosave is scheduled for `partial` alone, so it does not loop. A field already marked invalid (the user's explicit save showed the problem) is still parsed strictly until it is edited. That way an autosave does not silently clear the error. This also keeps the existing tests `an invalid recipient blocks the save and marks its field until it is edited` and `an invalid bcc blocks the save and is cleared by editing it` green. Tests: autosave past AUTOSAVE_MILLIS with a fragment saves the rest with no INVALID_ADDRESS, and does not loop. An autosave keeps pending text that already is an address. A finished invalid recipient still fails the autosave. Explicit save, send and leave still check the fragment. Attach from Files with a fragment pending adds the file. A device upload with a fragment pending goes ahead.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.compose.*'` - Pass

### CR-3 - Suggestions are not filtered against recipients already chosen, and duplicates can be added

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/RecipientInput.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/MailComposeViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeRecipientsFilesTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeRecipientsFilesReviewFixesTest.kt` (new)

Problem:
Every suggestion was shown, and `chooseSuggestion` always appended one, so the same address could become two chips.

Fix:
New `chosenAddresses(field)` (lowercased addresses of the finished tokens). `suggest` drops suggestions already chosen in that field. `chooseSuggestion` does not add an address that is already present, but still clears the pending text. The debounce test in MailComposeRecipientsFilesTest typed `ben@example.org, an` and expected `ben` among the suggestions. It now expects only `ann`, with a comment, because that is the behavior CR-3 asks for. New tests cover case-insensitive filtering, no filtering across fields, and no duplicate when choosing.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.compose.*'` - Pass

### CR-4 - Tapping anywhere on a chip removes it; broken line wrap in SuggestionRow

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/RecipientInput.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeRecipientsFilesScreenTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeRecipientsFilesScreenEdgeCasesTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeRecipientsFilesReviewFixesScreenTest.kt` (new)

Problem:
The chip's `onClick` removed the recipient. The `!=\n null` wrap in `SuggestionRow` was hard to read.

Fix:
The chip's `onClick` does nothing. The trailing close icon is clickable on its own (`Role.Button`, the existing "Remove <address>" content description, disabled with the chip). The condition is on one line again. The two existing tests that removed a chip by clicking its body now click the close icon. New tests: a tap on the chip body and the chip's own click action keep the recipient; the close icon of the second chip removes only that one; while sending the close icon is disabled and removes nothing.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.compose.*'` - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.compose.*'` - Pass (all compose tests, including the two former TEST-FAIL tests and 15 new tests)

Full test suite:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` - BUILD SUCCESSFUL, 3698 tests, 0 failures

Lint/type-check/build:
- `cd android && ./gradlew ktlintFormat` applied. ktlintCheck and lintDebug pass (same run as above).

`.taskcheck`:
- `sh scripts/check.sh`, first run - Fail: 1 of 5205 server tests, `ch.sonensei.cockpit.bookmarks.PageFetcherSizeLimitTests` (it expected 50 x "4096 bytes" and got 43 plus 7 x TIMEOUT). This is a timing-dependent server test. No server code was changed (`git status` shows only Android files). The first rerun of just that class failed, the second passed (`./gradlew test --tests 'ch.sonensei.cockpit.bookmarks.PageFetcherSizeLimitTests'` -> BUILD SUCCESSFUL). It is flaky under machine load and not related to this task. The outcome of a second full `sh scripts/check.sh` run is in the fixer's final message to the parent worker.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed. The exception is one unrelated, flaky server test that showed up in the first `.taskcheck` run (see above).
