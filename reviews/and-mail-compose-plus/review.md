# Code Review

Date: 2026-11-08

Task: and-mail-compose-plus - Android compose: recipient autocomplete, attach from Files

Scope: `git diff 6610a72c..6eb3ee8a -- android` (task commits 5eea90d2, 6eb3ee8a; on the current branch they appear as 608fbf87 and 6cf1735c with the same Android content). Package `ch.sonensei.cockpit.mail.compose`, the strings in `res/values{,-de}/strings_mail_compose.xml`, and the tests. The later Android commits on main (calendar edit) are out of scope.

Requirements:
- tasks/and-mail-compose-plus.md (acceptance criteria and the three recorded ASSUMPTIONs)
- SPEC.md §7.3/§7.4 (drafts, autosave), docs/ARCHITECTURE.md, AGENTS.md
- api/openapi.yaml: `GET /api/v1/contacts/autocomplete`, `ContactSuggestionDto`, `DraftAttachmentRequest`
- Web reference: web/src/app/features/mail/compose/recipient-input.ts, file-picker-dialog.ts, mail-compose.service.ts

Git range:
- Branch: main
- HEAD: 6cf1735c
- Base/start: 6610a72c59359b5821d1121e0e35477128e46fe2
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 2 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Suggestions only hide when the Subject text changes, not when focus leaves the recipient fields**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/MailComposeScreen.kt:421`
    - Category: Correctness
    - Problem: `MailComposeCallbacks.leaveRecipients` is documented as "Focus left the recipient fields: their suggestions hide". The only call site is the Subject field's `onValueChange`. Nothing hides the panel on focus change (`RecipientInput` has no `onFocusChanged`), when the user taps into the body or Subject without typing, when the body text changes (line 434 passes `callbacks.body` directly), or after IME Done (`finishRecipient` leaves the pending text empty, which does clear it, but only when there was pending text). If the user types "an" in To and then moves to the body, the To suggestions stay open above the form until the user types into Subject or back into a recipient field.
    - Why it matters: A stale suggestion list stays on screen and pushes the form down. One tap on it changes a recipient field the user has already left. The web closes its autocomplete on blur.
    - Suggested fix: Call `clearSuggestions` from an `onFocusChanged` on the recipient `BasicTextField` when focus is lost (or track the focused field), and drop the special case in Subject. Add a Compose test for it.

- [ ] **CR-2 | Autosave during a recipient search marks the field invalid and fails the save**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/ComposeSession.kt:131` (with `content()` at :222 and the pending text from `RecipientInput.kt`)
    - Category: Correctness
    - Problem: Following the chips ASSUMPTION, the text being typed (`RecipientEntry.pending`) stays part of `state.to/cc/bcc`. `content()` parses the whole field. The autosave runs 10 s after the first unsaved edit, not 10 s after the last one. So when it fires while the user is typing a search fragment such as "ann", it sets `invalid = {TO}` and `ComposeProblem.INVALID_ADDRESS`, and the draft stays unsaved. The same happens to "Attach from Files" and device uploads started while a fragment is typed (`addFile` -> `store()` returns false, and the file is not added). The plain field had the same parsing, but autocomplete now makes "type a name fragment and wait" the normal flow. On the web the typed text is not part of `recipients()` until it is committed, so an autosave never sees it.
    - Why it matters: In normal use the user gets an error banner and a red field in the middle of a search, the autosave silently stops working, and a file pick can be dropped.
    - Suggested fix: Leave out `pending` text that is not yet a valid address when building autosave content (e.g. use only `recipientEntry(field).tokens` plus the pending text if it parses). Keep the strict validation for explicit save and send. Add a ViewModel test: type a fragment, advance past `AUTOSAVE_MILLIS`, assert that there is no INVALID_ADDRESS and that the draft is saved.

## P3 Low

- [ ] **CR-3 | Suggestions are not filtered against recipients already chosen, and choosing one can add a duplicate**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/MailComposeViewModel.kt:189`, `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/RecipientInput.kt:69`
    - Category: Correctness
    - Problem: The web `recipient-input.ts` drops suggestions whose address is already a recipient of the field, and `add()` skips duplicates (case-insensitive). Android shows every suggestion, and `chooseSuggestion` always appends, so the same address can end up as two chips.
    - Why it matters: Small web/Android parity gap for a criterion the task is about. Duplicates reach the draft.
    - Suggested fix: Filter `items` by the field's current tokens (lowercased addresses) before publishing them, and make `chooseSuggestion` a no-op for an address that is already present.

- [ ] **CR-4 | Tapping anywhere on a recipient chip removes it**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/RecipientInput.kt:126`
    - Category: Quality
    - Problem: The whole `InputChip`'s `onClick` calls `removeRecipient`, so one stray tap on a chip deletes the recipient with no undo. The web removes only through the chip's remove button. `SuggestionRow` (lines 108-116) also has a broken `!=\n null` line wrap, which hurts readability.
    - Why it matters: Recipients are easy to lose by accident on a touch screen.
    - Suggested fix: Remove only through the trailing icon (make it an `IconButton`, or handle the click on the icon only), or offer undo. Rejoin the condition in `SuggestionRow`.

## Notes

- Criteria checked: #1 is met (debounced lookup through `/contacts/autocomplete`, which merges contacts and correspondents on the server as recorded in the ASSUMPTION; only the latest lookup can publish because of job cancellation, and `mailCall` rethrows `CancellationException`; chips; contact and recent icons). #2 is met (Attach menu, then the Files picker dialog with folder navigation, retry and empty/error states; the file is attached through `fileId`). #3 is met (all 11 new keys are in both `values/` and `values-de/`).
- API contract: `AttachmentRefDto` leaves out the unset `partId`/`fileId` (`EncodeDefault.NEVER`), which matches "exactly one of" in `DraftAttachmentRequest`. `autocomplete(q, limit=8)` is within the allowed 1-50. `ContactSuggestionDto` fields match the spec, including the nullable `email`.
- The changes to existing tests are justified: the Attach menu adds a step, and draft recipients now end in `", "`. No test was weakened.
- Evidence: `tasks.py verify` lists no recorded evidence yet for criteria #1-#4. The Gradle and `scripts/check.sh` runs for #4 must be recorded before `done`. This reviewer could not run Gradle (the command was not permitted in this session), so #4 is unverified here.

## Review conclusion

Material issues found; fix before completing the task.
