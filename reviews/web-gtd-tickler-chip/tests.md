# Test Report

Date: 2026-11-23

Task: web-gtd-tickler-chip - Web: Tickler as list choice, 'new today' marker in Next

Scope: task commit 19c60cb42 on base b2a2bad83d4fd470bbf03a92657f6a6d162f3908 (as given by the parent). During this run the parent rebased it twice; `git range-diff` shows the patch unchanged (19c60cb42 = e63a1929e = e9421dbd2). The web tree at measured HEAD 41ee561635d2c2c2e5f8a05d065bc9398034b1de matches e9421dbd2 (41ee56163 only adds `reviews/web-gtd-tickler-chip/review.md`). Uncommitted inputs: the two test files listed below.

Requirements:
- `tasks/web-gtd-tickler-chip.md` acceptance criteria 1-5
- `docs/ARCHITECTURE.md` "Coverage"; existing spec conventions in `web/src`

## Test files changed

- `web/src/app/features/gtd/task-detail/task-draft.spec.ts`
- `web/src/locale/messages-de.spec.ts`

## Tests added or updated

Count: 5 new tests (no existing tests changed).

- `patchFor` > does not patch waiting-for or the scheduled date while Tickler overrides the stored list (tickler chosen over a WAITING and over a SCHEDULED task, with edited waiting/scheduled fields: patch is `{}`, the move is `INBOX` + `ticklerDate`)
- `patchFor` > still patches other fields of an unchanged tickler without moving it
- `listChoice / effectiveList` > shows and saves the stored list without a tickler, Tickler / INBOX with one (all 5 lists)
- `listChoice / effectiveList` > choosing Tickler keeps the stored list in the draft (going back to the stored list needs no move and no change)
- German translations > names the tickler and the new-today badge in English and German (web-gtd-tickler-chip) (exact EN/DE text of `taskDetail.tickler`, `inbox.action.tickler`, `gtd.tickler.date`, `gtd.tickler.hint`, `taskDetail.error.ticklerRequired`, `gtd.next.newToday`; old `taskDetail.ticklerHint` / `inbox.prompt.ticklerDate` are gone)

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: List selector offers the 6 choices incl. Tickler; Tickler needs a future date, saves `INBOX` + `ticklerDate` via move; separate field gone | task-detail.spec `tickler` > offers Tickler among the lists..., moves the task to the Inbox with its tickler date, needs a tickler date in the future; task-draft.spec `validateDraft` tickler tests, `moveFor` > saves a chosen tickler as INBOX..., new `patchFor` tickler-over-WAITING/SCHEDULED test | focused `ng test` (below) | Pass |
| AC2: a task with `ticklerDate` opens as Tickler whatever its list; another list clears it; Scheduled asks for its date; unchanged save does nothing | task-detail.spec opens a task with a tickler as Tickler..., clears the tickler when another list is chosen...; task-draft.spec `moveFor` > does not move an unchanged tickler..., clears the tickler when another list is chosen...; `chooseList`; new `listChoice / effectiveList` tests; new unchanged-tickler patch test | focused `ng test` | Pass |
| AC3: one name "Tickler"/"Wiedervorlage" and the hint in form, detail and Inbox processing (EN + DE) | inbox-page.spec (prompt label/hint), task-detail.spec (hint text), new messages-de.spec web-gtd-tickler-chip test | focused `ng test` | Pass |
| AC4: NEXT tasks with `releasedOn` == today show a text badge "New today"/"Neu heute", listed first in normal order; normal from next day; filters still apply | next-view.spec `new today` (2 tests), `isNewToday / newTodayFirst` (2 tests); German text in new messages-de.spec test | focused `ng test` | Pass |
| AC5: strings in both xlf files; unit tests; `npm run check` passes | messages-de.spec (all units present, current, translated) + new test; full web suite via `npm run coverage` | `npm run coverage` | Pass for the full unit suite; `npm run check` as a whole Not run here (lint/format/typecheck of the changed spec files run separately and pass; parent runs `.taskcheck`) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/gtd/**/*.spec.ts' --include='src/locale/**/*.spec.ts'`

Result:

Pass - 36 test files, 1014 tests passed.

Also: `npx eslint` and `npx prettier --check` on the two changed spec files -> clean.

## Full test execution

Command:

`cd web && npm run coverage` (the full web unit suite with coverage)

Result:

Pass - 199 test files, 5000 tests passed.

## .taskcheck

Result:

Not run (time budget; the full web unit suite passed above; the parent runs `.taskcheck` before `done`).

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no changed-line tooling configured).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-gtd-tickler-chip; base b2a2bad83d4fd470bbf03a92657f6a6d162f3908; task commit 19c60cb42 from the parent, rebased patch-identical to e9421dbd2; measured HEAD 41ee561635d2c2c2e5f8a05d065bc9398034b1de; file scope from `git diff --stat b2a2bad83..19c60cb42`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 41ee56163 plus uncommitted `web/src/locale/messages-de.spec.ts` sha256 d7027c50440cb30f37929ba3ab3978abaa7a8fb2915acdc599f0c0cd03078534 and `web/src/app/features/gtd/task-detail/task-draft.spec.ts` sha256 41bef9f0a1dc4b4327cd7dacb92d583cced56e6f7c878f1fcadb33c2a6e4d6e4 (unchanged between the run and the report).
- Exact included files: `web/src/app/features/gtd/task-detail/task-draft.ts`, `task-detail.ts`, `web/src/app/features/gtd/next/next-view.ts`, `web/src/app/features/gtd/data/task-store.ts` (formatting-only change), `web/src/app/features/gtd/ui/context-chips.ts` (formatting-only change). Excluded: `*.html`, `*.scss` templates/styles (not instrumented by v8; behavior covered by the component specs), `web/src/locale/*.xlf` (data, checked by messages-de.spec), spec files. No renamed or deleted production files.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8` ^5.0.5; `npm run coverage` in `web/`; full suite.
- Run start/completion time and exit status: started 2026-11-23 18:37:55 +01:00, report written 18:41:41 +01:00; all tests passed, coverage summary printed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 f859362b4156c5abf8ff248ef2457a29bd241d06fc4e49481ce28d7b82bfa791; `web/coverage/web/coverage-summary.json`.
- Evidence outputs are fresh and inputs stayed unchanged: yes (report mtime from this run; Vitest rewrites `coverage/`; the web tree was the same before and after; HEAD changed only by a review-file commit and a patch-identical rebase).
- Final run after latest test additions and production fixes: yes (no production fixes so far).

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/task-detail/task-draft.ts` | 84/84 (100%) | 126/126 (100%) | 14/14 (100%) | 70/70 (100%) |
| `web/src/app/features/gtd/task-detail/task-detail.ts` | 352/356 (98.87%) | 227/242 (93.80%) | 69/69 (100%) | 290/290 (100%) |
| `web/src/app/features/gtd/next/next-view.ts` | 101/104 (97.11%) | 40/43 (93.02%) | 38/39 (97.43%) | 86/88 (97.72%) |
| `web/src/app/features/gtd/data/task-store.ts` | 85/85 (100%) | 99/102 (97.05%) | 28/28 (100%) | 68/68 (100%) |
| `web/src/app/features/gtd/ui/context-chips.ts` | 28/28 (100%) | 12/13 (92.30%) | 4/4 (100%) | 18/18 (100%) |
| Aggregate (5 files, summed counters) | 650/657 (98.93%) | 504/526 (95.82%) | 153/154 (99.35%) | 532/534 (99.63%) |

No scoped file is missing from the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `task-detail.ts:333` branch [1] (`setList` when no task is loaded) | defensive guard; the selector only renders with a task | justified gap, not reachable from the UI |
| `task-detail.ts` stmts 354, 409, 502, 517; branches 100, 181, 196, 226, 258, 297, 354, 358, 409, 483, 502, 517, 522 | pre-existing code (save/convert/calendar error paths), not changed by this task | out of task scope |
| `next-view.ts:247-249` (uncomplete in `toggleDone`), branches 80, 212 | pre-existing code, not changed by this task | out of task scope |
| `task-store.ts` branches 57, 96, 114; `context-chips.ts:32` | pre-existing; this task only reformatted those files | out of task scope |

## Unable to test

- Visual look of the "New today" badge (SCSS) and real-browser behavior of the date inputs: no criterion asks for an E2E run; jsdom component tests cover text, order and form behavior.

## Summary

Tests added and all validation passed. Task-scoped coverage is sufficient; no production defects found.
