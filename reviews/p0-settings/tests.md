# Test Report

Date: 2026-10-20

Task: p0-settings - User settings API

Scope: task commits `ec2544f`, `2acd96c` (`git log 85dc52177d1680c601860c7da275755e44ca580c..2acd96cfe5bb9ae689f298b80f57852639f9fcb1`), on branch `main`, plus the uncommitted test changes listed below. No uncommitted production changes.

Requirements:
- `tasks/p0-settings.md` acceptance criteria and recorded assumptions (null resets a key; unknown key / invalid value -> 400 per key, nothing stored; PATCH requires If-Match with 428/412; one jsonb row, ETag `"0"` when there is no row; core keys and defaults; modules register `SettingDefinition` beans or call `register()`; `SettingsChangedEvent` after a change)
- `SPEC.md` §4.2 (ETag/If-Match, Problem Details) and §4.7 (settings)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/settings/SettingsEdgeCaseTests.kt` (new)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/settings/SettingTypeTests.kt` (extended)

## Tests added or updated

Count: 18 (15 new in `SettingsEdgeCaseTests`, 2 new and 1 extended in `SettingTypeTests`). The existing 20 tests are unchanged.

New tests in `SettingsEdgeCaseTests` (Spring Boot, `PostgresTestBase`, `@AutoConfigureMockMvc`, same context as the other settings tests):
- `every core key can be set over the API and is returned normalized` (weekStart, creativityLabels trimmed, mailImagePolicy, mailImageAllowedSenders trimmed)
- `PATCH accepts the any-version If-Match and rejects weak or listed stale tags` (`*`, `W/"0"` -> 412, list `"0", "1"`)
- `precondition errors are problem details and change nothing` (428/412 are `application/problem+json`, ETag stays `"0"`)
- `an invalid body is checked before the If-Match`
- `null for an unknown key is still an unknown key`
- `values of the wrong JSON type are rejected per key` (5 field errors, no row created)
- `an empty, null or malformed body is a 400`
- `an empty object changes nothing and keeps the ETag`
- `setting a key to the value it already has keeps the ETag` - **fails, see TW-1**
- `a failed or unchanged update publishes no event`
- `resetting a key publishes it as changed`
- `resetting a stored value its definition no longer accepts removes it`
- `stored keys of settings that are no longer registered survive other changes`
- `there is at most one settings row` (unique index `user_settings_single_row`)
- `a concurrently created settings row is a conflict` (repository mock throws `DataIntegrityViolationException` -> `ConflictException`)

New/extended in `SettingTypeTests`:
- `types reject null and accept values exactly at their limits` (Text default 200, WholeNumber lower bound / Short / Byte / BigInteger / Long overflow, Labels null value, TextList duplicates after trim and null items)
- `the core settings have the defaults of the spec` (and unique core keys)
- `definitions need a lowerCamelCase key and a valid default` (extended: 64-char key accepted, 65-char key rejected)

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: GET returns all settings with defaults (timeZone Europe/Zurich, weekStart, theme system, energy/creativity labels, mail image policy, ...) | `SettingsWebTests.GET returns every setting with its default and an ETag`, `SettingsServiceTests.unset settings read as their defaults`, `SettingTypeTests.the core settings have the defaults of the spec` | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.settings.*'` | Pass |
| AC1: PATCH validates known keys and types (unknown key -> 400) | `SettingsWebTests.an unknown key or an invalid value is a 400 per key and changes nothing`, `SettingsEdgeCaseTests.values of the wrong JSON type are rejected per key`, `...null for an unknown key is still an unknown key`, `...an empty, null or malformed body is a 400`, `...an invalid body is checked before the If-Match`, `SettingTypeTests.*` | same | Pass |
| AC1: PATCH sets keys, null resets, others unchanged; every core key settable | `SettingsWebTests.PATCH changes the given keys...`, `SettingsEdgeCaseTests.every core key can be set over the API and is returned normalized`, `...resetting a key publishes it as changed` | same | Pass |
| Assumption: PATCH requires If-Match (428/412), ETag `"0"` without a row | `SettingsWebTests.PATCH needs a current If-Match`, `SettingsEdgeCaseTests.PATCH accepts the any-version If-Match...`, `...precondition errors are problem details and change nothing`, `...an empty object changes nothing and keeps the ETag` | same | Pass |
| Assumption: ETag is the version of the settings document (does not change when nothing changed) | `SettingsEdgeCaseTests.setting a key to the value it already has keeps the ETag` | same | **Fail (TW-1)** |
| Assumption: single row; concurrent first write -> 409 | `SettingsEdgeCaseTests.there is at most one settings row`, `...a concurrently created settings row is a conflict` | same | Pass |
| AC2: SettingsService lets modules read settings and register keys with defaults | `SettingsServiceTests.modules register their own settings, as beans or at runtime`, `SettingsServiceTests.changes are stored...`, `SettingTypeTests.definitions need a lowerCamelCase key and a valid default` | same | Pass |
| Assumption: SettingsChangedEvent with changed keys only, none on failure/no-op | `SettingsServiceTests.a change publishes the changed keys only`, `SettingsEdgeCaseTests.a failed or unchanged update publishes no event`, `...resetting a stored value its definition no longer accepts removes it` | same | Pass |
| Stale/unregistered stored values read as defaults and are not lost | `SettingsServiceTests.a stored value its definition no longer accepts reads as the default`, `SettingsEdgeCaseTests.stored keys of settings that are no longer registered survive other changes` | same | Pass |
| Security: settings need a login | `SettingsWebTests.settings need a login` | same | Pass |
| AC3: `./gradlew test --tests 'ch.sonensei.cockpit.core.settings.*'` passes (Postgres via Testcontainers) | all 38 settings tests | same | **Fail: 37 passed, 1 failed (TW-1)**. Before the new tests: 20/20 passed |
| AC4: `api/openapi.yaml` regenerated, `OpenApiContractTest` passes without the flag | `OpenApiContractTest` (1 test) | `cd server && ./gradlew test jacocoTestReport --continue` (full suite) | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.settings.*'`

Result:

Fail: 38 tests, 37 passed, 1 failed (`SettingsEdgeCaseTests.setting a key to the value it already has keeps the ETag`, TW-1). `SettingTypeTests` 10/10, `SettingsServiceTests` 6/6, `SettingsWebTests` 6/6, `SettingsEdgeCaseTests` 14/15.

`cd server && ./gradlew ktlintCheck`: Pass (after formatting one chained call in the new test code).

## Full test execution

Command:

`cd server && ./gradlew test jacocoTestReport --continue`

Result:

Fail: 702 tests, 701 passed, 1 failed (only TW-1; `OpenApiContractTest` 1/1 and `MigrationConventionTests` 2/2 pass).

## .taskcheck

Result:

Fail: `sh scripts/check.sh` exit 1. The tfp gate tests passed; the server step (`./gradlew check`) failed with 702 tests, 1 failed (TW-1 only). The web and Android steps did not run because the script stops at the first failure (`set -e`).

## Bugs found

### A PATCH that changes nothing still bumps the settings ETag

Finding ID:

`TW-1`

Test:

`SettingsEdgeCaseTests.setting a key to the value it already has keeps the ETag`

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/core/settings/domain/SettingsService.kt:97-98` (`settings.overrides = jsonMapper.writeValueAsString(overrides.toSortedMap())` followed by an unconditional `saveAndFlush`)

Requirement:

Recorded assumption "The row version is the ETag of the whole settings document", and the service's own intent (unchanged keys are skipped, no `SettingsChangedEvent` is published). SPEC.md §4.2 optimistic locking: the ETag should mark a change of the document, otherwise other clients (web and Android open at the same time) get needless 412s.

Expected behavior:

`PATCH {"theme": "dark"}` with `If-Match: "1"` when theme already is `dark` returns 200 with `ETag: "1"` (as an empty object `{}` already does).

Actual behavior:

It returns `ETag: "2"`. Cause: Postgres returns the `jsonb` column normalized (`{"theme": "dark"}` with a space), Jackson writes `{"theme":"dark"}`, so Hibernate sees a different `String` and the entity becomes dirty, issuing an UPDATE and a version increment. Every PATCH bumps the version once any override is stored, even when no value changes. (With no override stored, `"{}"` equals `"{}"` and the ETag stays, which is why the empty-object test passes.) A possible fix is to assign `overrides` only when the parsed map actually differs, rather than comparing serialized strings.

Severity:

Low (no data is wrong; causes spurious ETag changes and avoidable 412 conflicts between clients). The parent may decide this is acceptable behavior; if so, it is a requirement decision to record, not a reason to weaken the test.

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: partial-failed. Fresh JaCoCo data from the full-suite run in which exactly one test (TW-1) failed; all other 701 tests ran. The report was generated from that run's `test.exec` with `./gradlew jacocoTestReport -x test` because Gradle skips `jacocoTestReport` after a failed `test`. Values are diagnostic until TW-1 is fixed and coverage is rerun.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to whole-file coverage for the measured Kotlin files, because every one of them was added in full by this task (`git diff --stat` shows only insertions; no pre-existing lines). No separate changed-line tool was run.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `p0-settings`; base `85dc52177d1680c601860c7da275755e44ca580c`; HEAD `2acd96cfe5bb9ae689f298b80f57852639f9fcb1`; task commits `ec2544f`, `2acd96c`, supplied by the parent and confirmed with `git log 85dc521..HEAD` and `git diff --stat 85dc521..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): production equals HEAD (`git diff --quiet HEAD -- server/src/main api web`). Test sha256: `SettingsEdgeCaseTests.kt` b355f78dc25326a0255acbdff4195ecdf2a00fa7124fd8bbe49165714259b6aa, `SettingTypeTests.kt` 36728a7a63cd88fd12490686d50f6ea442a193e34dd3f6aa6e475312a1bc3bca, `SettingsServiceTests.kt` 0c7f49b102ed91be895b7b31a67bd3e06d9f6c024fc4ae1e70fbff7acd560f19, `SettingsWebTests.kt` c92ed6a5963c50456d8c7d32cbdd8e16115ece2a42d3437f2a1cb06384476ded. The hashes were identical before and after the run.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/core/settings/api/SettingsController.kt`, `.../api/SettingsSchemas.kt`, `.../domain/SettingDefinition.kt`, `.../domain/SettingsService.kt`, `.../domain/UserSettings.kt`, `.../infra/UserSettingsRepository.kt`. Excluded: `server/src/main/resources/db/migration/core/V202610201130__core_settings.sql` (SQL, cannot be instrumented, but it is applied by Flyway in every Postgres test and its unique index is tested), `api/openapi.yaml` (generated, checked by `OpenApiContractTest`: pass), `web/src/app/api/generated/**` (generated client, out of scope for server coverage). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (project-configured); `./gradlew test jacocoTestReport --continue`, then `./gradlew jacocoTestReport -x test`; working directory `server/`; full server test suite.
- Run start/completion time and exit status: started 2026-10-20T13:39:57+02:00, test run completed 13:41:22+02:00, exit 1 (1 failing test, TW-1); report generated 13:41:31+02:00, exit 0.
- Generated report paths and report hashes/run identifier: `server/build/jacoco/test.exec` (13:41:22, sha256 510f23cfb14a88a8643f4912645806fbc32b56b6e54ec6f7c60e0585a243c3eb), `server/build/reports/jacoco/test/jacocoTestReport.xml` (13:41:31, sha256 baea426419b7c468063cecaeb19bee0ede894f7e3427e4080b062e00eb18881b). The earlier report from 11:28 was replaced, not used.
- Evidence outputs are fresh and inputs stayed unchanged: yes (timestamps after the run start, hashes above unchanged).
- Final run after latest test additions and production fixes: yes for the test additions; no production fixes have been made yet (pre-fix revision `2acd96c`). Rerun after the TW-1 fix.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Statements = JaCoCo INSTRUCTION counter.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/.../core/settings/domain/SettingsService.kt` | 486/489 (99.4%) | 46/46 (100%) | 19/20 (95.0%) | 66/66 (100%) |
| `server/.../core/settings/domain/SettingDefinition.kt` | 829/861 (96.3%) | 96/96 (100%) | 33/40 (82.5%) | 87/89 (97.8%) |
| `server/.../core/settings/domain/UserSettings.kt` | 25/25 (100%) | N/A | 4/4 (100%) | 5/5 (100%) |
| `server/.../core/settings/api/SettingsController.kt` | 39/39 (100%) | N/A | 4/4 (100%) | 5/5 (100%) |
| `server/.../core/settings/api/SettingsSchemas.kt` | 0/90 (0%) | N/A | 0/16 (0%) | 0/32 (0%) |
| `server/.../core/settings/infra/UserSettingsRepository.kt` | N/A (interface, no bytecode counters; executed through the Spring Data proxy in every Spring test) | N/A | N/A | N/A |

Aggregate over the five measured files (summed counters): statements 1379/1504 (91.7%), branches 142/142 (100%), functions 60/84 (71.4%), lines 163/197 (82.7%). Without the documentation-only `SettingsSchemas.kt`: statements 1379/1414 (97.5%), functions 60/68 (88.2%), lines 163/165 (98.8%). Not measured: `UserSettingsRepository.kt` (no counters); excluded: migration SQL and generated files (see above). JaCoCo also lists `Comparisons.kt` and `Extensions.kt` under the settings package with no counters; these are Kotlin stdlib inline sources, not task files.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `SettingsSchemas.kt:10-54` | None at runtime: these classes only describe the OpenAPI schema and are never instantiated | Justified gap. The schema output is checked by `OpenApiContractTest` (pass) |
| `SettingDefinition.kt:26` (`toString`), `:19`, `:68`, `:84` (property getters `description`, `values`, `keys` not read by tests) | Diagnostics and metadata only | Justified gap, no behavior to assert |
| `SettingDefinition.kt:99-102` (`TextList` synthetic default-argument constructor) | Both defaults are overridden by the only user (`MAIL_IMAGE_ALLOWED_SENDERS`) | Justified gap |
| `SettingsService.kt:153` (generated `SettingsChangedEvent` data-class member) | Equality/fields of the event are used by the event tests; the remaining missed method is a generated data-class member | Justified gap |
| `SettingsService.kt` `create()` catch branch | Concurrent first write -> 409 | Covered by the new test `a concurrently created settings row is a conflict` |

## Unable to test

- A real concurrent first write (two transactions racing on the unique index) through HTTP was not run end to end. The database constraint and the 409 mapping are each tested separately. Follow-up is optional.
- Registration of `SettingDefinition` beans from the real Spring context was tested with a mocked `ObjectProvider` (existing test), not with a real extra bean. A test configuration bean would create a separate Spring context for this suite, against the shared-context convention. No module registers a key yet. Follow-up when the first module does.
- Web client use of the regenerated `web/src/app/api/generated` code was not run here: the web step of `.taskcheck` did not run because the server step failed first.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion (TW-1, Low: a no-op PATCH bumps the ETag). All other 701 server tests, ktlint and `OpenApiContractTest` pass. Task-scoped branch coverage is 100%; the remaining uncovered code is documentation-only schema classes and trivial accessors.
