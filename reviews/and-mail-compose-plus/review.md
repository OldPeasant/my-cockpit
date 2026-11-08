# Code Review

Date: 2026-11-08

Task: and-mail-compose-plus - Android compose: recipient autocomplete, attach from Files

Scope: `git diff e60758be211736938ded1f7c25c41aa767f66077..e79e5586 -- . ':!tasks' ':!reviews'`. This is the final code after the review fixes: package `ch.sonensei.cockpit.mail.compose` (main and test), and `res/values{,-de}/strings_mail_compose.xml`. Committed changes only. The working tree was clean.

Requirements:
- tasks/and-mail-compose-plus.md (acceptance criteria #1-#4 and the three recorded ASSUMPTIONs)
- AGENTS.md project notes, docs/ARCHITECTURE.md conventions
- The previous review (CR-1..CR-4), fixes.md and tests.md in reviews/and-mail-compose-plus/

Git range:
- Branch: main
- HEAD: e79e5586
- Base/start: e60758be211736938ded1f7c25c41aa767f66077
- Commits reviewed: 5 (8e01c0d7, 1a20e46d, bf05d554, f1cb1c11, e79e5586)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Earlier findings re-checked

- CR-1 (suggestions hid only when the Subject text changed): resolved. `RecipientInput` calls `onLeave` when its text field changes from focused to unfocused (`RecipientInput.kt:182-186`). To, Cc and Bcc wire it to `viewModel::clearSuggestions`, and the Subject special case is gone. Compose tests cover To->Subject, To->Cc and fields that never had focus.
- CR-2 (an autosave or attach during a search failed with INVALID_ADDRESS): resolved. `store(strict = false)` and `content(strict = false)` leave out pending text that does not parse as an address (`ComposeSession.kt`, `leavesOut`, `typingIncomplete`, `typingRemoved`). The autosave, the device upload and `addFile` use the non-strict mode. Explicit save, send (including the conflict overwrite in `sendStored`) and leave stay strict. The `partial` flag keeps the session dirty, so the left-out fragment is still checked later. No new autosave is scheduled for `partial` alone, so it cannot loop. A field already marked invalid stays strict. Tests cover each of these paths.
- CR-3 (no filtering of chosen recipients, duplicate chips): resolved. `chosenAddresses` is case-insensitive. `suggest` filters the items per field, and `chooseSuggestion` adds no second chip but still clears the typed text.
- CR-4 (a tap anywhere on a chip removed it; broken line wrap): resolved. The chip's `onClick` is a no-op. Only the close icon removes the recipient (role Button, disabled with the chip). The `SuggestionRow` condition is on one line again. See CR-1 below for a remaining accessibility detail of this fix.
- The unpaired-quote fix in `formatRecipients` (`ComposeModels.kt`) is correct: a name with an odd number of `"` is quoted with its quotes removed. Names with paired quotes keep their old output, so the existing round-trip test still holds.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The chip's close icon is an 18 dp touch target, and the chip itself is announced as a clickable control that does nothing**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/RecipientInput.kt:152` (and `:142`)
    - Category: Quality
    - Problem: After the CR-4 fix the only way to remove a recipient is a `clickable` placed directly on an `Icon` of size `InputChipDefaults.IconSize` (18 dp). Nothing applies `minimumInteractiveComponentSize()`, so the target stays far below the 48 dp minimum. Also, `InputChip(onClick = {}, enabled = enabled)` is still exposed to TalkBack as an enabled, clickable chip whose action does nothing.
    - Why it matters: Removing a recipient on a phone takes a precise tap. For screen-reader users the chip offers a dead action, next to a small separate remove button.
    - Suggested fix: Give the remove control a 48 dp interactive size, e.g. an `IconButton` or `Modifier.minimumInteractiveComponentSize()` around the icon. Optionally, add a custom accessibility action "Remove <address>" on the chip.

## Notes

- Criteria checked against the code: #1 is met. Suggestions are debounced (200 ms) through `GET /api/v1/contacts/autocomplete`, as the ASSUMPTION records. Only the latest lookup publishes: the job is cancelled on every edit, on focus leave and on session start, and `mailCall` rethrows `CancellationException`. A failed lookup shows no suggestions. Chips use contact and recent icons. #2 is met. The Attach menu leads to the Files picker dialog, which has folder navigation, loading, empty, offline and failed states with retry. Older listings are cancelled, and `FilesRepository.call` rethrows cancellation. A picked file is sent as `fileId` in the PUT body. `AttachmentRefDto` leaves out unset fields, so exactly one of them is set. #3 is met: all 11 new keys are in both `values/` and `values-de/`.
- The changes to existing tests are justified and weaken nothing. The Attach button now opens a menu, so the tests click `ATTACH_DEVICE`. Draft recipients now end in `", "` so that they show as chips. CR-3 changed the expected suggestions in one debounce test, as intended.
- Evidence: `python tasks.py verify and-mail-compose-plus` still lists criteria #1-#4 as unrecorded. tests.md reports that the Android command `./gradlew ktlintCheck testDebugUnitTest lintDebug` passed (3871 tests). It also reports that `sh scripts/check.sh` did not finish in the test-writer's shell: Node v20.9.0 is below the version Angular requires, and fixes.md mentions a flaky, unrelated server test. Criterion #4 explicitly names `sh scripts/check.sh`. The parent worker must run it to the end and record the result before `done`. This reviewer did not run Gradle or the check.

## Review conclusion

No material issues found.
