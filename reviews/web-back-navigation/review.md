# Code Review

Date: 2026-11-08

Task: web-back-navigation - Web back buttons return to where the user came from

Scope: `git diff 1a634a78e..3ff3a2c3f -- web/` (all task commits on top of base 1a634a78e3ac169fd6fe817a129bb3bd39373630). This is a re-review of the final code. The focus is the production changes since the previous review (which covered up to e9c908136): d1cabbdc0 and 3ff3a2c3f in `web/src/app/shared/navigation/back-navigation.ts`, plus the spec changes in d1cabbdc0, bed9bc2da and 3ff3a2c3f. Only committed code was reviewed. Uncommitted test-writer edits in the working tree were ignored.

Requirements:
- `tasks/web-back-navigation.md` (acceptance criteria and recorded assumptions, including the accepted known limitation for a refused Back followed by a browser Forward, and the note to stop chasing further exotic history races)
- `SPEC.md`, `docs/ARCHITECTURE.md`, `AGENTS.md` "Project notes"
- Parent agent instructions (re-review the final code; review committed code only)
- Previous `reviews/web-back-navigation/review.md` (CR-1, CR-2) and `reviews/web-back-navigation/fixes.md`

Git range:
- Branch: main
- HEAD: 3ff3a2c3f7bcd3a6756ce4c1aa5bc66f8e2a4591
- Base/start: 1a634a78e3ac169fd6fe817a129bb3bd39373630
- Commits reviewed: 12 (b3b1f4396, 3f5e1909d, 02f9be5e0, 017142026, a2f06e977, eae4fb86b, 99b2dce55, e9c908136, 3f828d971, d1cabbdc0, bed9bc2da, 3ff3a2c3f). The re-review concentrated on d1cabbdc0, bed9bc2da and 3ff3a2c3f.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Commands run

- `git log --oneline 1a634a78e..HEAD`, `git diff --stat 1a634a78e..3ff3a2c3f -- web/`: 12 commits and 35 files under `web/`
- `git diff --stat e9c908136..3ff3a2c3f`: since the previous review, only `back-navigation.ts` (+15/-13), `back-navigation.spec.ts` and the review reports changed
- Read in full: `back-navigation.ts` at 3ff3a2c3f, and the new or changed tests in `back-navigation.spec.ts` at 3ff3a2c3f
- `grep` for `skip|xit|.only` in the committed spec: none. The test from bed9bc2da that was skipped earlier ("goes to the parent route after a Back to an entry it never saw that another navigation took over") is now enabled.
- Angular router source `web/node_modules/@angular/router/fesm2022/_router-chunk.mjs`: `setupNavigations` (3840-3887), `isUpdatingInternalState` / `isUpdatedBrowserUrl` (4103-4111), `isRedirectingEvent` (1903), history restore (4421), `navigateToSyncWithBrowser` (4639-4660)
- Tests were not re-run in this pass. The committed spec cannot be run in isolation without touching the other helper's uncommitted edit of the same file.

## Changes since the previous review

- Previous CR-1 (a superseded Back left the mirror on the old entry) is fixed. When a running popstate gets a `SupersededByNewNavigation` cancel, `followTakenOver` now moves the index to the restored entry. The ordering is correct: the outer `switchMap` unsubscribes the running inner pipeline, so its `finalize` emits the cancel (`:4043-4047`) before the new navigation's `NavigationStart` (`:3882`), and `restoring` still identifies the Back at that point. The history state manager does not restore history for this cancel (`:4421`), so the browser stays on the restored entry. A newer popstate finds its own entry by `restoredId` in `record`, and an imperative push or replace applies at the restored index. Both cases are covered: "leads to the page its back button names after a Back another navigation took over" (it also asserts that the cancel code really is `SupersededByNewNavigation`) and "follows the second Back when a Back runs into a first one still running".
- Previous CR-2 is fixed. The replace branch of `record` keeps the forward entries, like `replaceState`. This is covered by "keeps the page ahead after a Back a guard redirected, as the browser does".
- 3ff3a2c3f: for a taken-over Back to an entry the mirror never saw, the mirror is now cleared (index -1) instead of keeping the page that was left. Pushing or replacing afterwards gives a single entry with nothing behind it, so `back()` goes to the parent route. This is the documented rule for unknown entries. The forward entries are lost in that case, which only costs a parent-route fallback and never leads to a wrong page. `restoreCancelled` still keeps `[current]` for an unknown entry, which is correct because there the router rewrites the entry with the current page.
- `back()` keeps waiting past redirecting and superseded cancels and ends on `NavigationEnd`, a non-redirecting cancel, `NavigationError`, `NavigationSkipped` or the 1.5 s timeout. The double-click guard (`goingBack`) is unchanged.

## Acceptance criteria assessment

1. Shared helper: `BackNavigation` (root service, started by `provideBackNavigation()`) and `app-back-button` in `web/src/app/shared/navigation`. It goes back when the previous entry is a known app page and to the parent route otherwise. Met, apart from the narrow race in CR-1.
2. Label: the route title, or the name a page sets via `setLabel`, otherwise "Back". Met.
3. Used by mail read, task detail, project detail, note editor, contact detail and bookmark reader. Met (mail compose is excluded by a recorded assumption).
4. Mail Archive, Spam and Delete go back to the origin, or replace the message with `/mail` on a deep link. Met (unchanged since the previous review).
5. Unit tests "opened from New -> back returns to New with its filter" and "deep link -> parent route" exist. Met.
6. EN and DE locale units. Met (the locale files are unchanged since the previous review).
7. Unit tests cover the new behaviour. The parent still has to run and record `npm run check` and `sh scripts/check.sh` on the final code.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A Back that is followed by a browser Forward before it finishes leaves the mirror one step behind**
    - File: `web/src/app/shared/navigation/back-navigation.ts:177-189` (`followTakenOver`), `web/src/app/shared/navigation/back-navigation.ts:78-79`
    - Category: Correctness
    - Problem: `followTakenOver` moves the index to the restored entry as soon as the superseded cancel arrives. It assumes that the navigation taking over will start and be recorded. A browser Forward back to the page that is still shown is never recorded, though. Example: the history is `[/today, /waiting, /mail/messages/m1]`, the user is on m1 and presses Back, and the guard or lazy chunk of `/waiting` is still loading. The user then presses Forward. The browser is on m1 again. The first popstate is cancelled with `SupersededByNewNavigation`, and `followTakenOver` moves the mirror to `/waiting`. The Forward popstate targets the router's current URL (m1), and the browser URL is m1 too. So `isUpdatingInternalState()` and `isUpdatedBrowserUrl()` are both false (`_router-chunk.mjs:3872-3878`, `:4103-4111`), and the router emits `NavigationSkipped` with no `NavigationStart` or `NavigationEnd`. The mirror stays on `/waiting` while m1 is shown. The back button then names "Today" (`previous()` is `/today`), but its click (`location.back()`) goes to `/waiting`. This fixes itself after one step. Before d1cabbdc0 this sequence came out right, because the superseded cancel was ignored. So the fix for the earlier CR-1 introduced this case. It differs from the accepted known limitation, which covers a refused Back followed by Forward, not a Back that is still running.
    - Why it matters: for one step, the label does not match where the button leads (criterion 2). The race needs a slow guard or chunk plus a quick Forward, so it is rare.
    - Suggested fix: move the index later. In `followTakenOver`, only remember the restored index or the "unknown" result. Apply it at the next `NavigationStart` if that navigation is imperative (a newer popstate resolves its own entry in `record`), and drop it on `NavigationSkipped`. A skipped popstate means the browser is back on the URL the router shows, which is the entry the mirror never left. Add a regression test: a slow Back, then `location.forward()`, then `previous()` should still be `/waiting`. If the parent prefers not to add more special cases, the alternative is to record this sequence as a further accepted limitation with `tasks.py assume`, in line with the extension note to stop chasing exotic history races.

## Review conclusion

No material issues found. The fixes for the previous CR-1 and CR-2 are correct when checked against the Angular router source, and the test that was skipped earlier is now enabled. The only finding is a P3 race that d1cabbdc0 introduced (CR-1). The parent can fix it as suggested or record it as an accepted limitation.
