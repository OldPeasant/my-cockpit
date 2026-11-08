# Code Review

Date: 2026-11-08

Task: web-mail-read-actions - Web mail read view: unread, flag, move, shortcuts r/a/f/c

Scope: full committed task diff `git diff 8e7ae960e..bfe1687b4` (implementation a62f12729, tests 5c306bd13, review fixes bfe1687b4); second pass on the final code. Working tree was clean at review time; only committed code was reviewed.

Requirements:
- tasks/web-mail-read-actions.md (acceptance criteria and recorded assumptions)
- SPEC.md section 7.3 (mail read view, Gmail-like keys)
- AGENTS.md "Project notes", docs/ARCHITECTURE.md conventions
- Previous review (this file, revision a62f12729) and reviews/web-mail-read-actions/fixes.md
- Parent instructions (verify earlier findings; look for new issues in criteria, races, keyboard handling, i18n English and German incl. messages-de.spec.ts, accessibility, maintainability)

Git range:
- Branch: main
- HEAD: bfe1687b4
- Base/start: 8e7ae960e4811726fafaaa1970a6e86b9b4f0a1a
- Commits reviewed: 3

Summary: 0 P0, 0 P1, 0 P2, 0 P3 (open findings; the four findings of the first pass are resolved, see below)

## P0 Critical

None.

## P1 High

None open.

- [x] **CR-1 | New read-view behavior and the shortcut help registry had no unit tests** - Resolved
    - File: `web/src/app/features/mail/read/mail-read-page.spec.ts`, `web/src/app/features/mail/list/mail-list-page.spec.ts`, `web/src/app/features/mail/list/mail-list-session.spec.ts`, `web/src/app/features/mail/list/mail-shortcuts.spec.ts`, `web/src/app/shell/page-shortcuts.spec.ts`, `web/src/app/shell/shell.spec.ts`
    - Category: Tests
    - Status: Commit 5c306bd13 adds tests for Mark unread (waits for the pending mark-read, session update, back with replaceUrl, failure keeps the message open), Star/Unstar (FLAG vs UNFLAG, failure toasts, message changed while running), Move (per-account folders, current folder excluded, cancel, target passed, failure while loading folders), the read-view keys r/a/f/c/u/s/v with the dialog-open and not-ready guards, `c` in the list with r/a/f ignored there, `MailListSession.markedUnread`/`flagged`, `PageShortcuts` registration and removal, and the new sections of the ? overview. Commit bfe1687b4 adds a `moveTargetsOf` unit test and an accessible-label test for the Star toggle. Existing assertions were only adapted to the changed Star label, not weakened. Focused run by the reviewer: `cd web && npx ng test --watch=false --include` on the read page, folders store, list page, list session, mail shortcuts, page-shortcuts, shell and messages-de specs -> 8 files, 435 tests passed.

## P2 Medium

None.

## P3 Low

None open.

- [x] **CR-2 | German star wording diverged from the existing translations** - Resolved
    - File: `web/src/locale/messages.de.xlf` (units `mailShortcuts.flag`, `mailRead.flag`, `mailRead.flagTitle`, `mailRead.flagFailed`, `mailRead.unflagFailed`)
    - Category: Quality
    - Status: The units now use the list's vocabulary ("Mit Stern markieren", "Mit Stern markieren oder Stern entfernen", "Die Nachricht konnte nicht mit Stern markiert werden: {reason}", "Der Stern konnte nicht entfernt werden: {reason}"), matching the existing units "Mit Stern markieren" / "Stern entfernen" / "Mit Stern". Placeholders unchanged; messages-de.spec.ts passes (no missing, stale or duplicate units, placeholders match).

- [x] **CR-3 | Star toggle changed its label while also exposing aria-pressed** - Resolved
    - File: `web/src/app/features/mail/read/mail-read-page.html:117`
    - Category: Quality (accessibility)
    - Status: The button keeps one translated label ("Star" / "Mit Stern markieren"); the state glyph sits in an `aria-hidden` span, so `aria-pressed` alone conveys the state. The unused unit `mailRead.unflag` was removed from both xlf files, and the source of `mailRead.flag` changed to "Star" in both. The hand-edited location line numbers (html 128,129; ts 576 and 578) match the committed sources. No other code or e2e test references the removed "★ Starred" text (the list's `aria-label="Starred"` row icon is a different unit).

- [x] **CR-4 | Move-target filtering duplicated from MailFoldersStore.moveTargets** - Resolved
    - File: `web/src/app/features/mail/list/mail-folders-store.ts:150`, `web/src/app/features/mail/read/mail-read-page.ts:280`
    - Category: Quality
    - Status: A pure exported `moveTargetsOf(nodes, exceptId)` next to `folderTree` is now used by both `MailFoldersStore.moveTargets` and `MailReadPage.move()`; behavior unchanged and unit-tested.

## Checked without new findings

- Races: `run()` sets `triaging` synchronously before its first await, so double clicks or repeated `s`/`u`/`v` cannot start a second action; `move()` clears `triaging` in `finally` and hands over to `run()` without an intervening await; Mark unread awaits the pending mark-read; navigation or destroy during folder loading, the dialog or the action is guarded by the id/destroyed checks.
- Keyboard handling: all keys go through `mailShortcutFor`, so modifier combinations (Ctrl+R/F/A/C), typing targets, IME composition and repeats keep their browser meaning; dialog-open guard on both pages; r/a/f/u/s/v require the ready state; `c` works on the list and the read view.
- i18n: every new UI string has English and German units; `npm run lint` -> "All files pass linting."; messages-de.spec.ts passes.
- Accessibility: Star toggle label and `aria-pressed` as above; the action group has a translated `aria-label`.
- Maintainability: no dead code or debug output introduced by the tests or fixes; the fixer's edits stay inside the task's touches (mail list/read, locale) plus the shell registry recorded as an assumption.

## Review conclusion

No material issues found.
