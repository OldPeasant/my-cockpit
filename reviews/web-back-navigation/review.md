# Code Review

Date: 2026-11-08

Task: web-back-navigation - Web back buttons return to where the user came from

Scope: `git diff 8f36a3d..70358e258 -- web/` (all task commits on top of base 8f36a3d9668cb4a1dffd21b73f42ae828f5bd53d). Committed changes only. The working tree holds an uncommitted edit of `web/src/app/shared/navigation/back-navigation.spec.ts` by another helper running at the same time; it is not part of the reviewed scope, but its failing test confirmed CR-1 below.

Requirements:
- `tasks/web-back-navigation.md` (acceptance criteria and recorded assumptions, including the accepted known limitation for the earlier CR-1)
- `SPEC.md`, `docs/ARCHITECTURE.md`, `AGENTS.md` "Project notes"
- Parent agent instructions (re-review of the final code; verify the redirect fix of TW-1 against the Angular router source)
- `reviews/web-back-navigation/fixes.md`

Git range:
- Branch: main
- HEAD: 70358e258
- Base/start: 8f36a3d9668cb4a1dffd21b73f42ae828f5bd53d
- Commits reviewed: 8 (3a11262b8, 5f3374c69, fbd730ace, 1cb7f53a7, 7655d5891, e8c58ec89, e55caf3fc, 70358e258; four of them carry only tests and reports)

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## Commands run

- `git log --oneline 8f36a3d..HEAD`, `git diff --stat 8f36a3d..HEAD` -> 8 commits, 38 files (35 under `web/`)
- `git diff --stat e8c58ec89..HEAD -- web/` -> since the previous review only `back-navigation.ts` (+33/-10) and its spec changed in production/test code
- Full read of `back-navigation.ts`, `back-navigation.spec.ts`; diff of `app.config.ts`, `back-button.ts`, `index.ts`, mail read page, task detail page and task detail component
- Angular router source `node_modules/@angular/router/fesm2022/_router-chunk.mjs` (v22.2.3): `NavigationTransitions.setupNavigations` (lines 3840-3910, 4053-4069), `HistoryStateManager.handleRouterEvent` / `setBrowserUrl` / `restoreHistory` (4404-4483), `Router.subscribeToNavigationEvents` `RedirectRequest` handling (4595-4610)
- `npx ng test --watch=false --include src/app/features/mail/read/mail-read-page.spec.ts --include src/app/features/gtd/task-detail/task-detail-page.spec.ts` -> 2 files, 113 tests passed
- `npx ng test --watch=false --include src/app/shared/navigation/back-navigation.spec.ts` -> run on the working tree (the committed spec could not be isolated without touching the other helper's uncommitted edit): 48 passed, 1 failed, the uncommitted test "leads to the page its back button names after a Back another navigation took over" (`previous()?.url` is `/mail/messages/m1`, expected `/waiting`), see CR-1. The fixer's run of the committed spec is recorded in `fixes.md` (148 tests passed with the mail read spec).

## Redirect fix (TW-1) checked against the router

- A guard redirect produces `NavigationCancel` with code `Redirect` and then a `RedirectRequest` (`_router-chunk.mjs:4064-4068`). The state manager does not restore history for a redirecting cancel (`:4421-4425`), so the browser stays on the entry the popstate moved to.
- The follow-up is scheduled as an `IMPERATIVE_NAVIGATION` with `replaceUrl` true for a browser-triggered source (`:4603-4606`), and `setBrowserUrl` then calls `replaceState` on that entry (`:4442-4448`).
- `followRedirect` moves the mirror's index to the restored entry before the follow-up's `NavigationStart` (which resets `restoring`), and `record` then replaces that entry because `currentNavigation().extras.replaceUrl` is set. This matches the browser. The unknown-entry branch (`[current]`, index 0) gives "nothing known behind", as for other unknown entries.
- `back()` no longer resolves on a redirecting cancel and waits for the follow-up's `NavigationEnd`. This is bounded by the 1.5 s timeout and covered by "resolves back() only when the navigation a guard redirected the Back to has ended".

## Acceptance criteria assessment

1. Shared helper: `BackNavigation` (root service, started by `provideBackNavigation()` in `app.config.ts`) and `app-back-button` in `web/src/app/shared/navigation`. Met, apart from the superseded-Back race in CR-1.
2. Label: route title or a page's own name via `setLabel`, else "Back"; aria label "Back to {destination}". Met.
3. Used by mail read, task detail page, project detail, note editor, contact detail and bookmark reader. Met (mail compose is excluded by a recorded assumption).
4. Archive, Spam and Delete call `back('/mail', { replaceUrl: true })`. Met.
5. Unit tests for "opened from New -> back returns to New with its filter" and "deep link -> parent route" exist. Met.
6. EN and DE locale units `back.label`, `back.aria`. Met (checked in the previous review; the locale files did not change since then).
7. Focused suites pass (above). The parent still has to run and record `npm run check` and `sh scripts/check.sh` on the final code.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A Back that a newer navigation supersedes leaves the back button on the wrong page**
    - File: `web/src/app/shared/navigation/back-navigation.ts:78-88`, `web/src/app/shared/navigation/back-navigation.ts:226-232`
    - Category: Correctness
    - Problem: A popstate navigation cancelled with `SupersededByNewNavigation` is ignored. `isRedirecting` excludes it from `restoreCancelled`, and only `Redirect` reaches `followRedirect`. In the router the browser has already moved to the restored entry, and a superseded cancel does not restore history (`_router-chunk.mjs:4421`). The outer `switchMap` emits the old navigation's cancel before the new navigation's `NavigationStart` (`:3842`, `:4043-4047`, `:3882`), so `restoring` still identifies the Back at that point. Example: the history is `[/today, /waiting, /mail/messages/m1]` and the Back to `/waiting` is still running (slow guard or lazy chunk). The user then clicks a nav link to `/mail`, and the browser pushes `/mail` on top of the `/waiting` entry: `[/today, /waiting, /mail]`. The mirror's index is still on m1, so it pushes after it: `[/today, /waiting, m1, /mail]`. The back button then names "Message" and links to m1, but its click (`location.back()`) goes to `/waiting`. The other helper's uncommitted test reproduces this (`previous()?.url` is `/mail/messages/m1` instead of `/waiting`). The previous review called ignoring this case correct, which was wrong for a popstate superseded by an imperative navigation.
    - Why it matters: criteria 1 and 2 (the label names the destination, and the button goes where it says) break after a realistic race: clicking a nav item while a Back is still loading.
    - Suggested fix: send a `SupersededByNewNavigation` cancel of the running popstate to `followRedirect` as well, so the index moves to the restored entry. A newer popstate still finds its own entry by `restoredId`, and an imperative push or replace then applies at the right index. Keep `back()` waiting past it as now. Commit the other helper's test as the regression test.

## P3 Low

- [ ] **CR-2 | A replacing navigation drops the forward entries the browser keeps**
    - File: `web/src/app/shared/navigation/back-navigation.ts:215-220`
    - Category: Correctness
    - Problem: For `replaceUrl` or a same-URL navigation, `record` truncates the mirror to `entries.slice(0, index + 1)`. The browser's `replaceState` (`_router-chunk.mjs:4448`) keeps the forward entries. The new redirect path now reaches this branch after a Back: with `[/today, /guarded, m1]`, a Back redirected to `/login` leaves the mirror at `[/today, /login]` while the browser has `[/today, /login, m1]`. If the user then presses the browser's Forward to m1, its `restoredId` is not found, the mirror starts afresh, and the back button goes to the parent route (a push) instead of back to `/login` or `/today`.
    - Why it matters: after this rare sequence the button falls back to the parent route although an app page lies behind (criterion 1). It never leads to a wrong page.
    - Suggested fix: replace in place without truncating (`next = [...entries]; next[index] = entry`) in the replace branch, and add a test for "redirected Back, then Forward". Alternatively, accept it alongside the recorded known limitation.

## Review conclusion

Material issues found; fix before completing the task. CR-1 (P2) is a concrete, reproduced mismatch between the back button's label and its destination after a superseded Back. CR-2 is optional. The TW-1 redirect fix itself is correct against the Angular 22.2.3 router.
