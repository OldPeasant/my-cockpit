# Test Report

Date: 2026-11-08

Task: web-mail-search-nav - Web mail search survives opening a match and has previous/next

Scope: `git diff 3071bf1f7f8b99ed97295ac9dbc7ab9a8c567be8..616bc8182d6c434a4d3d1da480ede2246a1597bd`, task commits `edc4b5c27` (implementation) and `616bc8182` (parent's tests). These are the rebased forms of `993f1ac0a` / `45df64e3d` named by the parent; `git diff 45df64e3d..616bc8182` changes only `tasks/web-mail-search-nav.md` (assume commits), so the code is identical. Branch `main`. Uncommitted inputs: the four spec files listed below, which this helper added to.

Requirements:
- `tasks/web-mail-search-nav.md` acceptance criteria 1-7
- `AGENTS.md` (project notes, conventions), `docs/ARCHITECTURE.md` "Coverage"
- Parent worker's description of the change and the suggested gaps

## Test files changed

- `web/src/app/features/mail/list/mail-list-store.spec.ts`
- `web/src/app/features/mail/list/mail-list-session.spec.ts`
- `web/src/app/features/mail/list/mail-list-page.spec.ts`
- `web/src/app/features/mail/read/mail-read-page.spec.ts`

## Tests added or updated

Count: 28 added, 0 changed. No existing test was changed, weakened or skipped.

- `mail-list-store.spec.ts`, new `describe('snapshot and restore')` (8): no snapshot before the first page, after a failed load or during a server search; snapshot contents (scope, nextCursor, cursor, search extras); restore without loading, nothing selected and no errors; restored search results are not refetched by `refresh()`; the restored items are a copy, not the snapshot's array; a first page that arrives after a restore is dropped; a next page and a refetch that arrive after a restore are dropped; a refetch after restoring moves the cursor off a message that is gone.
- `mail-list-session.spec.ts` (5): next pressed twice while the next page loads sends one request; a page is dropped when the same URL was saved again with another cursor; no step for an unknown message or one not opened from the list; `visited` leaves a read message alone and handles a missing `threadUnread`; removing a message that is not the cursor keeps the cursor, and removing an unknown id changes nothing.
- `mail-list-page.spec.ts`, "when the page is left and shown again" (9): the saved list is restored only on the first route application, not when the folder changes back inside the page; a folder list saved for the other view (by conversation) is not restored; nothing is saved after a failed load; nothing is saved while the server search runs; a folder search's URL is `/mail/folders/<id>?q=…&server=1`, with the query trimmed. New `describe('scroll position')`: the scroll offset is tracked and saved on leave; on return it scrolls back and brings the cursor row into view; an empty restored result list (no cursor) still scrolls back; a list that is loaded fresh is not scrolled.
- `mail-read-page.spec.ts`, "previous/next through the list it was opened from" (6): `j`/`k` do nothing while a dialog is open and work again once it is closed; `j`/`k` do nothing while typing in an input or a contenteditable; previous/next are disabled while the next page loads, a second `j`/`k` sends no second request, and going Back meanwhile stops the read view from navigating when the page arrives; a failed next page keeps the message and leaves Next enabled; a failed archive keeps the message in the saved list; an archive that finishes after the user stepped on with `j` takes the message out of the list but stays on the message the user is reading.

## Requirement coverage

Commands: F = `cd web && npx ng test --watch=false --include 'src/app/features/mail/**/*.spec.ts'` (20 files, 711 tests, pass). W = `cd web && npm run check` (pass). T = `sh scripts/check.sh` (pass).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 query and server option in the URL, also on folder routes | `in the URL` block (4, parent); `saves the server option and the folder of a search in the URL` | F, W | Pass |
| AC1 going back restores the field and the results from the store, with no second search | `shows saved search results again without searching again`, `shows a saved folder list again and refetches it in the background`, `loads anew when the saved list is another one` (parent); `restores the saved list only when the page is shown, not when the folder changes back`, `does not restore a folder list saved for the other view`, `saves nothing after the list failed to load`, `saves nothing while the server search runs`; store `snapshot and restore` (8); session `restores only the list of the same URL and scope` | F, W | Pass |
| AC2 previous/next with `k`/`j` through search results, disabled at the ends | `shows previous/next with the position, disabled at the ends`, `steps with j and k…` (parent); `does not step with j or k while a dialog is open`, `does not step with j or k while typing`, `disables previous/next while the next page loads, and stays when the user went back meanwhile`; session step tests | F, W | Pass |
| AC3 the same for a folder list in its order (with paging) | `steps into the next page of a folder list at its end` (parent); `stays on the message when the next page fails to load`; session `asks for the next page once…`, `drops a next page when the same list was saved anew…` | F, W | Pass |
| AC4 archive/delete moves to the next match, or back to the list | `Archive/Delete/Spam moves on to the next message of the list`, `moves to the message before after archiving the last one, then back to the list` (parent); `keeps the message in the list when archiving it fails`, `takes an archived message out of the list but stays on the one the user moved on to` | F, W | Pass |
| AC5 scroll position and cursor row kept when returning | `saves the shown list with its URL, cursor…` (parent); `scroll position` block (4); session `moves the cursor to a visited message…`, `keeps the list cursor when another message than the cursor is removed` | F, W | Pass (jsdom stand-in: the body acts as the scrolling container. The real `mat-sidenav-content` scroll was not run in a browser.) |
| AC6 new UI strings in English and German | No unit test. The `i18n` ids are used in the templates; `ng build` in W compiles the templates. The German translations were not checked by a test (see "Unable to test") | W | Not testable as a unit test |
| AC7 unit tests cover the new behavior; `npm run check` and `sh scripts/check.sh` pass | All of the above | W, T | Pass |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include 'src/app/features/mail/**/*.spec.ts'`

Result:

Pass: 20 files, 711 tests. The store, session, list-page and read-page specs were also run on their own while writing.

## Full test execution

Command:

`cd web && npm run check` (api:check, `ng lint --max-warnings=0`, e2e typecheck, `ng test --watch=false`, `ng build`)

Result:

Pass: 188 files, 4243 tests at the time. The final coverage run had 4244 tests after one more test was added. The build printed a warning that the initial bundle exceeds its 500 kB budget (692 kB). The warning was not added by this task. `npx prettier --check` passes on the four spec files.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` (tfp, server, web, android) printed "all checks passed", 13:26:23-13:28:08 (about 1 min 45 s).

## Bugs found

No production defects were found. All new tests pass against the implementation.

One low-severity note that no test proves is a defect: the read view shows previous/next only when `BackNavigation.previous().url` equals the URL that the list page builds (`listUrl()`: `q` before `server`, a blank `q` dropped). A hand-typed or external link such as `/mail?server=1&q=report` or `/mail?q=` shows and restores the list correctly, because `restorable` compares against `listUrl()` too. The read view opened from that list shows no previous/next, though, because the history URL differs from the saved one. The app's own links and the search box always produce the canonical form. This is left for the parent and reviewer to decide. It is not marked as a required fix.

## Coverage

Status: verified for the TypeScript files. The template entry for `mail-read-page.html` is unreliable (see below).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (the tooling has no changed-line report).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-mail-search-nav`. Base `3071bf1f7f8b99ed97295ac9dbc7ab9a8c567be8` and task commits come from the parent. HEAD is `616bc8182d6c434a4d3d1da480ede2246a1597bd`. The commits are `edc4b5c27` and `616bc8182`, after the parent's rebase. File scope: `git diff --name-only 3071bf1f7..HEAD -- web/src` minus `*.spec.ts`.
- Source/test snapshot identifier (including uncommitted inputs), sha256: `mail-list-page.spec.ts` 7fc72fa0…f183, `mail-list-session.spec.ts` 1a243f49…29db, `mail-list-store.spec.ts` bee3da6e…5a07, `mail-read-page.spec.ts` 7f8923ef…08f3. The production files are as committed at HEAD (`mail-list-page.ts` 3be08ede…d63, `mail-list-session.ts` 6d71318d…d63, `mail-list-store.ts` d17ec75b…482, `mail-read-page.ts` 54727896…8b, `mail-read-page.html` c88b7020…178).
- Exact included files: `web/src/app/features/mail/list/mail-list-session.ts`, `mail-list-store.ts`, `mail-list-page.ts`, `mail-list-page.html`, `mail-folders-store.ts` (only formatting changed), `web/src/app/features/mail/read/mail-read-page.ts`, `mail-read-page.html`. Excluded: `mail-read-page.scss` and `web/src/locale/messages.xlf` / `messages.de.xlf`, which are not executable. No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8` 5.0.5, Node 24.21.0. Command: `npm run coverage` (`ng test --watch=false --coverage …`), run in `web/`, full suite.
- Run start/completion time and exit status: 2026-11-08T13:25:19+01:00 to 13:26:15+01:00, exit 0, 188 files and 4244 tests passed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 ca5b0126…b435 and `web/coverage/web/coverage-summary.json` sha256 229a62f1…878a, written 13:26:15.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report's mtime matches the end of the run, and the input hashes were identical before and after the run (`diff` empty).
- Final run after latest test additions and production fixes: yes. It ran after the last test was added. No production fixes were made.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/mail/list/mail-list-session.ts` | 73/74 (98.64%) | 76/77 (98.70%) | 25/25 (100%) | 53/53 (100%) |
| `web/src/app/features/mail/list/mail-list-store.ts` | 310/310 (100%) | 159/170 (93.52%) | 72/72 (100%) | 252/252 (100%) |
| `web/src/app/features/mail/list/mail-list-page.ts` | 245/246 (99.59%) | 159/167 (95.20%) | 63/64 (98.43%) | 203/203 (100%) |
| `web/src/app/features/mail/list/mail-list-page.html` | 229/229 (100%) | 36/36 (100%) | 19/19 (100%) | 152/152 (100%) |
| `web/src/app/features/mail/list/mail-folders-store.ts` | 91/91 (100%) | 60/61 (98.36%) | 38/38 (100%) | 75/75 (100%) |
| `web/src/app/features/mail/read/mail-read-page.ts` | 225/231 (97.40%) | 140/156 (89.74%) | 44/45 (97.77%) | 188/188 (100%) |
| `web/src/app/features/mail/read/mail-read-page.html` (unreliable, see below) | 53/245 (21.63%) | 3/45 (6.66%) | 0/15 (0%) | 49/167 (29.34%) |

Aggregate over all 7 measured files (summed counters): statements 1226/1426 (85.97%), branches 633/712 (88.90%), functions 261/278 (93.88%), lines 972/1090 (89.17%).

Without `mail-read-page.html`: statements 1173/1181 (99.32%), branches 630/667 (94.45%), functions 261/263 (99.24%), lines 923/923 (100%).

Excluded files (not executable): `mail-read-page.scss`, `messages.xlf`, `messages.de.xlf`.

The `mail-read-page.html` counters do not reflect what ran. They report the previous/next click listeners (html lines 16 and 31) and the Archive/Spam/Delete click listeners as never called. Passing tests click exactly those buttons and assert the resulting navigation (`shows previous/next with the position…`, `Archive moves on to the next message of the list`), so the listeners did run. The v8 source mapping for this template is wrong in this setup. Its numbers must not be read as a coverage gap. The new template block (lines 4-39) is exercised by the read-page tests named above.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail-list-session.ts:142` (`fetchMore` guard: no saved list, no cursor, or a search) | Defensive code. `step()` checks `position.more` (which requires a cursor and no search) before calling `loadMore` | Left uncovered: not reachable through the public API without a race that `loadMore`'s single-flight already covers |
| `mail-list-page.ts:236` (`restoreScroll` without a cursor) | AC5 for an empty result list | Covered by the new test `scrolls back also for search results that found nothing` |
| `mail-list-page.ts:225` (window as the scroll target when the document scrolls) | AC5 when no ancestor scrolls | Covered as a branch in jsdom (`document.scrollingElement` path). The shell scrolls `mat-sidenav-content` (overflow auto), which the body stand-in models. No real-browser scroll test was run |
| `mail-list-page.ts:119` (`viewChild` arrow) and pre-existing `??` / `||` arms (97, 101, 108, 340, 412, 433, 47) | Compiler artifact and older code, not part of this task | No action |
| `mail-list-store.ts` binary-expr arms 252-531 | `?? []` fallbacks in older code (loadMore, refresh, apply), outside the changed `snapshot`/`restore` | No action |
| `mail-read-page.ts:76` (host keydown binding function), 160, 193, 297, 329, 331 and other arms | Line 76 is a mapping artifact: `onKeydown` runs in many passing tests. The rest are older guards (attachments, GTD, image loading) outside the task's change | No action |
| `mail-folders-store.ts:45` | Only formatting changed in this task | No action |

## Unable to test

- AC6 (German translations): no unit test checks that the new `@@mailRead.*` units have German targets. Checking this needs a German (`de`) localized build or an xlf check. The parent can confirm it by inspecting `messages.de.xlf` or building the `de` locale. Follow-up is optional.
- Real browser scroll restoration in the shell's `mat-sidenav-content` and the history interplay of `replaceUrl` with real browser Back were not run. jsdom stand-ins and the Router testing harness with `provideLocationMocks` were used instead. No Playwright test was run, and the criteria do not ask for one.

## Summary

Tests added and all validation passed. Task-scoped coverage of the TypeScript files is high (lines 100%, branches 94.45% without the template whose mapping is unreliable). No production defects were found. One low-severity observation about non-canonical list URLs is noted for the parent; it is not a required fix.
