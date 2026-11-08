# Fix Report

Date: 2026-11-08

Task: cal-invitation-respond-android (answer calendar invitations in the Android event editor)

Scope: current task, finding CR-1 of the re-review in `reviews/cal-invitation-respond-android/review.md`. The parent accepted CR-2 as optional and said not to change it.

## Fixed findings

### CR-1 — Comments still describe every non-412 refusal as "not invited or organizer"

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/EventEditorViewModel.kt`

Problem:
The KDoc of `EditorMessage.RESPOND_FAILED` named only "organizer / not invited" as the reason for a refusal. The KDoc of `STALE` said "a 409 to an answer means the user can't answer (not invited, or organizer)". Both contradicted the earlier fix (neutral text plus a Reload action), because a 409 can also mean the event changed at the provider, is not synced yet, or is in a read-only calendar.

Fix:
I changed comments only. Both KDocs now list all the refusal reasons (not invited, organizer, changed at the provider, not synced yet, read-only) and say that a reload and another try may help. The `STALE` KDoc keeps its first sentence ("A stale `If-Match` (412)") and calls a 409 to an answer a refusal. No code changed.

Validation:
- `cd android && ./gradlew ktlintCheck`: Pass

## Not fixed

### CR-2 — The mail accounts are still fetched twice (earlier CR-3, accepted as optional)

Status:
Out of scope (optional)

Reason:
The parent accepted this as optional and told me not to change it. It does not change behavior.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintCheck`: Pass. No tests are needed because only comments changed.

Full test suite:
- Not run. Only comments changed, and the parent runs `.taskcheck`.

Lint/type-check/build:
- ktlintCheck: Pass

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
1 (CR-2, optional, not changed on the parent's instruction)

## Final status

All validated findings fixed and validation passed. The optional CR-2 was left unchanged on purpose.

## History: earlier fix pass (first review)

These findings came from the first review. They were fixed in commit 562f6bc93, and the re-review confirmed both fixes.

- Earlier CR-1 (a refused answer always said "not invited or organizer"): fixed. The text in `values` and `values-de` is now neutral, `Message` in `EventEditorScreen.kt` shows Reload for `RESPOND_FAILED`, and the screen test `a refused answer is explained` covers it. Validated with `cd android && ./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.calendar.edit.*'`: Pass.
- Earlier CR-2 (the answer section never appeared if the sources failed to load on open): fixed. `reload()` now calls `loadSources()` before `load()`. The test `a retry after the sources failed to load fetches them again, so the invitation can be answered` covers it. The same validation command passed.
- Earlier CR-3 (the mail accounts are fetched twice): skipped as optional. It is the same issue as the current CR-2.
