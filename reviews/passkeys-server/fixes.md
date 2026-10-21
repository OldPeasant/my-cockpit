# Fix Report

Date: 2026-10-20

Task: passkeys-server - Passkeys (WebAuthn) backend

Scope: findings CR-1..CR-4 of `reviews/passkeys-server/review.md`, as directed by the parent worker (changes limited to `core/auth/passkey` main and test sources, the passkey row of `docs/ARCHITECTURE.md`, and regenerated `api/openapi.yaml` + `web/src/app/api/generated/`; `deploy/` untouched).

## Fixed findings

### CR-1 — Shipped deployment never passes the passkey relying-party config; request-based origin is wrong behind Caddy

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/api/PasskeyRelyingParties.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/passkey/PasskeyRelyingPartiesTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/passkey/PasskeyConfiguredRelyingPartyTests.kt` (constructor call only)

Problem:
Without `cockpit.auth.passkey.*` (which the deploy compose files do not forward) the server derived the accepted origin from the request URL. Behind Caddy, which terminates TLS, that is `http://<domain>`, while browsers sign `https://<domain>`, so every passkey ceremony failed in production.

Fix:
`PasskeyRelyingParties` now also reads `cockpit.domain` (env `COCKPIT_DOMAIN`, which `deploy/docker-compose.yml` and `docker-compose.e2e.yml` already pass to the server).
- RP ID: `rp-id`, else `cockpit.domain`, else the request host.
- Origins: `origins`, else (domain set) `https://<domain>` plus, only when the request host equals the domain (case-insensitive), `https://<domain>[:port]` and `http://<domain>[:port]` with the request's port. Without a domain: the request origin, as before.

The KDoc explains why this is safe: the host must equal the configured domain, and browsers run WebAuthn only in secure contexts or on localhost. The Android origin still needs explicit `origins`. New tests cover these cases:
- domain behind a TLS proxy;
- a non-default HTTPS port (`localhost:18443`);
- the e2e `http://localhost:<port>`, including an upper-case Host;
- a foreign host, which gets only `https://<domain>`;
- `rp-id` overriding the domain;
- `origins` overriding the domain;
- precedence over the domain.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.passkey.*'` — Pass

### CR-2 — ARCHITECTURE.md claims the unconfigured default is fine for e2e although the e2e stack runs on an IP

Files changed:
- `docs/ARCHITECTURE.md` (passkey row of the environment table only)

Problem:
The doc said the request-based default was fine for e2e. But `run-e2e.mjs` uses `127.0.0.1`, and browsers reject an IP address as RP ID.

Fix:
The row now describes the `COCKPIT_DOMAIN` defaults. It says that e2e passkey tests must use `http://localhost:<port>`, not `127.0.0.1`. It also says that the Android origin needs `COCKPIT_AUTH_PASSKEY_ORIGINS` set explicitly, and that forwarding it in `deploy/` is a follow-up task.

Validation:
- Manual re-read of the row; `sh scripts/check.sh` — Pass

### CR-3 — Unauthenticated option requests can evict in-progress registration ceremonies

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/domain/PasskeyCeremonies.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/passkey/PasskeyCeremoniesTests.kt`

Problem:
A single shared pool of 256 ceremonies evicted the oldest ceremony of any kind. The public authentication-options endpoint could therefore evict an owner's pending registration.

Fix:
Registration and authentication ceremonies are now kept in separate bounded `LinkedHashMap`s, each holding at most `MAX_PENDING` (256). Eviction only drops the oldest ceremony of the same kind. `finish` looks in both maps; ids are random, and the existing kind checks in `PasskeyService` are unchanged. Two new tests check that authentication churn (2 × `MAX_PENDING`) never evicts a pending registration, and that registrations are bounded separately with the oldest dropped first.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.passkey.*'` — Pass

### CR-4 — OpenAPI advertises `application/problem+json` with a `PasskeyResponse` body for the 201 of `finishPasskeyRegistration`

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/api/PasskeyController.kt`
- `api/openapi.yaml` (regenerated)
- `web/src/app/api/generated/fn/passkeys/finish-passkey-registration.ts` (regenerated)

Problem:
The 201 response had no explicit content, so springdoc listed `PasskeyResponse` under both `application/json` and `application/problem+json`.

Fix:
The 201 `@ApiResponse` now declares `content = [Content(mediaType = APPLICATION_JSON_VALUE, schema = Schema(implementation = PasskeyResponse::class))]`. The problem responses are unchanged. After regeneration, the `problem+json` entry for 201 is gone, and the generated web client now sends `accept: 'application/json'` for this call instead of `application/problem+json`.

Validation:
- `cd server && UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'` — Pass (regenerated)
- `cd web && npm run api:gen` — Pass
- `cd server && ./gradlew check` (includes `OpenApiContractTest` without the flag) — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.passkey.*'` — BUILD SUCCESSFUL; 6 test classes, 53 tests, 0 failures, 0 errors, 0 skipped

Full test suite:
- `cd server && ./gradlew check` — BUILD SUCCESSFUL (ktlint, detekt/lint and all server tests)

Lint/type-check/build:
- `cd server && ./gradlew ktlintFormat` applied; lint is part of `./gradlew check` — Pass

`.taskcheck`:
- `sh scripts/check.sh` — Pass ("all checks passed"; web: 22 files, 321 tests passed)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.

Notes for the parent worker:
- `deploy/` was not changed. Forwarding `COCKPIT_AUTH_PASSKEY_*` (needed for the Android origin) remains the follow-up task the parent creates.
- `docs/ARCHITECTURE.md` still lists `COCKPIT_DOMAIN` as "not yet used" in the row below the passkey row. That row was left unchanged as instructed; the parent may want to adjust it.
