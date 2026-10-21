# Test Report

Date: 2026-10-22

Task: contacts-aggregate - Contact detail aggregation and autocomplete

Scope: base `da5958f0ce6f09dad2e879aef4ad5486007fc7b1` (merge-base with origin/main) .. final code `25c7b0fee8131e77147fed585c274cc53238d9e5` on `main`. Task commits: 1d8854446 (implementation), 036b5fb77 (review fixes), 25c7b0fee (this test-writer's recurring/all-day test). They are the rebased versions of 169df4179 / fe802ca84 / 314a570ad (original base 03f7b79b); `git diff 314a570ad 25c7b0fee -- . ':!tasks' ':!reviews'` is empty, so the code is the same. `git diff --stat da5958f0..HEAD` shows only this task's files: the four production files in `server/src/main/kotlin/ch/sonensei/cockpit/contacts/aggregate/`, three test files in the same test package, generated `api/openapi.yaml` and `web/src/app/api/generated/**`. Uncommitted: only `reviews/contacts-aggregate/`.

Requirements:
- `tasks/contacts-aggregate.md` acceptance criteria and its recorded assumptions (mail/event limits, exclusions, autocomplete ranking, blank query)
- SPEC.md §8.3 (contact detail: everything about a person), §4.2 (Problem Details errors)
- AGENTS.md / docs/ARCHITECTURE.md test and coverage conventions
- `reviews/contacts-aggregate/review.md` and `fixes.md` (behaviors fixed in 036b5fb77, formerly fe802ca84)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/aggregate/ContactAggregateEdgeCaseTests.kt` (one test added by this test-writer, committed in 25c7b0fee)

Other task tests reviewed and left unchanged: `ContactAggregateWebTests.kt` (5), `CorrespondenceQueryTest.kt` (4). This pass added no tests: the fresh coverage shows no remaining meaningful gap (see "Coverage").

## Tests added or updated

Count: 1

- `related lists each occurrence of recurring timed and all-day series with the contact, by start` (ContactAggregateEdgeCaseTests): sets up a weekly timed series (COUNT=3, Europe/Zurich) and a daily all-day series (COUNT=2, attendee address in a different case), both with the contact as an attendee. It checks that every occurrence is listed and sorted by start, with the all-day days placed correctly between the timed ones. It also checks per kind that `seriesId` is set, that `occurrence` is an instant for timed series and a date for all-day series, and the `start`/`startDate`/`endDate`/`allDay` fields. The parent ran it on 25c7b0fee and it passed.

## Requirement coverage

Command (parent, in `server/`, on 25c7b0fee, 2026-10-21 23:59 +0200): `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.contacts.aggregate.*' --tests '*OpenApiContractTest' jacocoTestReport` -> BUILD SUCCESSFUL. Every row below ran in that command.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 related: recent mails from/to the contact (From/To/Cc, newest first; Trash/Spam/Drafts and detached rows left out; duplicate copies listed once; newest 20; whole-address match including regex metacharacters; direction) | Web: `related lists mails from and to the contact newest first, without Trash, other people and duplicate copies`; Edge: `related leaves out Spam, Drafts and detached messages but keeps folders without a role`, `related returns only the newest 20 mails`, `related matches addresses with regex metacharacters literally`; CorrespondenceQueryTest (4) | parent command above | Pass |
| AC1 related: upcoming events with them (case-insensitive attendee match; past, beyond 90 days, without the contact and cancelled left out; next 20; by start) | Web: `related lists upcoming occurrences with the contact as attendee and its Waiting For tasks`; Edge: `related leaves out cancelled events`, `related returns only the next 20 occurrences` | parent command above | Pass |
| AC1 related: recurring and all-day occurrences | Edge: `related lists each occurrence of recurring timed and all-day series with the contact, by start` | parent command above | Pass |
| AC1 related: Waiting For items (only this contact, open, all pages) | Web: `related lists upcoming occurrences ...`; Edge: `related collects Waiting For tasks from every page of the list` | parent command above | Pass |
| AC1 related: an unknown or trashed contact is 404; a contact without email has no mails or events | Web: `related of a contact without email has no mails or events, unknown contact is 404`; Edge: `related of a contact in the Trash is 404` | parent command above | Pass |
| AC2 autocomplete merges contacts and recent correspondents (one entry per address; prefix ranking; no duplicates; own account/identity addresses and Trash left out; newest name; correspondent linked to the contact that owns the address; LIKE wildcards taken literally) | Web: `autocomplete merges contacts and recent correspondents without duplicates or own addresses`; Edge: `autocomplete leaves out the owner's account and identity addresses`, `autocomplete lists contacts whose name starts with the query before other matching contacts`, `autocomplete treats LIKE wildcards in the query literally`, `autocomplete finds contacts whose name starts with the query behind more than a page of other matches`, `autocomplete links a correspondent to the contact owning its address` | parent command above | Pass |
| AC2 autocomplete limit (default 10, explicit limit, 0 and 51 -> 400) and blank/whitespace query -> [] | Web: `autocomplete honours the limit, has nothing for a blank query and rejects a bad limit`; Edge: `autocomplete uses the default limit of 10`, `autocomplete has nothing for a whitespace-only query even though every contact contains a space` | parent command above | Pass |
| AC3 `./gradlew test --tests 'ch.sonensei.cockpit.contacts.aggregate.*'` passes (Postgres via Testcontainers) | all 24 tests of the package (Web 5, Edge 15, CorrespondenceQuery 4) | parent command above | Pass (24/24, 0 failures/errors/skipped) |
| AC4 API documented; `OpenApiContractTest` passes without the flag | `OpenApiContractTest` | parent command above | Pass (1/1) |

## Focused test execution

Command (parent, in `server/`, on HEAD 25c7b0fee, 2026-10-21 23:59 +0200):

`./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.contacts.aggregate.*' --tests '*OpenApiContractTest' jacocoTestReport`

Result:

Pass: BUILD SUCCESSFUL; ktlint clean (including the new test); ContactAggregateWebTests 5/5, ContactAggregateEdgeCaseTests 15/15 (includes the new test), CorrespondenceQueryTest 4/4, OpenApiContractTest 1/1; 0 failures, errors or skipped tests (Postgres via Testcontainers).

## Full test execution

Command:

`sh scripts/check.sh` (`.taskcheck`)

Result:

Not run by this test-writer (it cannot run Gradle). The parent runs it on the final code before `tasks.py done`.

## .taskcheck

Result:

Not run by this test-writer. `scripts/check.sh` is present; the parent runs it before `done`.

## Bugs found

None. No test failed in the parent's run on 25c7b0fee. Reading the final code against the criteria and assumptions found no requirement violation.

## Coverage

Status: verified - fresh JaCoCo run by the parent on the final code (25c7b0fee), with all selected tests passing, including the test added by this test-writer.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. All four files are new in this task (the diff adds them in full), so whole-file coverage is the same as changed-line coverage for them.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: contacts-aggregate; base `da5958f0ce6f09dad2e879aef4ad5486007fc7b1`; measured HEAD `25c7b0fee8131e77147fed585c274cc53238d9e5`; task commits 1d8854446, 036b5fb77, 25c7b0fee, supplied by the parent. Confirmed with `git diff --stat da5958f0..HEAD` (only this task's files) and with the empty code diff against the pre-rebase 314a570ad.
- Source/test snapshot identifier (including uncommitted inputs): commit 25c7b0fee; no uncommitted source or test changes (`git status --short` lists only `reviews/contacts-aggregate/`). sha256 after the run: Controller `5a1997070e2f...`, Dtos `2a99e9fc9585...`, Service `e1b97be2c815...`, CorrespondenceQuery `d3db40e07c00...`, ContactAggregateEdgeCaseTests `497a1194be15ac29c7309dd8521a448216b2fe847626ae95dbab8554270e717c`, ContactAggregateWebTests `09df87aaeb47...`, CorrespondenceQueryTest `1bad360d8691...`. The production hashes are the same as in the earlier measured revision, and the test hash is the same as the test this test-writer added.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/contacts/aggregate/{ContactAggregateController,ContactAggregateDtos,ContactAggregateService,CorrespondenceQuery}.kt`. Excluded: `api/openapi.yaml` and `web/src/app/api/generated/**` (generated; OpenApiContractTest checks the contract) and `tasks/`/`reviews/` (metadata). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo through the project's `jacocoTestReport` Gradle task (version as configured in `server/build.gradle.kts`); `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.contacts.aggregate.*' --tests '*OpenApiContractTest' jacocoTestReport` in `server/`; selection = the aggregate test package plus OpenApiContractTest.
- Run start/completion time and exit status: the parent started the run at 2026-10-21 23:59 +0200; report written 2026-10-21 23:59:57 +0200; BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 `71e6b43e056d2b884dcf5c38515da50d85f5ce29f6ccdcccc0df214b65f64c2b`. This test-writer checked the hash and file time and read the counters below from this XML.
- Evidence outputs are fresh and inputs stayed unchanged: the report hash differs from the earlier run (`7a63f640...`); its time matches the parent's run on 25c7b0fee; the counters now include the new test (Dtos branches 10/10 instead of 5/10). The source files are unchanged since that commit.
- Final run after latest test additions and production fixes: yes.

Values below are covered/total (percentage).

| Changed file | Statements (JaCoCo instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/.../contacts/aggregate/ContactAggregateController.kt` | 56/56 (100.0%) | 8/8 (100.0%) | 3/3 (100.0%) | 8/8 (100.0%) |
| `server/.../contacts/aggregate/ContactAggregateDtos.kt` | 519/519 (100.0%) | 10/10 (100.0%) | 46/46 (100.0%) | 88/88 (100.0%) |
| `server/.../contacts/aggregate/ContactAggregateService.kt` | 607/627 (96.8%) | 39/44 (88.6%) | 18/20 (90.0%) | 84/86 (97.7%) |
| `server/.../contacts/aggregate/CorrespondenceQuery.kt` | 338/346 (97.7%) | 14/20 (70.0%) | 14/15 (93.3%) | 54/54 (100.0%) |
| **Total (summed counters; 4/4 files measured, none missing or excluded)** | 1520/1548 (98.2%) | 71/82 (86.6%) | 81/84 (96.4%) | 234/236 (99.2%) |

Compared with the earlier run (fe802ca84, without the new test): Dtos 502/519 -> 519/519 instructions and 5/10 -> 10/10 branches; Service 594/627 -> 607/627 instructions and 36/44 -> 39/44 branches.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ContactAggregateDtos.kt:106` (occurrence id, instant vs date) | Recurring events in "upcoming events with them": the occurrence id the client needs to address one occurrence | Covered now by `related lists each occurrence of recurring timed and all-day series with the contact, by start` (10/10 branches in the file) |
| `ContactAggregateService.kt:80` (2/6 branches and 7 instructions missed; synthetic method `related$lambda$4` missed) | Sort order of upcoming occurrences. The all-day `startDate` sort key is now covered (4/6 branches). Still missed: the fallback when both `start` and `startDate` are null, and the `event.id` tie-break comparator | Justified gap. The null/null fallback cannot be reached for a valid occurrence (every occurrence has `start` or `startDate`). The tie-break only fixes the order between two occurrences with exactly the same start; the result set is the same either way, so the risk is low |
| `ContactAggregateService.kt:66-67` (catch `NotFoundException`) | A message deleted between the id query and `MailReadService.get` is skipped instead of failing the whole request | Not tested: needs a deletion during one request. A deterministic test would need a mocked `MailReadService`, which these integration tests do not use. Low risk (a simple skip); justified gap |
| `ContactAggregateService.kt:158-159` (`contactWithAddress` next page) | Linking a correspondent to its contact when more than 100 (`CursorRequest.MAX_LIMIT`) contacts contain the address as a substring before the exact owner | Not tested: needs more than 100 lookalike contacts. The exact-match check is tested (`autocomplete links a correspondent to the contact owning its address`), and the paging loop is the same as the tested `waitingFor` loop. Low risk; justified gap |
| `ContactAggregateService.kt:136` (2/6 branches: stop on `prefixEntries >= limit` while another page exists; the `CONTACT_SCAN` cap of 1000) | Early stop and upper bound of the contact scan. These affect performance, not results (later contacts rank lower) | Not tested: needs more than 100 prefix matches or more than 1000 contacts. Ordering across pages is tested. Low risk; justified gap |
| `ContactAggregateService.kt:178` (`Companion.getEVENT_HORIZON` missed) | Kotlin-generated companion accessor. The 90-day horizon itself is tested (the "Too far" event is left out) | No behavior gap |
| `CorrespondenceQuery.kt:90` (4/10 branches: an address that does not contain the query and has no display name) | Co-recipients without a name that do not match the query are filtered out | Not tested separately. The positive and name-based cases of the filter are tested. Low risk |
| `CorrespondenceQuery.kt:97` (2/4 branches: an older sighting when a name is already known, or neither has a name) | Keeps the newest name and only fills in a missing one | The fill-in case is tested ("Annika K" in the web test). The other branches do nothing. Low risk |
| `CorrespondenceQuery.kt:15` (`Correspondent.getLastSeenAt` missed) | Generated getter of the data class `Correspondent` | No behavior |

## Unable to test

- The `NotFoundException` race in `related` (a message disappears between the query and the load) cannot be reproduced reliably without mocking. No follow-up needed.
- `.taskcheck` and the full suite: not run by this test-writer (it cannot run Gradle). The parent runs them on the final code before `done`.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. All 24 task tests (including the recurring/all-day test this test-writer added) and OpenApiContractTest passed, and ktlint was clean, in the parent's run on the final code 25c7b0fee. A fresh JaCoCo report from that run gives 98.2% instructions, 86.6% branches and 99.2% lines over the four task files. The remaining gaps are low risk or cannot be reached, and each is justified above. The parent still runs `.taskcheck` and the full suite before `done`.
