---
title: Convert task to reference note
status: todo
owner: 
deps: [notes-api,gtd-capture-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd/convert,server/src/test/kotlin/ch/sonensei/cockpit/gtd/convert]
prio: 2
lease_until: 
releases: 1
extended: 
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
- release by dell26/agent-5: Implementation, tests, web client regen and all quality reports (tests.md, review.md final re-review with 0 findings, fixes.md) are committed (HEAD bad15813); sh scripts/check.sh passes. Left (finalization only): run 'cd server && ./gradlew test --tests ch.sonensei.cockpit.gtd.convert.*' (Testcontainers) and the OpenApiContractTest with and without UPDATE_OPENAPI=1, record evidence for criteria 1-3 with tasks.py verify, write+commit reviews/gtd-convert-note/quality.json (reviewed_revision = current HEAD; review.md names pre-rebase hash b65f2b89, patch-ids unchanged; fixer ran for TW-1/CR-1), then tasks.py done.
