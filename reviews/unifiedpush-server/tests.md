# Test Report

Date: 2026-10-26

Task: unifiedpush-server - Server side of UnifiedPush (registrations + push sending)

Scope: immutable base 9b1203a906cef42fed2a7a43ff40690c56a2ace7 (merge-base with origin/main, supplied by the parent) .. HEAD c3fbd5f3fbfdb446d8cb2bab4275eed483530c65. Task-owned commits (supplied by the parent, checked with `git log 9b1203a90..HEAD`): 6c9e1cf74, 6f2061b0a, e3dbcebc9, d3170a767, 24e382920 (HEAD c3fbd5f3f merges `origin/wip/unifiedpush-server`). Working tree is clean apart from this report. No production code changed by the test-writer.

Requirements:
- `tasks/unifiedpush-server.md` acceptance criteria 1-3 and the recorded assumptions (no backup of registrations; payload `{"kind","id"}`; 204/idempotent PUT and DELETE without If-Match; instanceId pattern; http(s) endpoints; push after commit with TTL 1 day and urgency high/normal)
- `SPEC.md` (single user, Problem Details errors, UUIDv7 ids), `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`

## Test files changed

- none by this run (the 52 push tests and the `NewMailAnnouncerTests` changes were already committed in the task commits listed above)

## Tests added or updated

Count: 0

Gap analysis against the acceptance criteria found no meaningful untested behavior (see Coverage), so no test was added.

Existing task tests reviewed (63 run in the focused selection):
- `UnifiedPushWebTests` (5), `UnifiedPushWebEdgeCaseTests` (8): PUT/DELETE, 204, idempotency, key clearing, 400 with field name, 401 for anonymous, instanceId 1/128/129 chars, endpoint length 2048/2049, malformed JSON, invalid/padded keys
- `UnifiedPushServiceTests` (12), `UnifiedPushUpsertTests` (3): upsert in place, concurrent registrations (CR-1 fix), UUIDv7 id/timestamps/version, URL validation, DB constraints (one row per instance, both keys or none), gone-endpoint removal races, no endpoint URL in logs
- `PushDispatcherTests` (5), `PushDispatcherFailureTests` (7), `PushEndToEndTests` (5): payload kind+id only, aes128gcm encryption decrypting to the payload, push to every endpoint, 404/410 removed and other statuses (incl. 500/503/429/3xx) kept, after-commit only and none on rollback, unreachable server / encryption failure / repository failure / shutdown never break the publisher, scheduler-fired reminder pushes id and never title, redirects not followed
- `WebPushEncryptionTests` (3), `WebPushEncryptionEdgeCaseTests` (4): RFC 8291 example vector reproduced exactly, fresh salt per message, record-size boundary, off-curve key rejected, keys hidden in toString
- `NewMailAnnouncerTests` (11, 1 for this task): `announced mail is pushed to the devices with the newest message's id only`, plus the existing no-notification cases (no push when no SSE notification)

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: PUT `/api/v1/push/unifiedpush/{instanceId}` (endpoint, optional p256dh/auth) and DELETE for the logged-in user | `UnifiedPushWebTests.*`, `UnifiedPushWebEdgeCaseTests.*`, `UnifiedPushServiceTests.*`, `UnifiedPushUpsertTests.*` | focused command below | Pass |
| AC1: Flyway migration under `db/migration/core` | `UnifiedPushServiceTests.the table allows one row per instance and only both keys or none` (Spring context runs Flyway incl. `V202610261400__core_push_unifiedpush.sql`) | focused command below | Pass |
| AC1: backup/restore covered or reason recorded | Not testable: by assumption, registrations are not backed up; the reason is in the task notes and the migration comment | n/a | Not testable (documented exclusion) |
| AC1: `api/openapi.yaml` regenerated | `OpenApiContractTest` in the full server suite; `api/openapi.yaml` contains `/api/v1/push/unifiedpush/{instanceId}` (line 7167) | `cd server && ./gradlew check` | Pass |
| AC2: push to every registered endpoint on `ReminderFiredEvent` | `PushDispatcherTests.a fired reminder is pushed as kind and id only, unencrypted without keys`, `...every registered endpoint gets the push...`, `PushEndToEndTests.a reminder fired by the scheduler is pushed with its id, never its item's title` | focused command below | Pass |
| AC2: push on new mail (`NewMailAnnouncer`) | `NewMailAnnouncerTests.announced mail is pushed to the devices with the newest message's id only` | focused command below | Pass |
| AC2: payload kind + id only, no item contents | the tests above plus `PushEndToEndTests` (title never in the push) | focused command below | Pass |
| AC2: RFC 8291 encryption when keys are given | `WebPushEncryptionTests.encrypts the RFC 8291 example message exactly as the RFC does`, `PushDispatcherTests.with keys the push is encrypted with aes128gcm and decrypts to the payload`, `WebPushEncryptionEdgeCaseTests.*` | focused command below | Pass |
| AC2: endpoints answering 404/410 are removed | `PushDispatcherFailureTests.only 404 and 410 remove an endpoint, other answers keep it`, `UnifiedPushServiceTests.a gone answer ...` (3 tests) | focused command below | Pass |
| AC2: failures never break the event flow | `PushDispatcherTests.an unreachable push server does not break the publisher`, `PushDispatcherFailureTests.*` (7), `PushEndToEndTests.a failing resolver still pushes the reminder` | focused command below | Pass |
| AC3: `./gradlew test --tests 'ch.sonensei.cockpit.core.push.*'` passes with a fake distributor | all 52 tests in `ch.sonensei.cockpit.core.push.*` (WireMock / local HTTP stub as the distributor) | focused command below | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.push.*' --tests 'ch.sonensei.cockpit.mail.sync.NewMailAnnouncerTests' jacocoTestReport`

Result:

Pass. BUILD SUCCESSFUL in 23s, exit 0, at 2026-10-26 about 16:53 +01:00. 63 tests: 52 in `ch.sonensei.cockpit.core.push.*` (PushDispatcherFailureTests 7, PushDispatcherTests 5, PushEndToEndTests 5, UnifiedPushServiceTests 12, UnifiedPushUpsertTests 3, UnifiedPushWebEdgeCaseTests 8, UnifiedPushWebTests 5, WebPushEncryptionEdgeCaseTests 4, WebPushEncryptionTests 3) + NewMailAnnouncerTests 11; 0 failures, 0 errors, 0 skipped (from `server/build/test-results/test/TEST-*.xml`).

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Pass. BUILD SUCCESSFUL in 8m 41s, exit 0, finished about 17:02 +01:00. ktlint main/test/script checks pass. 5046 server tests, 0 failures, 0 errors, 0 skipped.

## .taskcheck

Result:

Not run by the test-writer. `sh scripts/check.sh` also runs the web and Android checks, which this server-only task does not touch. Its server part (`./gradlew check`) passed above. The parent worker runs `.taskcheck` before `done`.

## Bugs found

None. No test failed, and no production behavior contradicts the acceptance criteria or the recorded assumptions.

## Coverage

Status: verified (fresh run, exit 0, stable snapshot).

Metric: whole-file coverage of task-changed production files (includes the pre-existing lines of `NewMailAnnouncer.kt`; hits come from the focused 63-test selection only).
Changed-line coverage: not measured as a separate metric. All 2 added executable lines in `NewMailAnnouncer.kt` (the `PushMessage` publish at line 73 and its imports) are covered. Every other scoped file is new in this task, so its whole-file numbers are its changed-line numbers.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: unifiedpush-server; base 9b1203a906cef42fed2a7a43ff40690c56a2ace7; HEAD c3fbd5f3fbfdb446d8cb2bab4275eed483530c65; commits 6c9e1cf74, 6f2061b0a, e3dbcebc9, d3170a767, 24e382920 from the parent, file scope from `git diff --stat 9b1203a90..HEAD -- server`
- Source/test snapshot identifier (including uncommitted inputs): HEAD c3fbd5f3f with a clean tree (only this untracked report), the same before and after both runs; no uncommitted source or test inputs
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/core/push/{api/UnifiedPushController.kt, domain/PushDispatcher.kt, domain/PushMessage.kt, domain/UnifiedPushRegistration.kt, domain/UnifiedPushService.kt, domain/WebPushEncryption.kt, domain/WebPushKeys.kt, infra/UnifiedPushClient.kt, infra/UnifiedPushRegistrationRepository.kt}`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/NewMailAnnouncer.kt`. Excluded: `V202610261400__core_push_unifiedpush.sql` (SQL, not instrumentable; exercised by Flyway in the Spring context tests), `api/openapi.yaml` and the regenerated web client (generated files), test files, task/review files. No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (as configured in `server/build.gradle.kts`, Gradle 9.8.1); `./gradlew test --tests 'ch.sonensei.cockpit.core.push.*' --tests 'ch.sonensei.cockpit.mail.sync.NewMailAnnouncerTests' jacocoTestReport`; working directory `server/`
- Run start/completion time and exit status: 2026-10-26 about 16:53 +01:00 (test suites timestamped 15:53:19Z-15:53:38Z), BUILD SUCCESSFUL in 23s, exit 0
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 b2386239d4a33fbc3167395ca1ad3a959d8fd5f49652a2bbbe0ee6fe94847589 (HTML at `server/build/reports/jacoco/test/html/index.html`)
- Evidence outputs are fresh and inputs stayed unchanged: yes. Gradle recompiled main and test and ran `:test` and `:jacocoTestReport` (not UP-TO-DATE). HEAD and the clean tree were the same after the run. The later `./gradlew check` did not rerun `jacocoTestReport` (report hash unchanged).
- Final run after latest test additions and production fixes: yes. It covers HEAD c3fbd5f3f, which includes the CR-1/CR-2 fixes (d3170a767) and `UnifiedPushUpsertTests` (24e382920). This run added no tests.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. "Statements" are JaCoCo INSTRUCTION counters.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `core/push/api/UnifiedPushController.kt` | 133/139 (95.7%) | 16/18 (88.9%) | 9/10 (90.0%) | 30/30 (100.0%) |
| `core/push/domain/PushDispatcher.kt` | 214/215 (99.5%) | 15/16 (93.8%) | 8/8 (100.0%) | 35/35 (100.0%) |
| `core/push/domain/PushMessage.kt` | 51/54 (94.4%) | N/A (0/0) | 6/7 (85.7%) | 8/8 (100.0%) |
| `core/push/domain/UnifiedPushRegistration.kt` | 78/92 (84.8%) | 7/8 (87.5%) | 8/11 (72.7%) | 11/14 (78.6%) |
| `core/push/domain/UnifiedPushService.kt` | 166/166 (100.0%) | 23/24 (95.8%) | 6/6 (100.0%) | 28/28 (100.0%) |
| `core/push/domain/WebPushEncryption.kt` | 402/402 (100.0%) | 8/8 (100.0%) | 10/10 (100.0%) | 49/49 (100.0%) |
| `core/push/domain/WebPushKeys.kt` | 109/109 (100.0%) | 10/10 (100.0%) | 7/7 (100.0%) | 16/16 (100.0%) |
| `core/push/infra/UnifiedPushClient.kt` | 149/151 (98.7%) | 2/2 (100.0%) | 5/6 (83.3%) | 31/31 (100.0%) |
| `core/push/infra/UnifiedPushRegistrationRepository.kt` | N/A (0/0) | N/A (0/0) | N/A (0/0) | N/A (0/0) |
| `mail/sync/NewMailAnnouncer.kt` | 168/180 (93.3%) | 14/14 (100.0%) | 8/12 (66.7%) | 35/37 (94.6%) |
| **Aggregate (summed counters, 10 files)** | **1470/1508 (97.5%)** | **95/100 (95.0%)** | **67/77 (87.0%)** | **243/248 (98.0%)** |

The aggregate covers all 10 scoped Kotlin files. `UnifiedPushRegistrationRepository.kt` is measured but has no counters: it is a Spring Data interface whose query methods have no bytecode of their own, and it is exercised through `UnifiedPushService` in the integration tests. Excluded from the aggregate (not instrumentable): the SQL migration and the generated OpenAPI/web-client files. No scoped file is missing from the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `UnifiedPushRegistration.kt:25,29,33` (protected setters) and `:36` (1 of 8 branches: `auth` null while `p256dh` set) | JPA-only setters; a half-keyed row is impossible because of the DB check constraint (tested in `UnifiedPushServiceTests.the table allows one row per instance and only both keys or none`) | Justified gap: no test added |
| `PushDispatcher.kt:62` (1 instruction, 1 of 16 branches) | The `when` on the HTTP status. Statuses outside 2xx/404/410 are tested (`only 404 and 410 remove an endpoint, other answers keep it` with 301/400/401/403/413/429/500/503). The missed counter is a Kotlin bytecode artifact of the `in 200..299` subject check. | Justified gap: no test added |
| `UnifiedPushService.kt:69` (1 of 24 branches) | Endpoint validation. Relative, mailto, ftp, hostless and userinfo URLs are tested. The missed branch is a null/empty variant of `scheme`/`host` that `java.net.URI` does not produce for the remaining inputs. | Justified gap: no test added |
| `UnifiedPushController.kt:73` (1 of 18 branches: `e.message` null) and `:112` (constant initializer) | `WebPushKeys` always throws with a message, and the field mapping is tested (`keys that are not base64url, not uncompressed or not on the curve answer 400 for the right field`). Line 112 is a compile-time constant. | Justified gap: no test added |
| `PushMessage.kt:22` (generated data-class method), `UnifiedPushClient.kt:66` (unused `TTL` getter; the value is sent as a header and asserted through the distributor stub) | No behavior | Justified gap: no test added |
| `NewMailAnnouncer.kt:27,28,35,37` | Pre-existing `NewMailSummary`/`NewMailNotification` data-class getters used only by JSON serialization, not changed by this task | Out of task scope: no test added |

## Unable to test

- Backup/restore round trip for UnifiedPush registrations: intentionally not backed up (recorded assumption: an app installation registers again after a restore). Nothing to test, and no follow-up is needed unless a human rejects the assumption.
- Delivery through a real UnifiedPush distributor (ntfy) and decryption on a real Android device: these need an external service and a device. The criteria name only a fake distributor (MockWebServer or similar). The RFC 8291 test vector and the round-trip decryption test stand in for this. Follow-up belongs to `unifiedpush-android`.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The focused selection (63 tests) and the full server `./gradlew check` (5046 tests plus ktlint) pass. Task-scoped coverage is 98.0% of lines and 95.0% of branches, and every remaining gap is justified above.
