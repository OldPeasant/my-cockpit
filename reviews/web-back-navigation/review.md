# Code Review

Date: 2026-11-08

Task: web-back-navigation - Web back buttons return to where the user came from

Scope: `git diff 9a29669a..972b750b -- web/` (the task's three commits on top of base 9a29669abe86beff6ba450f28a4435ba4087f0f3), plus the test report `reviews/web-back-navigation/tests.md`. Committed changes only; the working tree was clean.

Requirements:
- `tasks/web-back-navigation.md` (acceptance criteria and recorded assumptions)
- `SPEC.md`, `docs/ARCHITECTURE.md`, `AGENTS.md` "Project notes"
- Parent agent instructions

Git range:
- Branch: main
- HEAD: 972b750b9be520fe4849125977d1c1d6931656f1
- Base/start: 9a29669abe86beff6ba450f28a4435ba4087f0f3
- Commits reviewed: 3 (a63203aff, e5322d8c2, 972b750b9)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Commands run

- `git log --oneline 9a29669a..972b750b` and `git diff --stat 9a29669a..972b750b` -> 3 commits, 36 files
- `git diff 9a29669a..972b750b -- {changed web files}`; full reads of `shared/navigation/back-navigation.ts`, `back-button.ts`, `index.ts`, the triage code in `mail-read-page.ts`
- `grep` for remaining hardcoded back links (`class="back"`, `routerLink=".."`, `routerLink="/..."`) and for direct `Location` history writes (`replaceState`, `location.go`) in `web/src/app` -> only the not-found states of project detail / note editor and mail compose (excluded by assumption) keep fixed links; no direct history writes that would desync the mirror
- `grep` for `canDeactivate`, `canceledNavigationResolution`, route titles, login `navigateByUrl` -> login/TOTP use `replaceUrl: true`, so `/login` never ends up as the previous entry; route titles exist for New, Dashboard, Mail, Projects, Notes, Contacts, Bookmarks
- `cd web && npx ng test --watch=false --include src/app/shared/navigation/back-navigation.spec.ts --include src/app/features/mail/read/mail-read-page.spec.ts --include src/app/features/gtd/task-detail/task-detail-page.spec.ts` -> 3 files, 145 tests passed
- Locale check: `back.label`, `back.aria` present in `messages.xlf` and `messages.de.xlf` (DE translated); removed units `bookmarks.back`, `contacts.back`, `mailRead.backAria` have no remaining source references; `gtd.project.back` and `notes.editor.back` are kept because the not-found states still use them

## Acceptance criteria assessment

1. Shared helper: `BackNavigation` (root service started by `provideBackNavigation()` in `app.config.ts`) + `app-back-button` in `web/src/app/shared/navigation`; goes back in history when the previous entry is an app page it saw, else navigates to the fallback. Met.
2. Label names the destination: route title of the deepest primary route, or the name a page set via `setLabel` (project, note, contact, bookmark), else "Back". Met.
3. Used by mail read, task detail page, project detail, note editor, contact detail and bookmark reader. Calendar `events/:id` is a redirect, not a page. Mail compose excluded by a recorded assumption. Met.
4. Archive/Spam/Delete use `back('/mail', { replaceUrl: true })`. Met (Forward-reopen behavior recorded as assumption).
5. Unit tests: "opened from New -> back returns to New with its filter" and "deep link -> parent route" exist in `back-navigation.spec.ts` and `mail-read-page.spec.ts`. Met.
6. Strings in EN + DE locale files. Met.
7. Tests cover behavior (back-navigation.ts and back-button.ts 100 % per tests.md); `npm run check` passed per the parent, `sh scripts/check.sh` is for the parent to run before `done`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Mail triage re-enables its actions before the page has left**
    - File: `web/src/app/shared/navigation/back-navigation.ts:84-86`, `web/src/app/features/mail/read/mail-read-page.ts:211-215`
    - Category: Correctness
    - Problem: `BackNavigation.back()` calls `location.back()` and returns at once when there is a previous app page; the popstate navigation runs later. `triage()` awaits `back()`, then its `finally` sets `triaging` to null while the moved message is still shown with `state() === 'ready'` and `destroyed === false`. Until the popstate navigation finishes, a repeated `e` / `!` / `#` key (key autorepeat) or click gets through the `triaging()` guard and calls `mail.apply` again for the same message. Before this change, `await router.navigateByUrl(...)` only resolved once the page had been replaced, so that window did not exist. The window is short (a few ms up to one router navigation), so this is a narrow regression. A second Delete on a message that is now in Trash could remove it permanently, or it may just show an error toast if the id changed after the move.
    - Why it matters: in the worst case a duplicate destructive mail action. The same early return also lets a fast double click on a back button call `location.back()` twice.
    - Suggested fix: let `back()` resolve only after the popstate navigation has ended, e.g. wait for the next `NavigationEnd`/`NavigationCancel`/`NavigationError` after `location.back()`. Or keep `triaging` set when the triage succeeded and the page is leaving. Add a test where a second triage call before the navigation settles does not call `mail.apply` again. Suggested disposition: fix (small), or accept with an assumption if the parent considers the window too narrow to matter.

## Review conclusion

No material issues found. The single P3 finding is optional to fix before completing the task.
