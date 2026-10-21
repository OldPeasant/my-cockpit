# Test Report

Date: 2026-10-21

Task: web-projects - Projects views

Scope: task commits `c262188986aed7f56420943936d30d47069c8524..942dc560634d5e380ad3d276ca818c98e067df3f` (base = merge-base with origin/main, given by the parent worker; 8 task commits d0081318..942dc560), plus this run's uncommitted test changes. Production code: `web/src/app/features/gtd/projects/` (the task's `touches`). No unrelated production files are in the diff.

Requirements:
- `tasks/web-projects.md` acceptance criteria and recorded assumptions
- SPEC.md §5.3 (as referenced by the task), docs/ARCHITECTURE.md "Coverage"
- `reviews/web-projects/review.md`, `reviews/web-projects/fixes.md` (earlier findings CR-1..CR-5, TW-1, TW-2)

## Test files changed

- `web/src/app/features/gtd/projects/projects-states.spec.ts` (this run)
- `web/src/app/features/gtd/projects/project-groups.spec.ts` (this run)

Already committed by the earlier test-writer run and the implementation: `projects-pages.spec.ts`, `projects-states.spec.ts`, `project-form.spec.ts`, `project-groups.spec.ts`, `project-task-store.spec.ts`, `area-store.spec.ts`, `markdown.spec.ts`, `web/src/app/app.spec.ts` (1 line), `web/e2e/gtd-projects/projects.spec.ts`.

## Tests added or updated

Count: 7 added in this run (93 tests in the projects folder, up from 86)

- `list › cancelling an inline edit closes the form and keeps the project unchanged`
- `detail › cancelling the edit form on the detail keeps the project unchanged`
- `detail › shows the project while its tasks are still loading`
- `detail › submitting "Add task" again while the first is saving creates one task`
- `detail › submitting "Add as next step" again while the first is saving creates one task`
- `detail › completing the next step twice in a row sends one completion and opens one prompt`
- `project status helpers › shows an unknown status from a newer server as its raw value`

The three double-submit tests hold the request open with a deferred promise. The input text stays until the create call returns, so only the `run()` busy guard prevents a second create or completion. These tests would fail without that guard.

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include='src/app/features/gtd/projects/**/*.spec.ts'`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: list grouped by area (area order, "No area" last, unknown area to "No area", empty areas left out, status then title order) | `groupByArea` (3 tests); `Projects views › list › groups projects by area in area order and highlights stalled ones` | F | Pass |
| AC1: stalled projects highlighted (badge, row class, count, hidden ones not counted) | `groups projects by area … highlights stalled ones`; `counts several stalled projects and leaves hidden ones out of the count` | F | Pass |
| AC1: create project (status, area, dates; title required, trimmed) | `creates a project with status, area and dates`; `ProjectForm` create tests (start values, whitespace title refused, optional fields undefined, single request while saving, failure re-enables) | F | Pass |
| AC1: edit project (status, area, dates; only changed fields; cleared = null; cancel) | `edits a project, sending only the changed fields`; `ProjectForm` edit tests; `edits the project from the detail and announces the save`; `cancelling an inline edit …` (new); `cancelling the edit form on the detail …` (new) | F | Pass |
| AC1 assumption: completed/dropped hidden behind a toggle | `shows completed and dropped projects on request`; `says "No open projects" …` | F | Pass |
| AC2: ordered tasks with drag reorder (and keyboard alternative) | `dragging a task saves the new order and announces it`; `shows the dragged order while it is saved`; `a drop at the same place sends nothing`; `a refused reorder restores …`; arrow-key tests (4); `ProjectTaskStore` (6 tests, incl. reverse-order answers and failure of an older reorder) | F | Pass |
| AC2: next-step marker; make another task next | `lists the open tasks in order with the next step marked`; `makes another task the next step`; `the prompt stays open when making a task the next step fails` | F | Pass |
| AC2: description with Markdown preview (escaping, hostile input) | `edits the description with a live Markdown preview`; `an emptied description is cleared on the server`; `cancel and Escape discard …`; `keeps the description editor open when saving fails`; `renders hostile description Markdown as text`; `markdown.spec.ts` | F | Pass |
| AC2: add task (stalled project gets it as next step; whitespace refused; failure keeps text; no duplicate on retry or double submit) | `adds a task; a stalled project gets it as next step`; `adding a task to a project with a next step keeps that next step`; `does not add a whitespace-only task …`; `a failed next step after adding a task …`; `submitting "Add task" again while …` (new) | F | Pass |
| AC2: completing the next step opens "Choose next step" (pick, add new, skip, Escape, focus, failures, double completion) | `completing the next step opens "Choose next step"; picking a task sets it`; `the prompt adds a new task as next step`; `skipping leaves the project stalled`; `completing another task does not prompt`; `a failed completion of the next step does not prompt`; focus tests (2); `a failed "add as next step" …`; `the prompt without open tasks …`; `submitting "Add as next step" again …` (new); `completing the next step twice in a row …` (new) | F | Pass |
| Loading / error / not-found states | list and detail loading+error+retry tests; `shows an error when the tasks cannot be loaded, and retries`; `shows the project while its tasks are still loading` (new); `says when the project does not exist` | F | Pass |
| AC3: Vitest tests cover grouping, stalled badge, next-step prompt; `cd web && npm run check` passes | all of the above | `cd web && npm run check` | Pass (exit 0) |
| AC4: Playwright `e2e/gtd-projects/projects.spec.ts` against the e2e stack | `web/e2e/gtd-projects/projects.spec.ts` | not run | Not run: the parent worker asked to run it itself. Only `e2e:typecheck` ran here, which does not count as execution. |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/gtd/projects/**/*.spec.ts'`

Result:

Pass: 7 files, 93 tests (86 before this run's additions, also Pass)

## Full test execution

Command:

`cd web && npm run check` (api:check, lint, e2e:typecheck, test:ci, ng build)

Result:

Pass (exit 0). "All files pass linting.", 71 test files / 1282 tests passed, build OK. The build prints the existing initial-bundle budget warning (642.29 kB > 500 kB). It is unrelated to this task because projects is a lazy chunk. `npx prettier --check` on both changed specs: clean.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` printed "== all checks passed" (server, web, android).

## Bugs found

None. All new tests passed against the current implementation (after the fixer's commit d99e65f2).

## Coverage

Status: verified (whole-file, fresh run after the final test additions; no production change since).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. Every scoped production file was added by this task, except `projects.routes.ts` (+4/-3, its single statement is covered). Whole-file coverage is therefore effectively changed-line coverage for these files. Hits from pre-existing tests (e.g. `app.spec.ts`) are included.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-projects`; base `c262188986aed7f56420943936d30d47069c8524` (from the parent); HEAD `942dc560634d5e380ad3d276ca818c98e067df3f`; scope = `git diff --stat base..HEAD`, matching the task's `touches: web/src/app/features/gtd/projects`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 942dc560 plus uncommitted `projects-states.spec.ts` (blob 009807718666dcbca1d653787505fcd1df490050) and `project-groups.spec.ts` (blob c9d8864b39c8f6abe42598e50e2334daf9aa5a0b). No production files modified.
- Exact included files: `area-store.ts`, `markdown.ts`, `project-detail-page.ts`, `project-detail-page.html`, `project-form.ts`, `project-form.html`, `project-groups.ts`, `project-task-store.ts`, `projects-page.ts`, `projects-page.html`, `projects.routes.ts` (all under `web/src/app/features/gtd/projects/`). The provider reports the templates as separate entries. Excluded: `*.scss` (styles, no executable behavior), `*.spec.ts`, the e2e spec, `reviews/`. No renamed or deleted files. No scoped file is missing from the report.
- Provider/version, command, working directory, and test selection: Vitest v5.0.5 with v8 coverage (`@vitest/coverage-v8`) via `npm run coverage` in `web/`, full web unit suite (71 files, 1282 tests). Per-file counters were read from `coverage/web/coverage-final.json` (statements, branches, functions) and `coverage/web/coverage-summary.json` (lines).
- Run start/completion time and exit status: started after 2026-10-21T01:31:54Z, report written 2026-10-21T01:33:10Z, exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json`, sha256 `beded6f91ca68e88b4cd9d5c5476d82a5c969c89d30decfff142db123a8bd56c` (mtime 2026-10-21T01:33:10Z); `web/coverage/web/coverage-summary.json`.
- Evidence outputs are fresh and inputs stayed unchanged: Vitest rewrites `coverage/` on each run, and the mtime matches this run. The spec blob hashes were taken before the run, and nothing was edited afterwards.
- Final run after latest test additions and production fixes: yes (it includes fixer commit d99e65f2 and this run's tests).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `projects/area-store.ts` | 12/13 (92.31%) | 7/7 (100%) | 5/6 (83.33%) | 7/8 (87.5%) |
| `projects/markdown.ts` | 54/54 (100%) | 25/25 (100%) | 8/8 (100%) | 52/52 (100%) |
| `projects/project-detail-page.ts` | 134/136 (98.53%) | 57/66 (86.36%) | 39/39 (100%) | 118/118 (100%) |
| `projects/project-detail-page.html` | 261/261 (100%) | 34/34 (100%) | 23/23 (100%) | 151/151 (100%) |
| `projects/project-form.ts` | 53/53 (100%) | 57/58 (98.28%) | 7/7 (100%) | 41/41 (100%) |
| `projects/project-form.html` | 96/96 (100%) | 4/4 (100%) | 8/8 (100%) | 45/45 (100%) |
| `projects/project-groups.ts` | 23/23 (100%) | 14/14 (100%) | 13/13 (100%) | 19/19 (100%) |
| `projects/project-task-store.ts` | 22/22 (100%) | 13/13 (100%) | 5/5 (100%) | 14/14 (100%) |
| `projects/projects-page.ts` | 29/29 (100%) | 10/11 (90.91%) | 11/11 (100%) | 21/21 (100%) |
| `projects/projects-page.html` | 111/111 (100%) | 30/30 (100%) | 9/9 (100%) | 66/66 (100%) |
| `projects/projects.routes.ts` | 1/1 (100%) | N/A (0/0) | N/A (0/0) | 1/1 (100%) |
| **Aggregate (summed counters, all 11 scoped files, none missing)** | 796/799 (99.62%) | 251/262 (95.80%) | 128/129 (99.22%) | 535/536 (99.81%) |

Before this run's additions (fresh run at 2026-10-21T01:28:39Z, report sha256 `d9f393d0…3503`), the templates had uncovered handlers: the detail edit-form `cancelled` listener, the tasks "Loading…" branch and the list inline-edit `cancelled` listener. `run()`'s busy guard (`project-detail-page.ts:271`) and the `statusLabel` unknown-status fallback were also uncovered. The new tests cover all of them.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `area-store.ts:32-33` (`matches()`) | Change-event filter of the base `ListStore`; areas have no query, it always returns `true` | Justified gap: trivial. The base store calls it only for live change events, which these views do not emit in unit tests. |
| `project-detail-page.ts:136` (`makeNext` without project), `:232` (`saveDescription` guard: no project / not editing / already saving) | Defensive guards; the buttons that call them only render with a project, and Save is disabled while saving | Justified gap: not reachable through the UI |
| `project-detail-page.ts:63, 103` (`params.get('id') ?? ''`), `:79, 81` (`?? ''` description fallbacks), `:168, 192` (`find(id) ?? project`) | Fallbacks for a missing route param or a project removed mid-request | Justified gap: the route always has `:id`. A project vanishing between create and set-next-step is an edge case not named by the criteria. |
| `project-detail-page.ts:41`, `projects-page.ts:22`, `project-form.ts:36` (1 branch each) | Class-declaration-level branches emitted by the compiler (decorator/field initialisation), no source behavior | Not material |

## Unable to test

- AC4 Playwright `e2e/gtd-projects/projects.spec.ts` against the e2e stack: not run, at the parent worker's instruction (the parent runs it). `npm run e2e:typecheck` (part of `npm run check`) only type-checks it, and that is not evidence that it passes. Follow-up: the parent must run `npm run e2e -- e2e/gtd-projects/projects.spec.ts`.
- Real pointer drag-and-drop through the CDK: the unit tests emit the `CdkDropList.dropped` event directly. Real drag gestures are only exercised by a browser, and they are not named in the e2e criterion. No follow-up required beyond the e2e run.

## Summary

Tests added and all validation passed. Task-scoped coverage is sufficient (99.62% statements, 95.80% branches; remaining gaps justified above), and no production defects were found. The Playwright criterion (AC4) is left for the parent worker to run.
