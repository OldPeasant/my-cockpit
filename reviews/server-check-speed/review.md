# Code Review

Date: 2026-11-08

Task: server-check-speed - Server check: make .taskcheck's server tests fast again

Scope: `git diff bb487db91..HEAD -- . ':!reviews'` (whole task, final code), with focus on the CR-2 fix
(`git diff d55caf130..075e9f168`, rebased as `37552d012..fab402e79`, same patch-id). Committed changes only.

Requirements:
- tasks/server-check-speed.md acceptance criteria
- SPEC.md §10.2 (backup secrets: Argon2id-derived key), docs/ARCHITECTURE.md "Testing"/"Coverage"
- AGENTS.md project notes (lint, test conventions, CI must still run every server test)
- Parent focus: is deleting build/test-data safe for every Test task (test, integrationTest, updateOpenApi), does
  anything else write there (android, e2e, scripts/), are the docs accurate
- Context: previous reviews of this task, reviews/server-check-speed/tests.md and fixes.md

Git range:
- Branch: main
- HEAD: fab402e79bdb7295a1a53f5f4fe81e213a42f56d (reviewed revision; HEAD was rebased during the review from
  075e9f168, and the code diff of the last commit has the same patch-id 8812d1532a0d)
- Base/start: bb487db914cd59cc0dcd661523f992d6f3ef6ca4 (current merge-base with origin/main: 9c3f0186d; the
  commits in between only touch tasks/ files of this and other tasks)
- Commits reviewed: 6 task commits (3d707e99b, c49249179, 2e1620bc5, f66c1bfb7 code; d716bbb80, 37552d012 reports
  only; fab402e79 code and docs for CR-2)

Summary: 0 P0, 0 P1, 0 P2, 0 P3 open (CR-1 and CR-2 resolved)

## Status of earlier findings

- CR-1 (P2, production default of the backup secrets KDF not covered): **resolved** in f66c1bfb7. The test
  `a freshly loaded BackupSecrets encrypts with the default KDF, so production is not lowered like Spring tests`
  (`server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupSecretsCryptoTests.kt`) loads `BackupSecrets` in an
  isolated class loader and asserts `DEFAULT_KDF` and the initial `encryptionKdf` are 64 MiB / 3 passes / 1 lane, so a
  weakened initializer fails regardless of test order or fork.
- CR-2 (P3, per-fork test data dirs never cleared, new names on most runs): **resolved** in fab402e79.
  `server/build.gradle.kts:141-144` adds `testDataDir = layout.buildDirectory.dir("test-data")` and calls
  `deleteRecursively()` on it in the `doFirst` of every `Test` task, before any fork JVM starts.

## Review of the CR-2 fix

- Safe for all Test tasks. The deletion is in `tasks.withType<Test>().configureEach`, so `test`, `integrationTest`
  and `updateOpenApi` each clear `build/test-data` in `doFirst`, which runs before the task's fork JVMs start, so it
  cannot delete data under a running fork of the same task. All three tasks run in the same project; neither
  `org.gradle.parallel` nor the configuration cache is enabled (`server/gradle.properties`), so two Test tasks of
  one build do not run at the same time (for example `./gradlew test integrationTest` runs them in sequence, and each
  starts from an empty dir). No test reads data left by an earlier run or another task: each JVM starts its own
  Postgres container with an empty schema, so blobs or files left in the data dir have no DB rows pointing to them.
- Nothing else writes there. `git grep test-data` finds only `server/src/test/resources/config/application.properties:9`
  (`cockpit.data-dir=build/test-data/fork-${org.gradle.test.worker:0}`), the build script and docs/ARCHITECTURE.md.
  `bootRun` uses the default `data` dir (or `COCKPIT_DATA_DIR`); the web e2e setups and `scripts/check.sh` only
  start `./gradlew check`/`bootRun`/`integrationTest` and set no data dir under `build/test-data`; android uses its own
  Gradle project and never touches `server/build`. CI (`.github/workflows/ci.yml`) has no reference to it.
- Path is consistent. The test working directory is the server project dir (Gradle default), so the relative
  `build/test-data/...` in the test properties resolves to the same folder as `layout.buildDirectory.dir("test-data")`
  (no custom `buildDir`).
- Concurrent Gradle invocations on the same clone (two `./gradlew` processes at once) could delete each other's
  data dir, but the same was already true for `build/tmp/test-jvm/{task}` and for the build outputs themselves; this
  is not a usage the project supports and not a regression. Not a finding.
- `deleteRecursively()` ignores failures (returns false); a leftover file would only be the old leak, not a test
  failure. Acceptable, consistent with the existing temp dir cleanup.
- Docs. The build script comment (lines 98, 130-132) and docs/ARCHITECTURE.md line 163
  (`build/test-data/fork-{worker id}`, "emptied at the start of each test task") now describe the behaviour
  correctly, including that the folder name is the Gradle worker id, not a fork index. docs/ARCHITECTURE.md line 112
  ("Tests use `server/build/test-data`") is still true. The first clean after this change will also delete the old
  shared `build/test-data/blobs` (13 GB on this machine), a one-off cost.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None open (CR-2 resolved, see above).

## Notes on the whole task (checked, no finding; unchanged since the previous review)

- Production never uses the cheap crypto: `FastTestCrypto` and `META-INF/spring.factories` are in the test source
  set and only run in the Spring TestContext framework; nothing under `server/src/main` assigns
  `BackupSecrets.encryptionKdf`. `FastTestCryptoTests` covers the production encoder parameters and that fast
  hashes and envelopes still pass production verification and `decrypt`'s lower bounds.
- Per-fork isolation: each fork has its own Postgres container, Spring context cache and data dir; classes needing
  their own dirs use unique temp dirs; `BackupWorkDirCleanupTests` uses its own `@TempDir`. `tempRoot` is null in
  production (old behaviour), and both branches are tested.
- Order-independence fixes in `HabitEdgeCaseTests` and `GoogleContactsSyncTests` are correct.
- CI still runs every non-external server test via `sh scripts/check.sh server`; the default fork count is
  `availableProcessors / 4` clamped to 1..4, overrides clamped to at least 1.
- Not reviewable from code (parent evidence): criteria 1, 3 and 4 (slowest classes, wall-time comparison, three
  green runs) and `sh scripts/check.sh` passing on the final code. The parent's three `./gradlew check` runs on this
  revision are the evidence for the CR-2 change as well.

## Review conclusion

No material issues found. CR-1 and CR-2 are resolved; the CR-2 fix is safe for `test`, `integrationTest` and
`updateOpenApi`, nothing else writes to `server/build/test-data`, and the docs match the behaviour.
