---
title: UnifiedPush for Android
status: done
owner: dell26/agent-1
deps: [and-reminders, unifiedpush-server, unifiedpush-android-client]
touches: [android/app/src/main/java/ch/sonensei/cockpit/push,android/app/src/test/java/ch/sonensei/cockpit/push]
prio: 3
lease_until: 
subtasks: unifiedpush-server, unifiedpush-android-client
releases: 1
extended: 
hold: 
---
## Goal
Push via a self-hosted distributor (ntfy).

## Acceptance criteria
- [x] App registers with UnifiedPush; server sends reminder/new-mail pushes; fallback to periodic sync
  - verified (2026-10-27 00:10+0100 by dell26/agent-1): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.push.*' -> 129 passed, 0 failed (UnifiedPushWeb*/UnifiedPushService registrations, PushDispatcher reminder+new-mail pushes to a WireMock distributor, WebPushCryptoRfc8291 encryption); cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*' --tests 'ch.sonensei.cockpit.reminders.ReminderShowFiredByServerTest' -> 108 passed (PushRegistrarTest registration/unregistration + 'no distributor installed means no registration, only the periodic sync' fallback, PushServerContractTest/PushRegistrationKeysContractTest pin the server payload+key format, PushReminderDeliveryTest/PushMessageHandlerTest reminder+new-mail handling). Real device + ntfy run NOT done (none available here; see assumption)
- [x] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*'` passes, covering message handling (Robolectric/Compose UI tests for screens)
  - verified (2026-10-27 00:10+0100 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*' (run together with ReminderShowFiredByServerTest, createDebugUnitTestCoverageReport, ktlintCheck) at 419fb4a18 -> BUILD SUCCESSFUL, push.*: 101 tests 0 failures (Robolectric: PushInAppTest, PushAndroidAdaptersTest; message handling: PushMessageHandlerTest 12, PushReminderDeliveryTest 22); push has no screens so no Compose UI test applies; task-scoped line coverage 189/197 (95.9%)

## Notes
- split by dell26/agent-2: into unifiedpush-server, unifiedpush-android-client. Nothing implemented yet: neither the server push API nor the Android client exists. Split into server (registrations + sending) and Android client; this task then only integrates and verifies. Once they are done, this task is only integration: merge what is left on wip/unifiedpush-android (if anything), verify the acceptance criteria end to end, done.
- hold by Peter Gürtler: test
- ASSUMPTION (2026-10-26 23:02+0100 by dell26/agent-2): No device/emulator/ntfy in the agent environment: end-to-end verified by combining server tests (WireMock distributor, RFC 8291 encryption, reminder+mail pushes) with Android tests (registration, PUT contract via generated client, message handling) plus a new app-side contract test pinning the server payload format; a manual on-device run with ntfy is left to the owner
- ASSUMPTION (2026-10-26 23:34+0100 by dell26/agent-2): Reminder push now loads the pushed reminder by id (GET /reminders/{id}) and shows it once via ReminderScheduler.showFiredByServer (small addition in reminders/, outside touches); a redelivered push after a later sync may re-post (replace) the notification
- extended by dell26/agent-2: Implementation, fixes (f0d873129) and fixer report done; push.* and reminders.ReminderShowFiredByServerTest pass. Left: code changed after review, so re-run test-writer (write reviews/unifiedpush-android/tests.md incl. coverage) and code-reviewer on final code; verify criteria #1 (combined server+android evidence, see assumption) and #2; write quality.json; run .taskcheck; done.
- release by dell26/agent-2: Implementation complete; push.* tests pass (gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.push.*', last commit 3865daa14 adds 2 delivery tests). reviews/unifiedpush-android has review.md (final review at 6f074d17b) and fixes.md. LEFT: (1) run test-writer on final code -> reviews/unifiedpush-android/tests.md incl. coverage; (2) re-run code-reviewer if test-writer changes code beyond 6f074d17b's reviewed code (3865daa14 is test-only, check freshness per docs/QUALITY.md); (3) tasks.py verify #1 (combined server WireMock/RFC8291 tests + Android registration/handling tests, see assumption) and #2; (4) write quality.json, run sh scripts/check.sh, done.
- done by dell26/agent-1: `sh scripts/check.sh` passed in 67s on f5179a2c0; criteria verified 2/2
