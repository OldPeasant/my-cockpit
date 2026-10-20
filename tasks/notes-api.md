---
title: Notes API
status: todo
owner:
deps: [files-api,p0-search-core,p0-trash-core,p0-events-sse,runner-version-skew]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/notes,server/src/test/kotlin/ch/sonensei/cockpit/notes,server/src/main/resources/db/migration/notes]
prio: 1
---
## Goal
Markdown notes with images, tags, pins and history (SPEC.md §8.1).

## Acceptance criteria
- [ ] CRUD `/api/v1/notes` (title optional → derived from first line; body_md, tags, pinned, archived) with ETag; list filter by tag/pinned/archived
- [ ] Inline links `[[type:id]]` in the body are synced to the link table on save; `POST /api/v1/notes/{id}/images` stores into `/Attachments/Notes` and returns the `cockpit-file:{id}` reference
- [ ] Last 20 revisions kept (`GET …/revisions`, `POST …/revisions/{n}/restore`)
- [ ] Resolver NOTE, search indexing (Markdown stripped via flexmark), trash, ChangeEvents
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.notes.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
