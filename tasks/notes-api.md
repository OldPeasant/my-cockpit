---
title: Notes API
status: in_progress
owner: dell26/agent-3
deps: [files-api,p0-search-core,p0-trash-core,p0-events-sse,runner-version-skew]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/notes,server/src/test/kotlin/ch/sonensei/cockpit/notes,server/src/main/resources/db/migration/notes]
prio: 1
lease_until: 2026-10-20T23:42:34Z
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
- ASSUMPTION (2026-10-21 01:10+0200 by dell26/agent-3): Note history: before a title/body change the previous content is kept as a revision, but at most one per 5 minutes (autosave sessions keep the pre-session state, not every keystroke); a revision restore always keeps the replaced content. Revisions are earlier versions only (current content not listed); numbered per note; newest 20 kept.
- ASSUMPTION (2026-10-21 01:10+0200 by dell26/agent-3): GET /api/v1/notes defaults to non-archived notes; archived=true lists only archived, archived=all both. Order: pinned first, then updatedAt desc, id desc.
- ASSUMPTION (2026-10-21 01:10+0200 by dell26/agent-3): Tags: trimmed, leading # removed, unique per note regardless of case, max 50 tags of 50 chars; a new tag reuses the spelling other notes already use; tag filter is case-insensitive. Added GET /api/v1/notes/tags (tag + count) for the tag filter UI.
- ASSUMPTION (2026-10-21 01:10+0200 by dell26/agent-3): Derived title: first line with text of the body as flexmark plain text, max 100 chars, 'Untitled note' if none; API returns title (nullable, as set) and displayTitle. POST /notes/{id}/images accepts only image/* mime types, stores via FilesService into /Attachments/Notes, does not change the note, and returns fileId, reference cockpit-file:{id} and markdown. No client-chosen ids for notes (no offline create).
