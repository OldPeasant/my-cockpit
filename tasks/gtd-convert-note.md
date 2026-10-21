---
title: Convert task to reference note
status: in_progress
owner: dell26/agent-5
deps: [notes-api,gtd-capture-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert,server/src/test/kotlin/ch/sonensei/cockpit/gtd/convert]
prio: 2
lease_until: 2026-10-21T03:26:54Z
---
## Goal
'Move to reference' converts a task into a note (SPEC.md §5.1).

## Acceptance criteria
- [ ] `POST /api/v1/tasks/{id}/convert-to-note` creates a note (title, notes as body, links carried over) and deletes the task in one transaction
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.convert.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
