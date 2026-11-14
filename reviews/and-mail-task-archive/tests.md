# Test Report

Date: 2026-11-14

Task: and-mail-task-archive - Android: mail → Next action & archive (read view, New view, mail list)

Scope: task commits 50ca61a7e7aa61a43c7f03fbee390960a0206490, 23dc801241216a6d297ff945ba4fd62f91a052a4, 3d3f6a0ca4b70f7500f45b9fea4030c7dab87660 on base 1bc5b01125fc3be34d5ba13b87aee4baff8aa0f0 (`git diff 1bc5b0112..3d3f6a0ca -- android`). HEAD at report time is 0a32d121223a635470ba8d2972fdca814efc4e37, which only adds `reviews/and-mail-task-archive/review.md`; production code is identical to 3d3f6a0ca. Branch: main. Uncommitted changes: only the test files listed below (and this report).

Requirements:
- `tasks/and-mail-task-archive.md` acceptance criteria and recorded assumptions (toolbar: "To Waiting" moved to the overflow; selection action in the overflow; snackbar stays while a mail is named)
- `api/openapi.yaml` `POST /api/v1/mail/messages/to-tasks` contract (request/response, 404 creates nothing, one result per distinct id)
- `docs/ARCHITECTURE.md` "Coverage"; existing test conventions in `android/app/src/test/java/ch/sonensei/cockpit/mail` and `.../newitems`

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailNextActionsRepositoryTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailNextActionsViewModelTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailNextActionsScreenTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailNextActionInAppTest.kt` (new, Hilt activity)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadNextActionViewModelTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadNextActionRouteTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/newitems/NewNextActionViewModelTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/newitems/NewNextActionScreenTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadScreenTest.kt` (updated: the toolbar test now expects "Next action & archive" in the toolbar and "To Waiting" in the menu; 4 tests added)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadNoteLinksTest.kt` (updated: the 320dp toolbar test checks the new button instead of "To Waiting", which moved to the menu)

No existing assertion was weakened: the two updated tests follow the recorded toolbar assumption and still check every toolbar button (and that "To Waiting" still works, from the menu).

## Tests added or updated

Count: 51 tests added (repository 7, list view model 5, list screen 8, in-app 3, read view model 7, read route 3, read screen 4, New view model 9, New screen 5), 2 existing tests updated.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 read view: action next to "To task" (unchanged); success opens the task detail; Back returns to where the mail was opened from | `MailReadScreenTest` (toolbar order, labelled, To Waiting in menu, all toolbar actions), `MailReadNextActionViewModelTest` (success, busy, double tap, stale answer, no mail), `MailReadNextActionRouteTest` (archived: task replaces mail, back calls onRemoved + onClose), `MailNextActionInAppTest` "the read view opens the new task ... back returns to the list without it" | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*' --tests 'ch.sonensei.cockpit.newitems.*'` | Pass |
| AC2 New view: mail rows offer the same action, same result | `NewNextActionViewModelTest` (archived mail leaves list + cache, task opens once, offline/unknown/double tap send nothing), `NewNextActionScreenTest` (button beside "→ Inbox task", disabled offline/while converting, route opens the task once via `LocalTaskOpener`) | same | Pass |
| AC3 mail list multi-select: one Next action per mail, archived mails leave, list stays, snackbar "N next actions created" opens Next Actions | `MailNextActionsViewModelTest`, `MailNextActionsScreenTest` (menu item, disabled while running, plural/singular, "Open Next Actions" → openNext, auto-dismiss), `MailNextActionInAppTest` "the multi-selection's next actions leave the list and the snackbar opens Next Actions" (real shell navigates to Next) | same | Pass |
| AC4 archive failure keeps the task (single mail: detail still opens) and names the mail; failed creation leaves the mail with an error | `MailReadNextActionViewModelTest` (NotArchived with reason, NextActionFailed for 404/unreadable/offline/empty), `MailReadNextActionRouteTest` (not archived: back returns to the mail with the notice; failed creation keeps the mail), `MailReadScreenTest` notices (NO_ROLE_FOLDER, other code, no code), `NewNextActionViewModelTest`/`NewNextActionScreenTest` notices, `MailNextActionsViewModelTest` (unarchived mails stay and are named, blank subject falls back to the task title; failed call keeps every mail), `MailNextActionsScreenTest` (snackbar lists each mail and stays until closed) | same | Pass |
| AC5 the task detail's linked items show the mail, and opening it shows the archived mail | `MailNextActionInAppTest` "the read view opens the new task, whose links show the mail ..." (links panel lists the EMAIL link) and "opening the linked mail from the new task's detail shows the archived mail" | same | Fail: TW-2 (the link shows, but tapping it does nothing when the task was opened from the read view) |
| AC6 strings EN + DE; touch targets ≥ 48dp; fits at 320dp | EN texts asserted in the screen tests above; `MailReadScreenTest` 48dp touch target, `MailReadNoteLinksTest` 320dp toolbar, `NewNextActionScreenTest` 320dp row buttons with 48dp touch height, `MailNextActionsScreenTest` 320dp selection bar + menu item touch height | same | Pass. German strings are not asserted by these tests; their presence is enforced by `lintDebug` (MissingTranslation), which this helper did not run |
| AC7 unit/Compose tests cover the new behavior; ktlintCheck, testDebugUnitTest, lintDebug, check.sh pass | all of the above | `cd android && ./gradlew ktlintCheck testDebugUnitTest` | Fail: 5489 tests, 1 failed (TW-2). ktlintCheck passed. `lintDebug` and `sh scripts/check.sh`: Not run (the check would fail on the same test; left to the parent after the fix) |
| Server contract: list NEXT and archive true sent, 100 ids per request, distinct ids, 404/400 fail, 5xx/no connection offline, unreadable answer fails | `MailNextActionsRepositoryTest` | same | Pass |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests '*NextAction*' --tests 'ch.sonensei.cockpit.mail.read.MailReadScreenTest' --tests 'ch.sonensei.cockpit.mail.read.MailReadNoteLinksTest'`, then `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*' --tests 'ch.sonensei.cockpit.newitems.*'`

Result:

Fail: 1086 tests completed, 1 failed (`MailNextActionInAppTest` "opening the linked mail from the new task's detail shows the archived mail", TW-2). ktlintCheck passed. All other tests pass, including the two updated toolbar tests.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest`

Result:

Fail: 5489 tests completed, 1 failed (the same TW-2 test only). No other Android test was affected by the toolbar change.

## .taskcheck

Result:

Not run: `sh scripts/check.sh` runs the Android unit tests, which fail on the TW-2 test until production is fixed. Server and web are outside this task's changes.

## Bugs found

### Opening the linked mail from the task detail does nothing after "Next action & archive" in the read view

Finding ID:

`TW-2`

Test:

`ch.sonensei.cockpit.mail.list.MailNextActionInAppTest` "opening the linked mail from the new task's detail shows the archived mail"

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt:124-137` (task overlay via `SearchItemScreen(TASK_ITEM_TYPE, ...)`), which reaches `android/app/src/main/java/ch/sonensei/cockpit/search/SearchItemScreen.kt:40` `TaskDetailScreen(id, TaskDetailNavigation(close = onClose), modifier)`: `openItem`, `openTask` and `openScreen` keep their no-op defaults.

Requirement:

AC5: "The task detail's linked items show the mail, and opening it shows the archived mail."

Expected behavior:

In the task detail opened by "Next action & archive" from the read view, tapping the linked mail (EMAIL link in the links panel) opens that mail (now in the Archive).

Actual behavior:

The links panel lists the mail, but tapping it does nothing: the test times out waiting for the mail read view after the tap. (The New view and the Next Actions list open tasks through the shell's task route, whose `TaskDetailNavigation` handles `openItem`, so they are not affected.)

Severity:

Medium

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

### Mixed result when more than 100 mails are selected is reported as a total failure

Finding ID:

`TW-1`

Test:

`MailNextActionsRepositoryTest` "a failed later batch fails the whole call, after the earlier batch was sent" (passes; it documents the current behavior, no failing test because the expected outcome for this case is not specified)

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailRepository.kt:168-181` (`toNextActions` returns `Failed` if any batch fails) and `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt` `nextActionsForSelection` (`else` branch: `NEXT_ACTIONS_FAILED`, every mail kept)

Requirement:

AC3/AC4: archived mails leave the list; a failed creation leaves the mail in place with an error.

Expected behavior:

When batch 1 (100 mails) succeeds and batch 2 fails (e.g. a mail of batch 2 is gone, 404), the 100 mails of batch 1 have their Next actions and are archived; the list should drop them and report only the failed batch.

Actual behavior:

The list keeps all selected mails and says "The next actions could not be created; the mails stay where they are." although the first 100 were created and archived (until the next reload). Only reachable with more than 100 selected mails.

Severity:

Low

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.` (or record an assumption that this edge case is accepted)

### Observation (no test, not a defect against a stated requirement)

Finding ID:

`TW-3`

In the read view (`MailReadViewModel.nextAction`, `it.message?.subject.orEmpty()`) and the New view (`NewViewModel.nextAction`, the row subject), a mail without a subject is named as `“”` in the "could not be archived" notice, while the mail list falls back to the task title. Severity: Low. Fix optional (use the task title, as the list does).

## Coverage

Status: verified for the run below (the one known-failing TW-2 test was excluded from the coverage run so the report could be generated; its two passing siblings were included).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured separately, from the per-line entries of the JaCoCo XML (covered instructions `ci` above 0) intersected with the added/modified lines of `git diff -U0 1bc5b0112..HEAD -- android/app/src/main/java` (lines without a JaCoCo entry are not executable and not counted): 212/223 changed executable lines covered. The 11 missed: 10 in `MailApi.kt` (Kotlin constructors of the response DTOs; kotlinx.serialization uses its own synthetic constructor) and `MailScreen.kt:135` (a default parameter value).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-mail-task-archive; base 1bc5b01125fc3be34d5ba13b87aee4baff8aa0f0 (from the parent); task commits 50ca61a7e, 23dc80124, 3d3f6a0ca (from the parent, confirmed with `git log`); measured code = 3d3f6a0ca4b70f7500f45b9fea4030c7dab87660 (HEAD 0a32d121223a635470ba8d2972fdca814efc4e37 adds only `reviews/` on top).
- Source/test snapshot identifier (including uncommitted inputs): production = commit 3d3f6a0ca (no uncommitted production changes). Uncommitted test files, sha256 prefixes: MailReadNoteLinksTest cd1c5adb74e97a50, MailReadScreenTest 345e7c642940897e, MailNextActionInAppTest e599a91411ebd013, MailNextActionsRepositoryTest 3ca94357082f1ff2, MailNextActionsScreenTest 404ca3249a8f8952, MailNextActionsViewModelTest 74efd9795c7fb8b3, MailReadNextActionRouteTest 457d453aa600e057, MailReadNextActionViewModelTest 2c48487d89afaef1, NewNextActionScreenTest 66212712b250d1b1, NewNextActionViewModelTest 25e388d8da506553 (unchanged since the coverage run).
- Exact included files; renamed/deleted/excluded files and reasons: included `mail/list/MailApi.kt`, `mail/list/MailListViewModel.kt`, `mail/list/MailModels.kt`, `mail/list/MailRepository.kt`, `mail/list/MailScreen.kt`, `mail/read/MailReadScreen.kt`, `mail/read/MailReadViewModel.kt`, `newitems/NewScreen.kt`, `newitems/NewViewModel.kt`, `ui/CockpitShell.kt` (all under `android/app/src/main/java/ch/sonensei/cockpit/`). Excluded: the four `strings_*.xml` resource files (not executable). No renames or deletions. All 10 included files were found in the report.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (Gradle 9.8.1); working directory `android/`; `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.*' --tests 'ch.sonensei.cockpit.mail.compose.*' --tests 'ch.sonensei.cockpit.newitems.*' --tests 'ch.sonensei.cockpit.mail.list.MailList*' --tests 'ch.sonensei.cockpit.mail.list.MailModels*' --tests 'ch.sonensei.cockpit.mail.list.MailRouteInAppTest' --tests 'ch.sonensei.cockpit.mail.list.MailRepositoryTest' --tests 'ch.sonensei.cockpit.mail.list.MailFolder*' --tests 'ch.sonensei.cockpit.mail.list.MailSearch*' --tests 'ch.sonensei.cockpit.mail.list.MailActions*' --tests 'ch.sonensei.cockpit.mail.list.MailReadNavigationInAppTest' --tests 'ch.sonensei.cockpit.mail.list.MailSelectionScreenTest' --tests 'ch.sonensei.cockpit.mail.list.MailLoadOlder*' --tests 'ch.sonensei.cockpit.mail.list.MailNextActions*' --tests 'ch.sonensei.cockpit.mail.list.MailNextActionInAppTest.the*' createDebugUnitTestCoverageReport` (all mail and New view tests except the TW-2 test). The other parts of the app (for example `ui/CockpitShell.kt`'s own shell tests) were not in this selection, so `CockpitShell.kt` whole-file numbers are lower than with the full suite.
- Run start/completion time and exit status: about 2026-11-14T08:29:50Z to 08:30:33Z (report written 08:30:33Z), BUILD SUCCESSFUL, exit 0; 1080 tests, 0 failures.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 2ffd97977ebe13b5de5e4931d3f5bc62744d14b88c16b4149b8ac18b64e7d478.
- Evidence outputs are fresh and inputs stayed unchanged: the report timestamp matches this run; the test task executed (not up to date); test files were last edited before the run and production was not changed.
- Final run after latest test additions and production fixes: yes for the test additions; no production fix exists yet (TW-1, TW-2 open), so coverage must be measured again after the fix.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Statements = JaCoCo INSTRUCTION, Functions = METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `mail/list/MailApi.kt` | 750/1324 (56.6%) | N/A (0/0) | 95/141 (67.4%) | 109/157 (69.4%) |
| `mail/list/MailListViewModel.kt` | 5137/5205 (98.7%) | 412/432 (95.4%) | 127/127 (100.0%) | 482/482 (100.0%) |
| `mail/list/MailModels.kt` | 1022/1038 (98.5%) | 93/98 (94.9%) | 70/70 (100.0%) | 120/123 (97.6%) |
| `mail/list/MailRepository.kt` | 1227/1236 (99.3%) | 91/98 (92.9%) | 33/33 (100.0%) | 127/127 (100.0%) |
| `mail/list/MailScreen.kt` | 8804/9050 (97.3%) | 323/368 (87.8%) | 180/215 (83.7%) | 705/715 (98.6%) |
| `mail/read/MailReadScreen.kt` | 6235/6550 (95.2%) | 310/365 (84.9%) | 128/146 (87.7%) | 466/474 (98.3%) |
| `mail/read/MailReadViewModel.kt` | 1497/1508 (99.3%) | 117/122 (95.9%) | 66/66 (100.0%) | 145/145 (100.0%) |
| `newitems/NewScreen.kt` | 3837/4004 (95.8%) | 138/159 (86.8%) | 67/78 (85.9%) | 292/297 (98.3%) |
| `newitems/NewViewModel.kt` | 1519/1519 (100.0%) | 94/94 (100.0%) | 60/60 (100.0%) | 177/177 (100.0%) |
| `ui/CockpitShell.kt` | 1450/2203 (65.8%) | 64/135 (47.4%) | 39/55 (70.9%) | 135/178 (75.8%) |
| Aggregate (summed counters, 10 files, none unmeasured; 4 string resource files excluded) | 31478/33637 (93.6%) | 1642/1871 (87.8%) | 865/991 (87.3%) | 2758/2875 (95.9%) |

Changed lines per file (covered/executable changed): MailApi 16/26, MailListViewModel 32/32, MailModels 7/7, MailRepository 13/13, MailScreen 37/38, MailReadScreen 29/29, MailReadViewModel 18/18, NewScreen 24/24, NewViewModel 35/35, CockpitShell 1/1.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail/list/MailApi.kt:216-234` | Kotlin constructors of `MailToTasksResponseDto`, `MailToTaskResultDto`, `MailToTaskTaskDto` | Justified gap: deserialization goes through the serializer's synthetic constructor, exercised by `MailNextActionsRepositoryTest`; the rest of the file is pre-existing API surface |
| `mail/list/MailScreen.kt:135` and branches at 120/122 | default `openNext` of `MailRoute`; the Hilt check of `MailScreen` | Justified gap: the shell always passes `openNext` (covered by the in-app test); non-Hilt branch is the placeholder |
| `mail/list/MailScreen.kt:436` (5/6 branches) | snackbar result handling in `NextActionsSnackbarEffect` | Action, dismiss icon and timeout all tested in `MailNextActionsScreenTest`; the remaining branch is a Compose-generated one |
| `mail/read/MailReadScreen.kt:127` (2/4 branches) | the `leave` lambda of the task overlay (archived or not) | Both outcomes tested via system back in `MailReadNextActionRouteTest`; the close path of the task detail itself has no button here |
| `mail/read/MailReadViewModel.kt:171` (2/4 branches) | `it.message?.subject.orEmpty()` when no mail is loaded | Not reachable: `nextAction` needs a loaded mail (tested: nothing is sent without one) |
| `newitems/NewScreen.kt:138, 496` (1/2 branches) | `taskOpener?.open` without a shell opener; notice color branch | Justified gap: the shell always provides the opener; text of each notice is tested |
| `ui/CockpitShell.kt:321` | `openNext` navigation from the Mail destination | Covered by `MailNextActionInAppTest` (Next screen shown); the rest of the file is outside this task |

## Unable to test

- German texts of the new strings are not asserted by a test; their presence in `values-de` is checked by `lintDebug` (MissingTranslation), not run by this helper. Follow-up: the parent runs `lintDebug`.
- The task detail opened from the read view has no close button of its own (only system back), as for every item opened through `SearchItemScreen`; only system back was tested.
- AC5 "opening it shows the archived mail" from the New view or Next Actions list (task opened through the shell's task route) was not exercised end to end; the code path differs from the failing read-view path and uses the shell's `openItem`.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion (TW-2 Medium with a failing test for AC5; TW-1 Low, a mixed batch result shown as a total failure; TW-3 Low observation).
