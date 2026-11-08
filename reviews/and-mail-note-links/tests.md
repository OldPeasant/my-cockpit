# Test Report

Date: 2026-11-08

Task: and-mail-note-links - Android mail: create note and linked items

Scope: task commit 2fac1622b0e1740ef4abe61610219c9dbf207c69 on base 49b1d117ae2928436695d299aa85451676ed42f4 (`git diff 49b1d117a..2fac1622b`), supplied by the parent worker. HEAD = 2fac1622b0e1740ef4abe61610219c9dbf207c69 on branch main. Uncommitted inputs: only the three new test files listed below (and this report); no production change in the working tree.

Requirements:
- `tasks/and-mail-note-links.md` acceptance criteria and recorded assumptions (Create note is a toolbar icon next to To task / To Waiting; links panel below the body)
- Parent worker's instructions (scope, test locations, no Gradle for the helper)
- Existing conventions in `android/app/src/test/java/ch/sonensei/cockpit/mail/read/` (`MailReadScreenTest`, `MailReadViewModelTest`, `MailReadSpamGermanTest`, `MailReadTestSupport`)

Changed production files in scope:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt` (NOTE toolbar button, NOTE notices, `linksPanel` slot with tag `MailReadTags.LINKS`, route wiring of `LinkedItemsSection` and reload after a Created notice)
- `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadRepository.kt` (`MailGtdAction.NOTE` mapped to `MailApi.toNote`)
- `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailApi.kt` (Retrofit interface method `toNote`, POST `api/v1/mail/messages/{id}/to-note`; declarative, no executable lines of its own)
- `android/app/src/main/res/values/strings_mail.xml`, `android/app/src/main/res/values-de/strings_mail.xml` (resources, not instrumented; checked through the Compose tests)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadNoteLinksTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadNoteGermanTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadNoteViewModelTest.kt` (new)

No existing test was edited. The task commit itself already added `MailReadRepositoryTest` "create note posts to to-note and returns the note's title".

## Tests added or updated

Count: 23

`MailReadNoteLinksTest` (Robolectric + Compose, English):
- the create note button asks for a note
- the create note button has an English TalkBack label
- the create note button sits after the GTD actions and before archive
- the create note button waits while an action runs
- without a mail the create note button is disabled
- after a failed load the create note button is disabled
- a created note is announced with its title
- a failed note is reported
- the linked items panel is shown below a mail
- the linked items panel is shown below a text-only mail and while an action runs
- without a mail there is no linked items panel
- a failed load shows no linked items panel
- the route creates a note from the mail and announces it, the mail stays open
- the route reports a note the server could not create
- the route shows the links area for a loaded mail

`MailReadNoteGermanTest` (Robolectric, `@Config(qualifiers = "de")`):
- the create note button has a German TalkBack label
- a created note is announced in German
- a failed note is reported in German

`MailReadNoteViewModelTest` (JVM, coroutines test dispatcher):
- a created note is reported with its title and the mail stays open
- a note the server refused or could not be reached for is reported as failed
- a second tap on create note while one runs creates only one note
- no note is created before the mail is shown
- a note answer for a closed mail is not shown on the next one

## Requirement coverage

The helper could not run Gradle in this environment (sub-agents are not permitted to). Every result below is "Not run" until the parent worker runs the command and records the outcome.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. "Create note" action from the read view, same server call as web (POST /api/v1/mail/messages/{id}/to-note) | `MailReadRepositoryTest` create note posts to to-note (task commit); `MailReadNoteLinksTest` the create note button asks for a note, the route creates a note from the mail and announces it, the route reports a note the server could not create, button disabled tests, position test; `MailReadNoteViewModelTest` all 5 | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.*'` | Not run: the helper cannot run Gradle; the parent must run it |
| 2. The read view shows the linked items panel for the mail | `MailReadNoteLinksTest` the linked items panel is shown below a mail, shown below a text-only mail and while an action runs, without a mail there is no linked items panel, a failed load shows no linked items panel, the route shows the links area for a loaded mail | same command | Not run: the helper cannot run Gradle. Partial: the route's `LinkedItemsSection` with a real `LinkedItemsViewModel` is not exercised (see Unable to test) |
| 3. New strings in English and German | `MailReadNoteLinksTest` English label/notice tests; `MailReadNoteGermanTest` all 3 | same command | Not run: the helper cannot run Gradle |
| 4. Unit/Compose tests cover the new behavior; `./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass | all of the above | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`; `sh scripts/check.sh` | Not run: the helper cannot run Gradle; the parent must run both |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.MailReadNoteLinksTest' --tests 'ch.sonensei.cockpit.mail.read.MailReadNoteGermanTest' --tests 'ch.sonensei.cockpit.mail.read.MailReadNoteViewModelTest' --tests 'ch.sonensei.cockpit.mail.read.MailReadRepositoryTest'`

Result:

Not run (sub-agents cannot run Gradle here). The tests were written against the current production code by reading it; the parent worker runs them.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run (helper has no Gradle access); delegated to the parent worker.

## .taskcheck

Result:

Not run (`sh scripts/check.sh` uses Gradle); delegated to the parent worker.

## Bugs found

None found by reading the code against the acceptance criteria. Test results are pending the parent's run; if any of the new tests fail, the parent must determine whether the test or the production code is wrong.

## Coverage

Status: unavailable - the test-writer helper cannot run Gradle in this environment, so the configured Android coverage command (`cd android && ./gradlew createDebugUnitTestCoverageReport`, JaCoCo via AGP, report `android/app/build/reports/coverage/test/debug/report.xml`) was not executed. No coverage numbers are reported; none from earlier runs or other scopes are substituted.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-mail-note-links; base 49b1d117ae2928436695d299aa85451676ed42f4; HEAD 2fac1622b0e1740ef4abe61610219c9dbf207c69; task-owned commit 2fac1622b supplied by the parent and confirmed by `git diff --name-status 49b1d117a..2fac1622b`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 2fac1622b plus the three new untracked test files listed above; no measurement was taken, so no hash was recorded.
- Exact included files; renamed/deleted/excluded files and reasons: in scope `mail/read/MailReadScreen.kt`, `mail/read/MailReadRepository.kt`, `mail/list/MailApi.kt` (interface only, JaCoCo will show no executable lines for the new method). Excluded: the two `strings_mail.xml` files (resources, not instrumented). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP; `./gradlew createDebugUnitTestCoverageReport` in `android/`; not run.
- Run start/completion time and exit status: not run.
- Generated report paths and report hashes/run identifier: none.
- Evidence outputs are fresh and inputs stayed unchanged: not applicable, no run.
- Final run after latest test additions and production fixes: no - the helper cannot run Gradle.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt` | not measured | not measured | not measured | not measured |
| `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadRepository.kt` | not measured | not measured | not measured | not measured |
| `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailApi.kt` | not measured | not measured | not measured | not measured |

Expected gaps from reading the code (to be confirmed by a real run):

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailReadScreen.kt` `mailLinks` (Hilt branch returning `hiltViewModel(key = linksViewModelKey(...))`) | Criterion 2: the panel's view model for the shown mail | Not reachable in Robolectric screen tests: it requires an activity that is a `GeneratedComponentManagerHolder` (Hilt). Gap; would need a Hilt test activity or an instrumented/e2e test |
| `MailReadScreen.kt` `MailReadRoute` `linksPanel` lambda body calling `LinkedItemsSection(ItemRef("EMAIL", id), ...)` and its `open` handler (InApp target sets `linked`, which shows `SearchItemScreen`) | Criterion 2: panel content for the mail and opening a linked item over the mail | Same reason (links view model is null without Hilt). The slot itself is covered by `MailReadNoteLinksTest` with a fake panel; `LinkedItemsSection` has its own tests from and-links-panel |
| `MailReadScreen.kt` `LaunchedEffect(state.notice)` calling `links?.reload()` after a Created notice | Panel refreshes after a note/task was created | Effect runs in the route tests but `links` is null, so the reload call itself is not verified. Gap, same reason |
| `MailReadScreen.kt` linked-item overlay branch (`item != null`, `BackHandler`, `SearchItemScreen`) | Back from a linked item returns to the mail | Not reachable without the Hilt panel; gap |

Follow-up for the parent: run `cd android && ./gradlew createDebugUnitTestCoverageReport` and read the `MailReadScreen.kt` and `MailReadRepository.kt` counters (INSTRUCTION, BRANCH, LINE, METHOD) from `android/app/build/reports/coverage/test/debug/report.xml` if task-scoped numbers are required.

## Unable to test

- Running any test, lint or coverage: the helper cannot run Gradle here; the parent must run the commands listed above.
- The real `LinkedItemsSection` inside `MailReadRoute` (Hilt-provided `LinkedItemsViewModel`, `ItemRef("EMAIL", {messageId})`, reload after Created, opening an in-app linked item over the mail and back): needs a Hilt-enabled activity; not covered by unit tests. Follow-up: an instrumented test or e2e flow, or accept the gap since the panel component is tested in and-links-panel.
- The end-to-end server call against a running backend: covered at the HTTP level by `MailReadRepositoryTest` with MockWebServer only.

## Summary

Tests added (23 across three new files); they have not been run because the helper cannot run Gradle. The parent worker must run `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` and record the results. No production defects were found by reading the code. Task-scoped coverage was not measured, and the Hilt-only route wiring of the links panel remains untested by unit tests.
