---
title: Notes API
status: in_progress
owner: dell26/agent-2
deps: [files-api,p0-search-core,p0-trash-core,p0-events-sse,runner-version-skew]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/notes,server/src/test/kotlin/ch/sonensei/cockpit/notes,server/src/main/resources/db/migration/notes]
prio: 1
lease_until: 2026-10-21T01:42:07Z
extended: 
releases: 1
---
## Goal
Markdown notes with images, tags, pins and history (SPEC.md §8.1).

## Acceptance criteria
- [x] CRUD `/api/v1/notes` (title optional → derived from first line; body_md, tags, pinned, archived) with ETag; list filter by tag/pinned/archived
  - verified (2026-10-21 02:21+0200 by dell26/agent-3): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.notes.*' --tests '*OpenApiContractTest' (Postgres via Testcontainers) -> 70 tests, 0 failures; NoteWebTests/NoteEdgeCaseWebTests cover CRUD, derived title, ETag/If-Match 428/412, tag/pinned/archived filters incl. archived=all and 400 on invalid
  - verified (2026-10-21 03:26+0200 by dell26/agent-2): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.notes.*' --tests '*OpenApiContractTest' at 26322171 on top of main (Postgres Testcontainers) -> BUILD SUCCESSFUL; NoteWebTests 20 + NoteEdgeCaseWebTests 26 pass: CRUD, derived title, ETag/If-Match 428/412, tag/pinned/archived filters
- [x] Inline links `[[type:id]]` in the body are synced to the link table on save; `POST /api/v1/notes/{id}/images` stores into `/Attachments/Notes` and returns the `cockpit-file:{id}` reference
  - verified (2026-10-21 02:21+0200 by dell26/agent-3): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.notes.*' --tests '*OpenApiContractTest' (Postgres via Testcontainers) -> 70 tests, 0 failures; NoteWebTests/NoteInfraTests cover [[type:id]] link sync on create/update/restore and POST /notes/{id}/images storing under /Attachments/Notes returning cockpit-file:{id} (+ NoteImageMaxSizeWebTests)
  - verified (2026-10-21 03:26+0200 by dell26/agent-2): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.notes.*' --tests '*OpenApiContractTest' at 26322171 on top of main (Postgres Testcontainers) -> pass; NoteWebTests inline link sync + image upload into /Attachments/Notes returning cockpit-file:{id}; NoteImageMaxSizeWebTests 1
- [x] Last 20 revisions kept (`GET …/revisions`, `POST …/revisions/{n}/restore`)
  - verified (2026-10-21 02:21+0200 by dell26/agent-3): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.notes.*' --tests '*OpenApiContractTest' (Postgres via Testcontainers) -> 70 tests, 0 failures; NoteRevisionInfraTests/NoteWebTests cover 20-revision cap, GET revisions, POST revisions/{n}/restore, concurrent body/restore changes -> one wins with 409
  - verified (2026-10-21 03:26+0200 by dell26/agent-2): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.notes.*' --tests '*OpenApiContractTest' at 26322171 on top of main (Postgres Testcontainers) -> pass; NoteWebTests 'only the newest 20 revisions are kept' + restore; NoteRevisionInfraTests 5
- [x] Resolver NOTE, search indexing (Markdown stripped via flexmark), trash, ChangeEvents
  - verified (2026-10-21 02:21+0200 by dell26/agent-3): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.notes.*' --tests '*OpenApiContractTest' (Postgres via Testcontainers) -> 70 tests, 0 failures; NoteInfraTests/NoteMarkdownTests cover NOTE resolver, flexmark-stripped search document, trash delete/restore re-index, ChangeEvents
- [x] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.notes.*'` passes (Postgres via Testcontainers where persistence is involved)
  - verified (2026-10-21 02:21+0200 by dell26/agent-3): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.notes.*' -> BUILD SUCCESSFUL, 69 notes tests (NoteWeb 20, NoteEdgeCaseWeb 26, NoteInfra 9, NoteRevisionInfra 5, NoteMarkdown 8, NoteImageMaxSize 1), 0 failures; persistence tests on Postgres Testcontainers
- [x] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag
  - verified (2026-10-21 02:21+0200 by dell26/agent-3): UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest' regenerated api/openapi.yaml (+ npm run api:gen); then ./gradlew test --tests '*OpenApiContractTest' without flag -> pass; cd web && npm run check -> pass

## Notes
- ASSUMPTION (2026-10-21 01:10+0200 by dell26/agent-3): Note history: before a title/body change the previous content is kept as a revision, but at most one per 5 minutes (autosave sessions keep the pre-session state, not every keystroke); a revision restore always keeps the replaced content. Revisions are earlier versions only (current content not listed); numbered per note; newest 20 kept.
- ASSUMPTION (2026-10-21 01:10+0200 by dell26/agent-3): GET /api/v1/notes defaults to non-archived notes; archived=true lists only archived, archived=all both. Order: pinned first, then updatedAt desc, id desc.
- ASSUMPTION (2026-10-21 01:10+0200 by dell26/agent-3): Tags: trimmed, leading # removed, unique per note regardless of case, max 50 tags of 50 chars; a new tag reuses the spelling other notes already use; tag filter is case-insensitive. Added GET /api/v1/notes/tags (tag + count) for the tag filter UI.
- ASSUMPTION (2026-10-21 01:10+0200 by dell26/agent-3): Derived title: first line with text of the body as flexmark plain text, max 100 chars, 'Untitled note' if none; API returns title (nullable, as set) and displayTitle. POST /notes/{id}/images accepts only image/* mime types, stores via FilesService into /Attachments/Notes, does not change the note, and returns fileId, reference cockpit-file:{id} and markdown. No client-chosen ids for notes (no offline create).
- extended by dell26/agent-3: Implementation done (a8ac0f16): CRUD+ETag, tag/pinned/archived filters, inline link sync, image upload, 20 revisions + restore, resolver/search/trash/events, OpenAPI regenerated; 69 notes tests pass, check.sh passes. Left: re-run test-writer (reviews/notes-api/tests.md missing, with coverage) and code-reviewer on final code after fixes in 33fcd69f, fixer if needed, run OpenApiContractTest, verify criteria 1-6, write quality.json, done.
- release by dell26/agent-3: Implementation complete and all 6 criteria verified; test-writer, code-reviewer and fixer reports plus quality.json committed (reviewed_revision 3d5c5215 = pre-rebase hash of c7fda62e, same patch). Left: only 'python tasks.py done notes-api' (update reviewed_revision to current hash if done complains). Last check.sh run: 2410 tests, 1 failure in core ClockConfigurationTests (flaky: Instant micro vs nano precision, not notes code) - rerun; if it keeps failing, fix/report it separately. check.sh took ~8 min here.
