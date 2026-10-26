---
title: BackupJobService/RestoreService leak their lazy temp work dirs
status: done
owner: dell26/agent-2
deps: []
touches: [server/src/main/kotlin/ch/sonensei/cockpit/backup/domain]
prio: 2
lease_until: 
---
## Goal
`BackupJobService` (`workDir` = `Files.createTempDirectory("cockpit-backups")`) and `RestoreService`
(`Files.createTempDirectory("cockpit-restores")`) create a temp work dir lazily and never delete it. Every Spring
context in the server tests that touches them leaves one (empty) dir in /tmp: one `./gradlew check` run left
14 `cockpit-backups*` and 2 `cockpit-restores*` dirs (found by `test-tmp-dir-leaks`, measured with
`JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=<clone>/.tmp/jtmp`). In production it leaks one dir per server start.

## Acceptance criteria
- [x] Both services delete their work dir (if it was created) when the bean is destroyed (e.g. `@PreDestroy` +
  `deleteRecursively()`), without changing backup/restore behavior
  - verified (2026-10-26 09:49+0100 by dell26/agent-2): destroy() of BackupJobService/RestoreService deletes the lazy work dir only if initialized (59fa3e4d2); BackupWorkDirCleanupTests (10 cases: deleted after backup/failed backup/upload/invalid upload, none created if unused) + BackupJobServiceTests -> all pass
- [x] `cd server && ./gradlew check` passes and leaves no `cockpit-backups*`/`cockpit-restores*` dirs in the
  JVM temp dir (run it with `java.io.tmpdir` pointed at an empty dir inside the clone and count)
  - verified (2026-10-26 09:49+0100 by dell26/agent-2): cd server && JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=<clone>/.tmp/jtmp ./gradlew check --rerun-tasks (fresh empty dir) -> exit 0, 0 cockpit-backups*/cockpit-restores* dirs left (before: 14+2)
- [x] `sh scripts/check.sh` passes
  - verified (2026-10-26 09:49+0100 by dell26/agent-2): sh scripts/check.sh -> == all checks passed (server, web, android)

## Notes
- done by dell26/agent-2: `sh scripts/check.sh` passed in 757s on e718dea91; criteria verified 3/3
