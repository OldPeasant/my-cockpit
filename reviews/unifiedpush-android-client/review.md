# Code Review

Date: 2026-10-26

Task: unifiedpush-android-client - Android UnifiedPush client (round 6, after the Android test heap change)

Scope: the whole task, `origin/main...HEAD`, committed changes only (the working tree is clean). This round focuses on the only code change since the previous review: `maxHeapSize = "2g"` for all Test tasks in android/app/build.gradle.kts.

Requirements:
- tasks/unifiedpush-android-client.md (acceptance criteria 1-3 and the recorded assumptions, including the 22:49 test heap assumption)
- AGENTS.md project notes and conventions
- docs/ARCHITECTURE.md (Android stack table)
- scripts/check.sh and .github/workflows/ci.yml (the paths that run the Android unit tests)
- The previous review of revision 83603e8c2f3536694ca2e3d60ee5dd3bd7e8eec8 (0 findings)

Git range:
- Branch: main
- HEAD: ff93b836a0664bb6e0189fd60279e8cdb40e2aa3
- Base/start: d62f8910ea2429be59725a6848d86c828aff40f9 (the current `git merge-base HEAD origin/main`)
- Commits reviewed: 19 in `origin/main..HEAD`. The parent named ef625c0f6 as HEAD. The branch has moved since then. `git diff ef625c0f6 ff93b836a` touches only `tasks/itip-reply-ordering.md` and `tasks/unifiedpush-android-client.md`. The heap commit ff93b836a has the same content as ef625c0f6, so this review applies to both. `reviewed_revision` should name ff93b836a0664bb6e0189fd60279e8cdb40e2aa3.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Changes since the previous review

`git diff 83603e8c2 HEAD --stat` lists four files:
- android/app/build.gradle.kts (+2 lines)
- reviews/unifiedpush-android-client/quality.json
- tasks/itip-reply-ordering.md
- tasks/unifiedpush-android-client.md

Production code, tests, other build files, `api/` and the docs are unchanged. The earlier review of the push code, the logout hook, message handling, integration and security therefore still holds.

## Review of the heap change (android/app/build.gradle.kts:88-90)

- **Correctness.**
    - The line sits inside the existing `tasks.withType<Test>().configureEach` block.
    - It sets only the forked test JVM's `-Xmx`. It does not change test selection, the JaCoCo settings, or the smoke-test exclusion and e2e handling below it.
    - No production or APK build path reads it, so app behavior and the release APK are unaffected.
- **Scope.**
    - The change is outside the task's acceptance criteria. It was made because the full Android suite hit `OutOfMemoryError` in the unrelated `CockpitThemeTest`, and that blocked `.taskcheck` for this task.
    - It is the smallest fix the earlier release note suggests. It is recorded as an assumption in the task file (22:49), as AGENTS.md requires for decisions a human may want to revisit.
    - `forkEvery` was not used. That keeps the run time of `.taskcheck` unchanged.
- **Integration and resources.**
    - The Gradle daemon is already at `-Xmx2048m` (android/gradle.properties). A single test worker at 2g brings the peak to about 4 GB plus the Kotlin daemon.
    - CI runs `sh scripts/check.sh android` in its own `check-android` job on `ubuntu-24.04` (ci.yml:75-90). That hosted runner has enough memory for this.
    - `maxParallelForks` is not set, so there is still only one test JVM.
    - Locally, check.sh runs the parts one after another, so the server and web builds do not run at the same time as the Android tests.
- **Quality.** The comment says why the value was raised and matches the project's style of comments in this file. No ARCHITECTURE.md update is needed: no tool, runtime or library was added.

## Acceptance criteria check

- **AC1 and AC2:** Unchanged since the previous review. The code matches the criteria: connector 3.3.5 in libs.versions.toml and the stack table, registration on login, the PUT, and DELETE plus unregister on logout. Reminder pushes run the reminder sync, mail pushes show the new-mail notification, and unknown or garbled pushes are ignored. The recorded verify evidence names the commands and their results. A larger test heap does not change the behavior those runs observed.
- **AC3:** The recorded command is `testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*'`, which passed. The heap change only gives that run more memory.
- **Gradle runs in this review:** Bash was limited to read-only git inspection, so I did not run Gradle here. Before `done`, the parent's `.taskcheck` run on HEAD must show that the full Android suite now finishes without the OOM.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
