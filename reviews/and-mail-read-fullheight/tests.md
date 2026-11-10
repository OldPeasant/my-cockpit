# Test Report

Date: 2026-11-10

Task: and-mail-read-fullheight - Android read view: mail at full height in one page scroll, linked items below

Scope: `git diff d773e6bd198c0e07ee1fe038c130486982fbfdb7..114b43f75 -- android` (branch `main`, round 2, final revision). Task-owned code commits: `ffcbbcf3a` (implementation), `322f4e4fe` (ktlint format), `05cbd5ba6` (round-1 tests and reports), `114b43f75` (fixer: CR-3, `MailWebView.forgetHeight()` plus a regression test). The other commits after the base touch only `tasks/` (assume, verify, heartbeat). Working tree before this round: clean.

Requirements:
- `tasks/and-mail-read-fullheight.md` acceptance criteria and the recorded assumptions (wrap-content WebView, kept height only during a same-mail reload, zoom trade-off, fixed notice/progress bar, attachment list box)
- `reviews/and-mail-read-fullheight/fixes.md` (CR-3 fix intent: a height kept for one mail must not apply to another mail)
- `AGENTS.md`, `docs/ARCHITECTURE.md` ("Coverage", Android test conventions)
- Parent instructions (round 2: check the final code's tests, add only for remaining gaps; the test-writer owns Gradle)

## Test files changed

Round 2 (uncommitted):
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailWebViewTest.kt`

Already committed in earlier rounds (`05cbd5ba6`, `114b43f75`): `MailReadScreenTest.kt`, `MailReadNoteLinksTest.kt`, `MailBodyViewTest.kt`, `MailWebViewTest.kt`, `MailReadTestSupport.kt` (helper `layOutWebView`).

No production file was changed by the test-writer.

## Tests added or updated

Count: 2 new tests in round 2 (no existing test changed or weakened). In total for the task: 21 tests from round 1, 1 regression test from the fixer, 2 from round 2.

Round 2 gap: the fixer's regression test `another mail loaded before the reload finished drops the kept height` (MailBodyViewTest) checks that `keptHeight` drops to 0. It does not check that the view then measures at the new mail's own height, or that `forgetHeight` asks for a new layout only when a height was kept. Added to MailWebViewTest:
- `another mail drops a kept height at once, so it is measured at its own content height`: keep a height 900px above the content, then `forgetHeight()`. The test checks that `keptHeight` is 0, a layout is requested, and the next unspecified-height measure returns the content height. Without the `requestLayout()` in `forgetHeight`, the layout-request assertion fails.
- `another mail without a kept height asks for no new layout`: covers the `keptHeight == 0` branch, which every first load takes (`loaded == null`). It checks that no relayout is requested, so the first load causes no extra layout pass.

Tests from earlier rounds remain as listed in round 1. MailReadScreenTest (8), MailReadNoteLinksTest (2), MailBodyViewTest (4 + fixer's 1) and MailWebViewTest (7) cover the page structure, the order of the page contents, the uncapped links, plain text inside the page, kept/forgotten heights and the measure logic.

## Requirement coverage

Focused command (F): `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.*'` (Robolectric, JVM unit tests), run 2026-11-10 on the final code with the round-2 tests.
Coverage command (C): `cd android && ./gradlew createDebugUnitTestCoverageReport` (whole Android debug unit-test suite).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: toolbar and previous/next row fixed; below them one vertical scroll with headers, attachments, images banner, reply buttons, body, links in that order; scrolling past the headers leaves the mail filling the page | `headers, attachments, banner, reply buttons, body and links are one page in that order`; `the toolbar and the previous-next row stay outside the page`; `the body gets its full height, and scrolling past the headers leaves it filling the page`; `the links after a long mail are reached by scrolling to the end of the page`; `the next mail starts at the top of the page, images loading keep the position` | F, C | Pass |
| AC2: HTML mail at full content height, no own vertical scroll, height follows the content, kept across a same-mail reload (Load images) until the page finishes, dropped when another mail loads; short mails leave no empty space | `the body gets its full height, ...`; `a short mail leaves no empty space before the links`; `the body is a web view as tall as its content, without its own vertical scroll`; `the web view is as tall as its content and does not scroll vertically by itself`; `the body height is the content height, but not less than a kept height`; `a kept height holds the web view's height in a scrolling page until the page finished`; `a kept height never overrides a height the parent fixes`; `a finished page without a kept height asks for no new layout`; `a finished page in a plain web view is ignored`; `the same mail reloaded with its images keeps its height until the page finished`; `another mail starts at its own height`; `another mail loaded before the reload finished drops the kept height` (fixer); `another mail drops a kept height at once, so it is measured at its own content height` (round 2); `another mail without a kept height asks for no new layout` (round 2); `a recomposition with the same document neither reloads nor keeps a height` | F, C | Pass for the height logic. Real Chromium content measuring: Not run (no Chromium under Robolectric, see "Unable to test") |
| AC3: wide viewport / overview mode, pinch zoom kept | `wide newsletters stay fitted to the width and zoomable`; existing `the settings lock the untrusted html down` | F, C | Pass for the settings. Actual zoom and horizontal panning: Not run (needs a device) |
| AC4: plain-text mail in the same page scroll, no nested scroll box, stays selectable | `a long plain-text mail scrolls with the page, not in a box of its own`; existing `a text-only mail is shown as text, without the web view` | F, C | Pass for the structure. Selection gesture: Not testable here (`SelectionContainer` unchanged by the task) |
| AC5: linked-items panel not height-capped, all items at the end of the page; opening an item still works | `a long list of linked items is laid out in full, not capped, and its last item is reached by scrolling the page`; `a tapped linked item at the end of the page still opens it`; existing `the route shows the links area for a loaded mail` | F, C | Pass (opened through a fake panel. Real LinkedItemsSection navigation and back: Not run) |
| AC6: WebView lock-down unchanged (lockDownForMail, CSP, shouldInterceptRequest, shouldOverrideUrlLoading with the data: pass-through) | existing MailWebViewTest lock-down suite and MailBodyViewTest `the mail is loaded inside its locked-down page`, all against `MailWebView` | F, C | Pass |
| AC7: Compose/Robolectric tests for the new structure, no heightIn cap on links, body height logic | all tests above | F, C | Pass |
| AC8: manual check on an emulator or device | none | none | Not run: no emulator or device available; the parent recorded the skip |
| AC9: `./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` | ktlintCheck and testDebugUnitTest (focused) plus the full unit suite under C | F, C | ktlintCheck: Pass. Full unit suite: Pass. lintDebug and `sh scripts/check.sh`: Not run in round 2 (the parent runs `.taskcheck` before `done`) |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.*'`

Result:

Pass: BUILD SUCCESSFUL, ktlint clean. All 23 `ch.sonensei.cockpit.mail.read` suites passed: 332 tests, 0 failures, 0 errors, 0 skipped. This includes MailWebViewTest 21, MailBodyViewTest 7, MailReadScreenTest 42 and MailReadNoteLinksTest 18. The counts come from `android/app/build/test-results/testDebugUnitTest/TEST-ch.sonensei.cockpit.mail.read.*.xml`.

## Full test execution

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport` (runs the whole Android `testDebugUnitTest` suite with coverage)

Result:

Pass: 5438 tests, 0 failures, 0 errors, 0 skipped (summed from `android/app/build/test-results/testDebugUnitTest/*.xml`). This is 5435 from round 1, plus 1 from the fixer and 2 from round 2.

## .taskcheck

Result:

Not run in round 2. Round 1 ran `sh scripts/check.sh` on the pre-fix revision `526951e5c` and passed. That result is stale for the final revision. The parent runs `.taskcheck` (including `lintDebug`) on the final code before `done`.

## Bugs found

No production defect was found in round 2. The CR-3 fix behaves as intended: a height kept for one mail is dropped when another mail loads, the view then measures at the new mail's content height, and a first load causes no extra layout.

Round-1 open question `TW-1` (the attachment list keeps its own 168dp scroll box inside the page, `MailAttachments.kt`, outside `touches`) is unchanged in the code. The parent recorded it as an assumption in the task file. No failing test was kept for it because the requirement is ambiguous there.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. Method: new-side added/modified line numbers from the hunk headers of `git diff -U0 d773e6bd1 114b43f75` intersected with the JaCoCo XML line entries (executable lines only) of the same run. 48/48 changed executable lines covered; branches on changed lines 27/28. Per file: MailBodyView.kt 34/34 lines, 22/22 branches; MailReadScreen.kt 14/14 lines, 5/6 branches.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-mail-read-fullheight`; base `d773e6bd198c0e07ee1fe038c130486982fbfdb7` (from the parent); HEAD `114b43f75`; task-owned code commits `ffcbbcf3a`, `322f4e4fe`, `05cbd5ba6`, `114b43f75`. Ownership was checked with `git log d773e6bd1..HEAD` and the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): production blobs equal HEAD (`MailBodyView.kt` dc9277e4025a885dd650799c4d2ff190805b1aa0, `MailReadScreen.kt` 45a501eae42f96c90295c69f80df25c9f997d05f). Test blobs: uncommitted MailWebViewTest 04c48b6a16c32c0bf0e3d5fdf47f34eb76554686. Committed: MailBodyViewTest 927ab6844a1bc8e7ca80a1fb7e7c1e4315c60411, MailReadScreenTest 14671fc94c7f008961dfc3683d9c1187738c6d72, MailReadNoteLinksTest 8af6e0f523b5c23cee24557a805eca107d18c860, MailReadTestSupport 14d2db1ae8ff7c8ff8c6e8d0053cb35496d7530d.
- Exact included files; renamed/deleted/excluded files and reasons: included `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyView.kt` and `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt`. Excluded: `tasks/and-mail-read-fullheight.md` (task metadata) and `reviews/and-mail-read-fullheight/*` (reports). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; `./gradlew createDebugUnitTestCoverageReport` in `android/`; whole Android debug unit-test suite.
- Run start/completion time and exit status: started after 2026-11-10T06:47:44Z (the focused run came first), report written 2026-11-10T06:52:08Z, exit 0 (BUILD SUCCESSFUL in 3m 39s).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 faa0ab19211df9ba5c8cdfe821602c4addf3e62a1340afbe7cda35aa93480444.
- Evidence outputs are fresh and inputs stayed unchanged: the report mtime falls inside the run window. Gradle re-executed the test task because the test sources had changed since the last coverage run. Blob hashes taken after the run match the hashes taken before it.
- Final run after latest test additions and production fixes: yes (it includes the fixer's CR-3 change and the round-2 tests; no edits after the run).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. JaCoCo has no statement counter, so the Statements column shows the instruction counter.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyView.kt` | 476/479 (99.4%) | 53/54 (98.1%) | 24/25 (96.0%) | 82/83 (98.8%) |
| `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt` | 6021/6330 (95.1%) | 326/395 (82.5%) | 123/140 (87.9%) | 439/447 (98.2%) |
| Aggregate (summed counters, both files measured, none excluded) | 6497/6809 (95.4%) | 379/449 (84.4%) | 147/165 (89.1%) | 521/530 (98.3%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailBodyView.kt` `forgetHeight` (both branches) | CR-3: another mail must start at its own height; first loads must not cause an extra layout | Covered: round-2 tests `another mail drops a kept height at once, ...` and `another mail without a kept height asks for no new layout`, plus the fixer's MailBodyViewTest regression test |
| `MailReadScreen.kt:168` (1 missed branch: `state.message?.id` null) | the route's body key | Not reachable: the body is composed only when `state.message` is non-null. Justified gap |
| `MailReadScreen.kt` other missed lines/branches (99-180, 285, 296, 369-510, 627-765) | route wiring, default `body` parameter, menus, navigation, attachment and images callbacks | Not changed by this task (pre-existing code in the same file). No action |
| `MailBodyView.kt:189` (1 branch in the factory lambda), `:216` (`ActivityNotFoundException` catch in `openExternally`) | pre-existing link handling | Not changed by this task. No action |

## Unable to test

- Chromium's real wrap-content measuring is not available under Robolectric. This covers the height following the rendered content, re-measuring after cid or remote images load, after the "Load images" reload, and with pinch zoom. Robolectric has no Chromium, and `WebView.layout()` and content measuring are stubbed. The kept/forgotten-height logic, the `onMeasure` override and the `onPageFinished` hook are tested with a simulated laid-out height. Follow-up: the AC8 manual check on a device.
- Pinch zoom and horizontal panning inside the page scroll (AC3): need a device. Only the settings are asserted.
- Text selection in the plain-text body (AC4): `SelectionContainer` is present and unchanged by the task. No selection gesture test.
- Opening a real linked item and going back (AC5): the real `LinkedItemsSection` needs Hilt view models, so the page tests use a fake panel.
- AC8 manual verification: no emulator or device available here.

## Summary

Tests added and all validation passed. Round 2 added 2 MailWebView tests that close the remaining gap around the CR-3 fix (`forgetHeight` relayout and measured height). The focused suite (332 tests) and the full Android unit suite (5438 tests) pass, and ktlint is clean. Task-scoped coverage is fresh for the final revision: 48/48 changed executable lines, 27/28 changed-line branches. No production defects were found. `lintDebug` and `.taskcheck` remain for the parent on the final code, and the Chromium-dependent height behavior needs the AC8 device check.
