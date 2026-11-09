# Test Report

Date: 2026-11-09

Task: web-gtd-shared-markdown - Web: share renderMarkdown and Markdown styles between project description and task notes

Scope: `git diff cc8fbe4c050d569c485540f77b260b455f2ba049..03c090ae62e171febf6aec2308d2ee7e623b1cea -- web`. Task commits: 4b3972142 (share renderMarkdown and .markdown styles via gtd/ui), 05f12f9f0 (test markdown re-export), dfc05f7c6 (fixer: comments and styleUrls formatting). The task's web code at HEAD is the same as at dfc05f7c6. Branch: main. Working tree was clean before and after the runs; this re-run changed no test files. This report replaces the earlier report, which measured revision 7bc5ae606 before the fixer commit.

Requirements:
- tasks/web-gtd-shared-markdown.md acceptance criteria
- docs/ARCHITECTURE.md "Coverage"
- Parent instructions (re-check tests on the final code, add tests only if needed)

## Test files changed

- None in this run. The task already contains `web/src/app/features/gtd/ui/markdown.spec.ts` (moved from gtd/projects in 4b3972142, export identity test added in 05f12f9f0). Snapshot sha256: 9f7e7f942adbd7d107a7a82c87add5478ecd0d38619ccb95495a452dfc144f7d.

## Tests added or updated

Count: 0

I re-checked the final code and existing tests cover it. The fixer commit changed only comments (`projects/markdown.ts`, `task-detail-notes.scss`) and the formatting of the `styleUrls` arrays, so no behavior changed. Coverage of the final code:
- `gtd/ui/markdown.ts` renderer: 20 tests in `ui/markdown.spec.ts` (block structure, inline markup, escaping/XSS, link scheme filtering, line endings, edge cases).
- `gtd/ui/index.ts` barrel and the `gtd/projects/markdown.ts` re-export shim: `renderMarkdown exports > is the same function from gtd/ui and from the gtd/projects re-export`.
- Project description consumer: `projects-pages.spec.ts` and `projects-states.spec.ts` assert on the `.markdown` innerHTML.
- Task notes consumer: `task-detail.spec.ts` asserts on `#td-notes-view h3` for `notesMd: '# Notes'`.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `renderMarkdown` lives in a shared location and both consumers use it; no import from `gtd/projects` into `gtd/task-detail` remains | `ui/markdown.spec.ts` (20 renderer tests and the export identity test); the project description render tests in `projects-pages.spec.ts` / `projects-states.spec.ts`; the task-detail notes render test. Static check: `grep -rn "projects" web/src/app/features/gtd/task-detail --include=*.ts \| grep import \| grep -v spec` finds nothing | `npm --prefix web run check` (includes `test:ci`), `npm --prefix web run coverage` | Pass |
| AC2: one shared `.markdown` stylesheet partial used by both; rendering looks unchanged | Not unit-testable: jsdom does not apply component SCSS. Checked statically instead. A sorted line comparison of the task diff for `project-description.scss`, `task-detail-notes.scss` and `ui/markdown.scss` shows that two identical `.markdown` rule sets were removed and one identical set was added to `ui/markdown.scss`; only the notes-only `.markdown a { cursor: pointer; }` stays in `task-detail-notes.scss`. Both components list `../ui/markdown.scss` in `styleUrls` before their own sheets, so the cascade order is the same as before. `ng build` (part of `npm run check`) compiles both components with the new partial | `npm --prefix web run check` (ng build) | Pass (build and static comparison). Visual comparison: Not run, because no browser session was available in this helper run |
| AC3: `cd web && npm run check` and `sh scripts/check.sh` pass | `cd web && npm run check` (api:check, lint, e2e typecheck, test:ci, ng build) | `npm --prefix web run check` | Pass for `npm run check`. `sh scripts/check.sh`: Not run, because of the helper's time budget. The parent runs it before `done` |

## Focused test execution

Command:

`npm --prefix web run check` (the Angular unit-test builder runs the whole suite; the task's specs are in it)

Result:

Pass. Exit 0, 194 test files passed, 4682 tests passed. Lint and the build also passed. The build printed the existing initial-bundle budget warning (692.49 kB against a 500 kB budget); the task did not cause it, because the markdown code goes into lazy chunks. Ran 2026-11-09T00:52:32Z to 00:58:52Z.

## Full test execution

Command:

`npm --prefix web run coverage` (`ng test --watch=false --coverage ...`)

Result:

Pass. Exit 0, 194 test files passed, 4682 tests passed. Ran 2026-11-09T00:58:57Z to 01:02:50Z.

## .taskcheck

Result:

Not run (helper time budget; `npm run check` above covers the web part). The parent runs `sh scripts/check.sh` before `done`. The previous release note says an earlier run failed only on an Android Compose test that looks flaky and is unrelated to this task.

## Bugs found

None.

## Coverage

Status: verified for the task's executable production files. The two re-export-only files contain no executable code and are listed under exclusions.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured with tooling. In the two consumers, the changed executable lines are an import and the `styleUrls` decorator metadata, which runs when the class is defined; both files report 100% line coverage. `ui/markdown.ts` is the renderer moved unchanged from `projects/markdown.ts`, plus comments, and has 100% coverage on every metric.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-gtd-shared-markdown; base cc8fbe4c050d569c485540f77b260b455f2ba049 (from the parent); measured HEAD 03c090ae62e171febf6aec2308d2ee7e623b1cea; task commits 4b3972142, 05f12f9f0, dfc05f7c6 (`git log cc8fbe4c0..HEAD -- web` shows exactly these); file scope from `git diff --stat cc8fbe4c0 HEAD -- web`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 03c090ae6 with a clean working tree; `ui/markdown.spec.ts` sha256 9f7e7f942adbd7d107a7a82c87add5478ecd0d38619ccb95495a452dfc144f7d.
- Exact included files: `web/src/app/features/gtd/ui/markdown.ts` (moved from `gtd/projects/markdown.ts`), `web/src/app/features/gtd/projects/project-detail-page.ts`, `web/src/app/features/gtd/task-detail/task-detail.ts`.
- Excluded files and reasons:
  - `web/src/app/features/gtd/ui/index.ts` and `web/src/app/features/gtd/projects/markdown.ts`: re-export statements only (plus a comment in the shim), with no executable code. v8 does not list them in `coverage-summary.json`. The export identity test exercises both.
  - `ui/markdown.scss`, `projects/project-description.scss`, `task-detail/task-detail-notes.scss`: stylesheets, which v8 does not instrument. `ng build` compiles them.
  - `ui/markdown.spec.ts`: a test file.
- Provider/version, command, working directory, and test selection: Vitest (`vitest` ^5.0.0) with `@vitest/coverage-v8` ^5.0.5, via the Angular unit-test builder; `npm --prefix web run coverage` from the repository root (so the working directory is `web/`); full suite.
- Run start/completion time and exit status: 2026-11-09T00:58:57Z to 01:02:50Z, exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 e6e4828c4bb73a3c6e67907831b6f32f7601ebbc13fd80ac53ad5ce43984b9da), `web/coverage/web/coverage-final.json` (sha256 bf4122978edadda0385d0c689e6135ae560f72acd82c2875cca1a6a048116556), `lcov.info`.
- Evidence outputs are fresh and inputs stayed unchanged: I deleted `web/coverage/web` right before the run, so every report came from this run. No source or test files changed before, during or after the run (clean working tree).
- Final run after latest test additions and production fixes: yes. It measures HEAD, which includes the fixer commit dfc05f7c6.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/ui/markdown.ts` | 54/54 (100%) | 25/25 (100%) | 8/8 (100%) | 52/52 (100%) |
| `web/src/app/features/gtd/projects/project-detail-page.ts` | 188/190 (98.94%) | 88/97 (90.72%) | 48/48 (100%) | 164/164 (100%) |
| `web/src/app/features/gtd/task-detail/task-detail.ts` | 303/307 (98.69%) | 202/215 (93.95%) | 64/64 (100%) | 248/248 (100%) |
| Aggregate (summed counters, these 3 files) | 545/551 (98.91%) | 315/337 (93.47%) | 120/120 (100%) | 464/464 (100%) |

The aggregate leaves out `gtd/ui/index.ts` and `gtd/projects/markdown.ts` (no executable code) and the SCSS files (not instrumented).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `projects/project-detail-page.ts`: 2 statements and 9 branches uncovered (locations not extracted in this run) | Existing component logic, unrelated to this task. The task changed only an import and `styleUrls` here | Left as is: outside this refactor's scope. The description Markdown path is covered |
| `task-detail/task-detail.ts`: 4 statements and 13 branches uncovered (locations not extracted in this run) | Existing component logic, unrelated to this task. The task changed only an import and `styleUrls` here | Left as is: outside this refactor's scope. Notes rendering is covered |

## Unable to test

- Whether the rendered result looks the same (AC2). jsdom does not apply component styles, and no browser or visual comparison was run. The static comparison and `ng build` above support the claim that it is unchanged. Optional follow-up: look at a project description and at task notes by hand.
- `sh scripts/check.sh` was not run here (time budget). The parent must run it for AC3.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. On HEAD 03c090ae6, `npm run check` and the coverage run both passed (194 files, 4682 tests), and the moved renderer is 100% covered. The parent still has to run `sh scripts/check.sh` for AC3.
