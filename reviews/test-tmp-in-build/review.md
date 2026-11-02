# Code Review

Date: 2026-11-02

Task: test-tmp-in-build - Server and Android tests keep temp files in build/, not /tmp

Scope: task commit 5adf18af8 on top of base bd4b11a35 (git diff bd4b11a35..5adf18af8), as supplied by the parent worker: `server/build.gradle.kts`, `android/app/build.gradle.kts`, `docs/ARCHITECTURE.md`

Requirements:
- `tasks/test-tmp-in-build.md` (acceptance criteria and notes)
- `AGENTS.md` (project notes, conventions)
- `docs/ARCHITECTURE.md` ("Testing")
- Parent worker instructions (focus: integrationTest coverage, `clean`, configuration cache, task inputs, Windows paths, Testcontainers)

Git range:
- Branch: main (the task work is merged into the local clone, HEAD b66b1c98b "Merge remote-tracking branch 'origin/wip/test-tmp-in-build'")
- HEAD: b66b1c98b
- Base/start: bd4b11a35abd0ea6a52080d0d49edbba82b09215
- Commits reviewed: 1 (5adf18af8)

Note on method: Bash was not available to this reviewer in this session, so `git diff` and `tasks.py verify` could not be run. The
change was reviewed from the current contents of the three touched files (which contain the change as described by the parent),
plus `server/gradle.properties`, `android/gradle.properties` and `scripts/check.sh`. Gradle was not run (not possible for
sub-agents here); runtime behaviour relies on the parent's evidence (second server `test` run UP-TO-DATE with the lambda
provider; Android Robolectric files landed under `build/`, not `/tmp`).

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Checks performed

- Criterion 1 (every `Test` task, folder under `build/`, created and emptied at task start, removed by `clean`):
  both files configure `tasks.withType{Test}().configureEach`, so `test`, `integrationTest` and `updateOpenApi` (server) and
  every AGP unit-test task such as `testDebugUnitTest`/`testReleaseUnitTest` (android; AGP's unit-test task is a `Test`
  subclass) are covered, including tasks registered after the block (`configureEach` is lazy). The folder is
  `layout.buildDirectory.dir("tmp/test-jvm/" + name)`, i.e. `build/tmp/test-jvm/{task name}`, distinct from Gradle's own
  per-task `build/tmp/{task name}` temporary dir. `doFirst` deletes and recreates it before the test JVMs fork, so leftovers
  of killed runs are removed by the next executing run of the same task; `clean` deletes `build/` and therefore it. The path is
  absolute, so `Files.createTempDirectory`/`@TempDir` resolve independently of the worker's working directory. Each task has
  its own folder, so `test` and `integrationTest` running in one build cannot empty each other's files; parallel forks of one
  task share a folder but only create random-named entries.
- Criterion 4 (integrationTest gets the same setting): covered by the `withType{Test}` block, which also applies to the
  later-registered `integrationTest`. Testcontainers transfers files to containers through the Docker API (tar copy) and
  reaches Podman via `DOCKER_HOST`, neither depends on `java.io.tmpdir`; the runtime proof is the parent's integrationTest run.
- Criterion 6: `docs/ARCHITECTURE.md` "Testing" states that test temp files live under `build/tmp/test-jvm/{task name}`, emptied
  per run and removed by `clean` (one sentence wrapped over two source lines).
- Task inputs / up-to-date: the `CommandLineArgumentProvider` lambda has no annotated input properties, so only its
  implementation is tracked, not the absolute path it emits; the build cache key therefore stays relocatable, and the
  parent observed a second run as UP-TO-DATE, which shows Gradle could fingerprint the lambda implementation (an unknown
  implementation would have disabled up-to-date checks). An UP-TO-DATE or FROM-CACHE run skips `doFirst`, which is fine: no
  test JVM runs then, so nothing is written.
- Configuration cache: not enabled in either `gradle.properties`. Even so, both lambdas capture only the
  `Provider{Directory}` value `testTmpDir` (no `project` or script references at execution time), which is
  configuration-cache compatible.
- Windows: the argument is a single list element (`-Djava.io.tmpdir=` plus the `File` path), so Gradle passes it as one
  argument even with backslashes or spaces; `deleteRecursively`/`mkdirs` are platform-neutral.
- Existing Android block: the new lines sit inside the existing `configureEach` alongside heap, logging, JaCoCo and e2e
  settings and do not change them; JaCoCo's exec file lives in `build/jacoco/`, outside the emptied folder.
- Scope: only the three files named in `touches`; no test code changed, no dependencies added. No secrets or personal data.

## Review conclusion

No material issues found.

Evidence for criteria 2, 3, 4 and 5 (the `/tmp` counts, the killed-run check, the integrationTest run and the check.sh
timing) is runtime evidence that the parent must record with `tasks.py verify`; this reviewer could not inspect the recorded
evidence in this session.
