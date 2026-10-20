# Code Review

Date: 2026-10-20

Task: p0-settings - User settings API

Scope: final task revision 7b196531e61c513515f7189d75190dd427afee6d against its merge-base with origin/main (task commits bde946a, 9eaa88d, 9b86aa8, 7b19653), excluding `reviews/` and `tasks/`. Pass 2: verifies the fixes for TW-1 and CR-1..CR-3 and re-reviews the whole task change.

Requirements:
- tasks/p0-settings.md acceptance criteria and recorded assumptions
- SPEC.md §4.2 (UUIDv7, version/ETag/If-Match, Problem Details) and §4.7 (settings)
- docs/ARCHITECTURE.md (server packages, persistence and migration conventions, API contract), AGENTS.md project notes
- Pass-1 review (CR-1..CR-3), reviews/p0-settings/tests.md (TW-1), reviews/p0-settings/fixes.md

Git range:
- Branch: main
- HEAD: 7b196531e61c513515f7189d75190dd427afee6d (confirmed from `.git/refs/heads/main`)
- Base/start: merge-base of HEAD and origin/main (pass-1 base 85dc52177d1680c601860c7da275755e44ca580c)
- Commits reviewed: 4

Method note: shell access was not available to this reviewer, so `git diff` could not be run. The review read every task file in full at the current checkout (HEAD = 7b19653): `core/settings/api/SettingsController.kt`, `api/SettingsSchemas.kt`, `domain/SettingDefinition.kt`, `domain/SettingsService.kt`, `domain/UserSettings.kt`, `infra/UserSettingsRepository.kt`, migrations `core/V202610201130__core_settings.sql` and `core/V202610201145__core_settings_overrides_object.sql`, `api/openapi.yaml`, and `SettingsEdgeCaseTests.kt`. The parent stated that only test files under `core/settings` may differ from HEAD in the working tree, so the production files read are taken to be the committed revision. Test results were not re-run here. They come from the fixer and test-writer reports.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Pass-1 findings and their resolution

- **TW-1 | A no-op PATCH bumped the ETag**: fixed. `SettingsService.update()` (SettingsService.kt:88-105) now reassigns `overrides` only when a key changed or a stored key was removed on reset, so Hibernate sees a clean entity and keeps the version. The paths were checked: setting the current value, setting the default while unset, and resetting an unset key all leave the entity clean. Resetting a stored value that is no longer valid removes it without publishing an event. Covered by `setting a key to the value it already has keeps the ETag` and `resetting a stored value its definition no longer accepts removes it`.
- **CR-1 | 409 conflict paths untested**: resolved. The first-row race is covered by `there is at most one settings row` (unique index) and `a concurrently created settings row is a conflict` (DataIntegrityViolationException -> ConflictException). The concurrent update is covered by `a concurrent change of the existing settings is a conflict and changes nothing`. That test bumps `version` inside the `checkVersion` callback, asserts `OptimisticLockingFailureException`, and checks that version and value are unchanged afterwards.
- **CR-2 | SettingsPatch enums excluded `null`**: fixed. `SettingsPatchNullEnums` (SettingsSchemas.kt:65-84) appends a real `null` to every enum in SettingsPatch. It is idempotent and keeps `types` and `description`. api/openapi.yaml:409-444 now lists `- null` for mailImagePolicy, theme and weekStart. The `Settings` response schema is unchanged, which is correct. Covered by `the documented patch allows null for the enum settings`.
- **CR-3 | A malformed `overrides` document broke every read**: fixed. A new migration, `V202610201145__core_settings_overrides_object.sql`, adds `check (jsonb_typeof(overrides) = 'object')`. The applied migration was not edited, and the folder and module token follow the convention. Together with `not null`, the column can only hold an object, so `readOverrides` cannot meet a non-object document. Covered by `the stored overrides must be a JSON object` (`[]`, JSON `null` and a string are rejected).

## Re-review of the whole change

Verified as correct:
- AC1: GET returns every registered key with its default (timeZone Europe/Zurich, weekStart MONDAY, theme system, energy/creativity labels, mailImagePolicy block, mailImageAllowedSenders []) and an ETag. PATCH validates every key and type, answers 400 Problem Details with one FieldError per unknown key or invalid value (a `null` for an unknown key is still unknown), and stores nothing on error. Validation runs before the If-Match check.
- AC2: `SettingsService.register()` (ConcurrentHashMap, duplicate key rejected, same instance idempotent) and `SettingDefinition` beans collected at startup. `get()` is type-safe and requires a registered definition. `SettingsChangedEvent` carries only the keys whose value changed.
- SPEC §4.2: PATCH requires If-Match (428 when missing, 412 when stale, through `ETags`). The version is the ETag of the whole document, with `"0"` when there is no row. A concurrent first create gives 409 through the unique index, and a concurrent update gives 409 through `@Version`. The entity extends BaseEntity (UUIDv7, timestamps, version).
- Stored values that a definition no longer accepts, and keys that are no longer registered, read as defaults. They are not returned and are not lost on other changes.
- Security: the endpoints are under the authenticated `/api/v1` (tested by `settings need a login`). Only keys are logged, at DEBUG. No values or secrets are logged. No unsafe query or HTML construction.
- AC4: api/openapi.yaml matches the controller (paths, operation ids, the If-Match header, 200/400/409/412/428, Settings/SettingsPatch with additionalProperties for module keys). The fixer reports the generated web client unchanged after `npm run api:gen`.
- Architecture: the `core.settings.{api,domain,infra}` split is respected. The schema change is a new migration. The `SettingsSchemas` classes are documentation-only and clearly marked as such.

Observation (not a finding): `SettingsChangedEvent` is published inside the update transaction, before commit. No listener exists yet. A future listener that must see the committed value (e.g. rescheduling daily jobs on a timeZone change) should use `@TransactionalEventListener(phase = AFTER_COMMIT)`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. All pass-1 findings (CR-1..CR-3) and TW-1 are resolved in revision 7b19653.
