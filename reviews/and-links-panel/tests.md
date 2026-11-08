# Test Report

Date: 2026-11-08

Task: and-links-panel - Android: shared linked-items panel with add, remove, create linked

Scope: task base `79e60ee80766ae677b1517c92b9ee468108381ec`, implementation commits df137aae4..`94276d0887dd49c88b9363e53177b5949e304fae` (`git diff 79e60ee80 94276d088 -- android`, given by the parent worker). Those commits were later rebased onto newer main (fee37558c and its parents) and the fixer's commit `ae67019936d70cf36d061110f8c717e44162bd60` ("fix review findings, links tests (WIP)") was added on top; the measurements below are of that HEAD, and the working tree was identical to it (`git diff --stat ae6701993` empty).

Requirements:
- `tasks/and-links-panel.md` acceptance criteria and its two recorded assumptions (create-linked asks for a title, the task goes to the Inbox; contacts open items in place, events via the web URL)
- SPEC.md section 4.3 (links), via the task goal
- `docs/ARCHITECTURE.md` "Coverage" (Android: `createDebugUnitTestCoverageReport`, JaCoCo)
- existing test conventions: `search/SearchRepositoryTest`, `search/SearchViewModelTest`, `calendar/edit/EventExtrasRepositoryTest`, `contacts/ContactsScreensTest`, `search/SearchRouteInAppTest`

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/core/links/LinkedItemsViewModelTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/core/links/LinksRepositoryTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/core/links/LinkedItemsPanelTest.kt` (new)

## Tests added or updated

Count: 83 (LinkedItemsViewModelTest 39, LinksRepositoryTest 16, LinkedItemsPanelTest 28). One of the 39 view model tests ("typing back to the shown text while a newer search is answering drops that answer") was added to the file by the fixer while this run was going on; the other 82 were written by the test-writer.

What they cover:
- **LinkedItemsViewModelTest** (fake `LinksSource`, `StandardTestDispatcher`, `CompletableDeferred` for ordering): load with loading state, the server URL flow, the same item again, switching items, offline/failed load and retry, an older load answering after a reload; removing manual links (running state, double tap, sync links ignored, failures, finishing after an item switch); debounced search (only the last text, trimmed, blank clears, filtering out the item itself and items already linked, a removed link's item comes back, offline/refused search and re-search, clearing or closing drops a running search, typing back to the shown text); add (busy, double tap, append, 409 reloads, failure keeps the search open, add while the load is still running, finishing after an item switch); create linked task/note (trimmed title, task vs note, link type, blank title, double tap, create failure keeps the dialog, link 409 reloads, link failure closes the dialog with an error, create or link finishing after an item switch); clearing errors.
- **LinksRepositoryTest** (MockWebServer and the generated APIs): `GET /api/v1/links?type&id`, mapping of both directions, manual vs sync, unavailable ends and blank texts; an unknown item type fails without a request; 5xx = offline, 4xx = failed with status, 204 / unreadable / unknown end type = failed; no connection = offline; `POST /api/v1/links` body (source and target refs), the answer as seen from the source, 409 = conflict; `DELETE /api/v1/links/{id}`, 404 counts as removed, 409 (sync link) refused, 5xx offline; `POST /api/v1/tasks` with the title only (no list, so the server puts it in the Inbox), `POST /api/v1/notes`; global search over all types.
- **LinkedItemsPanelTest** (Robolectric Compose): the rows (title, type label, subtitle, "Untitled", "unavailable"), tapping opens, unavailable rows disabled, remove only on manual links, remove disabled while running, empty, loading, load errors with retry, action error; add / new task / new note actions, disabled while busy or without an item; search dialog (typing, grouped hits, adding a hit, hits disabled while busy, "Nothing found." only once the search answered, search and add errors, Close); create dialog (title required, create, cancel, busy, error); German strings; `linkedItemTarget` (task and note in-app, event on the web, list screen fallback); `LinkedItemsSection` with a real view model: loads the item's links, in-app targets go to `open`, web targets start an `ACTION_VIEW` intent, the "No app can open this link." toast without a browser, remove through the view model, and nothing without Hilt or a view model.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: reusable `LinkedItemsPanel` in `core/links` lists links | LinkedItemsPanelTest: `lists the links with type, subtitle and an untitled or unavailable mark`, `no links says so`, `while loading there is no empty text`, `a failed load says why and retries`; LinkedItemsViewModelTest load tests; LinksRepositoryTest list tests | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.core.links.*' --tests 'ch.sonensei.cockpit.contacts.*' --tests 'ch.sonensei.cockpit.gtd.task.*' createDebugUnitTestCoverageReport` | Pass |
| AC1: opens each in-app where a screen exists, else the web URL | LinkedItemsPanelTest: `a task opens in the app, a note in the app, an event on the web`, `an item without a web page or server URL falls back to its Android list screen`, `the section loads the item's links, opens in-app targets and sends web targets to the browser`, `without a browser a web link says it cannot be opened`, `tapping an available link opens it, an unavailable one does nothing` | same | Pass |
| AC1: adds a link via the global search API | LinkedItemsViewModelTest search and add tests; LinkedItemsPanelTest: `the search dialog searches as you type and adds a hit`, `nothing found is ...`, `a failed add is shown in the search dialog`; LinksRepositoryTest: `linking posts both ends ...`, `a pair that is linked already is a conflict`, `search uses the global search over all types` | same | Pass |
| AC1: removes a link | LinkedItemsViewModelTest remove tests; LinkedItemsPanelTest: `manual links can be removed, sync links cannot`, `the section removes a manual link through the view model`; LinksRepositoryTest remove tests | same | Pass |
| AC1: "create linked task/note" | LinkedItemsViewModelTest create tests; LinkedItemsPanelTest create dialog tests; LinksRepositoryTest: `creating a task posts its title only, so it goes to the Inbox ...`, `creating a note ...` | same | Pass |
| AC2: used in task detail and contact detail; contact related rows tappable | Existing tests (not written here): ContactsScreensTest, ContactsRouteInAppTest, TaskDetailContentTest, TaskDetailScreenTest | same | Pass (existing tests). The route's in-place opening is not covered, see "Unable to test" |
| AC3: new strings in English and German | LinkedItemsPanelTest English assertions throughout; `the panel speaks German` (`@Config(qualifiers = "de")`) | same | Pass |
| AC4: unit/Compose tests cover the new behavior | the three new test classes | same | Pass |
| AC4: `./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass | none (full gates) | not run by the test-writer | Not run: the full Android unit suite, `lintDebug` and `.taskcheck` are left to the parent's final validation (slow, and other agents share the machine). ktlintCheck did pass in the command above |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.core.links.*'`

Result:

Pass on the final run. The first run had 3 failures, all mistakes in the tests (a `runCurrent()` that let a fake answering at once finish before the "busy" assertion; a text assertion on a Row that has no merged text). They were fixed in the tests, not in production code.

Then, with the related packages, lint and coverage:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.core.links.*' --tests 'ch.sonensei.cockpit.contacts.*' --tests 'ch.sonensei.cockpit.gtd.task.*' createDebugUnitTestCoverageReport`

Result:

Pass: BUILD SUCCESSFUL, 27 suites, 428 tests, 0 failures, 0 errors, 0 skipped (core.links 83, contacts 47, gtd.task 298).

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run by the test-writer. The parent worker runs it, plus `sh scripts/check.sh`, as final validation.

## .taskcheck

Result:

Not run (`sh scripts/check.sh` is left to the parent worker; see above).

## Bugs found

### A pending search overwrote the results after the user typed back to the shown text

Finding ID:

`TW-1`

Test:

`LinkedItemsViewModelTest.typing back to the shown text while a newer search waits keeps the shown results`

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/core/links/LinkedItemsViewModel.kt:141` (`search`), as of 94276d088

Requirement:

AC1 "adds a link via the global search API" (search as you type). The results shown must belong to the text in the field.

Expected behavior:

Type "plan" (results shown), type "plans", delete the "s" before the debounce ends: the "plan" results stay, `resultsQuery == "plan"`, no spinner.

Actual behavior:

At 94276d088 `search()` returned early when the trimmed text equalled `resultsQuery`, before cancelling `searchJob`. So the search for "plans" still ran and replaced the results: `resultsQuery = "plans"` while the field said "plan", and `searching` stayed true (an endless progress bar) until the next keystroke. This was found by reading the code and expressed as the test above.

Severity:

Low

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.` Status: already fixed in ae6701993, which cancels `searchJob` before the early return; it was in the working tree before the first test run. Both regression tests (the one above and the fixer's "... is answering drops that answer") pass against ae6701993. Neither was run against the pre-fix code; the failure there is derived from the code.

## Coverage

Status: verified (fresh, focused run; selection = core.links, contacts and gtd.task tests).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-links-panel; base `79e60ee80766ae677b1517c92b9ee468108381ec`; measured HEAD `ae67019936d70cf36d061110f8c717e44162bd60`. Ownership comes from the parent's scope (`git diff 79e60ee80 94276d088 -- android`, commits df137aae4..94276d088, rebased as 595ecdfb4..fee37558c) plus the fixer commit ae6701993.
- Source/test snapshot identifier (including uncommitted inputs): working tree equal to ae6701993 (no uncommitted changes). sha256: LinksRepository.kt f27961a4...876a2e, LinkedItemsViewModel.kt 73048086...ce4232, LinkedItemsPanel.kt 6bb6e675...981ad2, ContactsScreen.kt c446e073...f916, TaskDetailScreen.kt d4f660f3...5132, LinkedItemsPanelTest.kt d6a55f32...f5e, LinkedItemsViewModelTest.kt 9eb83908...b049, LinksRepositoryTest.kt 50f03019...ac6. The production hashes were the same before and after the run.
- Exact included files: `core/links/LinksRepository.kt`, `core/links/LinkedItemsViewModel.kt`, `core/links/LinkedItemsPanel.kt`, `contacts/ContactsScreen.kt`, `gtd/task/TaskDetailScreen.kt` (all under `android/app/src/main/java/ch/sonensei/cockpit/`). Excluded: `res/values*/strings_links.xml` (resources, no executable code; checked by the German string test); test files. Files changed only by the fixer commit are listed separately below.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (Gradle `jacoco` plugin, version from AGP/Gradle; not pinned in the build); `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.core.links.*' --tests 'ch.sonensei.cockpit.contacts.*' --tests 'ch.sonensei.cockpit.gtd.task.*' createDebugUnitTestCoverageReport` in `android/`.
- Run start/completion time and exit status: test suites ran 2026-11-08T19:54:38Z to 19:55:03Z (test-result timestamps); BUILD SUCCESSFUL (exit 0) in 55 s.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 3284f84ac7b1f381e0a4e5cbdd28bd5471e257c817ebb04b74848d4ad43bf10f.
- Evidence outputs are fresh and inputs stayed unchanged: testDebugUnitTest and createDebugUnitTestCoverageReport ran in this invocation (not up-to-date), after the last test edits. The input hashes match before and after.
- Final run after latest test additions and production fixes: yes (fixes in ae6701993 included; no test change after the run).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Statements = JaCoCo INSTRUCTION.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `core/links/LinksRepository.kt` | 650/665 (97.7%) | 52/56 (92.9%) | 35/37 (94.6%) | 76/80 (95.0%) |
| `core/links/LinkedItemsViewModel.kt` | 1617/1628 (99.3%) | 88/92 (95.7%) | 44/44 (100%) | 147/147 (100%) |
| `core/links/LinkedItemsPanel.kt` | 3298/3420 (96.4%) | 92/102 (90.2%) | 62/73 (84.9%) | 252/253 (99.6%) |
| `contacts/ContactsScreen.kt` (whole file, mostly older code) | 4119/4547 (90.6%) | 168/227 (74.0%) | 85/100 (85.0%) | 353/372 (94.9%) |
| `gtd/task/TaskDetailScreen.kt` (whole file, mostly older code) | 9010/11255 (80.1%) | 281/400 (70.3%) | 191/282 (67.7%) | 715/833 (85.8%) |
| **Aggregate (5 files, summed counters)** | 18694/21515 (86.9%) | 681/877 (77.7%) | 417/536 (77.8%) | 1543/1685 (91.6%) |

No scoped file is missing from the report. The new `core/links` package alone: 5565/5713 statements (97.4%), 232/250 branches (92.8%), 475/480 lines (99.0%).

Files changed only by the fixer commit ae6701993, from the same run (for information; the test selection did not include the `search` and `calendar` packages, so the last two are understated):

| File | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `contacts/ContactsModels.kt` | 630/630 (100%) | 46/46 (100%) | 37/37 (100%) | 62/62 (100%) |
| `contacts/ContactsRepository.kt` | 259/293 (88.4%) | 16/18 (88.9%) | 14/16 (87.5%) | 35/41 (85.4%) |
| `gtd/task/TaskDetailRepository.kt` | 1648/1691 (97.5%) | 108/114 (94.7%) | 96/101 (95.0%) | 159/163 (97.5%) |
| `search/SearchItemScreen.kt` | 27/484 (5.6%) | 2/19 (10.5%) | 1/11 (9.1%) | 4/26 (15.4%) |
| `calendar/edit/BirthdayContact.kt` | 0/72 (0%) | 0/10 (0%) | 0/1 (0%) | 0/6 (0%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `LinkedItemsPanel.kt:162-163` (first run) | No browser for a web link: toast | Added `without a browser a web link says it cannot be opened`; now covered |
| `LinkedItemsPanel.kt:328-331` (first run) | Add error inside the search dialog | Added `a failed add is shown in the search dialog`; now covered |
| `LinkedItemsViewModel.kt:214` (first run) | Link of a created item answering after an item switch | Added `a link of a created item finishing after another item was opened is ignored`; now covered |
| `LinkedItemsPanel.kt:93`, partial branches 121/134/322/329/334/378/410 | Data-class defaults, `orEmpty()` and null-safe calls, Compose-generated branches | Not material |
| `LinkedItemsPanel.kt:136-137` (Hilt branch) | `hiltViewModel` path when no view model is passed | Covered at runtime only under a Hilt activity (route tests in other packages); the no-Hilt return is tested |
| `LinkedItemsViewModel.kt` partial 90/122/239/240 | Null-safe calls (`searchJob?`, `links?` in remove, `item?.let`) | Not material (remove needs a shown link, so `links` is not null) |
| `LinksRepository.kt:128-129, 156-157` | `CancellationException` rethrow | Defensive; not material |
| `LinksRepository.kt` partial 125/183/189 | 2xx other than 204 on DELETE; `toLinkedItem` heuristic for a self-referencing end | Low risk; not added |
| `ContactsScreen.kt:131-133, 139-141, 158-167` | `ContactDetailRoute`: opening a related/linked item in place (`SearchItemScreen`), back from it, Hilt links VM, web launch / no-browser toast for events | **Gap (AC2 wiring).** `relatedTarget()` and the tappable rows (callbacks) are covered by ContactsScreensTest, but tapping a related row in the real route and returning with back is not. Not added here: it needs the Hilt route test (`ContactsRouteInAppTest`), which the fixer was editing at the same time. Follow-up recommended |
| `TaskDetailScreen.kt` (many lines) | Mostly older code from other tasks (edit sheets, convert flows) | Outside this task's change; the new `linksPanel` slot is covered by TaskDetailContentTest |

## Unable to test

- `ContactDetailRoute` in-place opening of related and linked items (tap a waiting-for/mail row, SearchItemScreen shows, back returns to the contact), and the event "no browser" toast in that route. This needs a Hilt route test. Follow-up: add it to `ContactsRouteInAppTest` (it already mocks `ContactsRepository.related`).
- Full Android unit suite, `lintDebug` and `sh scripts/check.sh` were not run by the test-writer. The parent worker must run them for AC4.
- No device or e2e check of the real browser intent (Robolectric records the intent only). The acceptance criteria do not ask for one.

## Summary

Tests added; one production defect (TW-1, Low) was found and is already fixed in ae6701993, where its regression tests pass. All focused validation passed (428 tests in core.links, contacts and gtd.task, plus ktlintCheck). One meaningful gap remains as a follow-up: the in-place opening in `ContactDetailRoute` for AC2 has no test. The full suite, `lintDebug` and `.taskcheck` still have to be run by the parent worker.
