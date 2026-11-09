# Test Report

Date: 2026-11-09

Task: web-palette-type-hint - Web: make the Ctrl+K type: filter discoverable (hint or chips in the palette)

Scope: `git diff e2321d9485b4b97a38298f3e61d22a8899ca1e36 2cdfe908812bcc4dde130de83f8cabc67915c9df` (one task commit on `main`, HEAD = 2cdfe908812bcc4dde130de83f8cabc67915c9df). The only uncommitted change is the test added by this report (`web/src/app/shell/command-palette.spec.ts`).

Requirements:
- `tasks/web-palette-type-hint.md` acceptance criteria
- `docs/ARCHITECTURE.md` ("Coverage", i18n conventions)
- `AGENTS.md`

## Test files changed

- `web/src/app/shell/command-palette.spec.ts`

## Tests added or updated

Count: 1 added (`shows the type: hint again after the filter is removed, also with results listed`). The task commit already added `hints at the type: filter until the query uses it`.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: the palette shows how to filter by type (a hint line) | `hints at the type: filter until the query uses it` (hint text, case-insensitive `Type:`, `type:` after another word, `subtype:` does not count); `shows the type: hint again after the filter is removed, also with results listed` (leading whitespace, the hint comes back on an empty query, the hint shows next to results) | `cd web && npx ng test --watch=false --include src/app/shell/command-palette.spec.ts` | Pass (9/9) |
| AC2: new UI strings in `web/src/locale` (EN + DE) | No unit test. I checked the diff by hand: `palette.typeHint` is in `messages.xlf` and has a German `<target>` in `messages.de.xlf` | Inspection only. I did not run `npm run build:de` | Not run: `build:de` (fails on a missing translation) was not run in this session, so the parent should run it |
| AC3: unit tests cover it, `npm run check` passes | All of the above | `cd web && npm run check` | Pass (api:check, lint, e2e:typecheck and test:ci passed, and `ng build` completed. The initial-bundle budget warning was already there and is not an error) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/shell/command-palette.spec.ts`

Result:

Pass (1 file, 9 tests)

## Full test execution

Command:

`cd web && npm run check` (also `npm run coverage`: 4632 tests passed)

Result:

Pass

## .taskcheck

Result:

Not run: `sh scripts/check.sh` was not run in this session because time was short. The web part (`npm run check`) passed. The task changes nothing in server/ or android/. The parent runs `.taskcheck` before `done`.

## Bugs found

None.

## Coverage

Status: verified (web part, whole-file coverage).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured with a tool. By inspection, the one changed TS line (`showTypeHint` computed, line 48) and the new `@if` block in the template are executed. Both branches of the `@if` are covered (the HTML file has 4/4 branches covered).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-palette-type-hint, base e2321d9485b4b97a38298f3e61d22a8899ca1e36, HEAD 2cdfe908812bcc4dde130de83f8cabc67915c9df. The parent supplied the task commit 2cdfe9088, and the diff matches the task's `touches` (web/src/app/shell) plus the locale files.
- Source/test snapshot identifier (including uncommitted inputs): sha256 command-palette.html 6a50e85e…, .scss 5c959782…, .spec.ts ba99c01d… (includes the new test), .ts d87191dc…. These were identical before and after the coverage run.
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/shell/command-palette.ts` and `command-palette.html`. Excluded `command-palette.scss` (stylesheet, no executable code), `messages.xlf` and `messages.de.xlf` (translation data, not instrumented), and `command-palette.spec.ts` (test).
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + @vitest/coverage-v8 5.0.5, `npm run coverage` in `web/`, full web suite.
- Run start/completion time and exit status: started 2026-11-09 01:05:36 +01:00, report written 01:10:33 +01:00. 4632 tests passed and the coverage summary was printed (success).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` (sha256 0aedb2df89d97a3974044f1d1723486126130d2094c50c10780c613f7dd638f3) and `web/coverage/web/coverage-summary.json`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report mtime falls inside this run, and the snapshot hashes were unchanged after it.
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/shell/command-palette.ts` | 55/55 (100%) | 26/27 (96.29%) | 20/20 (100%) | 45/45 (100%) |
| `web/src/app/shell/command-palette.html` | 41/43 (95.34%) | 4/4 (100%) | 3/4 (75%) | 28/28 (100%) |
| Aggregate (both files) | 96/98 (97.96%) | 30/31 (96.77%) | 23/24 (95.83%) | 73/73 (100%) |

The aggregate leaves out the .scss and .xlf files (not executable). No scoped file is missing from the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `command-palette.html:37` (2 statements, 1 function: `(click)="choose(row.item)"`) | Choosing a row with the mouse. This code existed before the task and is not part of it | Left uncovered: outside the task scope (the keyboard path `choose` is covered) |
| `command-palette.ts:22` branch #0 (class declaration) | Decorator/compiled-class branch from v8, not behavior | Left uncovered: a tool artifact that existed before the task |

## Unable to test

- AC2 German build: `npm run build:de` was not run in this session. The translation unit is present (checked by inspection). Follow-up: the parent should run `cd web && npm run build:de` if it wants executed evidence for AC2.
- Visual styling of the hint (`.type-hint` scss) is not unit-testable here. It does not affect behavior.

## Summary

Tests added and all validation passed. No production defects were found. Task-scoped coverage of the changed behavior is complete. `.taskcheck` and `build:de` are left to the parent.
