# Code Review

Date: 2026-11-01

Task: web-mail-read-delete - Mail read view: Delete button

Scope: committed task change `git diff ea78b8b06..fc8846c9a` (one commit). Uncommitted test-writer edits to `mail-read-page.spec.ts` in the working tree were not reviewed.

Requirements:
- tasks/web-mail-read-delete.md (acceptance criteria and the three recorded assumptions)
- SPEC.md §7.3 (mail reading, Gmail-like keys)
- docs/ARCHITECTURE.md, AGENTS.md "Project notes"
- Existing conventions: `web/src/app/features/mail/list/mail-list-page.ts`, `mail-list-store.ts`, `mail-shortcuts.ts`, `web/src/app/shell/shell.ts`

Git range:
- Branch: main
- HEAD: fc8846c9a
- Base/start: ea78b8b060c1be0666049382d4588b1fcf04b693
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | `#` deletes the message behind an open dialog**
    - File: `web/src/app/features/mail/read/mail-read-page.ts:205`
    - Category: Correctness
    - Problem: `onKeydown` only checks `state()` and `mailShortcutFor(event)`. It does not check `MatDialog.openDialogs`. The mail list does (`mail-list-page.ts:166`, `if (this.dialog.openDialogs.length) return;`), and so does the shell for its own hotkeys (`shell.ts:69-71`). Say a MatDialog is open over the read view and the focus is not in an input, for example the shortcut help that `?` opens (`ShortcutsDialog`, opened from `shell.ts:87`). Pressing `#` then deletes the message underneath and navigates away.
    - Why it matters: Criterion 3 says the key should work "as in the list", and the list ignores the key while a dialog is open. Here the result is an action the user did not intend. In Trash, `DELETE` is permanent (`MailActionService.kt:52`).
    - Suggested fix: Inject `MatDialog` and return early from `onKeydown` while `openDialogs.length > 0`, as the list does. Add a unit test that opens a dialog and checks that `#` sends no action.

## P3 Low

- [ ] **CR-2 | After Delete, the first Back press appears to do nothing**
    - File: `web/src/app/features/mail/read/mail-read-page.ts:194`
    - Category: Quality
    - Problem: `navigateByUrl(this.origin, { replaceUrl: true })` swaps the message's history entry for the list URL. In the usual flow (list, then message, then Delete) the history ends with two entries for the same list. The first Back press therefore stays on the same list. The test-writer's uncommitted test (working tree, "replaces the deleted message in the history") expects exactly this: "History: /notes, the folder, the folder". The deleted message never comes back, so criterion 2 is met. The leftover duplicate entry is a small navigation wart.
    - Why it matters: Users expect Back after a delete to leave the mail list. Here they have to press it twice.
    - Suggested fix: Optional. If the previous history entry is the origin (the navigation before this page was the list and nothing else came between), use `Location.back()`. Otherwise keep the current `replaceUrl` navigation. You could also accept the current behavior and record it with `tasks.py assume`.

## Notes (verified, no finding)

- `MailService` is `providedIn: 'root'`, so the read page can inject it. The page calls `apply('DELETE', [id])`, the same API the list uses, and the server is unchanged (recorded assumption: the task's `act` is `apply`).
- The button only renders when `state() === 'ready'`, is disabled while `deleting()` is true, and `delete()` also blocks re-entry, which covers double clicks and `#` during a request.
- Out-of-order and leave-while-running cases: a success after the user moved to another message, or after the page was destroyed, does not navigate. A failure always clears `deleting` in `finally`. Failures use `NotificationService.error`, the view's existing action-error pattern. A missing result for the id counts as a failure.
- "No longer shown there": `MailListStore` is provided per component, so returning recreates the list and reloads from the server. The server updates the header cache before it answers the action (`MailActionService.apply` / `updateCache`).
- `mailListOrigin` reads `previousNavigation.finalUrl` (the last successful navigation) once, at creation. Primary-outlet segments are flat, so it matches the real `/mail` and `/mail/folders/:folderId` URLs from `mail.routes.ts`. The list uses no query params that could get lost. Anything else falls back to `/mail`.
- The typing-target, AltGr and auto-repeat rules come from the shared `mailShortcutFor`.
- i18n: the 4 new units are in both `messages.xlf` and `messages.de.xlf` with German targets. `@@mailRead.unexpected` already existed.
- Focused tests: at the committed revision, the parent reported that `npm --prefix web run test:ci -- --include .../mail-read-page.spec.ts --include src/locale/messages-de.spec.ts` passed (69 tests). When I re-ran the read-page spec, the working tree held the test-writer's uncommitted edits. 67 passed and 1 failed: the new uncommitted history test (`mail-read-page.spec.ts:1220`, Back did not reach `/notes`). That failure belongs to the in-progress test changes, not to the reviewed commit. The parent should check it after the test-writer finishes.
- No criterion has evidence recorded yet (`tasks.py verify web-mail-read-delete`). The parent worker records it after the quality steps, so this is not a finding.

## Review conclusion

Material issues found; fix before completing the task.
