# Code Review

Date: 2026-10-26

Task: unifiedpush-server: Server side of UnifiedPush (registrations + push sending)

Scope: all committed changes in `origin/main..HEAD` (4 commits): production code under `server/src/main/kotlin/ch/sonensei/cockpit/core/push/`, the `NewMailAnnouncer` change, migration `db/migration/core/V202610261400__core_push_unifiedpush.sql`, `api/openapi.yaml`, the generated web client (`web/src/app/api/generated/`), and the tests. Uncommitted files (a parallel test-writer) were ignored. This review replaces the earlier one. It also checks the fixes for the earlier CR-1 and CR-2 described in `reviews/unifiedpush-server/fixes.md`.

Requirements:
- `tasks/unifiedpush-server.md` (acceptance criteria 1-3 and the four recorded assumptions)
- `AGENTS.md` project notes (module boundaries, migrations, OpenAPI regeneration, logging rules, backup rule)
- `SPEC.md` §4.2 / §14 conventions as referenced in `AGENTS.md`
- Existing code conventions (`RestoreService`, `MailRepositories` upsert, `ReminderService` event publication)

Git range:
- Branch: main
- HEAD: 03cf4bc4c9b18271758c6f793f7e478cd5812e2d
- Base/start: 606d09f32035adbab96718d64aec02cffa5ff60b (`git merge-base HEAD origin/main`)
- Commits reviewed: 4 (2b4095bdd, df81083ab, dbe0d4c6b, 03cf4bc4c)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Verification of earlier findings

- **Earlier CR-1 (concurrent PUT race giving 500/409): fixed.** `UnifiedPushService.register` now validates its input and then runs one native `insert ... on conflict (instance_id) do update` (`UnifiedPushRegistrationRepository.upsert`). The unique constraint `push_unifiedpush_registration_instance` exists, so the conflict target is valid. The update clears the keys when a later PUT omits them (covered by `PUT without keys after one with keys clears them`). The `version` column is incremented, so `@Version` stays consistent. `clearAutomatically = true` keeps the persistence context from returning stale entities. The regression test `concurrent registrations of the same instance all succeed and leave one row` runs barrier-synchronized inserts and then updates, 5 rounds of 8 threads, and asserts that no call fails and each instance has one row.
- **Earlier CR-2 (async failure-path test asserted too early): fixed.** `PushDispatcherTests.awaitFailureLogged` waits for the dispatcher's WARN log before the registration assertions. That log is the last step for the endpoint. The unreachable-endpoint test also shows that a later push is still delivered. `PushDispatcherFailureTests` uses `verify(timeout = ...)` or log polling for every asynchronous assertion.

## Acceptance criteria check

1. **PUT/DELETE, migration, backup, OpenAPI: met.**
   - `UnifiedPushController` maps `PUT`/`DELETE /api/v1/push/unifiedpush/{instanceId}` and answers 204.
   - Input checks: the instance id is checked against the pattern; the endpoint must be an absolute http(s) URL with a host and no user-info; the keys must be paired, base64url, of the right sizes and on the curve. Invalid input answers 400 with the field.
   - Anonymous requests answer 401 (tested).
   - The migration is a new file under `db/migration/core` with the required naming. It has a unique instance id and a check that both keys are set or neither.
   - Backup: the decision not to back up registrations is recorded as an assumption and in the migration comment. This matches the backup mechanism: backup is contributor-based, so this table is not exported, and `RestoreService.wipeDatabase` truncates all tables, so stale registrations do not survive a restore.
   - `api/openapi.yaml` and the generated client (`registerUnifiedPush`, `unregisterUnifiedPush`, `UnifiedPushRegistrationRequest`) match the controller.
2. **Push on reminder and new mail: met.**
   - `PushDispatcher` listens to `ReminderFiredEvent` and `PushMessage` with `@TransactionalEventListener(fallbackExecution = true)`. It only hands work to its own virtual-thread executor; a rejected task is caught.
   - The payload is `{"kind","id"}` only. It is encrypted with RFC 8291 `aes128gcm` when keys are present. The encryption is checked against the RFC's Appendix A vector.
   - 404 and 410 answers remove the registration through `removeGone`. The removal is endpoint-guarded, so it never deletes a re-registered endpoint.
   - Every per-endpoint failure and every failure to read the registrations is logged without the URL and then dropped.
   - `NewMailAnnouncer` publishes `PushMessage(MAIL, newest unread id)` only on the same path as the SSE notification, as the assumption states.
3. **`./gradlew test --tests 'ch.sonensei.cockpit.core.push.*'`: the tests exist and use WireMock as the distributor.** Whether they pass is evidence the parent worker must record with `tasks.py verify`. The task file does not record it yet, which is expected before `done`. `fixes.md` reports 49 tests passing on the pre-rebase revision.

## Other checks (no findings)

- **Security and data exposure:**
  - Endpoint URLs are never logged. `UnifiedPushClient` turns exceptions into `PushDeliveryException(simpleName)`, the dispatcher logs only exception types (tested), and `WebPushKeys.toString` hides the key values.
  - Redirects are not followed (tested).
  - The server POSTs to an owner-supplied URL. This is limited to the authenticated single owner, the response body is never returned, and the assumption explicitly allows LAN http endpoints. It is not a finding.
- **Architecture:**
  - The mail module depends only on the core `PushMessage` event type, not on push services. This fits the rule that modules talk only through services and events.
  - Packages follow the `api`/`domain`/`infra` layout.
- **Resilience:**
  - The 404/410 removal of an in-flight registration cannot delete a newer registration: it is guarded by id and endpoint (tested, including unregister followed by re-register).
  - Shutdown drops later pushes without an exception (tested).
- **Tests:** they cover normal, edge (instance id length bounds, endpoint length 2048, record-size boundary, padded base64url, short coordinates) and failure paths, including the scheduler end to end and rollback suppression.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
