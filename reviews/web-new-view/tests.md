# Test Report

Date: 2026-10-26

Task: web-new-view: Combined New view (GTD inbox + mail inbox) (round 2, final code after the fixes for CR-1..CR-3 / TW-1)

Scope: `git diff 464e31b760b54e108001f2f5da4c3d6defac7483..HEAD -- web` (base given by the parent worker, = origin/main at claim). The task's earlier commits 1bb76906 (implementation), 3c747664 (e2e spec), b975de5e (fixes), 2ca73f94 (extra tests) arrive through c5811bbf ("merge earlier work from wip/web-new-view"). Branch `main`. Measured HEAD: c5811bbfe7f2bfaa4e0e70dc1fe8359843b73917 at the start; during the run the parent committed b484d22ff7140d42957be8130bf49c34e55b2645, which only adds one line to `web/e2e/new/new.spec.ts` (Playwright, not part of the Vitest run or its coverage). Uncommitted when measured: only the two test files of this report.

Requirements:
- `tasks/web-new-view.md` acceptance criteria 1-3 (AC4 is Playwright and is run by the parent) and the recorded assumptions (New in the Home group right after Dashboard; tasks older than the oldest loaded mail held back while more mail can load; keys 1/2/3, `?source=inbox|mail`, j/k/o/Enter/t, flat unified inbox)
- SPEC.md §5.3 "New view", §9.1
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md
- Instructions from the parent worker

## Test files changed

- `web/src/app/features/new/new-page.spec.ts` (2 tests added)
- `web/src/app/features/new/new.routes.spec.ts` (new, 2 tests)

## Tests added or updated

Count: 4 added, none changed or removed.

The existing suite (`new-feed.spec.ts` 20 tests, `new-page.spec.ts` 44 tests before this round, counting each `it.each` case) already covered merge order across paging, each filter, SSE add/remove, "→ Inbox task" and the load-error fixes. I added tests only for these remaining gaps:

- `new.routes.spec.ts` › `puts "New" right after Dashboard and right before Inbox`: the nav position in AC1 and the recorded assumption (Home group, no group of its own). Before this, only the e2e spec checked the position.
- `new.routes.spec.ts` › `lazy-loads the New page at /new and marks its navigation entry active`: the real `app.routes.ts` route loads `app-new-page` with the title "New" and the active nav link.
- `new-page.spec.ts` › `live updates` › `keeps live rows of the hidden source out of the filtered list`: SSE combined with a filter. A new capture does not show under Mail and a new mail does not show under GTD Inbox. After switching to GTD Inbox, the live capture is there.
- `new-page.spec.ts` › `live updates` › `holds back a live capture older than the loaded mail until its page loads`: SSE combined with the hold-back assumption. An old capture that arrives live stays hidden until the mail pages reach it, then appears after the older mails, so the order stays correct.

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include='src/app/features/new/**/*.spec.ts'`: 3 files, 68 tests, all passed.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 route `/new`, "New" nav entry between Dashboard and Inbox | `new.routes.spec.ts`: both tests; also the existing `app.spec.ts` `builds every navigation section...` and `shell.spec.ts` link rendering | F; `npm run check` | Pass |
| AC1 merge newest first (task creation / mail date), source icon + label | `merges both inboxes newest first and shows each row source`; mergeNew `merges tasks by creation...`, `orders equal times by source and id`, `sorts an unparsable date last...`, `mails without an arrival date` (5 tests, CR-3) | F | Pass |
| AC1 load more mail by cursor without breaking the order (+ hold-back assumption) | mergeNew `holds back tasks older...`, `only appends when the next mail page loads`, `shows a task exactly as old as the oldest loaded mail...`, `holds nothing back once the last mail page is loaded...`, `shows every task while no mail is loaded`; page `keeps the order when older mail loads`, `loads older mail when the keyboard cursor reaches the last row`, `infinite scroll` (3 tests), `holds back a live capture older than the loaded mail...` (new) | F | Pass |
| AC2 filter All / GTD Inbox / Mail: buttons, keys 1/2/3, URL `?source=` (survives reload) | `filters by the URL` (3 cases, a fresh page opened at the URL, like a reload), `switches the filter with the buttons and keys and keeps it in the URL`, `does not navigate when the shown filter is chosen again`, mergeNew `filters by source`, `parseFilter` (5 cases), `infinite scroll` › `loads no mail pages under the GTD Inbox filter`, `ignores the failure of the hidden source` (2 cases) | F | Pass |
| AC2 opening a row: task detail / mail read view | `opens a task in its detail view`, `opens a mail in the read view on click`, `opens a mail in the read view with the keyboard`, `keys` › `acts on the cursor row with t and o...`, `opens the cursor row on o while a filter button has the focus`, `leaves Enter on a focused button to the button` | F | Pass |
| AC2 "→ Inbox task" (mail-gtd API), mail stays | `makes an Inbox task of a mail and keeps the mail`, `sends "→ Inbox task" once while it runs`, `reports a failed "→ Inbox task"`, `reports an unexpected "→ Inbox task" failure with a generic message` | F | Pass |
| AC2 live updates via SSE: new capture / new mail appear, processed / archived disappear | `adds new captures and new mail live and drops processed items`, `live updates` › `drops a mail archived elsewhere...`, `drops a task processed in another view`, `refetches both inboxes after the event stream reconnects`, `keeps live rows of the hidden source out of the filtered list` (new), `holds back a live capture...` (new) | F | Pass |
| Assumption: keys j/k/o/Enter/t, as in the mail list (CR-2 fix) | `keys` describe (8 tests), `keeps the cursor at the first row on k`, `shows the empty state, where the row keys do nothing` | F | Pass |
| Load-error fixes (CR-1 / TW-1): "Loading…" ends, the other source stays visible, retry | `load errors` (6 tests incl. 2 `it.each` cases), `load errors under All` (4 tests) | F | Pass |
| AC3 only existing APIs, no new server endpoint | `git diff --stat 464e31b7..HEAD -- server api web/src/app/api` is empty; the specs' fake `Api` accepts only `listTasks`, `listMailMessages`, `mailToTask` and throws on any other call | git diff; F | Pass |
| AC3 `cd web && npm run check` passes | whole web suite | `cd web && npm run check` | Pass |
| AC4 Playwright `e2e/new/new.spec.ts` against the e2e stack | `web/e2e/new/new.spec.ts` | `npm run e2e -- e2e/new/new.spec.ts` | Not run: the parent worker runs it, as instructed. The spec is type-checked by `npm run check` (`e2e:typecheck`), which does not execute it |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/new/**/*.spec.ts'`

Result:

Pass: 3 files, 68 tests. The first run of the new test `holds back a live capture...` failed because of a wrong date in my fixture (the "old" capture was newer than mail m4). I fixed the fixture, not the expectation.

## Full test execution

Command:

`cd web && npm run check` (api:check, lint `--max-warnings=0`, e2e:typecheck, test:ci, ng build)

Result:

Pass, exit 0: 174 test files, 3630 tests. `npx --prefix web prettier --check web/src/app/features/new`: Pass.

## .taskcheck

Result:

Not run in full (`sh scripts/check.sh` also runs the server and Android checks, which this web-only task does not change; the parent runs `.taskcheck` before `done`). Its web part, `npm run check`, passes.

## Bugs found

None.

## Coverage

Status: verified for the web part (the only part the task changes).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. Every production file in scope except `app.routes.ts` and `nav-sections.ts` is new, so for those whole-file and changed-line coverage are the same. The changed lines of the other two are covered (both files at 100 %).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-new-view`; base 464e31b760b54e108001f2f5da4c3d6defac7483; HEAD c5811bbfe7f2bfaa4e0e70dc1fe8359843b73917 (b484d22f, committed during the run, changes only the Playwright spec); scope = `git diff --name-only 464e31b7..HEAD -- web/src` without `*.spec.ts`, as given by the parent.
- Source/test snapshot identifier (including uncommitted inputs): sha256 before and after the runs (unchanged, `sha256sum -c`): `new-feed.ts` 97c0268f…, `new-page.ts` fd5e2d31…, `new-page.html` 46dd63ac…, `new.routes.ts` 4c0da351…, `app.routes.ts` c20c6e01…, `nav-sections.ts` a96b4f3f…, `new-page.spec.ts` b82d41d0…, `new.routes.spec.ts` 7346b2c1…, `new-feed.spec.ts` 32ff8621….
- Exact included files: `web/src/app/features/new/new-feed.ts`, `new-page.ts`, `new-page.html`, `new.routes.ts`, `web/src/app/app.routes.ts`, `web/src/app/shell/nav-sections.ts`. Excluded: `new-page.scss` (styles, not executable), `*.spec.ts`, `web/e2e/new/new.spec.ts` (Playwright test). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5 + `@vitest/coverage-v8` 5 (`^5.0.5`), Node v24.21.0; `npm run coverage` in `web/`; the whole web suite.
- Run start/completion time and exit status: run 1 started 2026-10-26 00:36:12 +01:00. It failed: 1 of 3630 tests timed out, `app.spec.ts` › `builds every navigation section...` (5000 ms limit). Its output was discarded. Run 2 started 00:37:52 and took 58.45 s. All 174 files and 3630 tests passed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 8ab579ef96453b0bb8c611803045445ec416e1f773e106be1b58392194fc53e5 (written 00:38:51), `web/coverage/web/coverage-summary.json` sha256 04cf93bbe74af4afde8d165c3e2a537b659daa4d24da590d2a457e6f5245655f.
- Evidence outputs are fresh and inputs stayed unchanged: yes. Run 2 rewrote the report, and the snapshot hashes match before and after.
- Final run after latest test additions and production fixes: yes. No production code changed after the fixes in b975de5e.

Values below use covered/total (percentage); zero-denominator metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/new/new-feed.ts` | 24/24 (100%) | 27/27 (100%) | 10/10 (100%) | 19/19 (100%) |
| `web/src/app/features/new/new-page.ts` | 122/124 (98.38%) | 92/94 (97.87%) | 32/33 (96.97%) | 94/94 (100%) |
| `web/src/app/features/new/new-page.html` | 109/109 (100%) | 20/20 (100%) | 5/5 (100%) | 69/69 (100%) |
| `web/src/app/features/new/new.routes.ts` | 2/2 (100%) | N/A (0/0) | 1/1 (100%) | 2/2 (100%) |
| `web/src/app/app.routes.ts` | 20/20 (100%) | N/A (0/0) | 19/19 (100%) | 20/20 (100%) |
| `web/src/app/shell/nav-sections.ts` | 3/3 (100%) | N/A (0/0) | 1/1 (100%) | 2/2 (100%) |
| **Aggregate (summed counters, all 6 scoped files; none missing)** | 280/282 (99.29%) | 139/141 (98.58%) | 68/69 (98.55%) | 206/206 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `new-page.ts:90` (statement + anonymous function) | none: the function that the Angular compiler generates for `viewChild('sentinel')` | Non-material: compiler glue. The sentinel itself is exercised by the `infinite scroll` tests |
| `new-page.ts:40` (branch `if[0]`) | none: a branch of the compiled `@Component` decorator/metadata | Non-material: compiler glue |
| `new-page.ts:219` (`if (!key) return;` in `focusCursor`) | defensive guard: `focusCursor` is called only from `moveCursor` right after `cursorKey.set(...)`, so the key is never null there | Non-material: not reachable through the UI, no test added |

## Unable to test

- AC4 (Playwright against the e2e stack with GreenMail): not run by me, as instructed. The parent worker runs `npm run e2e -- e2e/new/new.spec.ts`.
- Real `IntersectionObserver` scrolling geometry (jsdom has no layout). The tests replace the observer with a stub and trigger its callback. The real scroll is left to the e2e/manual check. No follow-up needed.
- Flaky outside the scope: in the first coverage run, `web/src/app/app.spec.ts` › `builds every navigation section, so none shows the coming soon placeholder` timed out at 5000 ms. The test navigates every NAV_SECTION in one test, and this task adds one more section. On its own it takes 780 ms, and it passed in the second coverage run and in both `npm run check` runs. It looks like a timing flake under the load of instrumented, parallel runs, not a defect in this task. If it recurs, the parent may want a follow-up to give that test a longer timeout.

## Summary

Tests added and all validation passed. Task-scoped coverage is sufficient, and no production defects were found. AC4 (Playwright) is left to the parent worker.
