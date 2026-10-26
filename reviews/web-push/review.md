# Code Review

Date: 2026-10-26

Task: web-push - Web Push notifications

Scope: `git diff b7f26df50cdcf04a0fdd4d1302020a6922a193c9..4d5191e93` (merge-base with origin/main to HEAD), excluding `tasks/` and `reviews/` (committed changes only). This is a follow-up re-review of the final revision 4d5191e93. The previous version of this report covered the code up to c12b8f3c4. Since then there are two code commits: d52adc426 (the OpenAPI tag rename) and 4d5191e93 (a regression test for it). Both are reviewed in full below.

Requirements:
- tasks/web-push.md (acceptance criteria and recorded assumptions, including the CR-1 deferral and the migration rename of 2026-10-26 17:55/17:56)
- tasks/push-session-binding.md (follow-up task that takes over CR-1)
- SPEC.md §4.6 (optional Web Push), §4.1 (sessions), §4.2 (API conventions), §14 (backup)
- docs/ARCHITECTURE.md, AGENTS.md project notes (module packages, migrations, logging, generated files: regenerate, never merge by hand)
- RFC 8291 / RFC 8188 (aes128gcm), RFC 8292 (VAPID), RFC 8030 (push protocol)

Git range:
- Branch: main
- HEAD: 4d5191e93485727f1ae5e14106f77f54979bd820
- Base/start: b7f26df50cdcf04a0fdd4d1302020a6922a193c9
- Commits reviewed: 14 task commits outside `tasks/` (1a20b213e to 4d5191e93), plus TFP state commits that only touch `tasks/`
- Uncommitted changes: only `reviews/web-push/review.md` and `reviews/web-push/tests.md` (review artifacts, no code)

Summary: 0 P0, 0 P1, 1 P2 (CR-1, deferred to push-session-binding), 0 P3

## Changes in d52adc426, checked

- Cause: `PushController` (this task) and main's `UnifiedPushController` both declared `@Tag(name = "Push")` with different descriptions. springdoc wrote two `tags` entries named `Push`, and the Android `openApiGenerate` refused the spec ("attribute tags.Push is repeated").
- Server: `PushController.kt:27` now uses `name = "Web Push"`. The description is unchanged. `UnifiedPushController.kt:31` keeps `"Push"`. No other server code refers to the tag name.
- Contract: `api/openapi.yaml` now has exactly one `name: Push` tag (UnifiedPush, line 46) and one `name: Web Push` tag (line 92). The three Web Push operations (PUT and DELETE `/api/v1/push/subscriptions`, GET `/api/v1/push/vapid-public-key`) are tagged `Web Push`. The UnifiedPush operations stay tagged `Push`. Paths, operation ids, schemas and responses are unchanged, so the HTTP contract is the same. The tag moved to the end of the list, which is springdoc's generation order, not a hand edit.
- Web client: the generated files moved from `fn/push/` to `fn/web-push/` without content changes (rename, 0 lines changed). `functions.ts` and `index.ts` re-export the same symbols (`putPushSubscription`, `deletePushSubscription`, `getVapidPublicKey` and their `$Params`) from the new paths. `fn/push/` now holds only the UnifiedPush functions. No code outside `api/generated/` imports a `fn/push/` path directly. `core/notifications/push-subscription.service.ts` imports from the generated index, so it needs no change.
- Android: the generator now produces separate API classes per tag. No Android source refers to either generated push API class, so nothing else is affected. The parent reports `ktlintCheck testDebugUnitTest lintDebug` successful on this revision, which runs `openApiGenerate` against the new spec.
- Generated files were regenerated (`updateOpenApi`, `npm run api:gen`), not merged by hand, as AGENTS.md requires. The parent reports `OpenApiContractTest` and `npm --prefix web run check` (including `api:check`) passing, which confirms the committed contract and client match the server.

## Changes in 4d5191e93, checked

- Test only: `server/src/test/kotlin/ch/sonensei/cockpit/core/push/PushWebTests.kt` gets the test "the Web Push API has its own OpenAPI tag and no tag name is repeated" and the constant `WEB_PUSH_TAG`. No production code changes.
- The test reads the live contract from `/v3/api-docs` through MockMvc and asserts that:
  - no tag name in `tags` appears twice;
  - `Web Push` appears exactly once;
  - each of the three Web Push operations (GET `vapid-public-key`, PUT and DELETE `subscriptions`) carries exactly the tag `Web Push`.
- It checks behaviour, not implementation details, and would have failed on c12b8f3c4: springdoc emitted two `tags` entries named `Push` there, so the list was not equal to its distinct values. The uniqueness check covers every tag in the contract, so a later module that reuses a tag name is caught too. That is the exact failure that broke the Android generation.
- It follows the file's conventions: kotest matchers with `withClue`, the injected Jackson `JsonMapper`, and the existing `BASE` and `SUBSCRIPTIONS` constants. The parent reports `ktlintCheck` OK and 19 of 19 `PushWebTests` passing on this revision.

## Changes between d6c48f31d and c12b8f3c4, checked (previous round)

- Task code was unchanged by the rebase. The only differences were:
  - the migration rename `V202610261400__core_push.sql` to `V202610261401__core_push_web.sql`, with identical content;
  - the regenerated `api/openapi.yaml` and `web/src/app/api/generated/`.
- Migrations: main's UnifiedPush task owns `V202610261400__core_push_unifiedpush.sql` (table `push_unifiedpush_registration`). This task's `V202610261401__core_push_web.sql` (tables `push_vapid_key`, `push_subscription`) sorts after it, so versions don't collide and table names don't overlap. The old file name was only ever on the task's wip branch, never on main, so no deployed Flyway history refers to it.

## Integration with the UnifiedPush code in core/push, checked

- Beans: no name or type conflicts. Web Push has `PushService`, `VapidKeyService`, `PushNotificationRelay`, `WebPushSender`, `PushConfiguration` and `PushProperties` (`cockpit.push.*`). UnifiedPush has `UnifiedPushService`, `PushDispatcher`, `UnifiedPushClient` and two `object`s (`WebPushEncryption`, `WebPushKeys`). The two controllers map different paths and now have distinct OpenAPI tags. UnifiedPush defines no `cockpit.push.*` properties.
- Events: the two do not send the same thing twice.
  - `PushNotificationRelay` listens to `ClientNotification` (`reminder`, `mail`) and sends the full notification to browser subscriptions.
  - `PushDispatcher` listens to `ReminderFiredEvent` and `PushMessage` and sends `{kind,id}` to UnifiedPush registrations.
  - `NewMailAnnouncer` publishes both a `ClientNotification` and a `PushMessage` for the same mail.
  - The two sets of targets are separate tables, so each device gets one push from its own channel. Both listeners use `@TransactionalEventListener(fallbackExecution = true)` and their own executors, so neither blocks the other or the publisher.
- Backup and restore: `RestoreService.wipeDatabase()` truncates every table except Flyway's history. That empties both Web Push tables after a restore, as the recorded assumption says. No backup contributor exports them, which is also as assumed.
- Duplicated RFC 8291 code: `infra/WebPushCrypto.encrypt` (this task) and `domain/WebPushEncryption.encrypt` (UnifiedPush) both implement aes128gcm encryption. Both are tested, `WebPushCrypto` against the RFC 8291 Appendix A vector. The duplication is recorded in the task assumption of 17:56, and its removal is a concrete note in `tasks/push-session-binding.md`: switch `PushService` to `WebPushEncryption` and keep the vector test. It came from the two tasks running in parallel, and the follow-up captures it adequately, so it is not reported as a separate finding.

## Earlier findings re-checked

- CR-1: deferred. See below. Neither d52adc426 nor 4d5191e93 touches it.
- CR-2 (no `pushsubscriptionchange`): still accepted as an assumption. Not reported again.
- CR-3, CR-4, CR-5 (fixed in an earlier round): d52adc426 changes only the tag name and generated files, and 4d5191e93 only adds a test, so the fixes still hold.

## Acceptance criteria

- AC 1 (VAPID keys at first start, subscriptions API, the service worker shows reminder and mail pushes, unsubscribe on 410): met. This was verified in earlier rounds. d52adc426 changes no behaviour, only the documentation tag, and 4d5191e93 adds only a test.
- AC 2 (`./gradlew test --tests 'ch.sonensei.cockpit.core.push.*'` with Testcontainers): the parent reports 108 tests and 0 failures on d52adc426 (`core.push.*` plus `*OpenApiContractTest`). This includes the UnifiedPush tests in the same package. On 4d5191e93, `PushWebTests` (19 tests, including the new tag test) passes with ktlint OK, as the parent reports.
- AC 3 (Vitest coverage of the subscription flow, `npm run check`): the specs are unchanged and cover the flow. The parent reports the check passing on d52adc426 (184 test files, 3857 tests).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [x] **CR-1 | Push subscriptions outlive a session that ends without an explicit logout (browser-session login, idle expiry)**: DEFERRED to task push-session-binding
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/push/domain/PushService.kt:47` (a subscription is not tied to a session); `web/src/app/core/notifications/provide-reminder-notifications.ts:26`
    - Category: Security
    - Problem: Only an explicit `AuthService.logout()` removes the browser's subscription. A session that ends because the browser closes (no "remember device") or after the 12 h idle limit leaves the subscription in place. The server keeps sending mail senders, subjects and reminder titles to that browser until it unsubscribes or its push service returns 410.
    - Why it matters: On a shared computer, personal data keeps appearing in notifications after the session ends.
    - Status: The parent deferred the fix. The deferral is adequately captured:
      - Assumption: `tasks/web-push.md` line 29 records the deferral, the reason (binding needs a session id in `CockpitPrincipal` in core/auth, outside this task's touches) and the behaviour until then.
      - Follow-up task: `tasks/push-session-binding.md` depends on web-push, has prio 2, and has testable acceptance criteria. These cover a session reference with `on delete cascade`, moving the subscription on re-subscribe, skipping or deleting subscriptions of expired sessions, and tests for logout, idle expiry and revoked sessions. Its touches list core/auth, core/push and the core migration folder.
      - Scope: neither the assumption nor the follow-up hides a different gap. The explicit-logout path is fixed and tested in this task.
    - Suggested fix: Implement push-session-binding as specified. No further change is required in web-push.

## P3 Low

None.

## Review conclusion

No material issues found. d52adc426 fixes the duplicate `Push` tag in the right place, the server annotation. The contract and web client were regenerated, not edited by hand. 4d5191e93 adds a meaningful regression test against repeated tag names. The HTTP contract and the symbols the web app imports are unchanged, and the Android generator now accepts the spec. CR-1 remains a valid P2. The parent deferred it explicitly, with a recorded assumption and a concrete follow-up task (push-session-binding), so it does not block completion. The task integrates cleanly with the UnifiedPush code in core/push: there are no bean, event, migration, table or OpenAPI tag conflicts.
