---
title: User settings API
status: todo
owner:
deps: [p0-auth-login]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/settings,server/src/test/kotlin/ch/sonensei/cockpit/core/settings,server/src/main/resources/db/migration/core-settings]
prio: 1
---
## Goal
Key-value user settings with typed defaults (SPEC.md §4.7).

## Acceptance criteria
- [ ] `GET /api/v1/settings` returns all settings with defaults (timeZone `Europe/Zurich`, weekStart, theme `system`, energy/creativity labels, mail image policy, …); `PATCH /api/v1/settings` validates known keys and types (unknown key → 400)
- [ ] A `SettingsService` lets modules read settings and register their own keys with defaults
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.settings.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
