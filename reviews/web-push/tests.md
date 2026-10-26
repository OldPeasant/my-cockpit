# Test Report

Date: 2026-10-26

Task: web-push - Web Push notifications

Scope: task base b7f26df50cdcf04a0fdd4d1302020a6922a193c9 (merge-base with origin/main, given by the parent worker) .. HEAD 4d5191e93485727f1ae5e14106f77f54979bd820 on branch main (final revision), web-push commits in `git log b7f26df..HEAD`, excluding `tasks/` and `reviews/`. The previous version of this report covered the code up to c12b8f3c40536c8866330ea851dec04008c947f8 (migration renamed to `server/src/main/resources/db/migration/core/V202610261401__core_push_web.sql` after the collision with main's UnifiedPush V202610261400; `api/openapi.yaml` and `web/src/app/api/generated/` regenerated); its numbers were taken on the same code before a rebase (then d6c48f31d). Since then: fc05f1682 (tasks/ only) and fix commit d52adc426 (see "Follow-up: duplicate OpenAPI tag" below), which changes one production line (`PushController.kt` `@Tag(name = "Push")` to `@Tag(name = "Web Push")`) plus the regenerated `api/openapi.yaml` and web client (`fn/push/*` moved to `fn/web-push/*`, `functions.ts`, `index.ts`), and commit 4d5191e93 (test only: the regression test below in `server/src/test/kotlin/ch/sonensei/cockpit/core/push/PushWebTests.kt`, committed by the parent). Not part of this task (same package, from main): UnifiedPush*, PushDispatcher, PushMessage, WebPushEncryption, WebPushKeys and their tests. Uncommitted: only this report and `reviews/web-push/review.md` (code-reviewer's); the working-tree PushWebTests.kt equals 4d5191e93 (`git diff 4d5191e93 -- PushWebTests.kt` is empty).

## Follow-up: duplicate OpenAPI tag (.taskcheck failure on c12b8f3c4)

- Failure: `.taskcheck` failed in the Android step. `openApiGenerate` validates `api/openapi.yaml` and rejected it with "attribute tags.Push is repeated": this task's `PushController` and main's `UnifiedPushController` both declared `@Tag(name = "Push")` (with different descriptions), so springdoc emitted two top-level tags named Push. The server's OpenApiContractTest and the web `api:check` did not catch it, as they only compare the committed file with a freshly generated one.
- Fix (d52adc426): the Web Push controller has its own tag "Web Push"; contract and web client regenerated. `grep -rn "fn/push/" web/src` now finds only UnifiedPush's register/unregister functions in the generated `index.ts`/`functions.ts`; no web source imports the old Web Push paths (web check passes, see below).
- Regression guard added: PushWebTests `the Web Push API has its own OpenAPI tag and no tag name is repeated`. It reads `/v3/api-docs` from the running app (same Spring context as the other PushWebTests, so no extra context start), asserts that the top-level tag names are distinct and that "Web Push" occurs once, and that the three Web Push operations (GET vapid-public-key, PUT and DELETE subscriptions) carry exactly the "Web Push" tag. On c12b8f3c4 it would fail (two "Push" tags, no "Web Push"). Checking tag uniqueness for the whole contract would arguably belong in `core/web/OpenApiContractTest.kt`, which is outside this task's test folder; the guard here covers the whole tag list anyway, so any future duplicate fails it too.
- Compile fix by the parent: the first version of the test did not compile, because on tools.jackson (Jackson 3) `JsonNode` the `.map {}` call did not resolve to `Iterable.map` ("distinct" receiver mismatch). The parent changed both `contract[...].map` calls to `.toList().map` (the idiom already used elsewhere in PushWebTests) and nothing else, committed as 4d5191e93. Assertions are unchanged.
- Run (parent, on 4d5191e93): `server/gradlew -p server ktlintCheck test --tests 'ch.sonensei.cockpit.core.push.PushWebTests'` -> ktlint OK, 19 tests, 0 failures, including the new test. Gradle cannot run from this sub-agent.

Requirements:
- tasks/web-push.md acceptance criteria 1-3 and its recorded assumptions (push follows the reminder switch; SW skips showing when a visible tab exists; keys/subscriptions not in backup; https endpoints; ?lang= texts; no pushsubscriptionchange, CR-2; CR-1 deferred to push-session-binding; migration renamed after the rebase)
- SPEC.md section 4.6 (as referenced by the task)
- docs/ARCHITECTURE.md "Coverage", AGENTS.md, docs/QUALITY.md

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/push/PushWebTests.kt` (one test added, plus constant `WEB_PUSH_TAG`; compile fix by the parent; committed in 4d5191e93)

Committed task tests (reviewed, otherwise unchanged): server `core/push/PushDeliveryTests.kt`, `PushNotificationRelayTests.kt`, `PushPropertiesTests.kt`, `PushTestBrowser.kt`, `PushWebTests.kt`, `WebPushCryptoEdgeCaseTests.kt`, `WebPushCryptoRfc8291Tests.kt`, `WebPushCryptoTests.kt`; web `browser-push.spec.ts`, `push-subscription.service.spec.ts`, `provide-reminder-notifications.spec.ts`, `push-logout.spec.ts`, `push-sw.spec.ts`, `reminder-notifications-toggle.spec.ts`, `core/auth/auth.service.spec.ts`.

## Tests added or updated

Count: 1 (PushWebTests `the Web Push API has its own OpenAPI tag and no tag name is repeated`, see the follow-up section above; parent-run on 4d5191e93: pass)

Integration after the earlier rebase was checked, and no further test was judged necessary:

- Both migrations apply together: every Spring Boot test class in `core.push` (web-push PushWebTests/PushDeliveryTests/PushNotificationRelayTests and UnifiedPush PushEndToEndTests/UnifiedPush*Tests) starts against the same Testcontainers Postgres with all Flyway migrations, so V202610261400 (push_unifiedpush_registration) and V202610261401 (push_vapid_key, push_subscription) are applied in order on each run. A version collision or a table clash would fail the context, and all 107 tests passed. The two migrations create disjoint tables and constraint names.
- The relay and the dispatcher coexist: PushDeliveryTests and PushEndToEndTests use the same `@Import(FakeItemResolversConfiguration, MutableClockTestConfiguration)` and so share one application context that holds both PushNotificationRelay (Web Push, listens to ClientNotification) and PushDispatcher (UnifiedPush, listens to ReminderFiredEvent/PushMessage). In PushDeliveryTests `a fired reminder is pushed to every subscription with VAPID and aes128gcm` and in PushEndToEndTests `a reminder fired by the scheduler is pushed with its id...`, the same `ReminderService.fireDue()` triggers both listeners, and each test sees exactly its own channel's requests (`shouldHaveSize 2` for the browsers, one request for the device). Both listeners hand the work to their own executor and catch failures, so one cannot block the other. A combined "one reminder reaches a browser and a device" test would add little beyond this, and this helper cannot run Gradle to check a new Kotlin test, so none was added.
- Web: no web production code changed in the rebase. The regenerated client is checked by `api:check` in `npm run check` (pass), and the push specs that call the generated push functions pass (focused run below).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: VAPID keys generated at first start (regenerated after a restore emptied them; startup survives an undecryptable or non-PKCS8 stored key; concurrent generation ends with one pair) | PushWebTests: `startup generates the key pair when there is none`, `the VAPID key pair exists after startup and stays the same`, `a restore that emptied the table gets a new key pair on next use`, `instances generating the key pair at the same time all end up with the one stored`, `a stored key pair the master key can't decrypt does not fail startup and is kept`, `a stored private key that is not a PKCS8 key does not fail startup` | parent: `server/gradlew -p server test --tests 'ch.sonensei.cockpit.core.push.*' jacocoTestReport` | Pass (parent-run; PushWebTests 18/18) |
| AC1: subscriptions API (subscribe/upsert, validation, unsubscribe via DELETE with body, login required, VAPID public key endpoint) | PushWebTests: `subscribing stores the subscription...`, `subscribing keeps id, created time and version...`, `invalid subscriptions are rejected with 400`, `endpoints up to 2048 characters...`, `endpoints without a host...`, `rejected keys name the field...`, `keys in padded standard base64 are accepted`, `unsubscribing removes the subscription...`, `unsubscribing needs an endpoint...`, `the API needs a login`, `unsubscribing needs a login`, `the same browser subscribing twice at the same time ends with one subscription` | parent gradle command above | Pass (parent-run) |
| AC1: reminder/new-mail events are pushed (VAPID + aes128gcm, urgency, after commit only, size limit) | PushDeliveryTests (13), PushNotificationRelayTests (5), WebPushCryptoTests (5), WebPushCryptoRfc8291Tests (3), WebPushCryptoEdgeCaseTests (9), PushPropertiesTests (2) | parent gradle command above | Pass (parent-run, 0 failures) |
| AC1: unsubscribe on 410 (and 404); other statuses keep the subscription | PushDeliveryTests: `a subscription the push service reports gone is removed, others stay`, `a server error or a redirect of the push service keeps the subscription and is not followed` | parent gradle command above | Pass (parent-run) |
| AC1: service worker shows reminder/new-mail pushes (EN/DE, tags shared with in-page notifications, skips when a tab is visible, click opens/focuses the item inside its scope) | push-sw.spec.ts: 20 tests (`push service worker`, `push service worker, edge cases`) | `npm --prefix web run test -- --watch=false --include='src/app/core/notifications/**/*.spec.ts' --include='src/app/core/auth/auth.service.spec.ts'` | Pass (this run) |
| AC2: `./gradlew test --tests 'ch.sonensei.cockpit.core.push.*'` passes (Testcontainers) | all server core/push tests (web-push and UnifiedPush classes together) | parent: `server/gradlew -p server test --tests 'ch.sonensei.cockpit.core.push.*' jacocoTestReport` | Pass (parent-run on c12b8f3c4-equivalent code: 107 tests in 16 classes, 0 failures, 0 errors, 0 skipped, from server/build/test-results/test XML, 2026-10-26 16:52:49Z to 16:53:24Z; re-run by the parent on d52adc426 together with OpenApiContractTest: 108 tests, 0 failures). PushWebTests incl. the new test re-run by the parent on 4d5191e93: 19 tests, 0 failures; not run by this helper |
| AC3: Vitest tests cover the subscription flow (sync with server key, replace old-key subscription, unsubscribe, failures silent, ordering of quick on/off, follows switch + permission + login, logout unsubscribes with timeout, new login re-subscribes, toggle hint) | push-subscription.service.spec.ts, browser-push.spec.ts, provide-reminder-notifications.spec.ts, push-logout.spec.ts, auth.service.spec.ts push tests, reminder-notifications-toggle.spec.ts push hint tests | focused command above | Pass (this run: 9 files, 151 tests) |
| AC3: `cd web && npm run check` passes | whole web check (lint, api:check, build, test:ci) | parent: `npm --prefix web run check` | Pass (parent-run on d52adc426 with the regenerated `fn/web-push/*` client: 184 files, 3857 tests, includes api:check) |
| Integration: both push migrations apply together; Web Push relay and UnifiedPush dispatcher coexist | all core.push Spring Boot classes on one Testcontainers DB; PushDeliveryTests and PushEndToEndTests in one shared context | parent gradle command above | Pass (parent-run) |
| Integration: the contract stays valid for the Android generator (unique tag names; Web Push has its own tag) | OpenApiContractTest (committed file equals generated); Android `openApiGenerate` spec validation; new PushWebTests `the Web Push API has its own OpenAPI tag and no tag name is repeated` | parent on d52adc426: `server/gradlew -p server test --tests 'ch.sonensei.cockpit.core.push.*' --tests '*OpenApiContractTest'`; `android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug`; parent on 4d5191e93: `server/gradlew -p server ktlintCheck test --tests 'ch.sonensei.cockpit.core.push.PushWebTests'` | Pass (parent-run on d52adc426: 108 tests, 0 failures; Android BUILD SUCCESSFUL. Parent-run on 4d5191e93: ktlint OK, PushWebTests 19 tests, 0 failures, including the new test) |

## Focused test execution

Command:

`npm --prefix /home/eric/work/my-cockpit-agents/agent-1/web run test -- --watch=false --include='src/app/core/notifications/**/*.spec.ts' --include='src/app/core/auth/auth.service.spec.ts'`

Result:

Pass (9 test files, 151 tests; run 2026-10-26 17:59:07 +01:00 on d6c48f31d, the pre-rebase equivalent of c12b8f3c4). d52adc426 changed no web source outside `web/src/app/api/generated/`; the parent's `npm --prefix web run check` on d52adc426 runs these specs again (pass).

Server focused on d52adc426 (parent-run): `server/gradlew -p server test --tests 'ch.sonensei.cockpit.core.push.*' --tests '*OpenApiContractTest'` -> 108 tests, 0 failures (107 core.push + OpenApiContractTest, which compares the committed api/openapi.yaml with the generated one). Then on 4d5191e93 (parent-run, after the parent's compile fix of the new test): `server/gradlew -p server ktlintCheck test --tests 'ch.sonensei.cockpit.core.push.PushWebTests'` -> ktlint OK, 19 tests, 0 failures, including `the Web Push API has its own OpenAPI tag and no tag name is repeated`. 4d5191e93 changes only this test file, so the 108-test result on d52adc426 still holds for all other classes.

Server focused, earlier run (parent-run): `server/gradlew -p server test --tests 'ch.sonensei.cockpit.core.push.*' jacocoTestReport` -> pass. Web-push classes: PushWebTests 18, PushDeliveryTests 13, PushNotificationRelayTests 5, PushPropertiesTests 2, WebPushCryptoTests 5, WebPushCryptoRfc8291Tests 3, WebPushCryptoEdgeCaseTests 9 (55). UnifiedPush classes from main in the same run: PushEndToEndTests 5, PushDispatcherTests 5, PushDispatcherFailureTests 7, UnifiedPushServiceTests 12, UnifiedPushUpsertTests 3, UnifiedPushWebTests 5, UnifiedPushWebEdgeCaseTests 8, WebPushEncryptionTests 3, WebPushEncryptionEdgeCaseTests 4 (52). Total 107, 0 failures.

## Full test execution

Command:

`npm --prefix web run check` (parent-run on d52adc426), `android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug` (parent-run on d52adc426), `npm --prefix web run coverage` (parent-run on the c12b8f3c4-equivalent code)

Result:

Pass. `npm run check` on d52adc426: lint, api:check, build, 184 test files, 3857 tests pass. Android on d52adc426: BUILD SUCCESSFUL, including `openApiGenerate` with spec validation, the step that failed on c12b8f3c4. `npm run coverage`: 184 files, 3857 tests passed. The full server suite was not run by this helper (Gradle cannot run from sub-agents).

## .taskcheck

Result:

Fail on c12b8f3c4 (parent-run): Android `openApiGenerate` rejected `api/openapi.yaml` with "attribute tags.Push is repeated". Fixed in d52adc426; the failing Android step passes on d52adc426 (parent-run above). A full `sh scripts/check.sh` on the final revision 4d5191e93 is not run by this helper (Gradle steps cannot run here); the parent worker runs it next.

## Bugs found

None open. No production defects found in the final code (production unchanged since d52adc426; 4d5191e93 is test-only). The duplicate OpenAPI tag "Push" (found by `.taskcheck`, not by this helper) is fixed in d52adc426 and now guarded by PushWebTests `the Web Push API has its own OpenAPI tag and no tag name is repeated`. The two defects found in earlier runs (non-PKCS8 VAPID key escaping the startup guard; no re-subscribe after a new login in the same tab) are fixed and covered by PushWebTests `a stored private key that is not a PKCS8 key does not fail startup`, provide-reminder-notifications.spec.ts `leaves push alone while logged out and subscribes again on the next login` and push-logout.spec.ts `subscribes again after logging in again, as the switch is still on`, all passing.

Observation for the parent, not a defect of this task's criteria: the two push channels in core/push differ in payload privacy. The Web Push message carries the item title (PushDeliveryTests checks `item.title`), always encrypted with the browser's aes128gcm keys. The UnifiedPush message deliberately carries only kind and id (`never its item's title`). This matches each task's design and the Web Push payload is always end-to-end encrypted. The duplicate RFC 8291 encryption (WebPushCrypto vs WebPushEncryption) is already recorded as an assumption and in push-session-binding.

## Coverage

Status: verified for c12b8f3c4 (measured on its pre-rebase equivalent d6c48f31d); carried over to d52adc426 and the final revision 4d5191e93 (test-only) without a new run. Both reports were written after the code commit they measure (17:56:11 +01:00): JaCoCo at 17:57:25, Vitest at 17:57:20. push-sw.js is not instrumented (see below). d52adc426 only changes the string value of the `@Tag` annotation on `PushController` (no executable code; annotations carry no JaCoCo counters) and regenerated files excluded from scope, so the parent reports the numbers as unchanged; this helper did not re-measure. The new PushWebTests test can only add hits (it calls springdoc, not task code paths beyond PushController's mapping metadata). If a fresh number on d52adc426 is required, the parent reruns `server/gradlew -p server test --tests 'ch.sonensei.cockpit.core.push.*' jacocoTestReport`.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-push; base b7f26df50cdcf04a0fdd4d1302020a6922a193c9 (from the parent); measured d6c48f31d7db0bfb302423ed64329269a2cbbf5d (code identical to c12b8f3c4 after the rebase); final HEAD 4d5191e93485727f1ae5e14106f77f54979bd820 (production last changed in d52adc426dc3bacc2076ecdaa556309527b02b88, annotation-only; 4d5191e93 test-only; not re-measured); file scope from `git diff --stat b7f26df..HEAD` excluding tasks/ and reviews/, checked against the task's `touches` and criteria. UnifiedPush files in core/push come from main (already in the base) and are excluded.
- Source/test snapshot identifier (including uncommitted inputs): HEAD d6c48f31d7db0bfb302423ed64329269a2cbbf5d, clean working tree (no uncommitted source or test changes). The task's production and test files are identical to the previously reported revision 8bc4d528 (empty `git diff 8bc4d528 HEAD` over them).
- Exact included files: web `web/src/app/core/notifications/browser-push.ts`, `push-subscription.service.ts`, `provide-reminder-notifications.ts`, `reminder-notifications-toggle.ts`, `reminder-notifications-toggle.html`, `web/src/app/core/auth/auth.service.ts`; server `core/push/domain/PushService.kt`, `VapidKeyService.kt`, `PushEntities.kt`, `core/push/api/PushController.kt`, `core/push/infra/WebPushSender.kt`, `PushNotificationRelay.kt`, `WebPushCrypto.kt`, `PushRepositories.kt`. Renamed: `V202610261400__core_push.sql` to `V202610261401__core_push_web.sql` (content unchanged; SQL, not instrumentable; applied by every Testcontainers test). Excluded: `web/src/app/api/generated/**` and `api/openapi.yaml` (generated), `web/src/app/core/notifications/index.ts` (re-exports only, absent from the report), `web/src/app/core/notifications/testing.ts` (test fake used only by specs), `web/src/locale/*.xlf`, `deploy/*`, `docs/*`, `application.properties` (config). Missing instrumentation: `web/public/push-sw.js` (push-sw.spec.ts loads it through `new Function`, so v8 coverage does not attribute it; behavior covered by 20 tests). No deleted files.
- Provider/version, command, working directory, and test selection: server: JaCoCo via Gradle, `server/gradlew -p server test --tests 'ch.sonensei.cockpit.core.push.*' jacocoTestReport` (parent-run, all core.push test classes, 107 tests). Web: Vitest 5.0.5 + @vitest/coverage-v8, `npm --prefix web run coverage` (parent-run, all 184 spec files).
- Run start/completion time and exit status: server tests 2026-10-26 16:52:49Z to 16:53:24Z, report written 17:57:25 +01:00, exit 0 per the parent, 107 tests 0 failures per the result XML. Web coverage report written 17:57:20 +01:00, 3857 tests passed, exit 0 per the parent.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 674b5dbbb3485a4094b2e826a83a37a15d7df3317d098e03eac7e705e14135b2; `web/coverage/web/coverage-final.json` sha256 454800dfb839b48e4c5fa3df7d80dfa465c516cada1271b6a43b812334a6fea5 (counters read from `web/coverage/web/coverage-summary.json` of the same run). Extraction script: `.tmp/cov.py`.
- Evidence outputs are fresh and inputs stayed unchanged: yes for the measured revision (reports written after its commit time, clean tree then).
- Final run after latest test additions and production fixes: no. d52adc426 (`@Tag` name only) and the new PushWebTests test (4d5191e93) came after the coverage run; executable task code is unchanged, so the numbers below still describe it, but they were not regenerated.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. The per-file numbers are identical to the previous report, as expected for unchanged code.

Web (Vitest v8; provider summary counters):

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/core/notifications/push-subscription.service.ts` | 54/55 (98.2%) | 23/23 (100%) | 12/13 (92.3%) | 44/44 (100%) |
| `web/src/app/core/notifications/browser-push.ts` | 23/23 (100%) | 10/10 (100%) | 8/8 (100%) | 21/21 (100%) |
| `web/src/app/core/notifications/provide-reminder-notifications.ts` | 10/10 (100%) | 4/4 (100%) | 3/3 (100%) | 8/8 (100%) |
| `web/src/app/core/notifications/reminder-notifications-toggle.ts` | 36/36 (100%) | 16/17 (94.1%) | 8/8 (100%) | 30/30 (100%) |
| `web/src/app/core/notifications/reminder-notifications-toggle.html` | 25/25 (100%) | 8/8 (100%) | 1/1 (100%) | 20/20 (100%) |
| `web/src/app/core/auth/auth.service.ts` | 48/48 (100%) | 20/20 (100%) | 16/16 (100%) | 39/39 (100%) |
| `web/public/push-sw.js` | not instrumented | not instrumented | not instrumented | not instrumented |
| Web aggregate (summed counters, 6 measured files; push-sw.js unmeasured) | 196/197 (99.5%) | 81/82 (98.8%) | 48/49 (98.0%) | 162/162 (100%) |

Server (JaCoCo; "Statements" = instructions, Functions = methods):

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/.../core/push/domain/PushService.kt` | 410/429 (95.6%) | 39/42 (92.9%) | 10/11 (90.9%) | 66/70 (94.3%) |
| `server/.../core/push/domain/VapidKeyService.kt` | 190/197 (96.4%) | 3/4 (75.0%) | 11/12 (91.7%) | 32/33 (97.0%) |
| `server/.../core/push/domain/PushEntities.kt` | 76/87 (87.4%) | N/A | 11/14 (78.6%) | 19/21 (90.5%) |
| `server/.../core/push/api/PushController.kt` | 90/90 (100%) | N/A | 14/14 (100%) | 25/25 (100%) |
| `server/.../core/push/infra/WebPushSender.kt` | 167/167 (100%) | 10/10 (100%) | 11/11 (100%) | 34/34 (100%) |
| `server/.../core/push/infra/PushNotificationRelay.kt` | 123/125 (98.4%) | 2/2 (100%) | 9/10 (90.0%) | 24/25 (96.0%) |
| `server/.../core/push/infra/WebPushCrypto.kt` | 704/713 (98.7%) | 33/38 (86.8%) | 15/15 (100%) | 94/94 (100%) |
| `server/.../core/push/infra/PushRepositories.kt` | N/A (interfaces, no counters) | N/A | N/A | N/A |
| Server aggregate (summed counters, 7 measured files; PushRepositories.kt has no counters) | 1760/1808 (97.3%) | 87/96 (90.6%) | 81/87 (93.1%) | 294/302 (97.4%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/src/app/core/notifications/push-subscription.service.ts:101` (`next.catch(() => undefined)` callback in enqueue) | Keeps the queue alive after a rejected task | Not reachable: every queued task catches its own errors; the queue surviving failures is tested (`keeps working after a failed call`). Justified gap. |
| `web/src/app/core/notifications/reminder-notifications-toggle.ts:30` (one branch at the class declaration) | None (compiler-emitted decorator/field branch) | Justified gap. |
| `web/public/push-sw.js` (whole file, not instrumented) | AC1 service worker display/click behavior | Behavior covered by 20 push-sw.spec.ts tests (reminder/mail texts, EN/DE, tags, visible-tab skip, click focus/navigate/open, scope guard). Coverage not measurable without changing the loader or the coverage config. |
| `PushService.kt:62` (`checkNotNull` null path), `:73` (`count()`), `:99` (status below 200 branch), `:108-110` (InterruptedException path), `:139` (null vs empty host branch) | Defensive paths | Non-material: upsert-then-find cannot return null; count() is a helper; interrupt and sub-200 statuses are defensive. Validation of hostless endpoints is tested. Justified gaps. |
| `VapidKeyService.kt:27` (`toString`), `:79` (rethrow when the racing row is still missing) | Debug string; concurrent-generation fallback | Concurrent generation is tested (`instances generating the key pair at the same time...`); the remaining rethrow is defensive. Justified gap. |
| `PushEntities.kt:25,43,47` (JPA field/setter accessors) | None | Justified gap. |
| `PushNotificationRelay.kt:56` (`destroy()`) | Executor shutdown | Shutdown behavior tested via `notifications after shutdown are dropped without an error`; the Spring callback line itself is not hit there. Justified gap. |
| `WebPushCrypto.kt:110` (salt-size `require` failure), `:196` (off-field coordinate branches) | Input checks | Off-curve and out-of-field points are tested (`public keys must be uncompressed points...`); the remaining short-circuit branches and the internal salt check are defensive. Justified gap. |

## Unable to test

- `.taskcheck` and all Gradle suites: cannot run from this sub-agent; server and Android results above are the parent's runs. Follow-up: parent runs `sh scripts/check.sh` on the final revision 4d5191e93.
- A combined Kotlin test "one fired reminder reaches a Web Push browser and a UnifiedPush device" was not added: coexistence is already exercised by the shared context (see "Tests added or updated"), and a new Kotlin test could not be run here. Optional, no follow-up required.
- Real browser Push API / real push service delivery (Chrome/Firefox push services): not testable in jsdom or unit tests; covered by fakes (BROWSER_PUSH fake, WireMock push service, RFC 8291 test vectors and a decrypting test browser). The criteria do not name an E2E run.
- push-sw.js coverage numbers: not measurable with the current loader and config (see Coverage).

## Summary

Tests added; no open production defects. The `.taskcheck` failure on c12b8f3c4 (duplicate OpenAPI tag "Push" rejected by the Android generator) is fixed in d52adc426, and one regression test was added to PushWebTests (`the Web Push API has its own OpenAPI tag and no tag name is repeated`). The parent fixed a Jackson 3 compile error in it (`.toList().map`) and committed it as 4d5191e93, the final revision; there ktlint passes and PushWebTests runs 19 tests with 0 failures (parent-run). On d52adc426 the parent's runs pass: core.push plus OpenApiContractTest (108 tests), Android ktlintCheck/testDebugUnitTest/lintDebug with spec validation, and `npm run check` (3857 tests). Coverage numbers come from the c12b8f3c4-equivalent code and were not regenerated for the annotation-only fix. `.taskcheck` was not run by this helper.
