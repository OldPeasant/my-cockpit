# Test Report

Date: 2026-11-08

Task: and-capture-improve - Android capture: syntax preview, save and add another, attachments

Scope: task commits 4068cec04 (implementation), 0eee5e56d (WIP tests of an earlier test-writer run), 6c69e41fb (fixes CR-1..3) on base 6f9fedef671b1d367d5bdda14878f3f3aa6d5993; measured HEAD 6c69e41fb56671721383541181aa514921122482 plus the uncommitted test changes listed below. Task ownership came from the parent worker (base and commit list) and the task's `touches`. The diff `6f9fedef6..6c69e41fb` holds only these task commits.

Requirements:
- tasks/and-capture-improve.md (acceptance criteria 1-5 and the three recorded assumptions)
- server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/CaptureParser.kt (reference for "same parsing rules")
- reviews/and-capture-improve/review.md (CR-1..3) and fixes.md
- docs/ARCHITECTURE.md "Coverage", AGENTS.md project notes

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/capture/share/CaptureSheetCaptureTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/capture/share/ShareAddAnotherActivityTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/capture/share/CaptureSyntaxTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/capture/share/ShareViewModelCaptureTest.kt`

These changes are uncommitted in the working tree. Test-writer helpers may not commit, so the parent worker commits them. The WIP tests from 0eee5e56d (CaptureSyntaxTest, CaptureVocabularySourceTest, CaptureSheetCaptureTest, ShareViewModelCaptureTest, ShareAddAnotherActivityTest) were reviewed and kept.

## Tests added or updated

Count: 11 (2 assertions fixed, 1 test replaced, 8 tests added)

- Fixed: `CaptureSheetCaptureTest` "after save and add another the cleared title takes the focus" and `ShareAddAnotherActivityTest` "save and add another queues the share, keeps the sheet open and cleared, then a plain save closes it". They used `assertTextEquals("")`, but an empty field's `Text` semantics contain the placeholder "What is it?". They now check `SemanticsProperties.EditableText == ""`, as `CaptureEntryPointsTest` does. Production behavior was correct.
- Replaced (CR-2): "a tap on the attachment chip removes it too" is now "a tap on the attachment's name does not remove it, only its close button does". The chip has no `OnClick` semantics action, a tap on the file name removes nothing, and the "Remove beach.jpg" button removes it.
- Added (CR-3), `CaptureSheetCaptureTest`:
  - "a sheet recreated with its view model does not repeat the confirmation" uses `StateRestorationTester` for a rotation.
  - "after process death the next capture saved with add another is confirmed" restores the saved state while the view model's count drops back to 0. The next add-another save must show "Saved to Inbox". With the earlier `rememberSaveable` counter, `shown` would come back as 2 and 1 > 2 would show no toast, so this test fails on the pre-fix code. I worked this out by reading the code and did not run it against the old code, because production code may not be changed.
- Added (criterion 4), `CaptureSheetCaptureTest` with `@Config(qualifiers = "de")`:
  - "the new controls and chips are in German" checks "Speichern und weitere", "Dateien oder Fotos anhängen", "beach.jpg entfernen", "Erkannt", "Fällig …" and "Wartet auf Anna".
  - "waiting without a name is a plain German waiting chip" checks "Wartet".
- Added (criterion 1 / CR-1 parity), `CaptureSyntaxTest`:
  - "only ASCII whitespace separates tokens, as on the server (CR-1)": U+00A0 and U+202F do not start a token, and tab, VT and FF do.
  - "a no-break space does not end a token, so the longer name is unknown"
  - "a project title with runs of spaces or tabs is matched like single spaces"
- Added (criterion 3), `ShareViewModelCaptureTest`: "files cannot be removed once the capture is queued and the sheet closes". This covers the `saved` half of `removeFile`'s guard, which had no test.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. Parsed project/contexts/due/waiting shown as chips while typing, same rules as web/server, offline, no server call | CaptureSyntaxTest (26, mirrors the server's CaptureParserTests, plus the ASCII-whitespace parity tests); CaptureVocabularySourceTest (7, server status/position ordering, broken cache); CaptureSheetCaptureTest syntax-chip tests (6); ShareViewModelCaptureTest preview tests (6, incl. "the preview is local only and queues nothing"); ShareAddAnotherActivityTest "a project from the offline GTD cache is previewed as a chip while typing" | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` | Pass |
| 2. "Save and add another" keeps the sheet open, cleared | CaptureSheetCaptureTest save-and-add-another tests (4) + SavedAndClearedToast tests (4, incl. CR-3); ShareViewModelCaptureTest add-another tests (7); ShareAddAnotherActivityTest "save and add another queues the share, keeps the sheet open and cleared, then a plain save closes it" (real activity, Hilt, Room queue) | same | Pass |
| 3. Attach button picks files/photos (system picker), attachments removable before saving, works with the offline queue | CaptureSheetCaptureTest attach/remove tests (10, incl. the CR-2 chip-body test); ShareViewModelCaptureTest file tests (19, incl. "picked files are copied into the offline queue on save and survive the source going away", "an unreadable picked file is reported and nothing is queued offline", and the new "files cannot be removed once the capture is queued...") | same | Pass |
| 3 (real system picker UI on a device) | none; the picker is replaced by an `ActivityResultRegistry` stand-in that checks the `OpenMultipleDocuments` contract and its `*/*` input | - | Not run: no device or emulator; Robolectric cannot show the system DocumentsUI |
| 4. New strings in English and German | CaptureSheetCaptureTest English assertions + the two `@Config(qualifiers = "de")` tests; lint `MissingTranslation` | same | Pass |
| 5. Tests cover the new behavior; `ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass | all of the above | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`; `sh scripts/check.sh` | Pass / Pass |
| CR-2 regression: only the close button removes an attachment | "a tap on the attachment's name does not remove it, only its close button does" | same | Pass |
| CR-3 regression: confirmation after process death | "after process death the next capture saved with add another is confirmed" | same | Pass |
| CR-1 regression: Unicode spaces split tokens as on the server | "only ASCII whitespace separates tokens, as on the server (CR-1)" | same | Pass, but limited: on OpenJDK `\s` was already ASCII, so this test would also pass before the fix. It records the parity rule but cannot show the ICU difference. |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.share.*'`

Result:

Pass. All 13 capture/share classes passed with 0 failures. Of the task's classes, CaptureSyntaxTest ran 26 tests, CaptureSheetCaptureTest 26, ShareViewModelCaptureTest 31 (32 after the last addition, which was rerun on its own: Pass), ShareAddAnotherActivityTest 2 and CaptureVocabularySourceTest 7.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` (final run 2026-11-08T18:19:05+01:00 to 18:23:17+01:00)

Result:

Pass. Exit status 0, BUILD SUCCESSFUL. 4157 Android unit tests ran with 0 failures, 0 errors and 0 skipped. ktlint and lint passed.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` (tfp, server, web, android) ran from 2026-11-08T18:24:13+01:00 to 18:25:42+01:00 with exit status 0 and printed "== all checks passed". This run used the working tree with the uncommitted test changes.

## Bugs found

None. Both earlier failing tests had wrong assertions (the placeholder is part of the `Text` semantics). The production behavior was correct.

## Coverage

Status: verified (fresh run on the snapshot below, exit status 0)

Metric: whole-file coverage of task-changed production files. This includes older code in those files and hits from tests that existed before this task.
Changed-line coverage: measured. The executable JaCoCo lines of the report were intersected with the added/modified lines of `git diff -U0 6f9fedef6..6c69e41fb` (new-side line numbers): **367/372 lines (98.7%)**. Branch counters on changed lines are 223/229 (97.4%); this is an indication only, not true changed-branch coverage. The 5 missed changed lines are ShareViewModel.kt 208, 209, 247, 248 and 258 (see below). Moved pre-existing lines count as changed.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-capture-improve; base 6f9fedef671b1d367d5bdda14878f3f3aa6d5993; HEAD 6c69e41fb56671721383541181aa514921122482; commits 4068cec04, 0eee5e56d and 6c69e41fb come from the parent worker. The file scope is `git diff --name-only 6f9fedef6..HEAD -- android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 6c69e41fb, with no production changes in the working tree. Uncommitted test files (sha256 prefix): CaptureSheetCaptureTest.kt 0eb3621ffd403ef5, ShareAddAnotherActivityTest.kt a11f0e7bc0172405, CaptureSyntaxTest.kt 7a2786bf356fc2e9, ShareViewModelCaptureTest.kt 6f8c4a5e0fe78611. The hashes were the same before and after the runs.
- Exact included files; renamed/deleted/excluded files and reasons: `capture/share/CaptureSheet.kt`, `CaptureSyntax.kt`, `CaptureVocabularySource.kt`, `ShareActivity.kt`, `ShareViewModel.kt`, and `capture/entry/CaptureActivity.kt`. Excluded: `res/values/strings_share.xml` and `res/values-de/strings_share.xml` (resources, not instrumented; checked by lint and the German/English tests). No renamed or deleted files. All 6 files were found in the report.
- Provider/version, command, working directory, and test selection: JaCoCo through AGP `enableUnitTestCoverage`; `./gradlew createDebugUnitTestCoverageReport` in `android/`, over the whole debug unit-test suite.
- Run start/completion time and exit status: the test run (`ktlintCheck testDebugUnitTest lintDebug`) ran 18:19:05 to 18:23:17 with exit status 0 and wrote `testDebugUnitTest.exec` at 18:22:02. `createDebugUnitTestCoverageReport` ran 18:23:17 to 18:23:34 with exit status 0. Gradle reused the up-to-date test task from that same run on unchanged inputs.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (sha256 6b95e21e5b8a5f748b1229790d7ade1ba6a35120133b7de098951b66a1bb32d0), `android/app/build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The exec and report timestamps fall within this session's final runs, and the test-file hashes did not change.
- Final run after latest test additions and production fixes: yes. No production code changed after 6c69e41fb.

Values below use covered/total (percentage). The Statements column holds JaCoCo instruction counters, since JaCoCo has no statement counter.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `capture/share/CaptureSyntax.kt` | 623/623 (100.0%) | 64/64 (100.0%) | 30/30 (100.0%) | 84/84 (100.0%) |
| `capture/share/CaptureVocabularySource.kt` | 145/162 (89.5%) | N/A (0/0) | 9/10 (90.0%) | 15/15 (100.0%) |
| `capture/share/CaptureSheet.kt` | 2943/2962 (99.4%) | 103/109 (94.5%) | 45/46 (97.8%) | 269/270 (99.6%) |
| `capture/share/ShareViewModel.kt` | 1515/1530 (99.0%) | 105/107 (98.1%) | 51/51 (100.0%) | 147/152 (96.7%) |
| `capture/share/ShareActivity.kt` | 577/582 (99.1%) | 14/16 (87.5%) | 14/15 (93.3%) | 44/44 (100.0%) |
| `capture/entry/CaptureActivity.kt` | 369/379 (97.4%) | 2/2 (100.0%) | 9/11 (81.8%) | 27/27 (100.0%) |
| **Total (summed counters, all 6 files, none unmeasured)** | 6172/6238 (98.9%) | 288/298 (96.6%) | 158/163 (96.9%) | 586/592 (99.0%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ShareViewModel.kt:161` (`saving \|\| saved` guard in `removeFile`, `saved` side) | Criterion 3: no removal after the capture is queued | Test added: "files cannot be removed once the capture is queued and the sheet closes". Now covered. |
| `ShareViewModel.kt:208-209, 247-248` (`catch (CancellationException) { throw e }`) | Rethrows coroutine cancellation | Left uncovered. This is defensive structured-concurrency code; testing it would need cancelling `viewModelScope` in the middle of a save. |
| `ShareViewModel.kt:211` (`IllegalArgumentException` with blank text → EMPTY_TEXT) | Queue rejects a blank title | Left uncovered. This catch existed before the task and was moved. `canSave` blocks a blank title before the queue is called, so the branch is unreachable in practice. The TEXT_TOO_LONG side is covered. |
| `ShareViewModel.kt:257-258` (`defaultTitle` with 0 files) | Default title when no files exist | Left uncovered. Callers only fill the title when files exist, so this branch is unreachable. |
| `CaptureSheet.kt:451/458` (`when` over `ShareError`) | none | Left uncovered: Kotlin's synthetic exhaustive-`when` fallback, unreachable. |
| `CaptureSheet.kt:125, 133, 179`; `ShareActivity.kt:79` partial branches | none of the new behavior | Left uncovered: Compose-generated `LaunchedEffect`/`pointerInput` branches, the pre-existing scrim tap during a save, and `url.orEmpty()` on a read-later sheet, which always has a URL. The behavior of line 125 (focus after add-another) and line 79 (toast) is tested. |
| `CaptureActivity.kt:51` (`onSaveAndAddAnother = { viewModel.save(addAnother = true) }` lambda) | Criterion 2 on the widget/tile/shortcut sheet | Gap, no test added. This one-line wiring is outside the task's `touches` (`capture/entry`, and its tests in `android/app/src/test/.../capture/entry`). The same wiring in ShareActivity is covered end to end by ShareAddAnotherActivityTest, and the view model and sheet behavior is shared. The parent can add a test in `CaptureEntryPointsTest` if it wants to widen the scope. |
| `CaptureVocabularySource.kt:26` (`observe$2.invokeSuspend`), `ShareActivity`/`CaptureActivity.setUserSettings` | none | Left uncovered: a coroutine-synthetic method (its line is covered by "a broken cache gives an empty vocabulary") and Hilt-injected setters. |

## Unable to test

- The real Android system picker (DocumentsUI) and real content-provider grants on a device. No emulator or device is available. The picker contract and its `*/*` input are tested with an `ActivityResultRegistry` stand-in, and copying picked files into the offline queue is tested with a Robolectric content provider. Follow-up: an on-device check only if a human wants one.
- The CR-1 difference between Android's ICU regex and the server's JVM regex. JVM unit tests and Robolectric run on OpenJDK `java.util.regex`, so a test cannot show it. An instrumented test on a device or emulator could. The parity rule is still recorded as a JVM test.
- CaptureActivity's add-another wiring (see the coverage table). It is outside the task's touches, so it was not tested here.

## Summary

Tests added and all validation passed. Task-scoped coverage is sufficient, and testing found no production defects. The two earlier failures came from wrong test assertions, not from bugs. The remaining gaps are defensive or unreachable branches, plus the one-line CaptureActivity wiring outside the task's touches, which is reported above.
