---
title: Quick capture endpoint and syntax parser
status: todo
owner:
deps: [gtd-tickler]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd,server/src/test/kotlin/ch/sonensei/cockpit/gtd,server/src/main/resources/db/migration/gtd]
prio: 1
---
## Goal
Idempotent capture used by web and Android (SPEC.md §4.5, §5.4).

## Acceptance criteria
- [ ] `POST /api/v1/capture {id?, text, notes?, fileIds?, parse?}` creates an INBOX task; a repeated client `id` returns 200 with the existing task (no duplicate, test)
- [ ] Parser (pure function, unit-tested): `#project` (existing by name, case-insensitive, else kept as text), `@context`, `!today|!tomorrow|!YYYY-MM-DD` due, `^waiting`; `POST /api/v1/capture/preview` returns the parse result
- [ ] `fileIds` are linked to the task via the link system
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
