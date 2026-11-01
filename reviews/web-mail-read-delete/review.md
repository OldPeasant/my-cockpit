# Code Review

Date: 2026-11-01

Task: web-mail-read-delete - Mail read view: Delete button

Scope: second review pass of the final committed revision. `git diff 6ba9af35d..0ec379e65 -- web/` (task commits a6dd247fe, 1e14d105f, 0ec379e65). The working tree had no uncommitted changes to tracked files when I reviewed it.

Requirements:
- tasks/web-mail-read-delete.md (4 acceptance criteria and the 3 recorded assumptions)
- SPEC.md §7.3 (mail reading, Gmail-like keys)
- docs/ARCHITECTURE.md, AGENTS.md "Project notes"
- First review (the earlier version of this file) and reviews/web-mail-read-delete/fixes.md
- Existing conventions: `web/src/app/features/mail/list/mail-list-page.ts`, `mail-shortcuts.ts`, `web/src/app/shell/shell.ts`

Git range:
- Branch: main
- HEAD: 0ec379e65509a175b8fed5629b8383e8dc8f5172
- Base/start: 6ba9af35dab9cc0c621ddb0f737c04d16ad9adf5 (merge-base with origin/main)
- Commits reviewed: 3

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Status of earlier findings

- **CR-1 (P2), `#` deletes the message behind an open dialog: fixed.** `mail-read-page.ts:207` now returns early while `this.dialog.openDialogs.length` is non-zero. This is the guard the list uses (`mail-list-page.ts:166`). `MatDialog` comes from the root injector, so dialogs the shell opens, such as the shortcut help, count too. The new test, "does nothing while a dialog is open, and deletes again once it is closed" (`mail-read-page.spec.ts:1124`), opens a real `MatDialog` and checks three things while it is open: no `preventDefault`, no action, no navigation. After the dialog closes, it checks that `#` deletes. According to fixes.md, the test fails when the guard is removed.
- **CR-2 (P3), the first Back press after Delete stays on the same list: accepted.** The `replaceUrl` navigation is a recorded task assumption, and `mail-read-page.spec.ts:1236` now pins the history behavior. This is not a defect against the criteria, so it stays closed.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Notes (verified, no finding)

- Production code changed since the first review only by the CR-1 guard (the `MatDialog` import, the injection, the early return and the doc comment). The 1e14d105f commit changes only tests and review files.
- Criterion 1: the button renders only in the `ready` state and is disabled while `deleting()` is true. `delete()` also blocks re-entry. It calls `MailService.apply('DELETE', [id])`, recorded as the assumption for the task's `act`, and the server is unchanged.
- Criterion 2: on success the page navigates to the origin read by `mailListOrigin` (`/mail` or `/mail/folders/:id`, else `/mail`). Failures (`ok=false`, a missing result, a result only for another id, a thrown error) show a toast through `NotificationService.error`, keep the view open and re-enable the button. A success after the user moved to another message, or left the page, does not navigate.
- Criterion 3: `#` uses the shared `mailShortcutFor`, which skips typing targets, chords and auto-repeat and accepts AltGr. It now also skips open dialogs, and it is ignored while the next message loads.
- Criterion 4: the tests cover the button, the shortcut (including typing targets, dialogs and auto-repeat), success navigation for every kind of origin, and the error cases.
- i18n: the 4 new `mailRead.*` units are in `messages.xlf` and `messages.de.xlf`, with German targets.
- Focused tests at HEAD (clean tree): `npm --prefix web run test:ci -- --include src/app/features/mail/read/mail-read-page.spec.ts --include src/locale/messages-de.spec.ts` gave 2 files and 76 tests passed. I did not run the full `npm run check` or `.taskcheck`; the parent worker runs those.
- Criterion evidence (`tasks.py verify`) is recorded by the parent worker after the quality steps, so a gap there is not a finding.

## Review conclusion

No material issues found.
