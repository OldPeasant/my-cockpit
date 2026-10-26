---
title: Server side of UnifiedPush (registrations + push sending)
status: in_progress
owner: dell26/agent-2
deps: [and-reminders]
prio: 3
parent: unifiedpush-android
lease_until: 2026-10-26T15:12:12Z
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
- ASSUMPTION (2026-10-26 15:15+0100 by dell26/agent-2): UnifiedPush registrations are not backed up (like sessions and Android devices): an endpoint belongs to one app installation, which registers again after a restore; documented in the migration.
- ASSUMPTION (2026-10-26 15:15+0100 by dell26/agent-2): Push payload is {"kind":"reminder"|"mail","id":<uuid>}: the reminder id, or for mail the newest new unread message id (mail_message.id); a push is only sent when the SSE new-mail notification is (Inbox, notifyNewMail).
- ASSUMPTION (2026-10-26 15:15+0100 by dell26/agent-2): PUT/DELETE /push/unifiedpush/{instanceId} answer 204, are idempotent and need no If-Match (a device-owned registration, not a user-edited entity); instanceId must match [A-Za-z0-9._-]{1,128}; endpoint must be an absolute http(s) URL (http allowed for a LAN ntfy).
- ASSUMPTION (2026-10-26 15:15+0100 by dell26/agent-2): Pushes are sent after commit on a virtual-thread executor owned by PushDispatcher, with TTL 1 day and Urgency high (reminder) / normal (mail); no VAPID, since UnifiedPush distributors (ntfy) do not need it; the web-push task can reuse WebPushEncryption.
