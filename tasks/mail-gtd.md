---
title: Mail to GTD integration
status: todo
owner:
deps: [mail-actions-api,gtd-capture-api,notes-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd,server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd]
prio: 1
---
## Goal
Turn mail into tasks, waiting-fors, notes (SPEC.md §7.3).

## Acceptance criteria
- [ ] `POST /api/v1/mail/messages/{id}/to-task|to-waiting|to-note` create linked items (waiting-for uses the recipient of a sent mail)
- [ ] Links survive moves (Message-ID re-resolution, test)
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.gtd.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
