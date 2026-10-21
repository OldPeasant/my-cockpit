# Test Report

Date: 2026-10-21

Task: p3-smoke - Phase 3 demo script (emulator), SPEC.md §13.2 Phase 3 steps 1-5

Scope: `git diff 0a2260658bf4f25f36e310b05d28b6fec3d087da...HEAD`, HEAD `004bbe4c3bb6e6e5380048acead50290284e0e0d`
(task commits 0924a3bc, 11e4da0f, a387f825, cdd61bab, 2bdf5cbd, plus the merges dfc7cd8b and 004bbe4c from
`origin/wip/p3-smoke`). Changed files: `android/app/build.gradle.kts`, `android/gradle/libs.versions.toml`,
`android/app/src/androidTest/.../HiltTestRunner.kt`, `.../smoke/Phase3DemoEmulatorTest.kt`,
`android/app/src/sharedTest/.../smoke/{Phase3Demo,E2eStack}.kt`, `android/app/src/test/.../smoke/Phase3DemoRobolectricTest.kt`,
`docs/ARCHITECTURE.md`, `reviews/p3-smoke/review.md`.

Requirements:
- `tasks/p3-smoke.md` acceptance criteria and recorded assumptions
- SPEC.md §13.2 "Phase 3 — Android v1" steps 1-5
- docs/ARCHITECTURE.md "Testing" (slow suites never in `.taskcheck`) and "Android demo script"
- parent instructions (scope, the running e2e stack at http://127.0.0.1:18390/, no emulator)

## Test files changed

- None. The task deliverable is test code itself. I reviewed it against steps 1-5 and found no gap that needs another test.

## Tests added or updated

Count: 0

Review of the demo against SPEC.md §13.2 Phase 3:

| Step | What `Phase3Demo` checks | Assessment |
|---|---|---|
| 1. Log in with password + TOTP | Fresh stack (`/api/test/reset`, setup + TOTP confirm). Login screen filled with URL/user/password/computed TOTP. Shell (Today) shown, login screen gone | Covered. The API stand-in logs in with a recovery code, so the app's TOTP login uses an unused time step |
| 2. Offline: share URL, share photo, widget "Pay rent"; pending 3 | ACTION_SEND text and image intents to ShareActivity, CaptureActivity (what the widget pill starts). `CaptureQueue.status.pendingCount == 3`. Server Inbox empty (only where the stack is reachable offline, CR-1) | Covered within the recorded assumptions (intents stand in for browser/gallery; count read from the status the widget shows) |
| 3. Online: all 3 in Inbox within a minute, photo attached, re-sync gives no duplicates | 60 s wait for 3 Inbox tasks in order. Shared URL in notes. Task ids equal client ids. Photo bytes equal via `/links` + `/files/{id}/content`. Queue empties. Re-queues all 3 and runs the real sync job again: same Inbox ids, 4 tasks total, photo linked once | Covered, including a real re-send (not only a second no-op sync) |
| 4. Process Inbox on the phone; Next/Today/Projects match the web | Processes 3 items in the Inbox UI (one to new project "Holiday", two to Next). Server Inbox empty. Next rows == API `list=NEXT` set. Today rows == API overdue+today+scheduled. Projects tags == API active projects; "Holiday ideas" belongs to "Holiday" | Covered. Comparison runs through the API the web uses (recorded assumption) |
| 5. Reminder fires a local notification at the due time | Reminder created via API, `ReminderSync.sync() == SYNCED`, no notification before. Robolectric: the single alarm is exact (window 0) at the reminder time to the ms, then fired. Emulator: real clock, 20 s ahead. Notification on the reminder channel titled "Water plants" | Covered (Robolectric checks the alarm time instead of waiting on a clock, recorded assumption) |

Gating check: `build.gradle.kts` adds `filter.excludeTestsMatching("ch.sonensei.cockpit.smoke.*")` to every `Test` task
unless `-Pe2eBaseUrl` is set. As a second guard, `RobolectricDevice.setUp` calls `assumeTrue` when the property is empty.
With the property set, the property is passed as a system property and `upToDateWhen { false }` forces a re-run.
I confirmed this by running it (below).

Observations (no action needed, for the parent):
- SPEC.md §13.2 says each phase script also runs every earlier phase's script as a regression test. The task's acceptance
  criteria cover only steps 1-5, so this demo does not chain the Phase 0-2 scripts. Out of scope for this task as written.
- The tested package filter is a package pattern. A future test placed in `ch.sonensei.cockpit.smoke` would also be
  excluded from `.taskcheck` silently. That matches the documented intent ("package smoke").

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Crit 1: steps 1-5 against the e2e stack, Robolectric fallback | `Phase3DemoRobolectricTest.phase3Demo` | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.smoke.*' -Pe2eBaseUrl=http://127.0.0.1:18390/ --console=plain` (stack health UP before the run) | Pass: 1 test, 0 skipped, 0 failures, 35.1 s (suite timestamp 2026-10-21T13:32:35Z) |
| Crit 1: instrumented run on an emulator | `Phase3DemoEmulatorTest.phase3Demo` | `./gradlew compileDebugAndroidTestKotlin ktlintCheck` (compile only) | Not run: no emulator/system image here (recorded assumption). It only compiled (BUILD SUCCESSFUL). Compiling is not execution |
| Smoke excluded from unit runs without `-Pe2eBaseUrl` (keeps `.taskcheck` fast) | whole unit suite | `cd android && ./gradlew testDebugUnitTest --console=plain` | Pass: 100 result files, 1044 tests, 0 failed/skipped. No `ch.sonensei.cockpit.smoke.*` result file (the only "Smoke" file is the unrelated `ch.sonensei.cockpit.TestLibrariesSmokeTest`) |
| Crit 2: ARCHITECTURE.md documents how to run (system image, AVD) | n/a (docs) | Read `docs/ARCHITECTURE.md` "Android demo script" | Not testable as code. The section has the stack command, Robolectric command, sdkmanager system image, avdmanager AVD, emulator start and connectedDebugAndroidTest. The Robolectric commands match what I ran |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.smoke.*' -Pe2eBaseUrl=http://127.0.0.1:18390/ --console=plain`

Result:

Pass (1 test, 35.1 s, BUILD SUCCESSFUL in 37 s, 2026-10-21 15:32:33-15:33:10 +02:00)

## Full test execution

Command:

`cd android && ./gradlew testDebugUnitTest --console=plain` (Android unit suite, smoke gated out), then `./gradlew ktlintCheck lintDebug`
and `./gradlew compileDebugAndroidTestKotlin ktlintCheck`

Result:

Pass. The first attempt of `testDebugUnitTest` failed with a Gradle infrastructure error
(`NoSuchFileException .../test-results/testDebugUnitTest/binary/in-progress-results-generic.bin`, no test failure). The
immediate retry passed: 1044 tests, 0 failures. `ktlintCheck lintDebug` and `compileDebugAndroidTestKotlin ktlintCheck`
both reported BUILD SUCCESSFUL.

## .taskcheck

Result:

Fail, in a part this task does not touch. `sh scripts/check.sh` (15:33:21-15:34:11):
- the TFP gate tests passed;
- `server: ./gradlew check` passed;
- `web: npm run check` failed: 57 of 2275 tests in 8 spec files (linked-items, inbox-page, projects-states, task-detail,
  note-editor-page, backup-settings, gtd-settings, security-settings);
- the Android step was not reached.

The task diff has no file under `web/` or `server/`. The failures are 8 "Test timed out in 5000ms" errors plus
46 cascading "Cannot configure the test module when the test module has already been instantiated" errors. Several other
agents' Gradle daemons were running on the machine, so this looks like load-induced timeouts, not a defect from this
task. I could not re-run the web check alone (the command was denied in my environment). The parent must re-run
`.taskcheck` (`tasks.py done` does this anyway) before completing.

## Bugs found

None in the task's code.

## Coverage

Status: not applicable. The task changes no production code. Every changed source file is test code
(`src/test`, `src/androidTest`, `src/sharedTest`), Gradle build/test wiring, or documentation. Task-scoped
production coverage therefore has an empty scope. I did not report any percentage, and none of these files is 100% covered.

Metric: whole-file coverage of task-changed production files: N/A (no production files in scope).
Changed-line coverage: not measured (no production lines changed).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: p3-smoke. Base `0a2260658bf4f25f36e310b05d28b6fec3d087da` (claim commit, from the parent). HEAD `004bbe4c3bb6e6e5380048acead50290284e0e0d`. Task commits 0924a3bc, 11e4da0f, a387f825, cdd61bab, 2bdf5cbd come from the parent and match `git log base..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 004bbe4c. The only uncommitted change is `reviews/p3-smoke/review.md` (not a test input).
- Exact included files; renamed/deleted/excluded files and reasons: no production files included. Excluded: the 5 Kotlin test sources (test code), `build.gradle.kts` / `libs.versions.toml` (build configuration), `docs/ARCHITECTURE.md`, `reviews/p3-smoke/review.md` (documentation). No renames or deletions.
- Provider/version, command, working directory, and test selection: not run (empty production scope).
- Run start/completion time and exit status: N/A.
- Generated report paths and report hashes/run identifier: N/A.
- Evidence outputs are fresh and inputs stayed unchanged: the test runs above are from this session on HEAD 004bbe4c. Inputs did not change between runs.
- Final run after latest test additions and production fixes: yes (no tests added, no production fixes).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (no production files in scope) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `Phase3DemoEmulatorTest.kt` (whole runner: airplane mode via shell, MediaStore photo, real clock) | Crit 1, emulator path | Not executed: no emulator/system image on this machine (recorded assumption). Compiles. Needs a run on a machine with KVM + system image |

## Unable to test

- The instrumented emulator run (`connectedDebugAndroidTest`) could not run: no emulator or system image here, and the SDK
  directory is outside the clone (write denied). Needs follow-up on a machine with an emulator. It is already recorded as an assumption.
- `.taskcheck` completeness: the web step failed in code this task does not touch, so the Android step of `scripts/check.sh` was not reached.
  I ran its parts (`ktlintCheck testDebugUnitTest lintDebug`) separately, and they passed. The parent must re-run `.taskcheck`.

## Summary

No additional tests were necessary for the reviewed scope. The demo covers SPEC.md §13.2 Phase 3 steps 1-5 and passes on
Robolectric against the e2e stack. The gating keeps it out of plain `testDebugUnitTest` runs. The emulator variant compiles
but was not run. `.taskcheck` failed in the web unit tests (not part of this task, probably caused by load) and must be
re-run by the parent.
