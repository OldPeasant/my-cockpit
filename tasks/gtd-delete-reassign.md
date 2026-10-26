---
title: Server-side reassignment when deleting a context or area
status: in_progress
owner: felix/agent-1
deps: [web-settings-gtd]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/gtd,api/openapi.yaml,web/src/app/features/settings/gtd]
prio: 2
lease_until: 2026-10-26T06:37:30Z
releases: 0
extended: 
hold: 
---
## Goal
Deleting a context/area with "move to another" must also reach items the list endpoints hide
(tasks in the tickler or Trash, projects in the Trash). Today the web client reassigns only what
`listTasks`/`listProjects` return (web-settings-gtd), so hidden items just lose the context/area.

## Acceptance criteria
- [x] `DELETE /api/v1/contexts/{id}?replaceWith=<id>` and `DELETE /api/v1/areas/{id}?moveTo=<id>` reassign all rows (incl. tickler/Trash) in one transaction; unknown target -> 400; integration tests
  - verified (2026-10-26 04:48+0100 by dell26/agent-1): server/gradlew -p server test --tests 'ch.sonensei.cockpit.gtd.*' jacocoTestReport ktlintCheck (Testcontainers Postgres) on 4d2582b47 -> BUILD SUCCESSFUL, 419 tests 0 failures; AreaContextWebTests 20/20 (DELETE contexts ?replaceWith / areas ?moveTo reassign tickler/Trash/done tasks + trashed projects, unknown/self target -> 400 with no change), AreaContextDeleteRollbackTests 2/2 (failure after reassignment rolls back, i.e. one transaction); ktlint clean
- [x] `api/openapi.yaml` regenerated; Settings -> Tasks & projects uses the parameter instead of patching items client-side, and the tickler/Trash hint in the delete dialog is removed; Vitest tests updated; `cd web && npm run check` passes
  - verified (2026-10-26 04:48+0100 by dell26/agent-1): api/openapi.yaml + web/src/app/api/generated regenerated in task (delete-context/delete-area carry replaceWith/moveTo); gtd-settings specs assert the param is sent, no task/project PATCH, tickler/Trash hint gone; npm --prefix web run check -> lint + build + Vitest pass (first run had 56 unrelated 5000ms timeouts at load avg ~50, rerun clean); npm --prefix web run coverage -> 174 files, 3628 tests passed

## Notes
- release by felix/agent-1: Not started. Host felix has a broken JDK (JAVA_HOME=/usr/lib/jvm/java-21-openjdk missing), so server tests and .taskcheck cannot run here; released for an agent on a working host.
- release by felix/agent-1: Not started. Host felix still has a broken JDK (JAVA_HOME=/usr/lib/jvm/java-21-openjdk missing; server/gradlew -v fails), so server integration tests and .taskcheck cannot run here; released for an agent on a working host.
- release by felix/agent-1: Not started. Host felix still has a broken JDK (JAVA_HOME=/usr/lib/jvm/java-21-openjdk missing; server/gradlew -p server -v fails), so server integration tests and .taskcheck cannot run here; released for an agent on a working host.
- ASSUMPTION (2026-10-26 02:44+0100 by dell26/agent-2): replaceWith/moveTo equal to the deleted context/area itself is rejected with 400 like an unknown id (field error on the parameter); a task that already has the replacement context just keeps it once
- ASSUMPTION (2026-10-26 02:44+0100 by dell26/agent-2): The delete dialog's task/project count still comes from listTasks/listProjects (visible items only); a context used only by tickler/Trash tasks shows 'No task uses this context' and is deleted without replacement. Counting hidden items would need a new count endpoint, out of scope
- extended by dell26/agent-2: Implementation, fix of CR-1 and clean re-review (review.md, fixes.md) committed. Left: run test-writer on final code (tests.md missing; wip AreaContextDeleteRollbackTests.kt committed unverified), write quality.json, record verify for both criteria, done. Last .taskcheck failed only in BackupRoundTripTest/FilesBackupContributorTests with 'Disk quota exceeded' (host disk, unrelated to this task).
- release by dell26/agent-2: Implementation complete (server DELETE ?replaceWith/?moveTo in one tx incl. tickler/Trash, 400 on unknown/self target; openapi regenerated; web settings uses the params, hint removed). CR-1 fixed, clean re-review committed (reviews/gtd-delete-reassign/review.md, fixes.md). At dfb237034 all gtd server tests pass (32 classes incl. AreaContextWebTests 20, AreaContextDeleteRollbackTests 2) and ktlintCheck is clean. Left: (1) run test-writer on final code -> reviews/gtd-delete-reassign/tests.md with coverage; (2) if it changes nothing, write quality.json (reviewed_revision = final hash, fixer ran for CR-1); (3) run cd web && npm run check, then tasks.py verify for #1 and #2; (4) sh scripts/check.sh, then done. An earlier .taskcheck failure was only 'Disk quota exceeded' in BackupRoundTripTest/FilesBackupContributorTests (host issue, unrelated).
- hold by dell26/agent-1: Task complete: both criteria verified, test/review/quality evidence committed (reviewed_revision 4d2582b47, fresh). .taskcheck fails only in BackupRoundTripTest and FilesBackupContributorTests with 'java.io.IOException: Disk quota exceeded': host /tmp tmpfs is 80% full of leaked /tmp/cockpit-round-trip-data* dirs that agents cannot delete. A human must clean /tmp, then unhold; the next owner only runs: python tasks.py done gtd-delete-reassign
