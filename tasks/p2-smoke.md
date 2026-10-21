---
title: Phase 2 demo script incl. restore on a fresh install
status: in_progress
owner: dell26/agent-2
deps: [web-files-upload-preview,web-notes-editor,web-settings-backup,web-gtd-convert-note,backup-contrib-gtd,backup-contrib-notes,backup-contrib-files,p1-smoke]
touches: [web/e2e/phase2,deploy/docker-compose.e2e.yml]
prio: 1
lease_until: 2026-10-21T08:04:10Z
---
## Goal
Automate SPEC.md §13.2 Phase 2, including wipe + restore into a fresh stack.

## Acceptance criteria
- [ ] `web/e2e/phase2/*.spec.ts` covers steps 1–5; step 5 restores the downloaded zip into a fresh stack (new volumes) and compares data via the API
- [ ] `readable/` content of the backup checked (Markdown files exist, image links resolve inside the zip)
- [ ] Phase 0–1 specs still pass

## Notes
- ASSUMPTION (2026-10-21 09:48+0200 by dell26/agent-2): Fresh install for step 5 = a second server + Postgres (server-fresh/postgres-fresh) in deploy/docker-compose.e2e.yml, started with the stack and used only by phase2; run-e2e.mjs (outside touches, small edit) waits for it and passes E2E_FRESH_BASE_URL. Step 5 is skipped when that variable is missing (E2E_BASE_URL runs without it).
