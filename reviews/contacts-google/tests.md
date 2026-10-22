# Test Report

Date: 2026-10-22

Task: contacts-google - Google contacts sync

Scope: `git diff f558cf46a1923daf70c0c7e5d2d44ee774dc6761..dff44523b6c544b4e0d95580bf27faf038cebfd4` (task commits 2dd849d18, 369c64b4e, 0a8cd16a9, 2661e7ba2, dff44523b, as given by the parent), plus the uncommitted test additions listed below. Branch: main. HEAD: dff44523b6c544b4e0d95580bf27faf038cebfd4.

Requirements:
- `tasks/contacts-google.md`: acceptance criteria 1 (incremental sync with sync tokens, writes with etag, WireMock-recorded tests) and 2 (focused Gradle test command passes, Postgres via Testcontainers), and the recorded assumptions in its Notes (Google wins on conflicts, expired token -> full listing + Trash, deletes and tombstones, field-group push, disconnect).
- `reviews/contacts-google/review.md` and `reviews/contacts-google/fixes.md` (the behavior that changed after the review).
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`.

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsSyncTests.kt` (uncommitted; sha256 `a4fd84c26ea7d374fec2889a280acb7d7ef02ac62efe55c459d2da4278fcdf19`)

I reviewed the existing tests (commit 0a8cd16a9 and the fixer's changes) against the final code. They match the current contract: GET before PATCH, only changed field groups, tombstones, and resource-name checks. None needed changes. No existing test was changed, weakened, skipped or removed.

## Tests added or updated

Count: 6 added, 0 updated. All are in `GoogleContactsSyncTests` and run against WireMock and Postgres via Testcontainers. They use fictitious data.

1. `an incremental listing over several pages sends the sync token with every page and trashes nothing it did not list`: both pages carry `syncToken` and `requestSyncToken=true`. The last page's `nextSyncToken` is stored. Contacts the incremental listing does not mention stay active, because `removeMissing` runs only for full listings.
2. `a full listing after an expired token that fails on a later page trashes nothing`: an expired token, then page 2 of the full listing answers 503. The run fails with a safe message, and Sam (on the failed page) is not moved to the Trash.
3. `a full listing after an expired token leaves a restored contact that waits for its create alone`: a restored contact whose `createContact` failed (link without resource name) is not trashed by the full listing. The new token is stored.
4. `a change of several field groups is written in one update that names each group`: `updatePersonFields=emailAddresses,phoneNumbers`, `personFields` is sent, and the etag is sent. The body has only those groups. Google's unchanged and unreadable email entries are kept. The changed phone drops `canonicalForm` and keeps its type. The link etag and synced version are updated.
5. `a rate limit on a write fails the run before the pull, keeps the sync token, and the change is pushed later`: PATCH 429 sets the rate-limit error. No listing is requested, and the token and synced version are unchanged. The next run pushes the change.
6. `a purged contact whose delete Google refuses is forgotten once Google lists it as deleted, no delete is sent again`: the pull's `removedAtGoogle` path for a tombstone. The tombstone is removed, and the next run sends no DELETE.

## Requirement coverage

Command for every row below: `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.google.*'`. **I could not run it**: this sub-agent is denied every `./gradlew` invocation (permission policy; `server/gradlew -p server --version` was refused). So the Result column is "Not run" for this round. The fixer's report (`fixes.md`) records 102 passing tests on the code at HEAD, without the 6 tests added here.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: first sync lists everything page by page, keeps the sync token | `a first sync lists every contact page by page and keeps the sync token` | focused command above | Not run: Gradle denied to sub-agent |
| AC1: incremental sync with the sync token | `a later sync applies only the changes since the sync token, deleted persons go to the Trash`; `a person listed with the etag already stored is not changed again`; new #1 | focused command above | Not run: Gradle denied to sub-agent |
| AC1 / assumption: expired token (400 EXPIRED_SYNC_TOKEN or 410) -> full listing, missing contacts to the Trash | `an expired sync token lists everything again and trashes the contacts Google no longer has`; `410 Gone for the sync token also starts a full listing`; `an expired token answer to a full listing fails the run instead of listing again and again`; new #2, #3 | focused command above | Not run: Gradle denied to sub-agent |
| AC1: writes carry the etag; only changed field groups are pushed | `a local change is written to Google with the person's etag and not again on the next run`; `editing only the note of a phone-only contact ...`; `editing the note of an organization-only contact ...`; `a local change Google has no field for is marked synced without a write`; new #4; `GooglePersonMappingTests` toUpdate tests (11) | focused command above | Not run: Gradle denied to sub-agent |
| Assumption: conflicts, Google wins (newer etag on read, 400 FAILED_PRECONDITION, 412) | `when the person changed at Google since the last sync ...`; `when the person changes at Google between the read and the write ...`; `412 for the etag also means Google's version wins` | focused command above | Not run: Gradle denied to sub-agent |
| Assumption: 404 on read/update trashes the local contact | `a contact deleted at Google before the push ...`; `... between the read and the write ...`; `a person deleted at Google after a stale etag goes to the Trash` | focused command above | Not run: Gradle denied to sub-agent |
| Assumption: deletes, restore re-creates, tombstones after purge | `a contact moved to the Trash is deleted at Google, and created there again when restored`; `a contact purged from the Trash before the push is still deleted at Google`; `a purged contact whose delete Google refuses is not imported again, and the delete is retried`; `a contact purged after its person was deleted at Google leaves no link behind`; `a delete Google refuses is retried ...`; new #6 | focused command above | Not run: Gradle denied to sub-agent |
| Error handling: refused writes, rate limits, 5xx, token failures, invalid resource names | `a write Google refuses ...`; `a rate limit fails the run ...`; new #5; `a 5xx answer of the listing ...`; `a listing Google refuses ...`; resource-name tests (3); `GoogleContactsClientTests` (16) | focused command above | Not run: Gradle denied to sub-agent |
| Adoption of backup-restored contacts, restored-at-Google person | `a contact restored from a backup is adopted ...`; `... that is in the Trash gives up its id ...`; `a person restored at Google after it was deleted there comes back`; `a person that cannot be stored is skipped and reported ...` | focused command above | Not run: Gradle denied to sub-agent |
| Assumption: which connections are synced, scheduler | `GoogleContactsSyncUnitTests` (6); `the scheduler is off in the test configuration ...` | focused command above | Not run: Gradle denied to sub-agent |
| Assumption: disconnect forgets state, contacts stay | `disconnecting the Google account forgets its sync state, the contacts stay`; `disconnecting a Microsoft connection leaves the Google sync state alone` | focused command above | Not run: Gradle denied to sub-agent |
| `ContactService.createSynced` | `createSynced refuses the LOCAL source and keeps the external id of a provider contact` | focused command above | Not run: Gradle denied to sub-agent |
| AC2: the focused Gradle command passes with Postgres via Testcontainers | whole package, 108 tests (102 existing + 6 new) | focused command above | Not run: Gradle denied to sub-agent; the parent must run it |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.google.*'`

Result:

Not run. This sub-agent is denied `./gradlew`, so the parent worker has to run it. Expected: 108 tests (`GoogleContactsSyncTests` 49, `GooglePersonMappingTests` 37, `GoogleContactsClientTests` 16, `GoogleContactsSyncUnitTests` 6). I checked the new tests statically against the production code paths. Their names, imports and line lengths follow the file's existing ktlint-clean style, but they have not been compiled.

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Not run (Gradle denied to sub-agent).

## .taskcheck

Result:

Not run (`sh scripts/check.sh` runs Gradle, which this sub-agent is denied).

## Bugs found

None. Reading the final code, I found no behavior that violates the acceptance criteria or the recorded assumptions. This is not confirmed by execution until the parent runs the new tests. If one of the 6 new tests fails, check whether the test or the production code is wrong before changing either.

## Coverage

Status: unavailable. The configured provider is JaCoCo via `cd server && ./gradlew test jacocoTestReport` (`docs/ARCHITECTURE.md` "Coverage"). This sub-agent is denied every `./gradlew` invocation, so no fresh coverage run exists for this snapshot. I read no existing report: any `server/build/reports/jacoco` output would describe an earlier state, so it is not substituted here.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no fresh JaCoCo XML).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `contacts-google`; base `f558cf46a1923daf70c0c7e5d2d44ee774dc6761`; HEAD `dff44523b6c544b4e0d95580bf27faf038cebfd4`; commits 2dd849d18, 369c64b4e, 0a8cd16a9, 2661e7ba2, dff44523b and the scope as given by the parent, cross-checked with `git diff --name-only f558cf46a..HEAD -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD dff44523b plus the uncommitted `GoogleContactsSyncTests.kt` (sha256 `a4fd84c26ea7d374fec2889a280acb7d7ef02ac62efe55c459d2da4278fcdf19`). No other uncommitted or untracked files.
- Exact included files:
  - `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsClient.kt`
  - `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsState.kt`
  - `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsSync.kt`
  - `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GoogleContactsSyncScheduler.kt`
  - `server/src/main/kotlin/ch/sonensei/cockpit/contacts/google/GooglePersonMapping.kt`
  - `server/src/main/kotlin/ch/sonensei/cockpit/contacts/domain/ContactService.kt` (materially modified: `createSynced`; whole-file numbers would include pre-existing code)

  Excluded: `server/src/main/resources/db/migration/contacts/V202610221100__contacts_google_sync.sql` (SQL, not instrumented by JaCoCo; it is applied by Flyway in every Testcontainers test). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle `jacoco` plugin); `./gradlew test --tests 'ch.sonensei.cockpit.contacts.google.*' jacocoTestReport` in `server/`. Not executed (permission denied).
- Run start/completion time and exit status: no run.
- Generated report paths and report hashes/run identifier: none. The expected path is `server/build/reports/jacoco/test/jacocoTestReport.xml`.
- Evidence outputs are fresh and inputs stayed unchanged: not applicable (no run).
- Final run after latest test additions and production fixes: no. Gradle is denied to this sub-agent.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `contacts/google/GoogleContactsClient.kt` | not measured | not measured | not measured | not measured |
| `contacts/google/GoogleContactsState.kt` | not measured | not measured | not measured | not measured |
| `contacts/google/GoogleContactsSync.kt` | not measured | not measured | not measured | not measured |
| `contacts/google/GoogleContactsSyncScheduler.kt` | not measured | not measured | not measured | not measured |
| `contacts/google/GooglePersonMapping.kt` | not measured | not measured | not measured | not measured |
| `contacts/domain/ContactService.kt` | not measured | not measured | not measured | not measured |

No aggregate: there are no measured counters. All 6 files are unmeasured, and the migration is excluded as stated above.

I compared each branch of the production code with the tests by reading them. This is not instrumented coverage:

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `GoogleContactsSync.kt` `pull()`: `pageToken` loop with a `syncToken` set | AC1: incremental sync over several pages | New test #1 |
| `GoogleContactsSync.kt` `pull()`: exception from a later page after an expired token (`removeMissing` not reached) | Expired token -> full listing must not trash on an incomplete listing | New test #2 |
| `GoogleContactsSync.kt` `removeMissing()`: links without `resourceName` | A pending create is not trashed by a full listing | New test #3 |
| `GoogleContactsSync.kt` `update()`: several groups in `updatePersonFields` | AC1: field-group writes with the etag | New test #4 |
| `GoogleContactsSync.kt` `sync()`: `GoogleContactsException` raised during the push | A run-level failure keeps the sync token and skips the pull | New test #5 |
| `GoogleContactsSync.kt` `removedAtGoogle()`: tombstone branch reached from the pull | Tombstones are removed when Google reports the person deleted | New test #6 |
| `GoogleContactsSync.kt` `push()`: link with neither contact nor resource name (`links.delete`) | Clean-up after a purge | Already covered by `a contact purged after its person was deleted at Google leaves no link behind` |
| `GoogleContactsSync.kt` `sync()`: lock already held | No overlapping runs | Already covered by `a run already going on for the connection is not started a second time` |
| `GoogleContactsSync.kt` `create()`: a create response without `etag` (the link stores a null etag; the next push's read then always differs, so Google wins) | Edge case: Google always returns an etag when `personFields` are requested | Left uncovered: not realistic for the People API, and no requirement covers it. Mentioned for the reviewer |
| `GoogleContactsSyncScheduler.kt`: `GoogleContactsSyncSchedulingConfiguration` bean when the property is missing or true | Default scheduling | Not covered by a Spring context test (tests turn the scheduler off). `run()` is covered by `GoogleContactsSyncUnitTests`. Low risk: plain `@Scheduled` configuration |

Follow-up for the parent: run `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.contacts.google.*' jacocoTestReport`, then read the `<sourcefile>` counters (`INSTRUCTION`, `BRANCH`, `LINE`, `METHOD`) for the files above. These live under package `ch/sonensei/cockpit/contacts/google`, plus `ContactService.kt` in `ch/sonensei/cockpit/contacts/domain`. Then send the results to a test-writer run so they can be recorded here. These numbers must not be taken from an earlier run.

## Unable to test

- Running any test, the full suite, `.taskcheck` and the coverage measurement: Gradle is denied to this sub-agent by the permission policy, not by a transient error. Follow-up: the parent runs them (commands above).
- Against the real Google People API: not possible here, and not required. Criterion 1 asks for WireMock-recorded tests, which the recordings in `server/src/test/resources/ch/sonensei/cockpit/contacts/google/` provide.

## Summary

Meaningful task behavior remains untested and requires follow-up. I added 6 tests for gaps in the sync-token paging, the expired-token full listing, multi-group etag writes, rate limits on writes, and tombstone removal. Reading the code, I found no production defects. Neither the 6 new tests nor the existing 102 have been run on this snapshot, and coverage was not measured, because this sub-agent may not run Gradle. The parent must run the focused command and `jacocoTestReport` before this evidence counts.
