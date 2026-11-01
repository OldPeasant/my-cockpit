# Test Report

Date: 2026-11-01

Task: and-search - Android: global search

Scope: base `05a5084d45a8e8b87687455df17dbdbf7c04b9ee`..HEAD `afd0b11ba1def3ce449bd0600a45f79cd6c77f31` on `main`; task commits `e580f21c7` (implementation) and `afd0b11ba` (tests), as given by the parent worker and confirmed with `git diff --stat 05a5084d4..HEAD` (only search, shell and search-string files changed). Uncommitted at measurement: this test-writer's edit of `SearchRepositoryTest.kt` only.

Requirements:
- `tasks/and-search.md` acceptance criteria
- `AGENTS.md` project notes, `docs/ARCHITECTURE.md` "Coverage"
- parent worker instructions (review existing 71 tests, add only what is missing)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/search/SearchRepositoryTest.kt`

## Tests added or updated

Count: 1

- `SearchRepositoryTest.a hit with a malformed id fails without a status instead of crashing`: a server answer with a non-UUID item id becomes `SearchResult.Failed(null)` (the screen's error state with retry) instead of an exception. It covers the `IllegalArgumentException` branch of `SearchRepository.search`, which no test reached before.

The existing 71 tests were reviewed against the criteria and cover them well: debounce and last-text-only searching, trimming, filters (one, several, all type names, Files = FILE+FOLDER), IME search action, loading, idle hint, nothing-found only for the current key, offline/failed with retry, out-of-order answers (success and failure answering last), clearing while pending, routing (in-app / web deep link / list screen / no browser), app-bar action on every destination, back navigation, and English/German strings. No other meaningful gaps were found, so no other tests were added.

## Requirement coverage

Command used for every row except the last one (F = focused command): `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.search.*' --tests 'ch.sonensei.cockpit.ui.CockpitShellSearchTest' createDebugUnitTestCoverageReport --rerun-tasks`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: search action in the app bar of every top-level screen opens the search screen | `CockpitShellSearchTest.everyDestinationHasTheSearchActionAndItOpensSearchWithBackToThatScreen`, `theSearchActionHasATalkBackLabel`, `screensWithTheirOwnFabKeepTheCaptureActionNextToSearch`, `theRealShellOpensTheSearchScreenWithItsHint`, `SearchRouteInAppTest.the app bar back arrow leaves search` | F | Pass |
| AC1: searches as you type (debounced) via `/api/v1/search` | `SearchViewModelTest.typing searches only once the pause is over, for the last text only`, `the text is searched trimmed but kept as typed`, `setting the same text again does not search again`, `SearchRepositoryTest.asks the search endpoint with the trimmed text, all types and the largest page`, 200-char boundary tests, `SearchRouteInAppTest.typing searches the server and shows the hits with their type headings` | F | Pass |
| AC1: type filter chips (9 types) | `SearchViewModelTest.a filter chip searches right away...`, `a filter chip without text only selects the filter`, `SearchRepositoryTest.a filter asks for its item types...`, `several filters ask...`, `every filter asks for the server's item type names`, `SearchScreenTest.every filter chip is shown and toggles its filter`, `SearchRouteInAppTest.a filter chip searches for its types right away` | F | Pass |
| AC2: results show type icon, title, subtitle | `SearchScreenTest.results show group headings, titles, subtitles and type icons`, `SearchRepositoryTest.groups are mapped in the server's order...`, `SearchModelsTest.every server type has its own heading...` | F | Pass |
| AC2: tap opens Android screen where one exists, else web deep link in the browser | `SearchModelsTest` routing tests, `SearchRouteInAppTest.a contact opens...`, `an email opens...`, `a note opens...`, `a task with a deep link opens in the browser below the server`, `without a browser on the phone...`, `an item without a deep link opens the screen listing its type`, `CockpitShellSearchTest.anItemOpenedFromSearchIsTitledWithItsTypeAndBackReturnsToSearch`, `anItemRouteWithABrokenIdShowsNothingAndDoesNotCrash` | F | Pass |
| AC3: loading, empty ("Nothing found") and error states | `SearchViewModelTest.a search shows loading...`, `offline says so, and retry...`, `a refused search says it failed`, `nothing found is only said for the text and filters it was searched for`, out-of-order tests, `SearchScreenTest` state tests, `SearchRepositoryTest` failure tests incl. new malformed-id test | F | Pass |
| AC3: IME search action | `SearchViewModelTest.the keyboard's search action searches without waiting for the pause`, `...with a blank text searches nothing`, `SearchScreenTest.the keyboard's search action submits`, `SearchRouteInAppTest` (`performImeAction`) | F | Pass |
| AC4: all new strings in English and German | `SearchModelsTest.every search string exists in German and is not blank`, `SearchScreenTest.in German ...` (2 tests), lint `MissingTranslation` | F and `cd android && ./gradlew ktlintCheck lintDebug` | Pass |
| AC5: `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes | whole Android unit suite | `sh scripts/check.sh` (Android step) and reruns, see "Full test execution" | Pass with a caveat: 1 unrelated flaky failure in the full run, which passed when rerun alone |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.search.*' --tests 'ch.sonensei.cockpit.ui.CockpitShellSearchTest' createDebugUnitTestCoverageReport --rerun-tasks`

Result:

Pass. Exit 0, 72 tests, 0 failures, 0 skipped (counted from `android/app/build/test-results/testDebugUnitTest/*.xml`). Before the new test, the parent's 71 tests also passed with the same selection.

## Full test execution

Command:

`sh scripts/check.sh` (tfp + server + web + `cd android && ./gradlew --quiet ktlintCheck testDebugUnitTest lintDebug`)

Result:

Fail (exit 1), unrelated to this task. tfp, server and web passed. The Android step ran 2455 tests: 1 failed and the build took 9m 10s on a loaded machine. The failure was `ch.sonensei.cockpit.ui.theme.CockpitThemeTest > dynamicColorOffInDarkModeUsesTheDarkFallbackPalette` with `AppNotIdleException: Compose did not get idle after 1 attempts in 60 SECONDS`. The file was last changed in `9e213df4a` (and-shell), and this task does not touch it.

Follow-ups:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.ui.theme.CockpitThemeTest'` -> Pass. The failure is a timing timeout under load, not a regression.
- `lintDebug` did not run in the full run because the test step failed first. A separate run of `cd android && ./gradlew ktlintCheck lintDebug` -> Pass.

## .taskcheck

Result:

Fail on the first attempt because of the unrelated flaky `CockpitThemeTest` timeout described above. Everything else passed. The parent worker should run `.taskcheck` again before `done`.

## Bugs found

None. No production defect was found.

## Coverage

Status: verified (whole-file coverage of task-changed production files, focused test selection).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 377/381 executable added lines covered (99.0%). Method: lines added in `git diff -U0 05a5084d4..HEAD` intersected with JaCoCo line entries that have instructions (`mi+ci>0`); a line counts as covered when `ci>0`. Changed-branch coverage was not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-search`; base `05a5084d45a8e8b87687455df17dbdbf7c04b9ee`; HEAD `afd0b11ba1def3ce449bd0600a45f79cd6c77f31`; commits `e580f21c7` and `afd0b11ba` as supplied by the parent and confirmed by `git diff --name-only`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `afd0b11ba` plus uncommitted `SearchRepositoryTest.kt` sha256 `e1aa66a0a0573d0c064cd0c252a7262a1cf2372cb745f95139c9e23f472aa6fd`. The hash was the same before and after the run, and `git status` showed only this file modified.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/search/{SearchViewModel,SearchScreen,SearchModels,SearchRepository,SearchItemScreen}.kt` and `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt`. Excluded: `res/values{,-de}/strings_search.xml` (resources, not executable; checked by lint and string tests); Hilt/KSP-generated `*_Factory`/`*_HiltModules*.java` (generated code); `LazyDsl.kt` (JaCoCo synthetic entry with no counters). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (`createDebugUnitTestCoverageReport`); command above with `--rerun-tasks`; working directory `android/`; selection `ch.sonensei.cockpit.search.*` + `ch.sonensei.cockpit.ui.CockpitShellSearchTest` (the task tests; other suites that also touch `CockpitShell.kt` were not part of this measurement).
- Run start/completion time and exit status: started after 2026-11-01T14:36:57Z, report written 2026-11-01T14:38:02Z, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 `db2a70cbbe313a94abe14078e492a550a702c2ce45696487ad70613217fe0ea8`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. `--rerun-tasks` forced the tests and the report to run again, the report mtime is from this run, and the test-file hash did not change.
- Final run after latest test additions and production fixes: yes. It ran after the only added test, and there were no production fixes.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. JaCoCo "Statements" = INSTRUCTION counter.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `search/SearchViewModel.kt` | 425/425 (100.0%) | 26/26 (100.0%) | 24/24 (100.0%) | 48/48 (100.0%) |
| `search/SearchScreen.kt` | 1777/1805 (98.4%) | 49/58 (84.5%) | 40/45 (88.9%) | 158/158 (100.0%) |
| `search/SearchModels.kt` | 334/334 (100.0%) | 20/20 (100.0%) | 18/18 (100.0%) | 42/42 (100.0%) |
| `search/SearchRepository.kt` | 334/334 (100.0%) | 28/28 (100.0%) | 17/17 (100.0%) | 55/55 (100.0%) |
| `search/SearchItemScreen.kt` | 538/707 (76.1%) | 16/28 (57.1%) | 7/16 (43.8%) | 39/42 (92.9%) |
| `ui/CockpitShell.kt` (whole file, mostly pre-existing code) | 1186/1579 (75.1%) | 56/91 (61.5%) | 37/42 (88.1%) | 107/132 (81.1%) |
| Aggregate (summed counters, 6 files above; exclusions listed in Evidence) | 4594/5184 (88.6%) | 195/251 (77.7%) | 143/162 (88.3%) | 449/477 (94.1%) |

`SearchRepository.kt` went from 327/334 instructions and 54/55 lines before the new test to 100%. Note: in the earlier run without `--rerun-tasks`, JaCoCo reported `SearchItemScreen.kt` with 572/741 instructions and 24/40 branches. The recompile changed Compose's generated bytecode, so these counters are not comparable between builds. The table uses the final, fresh run.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `SearchRepository.kt:88-89` (IllegalArgumentException catch) | malformed server answer must become an error state, not a crash | Test added: `a hit with a malformed id fails without a status instead of crashing`. Now covered. |
| `SearchItemScreen.kt:62` (mail compose from a mail opened via search), `:67-68` partial | reply/forward from the opened mail. Not an acceptance criterion: AC2 only requires opening the item | Justified gap. Opening the mail is covered in `SearchRouteInAppTest`. A reply flow would need a loaded mail fixture plus the compose screen, and it belongs to the mail feature. |
| `SearchItemScreen.kt:87-88, 94-95` (contact call/mail intent lambdas and the "no app" toast) | contact actions reuse `ContactIntents`/`ContactDetailContent`, which the contacts feature tests | Justified gap. Opening the contact and going back are covered. |
| `SearchItemScreen.kt:40-49` partial branches (`LocalActivity` not Hilt; `else` type closes) | defensive. The `else` branch only happens for a hand-built `search/item/TASK/...` route; `searchTarget` never produces one | Justified gap: not reachable from the UI. |
| `SearchScreen.kt:88-139` partial branches (default-argument/Hilt fallbacks, `SearchCallbacks` defaults), `:193, :275` (`keyboard?.hide()` null branch), `:213` (`loading && idle`), `:288-290` (default `action`) | Kotlin/Compose synthetic branches. All lines are covered | Non-material. |
| `CockpitShell.kt:160-161, 185-187` (changed lines: null `arguments`, title fallback when no type argument) | defensive. The navigation route always provides `type` | Non-material (changed-line miss: 187 only). Other missed lines (69, 72, 90-91, 129, 148, 203-250) are pre-existing shell code that other suites test. |

## Unable to test

- Real-device IME/on-screen keyboard behavior and launching a real browser: Robolectric simulates `performImeAction` and the `ACTION_VIEW` intent (the tests assert the started intent and the no-browser toast). Running on a device or emulator is not part of the criteria and was not done. No follow-up needed for the criteria.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. One test was added (malformed server answer). The focused suite passes (72/72), and `ktlintCheck lintDebug` passes. The one failure in the full `.taskcheck` run was an unrelated `CockpitThemeTest` idle timeout under load, which passed when rerun alone. The parent should run `.taskcheck` again before `done`.
