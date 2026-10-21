---
title: Backup contributor: GTD
status: in_progress
owner: dell26/agent-1
deps: [backup-contrib-core,gtd-convert-note]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd/backup,server/src/test/kotlin/ch/sonensei/cockpit/gtd/backup]
prio: 1
lease_until: 2026-10-21T07:22:33Z
extended: dell26/agent-5
---
## Goal
GTD data in backups, with readable Markdown (SPEC.md §10.2).

## Acceptance criteria
- [x] data/tasks.jsonl, projects.json, areas.json, contexts.json, checklist_items.jsonl
  - verified (2026-10-21 08:28+0200 by dell26/agent-5): cd server && ./gradlew test --rerun --tests '*GtdBackupContributorTests' -> 21/21 pass; 'tasks jsonl holds every task ...' and 'projects, areas, contexts and checklist items are exported ...' check data/tasks.jsonl, projects.json, areas.json, contexts.json, checklist_items.jsonl contents; 'import restores every row ...' checks re-import
- [x] readable/tasks/{inbox,next,waiting,someday,logbook}.md and readable/projects/<area>/<project>.md with checkboxes
  - verified (2026-10-21 08:28+0200 by dell26/agent-5): cd server && ./gradlew test --rerun --tests '*GtdBackupContributorTests' -> 21/21 pass; exact-Markdown tests for readable/tasks/{inbox,next,waiting,someday,logbook}.md and readable/projects/<area>/<project>.md with - [ ]/- [x] checkboxes (incl. No area, name sanitizing)
- [x] Round-trip test (`./gradlew test --tests '*BackupRoundTripTest'`) passes with this module's `BackupFixture` seeding tasks in every list, recurring tasks, projects with next steps, areas, contexts
  - verified (2026-10-21 08:28+0200 by dell26/agent-5): cd server && ./gradlew test --rerun --tests '*BackupRoundTripTest' -> 1/1 pass (0 failures) with GtdBackupFixture seeding all 5 lists, tickler, FIXED+AFTER_COMPLETION recurrence, projects with next steps, 2 areas, 2 contexts, checklist

## Notes
- ASSUMPTION (2026-10-21 07:23+0200 by dell26/agent-5): Readable GTD mirrors exclude the Trash; SCHEDULED tasks go into a 'Scheduled' section of tasks/next.md (SPEC lists no scheduled.md); tickler tasks stay in their list with 'Hidden until'; logbook = all done tasks newest first; projects without area go to readable/projects/No area/; area/project titles become file names with path separators and control chars replaced, case-insensitive clashes numbered ' (2)'.
- ASSUMPTION (2026-10-21 07:23+0200 by dell26/agent-5): Task contexts (task_context rows) are exported inside each tasks.jsonl record as sorted contextIds rather than a separate data file, matching the SPEC 10.2 file list.
- ASSUMPTION (2026-10-21 08:03+0200 by dell26/agent-5): Readable file names also replace Windows-invalid :*?"<>| with a space, trim trailing dots/spaces, and are cut at 100 code points (not UTF-16 chars); Windows reserved names like CON/NUL are not handled. waiting.md is ordered by waitingSince (nulls last), then position, id.
- extended by dell26/agent-5: Implementation, fixes (TW-1, CR-1..3) and re-review are done and committed (review.md: 0 P0-P2, 1 optional P3: Integer::class.java warning at GtdBackupContributor.kt:279). Left: fix P3, re-run test-writer on final code so tests.md is committed (it is missing), short code-reviewer re-check, verify criteria 1-3 (incl. ./gradlew test --tests '*BackupRoundTripTest'), write quality.json, .taskcheck (server stage is slow here, >10 min), done.
