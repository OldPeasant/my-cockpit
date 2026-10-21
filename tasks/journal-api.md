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
- ASSUMPTION (2026-10-22 01:11+0200 by dell26/agent-2): Journal entries are addressed by date: GET/PUT/DELETE /api/v1/journal/entries/{yyyy-MM-dd}. PUT is the upsert with the whole content (bodyMd, mood, tags; omitted = defaults): 201 without If-Match when the date has no entry (If-Match then 412), 200 with If-Match required to replace; concurrent create = 409. Dates after today (user time zone) are rejected
- ASSUMPTION (2026-10-22 01:11+0200 by dell26/agent-2): mood is an optional integer 1 (bad) to 5 (great); tags are normalized like note tags (trim, leading # removed, case-insensitive unique, max 50x50) but without reusing spellings of other entries
- ASSUMPTION (2026-10-22 01:11+0200 by dell26/agent-2): Images: POST /api/v1/journal/images stores into /Attachments/Journal without needing an existing entry (so Today can paste before first save); body references cockpit-file:{id} like notes. Journal bodies reuse notes' NoteMarkdown (plain text for search, inline [[type:id]] links synced to the link table) instead of duplicating it
