# Test Report

Date: 2026-10-26

Task: web-push - Web Push notifications

Scope: task base c7b117400574a0d13bfd17d594887e9b36241d14 (given by the parent worker) .. HEAD 378941749d6f5e39e6eb15c28f1ef60d4aae1d2a on branch main, every task commit in that range excluding `tasks/` and `reviews/`, plus one uncommitted test change made by this run (`web/src/app/core/notifications/reminder-notifications-toggle.spec.ts`). Includes the parent's two fixes: VapidKeyService.current() wrapping GeneralSecurityException (non-PKCS8 stored key), and provide-reminder-notifications.ts gating the push effect on AuthService.loggedIn().

Requirements:
- tasks/web-push.md acceptance criteria 1-3 and its recorded assumptions (push follows the reminder switch; SW skips showing when a visible tab exists; keys/subscriptions not in backup; https endpoints; ?lang= texts; no pushsubscriptionchange, CR-2)
- SPEC.md section 4.6 (as referenced by the task)
- docs/ARCHITECTURE.md "Coverage", AGENTS.md, docs/QUALITY.md

## Test files changed

- `web/src/app/core/notifications/reminder-notifications-toggle.spec.ts` (this run; uncommitted)

Already committed by the earlier test-writer run and the parent (reviewed here, not changed): server `core/push/PushDeliveryTests.kt`, `PushNotificationRelayTests.kt`, `PushPropertiesTests.kt`, `PushTestBrowser.kt`, `PushWebTests.kt`, `WebPushCryptoEdgeCaseTests.kt`, `WebPushCryptoRfc8291Tests.kt`, `WebPushCryptoTests.kt`; web `browser-push.spec.ts`, `push-subscription.service.spec.ts`, `provide-reminder-notifications.spec.ts`, `push-logout.spec.ts`, `push-sw.spec.ts`, `reminder-notifications-toggle.spec.ts`, `core/auth/auth.service.spec.ts`.

## Tests added or updated

Count: 1

- `ReminderNotificationsToggle > stops promising closed-tab notifications once switched off or push ends` (new): the "also show while My Cockpit is closed" hint disappears when push becomes inactive with the switch on, and is replaced by the in-app hint when the user switches off while push still reports active (the unsubscribe is queued and not finished yet). Guards the template order `!checked()` before `pushActive()`.

No server Kotlin tests were added or changed by this run.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: VAPID keys generated at first start (and regenerated after a restore emptied them; startup survives an undecryptable or non-PKCS8 stored key; concurrent generation ends with one pair) | PushWebTests: `startup generates the key pair when there is none`, `the VAPID key pair exists after startup and stays the same`, `a restore that emptied the table gets a new key pair on next use`, `instances generating the key pair at the same time all end up with the one stored`, `a stored key pair the master key can't decrypt does not fail startup and is kept`, `a stored private key that is not a PKCS8 key does not fail startup` | parent: `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.push.*' jacocoTestReport` | Pass (parent-run: 55 tests, 0 failures) |
| AC1: subscriptions API (subscribe/upsert, validation, unsubscribe via DELETE with body, login required, VAPID public key endpoint) | PushWebTests: `subscribing stores the subscription...`, `subscribing keeps id, created time and version...`, `invalid subscriptions are rejected with 400`, `endpoints up to 2048 characters...`, `endpoints without a host...`, `rejected keys name the field...`, `keys in padded standard base64 are accepted`, `unsubscribing removes the subscription...`, `unsubscribing needs an endpoint...`, `the API needs a login`, `unsubscribing needs a login`, `the same browser subscribing twice at the same time ends with one subscription` | parent gradle command above | Pass (parent-run) |
| AC1: reminder/new-mail events are pushed (VAPID + aes128gcm, urgency, after commit only, size limit) | PushDeliveryTests (13 tests), PushNotificationRelayTests (5), WebPushCryptoTests, WebPushCryptoRfc8291Tests, WebPushCryptoEdgeCaseTests, PushPropertiesTests | parent gradle command above | Pass (parent-run) |
| AC1: unsubscribe on 410 (and 404); other statuses keep the subscription | PushDeliveryTests: `a subscription the push service reports gone is removed, others stay`, `a server error or a redirect of the push service keeps the subscription and is not followed` | parent gradle command above | Pass (parent-run) |
| AC1: service worker shows reminder/new-mail pushes (EN/DE, tags shared with in-page notifications, skips when a tab is visible, click opens/focuses the item inside its scope) | push-sw.spec.ts: 20 tests (`push service worker`, `push service worker, edge cases`) | `npm --prefix web run test -- --watch=false --include='src/app/core/notifications/**/*.spec.ts' --include='src/app/core/auth/auth.service.spec.ts'` | Pass |
| AC2: `./gradlew test --tests 'ch.sonensei.cockpit.core.push.*'` passes | all server core/push tests | parent-run (sub-agents cannot run Gradle here) | Pass (parent-run: 55 tests, 0 failures); not re-run by this test-writer |
| AC3: Vitest tests cover the subscription flow (sync with server key, replace old-key subscription, unsubscribe, failures silent, ordering of quick on/off, follows switch + permission + login, logout unsubscribes with timeout, new login re-subscribes, toggle hint) | push-subscription.service.spec.ts (19), browser-push.spec.ts (7), provide-reminder-notifications.spec.ts (7), push-logout.spec.ts (2), auth.service.spec.ts push tests (4), reminder-notifications-toggle.spec.ts (push hint tests, incl. the new one) | focused command above | Pass (9 files, 151 tests) |
| AC3: `cd web && npm run check` passes | whole web check (api:check, lint, e2e:typecheck, test:ci, build) | `npm --prefix web run check` | Pass (exit 0; 184 files, 3857 tests; build emits the existing initial-bundle budget warning only) |

## Focused test execution

Command:

`npm --prefix /home/eric/work/my-cockpit-agents/agent-1/web run test -- --watch=false --include='src/app/core/notifications/**/*.spec.ts' --include='src/app/core/auth/auth.service.spec.ts'`

Result:

Pass (9 test files, 151 tests). Note: running the same specs via `npm exec --prefix web -- ng test ...` fails push-sw.spec.ts only because `npm exec` keeps the caller's cwd and the spec reads `public/push-sw.js` relative to `process.cwd()` (documented in the spec as "ng test runs from the web/ folder"); not a product defect.

## Full test execution

Command:

`npm --prefix /home/eric/work/my-cockpit-agents/agent-1/web run check`

Result:

Pass (exit 0; lint clean; 184 test files, 3857 tests; build succeeds).

Server: `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.push.*' jacocoTestReport` run by the parent worker: 55 tests, 0 failures. The full server suite was not run by this test-writer (Gradle cannot run from sub-agents).

## .taskcheck

Result:

Not run: `sh scripts/check.sh` includes the Gradle server and Android steps, which cannot run from this sub-agent. The parent worker must run it.

## Bugs found

None. No production defects found by this run. (The two defects found earlier, the non-PKCS8 VAPID key escaping the startup guard and no re-subscribe after a new login in the same tab, are already fixed by the parent and covered by PushWebTests `a stored private key that is not a PKCS8 key does not fail startup`, provide-reminder-notifications.spec.ts `leaves push alone while logged out and subscribes again on the next login` and push-logout.spec.ts `subscribes again after logging in again, as the switch is still on`, all passing.)

Observation, not a task defect: during the first `npm run coverage` run, `src/app/app.spec.ts > App > builds every navigation section, so none shows the coming soon placeholder` timed out at 5000 ms under v8 instrumentation; it passed alone without coverage (43/43) and in the two later full runs (coverage and check). Load-dependent timing, outside this task's files.

## Coverage

Status: verified for web (fresh run after the latest test addition); server numbers are from the parent's fresh JaCoCo run on the same production code (this run added no server tests). push-sw.js is unmeasured (see below).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-push; base c7b117400574a0d13bfd17d594887e9b36241d14 and task commits as given by the parent; HEAD 378941749d6f5e39e6eb15c28f1ef60d4aae1d2a; file scope from `git diff --stat c7b1174..HEAD` excluding tasks/ and reviews/, checked against the task's `touches` and criteria.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 378941749d6f5e39e6eb15c28f1ef60d4aae1d2a plus uncommitted `web/src/app/core/notifications/reminder-notifications-toggle.spec.ts` sha256 b04e6413187d75785d437e56d6fb825d0afb0e496ee3eea3a73232a8f23c6e16 (only modified file; `git status --porcelain` checked before and after the run).
- Exact included files: web `web/src/app/core/notifications/browser-push.ts`, `push-subscription.service.ts`, `provide-reminder-notifications.ts`, `reminder-notifications-toggle.ts`, `reminder-notifications-toggle.html`, `web/src/app/core/auth/auth.service.ts`; server `core/push/domain/PushService.kt`, `VapidKeyService.kt`, `PushEntities.kt`, `core/push/api/PushController.kt`, `core/push/infra/WebPushSender.kt`, `PushNotificationRelay.kt`, `WebPushCrypto.kt`, `PushRepositories.kt`. Excluded: `web/src/app/api/generated/**` (generated client), `web/src/app/core/notifications/index.ts` (re-exports only, no executable statements, absent from the report), `web/src/app/core/notifications/testing.ts` (test fake used only by specs), `web/src/locale/*.xlf`, `api/openapi.yaml` (generated), `deploy/*`, `docs/*`, `application.properties` (config), `V202610261400__core_push.sql` (not instrumentable; exercised by the Testcontainers tests). Missing instrumentation: `web/public/push-sw.js` (loaded by push-sw.spec.ts through `new Function`, so v8 coverage does not attribute it; behavior covered by 20 tests). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: web: Vitest 5.0.5 + @vitest/coverage-v8 5.0.5, `npm --prefix /home/eric/work/my-cockpit-agents/agent-1/web run coverage` (cwd web/, all specs). Server: JaCoCo via Gradle, `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.push.*' jacocoTestReport` (parent-run, core/push tests only).
- Run start/completion time and exit status: web final run 2026-10-26T17:43:33+01:00 to 17:44:50+01:00, all 3857 tests passed. An earlier web coverage run at 17:36 failed (app.spec.ts timeout, no report written) and was discarded; a second run at 17:38 passed and was superseded by the final run after the new test. Server: parent run, report written 2026-10-26 17:35:37 +01:00, 55 tests, 0 failures.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 a064dafa6901fbe56c287a66e7a9b6e171520fbfe670d13661688bd3f31f3176, `web/coverage/web/coverage-summary.json` sha256 6a25e546e6f89237d8ef8ff56e7486b72d1bd3253e7600eaae85b5066632e98a; `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 beeafea9bffab1a05878c556c9b552a82b3f8213f313c2d1739d1b475363b7b1 (summary also in `.tmp/server-push-coverage.txt`).
- Evidence outputs are fresh and inputs stayed unchanged: yes; web report rewritten by the final run (mtime 17:44:50), working tree unchanged during it apart from the one spec above, which was in place before the run.
- Final run after latest test additions and production fixes: yes for web. Server: the parent's run is on the final production code (after both fixes); this run did not change server tests.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

Web (Vitest v8; "Statements" = v8 statements, Lines = v8 lines):

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
| Server aggregate (summed counters, 7 measured files) | 1760/1808 (97.3%) | 87/96 (90.6%) | 81/87 (93.1%) | 294/302 (97.4%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/src/app/core/notifications/push-subscription.service.ts:101` (`next.catch(() => undefined)` callback in enqueue) | Keeps the queue alive after a rejected task | Not reachable: every queued task catches its own errors; the queue's survival after failures is tested (`keeps working after a failed call`). Justified gap. |
| `web/src/app/core/notifications/reminder-notifications-toggle.ts:30` (one branch at the class declaration) | None (compiler-emitted decorator/field branch) | Justified gap. |
| `web/public/push-sw.js` (whole file, not instrumented) | AC1 service worker display/click behavior | Behavior covered by 20 push-sw.spec.ts tests (reminder/mail texts, EN/DE, tags, visible-tab skip, click focus/navigate/open, scope guard). Coverage not measurable without changing the loader or coverage config. Payload shapes checked against server NewMailNotification / ReminderResponse by inspection. |
| `PushService.kt:62` (`checkNotNull` null path), `:73` (`count()`), `:99` (status below 200 branch), `:108-110` (InterruptedException path), `:139` (null vs empty host branch) | Defensive paths | Non-material: upsert-then-find cannot return null; count() is a helper; interrupt and sub-200 statuses are defensive. Validation of hostless endpoints is tested. Justified gaps. |
| `VapidKeyService.kt:27` (`toString`), `:79` (rethrow when the racing row is still missing) | Debug string; concurrent-generation fallback | Concurrent generation is tested (`instances generating the key pair at the same time...`); the remaining rethrow is defensive. Justified gap. |
| `PushEntities.kt:25,43,47` (JPA field/setter accessors) | None | Justified gap. |
| `PushNotificationRelay.kt:56` (`destroy()`) | Executor shutdown | Shutdown behavior tested via `notifications after shutdown are dropped without an error`; the Spring callback line itself is not hit in that test. Justified gap. |
| `WebPushCrypto.kt:110` (salt-size `require` failure), `:196` (off-field coordinate branches) | Input checks | Off-curve / out-of-field points are tested (`public keys must be uncompressed points...`); remaining short-circuit branches and the internal salt check are defensive. Justified gap. |

## Unable to test

- `.taskcheck` and all Gradle suites: cannot run from this sub-agent; server results above are the parent's run. Follow-up: parent runs `sh scripts/check.sh`.
- Real browser Push API / real push service delivery (Chrome/Firefox push services): not testable in jsdom/unit tests; covered by fakes (BROWSER_PUSH fake, WireMock push service, RFC 8291 test vectors and a decrypting test browser). The criteria do not name an E2E run.
- push-sw.js coverage numbers: not measurable with the current loader/config (see Coverage).

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. One web test was added (toggle push hint); focused web tests (151) and `npm run check` (3857 tests) pass; server core/push tests (55) pass per the parent's run. `.taskcheck` not run by this helper.
