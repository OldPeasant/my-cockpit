# Code Review

Date: 2026-11-02

Task: test-tmp-in-build - Server and Android tests keep temp files in build/, not /tmp

Scope: `git diff a0a707e35 HEAD -- server android docs` (task code commit e68c61ff0, merge f7b9b772c of origin/wip/test-tmp-in-build, and 9be7b78c5 which drops the superseded and-test-tmpdir lines)

Requirements:
- tasks/test-tmp-in-build.md (acceptance criteria and notes)
- AGENTS.md project notes (build files, shared docs, lint conventions)
- docs/ARCHITECTURE.md (Testing section)
- tasks/and-test-tmpdir.md (the earlier done task whose lines were removed)

Git range:
- Branch: main
- HEAD: 9be7b78c5
- Base/start: a0a707e35 (origin/main)
- Commits reviewed: 5 (e68c61ff0, 9d93051af, 36b4757f8, f7b9b772c, 9be7b78c5; only e68c61ff0, f7b9b772c and 9be7b78c5 touch the reviewed paths)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## What was checked

- `server/build.gradle.kts` and `android/app/build.gradle.kts`: `tasks.withType(Test).configureEach` adds
  `-Djava.io.tmpdir=build/tmp/test-jvm/{task name}` via `jvmArgumentProviders` and, in `doFirst`, deletes and recreates
  the folder. `withType(Test)` covers `test`, `integrationTest` (server, registered as `Test`) and every Android
  unit-test variant task. The directory is under `layout.buildDirectory`, so `clean` removes it.
- Using a `CommandLineArgumentProvider` instead of `systemProperty` keeps the absolute path out of the task's tracked
  inputs (no cache-key churn between clones); the Kotlin DSL compiles SAM conversions to classes, so the provider
  implementation stays trackable. The `doFirst` closure captures only a directory provider (`Provider` of `Directory`), which is compatible with
  the configuration cache.
- The 9be7b78c5 removal: the and-test-tmpdir lines (`temporaryDir.resolve("jvm")`, `systemProperty("java.io.tmpdir", ...)`,
  `doFirst { mkdirs() }`) would have set the same property twice (a `systemProperty` and a `-D` from the argument
  provider, with ordering-dependent precedence) and redeclared `val testTmpDir` in the same scope, which does not
  compile. Removing them is correct; the new setting satisfies and-test-tmpdir's criterion as well (tmpdir under the
  module's `build/`, not `/tmp`). No other file references the removed `temporaryDir/jvm` path.
- `git diff e68c61ff0 HEAD` on the three reviewed files is empty: the final code is identical to the task's original
  code commit, so the recorded evidence for criteria 1-4 and 6 (gathered on that code) still applies.
- No test hard-codes the host `/tmp` (the only hits are container paths in `HeaderSyncDovecotTests`), so no test changes
  were needed, matching the task note.
- `docs/ARCHITECTURE.md` lines 150-151: one line in the Testing area, consistent with the build code.
- Criterion 5 (`sh scripts/check.sh` passes, duration recorded) is still open on the board; it is evidence the parent
  must record, not a code defect, and was not claimed.
- No secrets, dependencies, or security-relevant configuration changed.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Server tmpdir comment sits above the unrelated testLogging code**
    - File: `server/build.gradle.kts:113`
    - Category: Quality
    - Problem: The new two-line tmpdir comment is placed after the existing testLogging comment, above
      `tasks.withType(Test).configureEach`, while the tmpdir code it describes is at the end of the block (lines 122-129).
      The testLogging comment and its code are now separated by an unrelated comment. In `android/app/build.gradle.kts`
      the same comment sits directly above the code it explains.
    - Why it matters: Minor readability; a reader of the block header sees two comments for two concerns and has to
      search for the tmpdir lines.
    - Suggested fix: Move the tmpdir comment inside the block, directly above `val testTmpDir`, as in the Android file.

## Review conclusion

No material issues found.
