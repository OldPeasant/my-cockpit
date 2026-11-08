# Test Report

Date: 2026-11-08

Task: web-mail-search-nav - Web mail search survives opening a match and has previous/next

Scope: The final task revision. The diff is `git diff 0843d39baddc3058c55b223c75f0029951597eb3..cba84e9873d8b5b594d150bde49758e526934c12`. The task commits are `97b2dc3d0` (implementation), `cdc292d18` (tests), `26d835546` (tests and the first reports), `5785b4600` (fixes for review CR-1 and CR-2), and `cba84e987` (more tests for the fixes). The parent supplied the base and the commits. Branch `main`, HEAD `cba84e9873d8b5b594d150bde49758e526934c12`. Uncommitted inputs are the two spec files this run added to (listed below). There are no uncommitted production changes. This report replaces the earlier report, which described a revision before the fixes.

Requirements:
- `tasks/web-mail-search-nav.md`: acceptance criteria 1-7 and the recorded assumptions
- `reviews/web-mail-search-nav/fixes.md` (CR-1 and CR-2 fixed) and `review.md`
- `AGENTS.md` project notes, `docs/ARCHITECTURE.md` "Coverage" and code conventions

## Test files changed

- `web/src/app/features/mail/list/mail-list-session.spec.ts`
- `web/src/app/features/mail/list/mail-list-page.spec.ts`

## Tests added or updated

Count: 5 added in this run, 0 changed. No existing test was changed, weakened or skipped.

The tests for the fix commit were checked first. `5785b4600` covers the following. For CR-1, the session loads the next page before it removes the last loaded item, falls back when the page fails, and loads nothing without a next page. The read page loads the next page on archive and stays on the list after Back during the load. For CR-2, a 230-item restored list is not refetched. `cba84e987` adds more cases. The session falls back when the next page is empty, keeps the cursor, shares the request with a concurrent `step`, loads nothing when the message was not opened from the list, and drops a stale page. The read page falls back when the page is empty or fails, keeps the triage actions disabled during the load, sends one request when `j` is pressed during an archive's load, and still refetches a list of exactly 200 items. The new tests below fill the remaining gaps without repeating these:

- `mail-list-session.spec.ts`
  - `loads no next page when removing the last search result, even with a cursor`: `remove` on a search list never pages (the `more` guard in the new `remove` path).
  - `loads the next page when the only loaded message of a folder list is removed`: the single-item boundary. With no previous item, the next page supplies the follow-up message and the cursor.
  - `names no message when the list is no longer behind the message once the page loaded`: the position is computed again after the await. When the user has left the list in the history meanwhile, `remove` returns null, and the loaded page stays in the saved list.
- `mail-list-page.spec.ts`
  - `does not refetch a saved folder list one item longer than a server page`: the exact CR-2 boundary at `MAIL_MAX_PAGE_SIZE + 1`. The existing test covers exactly `MAIL_MAX_PAGE_SIZE`.
  - `tracks the window scroll when no ancestor scrolls but the document does` (AC5): the `window` scroll target in `trackScroll`. This branch was not exercised before.

## Requirement coverage

Commands: F = `cd web && npx ng test --watch=false --include 'src/app/features/mail/**/*.spec.ts'` (20 files, 732 tests, pass). W = `cd web && npm run check` (pass, 188 files, 4265 tests). T = `sh scripts/check.sh` (pass).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: the query and the server option are in the URL, also on folder routes | list page `in the URL` block; `saves the server option and the folder of a search in the URL` | F, W | Pass |
| AC1: Back restores the field and the results from the store, with no second search | list page `shows saved search results again without searching again`, `shows a saved folder list again and refetches it in the background`, `restores the saved list only when the page is shown…`, `does not restore a folder list saved for the other view`, `saves nothing after the list failed to load`, `saves nothing while the server search runs`; store `snapshot and restore` block; session `restores only the list of the same URL and scope` | F, W | Pass |
| AC2: previous/next and `k`/`j` through search results, disabled at the ends | read page `shows previous/next with the position, disabled at the ends`, `steps with j and k…`, `does not step with j or k while a dialog is open`, `…while typing`, `disables previous/next while the next page loads…`; session step tests, `never loads more for search results` | F, W | Pass |
| AC3: the same for a folder list in its order, with paging | read page `steps into the next page of a folder list at its end`, `stays on the message when the next page fails to load`; session `asks for the next page once…`, `drops a next page when the same list was saved anew…` | F, W | Pass |
| AC4: Archive/Delete/Spam moves to the next match, or back to the list | read page `Archive/Delete/Spam moves on to the next message of the list`, `moves to the message before after archiving the last one, then back to the list`, `keeps the message in the list when archiving it fails`, `takes an archived message out of the list but stays on the one the user moved on to` | F, W | Pass |
| AC4 / CR-1: at the end of a loaded folder page, the next page loads first | read page `loads the next page of a folder list after archiving its last loaded message`, `stays on the message the user went to while the next page loaded for an archive`, `moves to the message before … when the next page comes back empty` / `… fails`, `keeps the triage actions busy while the next page loads for an archive`, `asks for the next page once when j is pressed while an archive loads it`; session `loads the next page of a folder list before removing its last loaded message`, `removes the last loaded message and names the one before when the next page fails`, `removes the last message of a folder list without loading more when it has no next page`, `names the one before when the next page … comes back empty`, `keeps the list cursor when the last loaded message is removed after loading more`, `shares the next page request of a removal with a step while it loads`, `loads no next page when removing a message that was not opened from the folder list`, `drops a next page loaded for a removal when the list was saved anew meanwhile`, and the 3 new session tests | F, W | Pass |
| AC5: the scroll position and cursor row are kept when returning | list page `scroll position` block (5, including the new window-scroll test); `saves the shown list with its URL, cursor…`; session `moves the cursor to a visited message…` | F, W | Pass. These are jsdom stand-ins: the body or the document element acts as the scrolling container. The real `mat-sidenav-content` scrolling was not run in a browser. |
| AC5 / CR-2: a restored list longer than one server page keeps its length, cursor and scroll | list page `keeps a saved folder list longer than one server page without refetching it` (230 items), new `does not refetch a saved folder list one item longer than a server page` (201), `still refetches a saved folder list of exactly one server page` (200) | F, W | Pass |
| AC6: new UI strings in English and German | No unit test checks this. W runs `ng build`, which compiles the templates with the `i18n` ids. The German build (`npm run build:de`) was not run by this helper. | W | Not testable as a unit test (see "Unable to test") |
| AC7: unit tests cover the new behavior; `npm run check` and `sh scripts/check.sh` pass | All of the above | W, T | Pass |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include 'src/app/features/mail/**/*.spec.ts'`

Result:

Pass: 20 files, 732 tests. The run before this helper's changes had 727 tests. `npx prettier --check` passes on both changed spec files.

## Full test execution

Command:

`cd web && npm run check` (api:check, `ng lint --max-warnings=0`, e2e typecheck, `ng test --watch=false`, `ng build`), 2026-11-08T13:45:49+01:00 to 13:47:23+01:00.

Result:

Pass, exit 0. Lint reported "All files pass linting." The tests passed: 188 files, 4265 tests. The build succeeded. It printed the existing warning that the initial bundle (692.43 kB) exceeds its 500 kB budget. This task did not add that warning.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` (tfp, server, web, android) printed "all checks passed" and exited 0. It ran from 13:48:48 to 13:56:49 (+01:00), about 8 minutes. That is above the 2-minute warning but within the 15-minute timeout. The duration came from this shared machine (Gradle server and Android parts). The web part's tests take about 1 minute.

## Bugs found

None. All new and existing tests pass against the final code. No production defect was found.

Review finding CR-3 (exact-URL match of the saved list) is still present, as the parent accepted it. It is recorded in the task assumptions and `review.md`, and is not repeated here as a bug.

## Coverage

Status: verified for the TypeScript files. The counters for `mail-read-page.html` are unreliable, as explained below.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured. The existing tooling has no changed-line report. The fix lines are covered: `mail-list-session.ts:113-126` (`remove`, both arms of the load-more condition) and `mail-list-page.ts:209` (both arms of the `MAIL_MAX_PAGE_SIZE` condition) have no uncovered statement or branch.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-mail-search-nav`. The base is `0843d39baddc3058c55b223c75f0029951597eb3` and HEAD is `cba84e9873d8b5b594d150bde49758e526934c12`. The task commits `97b2dc3d0`, `cdc292d18`, `26d835546`, `5785b4600` and `cba84e987` were supplied by the parent. The file scope comes from `git diff --name-only 0843d39bad..HEAD -- web/src ':!*.spec.ts'`.
- Source/test snapshot identifier (including uncommitted inputs), sha256 prefixes: production files as committed at HEAD: `mail-list-session.ts` bb9cfc185bad84f5, `mail-list-store.ts` d17ec75be2513d9e, `mail-list-page.ts` 3f4d7dca1d043840, `mail-list-page.html` 382daafe3d463f9a, `mail-folders-store.ts` 18062e404875524c, `mail-read-page.ts` 05ba79dfe3f5fb77, `mail-read-page.html` c88b702017a051f9. Specs: `mail-list-page.spec.ts` cf82c633a3177fd8 (uncommitted), `mail-list-session.spec.ts` fa1d4a4cb379e259 (uncommitted), `mail-list-store.spec.ts` bee3da6e66f8f9c3, `mail-read-page.spec.ts` 5185075b5ceda93e.
- Exact included files: `web/src/app/features/mail/list/mail-list-session.ts`, `mail-list-store.ts`, `mail-list-page.ts`, `mail-list-page.html`, `mail-folders-store.ts` (only formatting changed), `web/src/app/features/mail/read/mail-read-page.ts`, and `mail-read-page.html`. Excluded as not executable: `mail-read-page.scss`, `web/src/locale/messages.xlf` and `messages.de.xlf`. No files were renamed or deleted. The four files the parent named (session, store, list page, read page) are reported separately below.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8`, Node v24.21.0. The command is `npm run coverage` (`ng test --watch=false --coverage` with the text-summary, json-summary, json and lcov reporters). It ran in `web/` on the full suite.
- Run start/completion time and exit status: 2026-11-08T13:47:23+01:00 to 13:48:30+01:00, exit 0, 188 files and 4265 tests passed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` (sha256 cca773c8b8f3192fc14111b85b087b82428fd3ce138de65387307f854b4403b5) and `web/coverage/web/coverage-summary.json` (sha256 fce9a187ef420c1b9c3ffb2867a3d16713ca9e03c514e02e917a939001bb9461). Both were written at 13:48:30.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The reports' mtimes match the end of the run. `sha256sum -c` passed on all 11 inputs after the run.
- Final run after latest test additions and production fixes: yes. The run came after the last test was added and after the fix commit `5785b4600`. No production code changed in this run.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/mail/list/mail-list-session.ts` | 76/77 (98.70%) | 81/82 (98.78%) | 25/25 (100%) | 56/56 (100%) |
| `web/src/app/features/mail/list/mail-list-store.ts` | 310/310 (100%) | 159/170 (93.52%) | 72/72 (100%) | 252/252 (100%) |
| `web/src/app/features/mail/list/mail-list-page.ts` | 245/246 (99.59%) | 162/169 (95.85%) | 63/64 (98.43%) | 203/203 (100%) |
| `web/src/app/features/mail/read/mail-read-page.ts` | 225/231 (97.40%) | 140/156 (89.74%) | 44/45 (97.77%) | 188/188 (100%) |
| `web/src/app/features/mail/list/mail-list-page.html` | 229/229 (100%) | 36/36 (100%) | 19/19 (100%) | 152/152 (100%) |
| `web/src/app/features/mail/list/mail-folders-store.ts` | 91/91 (100%) | 60/61 (98.36%) | 38/38 (100%) | 75/75 (100%) |
| `web/src/app/features/mail/read/mail-read-page.html` (unreliable, see below) | 53/245 (21.63%) | 3/45 (6.66%) | 0/15 (0%) | 49/167 (29.34%) |

Aggregates use summed counters:
- The four TypeScript files the parent named (session, store, list page, read page): statements 856/864 (99.07%), branches 542/577 (93.93%), functions 204/206 (99.03%), lines 699/699 (100%).
- All five TypeScript files (adding `mail-folders-store.ts`): statements 947/955 (99.16%), branches 602/638 (94.36%), functions 242/244 (99.18%), lines 774/774 (100%).
- All 7 measured files, including both templates: statements 1229/1429 (86.00%), branches 641/719 (89.15%), functions 261/278 (93.88%), lines 975/1093 (89.20%).

Excluded, not executable: `mail-read-page.scss`, `messages.xlf`, `messages.de.xlf`. No scoped file is missing from the report.

The `mail-read-page.html` counters do not reflect what ran. They report the previous/next and Archive/Spam/Delete click listeners as never called. Passing tests click exactly those buttons and check the resulting navigation, for example `shows previous/next with the position…` and `keeps the triage actions busy while the next page loads for an archive`. The template's v8 source mapping is wrong in this setup, so its numbers are not a coverage gap.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail-list-session.ts:147` (`fetchMore` guard: no saved list, no cursor, or a search) | Defensive code. Both callers (`step`, `remove`) check `position.more` first, which already requires a cursor and no search | Left uncovered: not reachable through the public API |
| `mail-list-session.ts:113-126` (`remove` loading the next page, CR-1) | AC4 across a page boundary | Fully covered. 3 new tests: search list, single-item list, list left during the load |
| `mail-list-page.ts:209` (`MAIL_MAX_PAGE_SIZE` refetch guard, CR-2) | AC5 for long restored lists | Fully covered. New boundary test at 201 items, next to the existing tests at 200 and 230 |
| `mail-list-page.ts:232` (`window` as the scroll target) | AC5 when no ancestor scrolls | Was uncovered and is now covered by `tracks the window scroll when no ancestor scrolls but the document does` |
| `mail-list-page.ts:125` (`viewChild` arrow), `:53` (decorator `if`), and `??` / `||` arms at 103, 107, 114, 419, 440 | Compiler artifacts, or older code outside the task's change | No action |
| `mail-list-store.ts` `??` arms at 252-454 | `?? []` fallbacks in older code (loadMore, refresh, apply), outside the changed `snapshot`/`restore` | No action |
| `mail-read-page.ts:112` (`id ? position : null`, the empty-id arm) | Defensive: a read route always has an id | No action, not material |
| `mail-read-page.ts:76` (host keydown binding), and 160, 193, 298, 330, 332 with their arms | Line 76 is a mapping artifact: `onKeydown` runs in many passing tests. The rest are older guards (images, attachments, GTD) outside the task's change | No action |
| `mail-folders-store.ts:45` | Only formatting changed in this task | No action |

## Unable to test

- AC6 (German translations): no unit test checks that the new `@@mailRead.*` units have German targets. `npm run build:de` fails on a missing translation, but this helper did not run it. The parent can confirm the German strings with that command. Follow-up is optional.
- The real browser scroll restoration in the shell's `mat-sidenav-content`, and the history behavior of `replaceUrl` with a real browser Back, were not run. The tests use jsdom stand-ins and the Router testing harness instead. No Playwright test was run, and the criteria do not ask for one.

## Summary

Tests added and all validation passed. The fix commit's behavior (CR-1 and CR-2) is covered, including concurrency, failure and boundary cases. No production defects were found. On the four named TypeScript files, line coverage is 100% and branch coverage is 93.93%. The remaining uncovered branches are defensive guards, compiler artifacts or older code. The template counters for `mail-read-page.html` are unreliable, as explained above.
