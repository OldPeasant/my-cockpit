---
title: Backup contributor: GTD
status: in_progress
owner: dell26/agent-5
deps: [backup-contrib-core,gtd-convert-note]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd/backup,server/src/test/kotlin/ch/sonensei/cockpit/gtd/backup]
prio: 1
lease_until: 2026-10-21T06:25:30Z
---
## Goal
GTD data in backups, with readable Markdown (SPEC.md §10.2).

## Acceptance criteria
- [ ] data/tasks.jsonl, projects.json, areas.json, contexts.json, checklist_items.jsonl
- [ ] readable/tasks/{inbox,next,waiting,someday,logbook}.md and readable/projects/<area>/<project>.md with checkboxes
- [ ] Round-trip test (`./gradlew test --tests '*BackupRoundTripTest'`) passes with this module's `BackupFixture` seeding tasks in every list, recurring tasks, projects with next steps, areas, contexts

## Notes
- ASSUMPTION (2026-10-21 07:23+0200 by dell26/agent-5): Readable GTD mirrors exclude the Trash; SCHEDULED tasks go into a 'Scheduled' section of tasks/next.md (SPEC lists no scheduled.md); tickler tasks stay in their list with 'Hidden until'; logbook = all done tasks newest first; projects without area go to readable/projects/No area/; area/project titles become file names with path separators and control chars replaced, case-insensitive clashes numbered ' (2)'.
- ASSUMPTION (2026-10-21 07:23+0200 by dell26/agent-5): Task contexts (task_context rows) are exported inside each tasks.jsonl record as sorted contextIds rather than a separate data file, matching the SPEC 10.2 file list.
- ASSUMPTION (2026-10-21 08:03+0200 by dell26/agent-5): Readable file names also replace Windows-invalid :*?"<>| with a space, trim trailing dots/spaces, and are cut at 100 code points (not UTF-16 chars); Windows reserved names like CON/NUL are not handled. waiting.md is ordered by waitingSince (nulls last), then position, id.
