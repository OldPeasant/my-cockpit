# Test Report

Date: 2026-11-01

Task: web-mail-search - Mail view: search box with 'search on server' option

Scope: final task revision c198e60df5057c10ae75e2b3a7fa2e5444824867 on base 059b59076c0295df24b627be92367d0ac5560b2e (`git diff 059b59076..HEAD -- web`), as given by the parent worker; branch main. Task commits: 65feafa86 (implementation), 2b7558f29 (tests, search styles moved to `mail-list-search.scss`, `mail-folders-store.ts` reformat reverted), c198e60df (CR-1 fix). Working tree clean in `web/` at measurement time (no uncommitted source or test inputs). This report replaces the earlier one written for revision 4cf829fe1.

Requirements:
- tasks/web-mail-search.md acceptance criteria and recorded assumptions (route change clears the search; results not refetched live; results always flat; cached results kept during a server search, HTTP error replaces them)
- SPEC.md §7.3 "Search" (via the task), docs/ARCHITECTURE.md "Coverage"
- reviews/web-mail-search/review.md finding CR-1 and reviews/web-mail-search/fixes.md

## Re-check against the final code

Changes since the earlier tested revision 4cf829fe1, and what they need from the tests:
- `mail-list-page.ts`: `styleUrls` now lists `mail-list-page.scss` and `mail-list-search.scss` (styles only, no behavior to unit-test; the build's component-style budget warning is gone, see "Full test execution").
- `mail-list-page.ts` `setThreaded`: returns before the reload while a search is shown (CR-1). Covered by the fixer's spec "keeps a server search on a view switch and lists conversations after clearing it": it checks the stored choice, no new search and no list request, results kept, no server-search status, and that clearing the field loads the folder list with `threaded: true`. The view switch without a search (reload path) is covered by the existing view-toggle specs; coverage shows both sides of the new branch taken. No further test needed.
- `mail-folders-store.ts`: reformat reverted, the file is identical to base and leaves the task scope.
- The tests themselves were committed unchanged from the earlier report (hashes of `mail-list-store.spec.ts`, `mail.service.spec.ts`, `mail-testing.ts` identical to the earlier run); `mail-list-page.spec.ts` differs only by the CR-1 spec.

No test was added or changed in this pass.

## Test files changed

Task test files (committed in 2b7558f29 and c198e60df):
- `web/src/app/features/mail/list/mail-testing.ts` (FakeMail gets a controllable `search(query, folderId, server)` with a `searches` call log)
- `web/src/app/features/mail/list/mail.service.spec.ts`
- `web/src/app/features/mail/list/mail-list-store.spec.ts`
- `web/src/app/features/mail/list/mail-list-page.spec.ts`

## Tests added or updated

Count: 35 new tests in the task (4 MailService, 10 MailListStore, 21 MailListPage, the 21st being the CR-1 spec added by the fixer); 0 added or changed in this pass; no existing test weakened or removed.

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include='src/app/features/mail/**/*.spec.ts'` (19 files, 604 tests, all pass). The same tests also ran in `npm run coverage` and `npm run check`.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: Enter searches the current folder (`folderId`) | page "searches the shown folder on Enter and lists the results in its order"; store "searches the folder of the scope flat, in one page, and lists the results" | F | Pass |
| AC1: unified Inbox searches all folders (no folderId) | page "searches all folders from the unified Inbox"; store "searches all folders for the unified Inbox"; service "searches all folders (no folderId), cached only (no server flag), silently" | F | Pass |
| AC1: results newest first in the usual rows, flat | page "searches the shown folder on Enter ..." (server order kept), "shows results flat even in the Conversations view" | F | Pass |
| AC1: results stay flat on a view switch, no re-run of the search (CR-1) | page "keeps a server search on a view switch and lists conversations after clearing it" | F | Pass |
| AC1: opening a result opens the read view (/mail/messages/{id}) | page "opens a result in the read view" | F | Pass |
| AC1: clearing the field / Escape returns to the folder list | page "returns to the folder list when the field is emptied", "returns to the folder list when a blank query is submitted", "does nothing for a blank query without a search", "empties the field and returns to the folder list on Escape"; store "returns to the folder list with no search extras" | F | Pass |
| AC2: server option repeats the search with server=true | page "repeats the search on the server, keeping the cached results until it answers", "names several failed accounts, and an unknown one generically", "searches cached mail only again when the server option is turned off"; service "searches one folder on the server and maps failures to account ids" | F | Pass |
| AC2: failures named by account address, non-blocking, cached results still shown | page "repeats the search on the server ..." (status warning names the acc-2 address, no alert, rows shown), "names several failed accounts, and an unknown one generically" | F | Pass |
| AC2: cached results kept while the server search runs (assumption) | store "keeps the cached results shown while the same query is searched on the server", "does not keep results of another query, folder or a failed search", "replaces the kept results with the load error when the server search fails"; page "shows the load error when the server search fails" | F | Pass |
| AC2: hasMore hint | page "hints that only the newest results are listed when there are more"; store first search test (searchResult.hasMore) | F | Pass |
| AC3: loading state | page "searches the shown folder on Enter ..." (Loading messages while pending) | F | Pass |
| AC3: empty state "No messages found" | page "says \"No messages found\" when nothing matches" | F | Pass |
| AC3: error state with Try again re-running the search | page "shows a failed search with Try again, which searches again"; store "reloads the search after an error (Try again)" | F | Pass |
| AC3: list shortcuts don't fire while typing in the field | page "does not run list shortcuts while typing in the field" (j k x e s u v o / #) | F | Pass |
| AC3: `/` focuses the field | page "focuses the field with /, without typing it", "leaves / with modifiers, in other fields and while a dialog is open alone" | F | Pass |
| Assumption: route change clears the search, server option kept | page "clears the search when another folder is opened, keeping the server option" | F | Pass |
| Assumption: no live refetch of results; refresh() no-op while searching | store "does not refetch search results on refresh() nor on change events", "changes search results optimistically on an action, without refetching on failure"; page "does not refetch results on a live change event" | F | Pass |
| Stale responses ignored | store "ignores the answer of a search left before it arrived" | F | Pass |
| MailService.search mapping (params, items without id dropped, failures to account ids, errors silent) | service: 4 tests in describe "search" | F | Pass |
| AC4: new strings in web/src/locale (English + German) | not a unit-test concern; checked by inspection in the earlier pass: 9 `mail.search.*` units and the updated `mail.keysHint` have German targets (xlf files unchanged since) | `npm run build:de` | Not run in this pass; in the earlier pass it failed only on 16 pre-existing missing `calendar.invite.*` units from another task, no `mail.*` unit reported missing |
| AC5: `cd web && npm run check` passes | whole web suite | `cd web && npm run check` | Pass (185 files / 4053 tests, build completed) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/mail/**/*.spec.ts'`

Result:

Pass (19 test files, 604 tests; 2026-11-01 09:56:13 +01:00)

## Full test execution

Command:

`cd web && npm run check` (api:check, lint, e2e:typecheck, test:ci, ng build)

Result:

Pass (185 test files, 4053 tests passed; `ng build` completed with output in `web/dist/web`; 2026-11-01 09:57:50 to about 09:58:32 +01:00). The output was read through a pipe, so the exit code itself was not captured; no step reported an error and the chain ends with the build, which only runs when every earlier step succeeded. Only remaining build warning: the initial bundle budget (686.80 kB over 500 kB), unrelated to this task. The earlier `mail-list-page.scss` component-style budget warning no longer appears.

## .taskcheck

Result:

Not run: `sh scripts/check.sh` also runs the Gradle builds (server, Android), which this helper does not run; its web part (`npm run check`) passed above. The parent runs `.taskcheck`.

## Bugs found

No production defect found. All task tests pass against c198e60df. CR-1 is fixed and guarded by a spec (the fixer showed it fails without the fix).

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 98/98 executable added/modified lines covered (100%): mail-list-page.ts 41/41, mail-list-page.html 26/26, mail-list-store.ts 26/26, mail.service.ts 5/5. Method: lcov `DA` lines of this run intersected with the added lines of `git diff -U0 059b59076..HEAD` per file; removed and non-executable lines not counted. Changed-branch coverage not measured as a number; the new CR-1 branch (`if (this.search()) return;`) is not among the uncovered branches, so both sides ran.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-mail-search; base 059b59076c0295df24b627be92367d0ac5560b2e; HEAD c198e60df5057c10ae75e2b3a7fa2e5444824867; task commits 65feafa86, 2b7558f29, c198e60df (given by the parent worker, matching the task's acceptance criteria and `touches`).
- Source/test snapshot identifier (including uncommitted inputs): no uncommitted inputs in `web/`. SHA-256 prefixes, identical before the coverage run and after `npm run check`: mail-list-page.ts 6738d6cb0641, mail-list-page.html ed72f3ed3742, mail-list-store.ts 10c47dc2fb9f, mail.service.ts 631c5d227a8e, mail-list-search.scss 9fe7befa3e24, mail-list-page.spec.ts c23bb6c195a6, mail-list-store.spec.ts fb326538b8c9, mail.service.spec.ts c84791fdf8ee, mail-testing.ts 7c3e9b339f2e.
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/mail/list/{mail-list-page.ts, mail-list-page.html, mail-list-store.ts, mail.service.ts}`. Excluded: `mail-list-search.scss` (new stylesheet, not instrumented), `web/src/locale/messages*.xlf` (translation data). `mail-folders-store.ts` and `mail-list-page.scss` are identical to base at HEAD and are not in scope. No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with v8 coverage; `npm run coverage` (`ng test --watch=false --coverage` with text-summary, json-summary, json and lcov reporters), working directory web/; full web suite (185 files, 4053 tests).
- Run start/completion time and exit status: 2026-11-01 09:56:36 to 09:57:18 (+01:00); all 4053 tests passed, reports written.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 be4bba1f95f9...), `coverage-final.json` (ce0bbfae6417...), `lcov.info` (d3c715a130f4...), all written 09:57:18.
- Evidence outputs are fresh and inputs stayed unchanged: yes (report timestamps within the run; input hashes unchanged).
- Final run after latest test additions and production fixes: yes (run on c198e60df, after the CR-1 fix and its spec; nothing changed since).

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/mail/list/mail-list-page.ts` | 185/186 (99.46%) | 117/124 (94.35%) | 50/51 (98.03%) | 154/154 (100%) |
| `web/src/app/features/mail/list/mail-list-page.html` | 228/228 (100%) | 34/34 (100%) | 19/19 (100%) | 151/151 (100%) |
| `web/src/app/features/mail/list/mail-list-store.ts` | 294/294 (100%) | 154/165 (93.33%) | 70/70 (100%) | 237/237 (100%) |
| `web/src/app/features/mail/list/mail.service.ts` | 27/27 (100%) | 28/29 (96.55%) | 15/15 (100%) | 23/23 (100%) |
| Aggregate (summed counters, the 4 files above; scss and xlf excluded, nothing unmeasured) | 734/735 (99.86%) | 333/352 (94.60%) | 154/155 (99.35%) | 565/565 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail-list-page.ts:107` (statement and function), `:41` (branch) | `viewChild('sentinel')` declaration / component metadata, instrumentation artifacts | Not task code; left |
| `mail-list-page.ts:85, 89, 96, 257, 329, 350` (branch) | pre-existing `??` / `\|\|` fallbacks (account names, sender names, toggle) | Not task code; left |
| `mail-list-store.ts:205, 207, 242, 256, 279, 303, 334, 385, 405, 407, 484` (branch) | pre-existing `this.items() ?? []` and similar defensive fallbacks | Not task code; left |
| `mail.service.ts:164` (branch) | pre-existing `thread.items ?? []` | Not task code; left |

None of the uncovered locations is on a line the task added or changed.

## Unable to test

- Visual appearance of the search toolbar and warning (styles in `mail-list-search.scss`): not unit-testable; the build confirms the sheet compiles within the style budget. No follow-up from this report.
- German build (`npm run build:de`) as a whole: not re-run; in the earlier pass it was blocked by another task's 16 missing `calendar.invite.*` translations, and the xlf files have not changed since. Follow-up belongs to whoever owns the calendar invite strings.
- Real browser behavior of the `type="search"` clear button: covered only as an emptied-input `input` event in jsdom; an e2e check is outside this task's criteria.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
