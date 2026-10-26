# Test Report

Date: 2026-10-26

Task: gtd-delete-reassign - Server-side reassignment when deleting a context or area

Scope: task commits `01da15b13629869cc93cdd0ba96c94bfc8e9ab57..4d2582b4785c9e5feb8580e06fa0d96ee335164b` on `main` (6 task commits: e803354d2, 415528dee, ec174c9aa, b909c8570, d1e0a22a8, 4d2582b47; the heartbeat commit ec7f5b466 touching `tasks/mail-connection-errors.md` is in the range but not task-owned and excluded). Working tree clean at measurement time (only Git-ignored `.tmp/` scratch scripts).

Requirements:
- `tasks/gtd-delete-reassign.md` acceptance criteria 1 and 2, plus the recorded assumptions (self target is a 400 like an unknown id; the dialog count still counts visible items only)
- SPEC.md §4.2 conventions (Problem Details, ETag/If-Match), AGENTS.md project notes
- `reviews/gtd-delete-reassign/review.md` and `fixes.md` (CR-1: reload and explain when the move-to target no longer exists)

## Test files changed

None by the test-writer in this run. The task's existing test changes (written during implementation and fixing) were inspected:

- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/AreaContextWebTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/gtd/AreaContextDeleteRollbackTests.kt`
- `web/src/app/features/settings/gtd/delete-dialog.spec.ts`
- `web/src/app/features/settings/gtd/gtd-settings.service.spec.ts`
- `web/src/app/features/settings/gtd/gtd-settings.spec.ts`

## Tests added or updated

Count: 0

No real gap was found: every acceptance criterion and every changed branch is already exercised (see Requirement coverage and Coverage). Adding tests would only duplicate existing ones.

## Requirement coverage

Commands were run by the parent worker on HEAD 4d2582b47 (the test-writer sub-agent cannot run Gradle here); results as reported by the parent and consistent with the fresh JaCoCo/Vitest reports read by the test-writer.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: DELETE context with `replaceWith` reassigns all tasks incl. tickler and Trash (and done tasks); a task already having the replacement keeps it once; versions bumped; events only for non-Trash rows | AreaContextWebTests: `deleting a context with replaceWith gives all its tasks the other context, also in the tickler and the Trash`, `deleting a context with replaceWith also reaches done tasks and versions trashed tasks without announcing them`, `deleting a context without tasks with replaceWith leaves the replacement as it was` | `server/gradlew -p server test --tests 'ch.sonensei.cockpit.gtd.*' jacocoTestReport ktlintCheck` | Pass |
| AC1: DELETE area with `moveTo` moves all projects incl. Trash and completed ones | AreaContextWebTests: `deleting an area with moveTo moves all its projects, also those in the Trash`, `deleting an area with moveTo also moves completed projects and versions trashed ones without announcing them` | same | Pass |
| AC1: unknown (or own, per assumption) target -> 400, nothing changed, nothing announced | AreaContextWebTests: `deleting a context with an unknown or its own replaceWith is a 400 and changes nothing`, `deleting an area with an unknown or its own moveTo is a 400 and changes nothing`, `a 400 for the replacement context announces nothing` | same | Pass |
| AC1: If-Match (428/412) checked before the target, nothing reassigned | AreaContextWebTests: `deleting a context with replaceWith checks If-Match before the target and reassigns nothing on 428 or 412`, `deleting an area with moveTo checks If-Match before the target and moves nothing on 428 or 412` | same | Pass |
| AC1: one transaction (failure after reassignment rolls it back) | AreaContextDeleteRollbackTests: `a failure after replacing the context rolls back the replacement in every task`, `a failure after moving the projects rolls back the move` | same | Pass |
| AC1: delete without a target still clears context/area (regression) | AreaContextWebTests: `deleting an area takes its projects out of it, with a new version and a change event`, `deleting a context removes it from its tasks, with a new version and a change event` | same | Pass |
| AC2: `api/openapi.yaml` regenerated and generated client matches | `api:check` step of the web check | `npm --prefix web run check` | Pass |
| AC2: web service sends `replaceWith`/`moveTo` (omitted when null) and patches no tasks/projects | gtd-settings.service.spec: `deletes a context without a replacement and without touching its tasks`, `lets the server give the tasks the replacement context in the delete itself`, `deletes an area without a target and without touching its projects`, `lets the server move the projects to the target area in the delete itself`, `rejects with the 400 problem when the server refuses the target area, without a toast` | `npm --prefix web run check`; `npm --prefix web run coverage` | Pass |
| AC2: settings component uses the parameter; missing target reloads and explains (CR-1); other 400 keeps the list | gtd-settings.spec: delete with replaceWith/moveTo cases (around lines 260, 899, 933), missing-target context/area cases (around lines 960-1002), `keeps the list for a 400 whose field error is not on the target` | same | Pass |
| AC2: tickler/Trash hint removed from the delete dialog | delete-dialog.spec: `no longer warns about the tickler or the Trash when moving (the server moves those too)`, `no longer warns about projects in the Trash when moving` | same | Pass |
| AC2: `cd web && npm run check` passes | whole web check | `npm --prefix web run check` | Pass (an earlier run had 56 Vitest 5000 ms timeouts in unrelated specs under load average about 50; reruns passed) |

## Focused test execution

Command:

`server/gradlew -p server test --tests 'ch.sonensei.cockpit.gtd.*' jacocoTestReport ktlintCheck` (run by the parent worker)

Result:

Pass - BUILD SUCCESSFUL; 32 gtd test classes, 419 tests, 0 failures; AreaContextWebTests 20 tests / 0 failures, AreaContextDeleteRollbackTests 2 / 0; ktlint clean.

## Full test execution

Command:

`npm --prefix web run check` and `npm --prefix web run coverage` (run by the parent worker)

Result:

Pass - web check passed; coverage run 174 files / 3628 tests passed. The full server suite (`cd server && ./gradlew check`) and Android were not run by the test-writer; the parent runs them via `.taskcheck`.

## .taskcheck

Result:

Not run by the test-writer (no Gradle access in the sub-agent; the parent worker runs `sh scripts/check.sh` before `done`).

## Bugs found

None.

## Coverage

Status: verified (server whole-file and changed-line from JaCoCo; web whole-file from Vitest/Istanbul).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: server measured by intersecting JaCoCo `line` entries (ci/mi/cb/mb) with added lines of `git diff -U0 01da15b13..4d2582b47` (script `.tmp/changedlines.py`, Git-ignored): 8/10 changed executable lines covered, 14/14 branches on changed lines covered. Web changed-line coverage was not measured numerically; manual inspection of the Istanbul branch map shows no missed branch on any changed line of `gtd-settings.ts` (changed lines 279-347) or `delete-dialog.ts`.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `gtd-delete-reassign`; base 01da15b13629869cc93cdd0ba96c94bfc8e9ab57 (given by the parent); HEAD 4d2582b4785c9e5feb8580e06fa0d96ee335164b; task commits from `git log 01da15b13..HEAD`, cross-checked with the task's `touches` and acceptance criteria.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 4d2582b47 with a clean working tree; no uncommitted inputs.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/AreaService.kt`, `.../gtd/domain/ContextService.kt`, `.../gtd/api/AreaContextControllers.kt`, `web/src/app/features/settings/gtd/delete-dialog.ts`, `.../gtd-settings.ts`, `.../gtd-settings.service.ts`, `web/src/app/api/generated/fn/projects/delete-area.ts`, `.../delete-context.ts` (generated). `gtd-settings.html` is reported for context although not changed. Excluded: `api/openapi.yaml` (generated spec, no executable code), `reviews/`, `tasks/`, test files. No renames or deletions of production files.
- Provider/version, command, working directory, and test selection: server JaCoCo via `server/gradlew -p server test --tests 'ch.sonensei.cockpit.gtd.*' jacocoTestReport` from the repo root (gtd test classes only); web Vitest v8/Istanbul coverage via `npm --prefix web run coverage` from the repo root (full web suite). Provider versions as pinned in the project build files.
- Run start/completion time and exit status: both runs by the parent on 2026-10-26 after HEAD's commit time (04:36:20+01:00), exit status success; `jacocoTestReport.xml` written 2026-10-26 04:37, `web/coverage/web/coverage-final.json` written 2026-10-26 04:42-04:43.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 95bb663b611786cadd549cc1796cbb3d2f3b7838ae63dea6fa8219068bd2bcd3; `web/coverage/web/coverage-final.json` sha256 6c017789e0b91662828edc39c1c6c6e84a1d894b679324c8496198dc6566c270 (with `coverage-summary.json` in the same directory).
- Evidence outputs are fresh and inputs stayed unchanged: reports are newer than the HEAD commit, the working tree was clean, and the test-writer changed no source or test file.
- Final run after latest test additions and production fixes: yes (no tests were added afterwards; CR-1 fix is in b909c8570, before HEAD).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Server "Statements" are JaCoCo instructions.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/.../gtd/domain/AreaService.kt` | 386/397 (97.2%) | 37/38 (97.4%) | 10/11 (90.9%) | 59/61 (96.7%) |
| `server/.../gtd/domain/ContextService.kt` | 417/428 (97.4%) | 41/42 (97.6%) | 11/12 (91.7%) | 61/63 (96.8%) |
| `server/.../gtd/api/AreaContextControllers.kt` | 406/408 (99.5%) | N/A (0/0) | 23/24 (95.8%) | 49/49 (100%) |
| `web/.../settings/gtd/delete-dialog.ts` | 85/85 (100%) | 29/30 (96.7%) | 5/5 (100%) | 55/55 (100%) |
| `web/.../settings/gtd/gtd-settings.ts` | 159/159 (100%) | 99/106 (93.4%) | 60/60 (100%) | 130/130 (100%) |
| `web/.../settings/gtd/gtd-settings.service.ts` | 32/32 (100%) | 13/13 (100%) | 14/14 (100%) | 29/29 (100%) |
| `web/.../api/generated/fn/projects/delete-area.ts` (generated) | 9/9 (100%) | 1/2 (50%) | 3/3 (100%) | 9/9 (100%) |
| `web/.../api/generated/fn/projects/delete-context.ts` (generated) | 9/9 (100%) | 1/2 (50%) | 3/3 (100%) | 9/9 (100%) |

Aggregates (summed counters): server 3 files lines 169/173 (97.7%), branches 78/80 (97.5%); web 5 files lines 232/232 (100%), branches 143/153 (93.5%). No scoped file was unmeasured; `api/openapi.yaml` is excluded as non-executable.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `AreaService.kt:87,89` and `ContextService.kt:104,106` | Kotlin's synthetic `delete$default` for the new default argument `moveTo`/`replaceWith = null`; no caller uses the default (the controller always passes the parameter) | Justified gap: no behavior; the no-target path is covered via an explicit null from the controller |
| `AreaService.kt:71`, `ContextService.kt:88` (one branch each) | `order()`: position unchanged case; pre-existing code, not changed by this task | Out of task scope |
| `AreaContextControllers.kt:129` (companion object init) | pre-existing constant holder | Not material |
| `delete-dialog.ts:144` | Angular-compiled class branch, outside changed lines | Not material |
| `gtd-settings.ts:47,101,114,236,254,303,310` | pre-existing `??`/`||` fallbacks and compiled class branch, none on changed lines 279-347 | Out of task scope |
| `delete-area.ts:27`, `delete-context.ts:27` | generated client: response without body branch | Generated code, not hand-written; not tested |

## Unable to test

- The test-writer could not run Gradle, the full server suite, or `.taskcheck` itself; results above are the parent's runs on the same HEAD. The parent must run `sh scripts/check.sh` before `done`.
- Counting hidden (tickler/Trash) items in the delete dialog is out of scope per the recorded assumption; not tested.

## Summary

No additional tests were necessary for the reviewed scope. Tests and task-scoped coverage are sufficient; no production defects found.
