# Test Report

Date: 2026-10-26

Task: itip-api - API + web UI to send iTIP invitations for local events

Scope: immutable base `50d799687a48adf11f8a4e90c0f8947be439aa8c`, final HEAD `5377b19e3241fe5eecfb2e1ac0bd67a67d066bcd`, branch `main`. Task commits (`git log 50d799687..5377b19e3`): `43711d4db` (server ItipController, ItipApiTests, openapi + web client regeneration), `46318b536` (web invitation-dialog, event-dialog "Send invitations"), `5fea44596` (test-writer tests), `0acb182e7` (fixer: CR-1 account-load retry, CR-2 organizer left out of the To line), `158a6345c` (test-writer: retry that fails again), `5377b19e3` (200 response of the invitations endpoint documented as `application/json` only). The parent worker supplied base and HEAD. I checked them against `git log`, `git diff --stat` and the task acceptance criteria. The first five commits are the rebased versions of the range this report covered before (`4ab9b4c50..1e5a0705e` plus the then-uncommitted retry test). The only new code is in `5377b19e3`. No uncommitted source or test changes (`git status`: only `reviews/itip-api/` is untracked).

Requirements:
- `tasks/itip-api.md` acceptance criteria 1-2 and its recorded assumptions (endpoint shape, 422/502/503 mapping, web dialog behaviour)
- SPEC.md §6.2 (invitations), §4.2 (Problem Details), via the AGENTS.md project notes
- `reviews/itip-api/fixes.md` (CR-1, CR-2) for the fixer's changes
- docs/ARCHITECTURE.md "Coverage"; existing spec conventions (`scope-dialog.spec.ts`, `event-edit.service.spec.ts`)
- The existing contract test `CalendarApiContentTypeTests` (2xx calendar responses are JSON only), which commit `5377b19e3` fixes

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/itip/ItipApiTests.kt`
- `web/src/app/features/calendar/edit/invitation-dialog.spec.ts`
- `web/src/app/features/calendar/edit/event-dialog.spec.ts`

No test files changed in this final run (see "Final revision 5377b19e3" below).

## Tests added or updated

Count: 16 added by the test-writer over the task (server 1, web 15), none weakened or removed. None were added in this final run. The fixer's 2 new tests and the assertions it added to existing tests are not counted here; they are reviewed below.

Server (`ItipApiTests`, 7 tests):
- `more than the allowed number of recipients answers 400 without sending` (201 recipients, the `@Size(max = MAX_ITIP_RECIPIENTS)` boundary)
- An earlier draft also had `an unauthenticated request answers 401 without sending`. It failed in the class's authenticated `@BeforeEach` (creating the calendar needs a user), not in production code, so I removed it. The global 401 for `/api/**` is covered by `ApiConventionsWebTests` (`unauthenticated request stays 401 and is not turned into a 500`).

Web (`invitation-dialog.spec.ts`):
- InvitationDialog: `shows the first field error of a rejected request`, `shows a generic message when sending fails without an API error`, `cannot send while the mail accounts are loading`, `shows why the mail accounts could not be loaded and cannot send`, `shows a generic message when the mail accounts fail without an API error`, `sends once and cannot be closed while the mail is on its way` (double-click guard, `disableClose`, Send and Cancel disabled while pending, deferred promise), `can be closed again after a failed send`, `shows the loading state during a retry and the error again when it fails too` (CR-1: the retry while in flight, and a retry that fails again)
- InvitationService (HttpTestingController, through the real generated `sendEventInvitations` fn): `lists the mail accounts silently`, `posts the method and account to the invitations of the event, silently`, `rejects with the Problem Details of a failed send`
- InvitationPrompt: `opens the invitation dialog with the event and resolves what was sent`, `resolves undefined when the user cancels`

Web (`event-dialog.spec.ts`, describe `invitations`):
- `is not offered for a read-only local calendar`
- `sends an override on its own and a generated occurrence as its series`

Review of the fixer's spec changes (commit `0acb182e7`):
- CR-1: `loads the mail accounts again after a failure` (a retry that succeeds, then sends), plus assertions that the no-accounts hint is absent after a failed load and that "Try again" is absent when there are no accounts.
- CR-2: `leaves the address of the chosen account out of the guests` (case-insensitive match, updates when the selected account changes).
- Gap found and closed: nothing covered the loading state during a retry, or a second failure. Covered by the retry test above (`158a6345c`).
- Not covered by a test (low value): the To line of an event whose only guest is the organizer's own address is empty. The server then answers 409 and the dialog shows that message.

### Final revision 5377b19e3: no new tests needed

The commit changes only OpenAPI metadata. `ItipController` now declares `content = [Content(mediaType = APPLICATION_JSON_VALUE, schema = SendInvitationsResponse)]` on its `@ApiResponse(200)`. `api/openapi.yaml` and `web/src/app/api/generated/fn/calendars/send-event-invitations.ts` were regenerated (the generated `accept` changed from `application/problem+json` to `application/json`). Already covered:
- Server: `CalendarApiContentTypeTests.the contract documents successful calendar responses as JSON only` goes through every `/api/v1/calendar/**` path in `/v3/api-docs` and fails if a 2xx response lists `application/problem+json`. It covers the invitations path generically and failed before this commit (the parent's `sh scripts/check.sh`: 4986 tests, 1 failed, that one). `OpenApiContractTest` checks that `api/openapi.yaml` matches the generated contract.
- Web: the InvitationService tests run the regenerated fn against HttpTestingController (method, URL, body, silent context, Problem Details rejection). I did not add an assertion on the exact `Accept` header. It is generator output, it does not change client behaviour (`responseType: 'json'` either way), and a regeneration would overwrite it anyway. The server contract test is the right guard.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: POST sends REQUEST/CANCEL over the chosen account | ItipApiTests: `a REQUEST goes to every attendee but the organizer over the chosen account`, `a CANCEL to chosen recipients goes only to them`; ItipGreenMailTests (previous task) for the mails themselves | parent: `server/gradlew -p server updateOpenApi ktlintCheck test --tests '*CalendarApiContentTypeTests' --tests '*ItipApiTests' --tests '*OpenApiContractTest'` on HEAD 5377b19e3 | Pass |
| AC1: Problem Details errors (400/404/409/422/502/503) | ItipApiTests: `missing method or account and invalid recipients answer 400 Problem Details without sending`, `an unknown event answers 404 and an event without guests 409`, `a refusal by the SMTP server answers 422 SEND_REJECTED with the rejected addresses`, `a failing mail server answers 502 with its error code, an exhausted pool 503` | same parent command | Pass |
| AC1: recipient limit | ItipApiTests: `more than the allowed number of recipients answers 400 without sending` | same parent command | Pass |
| AC1: openapi regenerated; 2xx documented as JSON only | OpenApiContractTest; CalendarApiContentTypeTests `the contract documents successful calendar responses as JSON only` | same parent command | Pass (failed before 5377b19e3) |
| AC1 (client side): web calls the endpoint with method + account, silent errors, Problem Details surface as ApiError | InvitationService tests (3) | `npx --prefix web ng test --watch=false --include='src/app/features/calendar/edit/*.spec.ts'` on HEAD 5377b19e3 (this run) | Pass |
| AC2: "Send invitations" offered for saved local events with guests, not for synced, read-only, guestless or new events | event-dialog `invitations`: `sends invitations for a saved local event with guests and says so`, `is not offered without saved guests, for synced events or in a new event`, `is not offered for a read-only local calendar` | same ng test command | Pass |
| AC2: unsaved changes block sending; override vs series id | `asks to save unsaved changes first`, `sends an override on its own and a generated occurrence as its series` | same ng test command | Pass |
| AC2: dialog sends REQUEST/CANCEL over a chosen account, To line without the organizer, reports the result | invitation-dialog: `shows the event and its guests and sends an invitation over the first account`, `sends a cancellation over the chosen account`, `leaves the address of the chosen account out of the guests`; event-dialog `reports a cancellation and nothing when the user cancels the dialog`; InvitationPrompt tests (2) | same ng test command | Pass |
| AC2: error, loading, retry and no-account states; no double send; no closing while sending | invitation-dialog: `cannot send without a mail account`, `shows a refusal of the mail server and stays open`, `shows other errors with their message`, `shows the first field error of a rejected request`, `shows a generic message when sending fails without an API error`, `cannot send while the mail accounts are loading`, `shows why the mail accounts could not be loaded and cannot send`, `loads the mail accounts again after a failure`, `shows the loading state during a retry and the error again when it fails too`, `shows a generic message when the mail accounts fail without an API error`, `sends once and cannot be closed while the mail is on its way`, `can be closed again after a failed send` | same ng test command | Pass |
| AC2 in a real browser against the running stack | none (no Playwright spec in this task) | none | Not run: no e2e test exists for this flow; covered by unit/component tests only |

## Focused test execution

Command:

`npx --prefix web ng test --watch=false --include='src/app/features/calendar/edit/*.spec.ts'` (repository root, HEAD 5377b19e3, clean tree; run by the test-writer at 2026-10-26T15:34Z)

Result:

Pass - 8 test files, 168 tests passed.

Server (parent, HEAD 5377b19e3): `server/gradlew -p server updateOpenApi ktlintCheck test --tests '*CalendarApiContentTypeTests' --tests '*ItipApiTests' --tests '*OpenApiContractTest'` -> pass, ktlint clean.

## Full test execution

Command:

`npm --prefix web run coverage` (full web unit suite with v8 coverage, HEAD 5377b19e3)

Result:

Pass - 179 test files, 3789 tests passed.

Server full suite: not run by the test-writer (sub-agents cannot run Gradle). Before 5377b19e3 the parent's `sh scripts/check.sh` failed only on `CalendarApiContentTypeTests` (4986 tests, 1 failed). The parent's targeted run above shows that test passing now. The full server suite on 5377b19e3 has to be confirmed by the parent's final `.taskcheck`.

## .taskcheck

Result:

Not run by the test-writer: `sh scripts/check.sh` includes Gradle, which sub-agents cannot run. The parent has to run it on 5377b19e3 before `done`.

## Bugs found

No production defects found by failing tests in this task's new tests.

`CalendarApiContentTypeTests` (an existing contract test) failed in the parent's `.taskcheck` before 5377b19e3, because the invitations 200 response was documented with `application/problem+json`. Fixed in 5377b19e3; the parent's targeted run passes. It is listed here for traceability, not as an open finding.

Resolved observation (Low, UX): in the first implementation, a failed mail-account load showed the load error together with the hint "Add a mail account in the settings to send invitations." The fixer resolved this under CR-1 (`0acb182e7`): a load failure now has its own error with a "Try again" button, and the hint shows only after a successful load that returned no accounts. Verified by `shows why the mail accounts could not be loaded and cannot send`, `shows a generic message when the mail accounts fail without an API error`, `loads the mail accounts again after a failure` and `shows the loading state during a retry and the error again when it fails too` (all pass).

## Coverage

Status: verified for the web files on the final revision 5377b19e3 (fresh run). Server: the JaCoCo numbers come from the parent's run on 1e5a0705e (the pre-rebase equivalent of 43711d4db..0acb182e7). Since then `ItipController.kt` changed only in its `@ApiResponse` annotation arguments, which are class-file metadata and add no executable instructions or branches. No JaCoCo report was produced on 5377b19e3, because the parent's final server command did not include `jacocoTestReport`. The server row is therefore from a pre-final revision. If the parent wants fresh server numbers, run `server/gradlew -p server test --tests '*ItipApiTests' jacocoTestReport`.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: web - every executable line of both changed web files is covered (lines 92/92 and 165/165), so every added executable line is covered. This is derived from the v8 line counters, not from a separate diff intersection. Server - `ItipController.kt` is a new file, so whole-file and changed-line coverage are the same (33/33 lines, run on 1e5a0705e).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: itip-api; base `50d799687a48adf11f8a4e90c0f8947be439aa8c`; HEAD `5377b19e3241fe5eecfb2e1ac0bd67a67d066bcd`; commits `43711d4db`, `46318b536`, `5fea44596`, `0acb182e7`, `158a6345c`, `5377b19e3`, as given by the parent and confirmed with `git log` / `git diff --stat 50d799687..HEAD` (14 files, all in the task's scope).
- Source/test snapshot identifier (including uncommitted inputs): HEAD `5377b19e3`, clean tree (no uncommitted source or test changes). sha256: `invitation-dialog.ts` b5917b222c455d339ce4c1d4cbeedb621517cb0970ddff4ba58ad0ece06f91b8, `event-dialog.ts` 720594857dd35a167e4f5e50836371032e93bd0994fb509ddef9205fe9184456, `ItipController.kt` ad7339892874c0b566f4dc082732c7275e912783104ab2a5c44b6c4a86577c3c, `invitation-dialog.spec.ts` 64d6db78a53fad96008992ed9854f1bab414b70dffd2f054340a5903e5b6111f, `event-dialog.spec.ts` 2b16f4ffdd4d1eda172fc539d6cd2d6c62a6af40efba212154b3a92ce63e693b, `ItipApiTests.kt` e0ba870fb646a46d60c268afa171f87e86e7555f5f7c48eab15364080eaab3cd, generated `send-event-invitations.ts` c3f3872de923b260e14171a37ebd088dacfd49160dde0785f82888510bd523fb, `api/openapi.yaml` f2893a2c209b26cf6e658e6b7c4f07d6fe3746ee703a271b5bd16db59eb0d510. The web production and spec files have the same hashes as in the previous run. Only `ItipController.kt` and the generated files changed.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip/ItipController.kt`, `web/src/app/features/calendar/edit/invitation-dialog.ts`, `web/src/app/features/calendar/edit/event-dialog.ts`. Excluded: `event-dialog.html` (a template, measured through its component; v8 keeps no separate counters for it), `api/openapi.yaml` and `web/src/app/api/generated/**` (generated), and the test files. No renames or deletions.
- Provider/version, command, working directory, and test selection: web - vitest v5.0.5 with v8 coverage, `npm --prefix web run coverage` from the repository root, full web suite. Server - JaCoCo via the parent's `cd server && ./gradlew ktlintCheck test --tests '*ItipApiTests' jacocoTestReport` on 1e5a0705e.
- Run start/completion time and exit status: web 2026-10-26T15:34:20Z (build start) to 2026-10-26T15:35:37Z (report written), exit 0. Server: the parent's run on 1e5a0705e, exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` sha256 bf423b4a0d14fc119d0c82051ef3877df3b72c455e51e53e204efe04c7fbff9f (same as the previous run, as expected for byte-identical web inputs; rewritten at 2026-10-26 16:35:37 +0100 by this run), `web/coverage/web/coverage-final.json` sha256 f6c18008041a765ab8650cb5282098d2d7c5a1dfda65502cd32959e50c8f4622, `web/coverage/web/lcov.info`. Server: the parent's JaCoCo report under `server/build/reports/jacoco` (from 1e5a0705e).
- Evidence outputs are fresh and inputs stayed unchanged: web yes (clean tree, nothing edited during or after the run). Server: not fresh for 5377b19e3, see Status.
- Final run after latest test additions and production fixes: web yes. Server tests yes (parent's targeted run on 5377b19e3); server coverage no (from 1e5a0705e, annotation-only change since).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/.../calendar/itip/ItipController.kt` (JaCoCo, parent run on 1e5a0705e) | instructions 181/183 (98.9%) | 3/4 (75.0%) | 12/12 (100%) | 33/33 (100%) |
| `web/.../calendar/edit/invitation-dialog.ts` | 130/130 (100%) | 39/40 (97.5%) | 14/14 (100%) | 92/92 (100%) |
| `web/.../calendar/edit/event-dialog.ts` | 198/202 (98.0%) | 139/147 (94.6%) | 45/45 (100%) | 165/165 (100%) |

Web aggregate (summed counters, 2 files, HEAD 5377b19e3): statements 328/332 (98.8%), branches 178/187 (95.2%), functions 59/59 (100%), lines 257/257 (100%). The server file is not part of this aggregate, because it uses a different provider with different counter types. `event-dialog.html` and the generated files are excluded as stated above.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `invitation-dialog.ts:187` branch 0 | a compiler-emitted branch at the class declaration (decorator/constructor helper) with no behaviour | Justified gap: not reachable from tests |
| `event-dialog.ts:359` (statement + branch) | defensive guard in `sendInvitations` (not edit mode / not invitable / saving); in those states the button is not rendered or is disabled | Justified gap: defensive code, same pattern as the existing `delete` guard at line 344 |
| `event-dialog.ts:96, 182, 183, 201, 249, 251, 344` | older code this task did not change | Out of task scope |
| `ItipController.kt` 1 of 4 branches, 2 instructions | most likely the `e.message ?: ""` fallback in `handleSendRejected` (a MailSendRejectedException always carries a message) | Justified gap, not material |
| `ItipController.kt` `@ApiResponse(200)` content (5377b19e3) | 2xx calendar responses documented as JSON only | Not executable code; covered by `CalendarApiContentTypeTests` and `OpenApiContractTest` (parent run, pass) |

## Unable to test

- The "Send invitations" flow in a real browser: no Playwright spec exists for it, so only component tests cover it. Follow-up only if the parent wants e2e evidence for AC2.
- `.taskcheck`, the full server suite and fresh server JaCoCo on 5377b19e3: they need Gradle and are left to the parent.

## Summary

No additional tests were necessary for the reviewed scope of the final commit 5377b19e3: the existing `CalendarApiContentTypeTests` contract test covers it. On the final revision, the web suites pass (focused 168/168, full 3789/3789, fresh coverage), and the parent's targeted server run (ItipApiTests, CalendarApiContentTypeTests, OpenApiContractTest, ktlint) passes. No production defects remain. The parent still needs to run the final `.taskcheck` on 5377b19e3.
