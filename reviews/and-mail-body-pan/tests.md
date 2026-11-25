# Test Report

Date: 2026-11-25

Task: and-mail-body-pan - Mail body: pan diagonally instead of axis-locked scrolling

Scope: round 2 on the final code at ee4b0774efd0fe2f8f2fb3ff5df53231ccc81116. Task base 1de509f28a0468a706bd524c5c8654c91f5a555e (`git merge-base HEAD origin/main`). Task-owned production commits: c181305fc1584d0e343cba9164ae0be5d3d3b508 (diagonal pan, fling both axes) and ee4b0774efd0fe2f8f2fb3ff5df53231ccc81116 (fixer: CR-1 velocity from finger deltas, CR-2 up sample added before the fling). a5461b65c and 60d9f88ab only add tests and review reports. `git diff --stat 1de509f28 ee4b0774e` lists `MailBodyPan.kt`, `MailBodyView.kt`, `MailBodyPanTest.kt` and the reports under `reviews/and-mail-body-pan/`. Android only. The working tree was clean (no uncommitted inputs).

Requirements:
- `tasks/and-mail-body-pan.md` acceptance criteria 1 to 5 and its recorded assumptions
- `reviews/and-mail-body-pan/fixes.md` (CR-1, CR-2 fixes and their regression tests)
- `docs/ARCHITECTURE.md` "Coverage" (Android: JaCoCo via `createDebugUnitTestCoverageReport`)
- Parent instructions for round 2: change test files only for a real gap; re-run the `mail.read` tests, ktlintCheck and task-scoped coverage

## Test files changed

- None in round 2. `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailBodyPanTest.kt` is as committed in ee4b0774e (round-1 tests from a5461b65c plus the fixer's 3 regression tests).

## Tests added or updated

Count: 0 in round 2. The file now has 30 tests: 27 from round 1 and 3 added by the fixer:
- `the release velocity follows the finger's own movement, not its position on the moving body` (unit, `FingerVelocity`: constant local position, deltas only, gives about -500 px/s in x and -1000 px/s in y)
- `dragging, holding still and lifting gives no fling` (unit, `FingerVelocity`: an up sample after a 300 ms pause gives exactly `Velocity.Zero`)
- `a drag-hold-lift on the body leaves the page and the body where the finger left them` (Compose UI, Robolectric: after a 400 ms hold and lift, `scrollX` and the page scroll stay where the drag left them for 2 s of clock time)

Review of the fix against the tests: `FingerVelocity` starts at `Offset.Zero` at the down time and adds the running sum of `positionChange()` for every sample, the pre-slop moves and the up included. The up is added before `lastUp` is set and before the fling is computed (MailBodyPan.kt:131-135, 153-155). Both changed behaviors have a direct unit test, and CR-2 also has the UI regression test (the fixer reports it failed without the fix). The remaining CR-1 effect (the body moving under the finger during a vertical drag) cannot be reproduced under Robolectric, as fixes.md says; it is part of the device check in criterion 5. I found no other gap in the changed code that a test here would catch, so no test was added.

## Requirement coverage

All rows ran in the same command, `android/gradlew -p android testDebugUnitTest --rerun --tests 'ch.sonensei.cockpit.mail.read.*' createDebugUnitTestCoverageReport ktlintCheck`, on ee4b0774e.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 diagonal drag moves body horizontally and page vertically; no axis lock either way | `a diagonal delta pans sideways ...`, `a mostly vertical drag keeps its small horizontal part ...`, `a mostly horizontal delta keeps its small vertical part`, `a diagonal swipe on a wide body changes both ...`, `a mostly horizontal swipe keeps its small vertical part for the page`, `a second finger after the slop keeps the one-finger drag going` | see above | Pass |
| AC2 fling continues in both axes and stops at the edges | `a fast diagonal fling keeps going in both axes ...`, `a fling stops exactly at the right edge of the content`, `a fast horizontal fling pans on past the finger ...`, `dragging towards the top-left edges moves nothing past them`, `touching the body again stops a running fling`, `the edges stop a fling`, `the pan is clamped to the content range`, `at an edge the sub-pixel carry is dropped ...` | see above | Pass |
| AC2 release velocity from the finger's own movement (CR-1) | `the release velocity follows the finger's own movement, not its position on the moving body` | see above | Pass (unit level; the on-device vertical fling speed: Not run, needs a device, criterion 5) |
| AC2 no fling after drag, hold still, lift (CR-2) | `dragging, holding still and lifting gives no fling`, `a drag-hold-lift on the body leaves the page and the body where the finger left them` | see above | Pass |
| AC3 link tap still reaches the WebView | `a tap on the body is not consumed ...`, `a move below the touch slop is not consumed ...`, `a drag past the touch slop is consumed from then on ...` | see above | Pass (at the Compose level; the real link opening: Not run, needs a device) |
| AC3 pinch-zoom still works; panning respects the zoomed width | `a second finger before the slop leaves the pinch to the WebView`, `the WebView pans within its content width at the current zoom minus the view width` | see above | Pass (at the Compose level and for the range formula; real zoom: Not run, needs a device) |
| AC3 a mail no wider than the screen scrolls only vertically | `a mail no wider than the screen does not pan sideways`, `a mail no wider than the screen scrolls only vertically, also on a diagonal fling`, `a plain vertical swipe on the body scrolls the page only` | see above | Pass |
| AC3 dragging outside the body (header) scrolls the page as before | `dragging the headers still scrolls the page and leaves the body alone` | see above | Pass (attachments not tested separately: the same page `verticalScroll` handles them, and the fixture has no attachments) |
| AC3 TalkBack scrolling still works | none | none | Not run: needs a device with TalkBack. The page's `VerticalScrollAxisRange` semantics still exist, but no accessibility scroll action was run |
| AC4 unit tests for delta split / clamp; Compose UI test for a diagonal swipe | everything above | see above | Pass |
| AC5 real device or emulator | none | none | Not run: no device or emulator available to this helper; recorded as skipped on the task |

## Focused test execution

Command:

`android/gradlew -p android testDebugUnitTest --rerun --tests 'ch.sonensei.cockpit.mail.read.*' createDebugUnitTestCoverageReport ktlintCheck`

Result:

Pass. BUILD SUCCESSFUL in 34s. 26 test classes in `mail.read`, 377 tests, 0 failures, 0 errors, 0 skipped. `MailBodyPanTest`: tests="30" skipped="0" failures="0" errors="0". ktlintCheck: up to date and passing (no ktlint findings).

## Full test execution

Command:

`android/gradlew -p android testDebugUnitTest` (all Android unit tests)

Result:

Not run. The task changes only `mail/read`, and that whole package passed. The full Android suite and `.taskcheck` are long and would hold the Gradle build directory that the parent and the parallel review use. The parent runs `.taskcheck` before `done`.

## .taskcheck

Result:

Not run (left to the parent worker, see above).

## Bugs found

None. All 30 `MailBodyPanTest` tests and the rest of `mail.read` pass on ee4b0774e.

## Coverage

Status: verified (whole-file, scoped test selection `ch.sonensei.cockpit.mail.read.*`)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured with tooling. `MailBodyPan.kt` is entirely new in this task, so its whole-file numbers are its changed-line numbers (lines 88/88). In `MailBodyView.kt` (unchanged since round 1, same hash) the only changed line with a gap is line 198 (one missed branch).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-mail-body-pan; base 1de509f28a0468a706bd524c5c8654c91f5a555e; measured HEAD ee4b0774efd0fe2f8f2fb3ff5df53231ccc81116; task commits c181305fc and ee4b0774e (production), a5461b65c (tests), from `git log` of the task branch and the parent's instructions. File scope from `git diff --stat 1de509f28 ee4b0774e`.
- Source/test snapshot identifier (including uncommitted inputs): clean working tree at ee4b0774e. sha256 `MailBodyPan.kt` b574530be629b519b1e17bc968708a0c5a62e14233aaa85348e7286c5f29e63f, `MailBodyView.kt` 1a20af415d2acaf7bd4ad4216b43b2b21e0e507a286ccc4a963e77bb1605252d, `MailBodyPanTest.kt` f1a6e7974788f870ba5176489ba3f32334be8926c3bdf948c5a5f802cb8e03a2. Same hashes before and after the run.
- Exact included files; renamed/deleted/excluded files and reasons: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyPan.kt` and `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyView.kt` included. `MailBodyPanTest.kt` excluded (test). Review reports excluded (documentation). Nothing renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; command above, run from the repository root with Gradle project `android`. Only `mail.read` tests selected, so hits come from that package only.
- Run start/completion time and exit status: 2026-11-25T07:42:16Z to 07:42:51Z, exit 0, BUILD SUCCESSFUL (2 tasks executed: the forced test task and the coverage report; the rest up to date).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, mtime 2026-11-25 08:42:50 +0100, sha256 536d87d8dad092d15f587421d6f36a8cc55b41e9851b691493afdecb03b68910
- Evidence outputs are fresh and inputs stayed unchanged: yes. `--rerun` forced the test task, the report was rewritten within the run window, and the input hashes matched before and after.
- Final run after latest test additions and production fixes: yes, on ee4b0774e, which contains the fixer's production change and tests. The round-1 numbers (commit a31a5716c / 6bddc9f47) are superseded.

Values below use covered/total (percentage). "Statements" means JaCoCo INSTRUCTION counters, "Functions" METHOD counters.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyPan.kt` | 833/841 (99.0%) | 56/62 (90.3%) | 23/23 (100%) | 88/88 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyView.kt` | 609/612 (99.5%) | 55/56 (98.2%) | 30/31 (96.8%) | 90/91 (98.9%) |
| Aggregate (summed counters, both files, none excluded or unmeasured) | 1442/1453 (99.2%) | 111/118 (94.1%) | 53/54 (98.1%) | 178/179 (99.4%) |

Compared with round 1, `MailBodyPan.kt` went from lines 79/80 and branches 54/62 to 88/88 and 56/62 (new `FingerVelocity` class fully covered; the single-axis fling checks in `flingBoth` are now fully taken).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailBodyPan.kt:130` (`?: break` when the tracked pointer is missing from an event) and `MailBodyPan.kt:153` (one branch: dragging but no release seen, so no fling) | The gesture stream loses the first pointer mid-drag | Gap left: defensive. Compose delivers the tracked pointer until its release, and a cancel arrives as a synthetic release, so the test input API cannot produce it. No fling is the safe outcome |
| `MailBodyPan.kt:146` (one branch: `target()` null after a nonzero split) | WebView gone during a drag | Gap left: the factory sets the WebView before input can arrive. The null path of `split` is unit-tested (`without a WebView yet nothing pans`) |
| `MailBodyPan.kt:177`, `178`, `180` (one branch each: `target()` null during the horizontal fling) | WebView gone during a fling | Gap left: same reason; the fling stops on null, which is the safe outcome |
| `MailBodyPan.kt:117`, `159` (instructions only) | Coroutine / lambda epilogues made by the compiler | Not material |
| `MailBodyView.kt:198` (one branch in the public `MailBodyView` overload) | The production factory with `images` / `openExternally` | Not material: the production WebView path is covered by `MailWebViewTest` and `MailBodyViewTest`; the UI tests use the `create` overload |
| `MailBodyView.kt:87`, `239` | Present before this task (scheme set init, `ActivityNotFoundException` catch) | Outside the task scope |

## Unable to test

- The CR-1 on-device effect: the body scrolling up with the page under the finger during a vertical drag. Robolectric does not reproduce it; `FingerVelocity` is unit-tested instead. Follow-up: criterion 5 on a device (a vertical fling started on the body should keep its speed).
- A real link opening, a real pinch-zoom and a real WebView receiving or cancelling MotionEvents. Under Robolectric the WebView is never laid out (0x0), so it gets no touches; the tests check that `mailBodyPan` leaves those events unconsumed. Follow-up: criterion 5.
- TalkBack scrolling (AC3). Needs a device with an accessibility service. Follow-up: criterion 5.
- Dragging on the attachments list: the fixture has no attachments; it uses the same page `verticalScroll` as the headers, which are tested.
- CR-4 (a touch outside the body does not stop a running horizontal body fling) is deferred by the parent and recorded as an assumption; not tested.
- The full Android unit suite and `.taskcheck` were not run by this helper; the parent runs them.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The fixer's CR-1 and CR-2 changes are covered by unit and Compose UI regression tests, all 377 `mail.read` tests and ktlintCheck pass on ee4b0774e, and no test file was changed in round 2. Device-only behavior (criterion 5: vertical fling speed on a device, real link tap, pinch-zoom, TalkBack) remains for manual verification.
