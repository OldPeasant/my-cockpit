# Test Report

Date: 2026-10-21

Task: p1-smoke - Phase 1 demo script

Scope: `git diff c0dd91a4da72c2358296bbc26f789277f65de013 2b1d34bbe397ef123b698e1f08d8180fbe8e5491`
(base = merge-base with origin/main as given by the parent; task commits 17e6235b, 193f1eec, 2b1d34bb
on branch `main`), plus the uncommitted test changes of this report in
`web/src/app/features/gtd/inbox/inbox-page.spec.ts`. Changed files in the task diff:
`web/e2e/phase1/demo.spec.ts` (new e2e spec), `web/playwright.config.ts` (phase-demos project,
`workers: 1`), `web/src/app/features/gtd/inbox/inbox-page.ts` (production fix) and its spec.

Requirements:
- `tasks/p1-smoke.md` acceptance criteria 1 and 2
- `SPEC.md` §13.2 Phase 1 steps 1-9, §4.5 (capture syntax), §5.1-§5.3 (GTD rules and UI)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`

## Test files changed

- `web/src/app/features/gtd/inbox/inbox-page.spec.ts`

## Tests added or updated

Count: 3 (unit tests added for the inbox-page fix; the e2e spec was reviewed, not changed)

- `does not preselect the captured project when it is not active` - the `#project` preselection
  only applies to open (ACTIVE) projects; an ON_HOLD captured project falls back to the first open one.
- `does not set a next step when the move into a stalled project fails` - no `setProjectNextStep`
  call when the move is refused; the item comes back unprocessed.
- `sets only one next step when two items go into the same stalled project` - ordering: the first
  move answers (deferred promise) after the second item was already filed; exactly one next step is set.

The four unit tests the parent added with the fix (preselect captured project and set it as next
step with If-Match, new project becomes next step, project with a next step is kept, failure of
setting the next step still counts the item as processed) were reviewed and kept as they are.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 / §13.2 step 1: Q captures 3 items, parsed project, due date, context | `Phase 1 demo › 1. quick capture (Q) ...` | `cd web && npm run e2e -- --project=phase-demos` | Pass |
| AC1 / step 2: process Inbox (Next with @phone/LOW/5 min, to project as next step, Next), Inbox empty | `Phase 1 demo › 2. processing the Inbox ...`; unit: `makes the item the next step of a project without one`, `makes the item the next step of its new project`, `keeps the next step of a project that has one`, `counts the item as processed when setting the next step fails`, `does not preselect the captured project when it is not active`, `does not set a next step when the move into a stalled project fails`, `sets only one next step when two items go into the same stalled project` | e2e as above; `npx ng test --watch=false --include src/app/features/gtd/inbox/inbox-page.spec.ts`; `npm run check` | Pass |
| AC1 / step 3: Next filtered @phone + ≤15 min shows only "Call plumber"; done -> Logbook | `Phase 1 demo › 3. ...` | e2e as above | Pass |
| AC1 / step 4: completing the next step prompts "Choose next step"; skip -> stalled | `Phase 1 demo › 4. ...` | e2e as above | Pass |
| AC1 / step 5: tickler today+2 hidden from all lists; test clock +2 days -> back in Inbox | `Phase 1 demo › 5. ...` | e2e as above | Pass |
| AC1 / step 6: weekly FIXED Monday -> next Monday; AFTER_COMPLETION completed late -> relative to completion | `Phase 1 demo › 6. ...` | e2e as above | Pass |
| AC1 / step 7: "Waiting for: Anna - contract" in Waiting For, sorted by waitingSince | `Phase 1 demo › 7. ...` | e2e as above | Pass |
| AC1 / step 8: two linked tasks show each other; global search "passport" finds the task | `Phase 1 demo › 8. ...` | e2e as above | Pass |
| AC1 / step 9: delete -> Trash -> restore | `Phase 1 demo › 9. ...` | e2e as above | Pass |
| AC1: passes together with phase 0 specs | `Phase 0 demo › 1.`-`5.` (same run, 1 worker, phase order) | e2e as above | Pass (15 passed) |
| AC2: defects fixed here or recorded as tasks | The inbox "To project" fix (17e6235b) is covered by the unit tests above and e2e step 2 | `npm run check` | Pass; no further production defects found by this test-writer |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/features/gtd/inbox/inbox-page.spec.ts`

Result:

Pass (1 file, 61 tests passed)

## Full test execution

Command:

`cd web && npm run check` (api:check, eslint `--max-warnings=0`, e2e typecheck, `ng test --watch=false`, `ng build`)

Result:

Pass (exit 0; "All files pass linting."; 74 test files, 1367 tests passed; build complete)

Command:

`cd web && npm run e2e -- --project=phase-demos` (Playwright, Desktop Chrome, against the podman-compose e2e stack built by `e2e/support/run-e2e.mjs`)

Result:

Pass (exit 0; 15 passed, 1 worker: phase 0 steps 1-5, phase 1 set-up and steps 1-9). Run on the
committed e2e spec at HEAD 2b1d34bb; the e2e inputs are unchanged by this report's edits.

## .taskcheck

Result:

Pass (`sh scripts/check.sh` from the repo root via `web/`: tfp gate tests, server `./gradlew check`,
web `npm run -s check`, android ktlint + unit tests + lint; exit 0, "all checks passed").

## Bugs found

None. No test exposed a production defect.

Observations for the parent (not production defects, no fix required by the fixer):

- `TW-1` (Low, spec fidelity): SPEC.md §13.2 step 2 says "Plan holiday" goes to a **new** project
  "Holiday", but step 1 captures it with `#Holiday`, and the capture parser
  (`server/.../gtd/domain/CaptureParser.kt`) only resolves existing projects. The e2e spec reconciles
  this by creating an empty (hence stalled) "Holiday" project in a set-up step and filing the item
  into it, so it becomes its next step. The "new project becomes its next step" path is covered only
  by the unit test `makes the item the next step of its new project`, not end to end. The parent
  should record this as an assumption (`tasks.py assume`) if not already done.
- `TW-2` (Low, test strength): in step 6 the FIXED task is due Monday and completed Monday, so its
  expected next date (Monday + 7) is the same under FIXED and AFTER_COMPLETION; it does not tell the
  two modes apart. The SPEC wording is still met, and the AFTER_COMPLETION task (due Friday,
  completed Monday) does distinguish the modes. No change made.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. Method: added lines from
`git diff -U0 c0dd91a4 HEAD -- web/src/app/features/gtd/inbox/inbox-page.ts` (hunks +167..168,
+170..173, +285..288) intersected with the `DA:` lines of `lcov.info` (v8 source-mapped); executable
added lines 3/3 (100%) - line 168 (56 hits), 287 (10), 288 (10); the multi-line statement at
170-173 maps to one branch set. Branches on added lines (lcov `BRDA`): 6/6 hit (L170 `??` chain 4/4,
L288 `if (project?.stalled)` 2/2).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `p1-smoke`;
  base `c0dd91a4da72c2358296bbc26f789277f65de013`; HEAD `2b1d34bbe397ef123b698e1f08d8180fbe8e5491`;
  task commits 17e6235b, 193f1eec, 2b1d34bb as supplied by the parent and matched against the diff.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 2b1d34bb plus uncommitted
  `web/src/app/features/gtd/inbox/inbox-page.spec.ts` sha256
  `a37871973d61dd35a5a580441547dbcf3eba61bf2aa7dca8447dced6bcc99320`; `inbox-page.ts` sha256
  `5a5d0459de03e6200d9c4e36c3990daa60bd14f5d2bb9d54b76eeb6d201cf93f` (unchanged from HEAD); hashes
  identical before and after the runs.
- Exact included files; renamed/deleted/excluded files and reasons: included
  `web/src/app/features/gtd/inbox/inbox-page.ts`. Excluded: `web/e2e/phase1/demo.spec.ts` and
  `web/src/app/features/gtd/inbox/inbox-page.spec.ts` (tests), `web/playwright.config.ts` (test
  runner configuration). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with
  `@vitest/coverage-v8` 5.0.5; `npm run coverage` in `web/`; full web unit suite (74 files, 1367 tests).
- Run start/completion time and exit status: 2026-10-21T02:39:34Z - 2026-10-21T02:40:06Z, exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json`
  (sha256 `43827b3a8d2081811ff2accda7b27bebdb8ac69aa71ec094c2c647a364541690`),
  `web/coverage/web/coverage-final.json` (sha256
  `ffbd9bffa88800b9c407b7f407da101214ba840fb0d10fea0ef6475fd6f3795f`), `web/coverage/web/lcov.info`.
- Evidence outputs are fresh and inputs stayed unchanged: report files written 2026-10-21 04:40:06
  +0200 by this run (Vitest rewrites `coverage/`); input hashes rechecked after the run.
- Final run after latest test additions and production fixes: yes (no production change after the
  test additions; the run includes the 3 added tests).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/inbox/inbox-page.ts` | 154/158 (97.46%) | 87/91 (95.60%) | 37/38 (97.36%) | 126/127 (99.21%) |

Aggregate over the scope (one file, no unmeasured or missing files): same as the row above.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `inbox-page.ts:292` (`default: return` in `apply`) | none: only reached for actions without an effect (To reference / To calendar are disabled and their keys do nothing) | Pre-existing, not changed by the task; defensive, left uncovered |
| `inbox-page.ts:160` branch (`if (!task) return` in `choose`), `:223` branch (`if (!task) return` in `run`) | guards for no current item; callers already check `current()` | Pre-existing defensive guards, not task code; left uncovered |
| `inbox-page.ts:40` branch (component decorator) | compiler-generated branch, no behavior | Not material |

## Unable to test

- Nothing in scope was left untested. The "new project" variant of §13.2 step 2 is not run end to
  end (see TW-1); it is covered by unit tests only, because the capture syntax of step 1 needs the
  project to exist.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. Three unit tests were
added for the inbox "To project" fix; the web check, the phase demo e2e run (15 passed) and
`.taskcheck` pass. Two low-severity spec-fidelity observations (TW-1, TW-2) are for the parent to
record or accept.
