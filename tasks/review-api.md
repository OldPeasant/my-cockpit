---
title: Weekly review API
status: in_progress
owner: dell26/agent-1
deps: [gtd-projects-api,cal-local-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/review,server/src/test/kotlin/ch/sonensei/cockpit/review,server/src/main/resources/db/migration/review]
prio: 2
lease_until: 2026-10-21T22:34:47Z
---
## Goal
Resumable weekly review (SPEC.md §8.6).

## Acceptance criteria
- [ ] `weekly_review` with step progress; endpoints for step data: stale next actions (> 30 days), waiting-for, projects (stalled first), someday, past/upcoming events; complete review
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.review.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
