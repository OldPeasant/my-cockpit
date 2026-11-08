# Code Review

Date: 2026-11-08

Task: web-back-navigation - Web back buttons return to where the user came from

Scope: `git diff 280e0816..3da86b3f -- web/` (all task commits on top of base 280e081634b7d39812586181296017b38efb90b9, final revision including fix commits 5e1ef7636 and 3da86b3f5). Committed changes only; the working tree was clean.

Requirements:
- `tasks/web-back-navigation.md` (acceptance criteria and recorded assumptions)
- `SPEC.md`, `docs/ARCHITECTURE.md`, `AGENTS.md` "Project notes"
- Parent agent instructions (re-review of the final code; earlier CR-1 fixed per `reviews/web-back-navigation/fixes.md`)

Git range:
- Branch: main
- HEAD: 3da86b3f5880a06ce3c3c2ed4afe1b6368a3e366
- Base/start: 280e081634b7d39812586181296017b38efb90b9
- Commits reviewed: 6 (21aab8acd, 8d8b5e172, 76dabc254, e26a22a5c, 5e1ef7636, 3da86b3f5; 76dabc254 and e26a22a5c also carry tests and reports)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Commands run

- `git log --oneline 280e0816..HEAD`, `git diff --stat 280e0816..HEAD` -> 6 commits, 38 files (35 under `web/`)
- Full diff of every changed production file under `web/`; full reads of `shared/navigation/back-navigation.ts`, `back-button.ts`, `index.ts`, `back-navigation.spec.ts`; the mail read spec diff
- Angular router source (`node_modules/@angular/router/fesm2022/_router-chunk.mjs`, v22.2.3): `restoreHistory` / `resetUrlToCurrentUrlTree` (canceled popstate in 'replace' mode rewrites the entry the browser moved to with the current URL and `navigationId = lastSuccessfulId`), same-URL `NavigationSkipped` (emitted before `NavigationStart`), `setBrowserUrl` (same path or `replaceUrl` -> `replaceState`). The mirror in `record` / `restoreCancelled` matches this.
- `grep` for remaining hardcoded back links in `web/src/app/**/*.html` -> only mail compose (excluded by a recorded assumption) and the not-found / load-error states of project detail and note editor keep fixed list links; task detail's not-found/deleted "Back" emits `closed`, which uses `BackNavigation`
- `grep` in `web/e2e` for old back-link names ("All projects", "All notes", "Back to contacts", "Back to bookmarks", "Back to Mail") -> none left; the two updated locators read "Back to Projects", which matches the `nav.projects` route title when the project is opened from `/projects`
- Login and TOTP navigate to the return URL with `replaceUrl: true`, so `/login` does not become the previous entry
- `cd web && npx ng test --watch=false --include src/app/shared/navigation/back-navigation.spec.ts` -> 1 file, 40 tests passed
- `cd web && npx ng test --watch=false --include` mail-read-page, gtd/task-detail, contact-detail, note-editor-page, bookmark-reader, projects-pages specs -> 12 files, 514 tests passed
- Locale check: `back.label` and `back.aria` in `messages.xlf` and `messages.de.xlf` (DE translated); removed units `bookmarks.back`, `contacts.back`, `mailRead.backAria` have no source references left

## Acceptance criteria assessment

1. Shared helper: `BackNavigation` (root service, started at app startup by `provideBackNavigation()` in `app.config.ts`) and `app-back-button` in `web/src/app/shared/navigation`. Goes back in history when the previous entry is an app page it saw, else navigates to the fallback. Met.
2. Label names the destination: route title of the deepest primary route, or the name a page set via `setLabel` (project, note, contact, bookmark), else "Back"; the aria label reads "Back to {destination}". Met.
3. Used by mail read, task detail page, project detail, note editor, contact detail and bookmark reader. Met (mail compose excluded by a recorded assumption).
4. Archive, Spam and Delete call `back('/mail', { replaceUrl: true })`. `back()` now resolves only after the Back navigation ends (or a 1.5 s safety timeout), and `triaging` stays set until then, so the earlier CR-1 (duplicate triage while leaving) is fixed and covered by the two "while going back" tests. Met.
5. Unit tests "opened from New -> back returns to New with its filter" and "deep link -> parent route" exist in `back-navigation.spec.ts` and `mail-read-page.spec.ts`. Met.
6. Strings in EN and DE locale files. Met.
7. Focused suites pass (above). `npm run check` and `sh scripts/check.sh` are for the parent to run and record on the final code.

Change in 3da86b3f5 checked specifically: a second `back()` while one runs returns the pending promise (covered by the double-click test), and a non-redirecting `NavigationCancel` or a `NavigationError` of a popstate navigation moves the mirror to the restored entry and overwrites it with the current page, matching Angular's 'replace' resolution (covered by "leads to the page its back button names after a cancelled Back"). Redirect and superseded cancellations are left to the follow-up navigation, which is correct for the superseded case.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Mirror stays behind when a popstate lands on a duplicate entry after a cancelled Back**
    - File: `web/src/app/shared/navigation/back-navigation.ts:70-84`, `web/src/app/shared/navigation/back-navigation.ts:154-166`
    - Category: Correctness
    - Problem: After a cancelled Back, Angular rewrites the entry the browser moved to with the current URL and the current `navigationId`, so the browser history holds two adjacent entries with the same URL (e.g. `[/today, /editing, /editing]`, browser at position 1). `restoreCancelled` mirrors this correctly. If the user then presses the browser's Forward, the popstate goes to a URL equal to the router's current one, so the router emits only `NavigationSkipped` (before any `NavigationStart`) and `record` never runs. The mirror's index stays at position 1 while the browser is at position 2. The back button still names and links to `/today`, but its first click (`location.back()`) lands on the duplicate `/editing` entry, which the router skips again, so nothing visible happens. Only the second click reaches `/today`.
    - Why it matters: a back button that does nothing on the first click, in a rare sequence (unsaved-changes refusal, then browser Forward). The browser's own Back button acts the same way here, because the duplicate entry is Angular's doing, so the impact is low.
    - Suggested fix: Either accept it as a known limitation (record an assumption), or also handle a popstate `NavigationSkipped` whose target state's `navigationId` matches a known entry with the same URL by moving the index there (the restored id would have to be read from `location.getState()` or the popstate event, because no `NavigationStart` comes first). Add a test for cancel, then Forward, then back button.

- [ ] **CR-2 | Cancelled-Back test passes even if the mirror forgets the page behind**
    - File: `web/src/app/shared/navigation/back-navigation.spec.ts:160-175`
    - Category: Tests
    - Problem: The test asserts `router.url` equals `named?.url ?? '/mail'`. If a regression made `previous()` return `null` after the cancelled Back (e.g. `restoreCancelled` resetting the history to `[current]`), `back()` would navigate to the `/mail` fallback and the test would still pass, although the browser does have `/today` behind and the button should name it.
    - Why it matters: the exact behavior 3da86b3f5 added (keeping the page behind correct after a cancelled Back) is not pinned down.
    - Suggested fix: also assert `expect(named?.url).toBe('/today')` (and optionally the label 'Today') before the second `back()`.

## Review conclusion

No material issues found. Both P3 findings are optional; the earlier CR-1 is fixed in the reviewed code.
