---
title: Contacts API
status: in_progress
owner: dell26/agent-2
deps: [p0-items-links,p0-search-core,p0-trash-core,files-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/contacts,server/src/test/kotlin/ch/sonensei/cockpit/contacts,server/src/main/resources/db/migration/contacts]
prio: 2
lease_until: 2026-10-21T02:03:44Z
---
## Goal
Contacts (SPEC.md §8.3).

## Acceptance criteria
- [ ] Contact entity (SPEC.md §8.3 fields), CRUD with ETag, photo via Files, resolver CONTACT, search, trash
- [ ] vCard import (multi-contact file) and export (ez-vcard)
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
