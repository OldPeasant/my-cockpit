# Test Report

Date: 2026-10-22

Task: and-contacts - Android contacts

Scope: task commits `5bbedf2f8` (implementation) and `da27e7802` (review fixes CR-1/CR-2, test corrections RT-1..RT-3, the test-writer's first-pass tests), on top of base `988b870feaed4377f2966acc56e8874ffa6f3035`. Reviewed revision: `da27e7802` (HEAD). Production paths: `android/app/src/main/java/ch/sonensei/cockpit/contacts/{ContactsModels,ContactIntents,ContactsRepository,ContactsViewModels,ContactsScreen}.kt`, `android/app/src/main/res/values/strings_contacts.xml`. Uncommitted inputs from this final pass: test-only edits to `ContactsScreensTest.kt` and `ContactsViewModelsTest.kt` (listed below). Production code is unchanged since `da27e7802`.

Requirements:
- `tasks/and-contacts.md` acceptance criteria and its recorded assumptions (read-only contacts; ACTION_DIAL / ACTION_SENDTO mailto; related items shown, not tappable)
- SPEC.md §8.3 (contacts) and §9.2 item 7 (Android: contacts view, call/mail intents), as quoted in the task notes
- `reviews/and-contacts/review.md` CR-1/CR-2 and `reviews/and-contacts/fixes.md` (the behaviour CR-2 fixed)
- AGENTS.md project notes (fictitious test data, lint is part of the contract)

## Test files changed

First pass (committed in `da27e7802`, the fixer corrected 3 route tests):
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactsModelsTest.kt` (new, 7 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactsRouteInAppTest.kt` (new, 9 tests)

Final pass (uncommitted):
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactsScreensTest.kt` (+3 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactsViewModelsTest.kt` (+1 test, 1 test strengthened with an extra assertion)

## Tests added or updated

Count: 20 added by the test-writer (16 first pass, 4 final pass), 1 strengthened (`a failed load keeps the last list and says why` now also asserts that `loaded` stays true when the same search is refreshed and fails). No test was weakened, skipped or removed.

Final-pass additions, all targeting the final CR-2 code:
- `ContactsScreensTest.related items kept from an earlier load whose reload failed say they may be out of date`: the stale + FAILED wording (`contacts_related_failed_stale`) is shown and the old wording is not. Before this test, only the stale + OFFLINE wording was ever checked.
- `ContactsScreensTest.related items never loaded while offline say they are not shown`: the non-stale OFFLINE wording, so nothing claims to be "out of date" when nothing is shown.
- `ContactsScreensTest.a sent mail and a timed event show when they happen`: related mail to the contact with a time ("To them, ..." plus the date) and a timed event. Before this test, `formatInstant` (`ContactsScreen.kt` L533) never ran.
- `ContactsViewModelsTest.retrying a failed new search that then succeeds shows its result`: CR-2(a) recovery. `loaded` is false after a failed new search, then true with no message after a successful retry (so the empty-match text may show again).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 list/search | `ContactsScreensTest` (list order/open, search/clear, empty/failed + retry, `a failed new search shows no empty-match text`), `ContactsViewModelsTest` (load + debounced search, failed load keeps list, `a failed new search does not claim that nothing matches it`, `retrying a failed new search that then succeeds shows its result`), `ContactsRepositoryTest` (search query, paging, blank search), `ContactsRouteInAppTest.the Contacts screen is the real list, not the placeholder` | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.contacts.*' --rerun` (parent, on `da27e7802`) | Pass (42/42) for committed tests; final-pass VM test: Not run (see below) |
| AC1 detail | `ContactsScreensTest` (detail, no call/mail buttons, not-loaded), `ContactsViewModelsTest` (detail/related loaded separately, contact not loadable), `ContactsModelsTest`, `ContactsRouteInAppTest` (opened from list, survives configuration change) | same | Pass |
| AC1 call intent | `ContactIntentsTest` (dial, dialable, launch), `ContactsRouteInAppTest` (Call → first number, phone row → that number, no dialer → toast, nothing started) | same | Pass |
| AC1 mail intent | `ContactIntentsTest.mail sends to the address...`, `ContactsRouteInAppTest.Email opens a mail app with the address` | same | Pass |
| AC1 related items | `ContactsScreensTest` (waiting/mails/events/links, related banner, stale related banner), `ContactsViewModelsTest.a failed refresh keeps the related items shown, with a message`, `ContactsRepositoryTest.related combines...` (order-independent after CR-1), `ContactsModelsTest`; final pass: `...whose reload failed say they may be out of date`, `...never loaded while offline say they are not shown`, `a sent mail and a timed event show when they happen` | same | Committed: Pass. Final-pass screen tests: Not run (see below) |
| AC1 back from detail to list | `ContactsScreensTest` (back callback), `ContactsRouteInAppTest` (back arrow, system back) | same | Pass |
| AC2 contacts tests pass, intent construction, Robolectric/Compose UI tests | all of the above | same | Pass on `da27e7802` (42 tests, 0 failures). Must be re-run with the 4 final-pass tests (expected 46) |

The 4 final-pass tests were not executed by the test-writer: Gradle (and Bash) is denied to sub-agents in this environment. The parent must run them before recording evidence:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.contacts.ContactsScreensTest' --tests 'ch.sonensei.cockpit.contacts.ContactsViewModelsTest' --rerun`

Expected: ContactsScreensTest 14, ContactsViewModelsTest 7, 0 failures, ktlint clean. Then the full package: `--tests 'ch.sonensei.cockpit.contacts.*'` gives 46 tests.

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.contacts.*' --rerun` (parent, revision `da27e7802`)

Result:

Pass: 42 tests, 0 failures (ContactIntentsTest 4, ContactsModelsTest 7, ContactsRepositoryTest 5, ContactsRouteInAppTest 9, ContactsScreensTest 11, ContactsViewModelsTest 6). ContactsRepositoryTest also passed on a separate re-run, which supports CR-1 (no order dependence). The 4 final-pass tests are pending the parent's run (command above).

## Full test execution

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport` (full Android debug unit suite) and `./gradlew ktlintCheck lintDebug` (parent, revision `da27e7802`)

Result:

Pass (full suite passed; ktlint and Android lint clean). This does not include the final-pass test edits.

## .taskcheck

Result:

Not run by the test-writer (sub-agent denied). The parent runs `sh scripts/check.sh` before `done`.

## Bugs found

None. The CR-2 behaviour (the `loaded` reset on a new query, kept on refresh; stale-aware related banner wording) matches the review's intent on inspection. The final-pass tests encode that intent and are expected to pass.

## Coverage

Status: verified for revision `da27e7802` (the parent's fresh run). The 4 final-pass tests were added after this run and are not included in it.

Metric: whole-file coverage of task-changed production files (JaCoCo).
Changed-line coverage: not measured. The five Kotlin files are all new in this task, so whole-file coverage equals coverage of the task's code here.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-contacts`, base `988b870feaed4377f2966acc56e8874ffa6f3035`, HEAD `da27e7802`, task commits `5bbedf2f8` and `da27e7802` as given by the parent. File scope matches the task's `touches` (`android/.../contacts`) plus `strings_contacts.xml`.
- Source/test snapshot identifier (including uncommitted inputs): measured snapshot = `da27e7802` with no uncommitted source/test changes. The final-pass edits to `ContactsScreensTest.kt` and `ContactsViewModelsTest.kt` came later and are not in the measurement. Content hashes not recorded (the test-writer has no shell access).
- Exact included files; renamed/deleted/excluded files and reasons: `ContactIntents.kt`, `ContactsModels.kt`, `ContactsViewModels.kt`, `ContactsRepository.kt`, `ContactsScreen.kt`. Excluded: `strings_contacts.xml` (resources, no executable code) and the Hilt-generated `*_HiltModules*.java` classes in the same package (generated code). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo 0.8.15.202606040825 via Android Gradle plugin 9.4.1, `./gradlew createDebugUnitTestCoverageReport` in `android/`, full debug unit suite.
- Run start/completion time and exit status: run by the parent on 2026-10-22, suite passed (exit 0). Exact times not recorded by the test-writer.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/ch.sonensei.cockpit.contacts/index.source.html` (and per-file `*.kt.html`), read by the test-writer. Its counters match the parent's numbers exactly. Hash not recorded.
- Evidence outputs are fresh and inputs stayed unchanged: the report's ViewModels source view shows the CR-2 `loaded = false` line, and the Screen source view shows the stale-banner lines, so the report describes `da27e7802` code.
- Final run after latest test additions and production fixes: after the production fixes, yes. After the final-pass test additions, no: those are test-only, and a fresh run would only raise the covered counts (expected: `ContactsScreen.kt` L533 `formatInstant` covered).

Values below use covered/total (percentage). Statement counters: JaCoCo instruction counters are used.

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `ContactIntents.kt` | 112/112 (100%) | 10/10 (100%) | 4/4 (100%) | 17/17 (100%) |
| `ContactsModels.kt` | 708/708 (100%) | 48/48 (100%) | 45/45 (100%) | 72/72 (100%) |
| `ContactsViewModels.kt` | 563/563 (100%) | 38/40 (95.0%) | 28/28 (100%) | 74/74 (100%) |
| `ContactsRepository.kt` | 339/381 (89.0%) | 16/18 (88.9%) | 16/19 (84.2%) | 41/48 (85.4%) |
| `ContactsScreen.kt` | 3721/3913 (95.1%) | 140/171 (81.9%) | 76/86 (88.4%) | 323/329 (98.2%) |
| Aggregate (summed over the 5 files; excludes Hilt-generated classes and `strings_contacts.xml`) | 5443/5677 (95.9%) | 252/287 (87.8%) | 169/182 (92.9%) | 527/540 (97.6%) |

Progress across passes: before the test-writer's first-pass tests, `ContactsScreen.kt` was lines 288/328 and branches 111/171, and `ContactsModels.kt` branches were 26/48.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ContactsScreen.kt:533` (`formatInstant`) | Related mail time and timed event start shown on the detail | Final pass: `a sent mail and a timed event show when they happen` (pending parent run) |
| `ContactsScreen.kt:322-323` (stale/non-stale banner wording) | CR-2(b): all 4 message × stale combinations | Branches were already covered; the final pass adds text assertions for the 2 combinations no test had checked (stale FAILED, non-stale OFFLINE) |
| `ContactsScreen.kt:130,138` (data-class `copy`/`componentN`), `:73,:83,:168,:273,:299` (Compose default-parameter branches), `:278` (exhaustive `when` else) | none (compiler-generated) | Non-material |
| `ContactsScreen.kt:187,309,312,287` (`loading == true` progress bar / disabled retry) | Loading indicator while a load runs | Non-material: cosmetic. Loading state transitions are covered at view-model level |
| `ContactsScreen.kt:226,348,353-362,407,418,487,500,523,528` (partial branches of null/blank labels, a contact with only a phone or only an email, role empty) | Header buttons / labels for partial contacts | Low risk: both-present and none-present cases are tested (`the detail calls and mails...`, `a contact without phone or email...`). Not added, to avoid percentage chasing |
| `ContactsViewModels.kt:47` (search with an unchanged query) | Redundant `search` call is a no-op | Low risk. Indirectly exercised by the UI (the text field reports every change). Not added |
| `ContactsViewModels.kt:111` (`refresh()` before `open()`) | Defensive no-op | Non-material |
| `ContactsRepository.kt:35,38` (`ContactsModule` Retrofit providers) | Hilt wiring | Only reachable with the real Retrofit graph. The in-app test binds a fake repository. Not unit-testable without production/config changes |
| `ContactsRepository.kt:92-93,100-102` (`CancellationException` rethrow, `IllegalArgumentException`/`DateTimeException` catches), `:70` (page cap of 50), `:120` (2xx with null body) | Defensive handling of malformed server answers | Non-material defensive paths; malformed JSON (`SerializationException`) and HTTP errors are tested |

## Unable to test

- The real dialer / mail app hand-off on a device. Robolectric records the started intent and simulates "no matching activity", which is the observable contract. An on-device check is not part of the criteria.
- Search debounce through the full Hilt route. It is covered at view-model level with a test dispatcher.
- The final-pass tests could not be executed by the test-writer (no Gradle/Bash access). The parent must run them with the command in "Requirement coverage" and report any failure back. A failure would be a test or production issue for the fixer, not a reason to drop the test.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. On `da27e7802`, the contacts package (42 tests), the full Android unit suite, ktlint and lintDebug pass. Four small final-pass tests (3 screen, 1 view model) cover gaps left by CR-2 and the related-item time display. They are test-only, uncommitted, and must be run by the parent (`ContactsScreensTest`, `ContactsViewModelsTest`) before `done`.
