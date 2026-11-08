# Fix Report

Date: 2026-11-08

Task: web-mail-read-actions - Web mail read view: unread, flag, move, shortcuts r/a/f/c

Scope: current task; findings CR-1 to CR-4 of `reviews/web-mail-read-actions/review.md` (review of a62f12729), on top of the test-writer commit 5c306bd13

## Fixed findings

### CR-1 - New read-view behavior and the shortcut help registry have no unit tests

Files changed:
- none by the fixer (resolved by the test-writer commit 5c306bd13)

Problem:
The reviewed commit a62f12729 had no tests for mark unread, star, move, the read-view keys, `c` in the list, `MailListSession.markedUnread`/`flagged`, `PageShortcuts` and the new shortcut help sections.

Fix:
Verified only. Commit 5c306bd13 adds these tests in `mail-read-page.spec.ts` (+662 lines: Mark unread, Star, Move, keys r/a/f/c/u/s/v, dialog-open and not-ready guards), `mail-list-page.spec.ts`, `mail-list-session.spec.ts`, `mail-shortcuts.spec.ts`, `web/src/app/shell/page-shortcuts.spec.ts` and `shell.spec.ts`. They pass in the full web suite below.

Validation:
- `cd web && npm run check` - Pass (189 test files, 4337 tests)

### CR-2 - German star wording diverges from the existing translations

Files changed:
- `web/src/locale/messages.de.xlf`

Problem:
The new German units said "Markieren" / "Markiert" / "Markierung entfernen". The list's existing units for the same action say "Mit Stern markieren" / "Stern entfernen" / "Mit Stern", and "Markieren" also reads as selecting.

Fix:
Switched to the existing vocabulary: `mailRead.flag` "Mit Stern markieren", `mailRead.flagTitle` "Mit Stern markieren (s)", `mailShortcuts.flag` "Mit Stern markieren oder Stern entfernen", `mailRead.flagFailed` "Die Nachricht konnte nicht mit Stern markiert werden: {reason}", `mailRead.unflagFailed` "Der Stern konnte nicht entfernt werden: {reason}". The `reason` placeholders are unchanged. `mailRead.unflag` was removed (see CR-3).

Validation:
- `npx ng test --watch=false --include src/locale/messages-de.spec.ts` (with the two specs below) - Pass

### CR-3 - Star toggle changes its label while also exposing aria-pressed

Files changed:
- `web/src/app/features/mail/read/mail-read-page.html`
- `web/src/locale/messages.xlf`
- `web/src/locale/messages.de.xlf`
- `web/src/app/features/mail/read/mail-read-page.spec.ts`

Problem:
The button set `aria-pressed` and also switched its text between "☆ Star" and "★ Starred", so the label and the pressed state both carried the state.

Fix:
The button now has one translated label, `mailRead.flag` = "Star", which is always rendered. Before it sits a `span aria-hidden="true"` with the glyph ☆ or ★, so `aria-pressed` alone conveys the state to assistive tech. The source of `mailRead.flag` changed from "☆ Star" to "Star". The unit `mailRead.unflag` ("★ Starred") is no longer used and was removed from both xlf files. I edited the units by hand. Line numbers for the touched units come from a throw-away extraction into `.tmp/i18n-fix` (the real files were not regenerated). Spec: the visible-text expectations "★ Starred" became "★ Star". A new test checks that the label without aria-hidden content stays "Star" in both states, that the glyph switches from ☆ to ★, and that `aria-pressed` follows.

Validation:
- `npx ng test --watch=false --include src/app/features/mail/read/mail-read-page.spec.ts --include src/app/features/mail/list/mail-folders-store.spec.ts --include src/locale/messages-de.spec.ts` - Pass (3 files, 217 tests)

### CR-4 - Move-target filtering duplicated from MailFoldersStore.moveTargets

Files changed:
- `web/src/app/features/mail/list/mail-folders-store.ts`
- `web/src/app/features/mail/read/mail-read-page.ts`
- `web/src/app/features/mail/list/mail-folders-store.spec.ts`

Problem:
`MailReadPage.move()` re-implemented the "selectable and not the current folder" rule of `MailFoldersStore.moveTargets`.

Fix:
Added the pure exported function `moveTargetsOf(nodes, exceptId)` next to `folderTree`. Both `MailFoldersStore.moveTargets` and `MailReadPage.move()` now use it, and their behavior is unchanged. Added a small `moveTargetsOf` unit test covering tree order, non-selectable folders, the excluded id, an absent or null exception and the empty list.

Validation:
- focused run above - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include src/app/features/mail/read/mail-read-page.spec.ts --include src/app/features/mail/list/mail-folders-store.spec.ts --include src/locale/messages-de.spec.ts` - Pass, 3 files, 217 tests

Full test suite:
- `cd web && npm run check` (api:check, lint with max-warnings 0, e2e typecheck, test:ci, ng build) - Pass: "All files pass linting.", 189 test files / 4337 tests passed, build complete. The existing initial-bundle budget warning was there before these changes.

Lint/type-check/build:
- included in `npm run check` above - Pass. `npx prettier --write` on the changed TS/HTML/spec files made no further changes.

`.taskcheck`:
- Not run by the fixer (`sh scripts/check.sh` also runs Gradle for server and android, which the parent worker runs)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
