---
title: Other server tests leak their companion temp data dirs into /tmp
status: in_progress
owner: dell26/agent-2
deps: []
touches: [server/src/test/kotlin]
prio: 2
lease_until: 2026-10-26T08:33:29Z
---
## Goal
Like `BackupRoundTripTest` before `backup-roundtrip-tmp-leak`, several server test classes create a data dir with
`Files.createTempDirectory(...)` in their companion object and never delete it, so every `./gradlew check` (and
`.taskcheck`) leaves dirs in /tmp until the tmpfs quota is exhausted. Found by the helpers of
`backup-roundtrip-tmp-leak`: `RestoreWebTests`, `RestoreCliTests`, `BackupSecretsTest`, `SpaFallbackTests` and the
`*BackupContributorTests` (files, gtd, calendar, mail, journal, contacts, notes, bookmarks).

## Acceptance criteria
- [x] Every server test class that creates a temp dir in a companion object (or another static place) deletes it
  after the class, also when a test fails (`@AfterAll` + `deleteRecursively()` as in `BackupRoundTripTest`); test
  behavior is unchanged
  - verified (2026-10-26 09:15+0100 by dell26/agent-2): grep -rn createTempDirectory server/src/test/kotlin -> 12 classes with a companion temp dir (RestoreWebTests, RestoreCliTests, BackupSecretsTest, SpaFallbackTests, 8 *BackupContributorTests) + BackupRoundTripTest; each has @JvmStatic @AfterAll deleteRecursively() (added on main in 6b8afa8ae); no other static createTemp* in server tests
- [x] `cd server && ./gradlew check` passes, and the number of the affected temp dirs in /tmp is the same before
  and after the run
  - verified (2026-10-26 09:15+0100 by dell26/agent-2): sh .tmp/check-tmp.sh = cd server && JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=<clone>/.tmp/jtmp ./gradlew check --rerun-tasks --no-daemon -> exit 0; companion dirs (cockpit-*-data, cockpit-spa*) before 0, after 0. Unrelated leftovers: 14 empty cockpit-backups* + 2 cockpit-restores* from production BackupJobService/RestoreService -> new task backup-workdir-tmp-leak
- [ ] `sh scripts/check.sh` passes

## Notes
- ASSUMPTION (2026-10-26 09:13+0100 by dell26/agent-2): The companion-object @AfterAll cleanup was already on main (6b8afa8ae by agent-1, done while fixing another task); this task verifies it and adds no code. The remaining leaked dirs (empty cockpit-backups*/cockpit-restores*, one per Spring context, from production BackupJobService/RestoreService workDir) are outside this task's test-only scope: new task backup-workdir-tmp-leak.
