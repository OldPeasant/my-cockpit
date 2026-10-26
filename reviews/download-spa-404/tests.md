# Test Report

Date: 2026-10-26

Task: download-spa-404 - Unknown /download/* paths return 404, not the SPA index.html

Scope: `git diff 05ac0db06be2a6c87cae50660041d3c8e276df23..bb74d4b7ee2233add5e977708440441ee4e66827` (single task commit bb74d4b7ee2233add5e977708440441ee4e66827 on branch main; working tree clean, no uncommitted inputs).

Requirements:
- `tasks/download-spa-404.md` acceptance criteria
- `AGENTS.md` (conventions, quality gate), `docs/ARCHITECTURE.md` "Coverage"
- Parent instructions (scope, base and task commit, results the parent ran)

## Test files changed

None by the test-writer. The task commit itself changed:

- `server/src/test/kotlin/ch/sonensei/cockpit/core/spa/SpaFallbackTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/spa/SpaFallbackResourceResolverTests.kt`

## Tests added or updated

Count: 0 by the test-writer (the task commit added 1 web test and extended 2 existing tests; reviewed below and judged sufficient).

Review of the task's tests:

- `SpaFallbackTests.unknown download path is 404, not index html` - full `@SpringBootTest` + MockMvc, logged in (`@WithCockpitUser`), fixture web build containing `index.html`, so before the fix the resolver would answer 200 with HTML. Checks `/download/ios`, `/download/` and `/download`.
- `SpaFallbackTests.client route detection` - adds `download/ios` -> false and `downloads` -> true (the prefix matches a whole first segment, not a string prefix; guards against an over-broad `startsWith` fix).
- `SpaFallbackResourceResolverTests.server paths and missing files resolve to nothing` - adds `download/ios` resolves to null at resolver level.
- Regression proof (run by the parent): with the fix reverted, these 3 tests fail.

Non-regression of the real download endpoints (the controller must still win over the catch-all resource handler) is already covered by existing full-context tests in `server/src/test/kotlin/ch/sonensei/cockpit/core/download/`: `AndroidDownloadWebTests` (`serves the APK with its version to a logged-in user`, `reports the version as JSON`, `answers 404 problem details when no APK is deployed`, `needs a login`) and `AndroidDownloadEdgeCaseWebTests` (HEAD, 304, ranges, 403 for enrolling sessions). These run in the same `./gradlew test` / `.taskcheck` suite. The change cannot affect them: controller handler mappings take precedence over the `/**` resource handler, and the resolver is only consulted for paths no controller handles.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `download` is in `SERVER_PREFIXES` (KDoc updated) | `client route detection`; `server paths and missing files resolve to nothing`; KDoc checked by reading `SpaWebConfiguration.kt:16-17` | `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.spa.*'` (run by parent) | Pass |
| AC1: unknown `/download/...` path returns 404 for a logged-in user; web test | `SpaFallbackTests.unknown download path is 404, not index html` | same (run by parent) | Pass; fails with the fix reverted (parent) |
| AC2: `./gradlew test --tests 'ch.sonensei.cockpit.core.spa.*'` passes | SpaFallbackTests (15), SpaFallbackResourceResolverTests (4) | same (run by parent) | Pass, 19 tests, 0 failures |
| Implicit: `/download/android` still served by the controller | `AndroidDownloadWebTests`, `AndroidDownloadEdgeCaseWebTests` (existing) | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.download.*'` | Not run by test-writer: Gradle cannot run in the helper sandbox; parent should run it or rely on `.taskcheck` |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.spa.*'`

Result:

Pass (run by the parent worker, reported as BUILD SUCCESSFUL, 19 tests, 0 failures). The test-writer could not run Gradle in its sandbox.

## Full test execution

Command:

`sh scripts/check.sh` (`.taskcheck`)

Result:

Not run by the test-writer (Gradle unavailable in the helper sandbox). The parent runs it before `done`.

## .taskcheck

Result:

Not run (see above; parent responsibility).

## Bugs found

None.

## Coverage

Status: not measured with JaCoCo for this task (no per-task run was performed; the test-writer cannot run Gradle). Reasoned coverage below, not a tool measurement.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: download-spa-404; base 05ac0db06be2a6c87cae50660041d3c8e276df23; HEAD bb74d4b7ee2233add5e977708440441ee4e66827; task-owned commit bb74d4b (from parent, confirmed by `git diff --stat`).
- Source/test snapshot identifier (including uncommitted inputs): commit bb74d4b7ee2233add5e977708440441ee4e66827, clean working tree.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/core/spa/SpaWebConfiguration.kt`; no renames or deletions; test files excluded as non-production.
- Provider/version, command, working directory, and test selection: none (JaCoCo not run).
- Run start/completion time and exit status: N/A.
- Generated report paths and report hashes/run identifier: N/A.
- Evidence outputs are fresh and inputs stayed unchanged: N/A.
- Final run after latest test additions and production fixes: no; no coverage run exists.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/spa/SpaWebConfiguration.kt` | N/A (not measured) | N/A (not measured) | N/A (not measured) | N/A (not measured) |

Reasoned assessment (not a measurement): the production change is one list literal (`SERVER_PREFIXES`) plus KDoc. Every branch of `isClientRoute` (server prefix -> false; extension in last segment -> false; otherwise true) and of `getResource` (existing file; fallback to index; null) is exercised by `SpaFallbackTests` and `SpaFallbackResourceResolverTests`, including the new `download` entry.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| None identified by inspection | - | No tool measurement; if the gate needs numbers, run `cd server && ./gradlew test jacocoTestReport --tests 'ch.sonensei.cockpit.core.spa.*'` per ARCHITECTURE.md "Coverage" |

## Unable to test

- Running any Gradle command (focused tests, download tests, JaCoCo, `.taskcheck`): the helper sandbox cannot run Gradle. Results above come from the parent. Follow-up: parent runs `.taskcheck` (covers the `core.download` tests) before `done`.
- Minor, judged non-material: a deeper unknown path under the controller prefix (e.g. `/download/android/nope`) and HEAD on an unknown `/download/...` path are not tested explicitly. They take the same resolver path (first segment `download` -> no fallback) that the existing tests already cover, so no test was added.

## Summary

No additional tests were necessary for the reviewed scope. The task's tests cover both criteria and fail when the fix is reverted; the controller's `/download/android` behavior is covered by the existing `core.download` web tests. Task-scoped JaCoCo coverage was not measured; the reasoned assessment is above.
