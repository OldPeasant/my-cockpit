# Code Review

Date: 2026-10-20

Task: p0-settings - User settings API

Scope: task commits ec2544f and 2acd96c (`git diff 85dc521..2acd96c`), committed revision 2acd96c only

Requirements:
- tasks/p0-settings.md acceptance criteria and recorded assumptions
- SPEC.md §4.2 (UUIDv7, version/ETag/If-Match, Problem Details) and §4.7 (settings)
- docs/ARCHITECTURE.md (server package conventions), AGENTS.md project notes, api/README.md
- Existing conventions: core/web/ETags.kt, core/web/ProblemDetailsAdvice.kt, core/persistence/BaseEntity.kt, core/auth

Git range:
- Branch: main
- HEAD: 2acd96cfe5bb9ae689f298b80f57852639f9fcb1
- Base/start: 85dc52177d1680c601860c7da275755e44ca580c
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 1 P2, 2 P3

Verified as correct:
- GET returns every registered key with defaults (timeZone Europe/Zurich, weekStart MONDAY, theme system, energy/creativity labels, mailImagePolicy block, mailImageAllowedSenders []) and an ETag; PATCH validates every key and type, rejects unknown keys and invalid values with one FieldError per key (400, Problem Details) and stores nothing.
- SettingsService: modules register keys as SettingDefinition beans or via register() (duplicates rejected, same instance idempotent), read them type-safely with get(); SettingsChangedEvent carries only actually changed keys.
- Optimistic locking: If-Match is checked against the row version (428 missing, 412 stale); concurrent updates of the existing row fail on the JPA version check, mapped to 409 by ProblemDetailsAdvice. The first-create race is guarded by the unique index on `((true))`, and the duplicate insert becomes 409 via the DataIntegrityViolationException catch in create().
- Stored values that a definition no longer accepts, and keys that are no longer registered, read as defaults and do not leak into the response.
- Migration follows the naming convention (folder deviation from `touches` recorded as an assumption); entity uses BaseEntity (UUIDv7, timestamps, version). Only keys are logged, at DEBUG. Endpoints require authentication (tested).
- api/openapi.yaml and the generated web client match the controller (paths, operation ids, If-Match header, 400/409/412/428 responses, Settings/SettingsPatch schemas).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | The 409 conflict paths (first-create race, concurrent update) have no tests**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/settings/domain/SettingsService.kt:123`
    - Category: Tests
    - Problem: `create()` relies on the `user_settings_single_row` unique index plus the `DataIntegrityViolationException` -> `ConflictException` translation, and concurrent updates of an existing row rely on the `@Version` check raising `OptimisticLockingFailureException`. Neither SettingsServiceTests nor SettingsWebTests exercises these paths: no test inserts a competing row or performs a stale-version save, so a regression (e.g. the index being dropped, a different exception type surfacing, or `checkVersion` being bypassed) would produce a second settings row or a 500 without any test failing. The 409 response is part of the documented API contract.
    - Why it matters: this concurrency logic is subtle and regression-prone. The single-row invariant is what keeps `findFirstBy()` deterministic.
    - Suggested fix: add Testcontainers tests that (a) insert a row through JdbcTemplate after the service has read "no row" (or call `update` with a `checkVersion` callback that inserts the competing row) and assert `ConflictException` with nothing stored, and (b) bump `version` through JdbcTemplate inside the `checkVersion` callback and assert a 409 / `OptimisticLockingFailureException`. Also assert that a second raw insert into `user_settings` violates the unique index.

## P3 Low

- [ ] **CR-2 | SettingsPatch schema: `null` is allowed by `type` but rejected by `enum`**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/settings/api/SettingsSchemas.kt:43`
    - Category: Integration
    - Problem: `weekStart`, `theme` and `mailImagePolicy` in SettingsPatch are declared as `type: [string, "null"]` with an `enum` that does not contain `null` (api/openapi.yaml, SettingsPatch). In JSON Schema / OpenAPI 3.1, `enum` applies independently of `type`, so `{"theme": null}`, which is the documented reset operation, is invalid against the published schema. The generated TypeScript happens to allow `null`, but strict validators or other generators (e.g. a future Android client) will not.
    - Why it matters: the documented reset semantics contradict the published contract for these three keys.
    - Suggested fix: include `null` in the allowable values (or describe these properties with `oneOf`/`anyOf` of the enum and `null`), regenerate api/openapi.yaml and the web client.

- [ ] **CR-3 | A malformed `overrides` document breaks every settings read**
    - File: `server/src/main/resources/db/migration/core/V202610201130__core_settings.sql:9`
    - Category: Correctness
    - Problem: `readOverrides` (SettingsService.kt:136) deserializes `overrides` as `Map<String, Any?>`. The column accepts any jsonb, so a non-object value (`[]`, `null` JSON, a string), for example written by a restore or a manual fix, makes `get()`, `timeZone()`, `current()` and `update()` all throw, producing a 500 on GET/PATCH and breaking every module that reads the time zone. Invalid values inside a valid object are already handled gracefully. A non-object document is not.
    - Why it matters: stored state read back should not be able to break the whole settings feature. The backup/restore tasks (backup-contrib-core) will write this table.
    - Suggested fix: add `check (jsonb_typeof(overrides) = 'object')` to the table, which is cheap since the migration is not yet applied on main, and/or treat a non-object document as empty in `readOverrides`.

## Review conclusion

Material issues found; fix before completing the task. None blocks the acceptance criteria. CR-1 is a test gap that the test-writer can close. CR-2 and CR-3 are small contract and robustness fixes.

Note (not a finding, not verified): `overrides` is a String mapped as JSON. If Hibernate returns PostgreSQL's normalized jsonb text (with spaces), the compact string written back by `update()` differs even for a no-op PATCH, and the version/ETag would advance without a change. A test asserting that a no-op PATCH keeps the version would settle this.
