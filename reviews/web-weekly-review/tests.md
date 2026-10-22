# Test Report

Date: 2026-10-22

Task: web-weekly-review - Weekly review wizard

Scope: `git diff 8fc91c0abee46c1ee0b269556c9bafcc05d44258..1fae11dcb39a2f26247a9e31e6ec3accb83f4daf` (task commits c1407cba wizard, 26555389 session/page tests, 8e508c43 e2e spec, 1fae11dc step component tests), branch `main`, plus the uncommitted test changes listed below. Production changes: `web/src/app/features/review/**` (non-spec `.ts`, `.html`, `.scss`). Also in the diff: `web/e2e/review/review.spec.ts`, `web/playwright.config.ts` and a one-line `web/src/app/app.spec.ts` change. These are test/config files, not instrumented production code.

Requirements:
- `tasks/web-weekly-review.md` acceptance criteria and the recorded assumptions (Get clear links to Mail for flagged mails; reflection appended as a "## Weekly review" section of today's journal entry, with the draft kept in localStorage per review id; Keep only removes the item from the step's list; quick-add captures to the Inbox; the projects step's Add creates a NEXT task and sets it as the next step)
- `SPEC.md` §8.6 (8 steps, resumable, progress persisted in `weekly_review.step`)
- `docs/ARCHITECTURE.md` "Coverage"; `AGENTS.md`

## Test files changed

- `web/src/app/features/review/review-session.spec.ts` (extended)
- `web/src/app/features/review/review-page-reflection.spec.ts` (new)

The existing tests were not changed in substance. In `review-session.spec.ts` the API stub now also records the `HttpContext` argument and can hold a call open (`hold(fn)`). No assertion was removed, weakened or skipped.

## Tests added or updated

Count: 14 new tests (11 in `review-session.spec.ts`, 3 in `review-page-reflection.spec.ts`)

The task's existing specs (review-session, review-page, steps/*) already covered step navigation, resume, the keep/someday/delete/activate/follow-up actions, and appending the reflection to the journal entry, including the error paths. The new tests fill the remaining behavioural gaps:

- `ReviewSession`: saves only one move when a second one is asked while the first is saved; completes only once when finishing is clicked twice; reloads the review when a move finds it gone (404); shows the start screen when another tab already completed the review (409 on complete); stays on the start screen when starting fails; looks up today's journal entry without an error message for a missing one (`SILENT_ERRORS` set on `getJournalEntry`); keeps a reflection draft per review; drops the stored draft when the reflection is cleared; does not store a draft before a review is open; without usable browser storage: resumes the review without a draft when storage cannot be read, and keeps the reflection in the page and saves it when storage cannot be written.
- `ReviewPage with the reflection step` (the real `ReflectionStep` inside the real page, other steps stubbed). This checks that the step and the page share the page-provided `ReviewSession`: it saves what was typed in the reflection as a journal entry when finishing; it shows the reflection draft again when the review is resumed; it keeps the reflection when leaving the step and coming back.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: 8 steps of SPEC §8.6 | `ReviewPage` "shows every step with its own content", "starts at step 1 of 8 with the inbox"; `GetClearStep` "embeds the inbox processing and links to Mail for flagged mails"; `CalendarStep` suite (past 7 / upcoming 14 days, quick-add to inbox); step-lists suites for Waiting For, Projects, Next actions, Someday; `ReflectionStep` suite | `cd web && npm run check` (test:ci) | Pass |
| AC1: embedded inbox processing | `GetClearStep` "embeds the inbox processing..." (inbox page stubbed; the inbox page has its own spec) | `npm run check` | Pass |
| AC1: keep/someday/delete actions (also still waiting/follow up/done, activate, project status / next step) | step-lists.spec.ts: "keep removes the action without calling the server", "someday moves the action to SOMEDAY with If-Match...", "delete trashes the action...", Someday activate/keep/delete for tasks and projects, Waiting still waiting/follow up/done, Projects add/status/looks good, plus failure and double-click cases | `npm run check` | Pass |
| AC1: resumable | `ReviewSession` "resumes the open review at its saved step", "keeps the reflection draft for a resumed review...", "keeps a reflection draft per review", storage-unavailable tests; `ReviewPage` "resumes an open review at its saved step"; `ReviewPage with the reflection step` "shows the reflection draft again when the review is resumed" | `npm run check` | Pass |
| AC1: reflection saved as journal entry | `ReviewSession` "saves the reflection as a new journal entry of today", "appends the reflection to today's entry, keeping its mood and tags", "does not append the reflection twice...", "looks up today's journal entry without an error message..."; `ReviewPage with the reflection step` "saves what was typed in the reflection as a journal entry when finishing" | `npm run check` | Pass |
| AC2: Vitest tests cover step navigation and resume | `ReviewPage` "walks forward and back through the steps...", "jumps to any step from the rail", "disables the navigation while a move is saved..."; `ReviewSession` move/conflict/concurrency tests | `npx ng test --watch=false --include='src/app/features/review/**/*.spec.ts'` -> 7 files, 109 tests passed | Pass |
| AC2: `cd web && npm run check` passes | whole web suite, lint, api:check, e2e typecheck, build | `cd web && npm run check` -> api up to date, lint clean, 145 files / 2936 tests passed, build OK (existing initial-bundle budget warning only) | Pass |
| AC3: Playwright `e2e/review/review.spec.ts` against the e2e stack | `web/e2e/review/review.spec.ts` | `npm run e2e -- e2e/review/review.spec.ts` | Not run: per the parent's instruction. The parent is running it against the docker e2e stack, and a parallel run would collide. Only type-checked here, via `npm run e2e:typecheck` inside `npm run check` |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/review/**/*.spec.ts'`

Result:

Pass (7 test files, 109 tests)

## Full test execution

Command:

`cd web && npm run check`

Result:

Pass (api:check up to date, `ng lint --max-warnings=0` all files pass, `tsc -p e2e/tsconfig.json` OK, `ng test --watch=false` 145 files / 2936 tests passed, `ng build` OK with the pre-existing "bundle initial exceeded maximum budget" warning). The new spec was formatted with `npx prettier --write`, and `npx prettier --check` passes on both changed specs.

## .taskcheck

Result:

Not run. `.taskcheck` is `sh scripts/check.sh` (server, web and android). The task changes only `web/`, and the web part (`npm run check`) was run and passed. The parent runs `.taskcheck` before `done`.

## Bugs found

None. No test exposed a violation of the acceptance criteria or the recorded assumptions.

Non-blocking observation (no requirement covers it, so it is not filed as a finding with a failing test): in `web/src/app/features/review/steps/projects-step.ts` `addNextStep`, the typed draft is cleared right after `createTask` succeeds and before `setProjectNextStep` runs. If `setProjectNextStep` then fails, the project stays stalled, the field is empty, and the NEXT task already exists in the project. Typing the step again creates a second task. The parent may decide whether this matters.

## Coverage

Status: verified (fresh run on the final test snapshot; no production code changed during this session)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured. Every scoped file is new in this task, apart from `review.routes.ts`, which changed in 5 lines. Whole-file coverage is therefore effectively changed-file coverage, but no line intersection was computed.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-weekly-review`; base `8fc91c0abee46c1ee0b269556c9bafcc05d44258` (from the parent); HEAD `1fae11dcb39a2f26247a9e31e6ec3accb83f4daf`; task commits c1407cba, 26555389, 8e508c43, 1fae11dc (from the parent, matching `git log 8fc91c0a..HEAD`); file scope from `git diff --name-only 8fc91c0a..HEAD -- web/src` minus `*.spec.ts`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 1fae11dc plus uncommitted `review-session.spec.ts` sha256 `c6e809ce7bd8f9c6eac4a1cb9f12996532da716ea95abf44c427fcf4f0600c0c` and new `review-page-reflection.spec.ts` sha256 `9a709e12b0ae9b9812011be4039900850142940cdb0faf3af7957c6f38bc1c15`. No production files are modified in the working tree.
- Exact included files: `web/src/app/features/review/{review-page,review-session,review-steps,review.routes}.ts`, `web/src/app/features/review/steps/{calendar-step,get-clear-step,next-actions-step,projects-step,reflection-step,someday-step,step-list,waiting-step}.ts`. Templates (`review-page.html`) and styles (`*.scss`) are not reported separately by v8; the compiled template code is attributed to the component `.ts` file. Excluded: `web/e2e/review/review.spec.ts`, `web/playwright.config.ts`, `web/src/app/app.spec.ts` (test/config, not in the Vitest coverage scope). No renamed or deleted files. All 12 scoped files are present in the report.
- Provider/version, command, working directory, and test selection: Vitest v5.0.5 + `@vitest/coverage-v8` via `@angular/build:unit-test`; `npm run coverage` in `web/`; the full web unit suite (145 files, 2936 tests).
- Run start/completion time and exit status: 2026-10-22T03:47:51+02:00 to 03:49:00+02:00. All 2936 tests passed and the coverage summary was printed. The exit code itself was not captured (the output was piped through `tail`). An earlier, identical run before the new tests (03:42:43-03:43:47) returned exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` (sha256 prefix `0c8497b8295f931a`, mtime 2026-10-22 03:49:00), `web/coverage/web/coverage-summary.json` (sha256 prefix `8ee218cf2e47aea0`). Per-file counters were read from `coverage-summary.json` and the uncovered locations from `coverage-final.json`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. `coverage-final.json` was rewritten at the end of the run (its hash differs from the pre-test run's `9332a88a32b8cf70`), and both spec hashes were the same before and after the run.
- Final run after latest test additions and production fixes: yes for the test additions. No production fixes were made. If the fixer changes production code, this evidence is stale.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/review/review-page.ts` | 15/16 (93.75%) | 7/11 (63.64%) | 5/6 (83.33%) | 8/9 (88.89%) |
| `web/src/app/features/review/review-session.ts` | 73/73 (100%) | 49/50 (98.00%) | 14/14 (100%) | 63/63 (100%) |
| `web/src/app/features/review/review-steps.ts` | 4/4 (100%) | 2/2 (100%) | 3/3 (100%) | 3/3 (100%) |
| `web/src/app/features/review/review.routes.ts` | 1/1 (100%) | N/A (0/0) | N/A (0/0) | 1/1 (100%) |
| `web/src/app/features/review/steps/calendar-step.ts` | 97/97 (100%) | 33/36 (91.67%) | 12/12 (100%) | 69/69 (100%) |
| `web/src/app/features/review/steps/get-clear-step.ts` | 4/10 (40.00%) | 6/9 (66.67%) | 0/1 (0%) | 1/5 (20.00%) |
| `web/src/app/features/review/steps/next-actions-step.ts` | 62/62 (100%) | 12/13 (92.31%) | 11/11 (100%) | 38/38 (100%) |
| `web/src/app/features/review/steps/projects-step.ts` | 99/99 (100%) | 26/27 (96.30%) | 17/17 (100%) | 64/64 (100%) |
| `web/src/app/features/review/steps/reflection-step.ts` | 49/49 (100%) | 14/15 (93.33%) | 4/4 (100%) | 35/35 (100%) |
| `web/src/app/features/review/steps/someday-step.ts` | 71/71 (100%) | 18/19 (94.74%) | 13/13 (100%) | 46/46 (100%) |
| `web/src/app/features/review/steps/step-list.ts` | 39/39 (100%) | 11/12 (91.67%) | 11/11 (100%) | 29/29 (100%) |
| `web/src/app/features/review/steps/waiting-step.ts` | 69/69 (100%) | 16/17 (94.12%) | 11/11 (100%) | 45/45 (100%) |
| **Aggregate (summed counters, all 12 scoped files; none excluded or unmeasured)** | 583/590 (98.81%) | 194/211 (91.94%) | 101/103 (98.06%) | 402/407 (98.77%) |

The scoped numbers are identical to the run before the new tests. The new tests add behavioural assertions (concurrency, conflicts, storage failure, shared session) on lines that were already executed. They do not add line coverage.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `review-page.ts:30` (stmt/fn), `:30` if[0], `:34` binary-expr[0,1] | Angular-compiler class/factory glue at the decorator and class declaration (the spec recompiles the component via `overrideComponent`) | Justified gap: generated code, no task behaviour |
| `review-page.ts:47` if[1] (`goTo(null)`) | Back pressed on the first step | Justified gap: the template disables Back when there is no previous step (asserted in "starts at step 1 of 8 with the inbox"), so a `null` step cannot reach `goTo` |
| `review-session.ts:48` `?? 'GET_CLEAR'` | step read while no review is open | Justified gap: defensive default; the wizard only renders `step()` in phase `open` |
| `calendar-step.ts:102` if[0]; `:152` `event.startDate ?? 0` fallbacks | compiler glue; a timed occurrence without `start` | Justified gap: generated code; the API always sends `start` for timed occurrences (defensive fallback) |
| `get-clear-step.ts:15-19, 22` (stmts, fn, branches) | the AOT-compiled inline template and class glue of a logic-free component | Justified gap: `get-clear-step.spec.ts` swaps in an inbox stub via `overrideComponent`, so the JIT-recompiled template runs instead of the AOT one. Its behaviour (embedded inbox with the `embedded` class, Mail link to `/mail`) is asserted. The real embedding is exercised by the e2e spec (not run here) |
| `next-actions-step.ts:76`, `projects-step.ts:99`, `reflection-step.ts:57`, `someday-step.ts:87`, `waiting-step.ts:86` if[0]; `step-list.ts:10` binary-expr[1] | Angular class/factory glue at the class declaration lines | Justified gap: generated code |

## Unable to test

- AC3, the Playwright `e2e/review/review.spec.ts` against the e2e docker stack: not run, on the parent's explicit instruction (the parent is running it; a parallel run would collide on the stack). The parent must record its own run as evidence.
- The real `InboxPage` embedded in the Get clear step is not exercised in Vitest. It is stubbed, and the inbox page has its own spec. The e2e spec covers the real embedding (follow-up: the parent's e2e run).

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. 14 tests were added (session concurrency and conflict handling, storage failure, per-review drafts, the silent journal lookup, and page + real reflection step integration). The focused suite and `cd web && npm run check` pass. Task-scoped whole-file coverage is 98.81% statements, 91.94% branches, 98.06% functions and 98.77% lines, and the remaining gaps are compiler glue or unreachable defensive branches. AC3 (Playwright) was not run here and must be verified by the parent.
