# Code Review

Date: 2026-11-08

Task: web-mail-read-actions - Web mail read view: unread, flag, move, shortcuts r/a/f/c

Scope: committed task changes `git diff 8e7ae960e..a62f12729` (one commit, a62f12729); working tree clean at review time

Requirements:
- tasks/web-mail-read-actions.md (acceptance criteria and recorded assumptions)
- SPEC.md section 7.3 (mail read view, Gmail-like keys)
- AGENTS.md "Project notes", docs/ARCHITECTURE.md conventions
- Parent instructions (focus: races, keyboard handling, i18n, accessibility, tests)

Git range:
- Branch: main
- HEAD: a62f12729
- Base/start: 8e7ae960e4811726fafaaa1970a6e86b9b4f0a1a
- Commits reviewed: 1

Summary: 0 P0, 1 P1, 0 P2, 3 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | New read-view behavior and the shortcut help registry have no unit tests in the committed change**
    - File: `web/src/app/features/mail/read/mail-read-page.spec.ts:1428`
    - Category: Tests
    - Problem: The commit's only test changes are the button-order expectation in `mail-read-page.spec.ts` (three new labels) and the key-mapping table in `mail-shortcuts.spec.ts`. Nothing exercises: `markUnread()` (waits for the pending mark-read, calls `session.markedUnread`, navigates back with `replaceUrl`, failure keeps the message open), `toggleFlag()` (FLAG vs UNFLAG, message and session updated, failure toast), `move()` (folders loaded per account, current folder excluded, dialog cancel, target passed to `apply`, then the `leave` path, failure while loading folders resets `triaging`), the read-view keys `r`/`a`/`f`/`c`/`u`/`s`/`v` (including that they do nothing while a dialog is open or before the message is ready), `c` in the list and `r`/`a`/`f` being ignored there, `MailListSession.markedUnread`/`flagged`, `PageShortcuts.register` (removal on destroy) and the new sections in `ShortcutsDialog`. Focused run `npx ng test --include` on the read page, list page and shortcuts specs: 276 passed, i.e. the suite is green without covering the new behavior.
    - Why it matters: Acceptance criterion 4 ("Unit tests cover the new behavior") is not met by the reviewed commit; the mark-read/mark-unread ordering and the move busy state are exactly the regression-prone parts.
    - Suggested fix: The concurrent test-writer pass should add these tests (read page, list page, session, `page-shortcuts`, `shortcuts-dialog`); this finding is resolved once they are committed and pass.

## P2 Medium

None.

## P3 Low

- [ ] **CR-2 | German star wording diverges from the existing translations**
    - File: `web/src/locale/messages.de.xlf` (units `mailRead.flag`, `mailRead.unflag`, `mailRead.flagTitle`, `mailRead.flagFailed`, `mailRead.unflagFailed`, `mailShortcuts.flag`)
    - Category: Quality
    - Problem: The existing German units for the same concept use "Mit Stern markieren" (Star), "Stern entfernen" (Unstar) and "Mit Stern" (Starred). The new units use "Markieren", "Markiert", "Markierung entfernen" and "Markieren oder Markierung entfernen". In German "Markieren" also means selecting/highlighting, and the list uses "auswählen" for selection, so the same action has two names.
    - Why it matters: Inconsistent, ambiguous wording between the list and the read view for the same action.
    - Suggested fix: Reuse the existing vocabulary, e.g. "☆ Mit Stern markieren", "★ Mit Stern", "Mit Stern markieren oder Stern entfernen", "Die Nachricht konnte nicht mit Stern markiert werden: {reason}", "Der Stern konnte nicht entfernt werden: {reason}".

- [ ] **CR-3 | Star toggle changes its label while also exposing aria-pressed**
    - File: `web/src/app/features/mail/read/mail-read-page.html:315`
    - Category: Quality (accessibility)
    - Problem: The button sets `aria-pressed` and at the same time switches its text between "☆ Star" and "★ Starred"; the `title` stays "Star (s)" in both states. A screen reader announces e.g. "Starred, toggle button, pressed", and the pressed state and the label both encode the state, so the label no longer names the action.
    - Why it matters: WAI-ARIA toggle-button guidance: a toggle keeps a constant label when `aria-pressed` conveys the state; the current combination is confusing for assistive tech users.
    - Suggested fix: Either keep a constant accessible label ("Star") with `aria-pressed` and change only the icon, or drop `aria-pressed` and keep the action-style label ("Star" / "Remove star").

- [ ] **CR-4 | Move-target filtering duplicated from MailFoldersStore.moveTargets**
    - File: `web/src/app/features/mail/read/mail-read-page.ts:299`
    - Category: Quality
    - Problem: `move()` re-implements `selectable && id !== currentFolder` over `folderTree(...)`, the same rule as `MailFoldersStore.moveTargets` (`web/src/app/features/mail/list/mail-folders-store.ts:143`).
    - Why it matters: If the list's notion of a valid move target changes (e.g. excluding a role), the read view silently diverges.
    - Suggested fix: Extract a small pure helper (e.g. `moveTargets(nodes, exceptId)`) next to `folderTree` and use it from both places.

Checked without findings: modifier, typing-target, composing and repeat handling (all through `mailShortcutFor`, so Ctrl+R/Ctrl+F/Ctrl+A/Ctrl+C keep their browser meaning); no conflict with the shell keys (`q`, `?`, Ctrl+K); dialog-open guard on both pages; `triaging` stays set across folder loading and the move dialog and is handed to `run()` synchronously after the dialog closes, so no second action can slip in; navigation or destroy during the folder load or the action is guarded by the id/destroyed checks (the dialog closes on navigation); mark unread awaits the pending mark-read; every new UI string has English and German units with matching `reason` placeholders and no duplicate ids.

## Review conclusion

Material issues found; fix before completing the task (CR-1: the committed change lacks the unit tests the acceptance criteria require; expected to be closed by the test-writer pass).
