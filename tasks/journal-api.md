---
title: Journal API
status: in_progress
owner: dell26/agent-2
deps: [p0-items-links,p0-search-core,p0-trash-core,files-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/journal/entries,server/src/test/kotlin/ch/sonensei/cockpit/journal/entries,server/src/main/resources/db/migration/journal/entries]
prio: 3
lease_until: 2026-10-21T23:30:21Z
---
## Goal
Daily journal (SPEC.md §8.5).

## Acceptance criteria
- [ ] One entry per date (upsert by date), Markdown with images (Attachments/Journal), mood, tags; heatmap endpoint (dates with entries); 'on this day'
- [ ] Resolver JOURNAL_ENTRY, search, trash
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.journal.entries.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
