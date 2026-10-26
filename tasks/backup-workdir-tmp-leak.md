---
title: BackupJobService/RestoreService leak their lazy temp work dirs
status: in_progress
owner: dell26/agent-2
deps: []
touches: [server/src/main/kotlin/ch/sonensei/cockpit/backup/domain]
prio: 2
lease_until: 2026-10-26T08:53:41Z
---
## Goal
`BackupJobService` (`workDir` = `Files.createTempDirectory("cockpit-backups")`) and `RestoreService`
(`Files.createTempDirectory("cockpit-restores")`) create a temp work dir lazily and never delete it. Every Spring
context in the server tests that touches them leaves one (empty) dir in /tmp: one `./gradlew check` run left
14 `cockpit-backups*` and 2 `cockpit-restores*` dirs (found by `test-tmp-dir-leaks`, measured with
`JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=<clone>/.tmp/jtmp`). In production it leaks one dir per server start.

## Acceptance criteria
- [ ] Both services delete their work dir (if it was created) when the bean is destroyed (e.g. `@PreDestroy` +
  `deleteRecursively()`), without changing backup/restore behavior
- [ ] `cd server && ./gradlew check` passes and leaves no `cockpit-backups*`/`cockpit-restores*` dirs in the
  JVM temp dir (run it with `java.io.tmpdir` pointed at an empty dir inside the clone and count)
- [ ] `sh scripts/check.sh` passes

## Notes
