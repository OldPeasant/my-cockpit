# Test Report

Date: 2026-10-22

Task: contacts-google - Google contacts sync

Scope: `git diff e49088af..704299330c36de0e474cf435aa25a9ae4762074e`, excluding `tasks/` and `reviews/`. Base `e49088af` (`git merge-base HEAD origin/main`) and task commits `dc2e4c63..70429933` (9 commits: dc2e4c63, 741ada94, 7b9940d7, bcec2ac8, 9d118bf2, 73b54f65, b0f820e3, 82963afd, 70429933) come from the parent. Branch: main. HEAD: `704299330c36de0e474cf435aa25a9ae4762074e`. History was rebased onto the latest main since round 3, so the hashes differ from the earlier report. Production files were clean at HEAD during the run. Uncommitted inputs: the test file listed below (mine), plus `reviews/contacts-google/review.md` (modified by another helper, not by me; not a test input).

Requirements:
- `tasks/contacts-google.md`: criterion 1 (incremental sync with sync tokens, writes with etag, WireMock-recorded tests), criterion 2 (the focused Gradle command passes, with Postgres via Testcontainers), and the ASSUMPTION notes. One of them (CR-5): the display name of a GOOGLE contact is derived from Google, and a display name the cockpit derived is not pushed as a name.
- `reviews/contacts-google/fixes.md` round 3: the TW-1 fix in `GoogleContactsSync.markSynced`.
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`.

This is round 4. It replaces the round-3 report, which was for HEAD 49d2e4ae (before the rebase) and had TW-1 failing.

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsSyncTests.kt`
  - Uncommitted. sha256 `f8048e9f702942bb518aa62a9b60e3163b4dbe9c9f57e52fa44d7acd779f11f9`, the same before and after the run.
  - Added: an `import tools.jackson.databind.JsonNode`, a helper `nextPushedName()`, and 3 tests. The tests use the existing `companyWithNewOrganization()` and `duringWrite { }` hooks, which give a fixed order (an edit between the push's GET and its PATCH) without threads or delays.

No existing test was weakened, skipped or removed.

## Tests added or updated

Count: 3 added, 0 updated.

TW-1 fix check: the round-3 regression test `an organization-only contact whose note is edited while its new organization is pushed never gets the old one as a name` now passes. So does the guard test `a contact moved to the Trash during the push of its change stays there and is deleted at Google by the next run`.

The fix added a new condition: when the contact changed during the push, it gets the new derived name only if its display name is still the pushed one and it has no given or family name. Before this round, no test covered the case where the condition is false because the user renamed the contact during the push. Without such a test, a fix that always re-derives the name would also pass, and it would overwrite the user's name. The 3 new tests each make one part of the condition false:

1. `a display name the user gives while the new organization is pushed is kept and written to Google as the name`
   - During the push, the display name becomes "Fictional Holding AG".
   - Run 1 sends only `organizations`. The name stays "Fictional Holding AG", and `syncedVersion < version`.
   - Run 2 sends `names` with `unstructuredName` "Fictional Holding AG".
2. `a given name the user adds while the new organization is pushed keeps the display name and is written to Google`
   - During the push, the given name becomes "Erika".
   - Run 1 keeps the display name, and the change is not yet synced.
   - Run 2 sends `names` with `givenName` "Erika" and no `unstructuredName`.
3. `a family name the user adds while the new organization is pushed keeps the display name and is written to Google`
   - The same as test 2, with the family name "Muster".

All 3 pass.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: incremental sync with sync tokens (full listing page by page, token kept, incremental with token on every page, expired token by 400/410 leads to a full listing plus Trash) | `a first sync lists every contact page by page and keeps the sync token`, `a later sync applies only the changes since the sync token, ...`, `an incremental listing over several pages ...`, `an expired sync token lists everything again ...`, `410 Gone for the sync token also starts a full listing`, `a full listing after an expired token that fails on a later page trashes nothing`, `an expired token answer to a full listing fails the run ...` | `server/gradlew -p server test --tests 'ch.sonensei.cockpit.contacts.google.*' ktlintCheck --continue` | Pass |
| AC1: writes with etag (update with etag; a stale etag, 400 FAILED_PRECONDITION or 412, means Google wins; 404 trashes the contact; delete; re-create after restore) | `a local change is written to Google with the person's etag ...`, `when the person changes at Google between the read and the write, Google's version wins`, `412 for the etag also means Google's version wins`, `a contact deleted at Google between the read and the write goes to the Trash`, `a contact moved to the Trash is deleted at Google, and created there again when restored`, `a change of several field groups is written in one update that names each group` | same | Pass |
| AC1: WireMock-recorded tests | `GoogleContactsSyncTests` (recorded JSON in `server/src/test/resources/ch/sonensei/cockpit/contacts/google/`), `GoogleContactsClientTests` | same | Pass |
| CR-1: a derived display name is derived again after a push | `an organization-only contact edited twice in a row never gets its old organization as a name`, mapping tests `after a push ...` (3) | same | Pass |
| TW-1 (fixed): a local edit during the push still gets the new derived name | `an organization-only contact whose note is edited while its new organization is pushed never gets the old one as a name` | same | Pass (failed before the fix) |
| TW-1 fix guard: a name the user gives during the push is kept (display, given or family name) | the 3 new tests above | same | Pass |
| CR-1 guard: a contact trashed during the push | `a contact moved to the Trash during the push of its change stays there and is deleted at Google by the next run` | same | Pass |
| CR-3: a full listing keeps persons the run just created | `a full listing after an expired token that lags behind does not trash the contact the push just created` | same | Pass |
| AC2: the focused Gradle command passes (Postgres via Testcontainers) | all 118 tests in `ch.sonensei.cockpit.contacts.google.*` | `server/gradlew -p server test --tests 'ch.sonensei.cockpit.contacts.google.*' ktlintCheck --continue` | Pass: 118 tests, 0 failures, 0 skipped; ktlintCheck passes |

## Focused test execution

Command:

`server/gradlew -p server test --tests 'ch.sonensei.cockpit.contacts.google.*' ktlintCheck --continue`

This is the same as `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.google.*' ktlintCheck --continue`. Docker/Testcontainers was available. The run started at 06:22:50 +02:00 on the final test snapshot.

Result:

Pass. BUILD SUCCESSFUL in 31s.
- The result XMLs show 118 tests (40 + 6 + 16 + 56), 0 failures, 0 errors, 0 skipped.
- `runKtlintCheckOverTestSourceSet` ran on the changed test file and passed.

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Not run. The full server suite takes about 10 minutes, and the parent asked for the focused command. The parent runs it, or `.taskcheck`, before `done`.

## .taskcheck

Result:

Not run. The parent runs `sh scripts/check.sh` before `done`.

## Bugs found

None in this round. TW-1 from round 3 is fixed at HEAD 70429933: its regression test passes, and the 3 new tests show that the fix does not overwrite a name the user gives during the push.

## Coverage

Status: verified. The data comes from a fresh JaCoCo run on HEAD 70429933 plus the final test snapshot, and all tests passed.

Metric: whole-file coverage of task-changed production files. The 5 `contacts/google` files are new in this task, so for them whole-file coverage equals changed-line coverage.

Changed-line coverage:
- TW-1 fix (commit 70429933, `GoogleContactsSync.kt:291-295`): 5/5 executable lines and 10/10 branches covered, taken from the JaCoCo per-line entries. Line 294, the new condition, has 6/6 branches. Before this round, the three "user renamed it" branches there were not covered.
- `ContactService.kt`, the only pre-existing file the task modified (`createSynced`, added lines 96-113): 7/7 lines, 5/6 branches. Line 108 `photoFileId != null` is missed, because photos are not synced.

Evidence:
- Task id, base, measured HEAD and file-scope source:
  - Task `contacts-google`. Base `e49088af`, measured HEAD `704299330c36de0e474cf435aa25a9ae4762074e`.
  - The task commits come from the parent. File scope: `git diff --name-only e49088af..HEAD -- server/src/main`.
- Source/test snapshot:
  - Production files are unmodified at HEAD. Git blobs: `GoogleContactsSync.kt` 984d92d3, `GooglePersonMapping.kt` 6f0427fc, `GoogleContactsClient.kt` 21a9ad41, `GoogleContactsState.kt` 9d0bfc27, `GoogleContactsSyncScheduler.kt` 82b90ef1, `ContactService.kt` 7e6f323e.
  - The uncommitted `GoogleContactsSyncTests.kt` has sha256 `f8048e9f702942bb518aa62a9b60e3163b4dbe9c9f57e52fa44d7acd779f11f9`. The other test files are at their committed blobs.
- Included files: the 5 `contacts/google` production files, plus `ContactService.kt`.
- Excluded files:
  - `server/src/main/resources/db/migration/contacts/V202610221100__contacts_google_sync.sql`. JaCoCo cannot instrument SQL. Flyway applies it in every Testcontainers test.
  - `server/src/test/resources/config/application.properties` (test configuration).
  - No renamed or deleted files.
- Provider, command and selection:
  - JaCoCo through the Gradle `jacoco` plugin (`server/build.gradle.kts`; the plugin's default version, since none is pinned). Working directory: the repository root, with `-p server`.
  - Commands: the focused command above, then `server/gradlew -p server jacocoTestReport -x test`.
  - Selection: all 118 tests in `ch.sonensei.cockpit.contacts.google.*`.
- Run times and exit status:
  - Test run: 06:22:50 to about 06:23:23 +02:00. Exit 0.
  - Report run: completed at about 06:23:40. Exit 0.
- Reports:
  - `server/build/jacoco/test.exec`: written 06:23:23.46, sha256 `d8b5ad038df65fda8e9d33b88a320946270caffe23ac0a13e8a5f601d03166f3`.
  - `server/build/reports/jacoco/test/jacocoTestReport.xml`: sha256 `bc24c547f1dd32bc9b64701bb2bcfeb31cb677e9fa53f5adbf9babeb9a3da90e`, JaCoCo session `dell26-d3f65a57`.
- Freshness:
  - The test task re-ran because the test sources had changed (`compileTestKotlin` executed), and `test.exec` was rewritten by this run.
  - The test file hash and the production blobs were the same before and after the run.
- Final run after the latest test additions and production fixes: yes.

Values are covered/total (percentage). The scheduler and state files have no branches, so their branch metric is N/A.

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsSync.kt` | 1630/1682 (96.9%) | 169/185 (91.4%) | 52/52 (100%) | 255/255 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GooglePersonMapping.kt` | 2153/2228 (96.6%) | 250/280 (89.3%) | 39/39 (100%) | 242/244 (99.2%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsClient.kt` | 524/525 (99.8%) | 37/38 (97.4%) | 18/18 (100%) | 75/75 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsState.kt` | 109/109 (100%) | N/A | 23/23 (100%) | 24/24 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsSyncScheduler.kt` | 25/33 (75.8%) | N/A | 2/4 (50.0%) | 8/10 (80.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/contacts/domain/ContactService.kt` (whole file, mostly pre-existing code; google tests only) | 961/1518 (63.3%) | 58/112 (51.8%) | 31/41 (75.6%) | 114/157 (72.6%) |

Aggregate for the 5 `contacts/google` files (summed counters):
- Instructions: 4441/4577 (97.0%)
- Branches: 456/503 (90.7%)
- Methods: 134/136 (98.5%)
- Lines: 604/608 (99.3%)

The aggregate excludes `ContactService.kt` (reported per file above, plus its changed-line figure) and the SQL migration (not instrumentable). No scoped file was missing from the report.

The line numbers below are for HEAD 70429933. The fix shifted `GoogleContactsSync.kt` by +6 from line 290 on.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `GoogleContactsSync.kt:291-295` (TW-1 fix) | The display name after a push that overlapped a local edit | Fully covered: 10/10 branches. The 3 new tests cover the "user renamed it" branches. |
| `GoogleContactsSync.kt:161,162,211,307` | `checkNotNull` null branches | Not reachable: `push` only builds these writes with a contact and a resource name. |
| `GoogleContactsSync.kt:190,286,290,312,459` | `links.findById` / `contacts.findById` return null (link or contact removed by a concurrent disconnect or purge during an HTTP call) | Defensive; each one just returns. Low risk. Left uncovered. |
| `GoogleContactsSync.kt:308` | A stored resource name that is not "people/" plus an id, on the delete path | The same check on the update path is tested (`a stored resource name that is not people slash id fails only that contact, not the run`). Low risk. Left uncovered. |
| `GoogleContactsSync.kt:341` | 400 without `FAILED_PRECONDITION` on update | The refusal behavior is tested by `a write Google refuses is skipped and reported` (other statuses). Only the 400-with-another-reason combination is missing. Low risk. |
| `GoogleContactsSync.kt:448` | A backup-restored contact of another connection with the same resource name | Needs two Google connections; the test token provider models one. Not in the criteria. |
| `GoogleContactsSync.kt:458` | A person without an etag on a non-forced pull | Defensive: Google always sends etags. |
| `GoogleContactsSync.kt:486` | `removedAtGoogle` for a contact already deleted or missing | Defensive branches. |
| `GooglePersonMapping.kt` (25 partially covered lines, e.g. 268-274, 329-341, 352-381, 434) | Null/blank fallbacks in entry merging, address and birthday parsing | Mostly elvis/`?.` branches for malformed Google entries. The mapping behavior in the criteria is tested by the 40 `GooglePersonMappingTests`. No meaningful gap. |
| `GoogleContactsClient.kt:52` | One branch of URL building | Trivial. |
| `GoogleContactsSyncScheduler.kt:14,19` | The scheduling configuration bean (scheduler enabled) | The test configuration turns the scheduler off on purpose (`cockpit.contacts.google.sync.scheduler.enabled=false`). Bootstrap code. |
| `ContactService.kt:108` | `createSynced` with a photo | The sync never passes a photo (photos are not synced, per the ASSUMPTION). |

## Unable to test

- The real Google People API is not called. WireMock reproduces it with recorded responses, as criterion 1 requires. No follow-up.
- The scheduler running on its interval in the production configuration. It is off in tests by design. No follow-up for this task.
- A cross-connection resource-name collision (two Google accounts). The test token provider models one connection. Optional follow-up.

## Summary

Tests added and all validation passed.

The TW-1 fix is covered, including the branches where the user renames the contact during the push (3 new tests). The focused command passes: 118 tests and ktlintCheck. Every line and branch of the fix is covered. The full `./gradlew check` and `.taskcheck` are left to the parent.
