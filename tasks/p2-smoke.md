---
title: Phase 2 demo script incl. restore on a fresh install
status: in_progress
owner: dell26/agent-2
deps: [web-files-upload-preview,web-notes-editor,web-settings-backup,web-gtd-convert-note,backup-contrib-gtd,backup-contrib-notes,backup-contrib-files,p1-smoke]
touches: [web/e2e/phase2,deploy/docker-compose.e2e.yml]
prio: 1
lease_until: 2026-10-21T08:01:52Z
---
## Goal
Automate SPEC.md §13.2 Phase 2, including wipe + restore into a fresh stack.

## Acceptance criteria
- [ ] `web/e2e/phase2/*.spec.ts` covers steps 1–5; step 5 restores the downloaded zip into a fresh stack (new volumes) and compares data via the API
- [ ] `readable/` content of the backup checked (Markdown files exist, image links resolve inside the zip)
- [ ] Phase 0–1 specs still pass

## Notes
