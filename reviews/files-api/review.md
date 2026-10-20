# Code Review

Date: 2026-10-21

Task: files-api - Files API: upload, download, operations

Scope: `git diff c3b429ed64eceb70609b59999406373f0e3248fd..2d893a71`, excluding `tasks/` and `reviews/`. Reviewed revision: 2d893a71 (final code revision). Committed changes only. The working tree was clean at the start of the review.

How the scope was reviewed:
- The previous review covered fa633bff (full task scope `c3b429ed..fa633bff`, 0 findings). The commits since then are d996a5ec (review report only), 4ed5c744 (test report only) and 2d893a71.
- `git diff fa633bff..2d893a71`, excluding `reviews/`, touches exactly one file: `server/src/main/kotlin/ch/sonensei/cockpit/files/infra/DriveQueries.kt`, one line changed (the trailing `!!` after the `jdbc.queryForObject(...)` call in `totals()` is removed). `git show --stat 2d893a71` confirms that this commit changes only that file and `reviews/files-api/fixes.md`. All other task code is unchanged since fa633bff, so the conclusions of the previous review carry over.
- `DriveQueries.kt` was read in full, together with its only caller (`DriveService.kt:90`).
- Commands run, all read-only: `git status`, `git log`, `git show`, `git diff`, `git patch-id`, `grep`, `sed`. No Gradle or npm command was run, because a test-writer was running in the same clone at the same time. Test results come from `reviews/files-api/tests.md` and `reviews/files-api/fixes.md`.

Requirements:
- `tasks/files-api.md`: acceptance criteria 1-5 and the ASSUMPTION notes
- SPEC.md §8.2 and §4.2 (as reflected in the task), AGENTS.md project notes (in particular "fix warnings, don't suppress them"), `docs/ARCHITECTURE.md`
- Previous `reviews/files-api/review.md` (reviewed fa633bff, 0 findings)
- `reviews/files-api/tests.md` (TW-1) and `reviews/files-api/fixes.md`

Git range:
- Branch: main
- HEAD: 2d893a71 (files-api: remove unnecessary !! in DriveQueries.totals (TW-1), update fixes.md)
- Base/start: c3b429ed64eceb70609b59999406373f0e3248fd (merge-base with origin/main)
- Commits reviewed: 3 since the previous review (d996a5ec [report only], 4ed5c744 [report only], 2d893a71); the full task range up to fa633bff was covered by the previous reviews

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Changes since the previous review

- `DriveQueries.totals()`: the `!!` after `jdbc.queryForObject(sql) { rs, _ -> Totals(...) }` is removed. The project uses Spring Boot 4.1.1 (Spring Framework 7, JSpecify nullness), where `queryForObject(String, RowMapper<T>)` returns `T` and `T` is inferred from the row mapper. The mapper always builds a non-null `Totals`, so the result type is already `Totals`, as the compiler warning reported by the test-writer (`DriveQueries.kt:64:99`) says. Behavior is unchanged: `queryForObject` still throws `IncorrectResultSizeDataAccessException` if the row count is not exactly one, and the aggregate query without `group by` always returns exactly one row. The return type `Totals` of `totals()` and its caller `DriveService` are unaffected. The change resolves a warning in task code, as AGENTS.md requires.

## Check of `reviews/files-api/fixes.md`

- The TW-1 section matches the commit: the file, the line, the problem (the compiler warning quoted in `tests.md`) and the fix (removing the trailing `!!`) are described correctly, and the reasoning about `queryForObject` is correct.
- The validation claims (178 tests in the files suite, `compileKotlin` without a warning in the files module) could not be re-run here because Gradle was not run. They are consistent with `tests.md`, which reports 179 tests for the same suite plus `OpenApiContractTest`.
- The report correctly states that the full `./gradlew check` and `.taskcheck` were not re-run in the TW-1 round. The parent must still run `.taskcheck` on the final code before `done`.
- The earlier sections (CR-1 to CR-4, CR2-1, CR2-2) are unchanged in substance and were already checked in the previous reviews.

## Verification against the acceptance criteria

- AC1 to AC3: met in code. The only production change since fa633bff is the type-level cleanup above, which changes no behavior.
- AC4 (focused test suite): the test-writer's run on fa633bff passed (179 tests, see `tests.md`), and the fixer reports the files suite passing after TW-1. The reviewer did not re-run it. The parent's final evidence should come from a run on 2d893a71 (the concurrent test-writer run or `.taskcheck`).
- AC5 (OpenAPI regenerated, `OpenApiContractTest` passes): the change touches no API annotation or DTO, so `api/openapi.yaml` and the generated web client still match the code.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.

The only code change since the reviewed fa633bff, removing a redundant `!!` in `DriveQueries.totals()`, is correct and changes no behavior. `fixes.md` describes it accurately. All other task code is unchanged since the previous review.
