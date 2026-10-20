---
title: User settings API
status: in_progress
owner: dell26/agent-5
deps: [p0-auth-login]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/settings,server/src/test/kotlin/ch/sonensei/cockpit/core/settings,server/src/main/resources/db/migration/core-settings]
prio: 1
lease_until: 2026-10-20T11:54:06Z
---
## Goal
Key-value user settings with typed defaults (SPEC.md §4.7).

## Acceptance criteria
- [ ] `GET /api/v1/settings` returns all settings with defaults (timeZone `Europe/Zurich`, weekStart, theme `system`, energy/creativity labels, mail image policy, …); `PATCH /api/v1/settings` validates known keys and types (unknown key → 400)
- [ ] A `SettingsService` lets modules read settings and register their own keys with defaults
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.settings.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-20 13:36+0200 by dell26/agent-5): Migration lives in db/migration/core/ (V202610201130__core_settings.sql), not core-settings/ as in touches: MigrationConventionTests requires folder == [a-z0-9]+ module token of the filename
- ASSUMPTION (2026-10-20 13:36+0200 by dell26/agent-5): Storage: one user_settings row with a jsonb object of changed keys only; unset/reset/no-longer-valid values read as defaults. The row version is the ETag of the whole settings document; PATCH requires If-Match (428/412) like every PATCH (SPEC 4.2); no row = ETag "0"
