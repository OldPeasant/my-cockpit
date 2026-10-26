---
title: Server side of UnifiedPush (registrations + push sending)
status: done
owner: dell26/agent-2
deps: [and-reminders]
prio: 3
parent: unifiedpush-android
lease_until: 
extended: 
releases: 1
---
## Goal
Part of `unifiedpush-android` (UnifiedPush for Android), which was split.
Goal: The server stores UnifiedPush endpoints per device and POSTs a small push to them when a reminder fires or new mail arrives.

## Acceptance criteria
- [x] `PUT /api/v1/push/unifiedpush/{instanceId}` (body: endpoint URL, optional WebPush p256dh/auth keys) and `DELETE` of it, for the logged-in user; Flyway migration under `db/migration/core`; backup/restore covered or the reason recorded; `api/openapi.yaml` regenerated
  - verified (2026-10-26 17:06+0100 by dell26/agent-2): test-writer+code-reviewer: gradlew test --tests 'ch.sonensei.cockpit.core.push.*' (UnifiedPushWebTests/WebEdgeCase/ServiceTests/UpsertTests: PUT/DELETE 204, idempotent, 400 validation, per user) -> pass; migration core/V202610261400__core_push_unifiedpush.sql applied in tests; backup not covered by recorded assumption (device re-registers); api/openapi.yaml + web client regenerated (matches controller per review)
- [x] On `ReminderFiredEvent` and on new mail (`NewMailAnnouncer`), the server sends a push to every registered endpoint (payload: kind + id only, no item contents; RFC 8291 encryption when keys are given); endpoints answering 404/410 are removed; failures never break the event flow
  - verified (2026-10-26 17:06+0100 by dell26/agent-2): gradlew test --tests 'ch.sonensei.cockpit.core.push.*' --tests '*NewMailAnnouncerTests' -> 63 pass: PushDispatcherTests/EndToEnd (reminder+mail push to WireMock distributor, payload kind+id, RFC 8291 encryption incl. RFC example vector + round-trip decrypt), 404/410 removes endpoint, PushDispatcherFailureTests (failures logged, event flow unaffected)
- [x] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.push.*'` passes (MockWebServer or similar as the distributor)
  - verified (2026-10-26 17:07+0100 by dell26/agent-2): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.push.*' -> BUILD SUCCESSFUL, 52 tests, 0 failures (WireMock as distributor); full ./gradlew check also passed (5046 tests)

## Notes
- Work done on `unifiedpush-android` before the split is on `origin/wip/unifiedpush-android` (if it exists): merge it first if your part builds on it.
- ASSUMPTION (2026-10-26 15:15+0100 by dell26/agent-2): UnifiedPush registrations are not backed up (like sessions and Android devices): an endpoint belongs to one app installation, which registers again after a restore; documented in the migration.
- ASSUMPTION (2026-10-26 15:15+0100 by dell26/agent-2): Push payload is {"kind":"reminder"|"mail","id":<uuid>}: the reminder id, or for mail the newest new unread message id (mail_message.id); a push is only sent when the SSE new-mail notification is (Inbox, notifyNewMail).
- ASSUMPTION (2026-10-26 15:15+0100 by dell26/agent-2): PUT/DELETE /push/unifiedpush/{instanceId} answer 204, are idempotent and need no If-Match (a device-owned registration, not a user-edited entity); instanceId must match [A-Za-z0-9._-]{1,128}; endpoint must be an absolute http(s) URL (http allowed for a LAN ntfy).
- ASSUMPTION (2026-10-26 15:15+0100 by dell26/agent-2): Pushes are sent after commit on a virtual-thread executor owned by PushDispatcher, with TTL 1 day and Urgency high (reminder) / normal (mail); no VAPID, since UnifiedPush distributors (ntfy) do not need it; the web-push task can reuse WebPushEncryption.
- extended by dell26/agent-2: Implementation + tests committed (7d7ddecfc); push tests pass (gradlew test --tests 'ch.sonensei.cockpit.core.push.*'). review.md exists (0 P0-P2, 2 P3: CR-1 concurrent PUT race -> 500/409, CR-2 async failure-path test asserts too early) but is for the pre-test-writer revision. Left: test-writer did not write reviews/unifiedpush-server/tests.md (rerun it, with coverage); fixer for CR-1/CR-2; re-run test-writer+code-reviewer on final code; tasks.py verify 1-3; sh scripts/check.sh; write quality.json; done.
- release by dell26/agent-2: Implementation complete; CR-1/CR-2 fixed (fixes.md); latest review.md (on 03cf4bc4c, patch-equal to 978cd1888) has 0 findings. All 52 tests in ch.sonensei.cockpit.core.push.* pass at a93132915 (incl. new UnifiedPushUpsertTests, committed but not yet in a review). Left: run test-writer so it writes reviews/unifiedpush-server/tests.md with task-scoped coverage (it failed to write it twice; give it a narrow prompt); re-run code-reviewer because UnifiedPushUpsertTests.kt was added after the review; tasks.py verify 1-3; sh scripts/check.sh; write quality.json (fixer was run for CR-1/CR-2); done.
- ASSUMPTION (2026-10-26 17:06+0100 by dell26/agent-2): UnifiedPush registrations are not tied to auth_device: revoking a device does not delete its registration; it keeps getting content-free pushes (kind+id) until the distributor answers 404/410 or the app unregisters. Accepted as P3 (review CR-1); linking them is follow-up work.
- done by dell26/agent-2: `sh scripts/check.sh` passed in 342s on 7f9127b93; criteria verified 3/3
