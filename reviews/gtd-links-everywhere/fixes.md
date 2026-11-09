# Fix Report

Date: 2026-11-09

Task: gtd-links-everywhere - Linked items on projects and files; follow-up by mail (web + Android)

Scope: current task. Findings TW-4 and TW-5 from `reviews/gtd-links-everywhere/tests.md` and CR-1, CR-2 and CR-4 from `reviews/gtd-links-everywhere/review.md` (final-pass reports committed in f5e67ddb0). CR-3 is out of scope: the parent worker deferred it to the new task `and-waiting-contact-mail`.

Earlier fix round (same task, previous reports): TW-1 (Android email regex kept parentheses), TW-2 (project links toggle broke layout tests), TW-3 (lint UseKtx) were fixed by the parent in 3a36e2834 and 99fe47199. The earlier CR-2 (Android linked-items panel collapsed after returning from a linked item) and the earlier CR-3 (web "Draft email" navigating after the page was left) were fixed by the fixer in that round. Those IDs refer to the earlier review, not to the current one.

## Fixed findings

### TW-4 / CR-1 - Web emailIn keeps surrounding parentheses or brackets

Files changed:
- `web/src/app/features/gtd/lists/list-views.ts`

Problem:
The `emailIn` character classes excluded whitespace, `<>"',;` but not `()[]`, so "Anna (anna@example.test)" gave `(anna@example.test)`, an address the compose page rejects. Android was fixed for this earlier (TW-1).

Fix:
All parts of the regex now also exclude `(`, `)`, `[` and `]`, the same character class as Android. The JSDoc names the parentheses form.

Validation:
- `npx ng test --watch=false --include src/app/features/gtd/lists/list-views.spec.ts ...` (web/) - Pass (the existing failing emailIn cases for parentheses and brackets now pass)

### TW-5 - Trailing sentence full stop kept in the address (web and Android)

Files changed:
- `web/src/app/features/gtd/lists/list-views.ts`
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/WaitingScreen.kt`

Problem:
"Reply from anna@example.org." gave `anna@example.org.` on both clients.

Fix:
The last part of the address (after the final dot of the domain) must now end with a character other than `.`: `[^X]*[^X.]` instead of `[^X]+`, where X is the excluded set. Backtracking drops any trailing full stops; "alex@localhost." still yields no address, and plus-addressing and the other forms are unchanged. Same change on web (`emailIn`) and Android (`EMAIL`), with the doc comments updated.

Validation:
- web: list-views.spec.ts emailIn "Reply from anna@example.test." case - Pass
- Android: `WaitingDraftEmailTest` "a sentence ending after the address does not add its full stop to it" - Pass (8 of 8 tests in the class pass)

### CR-2 - Opening a linked item from the web file preview leaves the dialog on top

Files changed:
- `web/src/app/features/files/preview/preview-dialog.ts`
- `web/src/app/features/files/preview/preview-dialog.spec.ts`

Problem:
The preview is a full-window MatDialog; its default `closeOnNavigation` only reacts to popstate, so following a `routerLink` in the linked-items panel changed the route under the still open preview.

Fix:
`PreviewDialog` subscribes to `Router.events` and closes its dialog on `NavigationStart` (with `takeUntilDestroyed`), the same pattern the calendar event overlay already uses (`features/calendar/overlay/event-links.ts`). The class doc says so. The spec now provides a router (`provideRouter([{ path: '**', children: [] }])`) and a new test, "closes the preview when a linked item is followed, so the item is not hidden under it", flushes one linked note, checks the dialog is not closed yet, clicks the row, and expects the router URL to be the note's deep link and the dialog closed.

Validation:
- `npx ng test --watch=false --include src/app/features/files/preview/preview-dialog.spec.ts ...` (web/) - Pass

### CR-4 - Over-long compose page doc comment line

Files changed:
- `web/src/app/features/mail/compose/mail-compose-page.ts`

Problem:
One line of the class doc comment was about 140 characters, the rest wraps at about 100.

Fix:
Re-wrapped the paragraph to about 100 characters; wording unchanged.

Validation:
- `npm run lint` (part of `npm run check`) - Pass

## Not fixed

### CR-3 - Android "Draft email" ignores the linked contact

Status:
Out of scope

Reason:
The parent worker deferred it to the new task `and-waiting-contact-mail` (commit 262d65e8e). No code changed for it here.

## Validation summary

Focused tests:
- `npx ng test --watch=false --include src/app/features/gtd/lists/list-views.spec.ts --include src/app/features/files/preview/preview-dialog.spec.ts --include src/app/features/mail/compose/mail-compose-page.spec.ts` (web/) - 3 files, 125 tests passed
- `./gradlew ktlintCheck testDebugUnitTest --tests '*WaitingDraftEmailTest*' --tests '*ListScreensTest*' lintDebug` (android/) - BUILD SUCCESSFUL; WaitingDraftEmailTest 8/8, ListScreensTest 16/16, ktlint and lintDebug pass

Full test suite:
- `npm run check` (web/: api:check, lint, e2e typecheck, full test:ci, ng build) - Pass; reached and completed `ng build` (the "initial bundle exceeded 500 kB budget" warning is pre-existing and only a warning)
- Full Android unit suite and server: Not run by the fixer (only the Android files above changed; the server is untouched)

Lint/type-check/build:
- Web lint, e2e typecheck and build, Android ktlint and lintDebug - Pass (in the commands above)

`.taskcheck`:
- Not run by the fixer (its web and Android parts touched by these fixes were run above); the parent worker runs it before `done`

Remaining unresolved findings:
0 in scope (CR-3 deferred to `and-waiting-contact-mail`)

## Final status

All validated findings fixed and validation passed.

- TW-4 / CR-1: Fixed (list-views.ts), covered by the existing failing spec cases that now pass.
- TW-5: Fixed on web (list-views.ts) and Android (WaitingScreen.kt), covered by the existing failing tests that now pass.
- CR-2: Fixed (preview-dialog.ts), with a new regression test in preview-dialog.spec.ts.
- CR-4: Fixed (comment re-wrap only).
- CR-3: Not fixed, out of scope (deferred).

Production code changed after the review, so the test-writer and code-reviewer evidence is stale and they must run again on the final revision.
