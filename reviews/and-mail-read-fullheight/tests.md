# Test Report

Date: 2026-11-10

Task: and-mail-read-fullheight - Android read view: mail at full height in one page scroll, linked items below

Scope: `git diff d773e6bd198c0e07ee1fe038c130486982fbfdb7..526951e5ce743d9cd46874739a664cd9349563de -- android` (branch `main`, clean before the new tests). Task-owned commits: `9a516438eead31f165b25b52036cf4a4ecaacdad` (implementation) and `526951e5ce743d9cd46874739a664cd9349563de` (ktlint format). The parent named the commits `5903136be` and `983ccbf84`; those hashes are not in this clone's history from the base, the two commits above are the only code commits after the claim (`d773e6bd1`) and `1d9dcce47` (assume, tasks/ only), and their diff matches the parent's description exactly.

Requirements:
- `tasks/and-mail-read-fullheight.md` acceptance criteria and the recorded assumption (wrap-content WebView, kept height only during a same-mail reload, zoom trade-off)
- `AGENTS.md`, `docs/ARCHITECTURE.md` ("Coverage", Android test conventions)
- Parent instructions (test list, focused command, no concurrent Gradle)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadScreenTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadNoteLinksTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailBodyViewTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailWebViewTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadTestSupport.kt` (new helper `layOutWebView`: under Robolectric `WebView.layout()` sets no size because WebView hands `setFrame` to its stubbed Chromium provider, so the helper sets the frame fields to simulate a laid-out height; the height logic itself runs unmocked)

No production file was changed.

## Tests added or updated

Count: 21 new tests (no existing test changed or weakened)

- MailReadScreenTest (8): `headers, attachments, banner, reply buttons, body and links are one page in that order`; `the toolbar and the previous-next row stay outside the page`; `the body gets its full height, and scrolling past the headers leaves it filling the page`; `the links after a long mail are reached by scrolling to the end of the page`; `a short mail leaves no empty space before the links`; `a long plain-text mail scrolls with the page, not in a box of its own`; `the next mail starts at the top of the page, images loading keep the position`; `the route shows the real body inside the page, below the headers`
- MailReadNoteLinksTest (2): `a long list of linked items is laid out in full, not capped, and its last item is reached by scrolling the page`; `a tapped linked item at the end of the page still opens it`
- MailBodyViewTest (4): `the body is a web view as tall as its content, without its own vertical scroll`; `the same mail reloaded with its images keeps its height until the page finished`; `another mail starts at its own height`; `a recomposition with the same document neither reloads nor keeps a height`
- MailWebViewTest (7): `wide newsletters stay fitted to the width and zoomable`; `the web view is as tall as its content and does not scroll vertically by itself`; `the body height is the content height, but not less than a kept height`; `a kept height holds the web view's height in a scrolling page until the page finished`; `a kept height never overrides a height the parent fixes`; `a finished page without a kept height asks for no new layout`; `a finished page in a plain web view is ignored`

These would fail on the base revision: there was no `PAGE` container, the body was `weight(1f)` (not 2000dp), the links were capped at 240dp in their own scroll box, the plain text had its own `verticalScroll`, and `MailWebView`/`mailBodyHeight`/the `key` parameter did not exist.

## Requirement coverage

Focused command (F): `cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.*'` (Robolectric, JVM unit tests).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: toolbar and previous/next row fixed; below them one vertical scroll with headers, attachments, images banner, reply buttons, body, links in that order; scrolling past the headers leaves the mail filling the page below the fixed bar | `headers, attachments, banner, reply buttons, body and links are one page in that order`; `the toolbar and the previous-next row stay outside the page`; `the body gets its full height, and scrolling past the headers leaves it filling the page`; `the links after a long mail are reached by scrolling to the end of the page`; `the next mail starts at the top of the page, images loading keep the position` | F | Pass |
| AC2: HTML mail at full content height, no own vertical scroll, height follows content and is kept across a same-mail reload (Load images) until the page finished; short mails leave no empty space | `the body gets its full height, ...` (2000dp fake body not squeezed); `a short mail leaves no empty space before the links`; `the body is a web view as tall as its content, without its own vertical scroll`; `the web view is as tall as its content and does not scroll vertically by itself`; `the body height is the content height, but not less than a kept height`; `a kept height holds the web view's height in a scrolling page until the page finished`; `a kept height never overrides a height the parent fixes`; `a finished page without a kept height asks for no new layout`; `a finished page in a plain web view is ignored`; `the same mail reloaded with its images keeps its height until the page finished`; `another mail starts at its own height`; `a recomposition with the same document neither reloads nor keeps a height` | F | Pass (logic only; real Chromium content measuring is not available under Robolectric, see "Unable to test") |
| AC3: wide viewport / overview mode, pinch zoom kept | `wide newsletters stay fitted to the width and zoomable` (settings); existing `the settings lock the untrusted html down` | F | Pass for settings; actual zoom and horizontal panning: Not run (needs a device) |
| AC4: plain-text mail in the same page scroll, no nested scroll box, stays selectable | `a long plain-text mail scrolls with the page, not in a box of its own`; existing `a text-only mail is shown as text, without the web view`; existing `the linked items panel is shown below a text-only mail and while an action runs` | F | Pass for structure; selection gesture: Not testable here (the `SelectionContainer` is unchanged by the task, no selection test added) |
| AC5: linked-items panel not height-capped, all items at the end of the page; opening an item still works | `a long list of linked items is laid out in full, not capped, ...` (30 x 48dp = 1440dp laid out, last item reached by page scroll); `a tapped linked item at the end of the page still opens it`; existing `the linked items panel is shown below a mail`, `the route shows the links area for a loaded mail` | F | Pass (open via a fake panel's click; real LinkedItemsSection navigation and back: Not run, covered by existing linking tests outside this scope) |
| AC6: WebView lock-down unchanged (lockDownForMail, CSP, shouldInterceptRequest, shouldOverrideUrlLoading with data: pass-through) | existing MailWebViewTest suite (settings, CSP directives, blocked hosts, `the mail document and data images are left to the web view, ...`, link handling) and MailBodyViewTest `the mail is loaded inside its locked-down page`, all now running against `MailWebView` | F | Pass |
| AC7: Compose/Robolectric tests for the new structure, no heightIn cap on links, body height logic | all 21 tests above | F | Pass |
| AC8: manual check on an emulator or device | none | none | Not run: no emulator or device available to the test-writer; parent records the skip |
| AC9: `./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` | full suite via coverage run; `.taskcheck` | see below | see "Full test execution" and ".taskcheck" |

## Focused test execution

Command:

`cd android && ./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.*'`

Result:

Pass (final run: no failures, ktlint clean; ktlintFormat only reformatted the new test code). Earlier iterations failed on test mistakes that were fixed in the tests: clipped `boundsInRoot` for nodes scrolled out of view (now unclipped bounds), WebView height not settable by `layout()` under Robolectric (now `layOutWebView`), View's measure cache (now `forceLayout()` as a reload does), and a second scroll node from the attachment list (see "Bugs found" / TW-1).

## Full test execution

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport` (runs the whole Android `testDebugUnitTest` suite with coverage)

Result:

Pass: 5435 tests, 0 failures, 0 errors, 0 skipped (summed from `android/app/build/test-results/testDebugUnitTest/*.xml`). `lintDebug` was not run separately by the test-writer; it is part of `.taskcheck` below.

## .taskcheck

Result:

Pass: `sh scripts/check.sh` (repo root, with the new tests in the working tree) exited 0 with "all checks passed": tfp gate tests 22 OK, server `./gradlew check` OK, web check (eslint, 4815 vitest tests in 196 files, build) OK, android `./gradlew ktlintCheck testDebugUnitTest lintDebug` OK. Run 2026-11-10 after the coverage run.

## Bugs found

No production defect was found that a test proves against an acceptance criterion. One open question for the parent:

### Attachment list keeps its own capped scroll box inside the page

Finding ID:

`TW-1`

Test:

`headers, attachments, banner, reply buttons, body and links are one page in that order` (its single-scroll assertion excludes the attachment list's subtree so it tests the task's own change; without that exclusion it finds 2 vertical scroll nodes)

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailAttachments.kt:145` (`Column(Modifier.heightIn(max = 168.dp).verticalScroll(rememberScrollState()))`, KDoc line 128: "a long list scrolls on its own, so the mail body keeps its room")

Requirement:

AC1 "the read view is one vertical scroll: headers, attachments, ...". The task's `touches` do not include `MailAttachments.kt`, and the AC does not say explicitly whether a short attachment list may keep its own box.

Expected behavior:

Arguably, attachments scroll with the page (no nested scroll box), since the reason for the cap (the body's room) no longer applies.

Actual behavior:

With more than about 3 attachments, the list is a 168dp nested scroll box inside the page scroll.

Severity:

Low

Fix required:

Parent decision: either remove the cap in `MailAttachments.kt` (outside `touches`, needs an `assume`) or record that the nested attachment box is intended. No failing test was kept because the requirement is ambiguous here.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. Method: new-side added/modified line numbers from the hunk headers of `git diff -U0 d773e6bd1 526951e5c` intersected with the JaCoCo XML line entries (executable lines only) of the same run: 44/44 changed executable lines covered; branches on changed lines 25/26 (JaCoCo branch counters on those lines). MailBodyView.kt 30/30 lines, 20/20 branches; MailReadScreen.kt 14/14 lines, 5/6 branches.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-mail-read-fullheight`; base `d773e6bd198c0e07ee1fe038c130486982fbfdb7` (claim commit, from the parent); HEAD `526951e5ce743d9cd46874739a664cd9349563de`; task-owned code commits `9a516438e`, `526951e5c` (verified with `git log d773e6bd1..HEAD` and the task's `touches`).
- Source/test snapshot identifier (including uncommitted inputs): production blobs equal HEAD (`MailBodyView.kt` 8c8d7e4b35c689c9a1ddd630da056ac8abdde18e, `MailReadScreen.kt` 45a501eae42f96c90295c69f80df25c9f997d05f). Uncommitted test blobs (`git hash-object`): MailWebViewTest 0b6d24f5cf87829d120ba4643c444dc5cf6a0f2c, MailBodyViewTest 79f73f8767d3d3974f0a4e3f1d2e82b8a3147cf2, MailReadScreenTest 14671fc94c7f008961dfc3683d9c1187738c6d72, MailReadNoteLinksTest 8af6e0f523b5c23cee24557a805eca107d18c860, MailReadTestSupport 14d2db1ae8ff7c8ff8c6e8d0053cb35496d7530d.
- Exact included files; renamed/deleted/excluded files and reasons: included `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyView.kt`, `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt`. Excluded: `tasks/and-mail-read-fullheight.md` (task metadata). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`; `./gradlew createDebugUnitTestCoverageReport` in `android/`; whole Android debug unit-test suite.
- Run start/completion time and exit status: started 2026-11-10T06:37:36Z, report written 2026-11-10T06:41:17Z, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 e4b0839050a357715d1a1ce6fa5964444edd50301e7118aadbb1245b7ff00b1e.
- Evidence outputs are fresh and inputs stayed unchanged: report mtime is inside the run window; Gradle reran the tests because the test sources had changed; source/test blob hashes taken after the run match the last focused run's inputs (no edits in between).
- Final run after latest test additions and production fixes: yes (no production fixes were made; no test edits after the coverage run).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. JaCoCo has no statement counter; the instruction counter is shown under Statements.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyView.kt` | 464/467 (99.4%) | 51/52 (98.1%) | 23/24 (95.8%) | 78/79 (98.7%) |
| `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt` | 6021/6330 (95.1%) | 326/395 (82.5%) | 123/140 (87.9%) | 439/447 (98.2%) |
| Aggregate (summed counters, both files measured, none excluded) | 6485/6797 (95.4%) | 377/447 (84.3%) | 146/164 (89.0%) | 517/526 (98.3%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailReadScreen.kt:168` (1 missed branch: `state.message?.id` null) | the route's body key | Not reachable: the body is only composed when `state.message` is non-null. Justified gap |
| `MailReadScreen.kt:294` (default `body` lambda of `MailReadContent`) | default parameter; tests always pass a fake body, the route passes the real one | Pre-existing default, not changed by the task. Justified gap |
| `MailReadScreen.kt` other missed lines (99-180, 234-244, 369-510, 627-765) | route wiring, menus, navigation, attachment and images callbacks | Not changed by this task (pre-existing code in the same file). No action |
| `MailBodyView.kt:87` (companion init of `EXTERNAL_SCHEMES`, 2 instr.), `:180` (1 branch in the factory lambda), `:207` (`ActivityNotFoundException` catch) | pre-existing link handling | Not changed by this task. No action |

## Unable to test

- Chromium's real wrap-content measuring (height following rendered content, re-measuring after cid/remote images load, after "Load images" reload, and with pinch zoom): Robolectric has no Chromium, `WebView.layout()` and content measuring are stubbed. The kept-height logic, `onMeasure` override and `onPageFinished` hook are tested with a simulated laid-out height. Follow-up: the AC8 manual check on a device (long newsletter, short mail, "Load images").
- Pinch zoom and horizontal panning inside the page scroll (AC3): needs a device; only the settings are asserted.
- Text selection in the plain-text body (AC4): `SelectionContainer` is present and unchanged by the task; no selection gesture test was added.
- Opening a real linked item and going back (AC5): the real `LinkedItemsSection` needs Hilt view models; the page tests use a fake panel. The route test confirms the links area is inside the page.
- AC8 manual verification: no emulator or device available here.

## Summary

Tests added and all validation passed. Task-scoped coverage is high (44/44 changed executable lines). One ambiguity (TW-1, attachment list's own capped scroll box inside the page) is returned to the parent for a decision; Chromium-dependent height behavior needs the manual device check of AC8.
