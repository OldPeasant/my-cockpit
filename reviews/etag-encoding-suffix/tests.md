# Test Report

Date: 2026-11-25

Task: etag-encoding-suffix - Accept proxy content-coding suffix in If-Match (Caddy gzip breaks saves)

Scope: `git diff e4c488dd97eeacfd3dbfc3e34f5aa134b15efd5d..2a688b95936fcc14e69cedf12b5dcec6b996b485` (one task commit 2a688b959 on main, base e4c488dd9 = claim commit), plus the uncommitted test additions listed below.

Requirements:
- `tasks/etag-encoding-suffix.md` acceptance criteria
- SPEC.md §4.2 (optimistic locking via ETag/If-Match)
- docs/ARCHITECTURE.md "Coverage"
- Parent instructions (Caddy end-to-end criterion is done by the parent, not here)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/web/ETagsTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/web/ApiConventionsWebTests.kt`

## Tests added or updated

Count: 6 (5 added, 1 strengthened)

ETagsTests (added):
- `a suffixed tag matches at version zero, a large version, and among weak or stale tags` - version 0, `Long.MAX_VALUE` with `-br`, a list mixing `W/"2"`, a stale suffixed tag and the current `-zstd` tag, a list without spaces (`"2-br","3"`).
- `a bare suffix, a suffix without dash or a suffix outside the quotes is 412` - `"-gzip"`, `"2gzip"`, `"2-"`, `"gzip-2"`, `"2-br"x`, `"22-zstd"` for version 2.
- `a blank If-Match is still 428 and star still matches alongside suffixed tags` - empty string gives 428; `"1-gzip", *` matches.

ApiConventionsWebTests.ETagsAndIfMatch:
- Strengthened `PATCH with a stale proxy-suffixed ETag gives 412`: now also asserts the entity is unchanged afterwards.
- Added `PATCH with a weak or unknown-suffix ETag of the current version gives 412 and changes nothing` (`W/"1-gzip"`, `"1-deflate"`; ETag stays `"1"`, name stays unchanged).
- Added `PATCH with a list holding a stale and the current brotli-suffixed ETag updates` (`"0-gzip", "1-br"` gives 200 and ETag `"2"`).

The implementer's tests (2 in ETagsTests, 3 in the web tests) were reviewed and kept unchanged apart from the strengthening above.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: suffixed `-gzip`/`-zstd`/`-br` tag of the version matches | ETagsTests: `a proxy content-coding suffix on the current version matches`, `a suffixed tag matches at version zero, a large version, and among weak or stale tags` | `./gradlew test --tests 'ch.sonensei.cockpit.core.web.*' jacocoTestReport ktlintCheck` | Pass |
| AC1: weak, other versions, other suffixes, garbage give 412 | ETagsTests: `a suffixed stale, weak, unknown or malformed tag is 412`, `a bare suffix, a suffix without dash or a suffix outside the quotes is 412`, plus pre-existing `stale, unquoted or weak If-Match is 412`, `a list of only stale or weak tags is 412` | same | Pass |
| AC1: missing header 428, `*` matches | ETagsTests: `missing or blank If-Match is 428`, `current version, a list containing it, or star matches`, `a blank If-Match is still 428 and star still matches alongside suffixed tags` | same | Pass |
| AC2: unit tests for suffixed match, stale, list, failing cases | all ETagsTests above (10 tests) | same | Pass |
| AC3: web test through a real controller accepts `If-Match: "version-gzip"` | ApiConventionsWebTests.ETagsAndIfMatch: `PATCH with the current ETag as rewritten by a compressing proxy updates`, `DELETE with the current ETag as rewritten by a compressing proxy gives 204`, `PATCH with a stale proxy-suffixed ETag gives 412`, `PATCH with a weak or unknown-suffix ETag of the current version gives 412 and changes nothing`, `PATCH with a list holding a stale and the current brotli-suffixed ETag updates` | same | Pass |
| AC4: verified against Caddy | none here | not run | Not run: done by the parent worker per instructions |
| AC5: SPEC.md §4.2 / KDoc note | documentation, checked by reading the diff (KDoc on `ETags` and `checkIfMatch`, SPEC.md changed) | not applicable | Not testable (documentation) |

Note: the web tests use the test controller of ApiConventionsWebTests (an in-memory items endpoint that calls `ETags.checkIfMatch`), which is a real Spring MVC controller through MockMvc with security and the Problem Details handler.

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.web.*' jacocoTestReport ktlintCheck`

Result:

Pass (BUILD SUCCESSFUL). ETagsTests 10/10, ApiConventionsWebTests$ETagsAndIfMatch 12/12, $Errors 21/21, $Pagination 5/5, ApiExceptionsTests 2/2, CursorRequestTests 8/8, OpenApiContractTest 1/1; 0 failures, 0 errors, 0 skipped. ktlint clean on the test source set.

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Pass (BUILD SUCCESSFUL in 3m 24s; 5296 tests, 0 failures, 0 errors, 0 skipped, counted from `build/test-results/test/*.xml`). Web and Android parts are not affected by the task and were not run.

## .taskcheck

Result:

Not run (the parent runs `sh scripts/check.sh`; the server part of it, `./gradlew check`, passed above).

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 3/3 (100%) executable changed lines covered. Method: added/modified lines of ETags.kt in the task diff intersected with JaCoCo `line` entries of the report (instrumented lines 26, 32, 35, 48, 49, 51, 52, 57; changed executable lines 35, 51, 57; all have mi=0, mb=0). Changed-branch coverage not separately derived; the whole file has 0 missed branches.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: etag-encoding-suffix; base e4c488dd97eeacfd3dbfc3e34f5aa134b15efd5d (supplied by the parent); measured HEAD 2a688b95936fcc14e69cedf12b5dcec6b996b485, which is the single task commit; scope taken from `git diff e4c488dd..2a688b95 --stat`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 ETags.kt 9923d5ef054e3b1814b37d8ddb462636682bd51e87760d28efd4305d1c6fed22 (committed, unchanged); ETagsTests.kt 9f75fc3dd26befaa123a749d7dbe2ba9e1d2e5b9ee1f3170c7e26b994a585a4e and ApiConventionsWebTests.kt 75f4b93756a57e73b7aec3a9f3299ea0757f401c738af0da1a6a8d2578db8727 (uncommitted, with the additions above); hashes identical before and after the run.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/core/web/ETags.kt` (only production file changed). Excluded: `SPEC.md` (documentation), the two test files (tests). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (project configuration, OpenJDK 21.0.12.1); `./gradlew test --tests 'ch.sonensei.cockpit.core.web.*' jacocoTestReport ktlintCheck` in `server/`; selection: all tests in package `ch.sonensei.cockpit.core.web`.
- Run start/completion time and exit status: started 2026-11-25T08:11:34+01:00, report written 2026-11-25 08:12:01 +01:00, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 c87a88dc0221487cea66d9a3a403bc8de630f6aff24a2308a9934a18fca1a91d.
- Evidence outputs are fresh and inputs stayed unchanged: test classes were recompiled (`compileTestKotlin` executed), `test` and `jacocoTestReport` executed in this run, report timestamp after the run start; input hashes unchanged after the run.
- Final run after latest test additions and production fixes: yes (no production fixes were made; the run includes all test additions).

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/web/ETags.kt` | 127/127 instructions (100%) | 16/16 (100%) | 5/5 methods (100%) | 8/8 (100%) |

Aggregate over the task scope (one file) equals the row above; no files missing or unmeasured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| none | not applicable | no uncovered lines or branches |

## Unable to test

- AC4 (Caddy end-to-end): left to the parent worker by instruction; not run here.
- AC5 is documentation; no test applies.
- Case sensitivity of the suffix (`"2-GZIP"`) is deliberately not tested: the task names lowercase suffixes only and Caddy emits lowercase, so asserting either outcome would encode an undefined behavior.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found.
