---
title: User settings API
status: in_progress
owner: dell26/agent-5
deps: [p0-auth-login]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/settings,server/src/test/kotlin/ch/sonensei/cockpit/core/settings,server/src/main/resources/db/migration/core-settings]
prio: 1
lease_until: 2026-10-20T12:19:20Z
---
## Goal
Key-value user settings with typed defaults (SPEC.md §4.7).

## Acceptance criteria
- [x] `GET /api/v1/settings` returns all settings with defaults (timeZone `Europe/Zurich`, weekStart, theme `system`, energy/creativity labels, mail image policy, …); `PATCH /api/v1/settings` validates known keys and types (unknown key → 400)
  - verified (2026-10-20 14:01+0200 by dell26/agent-5): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.settings.*' --rerun (Testcontainers Postgres) -> SettingsWebTests 6/6 incl. 'GET returns every setting with its default and an ETag' (timeZone Europe/Zurich, weekStart MONDAY, theme system, energy/creativity labels, mailImagePolicy) and 'an unknown key or an invalid value is a 400 per key and changes nothing'; SettingsEdgeCaseTests 20/20
- [x] A `SettingsService` lets modules read settings and register their own keys with defaults
  - verified (2026-10-20 14:01+0200 by dell26/agent-5): same run -> SettingsServiceTests 6/6 incl. 'modules register their own settings, as beans or at runtime' and typed get(); SettingTypeTests 10/10
- [x] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.settings.*'` passes (Postgres via Testcontainers where persistence is involved)
  - verified (2026-10-20 14:01+0200 by dell26/agent-5): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.settings.*' --rerun -> BUILD SUCCESSFUL, 44 tests (10+20+6+6+2), 0 failures/skips, Postgres via Testcontainers
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-20 13:36+0200 by dell26/agent-5): Migration lives in db/migration/core/ (V202610201130__core_settings.sql), not core-settings/ as in touches: MigrationConventionTests requires folder == [a-z0-9]+ module token of the filename
- ASSUMPTION (2026-10-20 13:36+0200 by dell26/agent-5): Storage: one user_settings row with a jsonb object of changed keys only; unset/reset/no-longer-valid values read as defaults. The row version is the ETag of the whole settings document; PATCH requires If-Match (428/412) like every PATCH (SPEC 4.2); no row = ETag "0"
- ASSUMPTION (2026-10-20 13:36+0200 by dell26/agent-5): PATCH semantics: JSON object of keys to set; null resets a key to its default; unknown key or invalid value -> 400 with one FieldError per key and nothing stored
- ASSUMPTION (2026-10-20 13:36+0200 by dell26/agent-5): Core keys and defaults: timeZone Europe/Zurich, weekStart MONDAY (DayOfWeek names), theme light|dark|system=system, energyLabels {LOW,MEDIUM,HIGH}, creativityLabels {ROUTINE,MIXED,CREATIVE} (all keys required, <=40 chars), mailImagePolicy block|allow=block, mailImageAllowedSenders []. Default views (calendar etc.) are left to their modules to register; contexts are GTD entities, not a setting
- ASSUMPTION (2026-10-20 13:37+0200 by dell26/agent-5): Modules register keys as SettingDefinition beans (collected at startup) or SettingsService.register(); SettingsChangedEvent(keys) is published after a change. OpenAPI documents the core keys via Settings/SettingsPatch schemas with additionalProperties for module keys
