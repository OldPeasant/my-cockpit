# Code Review

Date: 2026-10-20

Task: p0-settings - User settings API

Scope: the whole task change at HEAD 101db46657045d93006b703497be15094a4ec472 against the base commit 8c62a1cf64e31de74d5bffae40efe4efe707e8cd (`git diff 8c62a1c..HEAD -- . ':!reviews' ':!tasks'`). Pass 3 is the final review of the final code. It pays particular attention to the tests added by test-writer pass 2.

Requirements:
- tasks/p0-settings.md acceptance criteria and recorded assumptions
- SPEC.md §4.2 (UUIDv7, version/ETag/If-Match, Problem Details) and §4.7 (settings)
- docs/ARCHITECTURE.md, AGENTS.md project notes (module packages, Flyway conventions, generated API files, logging)
- Earlier review passes, reviews/p0-settings/tests.md, reviews/p0-settings/fixes.md

Git range:
- Branch: main
- HEAD: 101db46657045d93006b703497be15094a4ec472 (reviewed revision)
- Base/start: 8c62a1cf64e31de74d5bffae40efe4efe707e8cd (merge-base with origin/main)
- Commits reviewed: 5 (292af9f implementation, 28a3f21 client regeneration and ktlint, a2af054 pass-1 tests, dd9641c fixes TW-1/CR-1..CR-3, 101db46 pass-2 tests)
- Uncommitted changes: none (working tree clean)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## History of earlier passes

- Pass 1 found CR-1 (409 conflict paths were untested), CR-2 (the `SettingsPatch` enums did not list `null`, which resets a key) and CR-3 (a non-object `overrides` document broke every read). Test-writer pass 1 found TW-1 (a no-op PATCH bumped the ETag). Commit dd9641c fixed all four.
- Pass 2 ran at pre-rebase revision 7b19653 without shell access. It read the files directly, confirmed every fix and found no issues.
- After pass 2, test-writer pass 2 added only tests: two tests in `SettingsEdgeCaseTests.kt` and the new `SettingsPatchNullEnumsTests.kt`. The rebase onto main brought in only task-state commits.

## Validation run by this reviewer

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.settings.*' --tests '*OpenApiContractTest' ktlintCheck` -> BUILD SUCCESSFUL. The `test` task ran (it was not up to date). The JUnit XML from this run shows 45 tests, 0 failures, 0 errors and 0 skipped:
- SettingTypeTests: 10
- SettingsEdgeCaseTests: 20
- SettingsServiceTests: 6
- SettingsWebTests: 6
- SettingsPatchNullEnumsTests: 2
- OpenApiContractTest: 1

ktlintCheck passed for the main and test sources.

## Review of the whole change at HEAD

All production code from the base commit to HEAD was read from the actual diff: `core/settings/{api,domain,infra}`, both `core` migrations, the `api/openapi.yaml` settings section and the generated web client files. What was checked:

- **AC1:** GET returns every registered key with its default: timeZone `Europe/Zurich`, weekStart `MONDAY`, theme `system`, the energy and creativity labels, mailImagePolicy `block` and mailImageAllowedSenders `[]`. PATCH validates every key and value before the If-Match check (`SettingsService.update` -> `validate`). An unknown key, including one sent as `null`, or an invalid value gives a 400 with one FieldError per key, and nothing is stored.
- **AC2:** `SettingsService.register()` rejects a duplicate key and treats re-registering the same instance as a no-op. `SettingDefinition` beans are collected at startup. `get()` is type-safe and requires the key to be registered. `SettingsChangedEvent` carries only the keys whose value changed.
- **SPEC §4.2:** a PATCH without If-Match gets 428, and a stale If-Match gets 412. The ETag is the row version, or `"0"` when no row exists yet. A concurrent first create gives 409 through the unique index `((true))`. A concurrent update gives 409 through `@Version`: `ProblemDetailsAdvice` maps `OptimisticLockingFailureException` to ConflictException. The entity extends BaseEntity, which provides the UUIDv7 id, timestamps and version.
- **Schema:** the schema change uses new migrations in `db/migration/core/`. A recorded assumption explains why they are not under `core-settings/`. The `check (jsonb_typeof(overrides) = 'object')` constraint is a separate migration, so no applied migration was edited.
- **AC4:** `api/openapi.yaml` matches the controller: the paths, the operation ids, the If-Match header, the 200/400/409/412/428 responses, and the `Settings`/`SettingsPatch` schemas with additionalProperties. In `SettingsPatch`, `theme`, `weekStart` and `mailImagePolicy` list `null` in their enums. The `OpenApiContractTest` run above passed.
- **Security and logging:** the endpoints sit under the authenticated `/api/v1`. Only key names are logged, at DEBUG. No values are logged.

Newly added tests (test-writer pass 2):
- `resetting a key that is not set keeps the ETag while other settings are stored` checks that a mixed PATCH with a no-op reset and an unchanged value keeps ETag `"1"`, keeps the stored values and publishes no second event. This is the intended no-op behavior of `update()`, where `dirty` stays false.
- `a PATCH with changed and unchanged keys bumps the ETag once and reports the changed key only` checks the ETag `"1"` -> `"2"` and the exact event list `[{theme}, {weekStart}]`. It catches regressions where unchanged keys would be reported as changed.
- `SettingsPatchNullEnumsTests` tests the OpenAPI customizer in isolation. It covers idempotence (two runs add `null` only once), keeping `types` and `description`, leaving non-enum properties alone, ignoring other schemas, and a document without components. The assertions are on behavior and use no mocks.
- The pass-1/fix tests in `SettingsEdgeCaseTests` were re-read. In `a concurrent change ... is a conflict`, the JDBC version bump runs in the same Spring transaction as `update()`, so the save fails with `OptimisticLockingFailureException`. The rollback then restores version 1 and `dark`, which the test asserts. This is a valid simulation of a concurrent writer.
- No existing test was weakened, deleted or skipped.

Observations (not findings):
- `SettingsChangedEvent` is published inside the update transaction. No listener exists yet. A future listener that must see the committed value should use `@TransactionalEventListener(phase = AFTER_COMMIT)`.
- `SettingsPatchNullEnums` rebuilds an enum property with only `types`, `description` and `enum`. That covers every attribute the current enum properties use. If an enum property later gets e.g. `example`, the customizer would have to copy it too.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. Reviewed revision: 101db46657045d93006b703497be15094a4ec472.
