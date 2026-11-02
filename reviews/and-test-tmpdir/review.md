# Code Review

Date: 2026-11-02

Task: and-test-tmpdir - Android: unit tests write Robolectric temp files into build/, not the shared /tmp

Scope: committed task changes f3d3b5bd248e4d9f7aa735cfe856c2b2f458767f..HEAD limited to code (`android/app/build.gradle.kts`); commits touching only `tasks/` are board bookkeeping

Requirements:
- tasks/and-test-tmpdir.md acceptance criteria
- AGENTS.md (Evidence, project notes), docs/QUALITY.md
- Parent's recorded evidence (`python tasks.py verify and-test-tmpdir`, task file on origin/main)

Git range:
- Branch: main
- HEAD: 616dca0092bad48e2d795431e355041cc88c7770
- Base/start: f3d3b5bd248e4d9f7aa735cfe856c2b2f458767f
- Commits reviewed: 2 code commits (59195dba0, d6e5b032d); the rest are heartbeat/verify board commits

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## What was checked

- `tasks.withType<Test>().configureEach` (android/app/build.gradle.kts:90-118) now sets
  `java.io.tmpdir` to `temporaryDir.resolve("jvm")` and creates it in `doFirst`. This satisfies the
  first half of criterion 1 (directory under the build directory, created before the run).
- Configuration avoidance: the code runs inside `configureEach`, so `temporaryDir` is only resolved
  for Test tasks that are realized. Calling `getTemporaryDir()` at configuration time is allowed.
- Configuration cache: the `doFirst` lambda captures only a `java.io.File`, not the project or the
  task, so it can be serialized. The configuration cache is not enabled in `android/gradle.properties`
  in any case.
- Other Test tasks: `testReleaseUnitTest` and any other Test task get their own
  `build/tmp/{taskName}/jvm`, so they do not share or collide. The e2e smoke run (`-Pe2eBaseUrl`)
  uses the same Test task and is unaffected. JaCoCo writes its exec data to its own destination,
  not to java.io.tmpdir, so it is unaffected.
- Up-to-date checks and the build cache: the absolute path becomes a system-property input. That
  input is stable within a clone. No build cache is configured (`org.gradle.caching` is unset), so
  cache relocatability is not a problem.
- `./gradlew clean` deletes `build/`, which includes the new directory, as the task goal requires.
- Current state of `android/app/build/tmp/testDebugUnitTest/jvm` after the parent's focused run:
  the nativeruntime directories are gone. Only one small (4 KB)
  `robolectric-CockpitThemeTest_...` data directory remains.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Criterion 1 "no new files appear in /tmp" is recorded as verified without observing /tmp**
    - File: `tasks/and-test-tmpdir.md:18`
    - Category: Tests
    - Problem: The recorded evidence for criterion 1 shows that Robolectric's nativeruntime and
      per-test directories were created under `build/tmp/testDebugUnitTest/jvm`. The evidence also
      says that listing /tmp is denied to agents. The other half of the criterion, "no new files
      appear in /tmp during testDebugUnitTest", was therefore inferred, not observed. A full
      `testDebugUnitTest` run also runs JVMs that are not covered by this change: the Gradle daemon,
      the Kotlin compile daemon, and KSP. Those JVMs still use the default /tmp.
    - Why it matters: AGENTS.md "Evidence" requires that a criterion is ticked only when it was seen
      to work. If it cannot be checked, it should be marked as skipped with an assumption. The
      evidence is honest about this limit, but the checkbox still claims the full criterion.
    - Suggested fix: This needs no code change. Record the /tmp part as not directly verifiable
      here. For example, add `python tasks.py assume and-test-tmpdir "..."`, which says that Test
      JVMs now write Robolectric files to build/tmp and that /tmp could not be listed under the
      sandbox. Alternatively, ask a human to compare `ls -d /tmp/robolectric*` before and after one
      run. Keep the verified evidence limited to what was observed.

## P3 Low

- [ ] **CR-2 | Comment says Robolectric "never deletes" the runtime; directories from aborted runs persist in the task temp dir until clean**
    - File: `android/app/build.gradle.kts:93-97`
    - Category: Quality
    - Problem: After the parent's normal focused run, the nativeruntime directories (about 157 MB)
      are no longer in `build/tmp/testDebugUnitTest/jvm`. Robolectric removes them when the JVM
      exits normally. They leak only when a test JVM is killed, for example by a timed-out check or
      an OOM-killed worker. Small `robolectric-{test}` data directories also remain after normal
      runs. `scripts/check.sh` never runs `clean`, so leftovers from aborted runs pile up in the
      clone until someone runs `./gradlew clean`.
    - Why it matters: The comment misstates the cause, which may mislead later debugging. A killed
      run still leaves about 200 MB per fork, now inside the clone instead of /tmp.
    - Suggested fix: Reword the comment, for example "...and leaves it behind when the test JVM is
      killed". Optionally clear the directory at the start of each run with
      `doFirst { testTmpDir.deleteRecursively(); testTmpDir.mkdirs() }`. This is safe because Gradle
      never runs the same task of one project concurrently.

## Review conclusion

No material issues found. The change is correct and minimal. It is compatible with configuration
avoidance and the configuration cache, and it keeps Test tasks separate from each other. CR-1 is
an evidence-recording issue that the parent should resolve. CR-2 is optional cleanup.
