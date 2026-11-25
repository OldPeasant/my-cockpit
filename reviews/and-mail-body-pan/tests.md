# Test Report

Date: 2026-11-25

Task: and-mail-body-pan - Mail body: pan diagonally instead of axis-locked scrolling

Scope: task commit a31a5716cb76f8090e02f86c2f97db8bdd398506 on base 5c923a8e29f4da3647378a4f461384a3badb2d8b, as given by the parent. During this run the parent rebased it: the same change is now 223d710149601c94fc0f242f00fc60cc0905be4d (parent c50f0ff4bc45d189c069519c845e385f81130ba9), and HEAD is 6bddc9f474206fc34c162b6a9ed901514d69d02d. `git diff a31a5716c 6bddc9f47` touches only `tasks/and-mail-body-pan.md` and `reviews/and-mail-body-pan/review.md`, so the production code is the same. Android only.

Requirements:
- `tasks/and-mail-body-pan.md` acceptance criteria 1 to 5
- `docs/ARCHITECTURE.md` "Coverage" (Android: JaCoCo via `createDebugUnitTestCoverageReport`)
- Parent instructions: test a tap, movement below the touch slop, a second finger before the slop, and a drag on the headers

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailBodyPanTest.kt` (extended; uncommitted)

## Tests added or updated

Count: 18 added (the file had 9 tests and now has 27). No existing test was weakened or removed. The test-only `WideWebView` now has a settable `range` and the body passed to `MailBodyView` now has a recorder modifier. Neither changes what the existing tests assert.

Added:
- Unit (HorizontalPan / split / atEdge): `at an edge the sub-pixel carry is dropped, so the way back starts fresh`, `a reset forgets the sub-pixel carry of the previous drag`, `a mostly horizontal delta keeps its small vertical part`, `no horizontal movement is never an edge`
- Unit (MailWebView as MailPanTarget, Robolectric): `the WebView pans within its content width at the current zoom minus the view width` (range minus extent, clamped at 0, so a pinch-zoom that widens the range widens the pan), `panning the WebView scrolls it sideways only`
- Compose UI (Robolectric, full `MailReadContent` page):
  - `a tap on the body is not consumed, so the WebView gets the link click`
  - `a move below the touch slop is not consumed and scrolls nothing`
  - `a drag past the touch slop is consumed from then on, up to the release`
  - `a second finger before the slop leaves the pinch to the WebView`
  - `a second finger after the slop keeps the one-finger drag going`
  - `a mostly horizontal swipe keeps its small vertical part for the page`
  - `dragging towards the top-left edges moves nothing past them`
  - `a fling stops exactly at the right edge of the content`
  - `touching the body again stops a running fling`
  - `a mail no wider than the screen scrolls only vertically, also on a diagonal fling`
  - `a fast horizontal fling pans on past the finger and leaves the page where it is`
  - `dragging the headers still scrolls the page and leaves the body alone`

About the test method: under Robolectric the WebView is never laid out. Its `setFrame` goes to a stub provider, so it stays 0x0 and gets no MotionEvents, even when `layout()` is called on it directly. I checked this with a diagnostic test and then removed it. So the "left to the WebView" tests check the event from Compose instead. A recorder `pointerInput` sits outside the body and runs in the Main pass, after the body's Initial-pass handler. It records whether `mailBodyPan` consumed each change. The AndroidView interop filter sends ACTION_CANCEL to the WebView as soon as a change is consumed and passes the event on otherwise, so unconsumed changes are what the WebView gets for link taps and pinch-zoom. Whether a real WebView actually opens the link or zooms can only be shown on a device (criterion 5).

## Requirement coverage

All rows ran in the same command, `android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.*'`, at the final snapshot.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 diagonal drag moves body horizontally and page vertically; no axis lock either way | `a diagonal delta pans sideways ...`, `a mostly vertical drag keeps its small horizontal part ...`, `a mostly horizontal delta keeps its small vertical part`, `a diagonal swipe on a wide body changes both ...`, `a mostly horizontal swipe keeps its small vertical part for the page`, `a second finger after the slop keeps the one-finger drag going` | see above | Pass |
| AC2 fling continues in both axes and stops at the edges | `a fast diagonal fling keeps going in both axes ...`, `a fling stops exactly at the right edge of the content`, `a fast horizontal fling pans on past the finger ...`, `dragging towards the top-left edges moves nothing past them`, `touching the body again stops a running fling`, `the edges stop a fling`, `the pan is clamped to the content range`, `at an edge the sub-pixel carry is dropped ...` | see above | Pass |
| AC3 link tap still reaches the WebView | `a tap on the body is not consumed ...`, `a move below the touch slop is not consumed ...`, `a drag past the touch slop is consumed from then on ...` | see above | Pass (at the Compose level; the real link opening was not run here, see Unable to test) |
| AC3 pinch-zoom still works; panning respects the zoomed width | `a second finger before the slop leaves the pinch to the WebView`, `the WebView pans within its content width at the current zoom minus the view width` | see above | Pass (at the Compose level and for the range formula; real zoom: not run, needs a device) |
| AC3 a mail no wider than the screen scrolls only vertically | `a mail no wider than the screen does not pan sideways`, `a mail no wider than the screen scrolls only vertically, also on a diagonal fling`, `a plain vertical swipe on the body scrolls the page only` | see above | Pass |
| AC3 dragging outside the body (header) scrolls the page as before | `dragging the headers still scrolls the page and leaves the body alone` | see above | Pass (attachments not tested separately: the same page `verticalScroll` handles them, and the fixture has no attachments) |
| AC3 TalkBack scrolling still works | none | none | Not run: needs a device with TalkBack. The unit tests read the page's `VerticalScrollAxisRange` semantics, which still exist, but no accessibility scroll action was run |
| AC4 unit tests for delta split / clamp; Compose UI test for a diagonal swipe | everything above | see above | Pass |
| AC5 real device or emulator | none | none | Not run: no device or emulator was available to this helper; it is the parent's manual check |

## Focused test execution

Command:

`android/gradlew -p android testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.*'` (final run with `--rerun` plus `createDebugUnitTestCoverageReport`, see Coverage), then `android/gradlew -p android ktlintCheck`

Result:

Pass. 26 test classes in `mail.read`, 0 failures, 0 skipped (the run before the last 4 tests were added counted 370 tests). `MailBodyPanTest`: tests="27" skipped="0" failures="0" errors="0". ktlintCheck: BUILD SUCCESSFUL.

## Full test execution

Command:

`android/gradlew -p android testDebugUnitTest` (all Android unit tests)

Result:

Not run. The task only changes `mail/read`, and the whole package suite passed. The full Android suite and `.taskcheck` (`sh scripts/check.sh`) are long, and running them would block the parent's Gradle. The parent runs `.taskcheck` before `done`.

## .taskcheck

Result:

Not run (left to the parent worker, see above).

## Bugs found

None. Every new test passed against the unchanged production code.

## Coverage

Status: verified (whole-file, scoped test selection `ch.sonensei.cockpit.mail.read.*`)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured line by line with tooling. By hand: of the changed lines in `MailBodyView.kt`, only line 198 (the public `MailBodyView` overload) has a missed branch. In `MailBodyPan.kt` the whole file is new, so whole-file equals changed-line coverage for it.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-mail-body-pan; base 5c923a8e29f4da3647378a4f461384a3badb2d8b with task commit a31a5716cb76f8090e02f86c2f97db8bdd398506 (from the parent). After the rebase: 223d710149601c94fc0f242f00fc60cc0905be4d on c50f0ff4bc45d189c069519c845e385f81130ba9. Measured HEAD 6bddc9f474206fc34c162b6a9ed901514d69d02d. The file scope comes from `git diff --stat` of the task commit.
- Source/test snapshot identifier (including uncommitted inputs): sha256 of `MailBodyPan.kt` a549af3fbae271ef5c1243f6757d451dee3a72ff8e9b1c0bc1c4ef6f3e8d23d2, `MailBodyView.kt` 1a20af415d2acaf7bd4ad4216b43b2b21e0e507a286ccc4a963e77bb1605252d, `MailBodyPanTest.kt` (uncommitted) a00ad9acb5e17d43401c887f67da0794eaa8154097fded62e882607d5b43ea8a. The same hashes were recorded before and after the run.
- Exact included files; renamed/deleted/excluded files and reasons: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyPan.kt` and `.../MailBodyView.kt` are included. The test file is excluded because it is a test. Nothing was renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`. Command `android/gradlew -p android testDebugUnitTest --rerun --tests 'ch.sonensei.cockpit.mail.read.*' createDebugUnitTestCoverageReport`, run from the repository root with the Gradle project `android`. Only `mail.read` tests were selected, so the numbers count hits from that package only.
- Run start/completion time and exit status: 2026-11-25T07:36:10Z to 07:36:50Z, BUILD SUCCESSFUL (2 tasks executed: the rerun test task and the report).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, mtime 2026-11-25 08:36:50 +0100, sha256 56dc1a6d6ad591c89e91fc58d04c365e54f8ff71b2604eec6462994f7ee21cad
- Evidence outputs are fresh and inputs stayed unchanged: yes. `--rerun` forced the test task, the report was rewritten at the end of the run, and the input hashes were the same before and after.
- Final run after latest test additions and production fixes: yes. No production fixes were made.

Values below use covered/total (percentage). "Statements" means JaCoCo INSTRUCTION counters.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyPan.kt` | 795/805 (98.8%) | 54/62 (87.1%) | 20/20 (100%) | 79/80 (98.8%) |
| `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyView.kt` | 609/612 (99.5%) | 55/56 (98.2%) | 30/31 (96.8%) | 90/91 (98.9%) |
| Aggregate (summed counters, both files, none excluded or unmeasured) | 1404/1417 (99.1%) | 109/118 (92.4%) | 50/51 (98.0%) | 169/171 (98.8%) |

Before the new tests (same command, 07:34Z run): `MailBodyView.kt` lines 89/91 (`MailWebView.maxPanX` at line 126 was not covered at all), and `MailBodyPan.kt` was the same as now.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailBodyView.kt:126` (`maxPanX` = range minus extent) | AC3: panning respects the zoomed width | Covered now by `the WebView pans within its content width at the current zoom minus the view width` |
| `MailBodyPan.kt:107` (`?: break` when the down pointer has gone from the event) | The gesture stream loses the first pointer | Gap left: defensive. Compose always delivers the tracked pointer until its release |
| `MailBodyPan.kt:122`, `153`, `154`, `156` (`target()` null during a drag or fling) | No WebView yet | Gap left: the factory sets the WebView before any input can arrive. The null path of `split` is unit-tested (`without a WebView yet nothing pans`) |
| `MailBodyPan.kt:129` (dragging, no release seen: no fling) | Gesture cancelled mid-drag | Gap left: this cannot be produced from the Compose test input API, because a cancel arrives as a synthetic release. Low risk: no fling, which is the safe outcome |
| `MailBodyPan.kt:145`, `160` (one branch each of the nonzero-velocity checks) | Single-axis flings | Both single-axis flings run (`a fast horizontal fling ...`, `a plain vertical swipe ...`), but JaCoCo still shows one branch of each check as not taken (probably the velocity on the idle axis is not exactly 0, or the branch comes from the float comparison; not checked further). Behavior is checked: the page stays at 0 on a horizontal fling and scrollX stays at 0 on a vertical one |
| `MailBodyPan.kt:94`, `135`, `144`, `168` (instructions only) | Coroutine and lambda epilogues made by the compiler | Not material |
| `MailBodyView.kt:198` (one branch in the public overload) | The production factory with `images` / `openExternally` | Not material: the production WebView path is covered by `MailWebViewTest` and `MailBodyViewTest`. The UI tests use the `create` overload |
| `MailBodyView.kt:87`, `239` | Present before this task (scheme set init, `ActivityNotFoundException` catch) | Outside the task scope |

## Unable to test

- A real link opening, a real pinch-zoom and a real WebView receiving or cancelling MotionEvents. Under Robolectric the WebView is never laid out (0x0, its `setFrame` goes to a stub provider), so it gets no touches. The tests check instead that `mailBodyPan` leaves those events unconsumed, which is what makes the interop pass them on. Follow-up: AC5 on a device or emulator (parent).
- TalkBack scrolling (AC3). This needs a device with an accessibility service. Follow-up: the AC5 manual check.
- Dragging on the attachments list. The fixture has no attachments. It uses the same page `verticalScroll` as the headers, which are tested.
- The full Android unit suite and `.taskcheck` were not run by this helper (see above). The parent runs them.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found. Device-only behavior (AC5, real link tap, pinch-zoom, TalkBack) remains for the parent's manual verification.
