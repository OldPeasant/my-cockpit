# Test Report

Date: 2026-11-08

Task: mail-load-older-ui: Load mail older than the sync window (web + Android)

Scope: commits `8c08d8089` (web) and `ff5eccc17` (Android) on top of `98251a938`. Before the
parent's rebase these were `aff8220c4` and `1094d9ff0` on base `ff797183e61538afeb87cb4dd3f827e744ecca01`.
`git diff 1094d9ff0 HEAD -- web android server` is empty, so the tested production code is
identical at the measured HEAD `bf037c198c42e5841e4153c8367f45d470ba07a1`. That HEAD only adds
`reviews/mail-load-older-ui/review.md`.
Production files in scope: `web/src/app/features/mail/list/{mail-list-store.ts,mail.service.ts,mail-list-page.html}`,
`web/src/locale/{messages.xlf,messages.de.xlf}` (translations, not instrumented),
`android/app/src/main/java/ch/sonensei/cockpit/mail/list/{MailApi,MailRepository,MailListViewModel,MailScreen}.kt`,
`android/app/src/main/res/values{,-de}/strings_mail.xml` (resources, not instrumented).

Requirements:
- `tasks/mail-load-older-ui.md` acceptance criteria 1-4
- SPEC.md §7.2 (sync window, `POST /api/v1/mail/folders/{id}/load-older`)
- `docs/ARCHITECTURE.md` "Coverage" and i18n conventions
- Parent's instructions (cover success, nothing older, offline/failed, not offered in unified inbox/search/with a next cursor, disabled while loading, German strings)

## Test files changed

- `web/src/app/features/mail/list/mail-testing.ts` (FakeMail gained a controllable `loadOlder`)
- `web/src/app/features/mail/list/mail-list-store.spec.ts`
- `web/src/app/features/mail/list/mail.service.spec.ts`
- `web/src/app/features/mail/list/mail-list-page.spec.ts`
- `web/src/locale/messages-de.spec.ts`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailLoadOlderRepositoryTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailLoadOlderViewModelTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailLoadOlderScreenTest.kt` (new; contains `MailLoadOlderScreenTest` and `MailLoadOlderGermanTest`)

## Tests added or updated

Count: 57. Web: 26 (store 15, service 3, page 7, German xlf 1). Android: 31 (repository 8,
view model 11, screen 8, German 4).

## Requirement coverage

Commands: W1 = `cd web && npx ng test --watch=false --include src/app/features/mail/list/mail-list-store.spec.ts --include src/app/features/mail/list/mail.service.spec.ts --include src/app/features/mail/list/mail-list-page.spec.ts --include src/locale/messages-de.spec.ts`.
A1 = `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.MailLoadOlder*'`.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 web: at the end of a folder, "Load older from server" calls the endpoint, shows progress, then the newly cached mails | service: `posts to the folder with the default limit, silently, and reads the counts`, `passes a given limit and reads missing counts as nothing loaded`; store: `calls load-older for the shown folder, then shows the newly cached messages at the end`, `reads more than one page when more messages were cached than one page holds`, `keeps infinite scroll going when the server has more cached mail than was asked for`, `starts one request at a time`; page: `offers it at the end of a folder, shows the progress, then the newly cached messages`, `is offered under an empty folder` | W1 | Pass |
| AC1 web: offered only at the end of a folder (not in the unified Inbox or a search, not while more cached pages follow, not after a failed load) | store: `is offered at the end of a fully loaded folder only`, `is offered for an empty folder`, `is not offered for search results, while loading, or after a failed load`, `does nothing where it is not offered`; page: `is not offered in the unified Inbox`, `is not offered while more cached pages follow`, `is not offered for search results` | W1 | Pass |
| AC1 web: the button is disabled while loading | page: `offers it at the end of a folder, ...` (asserts `disabled`) | W1 | Pass |
| AC1 web: leaving the folder while it loads older | store: `drops the answer when another folder was opened meanwhile` (Pass), `can load older in the next folder after leaving one while it loaded older` (**Fail**, TW-1) | W1 | Fail (TW-1) |
| AC2 web: errors (connection, nothing older) shown non-blocking | store: `says so when the server has nothing older, without reloading the list`, `shows a failed load-older without a toast and keeps the list; a retry clears it`, `shows a generic message when the connection fails`, `shows a failure of reading the list afterwards and keeps the list`, `clears the message when the folder is loaded again`; service: `rejects a failure with the ApiError, without a toast`; page: `says non-blocking that nothing older exists, and keeps the list and the button`, `shows a connection error at the end of the list without a toast` | W1 | Pass |
| AC1 Android: the endpoint call and reading the list up to its new end | `MailLoadOlderRepositoryTest` (8 tests: POST path and limit, missing counts, 404/503/closed server, single page, multi-page without duplicates with the cursor kept, stop at an empty page or empty cursor, a minimum limit of 1, a failed page fails the read) | A1 | Pass |
| AC1 Android: progress, then the newly cached mails; selection kept; hand-over to infinite scroll | VM: `it loads older mail for the folder, shows the progress, then the list up to its new end`, `a cursor after the newly cached mails hands over to infinite scroll`, `only one load older runs at a time`; screen: `the end of a folder offers load older from server`, `while it loads the progress shows and the button is disabled`, `an empty folder offers it under the empty text` | A1 | Pass |
| AC1 Android: not offered in the unified inbox or a search, with a next cursor, while loading, offline or failed | VM: `it is offered at the end of a fully loaded folder only`, `it does nothing in the unified inbox or while cached pages follow`; screen: `it is not offered in the unified inbox`, `... while cached pages follow`, `... in a search`, `... after the folder failed to load` | A1 | Pass |
| AC1 Android: leaving or refreshing the folder while it loads older | VM: `leaving the folder while it loads older drops the answer and the progress`, `refreshing or opening another folder clears the last message` | A1 | Pass |
| AC2 Android: nothing older, offline and refused shown non-blocking at the end of the list | VM: `nothing older on the server is said at the end of the list, which stays`, `offline and refused load older are told apart and keep the list`, `a failure reading the list afterwards is shown and keeps the list`, `a retry clears the last message while it runs`; screen: `nothing older, offline and refused are said above the button, which tries again` | A1 | Pass |
| AC3 web: new strings in English and German | `messages-de.spec.ts`: `has the load-older-from-server messages in English and German (mail-load-older-ui)` (Pass). The existing checks `has a unit for every extracted message ...` and `translates the current English text of every message` **fail** (TW-2) | W1 | Fail (TW-2) |
| AC3 Android: new strings in English and German | `MailLoadOlderGermanTest` (4 tests: button, progress, nothing older, offline, refused, all in the `de` locale); English texts in `MailLoadOlderScreenTest` | A1 | Pass |
| AC4: unit tests cover the new behavior; `sh scripts/check.sh` passes | the tests above; full suites below | see "Full test execution" | Fail: the web suite has 3 failing tests (TW-1, TW-2) |

## Focused test execution

Command:

W1 (above)

Result:

Fail: 242 passed, 3 failed (TW-1: 1 store test; TW-2: 2 existing tests in `messages-de.spec.ts`). The full suite and `.taskcheck` ran after the last test edit and gave the same 3 failures.

Command:

A1 (above), then `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.*'`

Result:

Pass. ktlint is clean. The `MailLoadOlder*` classes ran 31 tests, all passed (JUnit XML: German 4, Repository 8, Screen 8, ViewModel 11). The whole `mail.list` package passed with no failures.

## Full test execution

Command:

`cd web && npm run coverage` (the complete web Vitest suite with coverage), then `cd web && npm run lint` and `npx prettier --check` on the changed spec files

Result:

Fail: 193 files and 4546 tests. 4543 passed, 3 failed: the same 3 as above (TW-1, TW-2). Lint passed ("All files pass linting"), and Prettier is clean.

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport` (the complete Android unit suite with coverage), then `cd android && ./gradlew lintDebug`

Result:

Pass: exit 0. The JUnit XML lists 4735 test cases in 383 classes, with 0 failures. `lintDebug` passed with no output. `ktlintCheck` passed in A1.

## .taskcheck

Result:

Fail. `sh scripts/check.sh` ran from 2026-11-08T20:48:30Z to 20:51:03Z. tfp and server passed. The web step failed with the same 3 tests (4543 passed, 3 failed), so `set -e` stopped the script before the Android step. The Android step's commands (`ktlintCheck testDebugUnitTest lintDebug`) passed when run separately, as reported above.

## Bugs found

### Web: "load older" progress sticks after leaving the folder while it loads

Finding ID:

`TW-1`

Test:

`MailListStore > load older from server (SPEC.md §7.2) > can load older in the next folder after leaving one while it loaded older`

Production source:

`web/src/app/features/mail/list/mail-list-store.ts:259-291` (`loadOlder`: `finally { if (seq === this.loadSeq) this.loadingOlder.set(false); }`); `load()` (line 146) and `restore()` (line 228) never reset `loadingOlder`.

Requirement:

AC1 and AC2: the progress shows while loading, and errors are non-blocking. The button must stay usable in the folder that is shown.

Expected behavior:

After another folder is opened (or the list is reloaded or restored) while a load-older request runs, `loadingOlder()` is false once that request settles, and "Load older from server" works in the new folder.

Actual behavior:

`loadingOlder()` stays `true` for good (`expected true to be false`). The page keeps showing "Loading older messages from the server…" with a disabled button, and `loadOlder()` returns early. This is the same defect as the code review's P1 finding. The Android view model handles it (its `load()` resets `loadingOlder`), as `leaving the folder while it loads older drops the answer and the progress` shows.

Severity:

High

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

### Web: regenerated messages.xlf has 16 messages without German translations and 1 outdated one

Finding ID:

`TW-2`

Test:

`German translations (messages.de.xlf) > has a unit for every extracted message and none for messages that no longer exist` and `German translations (messages.de.xlf) > translates the current English text of every message` (existing tests in `web/src/locale/messages-de.spec.ts`)

Production source:

`web/src/locale/messages.xlf` (regenerated by this task's web commit) and `web/src/locale/messages.de.xlf`

Requirement:

AC3 (strings in English and German) and AC4 (`sh scripts/check.sh` passes). ARCHITECTURE.md requires every extracted message to have a German translation (XLIFF target element).

Expected behavior:

Every unit in `messages.xlf` has a unit in `messages.de.xlf` with the same source text.

Actual behavior:

The task's `npm run i18n:extract` pulled in 16 `calendar.invite.*` units: `open`, `saveFirst`, `cancelSent`, `requestSent`, `title`, `to`, `method`, `request`, `cancel`, `loading`, `noAccounts`, `account`, `send`, `accountsFailed`, `failed` and `rejected`. They come from the itip-api invitation dialog, which is already on main, but the base `messages.xlf` did not have them. Neither file has a German unit for them. The extraction also changed the source of `capture.save` from `" Save to Inbox "` to `"Save to Inbox"`, while `messages.de.xlf` still has the old source. The task's own four new messages are translated correctly. Probably `npm run build:de` fails as well, but that was not run.

Severity:

High (it blocks `.taskcheck` and therefore `tasks.py done`)

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.` (Add the German units for the 16 `calendar.invite.*` ids and update the `capture.save` source in `messages.de.xlf`. Whether this belongs in this task's scope is the parent's decision. `web/src/locale` is in the task's `touches`.)

## Coverage

Status: Android **verified**. Web **unavailable** for the full suite: `npm run coverage` exited
nonzero because of the 3 failing tests, and Vitest wrote no `coverage-final.json`. Only a
**partial, diagnostic** web run exists (see below). It does not include the store spec.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `mail-load-older-ui`. The parent supplied base `ff797183e61538afeb87cb4dd3f827e744ecca01` and commits `aff8220c4` (web) and `1094d9ff0` (Android). The parent rebased them during this run to `8c08d8089` and `ff5eccc17`. Measured HEAD is `bf037c198c42e5841e4153c8367f45d470ba07a1`. `git diff 1094d9ff0 HEAD -- web android server` is empty, so the production content is identical. Scope = the production files in those two commits (`git diff --stat ff797183e..1094d9ff0`).
- Source/test snapshot identifier (including uncommitted inputs): production = HEAD `bf037c198` (no uncommitted production changes). Uncommitted test files, sha256: `mail-list-store.spec.ts` 9e2b831b…f873, `mail-list-page.spec.ts` 8eacd74a…dc5df, `mail.service.spec.ts` bc5ebec2…90f9, `mail-testing.ts` d2d2a17c…939af, `messages-de.spec.ts` 069f5103…18cc, `MailLoadOlderRepositoryTest.kt` ed3f27a7…152e, `MailLoadOlderViewModelTest.kt` c1db072b…203f23, `MailLoadOlderScreenTest.kt` 0e5e08d6…2c481. No test was edited after the coverage runs.
- Exact included files; renamed/deleted/excluded files and reasons: included `android/.../mail/list/{MailApi,MailRepository,MailListViewModel,MailScreen}.kt` and `web/src/app/features/mail/list/{mail-list-store.ts,mail.service.ts,mail-list-page.html}`. Excluded because they are not executable or instrumented: `web/src/locale/messages.xlf`, `messages.de.xlf`, and `android/app/src/main/res/values{,-de}/strings_mail.xml`. The German strings are covered by tests instead. Nothing was renamed or deleted.
- Provider/version, command, working directory, and test selection: Android = JaCoCo via AGP `enableUnitTestCoverage`, `./gradlew createDebugUnitTestCoverageReport` in `android/`, all debug unit tests. Web full = Vitest + `@vitest/coverage-v8` via `npm run coverage` in `web/`, all specs (failed, no report). Web diagnostic = `npx ng test --watch=false --coverage --coverage-reporters=json --coverage-reporters=text-summary --include src/app/features/mail/list/mail-list-page.spec.ts --include src/app/features/mail/list/mail.service.spec.ts` in `web/` (137 tests passed).
- Run start/completion time and exit status: Android started 2026-11-08T20:38:16Z, report written 20:45:17Z, exit 0. Web full started 20:38:42Z (CET 21:38:42), took 198 s, exit nonzero. Web diagnostic report written 20:42:33Z, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 `d6409daf27ed01372c9b8e4f698e8c1ecd27d49f3dec351a6cc4847ea5810f9d`. Web diagnostic `web/coverage/web/coverage-final.json`, sha256 `590af41b37dee5a3814a07914b106aab4d4cc532f75ec4462a1a5c9bcb8c3788`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. Before the run, `report.xml` had mtime 19:54Z. After the run it had 20:45:17Z, later than the run start. The web `coverage-final.json` did not exist after the failed full run and was created by the diagnostic run. No production or test file changed between the runs. The HEAD change during the run (the rebase plus `review.md`) does not touch `web/`, `android/` or `server/`.
- Final run after latest test additions and production fixes: yes for the tests. No production fix has been made yet. TW-1 and TW-2 are open, so coverage is stale once the fixer changes code.

Android (verified; JaCoCo counters, "Statements" = instructions):

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/.../mail/list/MailApi.kt` | 669/1139 (58.7%) | N/A (0/0) | 84/119 (70.6%) | 93/131 (71.0%) |
| `android/.../mail/list/MailRepository.kt` | 1052/1061 (99.2%) | 81/88 (92.0%) | 31/31 (100%) | 114/114 (100%) |
| `android/.../mail/list/MailListViewModel.kt` | 4637/4701 (98.6%) | 382/402 (95.0%) | 116/116 (100%) | 447/447 (100%) |
| `android/.../mail/list/MailScreen.kt` | 8332/8513 (97.9%) | 299/337 (88.7%) | 172/202 (85.1%) | 669/677 (98.8%) |
| **Android aggregate (summed counters, 4 files)** | 14690/15414 (95.3%) | 762/827 (92.1%) | 403/468 (86.1%) | 1323/1369 (96.6%) |

Web (**partial, diagnostic only**: page and service specs, without `mail-list-store.spec.ts`; v8 via istanbul JSON; lines were not reported by this run):

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/.../mail/list/mail-list-store.ts` | 314/361 (87.0%) | 145/202 (71.8%) | 69/80 (86.3%) | N/A |
| `web/.../mail/list/mail.service.ts` | 30/30 (100%) | 33/34 (97.1%) | 16/16 (100%) | N/A |
| `web/.../mail/list/mail-list-page.html` | 246/246 (100%) | 42/42 (100%) | 20/20 (100%) | N/A |

There is no web aggregate, because the full-suite measurement is unavailable. Follow-up: rerun
`cd web && npm run coverage` after TW-1 and TW-2 are fixed.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailListViewModel.kt:242` (1 branch: `current.loadingMore` true) | Guard against load older while more pages load | Not reachable in practice: `canLoadOlder` needs `nextCursor == null`, and `loadMore` needs a cursor. Justified gap. |
| `MailListViewModel.kt:260` (`s.mailbox != folder`) | Answer for a folder that was left | `load()` cancels `loadJob` first, so the update never runs. Behavior covered by `leaving the folder while it loads older drops the answer and the progress`. Defensive code. |
| `MailListViewModel.kt:262` (`result !is Ok` without a failure) | Defensive | Unreachable: `olderFailure` returns non-null for every non-Ok case. Justified gap. |
| `MailRepository.kt:109,115` (`mailbox as? Folder` null, `folder?.…` null branches) | `messagesUpTo` for the unified Inbox | The UI only calls it with a folder. Non-material. |
| `MailApi.kt:151-155` (`MailLoadOlderDto` generated data-class/serializer members) and older uncovered DTO lines | Generated code | Non-material. The DTO decoding is covered by the repository tests. |
| `MailScreen.kt:592-640` (new `LoadOlder` and its call) | AC1/AC2 UI | Fully covered: no missed lines or branches in that range. The remaining misses in `MailScreen.kt` are in older code. |
| `mail-list-store.ts:259-306` (`loadOlder`, `fetchUpTo`) | AC1/AC2 | Uncovered in the diagnostic run only because the store spec was excluded. It is exercised by the 15 store tests in W1. TW-1 is the failing case. |

## Web tests whose failure is expected until the fix

`can load older in the next folder after leaving one while it loaded older` (TW-1) and the two
existing `messages-de.spec.ts` checks (TW-2) are legitimate. They were not skipped or weakened.

## Unable to test

- The web German UI was not rendered in the `de` locale. The project has no locale-switching component tests. German was verified in the XLIFF (the exact targets of the four new ids, with the placeholder kept). `npm run build:de` was not run. No follow-up is needed beyond TW-2.
- No end-to-end run against a real server and mail server (Playwright, emulator). The criteria only ask for unit tests. Not run.
- Web: the `hasMore` field of the response is read by the service but is not used by the UI. A further click after the last batch shows "nothing older". No requirement says otherwise, so no test asserts more.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion.
