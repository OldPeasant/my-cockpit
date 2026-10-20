# Test Report

Date: 2026-10-20

Task: p0-settings - User settings API (test-writer pass 2, on the final code after fixes)

Scope: task commits `bde946a`, `9eaa88d`, `9b86aa8`, `7b19653` (`git log c2f436192bb802fe2f1cd182461358ddfb240e41..7b196531e61c513515f7189d75190dd427afee6d`), on branch `main`, plus the uncommitted test changes listed below. No uncommitted production changes (`git diff --quiet HEAD -- server/src/main api web`). The base changed from `85dc521` (pass 1) to `c2f4361` because the task was rebased onto a newer main; the task's file list is the same plus the CR-3 migration.

Requirements:
- `tasks/p0-settings.md` acceptance criteria and recorded assumptions (null resets a key; unknown key / invalid value -> 400 per key, nothing stored; PATCH requires If-Match with 428/412; one jsonb row, ETag `"0"` when there is no row; ETag is the version of the whole settings document; core keys and defaults; modules register `SettingDefinition` beans or call `register()`; `SettingsChangedEvent` after a change)
- `reviews/p0-settings/fixes.md` (TW-1, CR-1, CR-2, CR-3) and `reviews/p0-settings/review.md`
- `SPEC.md` §4.2 (ETag/If-Match, Problem Details) and §4.7 (settings)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`

## History

- Pass 1 (pre-fix revision `2acd96c`): added 18 tests (`SettingsEdgeCaseTests` new, `SettingTypeTests` extended). Found TW-1 (Low): a PATCH that changes nothing bumped the ETag once any override was stored. 701/702 server tests passed, only TW-1 failed.
- Fixes (`7b19653`, see `fixes.md`): TW-1 fixed in `SettingsService.update()` (overrides reassigned only on a real change); CR-1 test for a concurrent update (409); CR-2 `SettingsPatchNullEnums` OpenApiCustomizer adds `null` to the SettingsPatch enums (with a `/v3/api-docs` test); CR-3 migration `V202610201145__core_settings_overrides_object.sql` (check constraint, with a test).
- Pass 2 (this report, final revision `7b19653`): verified each fix is tested, added 4 tests for the remaining gaps, everything passes.

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/settings/SettingsEdgeCaseTests.kt` (2 tests added)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/settings/SettingsPatchNullEnumsTests.kt` (new, 2 plain unit tests, no Spring context)

## Tests added or updated

Count: 4 (pass 2). No existing test was changed, weakened or disabled.

- `SettingsEdgeCaseTests.resetting a key that is not set keeps the ETag while other settings are stored`: TW-1 regression on the path the pass-1 test did not cover (reset of an unset key plus a same-value key, with a non-empty stored document). ETag stays `"1"`, values unchanged, no extra event.
- `SettingsEdgeCaseTests.a PATCH with changed and unchanged keys bumps the ETag once and reports the changed key only`: the TW-1 fix still bumps the version for a real change mixed with an unchanged key, and the event lists only `weekStart`.
- `SettingsPatchNullEnumsTests.adds null once to every enum and keeps type and description`: CR-2 customizer is idempotent (run twice, `null` added once), keeps `types` and `description`, leaves non-enum properties alone.
- `SettingsPatchNullEnumsTests.leaves other schemas alone and tolerates a document without the patch schema`: only `SettingsPatch` is changed; an OpenAPI document without components does not fail.

How each fix is tested:

| Finding | Tests |
|---|---|
| TW-1 no-op PATCH keeps ETag | `setting a key to the value it already has keeps the ETag` (pass 1, failed before the fix, passes now), `resetting a key that is not set keeps the ETag while other settings are stored` (new), `a PATCH with changed and unchanged keys bumps the ETag once...` (new), `resetting a stored value its definition no longer accepts removes it` (cleanup path still writes) |
| CR-1 409 paths | `a concurrently created settings row is a conflict` (pass 1), `a concurrent change of the existing settings is a conflict and changes nothing` (fixer) |
| CR-2 null in SettingsPatch enums | `the documented patch allows null for the enum settings` (fixer, real `/v3/api-docs`), `SettingsPatchNullEnumsTests` (new, 2), `OpenApiContractTest` (committed `api/openapi.yaml` lists `- null`) |
| CR-3 overrides must be an object | `the stored overrides must be a JSON object` (fixer: `[]`, JSON `null`, string rejected), `MigrationConventionTests` (file name and folder) |

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: GET returns all settings with defaults (timeZone Europe/Zurich, weekStart, theme system, energy/creativity labels, mail image policy, ...) | `SettingsWebTests.GET returns every setting with its default and an ETag`, `SettingsServiceTests.unset settings read as their defaults`, `SettingTypeTests.the core settings have the defaults of the spec` | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.settings.*' --tests '*OpenApiContractTest' ktlintCheck` | Pass |
| AC1: PATCH validates known keys and types (unknown key -> 400) | `SettingsWebTests.an unknown key or an invalid value is a 400 per key and changes nothing`, `SettingsEdgeCaseTests.values of the wrong JSON type are rejected per key`, `...null for an unknown key is still an unknown key`, `...an empty, null or malformed body is a 400`, `...an invalid body is checked before the If-Match`, `SettingTypeTests.*` | same | Pass |
| AC1: PATCH sets keys, null resets, others unchanged; every core key settable | `SettingsWebTests.PATCH changes the given keys...`, `SettingsEdgeCaseTests.every core key can be set over the API and is returned normalized`, `...resetting a key publishes it as changed` | same | Pass |
| Assumption: If-Match required (428/412), ETag `"0"` without a row | `SettingsWebTests.PATCH needs a current If-Match`, `SettingsEdgeCaseTests.PATCH accepts the any-version If-Match...`, `...precondition errors are problem details and change nothing`, `...an empty object changes nothing and keeps the ETag` | same | Pass |
| Assumption: ETag is the document version, unchanged when nothing changes (TW-1) | `SettingsEdgeCaseTests.setting a key to the value it already has keeps the ETag`, `...resetting a key that is not set keeps the ETag while other settings are stored`, `...a PATCH with changed and unchanged keys bumps the ETag once and reports the changed key only` | same | Pass |
| Assumption: single row; concurrent first write or concurrent update -> 409 (CR-1) | `SettingsEdgeCaseTests.there is at most one settings row`, `...a concurrently created settings row is a conflict`, `...a concurrent change of the existing settings is a conflict and changes nothing` | same | Pass |
| Stored overrides are always a JSON object (CR-3) | `SettingsEdgeCaseTests.the stored overrides must be a JSON object`, `MigrationConventionTests` | `cd server && ./gradlew test jacocoTestReport` (full suite) | Pass |
| AC2: SettingsService lets modules read settings and register keys with defaults | `SettingsServiceTests.modules register their own settings, as beans or at runtime`, `SettingsServiceTests.changes are stored...`, `SettingTypeTests.definitions need a lowerCamelCase key and a valid default` | focused command above | Pass |
| Assumption: SettingsChangedEvent with changed keys only, none on failure/no-op | `SettingsServiceTests.a change publishes the changed keys only`, `SettingsEdgeCaseTests.a failed or unchanged update publishes no event`, `...resetting a stored value its definition no longer accepts removes it`, `...a PATCH with changed and unchanged keys...` | same | Pass |
| Stale/unregistered stored values read as defaults and are not lost | `SettingsServiceTests.a stored value its definition no longer accepts reads as the default`, `SettingsEdgeCaseTests.stored keys of settings that are no longer registered survive other changes` | same | Pass |
| Security: settings need a login | `SettingsWebTests.settings need a login` | same | Pass |
| AC3: `./gradlew test --tests 'ch.sonensei.cockpit.core.settings.*'` passes (Postgres via Testcontainers) | all 44 settings tests (SettingTypeTests 10, SettingsServiceTests 6, SettingsWebTests 6, SettingsEdgeCaseTests 20, SettingsPatchNullEnumsTests 2) | same | Pass (44/44) |
| AC4: `api/openapi.yaml` regenerated, `OpenApiContractTest` passes without the flag; PATCH schema allows null for enum keys (CR-2) | `OpenApiContractTest`, `SettingsEdgeCaseTests.the documented patch allows null for the enum settings`, `SettingsPatchNullEnumsTests.*` | same | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.settings.*' --tests '*OpenApiContractTest' ktlintCheck`

Result:

Pass: 45 tests, 0 failures, 0 skipped (settings 44, `OpenApiContractTest` 1). ktlintCheck pass (`ktlintFormat` was run once on the new test file only; it reformatted chained calls). An earlier focused run that also included `*MigrationConvention*` passed (2/2).

## Full test execution

Command:

`cd server && ./gradlew test jacocoTestReport`

Result:

Pass: 709 tests, 0 failures, 0 errors, 0 skipped (counted from `server/build/test-results/test/*.xml`).

## .taskcheck

Result:

Pass: `sh scripts/check.sh` -> "== all checks passed" (server, web, android steps), run after the final test changes.

## Bugs found

None in pass 2. TW-1 from pass 1 is fixed and its regression test passes.

Observation (not a defect, for the parent to decide): after a key was explicitly set to a value equal to its default (e.g. `theme` `dark` -> `system`, which stores `"theme": "system"`), a later `{"theme": null}` removes the stored key and bumps the ETag although the returned document is identical (no event is published). The stored document did change, so this matches the fixer's intent for the cleanup path; it can only cause a rare spurious 412 for another client. No test asserts either behavior.

## Coverage

Status: verified (fresh full-suite run on the final revision plus the pass-2 tests, exit 0).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to whole-file coverage. Every measured Kotlin file was added in full by this task (`git diff --stat c2f4361..HEAD` shows only insertions), so all executable lines are changed lines. No separate changed-line tool was run.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `p0-settings`; base `c2f436192bb802fe2f1cd182461358ddfb240e41` (`git merge-base HEAD origin/main`, supplied by the parent); HEAD `7b196531e61c513515f7189d75190dd427afee6d`; task commits `bde946a`, `9eaa88d`, `9b86aa8`, `7b19653` (`git log --oneline origin/main..HEAD`), file list from `git diff --stat c2f4361..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): production equals HEAD (`git diff --quiet HEAD -- server/src/main api web`, checked before and after the run). Test sha256: `SettingsEdgeCaseTests.kt` d4bd3867476c78b840be45f201ed5ad511ecdd22bb880934391b34d779c59231, `SettingsPatchNullEnumsTests.kt` adf1396e53597e1434f80937315fa70e222784cd71a269c475cabdb9ad4e8acc, `SettingsServiceTests.kt` 0c7f49b102ed91be895b7b31a67bd3e06d9f6c024fc4ae1e70fbff7acd560f19, `SettingsWebTests.kt` c92ed6a5963c50456d8c7d32cbdd8e16115ece2a42d3437f2a1cb06384476ded, `SettingTypeTests.kt` 36728a7a63cd88fd12490686d50f6ea442a193e34dd3f6aa6e475312a1bc3bca; identical before and after the run. (`reviews/p0-settings/review.md` was modified in the working tree by another helper, not by this test-writer; it is not a test input.)
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/core/settings/api/SettingsController.kt`, `.../api/SettingsSchemas.kt`, `.../domain/SettingDefinition.kt`, `.../domain/SettingsService.kt`, `.../domain/UserSettings.kt`, `.../infra/UserSettingsRepository.kt`. Excluded: `server/src/main/resources/db/migration/core/V202610201130__core_settings.sql` and `V202610201145__core_settings_overrides_object.sql` (SQL, not instrumentable; applied by Flyway in every Postgres test, their unique index and check constraint are tested), `api/openapi.yaml` (generated, checked by `OpenApiContractTest`), `web/src/app/api/generated/**` (generated client; compiled and linted by the web step of `.taskcheck`). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via the project's Gradle `jacoco` plugin; `./gradlew test jacocoTestReport`; working directory `server/`; full server test suite.
- Run start/completion time and exit status: started 2026-10-20T13:55:33+02:00, completed 13:56:54+02:00 (BUILD SUCCESSFUL), exit 0.
- Generated report paths and report hashes/run identifier: `server/build/jacoco/test.exec` (13:56:53, sha256 cc38d7f7c2494fce7a434a116103ad352015e9c4ebdcd029cf8e27f308932017), `server/build/reports/jacoco/test/jacocoTestReport.xml` (13:56:54, sha256 06408455ba48d8a71b98ee1bf87b9de6cf29a018e6aeaf0211bf27780a12766e). Both replaced the reports of the preceding pass-2 run (13:54), which are not used.
- Evidence outputs are fresh and inputs stayed unchanged: yes (timestamps after the run start; test hashes and production state unchanged).
- Final run after latest test additions and production fixes: yes (final revision `7b19653` with all pass-2 tests).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Statements = JaCoCo INSTRUCTION counter, Functions = METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/.../core/settings/domain/SettingsService.kt` | 498/501 (99.4%) | 50/50 (100%) | 19/20 (95.0%) | 70/70 (100%) |
| `server/.../core/settings/domain/SettingDefinition.kt` | 829/861 (96.3%) | 96/96 (100%) | 33/40 (82.5%) | 87/89 (97.8%) |
| `server/.../core/settings/domain/UserSettings.kt` | 25/25 (100%) | N/A | 4/4 (100%) | 5/5 (100%) |
| `server/.../core/settings/api/SettingsController.kt` | 39/39 (100%) | N/A | 4/4 (100%) | 5/5 (100%) |
| `server/.../core/settings/api/SettingsSchemas.kt` | 95/185 (51.4%) | 14/14 (100%) | 2/18 (11.1%) | 16/48 (33.3%) |
| `server/.../core/settings/infra/UserSettingsRepository.kt` | N/A (interface, no counters; executed through the Spring Data proxy in every Spring test) | N/A | N/A | N/A |

Aggregate over the five measured files (summed counters): statements 1486/1611 (92.2%), branches 210/210 (100%), functions 62/86 (72.1%), lines 183/217 (84.3%). Not measured: `UserSettingsRepository.kt` (no counters). Excluded: the two migration SQL files and generated files (see above). JaCoCo also lists `Comparisons.kt` and `Extensions.kt` under the settings package with no counters; these are inlined Kotlin stdlib sources, not task files.

Compared with pass 1 (pre-fix): `SettingsService.kt` branches 46/46 -> 50/50 (TW-1 `dirty` branches all covered); `SettingsSchemas.kt` now includes the CR-2 customizer (lines 66-83), fully covered with 14/14 branches after the new unit tests (before them: 9/14, the early return and the idempotency check were missed).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `SettingsSchemas.kt:14-58` (`Settings`, `SettingsPatch` and their nested classes) | None at runtime: documentation-only classes, never instantiated | Justified gap. Their schema output is checked by `OpenApiContractTest` (pass) |
| `SettingsSchemas.kt:67-83` (CR-2 customizer) | `null` documented for reset of enum keys | Fully covered: `SettingsPatchNullEnumsTests` (new) and `the documented patch allows null for the enum settings` |
| `SettingDefinition.kt:26` (`toString`), `:19`, `:68`, `:84` (getters `description`, `values`, `keys` not read by tests), `:29`, `:132` (partial: synthetic companion/accessor code) | Diagnostics and metadata only | Justified gap, no behavior to assert (unchanged since pass 1) |
| `SettingDefinition.kt:99-102` (`TextList` default-argument constructor) | Both defaults are overridden by the only user (`MAIL_IMAGE_ALLOWED_SENDERS`) | Justified gap |
| `SettingsService.kt:161` (generated `SettingsChangedEvent` data-class member) | Event equality and keys are asserted by the event tests; the missed method is generated | Justified gap |

## Unable to test

- A real concurrent first write (two transactions racing on the unique index) end to end over HTTP was not run; the constraint and the 409 mapping are each tested, and the concurrent update path is tested with a real concurrent version bump. Follow-up optional.
- Registration of `SettingDefinition` beans from the real Spring context is tested with a mocked `ObjectProvider`, because an extra test bean would create a separate Spring context against the shared-context convention. Follow-up when the first module registers a key.
- That the generated web client actually sends `null` for reset is not tested here (no web code uses settings yet); the generated client compiles and lints in `.taskcheck`.

## Summary

Tests added and all validation passed. The fixes for TW-1, CR-1, CR-2 and CR-3 are covered by tests. 4 tests were added in pass 2 for the remaining TW-1 and CR-2 gaps. 44/44 settings tests, `OpenApiContractTest`, ktlint, the full server suite (709/709) and `.taskcheck` pass. Task-scoped branch coverage is 100% (210/210); the remaining uncovered code is documentation-only schema classes and trivial generated accessors. No production defects found in pass 2.
