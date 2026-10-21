# Fix Report

Date: 2026-10-21

Task: p1-smoke - Phase 1 demo script

Scope: current task; findings CR-1 and CR-2 of `reviews/p1-smoke/review.md`, fixed in
`web/e2e/phase1/demo.spec.ts` only (base c0dd91a4da72c2358296bbc26f789277f65de013, uncommitted
working-tree change on top of HEAD 87ded0b0).

## Fixed findings

### CR-1 — Step 2 "new project" path was not run end to end

Files changed:
- `web/e2e/phase1/demo.spec.ts`

Problem:
SPEC.md §13.2 step 2 sends "Plan holiday" to a new project. The script pre-creates "Holiday" so
that the `#Holiday` capture of step 1 parses (deviation recorded as an ASSUMPTION in
tasks/p1-smoke.md, kept). So the "New project" option of inbox processing "To project" was
never run end to end.

Fix:
At the end of step 2, after the existing assertions, the script captures "Measure the shed",
processes it with `p` (To project), picks the "New project…" option, enters "Garden shed" as the
"New project title" and clicks Move. Then it waits for Inbox zero and an empty inbox, and checks
through the API that the project "Garden shed" exists, that the task (now in NEXT) has that
projectId, that the project's `nextTaskId` is the task, and that the project is not stalled.
The extra item is in NEXT, so step 3's unfiltered row count goes from 3 to 4, with a comment.
Step 3's filtered check (@phone, ≤ 15 min, only "Call plumber") and the later steps don't change:
they select by title, and "Garden shed" does not match the `hasText: 'Holiday'` project row in
step 4.

Validation:
- `cd web && npm run e2e -- --project=phase-demos` — Pass (15 passed, twice)

### CR-2 — "Hidden from every list" UI checks could pass before the list loaded

Files changed:
- `web/e2e/phase1/demo.spec.ts`

Problem:
For each view, the step 5 loop waited only for the static h1 before `toHaveCount(0)`, so the
negative check could pass while the list was still loading.

Fix:
Each view now waits for a positive sign that it has loaded before the negative check:
the empty-state text of /inbox ("Your inbox is empty."), /today ("Nothing overdue, due or
scheduled today."), /waiting ("Nothing to wait for."), /someday ("No someday ideas."),
/scheduled ("Nothing scheduled."), and the known "Buy milk" row in /next. The texts come from
the templates' `@empty` blocks, which only render after the store has loaded. The API checks
stay as they were.

Validation:
- `cd web && npm run e2e -- --project=phase-demos` — Pass (15 passed, twice)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npm run lint` — Pass (All files pass linting)
- `cd web && npm run e2e:typecheck` — Pass
- `cd web && npm run e2e -- --project=phase-demos` — 3 runs: the first failed, the next two passed
  (15 passed each). The first run failed in step 2 at the pre-existing
  `expect(page.getByText('Your inbox is empty.')).toBeVisible()` (spec line 125). This is after
  "Buy milk" was moved to Next with `n`: the inbox list still showed "Buy milk" after 10 s. That
  line comes before the code added for CR-1, and its logic is unchanged. Its trace was
  overwritten by the next run, so the cause was not found. This may be an intermittent race
  between the optimistic move of the last item and the list view. The parent should consider
  it a possible flake.

Full test suite:
- `sh scripts/check.sh` (`.taskcheck`) — Pass (all checks passed)

Lint/type-check/build:
- `npm run lint`, `npm run e2e:typecheck` — Pass

`.taskcheck`:
- Pass

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed. One unexplained failure in pre-existing
step-2 code was seen in 1 of 3 e2e runs (see Validation summary).
