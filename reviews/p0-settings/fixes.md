# Fix Report

Date: 2026-10-20

Task: p0-settings - User settings API

Scope: current task. Findings TW-1 (reviews/p0-settings/tests.md), CR-1, CR-2, CR-3 (reviews/p0-settings/review.md), on top of HEAD 0b3a274 (base 85dc52177d1680c601860c7da275755e44ca580c). Changes are uncommitted.

## Fixed findings

### TW-1 — A PATCH that changes nothing still bumps the settings ETag

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/settings/domain/SettingsService.kt`

Problem:
`update()` always reassigned `overrides` with re-serialized JSON. Postgres returns jsonb reformatted, so Hibernate saw a changed string and incremented the version even when no value changed.

Fix:
`update()` tracks whether the overrides map was actually modified (a changed key, or removal of a stored key on reset, which keeps the cleanup of stale stored values) and only then reassigns `overrides`. Otherwise the entity stays clean and the version (ETag) is kept.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.settings.*'` — Pass (`setting a key to the value it already has keeps the ETag` and `resetting a stored value its definition no longer accepts removes it` both pass)

### CR-1 — The 409 conflict paths have no tests

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/core/settings/SettingsEdgeCaseTests.kt`

Problem:
The first-row race was already covered by the test-writer (`there is at most one settings row`, `a concurrently created settings row is a conflict`). A concurrent update of the existing row was not tested.

Fix:
New test `a concurrent change of the existing settings is a conflict and changes nothing`: it bumps `version` via JdbcTemplate inside the `checkVersion` callback and asserts `OptimisticLockingFailureException` (mapped to 409 by `ProblemDetailsAdvice`), with version and value unchanged afterwards.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.settings.*'` — Pass

### CR-2 — SettingsPatch schema: `null` is allowed by `type` but rejected by `enum`

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/settings/api/SettingsSchemas.kt`
- `api/openapi.yaml` (regenerated)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/settings/SettingsEdgeCaseTests.kt`

Problem:
`weekStart`, `theme`, `mailImagePolicy` declared `type: [string, "null"]` but an `enum` without null, so `{"theme": null}` was invalid against the published schema.

Fix:
Adding `"null"` to `allowableValues` was tried first: springdoc renders it as the string `"null"` (wrong), so it was reverted. Instead a small `OpenApiCustomizer` bean (`SettingsPatchNullEnums`, in SettingsSchemas.kt) appends a real `null` to every enum of the `SettingsPatch` properties, keeping `types` and `description`. The YAML now lists `- null` (YAML null). `npm --prefix web run api:gen` produced no change (the generated TS model already typed these as `... | null`). New test `the documented patch allows null for the enum settings` checks `/v3/api-docs`; it fails with the customizer disabled (checked) and passes with it.

Validation:
- `cd server && UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'` — Pass (regenerated)
- `cd server && ./gradlew test --tests '*OpenApiContractTest'` — Pass (without the flag)
- `npm --prefix web run api:gen` — no changes in `web/src/app/api/generated/`
- `npm --prefix web run check` — Pass

### CR-3 — A malformed `overrides` document breaks every settings read

Files changed:
- `server/src/main/resources/db/migration/core/V202610201145__core_settings_overrides_object.sql` (new)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/settings/SettingsEdgeCaseTests.kt`

Problem:
The `overrides` jsonb column accepted any JSON value; a non-object made every settings read throw (500).

Fix:
New Flyway migration (the applied V202610201130 one is unchanged) adding `constraint user_settings_overrides_object check (jsonb_typeof(overrides) = 'object')`. `readOverrides` was not changed: with the constraint a non-object can no longer be stored, so a tolerant read path would be unreachable code. New test `the stored overrides must be a JSON object` asserts that writing `[]`, JSON `null` and a string is rejected and the settings still read correctly.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.settings.*' --tests '*MigrationConvention*'` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.settings.*' --tests '*OpenApiContractTest' --tests '*MigrationConvention*' ktlintCheck` — Pass (SettingTypeTests 10, SettingsServiceTests 6, SettingsWebTests 6, SettingsEdgeCaseTests 18, OpenApiContractTest 1, MigrationConventionTests 2; 0 failures)

Full test suite:
- `cd server && ./gradlew check` — Pass (705 tests, 0 failures)
- `npm --prefix web run check` — Pass

Lint/type-check/build:
- `cd server && ./gradlew ktlintFormat ktlintCheck` — Pass

`.taskcheck`:
- Pass (`sh scripts/check.sh` -> "all checks passed", server, web and android steps)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
