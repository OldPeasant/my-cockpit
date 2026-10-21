# Test Report

Date: 2026-10-21

Task: cal-invitations - Respond to invitations

Scope: task commits 967809de6, 121fb209d, 5fe8ce31f, 654f0968f on immutable base cba4177c4bd01221a608830a3cec18a6a78d5357. HEAD at measurement is d12b41f9c92a9b2a552fbada397ca7a77145e2c4; that commit after 654f0968f only changes `reviews/cal-invitations/review.md`. Uncommitted inputs: the two test files listed below (this run's additions). Production code is unchanged against 654f0968f.

Requirements:
- `tasks/cal-invitations.md` acceptance criteria and recorded assumptions (If-Match 428/412, organizer and non-attendee get 409, Google `sendUpdates=all` and Graph `sendResponse=true`, Graph sync merges the event-level `responseStatus` into the user's attendee only when the attendee list shows no answer, Graph 400/403 stays 502)
- SPEC.md §6.2 and §4.2 (as summarized by the task and AGENTS.md)
- docs/ARCHITECTURE.md "Coverage" (informational only, no threshold)
- `reviews/cal-invitations/review.md` and `reviews/cal-invitations/fixes.md` (CR-1 fix: self-attendee merge and reload after the refetch; CR-3 fix: non-attendee 409)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/invitations/InvitationApiTests.kt` (sha256 b29bb0af8b2e75ff8e3e13ad13377eda5dcc0f39ebebe92df3a7347391daadff)
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/invitations/MicrosoftInvitationAdapterTests.kt` (sha256 9bc541110d2380133f7624925ccc511b123200197e25bb45d074a50f2ac5dc02)

## Tests added or updated

Count: 3

- `InvitationApiTests`: `declining a series that the refetch then removes returns the answer as saved, and the series is gone`. Google answers the series with DECLINED, and the refetch that follows lists `series01` as cancelled. Expected: 200 with the DECLINED attendee, the refetch ran, and the master and its override are deleted. This covers the `?: event` fallback of the CR-1 reload after the refetch.
- `InvitationApiTests`: `an answer Google returns as an unstorable event is a bad gateway and nothing changes`. Google's answer carries an RRULE without FREQ. Expected: 502 with "invalid event" in the detail, attendee still NEEDS_ACTION, external etag still `"inv-1"`, no rrule, no change event published.
- `MicrosoftInvitationAdapterTests`: `sync gives the user's answer from responseStatus to the user's attendee only, and an answer in the attendee list wins`. A third attendee (Kim) who has not answered stays NEEDS_ACTION while the user's attendee gets ACCEPTED from `responseStatus`. When the attendee list already shows the user's DECLINED and `responseStatus` says accepted, DECLINED is kept, as the recorded assumption says.

Existing tests reviewed and kept unchanged: `InvitationApiTests` (15), `MicrosoftInvitationAdapterTests` (15), `MicrosoftInvitationApiTests` (1), `GoogleInvitationAdapterTests` (11), `CalendarProviderAdapterInvitationDefaultsTests` (1).

## Requirement coverage

All rows were run by the parent worker from `server/` with `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.*' --tests '*OpenApiContractTest' jacocoTestReport` (2nd run, BUILD SUCCESSFUL, 897 tests, 0 failures).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `POST /api/v1/calendar/events/{id}/respond {ACCEPTED\|DECLINED\|TENTATIVE}` via the adapter; attendee status reflected (Google) | `InvitationApiTests`: accepting ... with Google's new etag; declining and answering tentatively are stored as well; an override is answered for its occurrence only; answering a series ... fetches the calendar again; declining a series that the refetch then removes ... (new); `GoogleInvitationAdapterTests` | command above | Pass |
| AC1 (Microsoft): Graph accept/decline/tentativelyAccept with `sendResponse=true`; the user's attendee shows the answer, also after the series refetch (CR-1) | `MicrosoftInvitationAdapterTests` (accepting calls Graph's accept action ...; the user's attendee shows the answer ...; an occurrence is answered through its instance; answering a series master ...; sync shows the user's answer from Graph's responseStatus ...; sync gives the user's answer ... to the user's attendee only ... (new)); `MicrosoftInvitationApiTests` accepting a series keeps the answer after the refetch ... | command above | Pass |
| AC1 errors: If-Match 428/412, 400 for a missing or invalid response, 404 for an unknown or trashed event, 409 for local/CalDAV/read-only/unsynced calendars, organizer, non-attendee (Google and Microsoft, CR-3), changed remotely (409 plus refetch), provider failure 502, invalid provider answer 502 (new) | `InvitationApiTests` (If-Match ...; a missing or unknown response ...; events of local calendars ...; an event the user is not invited to ...; 412 from Google ...; a failing Google ...; events of read-only ...; CalDAV ...; Trash ...; an answer Google returns as an unstorable event ... (new)); `MicrosoftInvitationAdapterTests` (stale etag/deleted; organizer and failing action; non-attendee; alias invitation; deleted while answering; only ACCEPTED, DECLINED and TENTATIVE) | command above | Pass |
| AC2: `./gradlew test --tests 'ch.sonensei.cockpit.calendar.invitations.*'` passes (Postgres via Testcontainers) | all classes in `calendar.invitations` (InvitationApiTests and MicrosoftInvitationApiTests use `PostgresTestBase`) | command above (a superset of the AC2 selection) | Pass |
| AC3: `OpenApiContractTest` passes without `UPDATE_OPENAPI` | `OpenApiContractTest` | command above | Pass |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.*' --tests '*OpenApiContractTest' jacocoTestReport` (run by the parent worker; sub-agents may not run Gradle here)

Result:

Pass on the 2nd run: ktlint clean, 897 tests, 0 failures/errors/skipped, all 3 new tests pass. The 1st run of the same command had 1 failure, in the unrelated `IcsFeedClientTests` "a feed of exactly the maximum size is read, a larger Content-Length is refused before reading" ("The feed server could not be reached (IOException)"). This is a known flake already seen in `fixes.md`; that class and the ICS code are outside this task.

## Full test execution

Command:

`sh scripts/check.sh` (full `.taskcheck`)

Result:

Not run in this pass. The `calendar.*` selection above (897 tests) was the broadest run. The parent worker runs `.taskcheck` before `done`.

## .taskcheck

Result:

Not run (by this helper; the parent worker runs it before `done`).

## Bugs found

None. No production defect was found. All new tests pass against the unchanged production code.

Observation (not a defect against any requirement): when the refetch after answering a series deletes the event (a declined series that the provider removes), `POST /respond` returns 200 with the event as saved before the refetch, including its ETag, although the event no longer exists locally. The behaviour is deliberate (`InvitationService.kt:119`, described in fixes.md) and is now pinned by a test. A client that uses that ETag next gets 404.

## Coverage

Status: verified (fresh run after the latest test additions; production code unchanged since 654f0968f)

Metric: whole-file coverage of task-changed production files (JaCoCo; statements = JaCoCo instructions).
Changed-line coverage: measured. Method: the executable lines from the JaCoCo XML `<line>` entries are intersected with the added/modified lines of `git diff -U0 cba4177c4..d12b41f9c -- server/src/main/kotlin`; removed and non-executable lines are not counted. Result: lines 185/185 (100%), branches on changed lines 117/121 (96.7%). The missed branches are `MicrosoftCalendarAdapter.kt:580` (1) and `GoogleCalendarAdapter.kt:277` (3); both are listed below.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: cal-invitations; base cba4177c4bd01221a608830a3cec18a6a78d5357; HEAD d12b41f9c92a9b2a552fbada397ca7a77145e2c4; task commits 967809de6, 121fb209d, 5fe8ce31f, 654f0968f (passed by the parent worker, consistent with `git log`); d12b41f9c only touches `reviews/`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD d12b41f9c plus the uncommitted test files `InvitationApiTests.kt` (sha256 b29bb0af...daadff) and `MicrosoftInvitationAdapterTests.kt` (sha256 9bc54111...dc02); hashes taken before the run and identical after it; `git status` shows only these two modified files.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/` `invitations/InvitationService.kt`, `invitations/InvitationController.kt`, `infra/microsoft/GraphEventMapping.kt`, `infra/microsoft/MicrosoftCalendarAdapter.kt`, `infra/google/GoogleCalendarAdapter.kt`, `infra/google/GoogleEventMapping.kt`, `sync/CalendarProviderAdapter.kt`. Excluded: `api/openapi.yaml` and `web/src/app/api/generated/**` (generated), `reviews/**` (documentation). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo 0.8.15 (Gradle `jacoco` plugin); `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.*' --tests '*OpenApiContractTest' jacocoTestReport`; `server/`; selection `ch.sonensei.cockpit.calendar.*` plus `*OpenApiContractTest`.
- Run start/completion time and exit status: report written 2026-10-21 21:38:57 +0200; exit status 0 (BUILD SUCCESSFUL, 2nd run). The 1st run failed on the unrelated flake; its output is not used.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 7b4906a6471f861ccc32c0c59771d9a3ed423ded41e4ccc836cbec3bff7b667f (verified by this helper); HTML under `server/build/reports/jacoco/test/html/`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report hash differs from the previous run (88b0173b...cfd8c, 21:33:24), and the new counters include the new tests (`InvitationService.kt` went from 71/73 to 73/73 lines). Test-file hashes are unchanged since they were written.
- Final run after latest test additions and production fixes: yes.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `calendar/invitations/InvitationService.kt` | 485/485 (100%) | 30/30 (100%) | 18/18 (100%) | 73/73 (100%) |
| `calendar/invitations/InvitationController.kt` | 49/49 (100%) | N/A | 6/6 (100%) | 10/10 (100%) |
| `calendar/infra/microsoft/GraphEventMapping.kt` | 1163/1165 (99.8%) | 141/146 (96.6%) | 19/19 (100%) | 129/129 (100%) |
| `calendar/infra/microsoft/MicrosoftCalendarAdapter.kt` | 2829/2959 (95.6%) | 231/269 (85.9%) | 52/52 (100%) | 365/371 (98.4%) |
| `calendar/infra/google/GoogleCalendarAdapter.kt` | 1322/1335 (99.0%) | 122/131 (93.1%) | 18/18 (100%) | 188/188 (100%) |
| `calendar/infra/google/GoogleEventMapping.kt` | 1281/1281 (100%) | 110/110 (100%) | 26/26 (100%) | 161/161 (100%) |
| `calendar/sync/CalendarProviderAdapter.kt` | 329/331 (99.4%) | N/A | 40/40 (100%) | 44/44 (100%) |
| **Aggregate (summed counters, all 7 files measured, none missing)** | 7458/7605 (98.1%) | 634/686 (92.4%) | 179/179 (100%) | 970/976 (99.4%) |

Before this run's tests, `InvitationService.kt` stood at 71/73 lines and 28/30 branches, and `GraphEventMapping.kt` at 140/146 branches.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `InvitationService.kt:108-109` (previously) | The provider answers with an event that cannot be stored: must be 502 with nothing changed | Test added: `an answer Google returns as an unstorable event ...`; now covered |
| `InvitationService.kt:119` (previously 2/4 branches) | The series refetch removes the event; the answer must still return | Test added: `declining a series that the refetch then removes ...`; now covered |
| `GraphEventMapping.kt:254` (previously 1/4 branches) | The user's `responseStatus` must not be copied to other attendees who have not answered; an answer already in the attendee list wins (assumption) | Test added: `sync gives the user's answer ... to the user's attendee only ...`; now covered |
| `MicrosoftCalendarAdapter.kt:580-584, 636-638, 649-651` | A Graph answer that cannot be read (bad date/zone) after a write or an answer becomes a provider error (502) | Left uncovered: shared, pre-existing catch blocks used by all writes; the respond path only reuses them. Low risk; worth a follow-up test in the Microsoft adapter suite, not in this task's scope |
| `MicrosoftCalendarAdapter.kt` other partial branches (123, 168, 210, 224, 231, 254-255, 294, 360, 474, 510, 536-566, 626, 693-694, 725-757) | Sync/push/cursor code of cal-microsoft | Outside this task's behavior (the file is in scope only because of the respond hook and the `self` plumbing); the respond code (394-449) and `invited()` (676-681) are fully branch-covered |
| `GraphEventMapping.kt:77, 194, 196, 220` | Reminder, diff and attendee-type mapping of cal-microsoft | Not changed by this task; no action |
| `GoogleCalendarAdapter.kt:277` (3/6 branches; a task-changed line) | An attendee entry in Google's JSON that is not an object is skipped | Defensive guard against malformed provider JSON; not reachable with Google's documented format; no test added |
| `GoogleCalendarAdapter.kt:126, 320` | Skipping unreadable listing items / unreadable write answer | Pre-existing (cal-google) defensive catches; no action |
| `CalendarProviderAdapter.kt` (2 instructions) | Interface default bodies | Synthetic/default-method glue; the invitation defaults are covered by `CalendarProviderAdapterInvitationDefaultsTests` |

## Unable to test

- `.taskcheck` and the web/android parts were not run by this helper: sub-agents cannot run Gradle or the check script in this environment. The parent worker ran the `calendar.*` selection and must run `.taskcheck` before `done`.
- Real Google/Microsoft endpoints are not reachable; all provider behavior is tested against WireMock with fictitious data. That the organizer actually receives the notification cannot be verified here; only the request parameters (`sendUpdates=all`, `sendResponse=true`) are asserted.
- The web client's generated `respondToInvitation` function has no UI that uses it in this task; generated code is excluded.

## Summary

Tests added and all validation passed. 3 tests were added to close meaningful gaps: the fallback when the refetch removes the event, the 502 for an answer that cannot be stored, and the scope of the Graph self-attendee merge. Task-scoped coverage is fresh and verified: changed lines 185/185, whole-file lines 970/976, branches 634/686. No production defects found. `.taskcheck` still has to be run by the parent worker.
