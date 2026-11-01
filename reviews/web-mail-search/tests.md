# Test Report

Date: 2026-11-01

Task: web-mail-search - Mail view: search box with 'search on server' option

Scope: task commit 4cf829fe1 on base 059b59076c0295df24b627be92367d0ac5560b2e (`git diff 059b59076..4cf829fe1`), as given by the parent worker; HEAD = 4cf829fe1, branch main. Uncommitted inputs at measurement time: only the four test files listed below (no production changes).

Requirements:
- tasks/web-mail-search.md acceptance criteria and recorded assumptions (route change clears the search; results not refetched live; results always flat; cached results kept during a server search, HTTP error replaces them)
- SPEC.md §7.3 "Search" (via the task), docs/ARCHITECTURE.md "Coverage"
- Parent worker's list of behaviors to cover

## Test files changed

- `web/src/app/features/mail/list/mail-testing.ts` (FakeMail gets a controllable `search(query, folderId, server)` with a `searches` call log)
- `web/src/app/features/mail/list/mail.service.spec.ts`
- `web/src/app/features/mail/list/mail-list-store.spec.ts`
- `web/src/app/features/mail/list/mail-list-page.spec.ts`

## Tests added or updated

Count: 34 new tests (4 MailService, 10 MailListStore, 20 MailListPage); no existing test changed.

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include='src/app/features/mail/list/mail-list-page.spec.ts'` and the same with the store and service specs (also part of `npm run coverage` and `npm run check`).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: Enter searches the current folder (`folderId`) | page "searches the shown folder on Enter and lists the results in its order"; store "searches the folder of the scope flat, in one page, and lists the results" | F | Pass |
| AC1: unified Inbox searches all folders (no folderId) | page "searches all folders from the unified Inbox"; store "searches all folders for the unified Inbox"; service "searches all folders (no folderId), cached only (no server flag), silently" | F | Pass |
| AC1: results newest first in the usual rows, flat | page "searches the shown folder on Enter ..." (server order kept), "shows results flat even in the Conversations view" | F | Pass |
| AC1: opening a result opens the read view (/mail/messages/{id}) | page "opens a result in the read view" | F | Pass |
| AC1: clearing the field / Escape returns to the folder list | page "returns to the folder list when the field is emptied", "returns to the folder list when a blank query is submitted", "does nothing for a blank query without a search", "empties the field and returns to the folder list on Escape"; store "returns to the folder list with no search extras" | F | Pass |
| AC2: server option repeats the search with server=true | page "repeats the search on the server, keeping the cached results until it answers", "names several failed accounts, and an unknown one generically", "searches cached mail only again when the server option is turned off"; service "searches one folder on the server and maps failures to account ids" | F | Pass |
| AC2: failures named by account address, non-blocking, cached results still shown | page "repeats the search on the server ..." (status warning names acc-2 address, no alert, rows shown), "names several failed accounts, and an unknown one generically" | F | Pass |
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
| AC4: new strings in web/src/locale (English + German) | not a unit-test concern; checked by inspection: 9 `mail.search.*` units and the updated `mail.keysHint` have German targets | `npm run build:de` | Not run to success: the build fails on 16 pre-existing missing `calendar.invite.*` units (none in base or this task's xlf files); no `mail.*` unit is reported missing |
| AC5: `cd web && npm run check` passes | whole web suite | `npm --prefix web run check` | Pass (exit 0; 185 files / 4052 tests) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/mail/list/mail-list-store.spec.ts' --include='src/app/features/mail/list/mail.service.spec.ts'` and `cd web && npx ng test --watch=false --include='src/app/features/mail/list/mail-list-page.spec.ts'`

Result:

Pass (94 tests and 79 tests)

## Full test execution

Command:

`npm --prefix web run check` (api:check, lint, e2e:typecheck, test:ci, ng build)

Result:

Pass (exit 0; 185 test files, 4052 tests). The build prints budget warnings (initial bundle 686.80 kB over 500 kB; `mail-list-page.scss` 5.13 kB over the 4 kB component-style warning budget, error budget 8 kB). Warnings only; not caused by the tests.

## .taskcheck

Result:

Not run: `sh scripts/check.sh` also runs the Gradle builds (server, Android), which this helper does not run (see the parent worker's notes); the web part of it (`npm run check`) passed above. The parent runs `.taskcheck`.

## Bugs found

No production defect found. Every new test passes against the task commit.

Observations for the parent (not test failures, no fix required by this report):
- `mail-list-page.scss` now triggers the component-style budget warning (5.13 kB over 4 kB). The project rule is to fix warnings; whether this one predates the task (the base source file was already 5001 bytes) was not measured.
- `npm run build:de` fails on 16 untranslated `calendar.invite.*` units that are absent from both xlf files at base and at this commit: another task's gap, outside this task.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 101/101 executable added/modified lines covered (100%). Method: lcov `DA` lines from this run intersected with added lines of `git diff -U0 059b59076..4cf829fe1` per file; removed and non-executable lines not counted. Changed-branch coverage not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-mail-search; base 059b59076c0295df24b627be92367d0ac5560b2e; HEAD 4cf829fe1 (the single task commit, given by the parent worker and matching the task's acceptance criteria and `touches`).
- Source/test snapshot identifier (including uncommitted inputs): SHA-256 prefixes, identical before and after the run: mail-list-page.ts 2a98645a0e84, mail-list-page.html ed72f3ed3742, mail-list-store.ts 10c47dc2fb9f, mail.service.ts 631c5d227a8e, mail-folders-store.ts 18062e404875, mail-list-page.spec.ts 36450c438e90, mail-list-store.spec.ts fb326538b8c9, mail.service.spec.ts c84791fdf8ee, mail-testing.ts 7c3e9b339f2e.
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/mail/list/{mail-list-page.ts, mail-list-page.html, mail-list-store.ts, mail.service.ts, mail-folders-store.ts}` (mail-folders-store.ts only reformatted). Excluded: `mail-list-page.scss` (styles, not instrumented), `web/src/locale/messages*.xlf` (translation data). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with @vitest/coverage-v8 5.0.5; `npm --prefix web run coverage` (`ng test --watch=false --coverage ...`), working directory web/; full web suite (185 files, 4052 tests).
- Run start/completion time and exit status: 2026-11-01 09:47:15 to 09:47:55 (+01:00); exit 0, all tests passed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 55c0b702dfb7...), `coverage-final.json` (ca5f39f2b4a9...), `lcov.info` (d75a1f6b6d0b...), all written 09:47:55.
- Evidence outputs are fresh and inputs stayed unchanged: yes (report timestamps within the run; input hashes unchanged).
- Final run after latest test additions and production fixes: yes (no production fixes; no test changed after the run).

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/mail/list/mail-list-page.ts` | 183/184 (99.45%) | 115/122 (94.26%) | 50/51 (98.03%) | 153/153 (100%) |
| `web/src/app/features/mail/list/mail-list-page.html` | 228/228 (100%) | 34/34 (100%) | 19/19 (100%) | 151/151 (100%) |
| `web/src/app/features/mail/list/mail-list-store.ts` | 294/294 (100%) | 154/165 (93.33%) | 70/70 (100%) | 237/237 (100%) |
| `web/src/app/features/mail/list/mail.service.ts` | 27/27 (100%) | 28/29 (96.55%) | 15/15 (100%) | 23/23 (100%) |
| `web/src/app/features/mail/list/mail-folders-store.ts` | 91/91 (100%) | 60/61 (98.36%) | 38/38 (100%) | 75/75 (100%) |
| Aggregate (summed counters, the 5 files above; scss and xlf excluded) | 823/824 (99.88%) | 391/411 (95.13%) | 192/193 (99.48%) | 639/639 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail-list-page.ts:107` (statement), `:41` (branch) | `viewChild('sentinel')` declaration / component metadata, instrumentation artifacts | Not task code; left |
| `mail-list-page.ts:85, 89, 96, 254, 326, 347` (branch) | pre-existing `??` / `\|\|` fallbacks (account names, sender names, toggle) | Not task code; left |
| `mail-list-store.ts:205, 207, 242, 256, 279, 303, 334, 385, 405, 407, 484` (branch) | pre-existing `this.items() ?? []` and similar defensive fallbacks | Not task code; left |
| `mail.service.ts:164` (branch) | pre-existing `thread.items ?? []` | Not task code; left |
| `mail-folders-store.ts:45` (branch) | pre-existing fallback; this file was only reformatted | Not task code; left |

None of the uncovered locations is on a line the task added or changed.

## Unable to test

- Visual appearance of the search toolbar and warning (styles): not unit-testable; no follow-up from this report.
- German build (`npm run build:de`) as a whole: blocked by another task's 16 missing `calendar.invite.*` translations; the task's own units have German targets (inspection). Follow-up belongs to whoever owns the calendar invite strings.
- Real browser behavior of `type="search"` clear button: covered only as an emptied-input `input` event in jsdom; an e2e check is outside this task's criteria.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
