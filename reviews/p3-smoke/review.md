# Code Review

Date: 2026-10-21

Task: p3-smoke - Phase 3 demo script (emulator)

Scope: `git diff 0a2260658bf4f25f36e310b05d28b6fec3d087da...HEAD` on the final revision 9434a6f1, committed changes only.
This replaces the review of revision 004bbe4c. Between 004bbe4c and HEAD, the only change outside `reviews/` and
`tasks/` is commit 9434a6f1 (`git diff 004bbe4c HEAD -- . ':!reviews' ':!tasks'` shows 1 file, `docs/ARCHITECTURE.md`,
+3/-1).

Requirements:
- tasks/p3-smoke.md (acceptance criteria and recorded assumptions)
- SPEC.md §13.2 Phase 3, steps 1-5
- docs/ARCHITECTURE.md (Test table, "Android demo script", rules on slow suites)
- AGENTS.md project notes
- Parent agent instructions (confirm the fix of the previous P3 CR-1, check that nothing else changed, re-confirm the verdict)
- Previous review of 004bbe4c and reviews/p3-smoke/fixes.md

Git range:
- Branch: main
- HEAD: 9434a6f18693624b35eb941dfd8a9b98d99cc1ea
- Base/start: 0a2260658bf4f25f36e310b05d28b6fec3d087da
- Commits reviewed: 9 (6 task commits, 2 merges of `origin/wip/p3-smoke`, 1 review/report commit c54bcfb4)
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 0 P2, 0 P3

What I checked:
- **The previous CR-1 (P3) is fixed.** The `Phase3DemoEmulatorTest` bullet in `docs/ARCHITECTURE.md:165-169` now says
  the emulator runner has only been compiled (`compileDebugAndroidTestKotlin`) and never run on an emulator (task
  p3-smoke), that the Robolectric runner is the one verified against the e2e stack, and that problems should be reported
  as a task. This matches the recorded assumption in `tasks/p3-smoke.md` and the fix described in
  `reviews/p3-smoke/fixes.md`. No other text changed.
- **Nothing else changed.** `git diff 004bbe4c HEAD -- . ':!reviews' ':!tasks'` contains only that hunk. The Kotlin
  sources (`Phase3Demo.kt`, `E2eStack.kt`, `Phase3DemoEmulatorTest.kt`, `HiltTestRunner.kt`,
  `Phase3DemoRobolectricTest.kt`), `android/app/build.gradle.kts` and `android/gradle/libs.versions.toml` are identical to
  revision 004bbe4c. That revision was reviewed in full with no findings above P3, so those conclusions still hold
  (CR-1 of the earlier round fixed by `stackReachableOffline`, step 3 client-id/dedup checks, steps 1/2/4/5 matching the
  spec and the assumptions, the smoke package left out of `.taskcheck` without `-Pe2eBaseUrl`, no secrets logged,
  `/api/test/reset` only called on an explicit URL).
- **Criterion 1:** Recorded with the Robolectric run against the e2e stack (1 passed, 0 skipped), with the emulator part
  explained as not possible here and the compile-only status of the emulator runner. The code is the same as when I
  re-ran the Robolectric demo on 004bbe4c (1 test, 0 failures, 29.9 s). It was not re-run for this docs-only change.
- **Criterion 2:** `docs/ARCHITECTURE.md` "Android demo script" documents the e2e stack startup, the system image
  install (`sdkmanager ... system-images;android-36;google_apis;x86_64`, arm64 note), AVD creation (`avdmanager create
  avd -n cockpit-api36`), emulator start, and both run commands. The Test table lists both commands. The content meets
  the criterion. `tasks/p3-smoke.md` still shows criterion 2 unticked, so the parent must record it with
  `tasks.py verify` before `done`. This is an open process step, not a code finding.

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
