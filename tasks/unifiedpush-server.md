---
title: Server side of UnifiedPush (registrations + push sending)
status: in_progress
owner: dell26/agent-2
deps: [and-reminders]
prio: 3
parent: unifiedpush-android
lease_until: 2026-10-26T14:26:33Z
---
## Goal
Part of `unifiedpush-android` (UnifiedPush for Android), which was split.
Goal: The server stores UnifiedPush endpoints per device and POSTs a small push to them when a reminder fires or new mail arrives.

## Acceptance criteria
- [ ] `PUT /api/v1/push/unifiedpush/{instanceId}` (body: endpoint URL, optional WebPush p256dh/auth keys) and `DELETE` of it, for the logged-in user; Flyway migration under `db/migration/core`; backup/restore covered or the reason recorded; `api/openapi.yaml` regenerated
- [ ] On `ReminderFiredEvent` and on new mail (`NewMailAnnouncer`), the server sends a push to every registered endpoint (payload: kind + id only, no item contents; RFC 8291 encryption when keys are given); endpoints answering 404/410 are removed; failures never break the event flow
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.push.*'` passes (MockWebServer or similar as the distributor)

## Notes
- Work done on `unifiedpush-android` before the split is on `origin/wip/unifiedpush-android` (if it exists): merge it first if your part builds on it.
