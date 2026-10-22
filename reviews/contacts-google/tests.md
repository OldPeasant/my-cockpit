# Test Report

Date: 2026-10-22

Task: contacts-google - Google contacts sync

Scope: `git diff e1aea044cfec34dd7dae7f7c88c819b3598bcab2..49d2e4ae53abf83924f7e91a5c4d244bf6b9f9f1`, excluding `tasks/` and `reviews/`. Task commits as given by the parent: 5d600784, 73915140, a4dadd65, 11309e57, fc249070, 77d6f984 and 9ea4c179, merged in 49d2e4ae. Branch: main. HEAD: 49d2e4ae53abf83924f7e91a5c4d244bf6b9f9f1. The only uncommitted change is the test file listed below. The production files were clean when tests ran.

Requirements:
- `tasks/contacts-google.md`: criterion 1 (incremental sync with sync tokens, writes with etag, WireMock-recorded tests), criterion 2 (the focused Gradle command passes, with Postgres via Testcontainers), and the ASSUMPTION notes. Those notes cover: Google wins on conflicts, an expired token leads to a full listing plus Trash, delete/re-create and tombstones, a field-group push, and Google deriving the display name.
- `reviews/contacts-google/fixes.md` round 2: CR-1 (the display name is derived again after a push) and CR-3 (persons the run created stay through a full listing).
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`.

This report replaces the earlier `tests.md`, which referred to commit hashes from a rewritten history. All results below are for HEAD 49d2e4ae plus the uncommitted test change.

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsSyncTests.kt`
  - Uncommitted, +86 lines, sha256 `b01a171e249638d3ec0f8ccc6862a0a765c18ba18b5586ee32a053f848acae98`.
  - `FixedTokens` got a `beforeCall` hook and a `calls` counter, reset in `@BeforeEach`. The hook runs a local edit between the push's read (GET, call 1) and its write (PATCH, call 2). The ordering is fixed and uses no threads or delays.

The other test files are unchanged. No existing test was weakened, skipped or removed.

## Tests added or updated

Count: 2 added, 0 updated.

The round-2 fixes already had regression tests from the fixer, all of which pass:
- WireMock: `an organization-only contact edited twice in a row never gets its old organization as a name` (CR-1).
- WireMock: `a full listing after an expired token that lags behind does not trash the contact the push just created` (CR-3).
- Mapping: `after a push a derived display name is derived again from the pushed fields`, `after a push a name the user or Google gave stays`, `two pushes in a row of an organization-only contact never send the old organization as a name`.

Coverage showed that the guard in `GoogleContactsSync.markSynced` (line 289: contact trashed or changed after the push read it) was never run by any test. The two new tests run it:

1. `an organization-only contact whose note is edited while its new organization is pushed never gets the old one as a name`:
   - Setup: the organization of c1004 changes to "Other GmbH". While the push is running, the note is edited.
   - First run: sends `organizations` only. The note stays unsynced (`syncedVersion < version`), so the next run should push it.
   - Second run: Google has the new organization and no name. The test expects only `biographies` to be sent, no `names`, and display name "Other GmbH".
   - **FAILS**: see TW-1.
2. `a contact moved to the Trash during the push of its change stays there and is deleted at Google by the next run`:
   - First run: the contact stays in the Trash and keeps its resource name. The name re-derive does not touch it.
   - Next run: sends exactly `DELETE people/c1004:deleteContact` and clears the link. Passes.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: incremental sync with sync tokens (full listing page by page, token kept, incremental with token on every page, expired token by 400/410 leads to a full listing plus Trash) | `a first sync lists every contact page by page and keeps the sync token`, `a later sync applies only the changes since the sync token, ...`, `an incremental listing over several pages ...`, `an expired sync token lists everything again ...`, `410 Gone for the sync token also starts a full listing`, `a full listing after an expired token that fails on a later page trashes nothing`, `an expired token answer to a full listing fails the run ...` | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.google.*' ktlintCheck --continue` | Pass |
| AC1: writes with etag (update with etag, stale etag 400 FAILED_PRECONDITION or 412 means Google wins, 404 trashes the contact, delete, re-create after restore) | `a local change is written to Google with the person's etag ...`, `when the person changes at Google between the read and the write, Google's version wins`, `412 for the etag also means Google's version wins`, `a contact deleted at Google between the read and the write goes to the Trash`, `a contact moved to the Trash is deleted at Google, and created there again when restored`, `a change of several field groups is written in one update that names each group` | same | Pass |
| AC1: WireMock-recorded tests | `GoogleContactsSyncTests` (recorded JSON in `server/src/test/resources/ch/sonensei/cockpit/contacts/google/`), `GoogleContactsClientTests` | same | Pass |
| Round-2 CR-1: a derived display name is derived again after a push | `an organization-only contact edited twice in a row never gets its old organization as a name`, mapping tests `after a push ...` (3) | same | Pass |
| Round-2 CR-1, edge case: a local edit during the push | `an organization-only contact whose note is edited while its new organization is pushed never gets the old one as a name` | same | **Fail** (TW-1) |
| Round-2 CR-1 guard: contact trashed during the push | `a contact moved to the Trash during the push of its change stays there and is deleted at Google by the next run` | same | Pass |
| Round-2 CR-3: a full listing keeps persons the run just created | `a full listing after an expired token that lags behind does not trash the contact the push just created` | same | Pass |
| AC2: focused Gradle command passes (Postgres via Testcontainers) | all 115 tests in `ch.sonensei.cockpit.contacts.google.*` | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.google.*' ktlintCheck --continue` | **Fail**: 115 tests, 1 failed (TW-1); ktlintCheck passes |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.google.*' ktlintCheck --continue` (Docker/Testcontainers available; 06:16 run on the final test snapshot)

Result:

Fail. 115 tests completed, 1 failed: the new TW-1 regression test at `GoogleContactsSyncTests.kt:646`, expected `biographies` but was `names,biographies`. `ktlintCheck` passes; an earlier run caught a test name longer than 140 characters, which I shortened. The other 114 tests pass, including all 113 that existed before.

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Not run. The full server suite takes about 10 minutes, according to `fixes.md`. Its result is already known to be Fail because of the new TW-1 test. The parent should run it after the fix.

## .taskcheck

Result:

Not run. `sh scripts/check.sh` includes `server ./gradlew check`, which fails on TW-1 until the production fix lands. The parent runs it before `done`.

## Bugs found

### A local edit during the push of a derived-name contact leaves the stale derived name, and the next push writes it to Google as the person's name

Finding ID:

`TW-1`

Test:

`GoogleContactsSyncTests.an organization-only contact whose note is edited while its new organization is pushed never gets the old one as a name`

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsSync.kt:287-290` (`markSynced` skips the re-derive when `stored.version != contact.version`), combined with `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GooglePersonMapping.kt:186-195` (`namesChanged`), `:203-210` (`derivedNameAfterPush`) and `:228-235` (`mergeNames`).

Requirement:

Task ASSUMPTION (05:28, review CR-5): the display name of a GOOGLE contact is derived from Google, and a display name the cockpit derived is not pushed as a name. Round-2 CR-1 in `fixes.md`: "Otherwise the old derived name would stay and look like a name the user gave, and a later push would write it to Google as the person's name."

Expected behavior:

The organization of an organization-only contact changes. Its note is also edited between the push's GET and PATCH. The next run pushes the note (`updatePersonFields=biographies`) and sends no `names`, and the display name becomes the new derived one ("Other GmbH").

Actual behavior:

1. Run 1 pushes `organizations`. `markSynced` sees that the contact version changed, so it skips the rename. The contact keeps "Fictional GmbH" as its display name.
2. Run 2 reads the person. Google now has organization "Other GmbH" and no name.
3. `derivedNameAfterPush` returns null, because "Fictional GmbH" no longer equals the name derived from Google.
4. `namesChanged` returns true ("Fictional GmbH" != "Other GmbH"), and `mergeNames` keeps the name because it differs from `derivedName`. The PATCH is therefore sent with `updatePersonFields=names,biographies`.
5. Per the code, the old organization is written as `unstructuredName` "Fictional GmbH", so it becomes a real name at Google. This is the exact outcome CR-1 meant to prevent.

The window is narrow: a user edit between the GET and the PATCH-response transaction of one run. When it is hit, the result is permanent wrong data at Google.

Severity:

Low. It needs a concurrent edit within milliseconds to seconds of the push, but the corruption it causes is silent and persists.

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.` For example: when the re-derive is skipped because the contact changed, keep enough state for the next push to recognize the stale derived name. Another option is to store the derived name in a separate transaction that does not depend on the version. The choice belongs to the fixer.

## Coverage

Status: partial-failed. The data comes from a fresh run of the final test snapshot, but that run had 1 failing test (TW-1). Every test executed, so the counters are complete for that run. Treat them as diagnostic until a run passes after the fix.

Metric: whole-file coverage of task-changed production files. The 5 `contacts/google` files are new in this task, so for them whole-file coverage equals changed-line coverage.
Changed-line coverage: measured separately for `ContactService.kt`, the only modified pre-existing file. I took the JaCoCo line entries in its added range (lines 96-113, `createSynced`): 7/7 executable lines covered, 5/6 branches covered. The missed branch is at line 108, `fields.photoFileId != null`: no synced contact has a photo.

Evidence:
- Task id, base, measured HEAD and file-scope source:
  - Task `contacts-google`. Base `e1aea044cfec34dd7dae7f7c88c819b3598bcab2` and the task commit list come from the parent.
  - Measured HEAD: `49d2e4ae53abf83924f7e91a5c4d244bf6b9f9f1`.
  - File scope: `git diff --name-only e1aea044..HEAD -- server/src/main`.
- Source/test snapshot:
  - Production files at HEAD, unmodified. Git blobs: `GoogleContactsSync.kt` 2ae2d6ed, `GooglePersonMapping.kt` 6f0427fc, `GoogleContactsClient.kt` 21a9ad41, `GoogleContactsState.kt` 9d0bfc27, `GoogleContactsSyncScheduler.kt` 82b90ef1, `ContactService.kt` 7e6f323e.
  - Uncommitted `GoogleContactsSyncTests.kt`: sha256 `b01a171e...acae98`. It was the same before and after the run.
  - The other test files are at their committed blobs.
- Included files: the 5 `contacts/google` production files, plus `ContactService.kt` (added `createSynced`).
  - Excluded: `server/src/main/resources/db/migration/contacts/V202610221100__contacts_google_sync.sql`. It is SQL, which JaCoCo cannot instrument. Flyway applies it in every Testcontainers test, so it is exercised indirectly.
  - Excluded: `server/src/test/resources/config/application.properties` (test configuration).
  - No renamed or deleted files.
- Provider, command and selection:
  - JaCoCo through the Gradle `jacoco` plugin (`server/build.gradle.kts`; the plugin's default version, since the build does not pin one).
  - Working directory: `server/`.
  - Commands: `./gradlew test --tests 'ch.sonensei.cockpit.contacts.google.*' ktlintCheck --continue`, then `./gradlew jacocoTestReport -x test`. The report task depends on `test` and does not run after a failed test task, so it was run separately on the fresh exec data.
  - Selection: all 115 tests in `ch.sonensei.cockpit.contacts.google.*`.
- Run times and exit status:
  - Test run: started 06:15:55 and completed about 06:16:40 (+02:00). Exit 1 (1 test failed; ktlint passed).
  - Report run: completed 06:16:51. Exit 0.
- Reports:
  - `server/build/jacoco/test.exec`: written 06:16:40.5, sha256 `b76166e67b8f42ae7ba229e303feafe38288ecdb7e46890f59c39b73caace77f`.
  - `server/build/reports/jacoco/test/jacocoTestReport.xml`: written 06:16:50.9, sha256 `3629fc9761790a83cf19ee844a32bc4c46d03750785b2dcf0357b218122d5209`.
- Freshness: the test task re-executed because its test inputs had changed. `test.exec` was rewritten by this run. The test file hash was the same before and after the run, and the production files were unchanged.
- Final run after the latest test additions: yes. Not yet after a production fix, because TW-1 is still open. After the fixer changes code, this coverage is stale and must be measured again.

Values are covered/total (percentage). The scheduler and state files have no branches, so their branch metric is N/A.

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsSync.kt` | 1589/1641 (96.8%) | 163/179 (91.1%) | 51/51 (100%) | 252/252 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GooglePersonMapping.kt` | 2153/2228 (96.6%) | 247/280 (88.2%) | 39/39 (100%) | 242/244 (99.2%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsClient.kt` | 524/525 (99.8%) | 37/38 (97.4%) | 18/18 (100%) | 75/75 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsState.kt` | 109/109 (100%) | N/A | 23/23 (100%) | 24/24 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsSyncScheduler.kt` | 25/33 (75.8%) | N/A | 2/4 (50.0%) | 8/10 (80.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/contacts/domain/ContactService.kt` (whole file, mostly pre-existing code; google tests only) | 961/1518 (63.3%) | 58/112 (51.8%) | 31/41 (75.6%) | 114/157 (72.6%) |

Aggregate for the 5 `contacts/google` files (summed counters):
- Instructions: 4400/4536 (97.0%)
- Branches: 447/497 (89.9%)
- Methods: 133/135 (98.5%)
- Lines: 601/605 (99.3%)

The aggregate excludes `ContactService.kt` (reported per file above, plus its changed-line figure) and the SQL migration (not instrumentable). No scoped file lacked report data.

Remaining uncovered locations:

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `GoogleContactsSync.kt:289` (contact changed or trashed after the push read it) | CR-1 re-derive guard | Covered now by the 2 new tests. The tests found TW-1. |
| `GoogleContactsSync.kt:161,162,211,301` | `checkNotNull` null branches | Not reachable: `push` only builds these writes with a contact and resource name. No test added. |
| `GoogleContactsSync.kt:190,284,288,306` | `links.findById` / `contacts.findById` return null (link or contact removed by a concurrent disconnect or purge during an HTTP call) | Defensive branches. Reaching them needs a disconnect during a call; the new `beforeCall` hook could do it if wanted. Low risk: each one just returns. Left uncovered. |
| `GoogleContactsSync.kt:302` | purged contact whose link has no resource name | Not reachable: `DELETE_PURGED` requires a resource name. |
| `GoogleContactsSync.kt:335` | 400 without `FAILED_PRECONDITION` on update | Covered in behavior by `a write Google refuses is skipped and reported` (403/other). Only the 400-with-another-reason combination is missing. Low risk. |
| `GoogleContactsSync.kt:442` | a backup-restored contact of another connection with the same resource name | Cross-connection collision, which needs two Google connections. The test token provider supports one. Left uncovered, not in the criteria. |
| `GoogleContactsSync.kt:452,453` | person without an etag on a non-forced pull; link to a contact row that is gone | Defensive. Google always sends etags. |
| `GoogleContactsSync.kt:480` | `removedAtGoogle` for a contact already deleted or missing | Defensive branches. |
| `GooglePersonMapping.kt` (27 partially covered lines, e.g. 268-274, 329-341, 352-381, 434) | Null/blank fallbacks in entry merging, address and birthday parsing | Mostly elvis/`?.` branches for malformed Google entries. The mapping behavior in the criteria (merge, keep unmodeled parts, cut and limits) is tested by the 40 `GooglePersonMappingTests`. No meaningful gap. |
| `GoogleContactsClient.kt:52` | one branch of URL building | Trivial. |
| `GoogleContactsSyncScheduler.kt:14,19` | the scheduling configuration bean (scheduler enabled) | The test configuration turns the scheduler off on purpose (`cockpit.contacts.google.sync.scheduler.enabled=false`). Bootstrap code. |
| `ContactService.kt:108` | `createSynced` with a photo | The sync never passes a photo (photos are not synced, per the ASSUMPTION). |

## Unable to test

- The real Google People API is not called; it is reproduced by WireMock with recorded responses, as criterion 1 requires. No follow-up.
- The scheduler running on its interval in production configuration: off in tests by design. No follow-up for this task.
- A cross-connection resource-name collision (two Google accounts): the test token provider models one connection. Optional follow-up.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion.

TW-1 (Low) breaks criterion 2 until it is fixed: the focused command now fails 1 of 115 tests. After the fix, re-run the focused command, `jacocoTestReport` and this test-writer.
