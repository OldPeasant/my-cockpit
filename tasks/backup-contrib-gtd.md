---
title: Backup contributor: GTD
status: todo
owner:
deps: [backup-contrib-core,gtd-convert-note]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd/backup,server/src/test/kotlin/ch/sonensei/cockpit/gtd/backup]
prio: 1
---
## Goal
GTD data in backups, with readable Markdown (SPEC.md §10.2).

## Acceptance criteria
- [ ] data/tasks.jsonl, projects.json, areas.json, contexts.json, checklist_items.jsonl
- [ ] readable/tasks/{inbox,next,waiting,someday,logbook}.md and readable/projects/<area>/<project>.md with checkboxes
- [ ] Round-trip test (`./gradlew test --tests '*BackupRoundTripTest'`) passes with this module's `BackupFixture` seeding tasks in every list, recurring tasks, projects with next steps, areas, contexts

## Notes
