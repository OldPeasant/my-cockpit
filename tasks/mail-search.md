---
title: Mail search
status: todo
owner:
deps: [mail-read-api,p0-search-core]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/search,server/src/test/kotlin/ch/sonensei/cockpit/mail/search]
prio: 2
---
## Goal
Find mail (SPEC.md §7.3).

## Acceptance criteria
- [ ] Cached headers and bodies indexed in search_document; `GET /api/v1/mail/search?q=&server=true` additionally runs IMAP SEARCH on the server
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.search.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
