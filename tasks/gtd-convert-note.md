---
title: Convert task to reference note
status: in_progress
owner: dell26/agent-5
deps: [notes-api,gtd-capture-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert,server/src/test/kotlin/ch/sonensei/cockpit/gtd/convert]
prio: 2
lease_until: 2026-10-21T03:57:47Z
---
## Goal
'Move to reference' converts a task into a note (SPEC.md §5.1).

## Acceptance criteria
- [ ] `POST /api/v1/tasks/{id}/convert-to-note` creates a note (title, notes as body, links carried over) and deletes the task in one transaction
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.convert.*'` passes (Postgres via Testcontainers where persistence is involved)
- [ ] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag

## Notes
- ASSUMPTION (2026-10-21 05:14+0200 by dell26/agent-5): Convert soft-deletes the task (Trash) rather than hard-deleting it, so it can be restored; the task keeps its own links in the Trash.
- ASSUMPTION (2026-10-21 05:14+0200 by dell26/agent-5): Links are carried over as copies, all as MANUAL links with the same direction and label (SYNC links owned by other items would be removed by their owner's next sync); links to items that no longer exist/are trashed are skipped.
- ASSUMPTION (2026-10-21 05:14+0200 by dell26/agent-5): Only title and notes move to the note (per criterion); checklist, contexts, dates etc. are not carried over (they stay on the trashed task). Endpoint returns 201 + NoteResponse + Location /api/v1/notes/{id}; If-Match optional like other task workflow actions.
