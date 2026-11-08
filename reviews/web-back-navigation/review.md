# Code Review

Date: 2026-11-08

Task: web-back-navigation - Web back buttons return to where the user came from

Scope: `git diff 1a634a78e..4b5e8a788 -- web/` (all task commits on top of base 1a634a78e3ac169fd6fe817a129bb3bd39373630). This is a re-review of the final commit. Since the previous pass (HEAD 3ff3a2c3f), the only change under `web/` is 4b5e8a788, which adds 4 tests to `web/src/app/shared/navigation/back-navigation.spec.ts`. The production code is the same as at 3ff3a2c3f. That code was reviewed in full in the previous pass, with special attention to d1cabbdc0 and 3ff3a2c3f in `back-navigation.ts`. Only committed code was reviewed, and the working tree was clean.

Requirements:
- `tasks/web-back-navigation.md` (acceptance criteria and recorded assumptions, including both accepted known limitations: a refused Back followed by Forward, and a Forward right after a Back that was still loading)
- `SPEC.md`, `docs/ARCHITECTURE.md`, `AGENTS.md` "Project notes"
- Parent agent instructions (re-review of the final commit 4b5e8a788; keep CR-1 with its accepted status)
- Earlier `reviews/web-back-navigation/review.md` rounds and `reviews/web-back-navigation/fixes.md`

Git range:
- Branch: main
- HEAD: 4b5e8a788277936c2cfc5d1d6f77005dcf9288f0
- Base/start: 1a634a78e3ac169fd6fe817a129bb3bd39373630
- Commits reviewed: 13 (b3b1f4396, 3f5e1909d, 02f9be5e0, 017142026, a2f06e977, eae4fb86b, 99b2dce55, e9c908136, 3f828d971, d1cabbdc0, bed9bc2da, 3ff3a2c3f, 4b5e8a788)

Summary: 0 P0, 0 P1, 0 P2, 1 P3 (CR-1, accepted as a known limitation)

## Commands run

- `git diff --stat 3ff3a2c3f..4b5e8a788`: under `web/`, only `back-navigation.spec.ts` changed (+76). The other files are review reports and `tasks/` files.
- `git show 4b5e8a788 -- web`: read the 4 new tests
- `git status --short`: clean working tree
- `cd web && npx ng test --watch=false --include src/app/shared/navigation/back-navigation.spec.ts`: 1 file, 57 tests passed (about 1 s). The jsdom notice "Not implemented: navigation to another Document" is printed but does not fail any test. It comes from tests where the back button leaves a modified click (new tab) to the browser.
- In the previous pass: a full read of `back-navigation.ts`, and a check of its handling of superseded and redirected navigations against `web/node_modules/@angular/router/fesm2022/_router-chunk.mjs` (`setupNavigations` 3840-3887, `isUpdatingInternalState` / `isUpdatedBrowserUrl` 4103-4111, history restore 4421)

## New tests in 4b5e8a788

- "keeps the pages ahead when the current entry is replaced, as the browser does" and "keeps the pages ahead on a navigation to the same URL" cover the replace branch of `record` without the guard-redirect setup. Both kinds of replace are covered: `replaceUrl` and a same-URL reload. In both, the browser keeps the forward entries (`replaceState`), and after `location.forward()` the test asserts `previous()`. These are meaningful regression tests for the earlier CR-2 fix.
- "leads to the page its back button names after a Forward another navigation took over" covers `followTakenOver` for a superseded Forward (the earlier tests only covered a superseded Back). It also checks that the push drops `/new`, as the browser's `pushState` does.
- "counts the page that took over a link still running as the page opened" covers a superseded imperative navigation. `restoring` is null for it, so `followTakenOver` does nothing, and the newer navigation is pushed after `/new`. The test asserts the result of the first navigation (`false`) and the final history.
- The tests check behaviour (router URL, `previous()`, where Back leads), not internals. None are skipped or focused.

## Acceptance criteria assessment

1. Shared helper: `BackNavigation` and `app-back-button` in `web/src/app/shared/navigation`. Back goes back in history when the previous entry is a known app page and to the parent route otherwise. Met. The narrow race in CR-1 is an accepted limitation.
2. Label: the route title, or the name a page sets for itself, otherwise "Back". Met.
3. Used by mail read, task detail, project detail, note editor, contact detail and bookmark reader. Met (mail compose is excluded by a recorded assumption).
4. Mail Archive, Spam and Delete go back to the origin, or replace the message with `/mail` on a deep link. Met.
5. Unit tests "opened from New -> back returns to New with its filter" and "deep link -> parent route" exist and pass. Met.
6. EN and DE locale units. Met (the locale files are unchanged since the earlier review).
7. Unit tests cover the new behaviour, and the focused spec passes (57 tests). The parent still has to run and record `npm run check` and `sh scripts/check.sh` on the final code.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [x] **CR-1 | A Back that is followed by a browser Forward before it finishes leaves the mirror one step behind (accepted known limitation)**
    - File: `web/src/app/shared/navigation/back-navigation.ts:177-189` (`followTakenOver`), `web/src/app/shared/navigation/back-navigation.ts:78-79`
    - Category: Correctness
    - Status: recorded as an accepted known limitation in `tasks/web-back-navigation.md` (ASSUMPTION 2026-11-08 12:12+0100). No code change. 4b5e8a788 does not change this behaviour.
    - Problem: `followTakenOver` moves the mirror's index to the restored entry as soon as a running popstate gets a `SupersededByNewNavigation` cancel. Example: the history is `[/today, /waiting, /mail/messages/m1]`, the user presses Back while `/waiting` is still loading, then presses the browser's Forward. The Forward targets the URL the router already shows, so the router emits only `NavigationSkipped` (`_router-chunk.mjs:3872-3878`) and nothing is recorded. The mirror stays on `/waiting` while m1 is shown. For one step, the back button names "Today" but goes to `/waiting`, and then it is right again.
    - Why it matters: for one step, the label does not match where the button leads (criterion 2). The race needs a slow guard or chunk plus a quick Forward.
    - Suggested fix (if it is ever revisited): only remember the move in `followTakenOver`. Apply it at the next imperative `NavigationStart` and drop it on `NavigationSkipped`. Add a regression test: a slow Back, then `location.forward()`.

## Review conclusion

No material issues found. The 4 tests added in 4b5e8a788 are meaningful, check behaviour rather than internals, and pass (57 tests in the spec). The production code is the same as in the previous pass. The only finding, CR-1 (P3), is recorded as an accepted known limitation.
