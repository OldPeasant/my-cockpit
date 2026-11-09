# Test Report

Date: 2026-11-09

Task: web-gtd-shared-markdown - Web: share renderMarkdown and Markdown styles between project description and task notes

Scope: `git diff 9fef9ab231227319cfa1125e69041983ed1038d9..7bc5ae6069984fb0244a5bd1c449b370f8a64c68 -- web/` (one task commit, "web: share renderMarkdown and .markdown styles via gtd/ui"). The parent named the task commit as 85a281db3, but HEAD resolves to 7bc5ae606 with that subject; the measured revision is 7bc5ae606. Branch: main. The only uncommitted change at measurement time is the spec edit listed below.

Requirements:
- tasks/web-gtd-shared-markdown.md acceptance criteria
- docs/ARCHITECTURE.md "Coverage"
- Parent instructions (pure refactor; add tests only where useful)

## Test files changed

- `web/src/app/features/gtd/ui/markdown.spec.ts` (moved by the task from gtd/projects; this run adds one test to it)

## Tests added or updated

Count: 1

- `renderMarkdown exports > is the same function from gtd/ui and from the gtd/projects re-export`: checks that the `gtd/ui` barrel (`index.ts`) and the `gtd/projects/markdown.ts` shim (still imported by bookmarks, files and contacts) both export the same `renderMarkdown` as `gtd/ui/markdown.ts`.

Existing tests already cover both consumers, so no new component tests were added:
- project description renders Markdown: `projects-pages.spec.ts` (line 299) and `projects-states.spec.ts` (lines 628 and 660) check `.markdown` innerHTML;
- task notes render Markdown: `task-detail.spec.ts` (around line 170-180) checks `#td-notes-view h3` for `notesMd: '# Notes'`.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `renderMarkdown` lives in a shared location and both consumers use it; no import from `gtd/projects` into `gtd/task-detail` | `ui/markdown.spec.ts` (all 20 renderer tests plus the new export identity test); task-detail notes render test; project description render tests in `projects-pages.spec.ts` / `projects-states.spec.ts`. The task diff removes the `../projects/markdown` import from `task-detail.ts` | `cd web && npm run coverage` (full Vitest suite) | Pass |
| AC2: one shared `.markdown` stylesheet used by both, rendering looks unchanged | Not unit-testable here: jsdom does not apply component SCSS. Checked by reading the diff: `ui/markdown.scss` holds the rules that were removed from both files unchanged; `task-detail-notes.scss` keeps only `.markdown a { cursor: pointer; }`, which was its one difference before; both components list `../ui/markdown.scss` in `styleUrls` before their own sheets. The production build compiles the SCSS (it is part of `npm run check`, which I did not run) | n/a | Not testable (unit); no visual/browser comparison was run |
| AC3: `cd web && npm run check` and `sh scripts/check.sh` pass | Only the unit-test part ran: full Vitest suite, plus prettier and eslint on the changed spec | `cd web && npm run coverage`; `npx prettier --check` and `npx eslint` on the spec | Partial: unit tests passed (194 files, 4631 tests) and lint is clean on the spec. Not run: `npm run check` (api:check, full lint, e2e typecheck, `ng build`) and `scripts/check.sh`, because of the time budget. The parent must run them |

## Focused test execution

Command:

`cd web && npx prettier --check src/app/features/gtd/ui/markdown.spec.ts` and `npx eslint src/app/features/gtd/ui/markdown.spec.ts`

Result:

Pass. I did not run the spec on its own; it ran inside the full suite below.

## Full test execution

Command:

`cd web && npm run coverage` (`ng test --watch=false --coverage ...`, Vitest 5.0.5, v8 provider)

Result:

Pass: 194 test files passed, 4631 tests passed, exit 0. Run started 2026-11-09 01:41:30 local time (build finished at 00:41:29Z) and took 290.5 s.

## .taskcheck

Result:

Not run (time budget). The parent runs `sh scripts/check.sh` before `done`.

## Bugs found

None.

## Coverage

Status: verified for the task's executable files. The two pure re-export files have no executable code (see the exclusions below).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured. The executable lines this task changed are the import lines in `project-detail-page.ts` and `task-detail.ts`, plus `ui/markdown.ts`, which is a byte-for-byte move of the old file and is 100% covered as a whole.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-gtd-shared-markdown; base 9fef9ab231227319cfa1125e69041983ed1038d9 (from the parent); HEAD 7bc5ae6069984fb0244a5bd1c449b370f8a64c68; one task commit; file scope from `git diff --name-only base..HEAD -- web/src`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 7bc5ae606 plus uncommitted `web/src/app/features/gtd/ui/markdown.spec.ts`, sha256 9f7e7f942adbd7d107a7a82c87add5478ecd0d38619ccb95495a452dfc144f7d.
- Exact included files: `web/src/app/features/gtd/ui/markdown.ts` (moved from `gtd/projects/markdown.ts`), `web/src/app/features/gtd/projects/project-detail-page.ts`, `web/src/app/features/gtd/task-detail/task-detail.ts`.
- Excluded files and reasons:
  - `web/src/app/features/gtd/ui/index.ts` and `web/src/app/features/gtd/projects/markdown.ts`: re-export statements only. They have no executable code, so v8 does not list them in the report. The new export identity test exercises both.
  - `*.scss` files (`ui/markdown.scss`, `projects/project-description.scss`, `task-detail/task-detail-notes.scss`): stylesheets, not instrumented.
  - `ui/markdown.spec.ts`: test file.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8`; `npm run coverage`; working directory `web/`; full suite.
- Run start/completion time and exit status: started 2026-11-09 01:41:30 local, about 290 s, exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 c33b402f9911c1b47aa773c3d582391372729ad194df0d86f113e5c6ad0307b3), plus `coverage-final.json` and `lcov.info`.
- Evidence outputs are fresh and inputs stayed unchanged: Vitest rewrote `coverage/` during this run. The spec was edited before the run and not changed afterwards. No production files changed.
- Final run after latest test additions and production fixes: yes. This run includes the new test, and no production fixes were made.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/ui/markdown.ts` | 54/54 (100%) | 25/25 (100%) | 8/8 (100%) | 52/52 (100%) |
| `web/src/app/features/gtd/projects/project-detail-page.ts` | 188/190 (98.94%) | 88/97 (90.72%) | 48/48 (100%) | 164/164 (100%) |
| `web/src/app/features/gtd/task-detail/task-detail.ts` | 303/307 (98.69%) | 202/215 (93.95%) | 64/64 (100%) | 248/248 (100%) |
| Aggregate (summed counters, these 3 files) | 545/551 (98.91%) | 315/337 (93.47%) | 120/120 (100%) | 464/464 (100%) |

The aggregate leaves out `gtd/ui/index.ts` and `gtd/projects/markdown.ts`, which have no executable code.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `projects/project-detail-page.ts`: 2 statements and 9 branches uncovered | Existing component logic, unrelated to this task. The task only changed an import and `styleUrls` here | Left as is: outside this refactor's scope. The markdown rendering path is covered |
| `task-detail/task-detail.ts`: 4 statements and 13 branches uncovered | Existing component logic, unrelated to this task. The task only changed an import and `styleUrls` here | Left as is: outside this refactor's scope. Notes rendering is covered |

## Unable to test

- Whether the rendered result looks the same (AC2). jsdom does not apply component styles, and no visual or browser comparison was run. Diff review shows the shared rules are identical to the removed copies, and the task-notes-only `cursor: pointer` rule is kept. Follow-up is optional, for example a manual look at a project description and at task notes.
- `npm run check` (including `ng build`, which compiles the new SCSS path) and `scripts/check.sh` were not run here. The parent must run them for AC3.

## Summary

Tests added and the full unit suite passed (194 files, 4631 tests). Coverage of the task's executable files is complete for the moved renderer, and no production defects were found. The rest of AC3 (`npm run check` and `scripts/check.sh`) still needs to be run by the parent.
