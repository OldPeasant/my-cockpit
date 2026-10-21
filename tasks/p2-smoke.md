---
title: Phase 2 demo script incl. restore on a fresh install
status: in_progress
owner: dell26/agent-2
deps: [web-files-upload-preview,web-notes-editor,web-settings-backup,web-gtd-convert-note,backup-contrib-gtd,backup-contrib-notes,backup-contrib-files,p1-smoke]
touches: [web/e2e/phase2,deploy/docker-compose.e2e.yml]
prio: 1
lease_until: 2026-10-21T08:47:39Z
---
## Goal
Automate SPEC.md §13.2 Phase 2, including wipe + restore into a fresh stack.

## Acceptance criteria
- [x] `web/e2e/phase2/*.spec.ts` covers steps 1–5; step 5 restores the downloaded zip into a fresh stack (new volumes) and compares data via the API
  - verified (2026-10-21 10:11+0200 by dell26/agent-2): npm --prefix web run e2e -- --project=phase-demos (support/run-e2e.mjs: deploy/docker-compose.e2e.yml via podman-compose incl. server-fresh + postgres-fresh, chromium) at 19092c54 -> 21 passed; phase2 steps 0-5 all ran (none skipped): step 5 sets up a throwaway owner on server-fresh, uploads the downloaded backup zip in Settings > Backup & restore, confirms, then compares an API snapshot (settings, contexts, areas, projects, tasks, logbook, checklists, notes, drive tree, file content sha256, links per item, trash) with the original server -> equal; recovery-code login forces TOTP re-enrollment (403 elsewhere), new TOTP web login works
- [x] `readable/` content of the backup checked (Markdown files exist, image links resolve inside the zip)
  - verified (2026-10-21 10:11+0200 by dell26/agent-2): same run, phase2 step 4: backup zip read with e2e/phase2/zip.ts -> readable/tasks/{inbox,next,waiting,someday,logbook}.md and readable/projects/Home/*.md contain the seeded tasks, readable/notes/<date>-*.md has the note text, its image link resolves (relative, URL-decoded) to files/... with the pasted PNG bytes, every image link in readable/**/*.md resolves to a zip entry, files/Documents/Tax/Invoice 2025.pdf, photo.png and archive.bin (sha256) present -> passed
- [x] Phase 0–1 specs still pass
  - verified (2026-10-21 10:11+0200 by dell26/agent-2): same run: phase-demos project runs phase0 (5 tests) and phase1 (10 tests) before phase2 -> all passed (21 passed total, also in the test-writer's independent rerun at 19092c54); sh scripts/check.sh -> all checks passed

## Notes
- ASSUMPTION (2026-10-21 09:48+0200 by dell26/agent-2): Fresh install for step 5 = a second server + Postgres (server-fresh/postgres-fresh) in deploy/docker-compose.e2e.yml, started with the stack and used only by phase2; run-e2e.mjs (outside touches, small edit) waits for it and passes E2E_FRESH_BASE_URL. Step 5 is skipped when that variable is missing (E2E_BASE_URL runs without it).
- ASSUMPTION (2026-10-21 09:48+0200 by dell26/agent-2): Restore on the fresh install goes through the web UI (Settings > Backup & restore) after a throwaway setup account, since the restore API needs a session; the CLI --restore path is not exercised here.
- ASSUMPTION (2026-10-21 09:48+0200 by dell26/agent-2): TOTP re-enrollment after restore without passphrase is verified via the API (recovery-code login -> totpEnrollmentRequired, 403 elsewhere, enroll, then web login with the new secret): the web enrollment screen is task web-totp-reenroll, not done yet.
- ASSUMPTION (2026-10-21 09:48+0200 by dell26/agent-2): Phase 1 data for the round trip is seeded through the API in phase2 step 0 (phase1's script resets the stack at its end, so its data is gone by phase 2).
