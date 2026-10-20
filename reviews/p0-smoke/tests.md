# Test Report

Date: 2026-10-20

Task: p0-smoke - Phase 0 demo script + README

Scope: `4a7b7d684dbffcaf760d8c22dd2cb8fe08b26b2a..ccd075cab841a2f19753f6405cc4e04b0c642cee` (task commit `ccd075ca`, branch `main`, base and commit supplied by the parent worker and matching `git log 4a7b7d68..HEAD`). Before the tests were added the working tree was clean. The only uncommitted changes are the two test files listed below.

Requirements:
- `tasks/p0-smoke.md` acceptance criteria
- SPEC.md §13.2 (Phase 0 demo), SPEC.md §4.7 (settings / theme)
- `docs/ARCHITECTURE.md` "Coverage", AGENTS.md
- Parent instructions: test the THEME_PREFERENCE fix (default token, settings-backed provider, shell menu)

## Test files changed

- `web/src/app/shell/shell.spec.ts`
- `web/src/app/features/settings/settings.routes.spec.ts`

## Tests added or updated

Count: 7 new tests. The `setup()` helper in `shell.spec.ts` now also takes optional extra providers, and its existing callers are unchanged.

- `shell.spec.ts` / Shell: `hands the theme chosen in the menu to THEME_PREFERENCE instead of setting it locally`
- `shell.spec.ts` / THEME_PREFERENCE without a provider: `applies the theme only in this browser through ThemeService`
- `settings.routes.spec.ts` / provideSettingsSync THEME_PREFERENCE:
  - `applies the theme at once and saves it as the setting with the loaded ETag`
  - `keeps the saved theme when the settings are loaded again after the next login`
  - `rolls the theme back and shows the error without an unhandled rejection when saving fails`
  - `keeps the chosen theme when the login load answers after the change was sent` (ordering: the login GET answers last with the old theme)
  - `saves the theme picked in the shell menu when the shell route provides the sync` (route-level wiring as in `app.routes.ts`. This is the regression for the defect: without the fix no PATCH is sent)

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `web/e2e/phase0/*.spec.ts` covers demo steps 1-5 and passes against the e2e stack | `e2e/phase0/demo.spec.ts` steps 1-5 (parent-authored), plus `e2e/support/smoke.spec.ts` | `cd web && npm run e2e` (podman-compose stack, Playwright chromium, projects `phase-demos` then `chromium`) | Pass: 7 passed (18.1s). The stack was healthy after 112 s |
| AC2: README.md content | none (documentation) | n/a | Not testable by automated tests (documentation review) |
| AC3: defect found is fixed: top-bar theme is saved as the setting and survives reload | The 7 unit tests above, plus e2e step 5 (`the dark theme persists across a reload`) | `cd web && npx ng test --watch=false --include src/app/features/settings/settings.routes.spec.ts --include src/app/shell/shell.spec.ts`, and `npm run e2e` | Pass (37/37 focused; e2e 7/7) |
| Default THEME_PREFERENCE applies locally via ThemeService.setMode | `applies the theme only in this browser through ThemeService`, plus the existing `switches the theme from the theme menu` and `marks the current theme in the theme menu` | focused command above | Pass |
| Settings-backed provider calls `SettingsService.update({theme})`, swallows the rejection, and rolls back | `applies the theme at once...`, `rolls the theme back...` | focused command above | Pass |
| Shell menu uses the token | `hands the theme chosen in the menu to THEME_PREFERENCE...`, `saves the theme picked in the shell menu...` | focused command above | Pass |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/features/settings/settings.routes.spec.ts --include src/app/shell/shell.spec.ts`

Result:

Pass (2 files, 37 tests)

## Full test execution

Command:

`cd web && npm run check` (api:check, lint, e2e:typecheck, test:ci, ng build)

Result:

Pass: 27 files, 436 tests; `All files pass linting.`; build succeeded. The build warns that the initial bundle exceeds its budget (505.20 kB against 500 kB). That is a warning, not an error. I did not determine whether this task caused it: `settings.providers.ts` is in the initial bundle and now imports `shell-tokens.ts`. The parent may want to compare with the base.

E2E: `cd web && npm run e2e` -> Pass, 7 passed (independent rerun, consistent with the parent's 7/7).

## .taskcheck

Result:

Pass on the second run (`sh scripts/check.sh` -> `== all checks passed`, exit 0).
The first run failed in the server suite (1454 tests, 1 failed):
`ClockConfigurationTests."the application clock is the system clock in UTC"`, with `2026-10-20T16:27:50.853991Z should be >= 2026-10-20T16:27:50.853991231Z`.
This is an intermittent pre-existing server test (from p0-db-conventions). It compares a microsecond-truncated clock with a nanosecond `Instant.now()`. This task changed no server code, so it is unrelated. The parent might want to record it as a separate flaky-test task.

## Bugs found

None in the task scope.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 16/16 executable added or modified lines covered (100%). Method: I intersected the lcov `DA` lines with the added lines of `git diff -U0 4a7b7d68..ccd075ca` for the four files. Changed-branch coverage was not measured separately.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: p0-smoke; base `4a7b7d684dbffcaf760d8c22dd2cb8fe08b26b2a`; HEAD `ccd075cab841a2f19753f6405cc4e04b0c642cee`; the single task commit `ccd075ca`, supplied by the parent and confirmed with `git log`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `ccd075ca` plus uncommitted test files, sha256 `shell.spec.ts` 647229e5029952506d56714e038968880abe688e736748af616c00b5d52ee578 and `settings.routes.spec.ts` 7465a753ee72583d3fd2c8ee40ed7647a9d9162f44322e5eba8b0733d774aef0.
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/settings/settings.providers.ts`, `web/src/app/shell/shell-tokens.ts`, `web/src/app/shell/shell.ts`, `web/src/app/shell/shell.html`. Excluded: `web/e2e/phase0/demo.spec.ts`, `web/e2e/README.md`, `README.md` (tests and docs), and `web/playwright.config.ts` (test configuration, not unit-instrumented; exercised by the e2e run). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8` 5.0.5; `npm run coverage` in `web/`; full web unit suite.
- Run start/completion time and exit status: started 2026-10-20 16:20:45 UTC, report written 16:21 UTC; exit 0 (27 files, 436 tests passed).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` (sha256 21e81750da3c6554c5fdf9b4289096446aff35ed0d6a96614aed1159545501dc), `web/coverage/web/coverage-summary.json` (sha256 78d0cac87d2a899436e102aaa9f997042b8aee257c1044a61688eb2dcb7af12c), `web/coverage/web/lcov.info`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The reports were rewritten by this run (timestamps 18:21 local), and the test files were not edited after the run.
- Final run after latest test additions and production fixes: yes. No production changes were made after `ccd075ca`.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/settings/settings.providers.ts` | 14/14 (100%) | 2/2 (100%) | 8/8 (100%) | 10/10 (100%) |
| `web/src/app/shell/shell-tokens.ts` | 7/7 (100%) | N/A (0/0) | 3/3 (100%) | 6/6 (100%) |
| `web/src/app/shell/shell.ts` | 47/47 (100%) | 22/22 (100%) | 14/14 (100%) | 38/38 (100%) |
| `web/src/app/shell/shell.html` | 89/90 (98.89%) | 6/6 (100%) | 6/7 (85.71%) | 64/64 (100%) |
| Aggregate (4 files, none unmeasured) | 157/158 (99.37%) | 30/30 (100%) | 31/32 (96.88%) | 118/118 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/src/app/shell/shell.html:9` (`Shell_Template_a_click_3_listener`, cols 36-57) | A pre-existing click listener on line 9, not changed by this task | Left uncovered: outside the task diff. The changed line 77 (`(click)="setTheme(mode)"`) is covered |

## Unable to test

- README.md content (AC2): this is documentation, so no automated test applies. It needs a human or reviewer read-through.

## Summary

Tests added and all validation passed. Task-scoped coverage is sufficient and no production defects were found. Separately, an intermittent pre-existing server test (`ClockConfigurationTests`) failed once in `.taskcheck` and passed on the rerun.
