# Test Report

Date: 2026-10-21

Task: and-share-target - Android share target

Scope: `10ad344f60c546f6dd94cf094cbe242ca9d56271..HEAD` (merge-base with origin/main .. HEAD `10d50e0a5c5462fdf66ab0eb6f77afa49878db04`), the three task-owned commits `9b75e099` (share activity, intent parser, capture sheet, tests), `6a098d5a` (more share tests, code review report), `10d50e0a` (fix review findings). Branch `main`; HEAD is the final code. The only uncommitted changes when testing were the two test files edited here (plus `reviews/and-share-target/review.md`, which the parallel code-reviewer changed; it is not a test input).

Requirements:
- `tasks/and-share-target.md` acceptance criteria and recorded assumptions (content: URIs only, copy on Save, title/notes rules, notes limit 100,000)
- SPEC.md §4.5 (share target), via the task goal
- `docs/ARCHITECTURE.md` "Coverage" (Android: `./gradlew createDebugUnitTestCoverageReport`, JaCoCo XML)
- AGENTS.md (test data fictitious, lint is part of the contract)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/capture/share/ShareIntentParserTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/capture/share/ShareViewModelTest.kt`

## Tests added or updated

Count: 3 (added)

- `ShareIntentParserTest.with a subject, multi-line text goes to the notes in full`: the subject is the title, and the whole multi-line text becomes the notes (no line is lost or taken for the title).
- `ShareIntentParserTest.multiple images given only as clip data are attached`: an `ACTION_SEND_MULTIPLE` that carries its URIs only in `ClipData` (no `EXTRA_STREAM`) still attaches every image in order.
- `ShareViewModelTest.a title typed while the file names load is kept when they arrive`: an ordering test. The user edits the title and notes while the provider is still answering the name query (held by a latch, no real delays). When the names arrive, the edits stay, the default file-name title does not replace them, and Save queues the edited text with the named file.

The existing task tests (62, written by the implementer) already covered the criteria well. I added nothing to `CaptureSheetTest`, `ShareActivityTest` or `ShareManifestTest`.

## Requirement coverage

Command F = `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.share.*' --offline` (BUILD SUCCESSFUL, 65 tests, 0 failures, 0 skipped).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: intent filters for `text/plain`, `image/*`, `*/*`, single and multiple | `ShareManifestTest` (4 tests: SEND text/jpeg/png/pdf/mp4, SEND_MULTIPLE text/image/*/png/pdf/*/*, other actions not matched, activity exported). They resolve against the merged manifest through PackageManager. | F | Pass |
| AC1: compact capture sheet (edit text, save) | `CaptureSheetTest` (11: shows and edits text/notes/files, Save and IME Done save, Cancel, tap outside cancels / tap on sheet does not, disabled while saving, Back consumed while saving, Save disabled while loading or blank, every error message) | F | Pass |
| AC1: sheet writes to the queue | `ShareActivityTest.shared text is edited in the sheet and queued`, `a shared URL is queued with the page title`, `a single file of any type is copied with its caption`. All use the real Hilt graph, the real CaptureQueue and the Room DAO, and check that the scheduler was called. `ShareViewModelTest.save queues the edited text, notes and the named files` (+ limit, error and retry tests) | F | Pass |
| AC2: shared content URIs are copied, not referenced, before the activity ends | `ShareActivityTest.multiple shared images are copied into the queue before the sheet closes` and `a single file of any type is copied with its caption`. After the sheet closes, the sender's files are deleted and the queued attachments' `localPath` contents still read back. `an unreadable file keeps the sheet open with an error and queues nothing` | F | Pass |
| AC3: intent parsing for text | `ShareIntentParserTest` plain text / single line / multiple texts joined / blank subject / subject-only / empty share / other actions | F | Pass |
| AC3: intent parsing for URL | `ShareIntentParserTest.a shared URL with a page title ...`, `a bare URL becomes the title`, `with a subject, multi-line text goes to the notes in full` (new) | F | Pass |
| AC3: intent parsing for multiple images | `ShareIntentParserTest.multiple images are all attached in order, with their caption`, `uris only in the clip data ...`, `multiple images given only as clip data are attached` (new), and end to end in `ShareActivityTest.multiple shared images ...` | F | Pass |
| AC3: Robolectric/Compose UI tests for screens | `CaptureSheetTest` (Compose rule), `ShareActivityTest` (ActivityScenario + Compose) | F | Pass |
| AC3: the named command passes | the whole `ch.sonensei.cockpit.capture.share.*` filter | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.share.*' --offline` | Pass (first run on HEAD without my additions: 62 tests; after them: 65 tests) |
| Assumption: only content: URIs accepted | `ShareIntentParserTest.file uris are ignored, so no app can make us read our own files` | F | Pass |
| Assumption: cancel/leave drops the share, nothing staged before Save | `ShareActivityTest.cancel closes the sheet without queueing`, `a tap outside the sheet closes it without queueing`, `edits survive a configuration change` (nothing queued) | F | Pass |
| Assumption: default title from up to 3 file names; user edits kept | `ShareViewModelTest` single, three and five files titles, `shared text is not replaced by file names`, `a title typed while the file names load is kept when they arrive` (new) | F | Pass |
| Assumption: notes over 100,000 refused | `ShareViewModelTest.notes at the server's limit are queued`, `notes over the server's limit are refused before queueing until shortened` | F | Pass |

## Focused test execution

Command:

`cd /home/eric/work/my-cockpit-agents/agent-4/android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.share.*' --offline`

Result:

Pass. BUILD SUCCESSFUL. JUnit XML: CaptureSheetTest 11, ShareActivityTest 11, ShareIntentParserTest 16, ShareManifestTest 4, ShareViewModelTest 23 = 65 tests, 0 failures, 0 errors, 0 skipped. ktlintCheck passed on the new test code.

## Full test execution

Command:

`cd /home/eric/work/my-cockpit-agents/agent-4/android && ./gradlew createDebugUnitTestCoverageReport --offline` (runs the whole Android debug unit test suite)

Result:

Pass. BUILD SUCCESSFUL in 1m 43s. 439 Android unit tests, 0 failures, 0 errors, 0 skipped.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` (server check, web check, android ktlint + unit tests + lint) ended with "== all checks passed" (`set -eu` script). The web build printed a pre-existing bundle-budget warning that has nothing to do with this task.

## Bugs found

None. No test exposed a production defect.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured as a separate metric. All four in-scope Kotlin files were added by this task, so whole-file coverage equals changed-line coverage for them.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-share-target`. Base `10ad344f60c546f6dd94cf094cbe242ca9d56271` (`git merge-base HEAD origin/main`). HEAD `10d50e0a5c5462fdf66ab0eb6f77afa49878db04`. Task-owned commits `9b75e099`, `6a098d5a`, `10d50e0a` (`git log base..HEAD`, all "and-share-target:"). They match the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `10d50e0a` plus two uncommitted test files: `ShareViewModelTest.kt` sha256 `47f464a3e1e6fef9203ad0dcc5d8b5be46514863cf0d251b7a84309dc7320417`, `ShareIntentParserTest.kt` sha256 `f26e56397d1d0d5b0a4918a98f3aa0935a4f490ed8d4fa653d4a702ea9f63404`. The hashes were the same before and after the coverage run.
- Exact included files; renamed/deleted/excluded files and reasons: included are `android/app/src/main/java/ch/sonensei/cockpit/capture/share/{SharedContent.kt, ShareViewModel.kt, ShareActivity.kt, CaptureSheet.kt}`. Excluded: `android/app/src/main/AndroidManifest.xml` and `android/app/src/main/res/values/strings_share.xml` are resources, not instrumentable bytecode. Their behavior is tested by `ShareManifestTest` and by `CaptureSheetTest.every error has its message`. Also excluded: the Hilt/KSP-generated classes in the same JaCoCo package (`Hilt_ShareActivity.java`, `ShareViewModel_HiltModules*.java`, `ShareViewModel_Factory.java`, `ShareActivity_GeneratedInjector.java`, `ActivityViewModelLazy.kt` inline mapping), because they are generated, not task source. Nothing was renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`. `./gradlew createDebugUnitTestCoverageReport --offline` in `/home/eric/work/my-cockpit-agents/agent-4/android`. Test selection: the full debug unit test suite (439 tests).
- Run start/completion time and exit status: 2026-10-21T01:50:41Z to 01:52:25Z, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (mtime 2026-10-21 03:52:25 +0200), sha256 `6e88fab9ee0b7040fb2bb142ee6e02acd8a34ea00fa56a1db024410567982d03`. Per-file counters were read from `<package name="ch/sonensei/cockpit/capture/share">` / `<sourcefile>` counters.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report mtime matches the run's completion time and differs from the earlier baseline run (01:49:02Z, sha256 `ef4dddba...`). The test-file hashes did not change during the run.
- Final run after latest test additions and production fixes: yes. It ran after the 3 new tests, on HEAD, which already contains the fix commit `10d50e0a`.

Values below use covered/total (percentage). "Statements" are JaCoCo INSTRUCTION counters.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/capture/share/SharedContent.kt` | 302/302 (100.0%) | 48/48 (100.0%) | 8/8 (100.0%) | 30/30 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/share/ShareViewModel.kt` | 701/711 (98.6%) | 52/53 (98.1%) | 26/26 (100.0%) | 68/70 (97.1%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/share/ShareActivity.kt` | 276/276 (100.0%) | 4/4 (100.0%) | 7/7 (100.0%) | 27/27 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/capture/share/CaptureSheet.kt` | 1372/1388 (98.8%) | 30/33 (90.9%) | 20/21 (95.2%) | 118/119 (99.2%) |
| **Aggregate (summed counters, 4 files)** | 2651/2677 (99.0%) | 134/138 (97.1%) | 61/62 (98.4%) | 243/246 (98.8%) |

The aggregate excludes the manifest and string resources (not instrumentable) and the generated Hilt classes. No in-scope file was left unmeasured.

The three new tests did not change the counters: the base run and the final run report identical per-file numbers. Those paths were already executed by other tests. The new tests add behavioral assertions (edit ordering, notes content, a clip-data-only multiple share), not coverage.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ShareViewModel.kt:97-98` (`catch (e: CancellationException) { throw e }`) | Cancellation goes through instead of being reported as FAILED. This only happens when the ViewModel is cleared mid-save, which the sheet prevents (Back is consumed, Cancel and tap outside are disabled while saving). | Left uncovered. Defensive, and it rethrows without changing state. A test would have to clear the ViewModel mid-save through internal APIs. |
| `ShareViewModel.kt:100` (blank-text branch of the IllegalArgumentException mapping) | Maps a queue rejection of blank text to EMPTY_TEXT. It cannot be reached, because `canSave` refuses blank text before `queue.capture` is called (tested: `a blank title is not queued ...`). | Left uncovered (unreachable defensive branch). The reachable branch, TEXT_TOO_LONG, is tested. |
| `CaptureSheet.kt:82` (2 of 10 branches in the scrim's `pointerInput`/`detectTapGestures` lambda) | Tap outside cancels unless saving. Both outcomes are tested (`a tap outside the sheet cancels ...`, `while saving nothing can be edited or cancelled`, `a tap outside cancels again once saving failed`). The missed branches are compiler-generated lambda/coroutine branches. | Not material. The behavior is tested. |
| `CaptureSheet.kt:205,212` (`ShareError.message()` `when`) | Every enum value's message is tested (`every error has its message`). The missed branch/line is the default branch that Kotlin generates for an exhaustive `when` over an enum (NoWhenBranchMatchedException). | Not material, cannot be reached. |

## Unable to test

- On-device behavior of the real system share sheet: the translucent theme, `excludeFromRecents` and `taskAffinity=""` over a real sending app, and a URI grant that is really revoked after the activity finishes. Robolectric tests the intent resolution, the activity and the copy before finish, but not a real Android system UI or a real cross-app grant. No device or emulator was used. Follow-up (optional): a manual or instrumented check on a device. No acceptance criterion names it.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. I added 3 tests. The focused share suite (65), the full Android unit suite (439) and `.taskcheck` all pass.
