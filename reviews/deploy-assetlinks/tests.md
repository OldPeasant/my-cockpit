# Test Report

Date: 2026-10-26

Task: deploy-assetlinks - Serve /.well-known/assetlinks.json for the Android app (passkeys)

Scope: `git diff d026fa584..d38ea3748 -- . ':!tasks' ':!reviews'` (base d026fa58453d71cceae56be0a10aff88dfc98e0d, HEAD d38ea3748b56af33ec605deea86eacc4ab287ac8; task commits 3e2f9b84b implementation, 362116997 tests, d38ea3748 fix for CR-2). Branch main, no uncommitted source or test changes.

Requirements:
- tasks/deploy-assetlinks.md (3 acceptance criteria, 3 recorded assumptions)
- reviews/deploy-assetlinks/review.md (CR-1 test gap, CR-2 config ownership) and fixes.md
- docs/ARCHITECTURE.md (env table, "Coverage"), AGENTS.md project notes

## Test files changed

None in this run. The existing task tests already cover the criteria, the assumptions and the review findings; no new test was needed (see "Requirement coverage"). Test files reviewed:

- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/passkey/AndroidAssetLinksTests.kt` (11 tests)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/passkey/AssetLinksWebTests.kt` (2 tests)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/passkey/AssetLinksDefaultWebTests.kt` (4 tests)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/passkey/PasskeyRelyingPartiesTests.kt` (12 tests, extended by the task)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/auth/passkey/PasskeyConfiguredRelyingPartyTests.kt` (adapted to the new constructor)

## Tests added or updated

Count: 0

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `GET /.well-known/assetlinks.json` returns the configured statement as `application/json` without login | `AssetLinksWebTests.assetlinks json names the app and its fingerprints, without login`; `AssetLinksDefaultWebTests.without fingerprints assetlinks json is an empty JSON list, public and cacheable` | `./gradlew test --tests 'ch.sonensei.cockpit.core.auth.passkey.*' ktlintCheck` | Pass |
| AC1 security: only GET/HEAD opened; POST/PUT/DELETE still need login; the API under a similar path stays protected; hidden from OpenAPI | `AssetLinksDefaultWebTests.state-changing requests to assetlinks json still need a login`, `the public rule does not open the API under a similar path`, `assetlinks json is not part of the published API contract` | same | Pass |
| AC1 format: Digital Asset Links JSON names (`relation`, `target.namespace`, `package_name`, `sha256_cert_fingerprints`), upper-case colon fingerprints | `AndroidAssetLinksTests.a statement serializes with the Digital Asset Links JSON names`, `fingerprints with or without colons, in any case, are normalized ...`, `blank entries are ignored, duplicates ...` | same | Pass |
| AC2: package and fingerprints configurable via env (`COCKPIT_AUTH_PASSKEY_ANDROID_FINGERPRINTS` / `_PACKAGE`, default package) | `AndroidAssetLinksTests.the deployment's COCKPIT_AUTH_PASSKEY_ANDROID_ environment variables are read`; `PasskeyRelyingPartiesTests.the deployment's COCKPIT_DOMAIN and COCKPIT_AUTH_PASSKEY_ environment variables are read` | same | Pass |
| AC2: forwarded in the deploy compose, documented in deploy/README.md | Inspection of `deploy/docker-compose.yml` (both vars forwarded, package default `ch.sonensei.cockpit`), `deploy/.env.example`, `deploy/README.md` | none (no test infrastructure for deploy files; `docker compose config` was not permitted in this session) | Not run: compose rendering not executed; verified by reading only |
| AC3: apk-key-hash origin of each fingerprint accepted by the passkey RP config | `AndroidAssetLinksTests.the apk-key-hash origin is the unpadded base64url ...` (independently computed values, incl. `-`/`_`, no padding); `PasskeyRelyingPartiesTests.the app origin of each configured fingerprint is added to explicitly configured origins`, `the app origin of a fingerprint is added to the request-derived default origins`; `AssetLinksWebTests.the relying party accepts the app origin of each configured fingerprint, besides the web origin` | same | Pass |
| AC3 "documented" | Inspection of deploy/README.md and docs/ARCHITECTURE.md env table | none | Not testable (documentation) |
| Assumption: invalid fingerprint fails startup | `AndroidAssetLinksTests.anything that is not 32 bytes of hex is rejected ...`, `an invalid fingerprint fails the construction ...`, `an invalid fingerprint in the environment fails the startup`; `PasskeyRelyingPartiesTests.an invalid android fingerprint fails the relying party configuration` | same | Pass |
| CR-2 fix: fingerprints with a blank package fail startup; no fingerprints means no statement and no origin | `AndroidAssetLinksTests.fingerprints with a blank package fail the construction ...`, `a blank package in the environment with fingerprints fails the startup`, `without fingerprints there is no statement and no origin, also without a package` | same | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.auth.passkey.*' ktlintCheck`

Result:

Pass. BUILD SUCCESSFUL; 89 tests in 10 passkey classes, 0 failures, 0 errors, 0 skipped (AndroidAssetLinksTests 11, AssetLinksWebTests 2, AssetLinksDefaultWebTests 4, PasskeyRelyingPartiesTests 12, PasskeyConfiguredRelyingPartyTests 4, others unchanged). ktlint clean.

## Full test execution

Command:

`cd server && ./gradlew test jacocoTestReport`

Result:

Pass. BUILD SUCCESSFUL in 7m 44s; 4661 tests, 0 failures, 0 errors, 0 skipped (from `server/build/test-results/test/*.xml`). Web and Android were not run: the task changes no web or Android code.

## .taskcheck

Result:

Pass for the affected parts: `sh scripts/check.sh tfp server` -> "all checks passed (tfp server)". The web and android parts were not run here (no changes in them); the parent runs the full `.taskcheck` through `tasks.py done`.

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. Method: added/modified line numbers from `git diff -U0 d026fa584..HEAD -- server/src/main` intersected with JaCoCo `<line>` entries (executable lines only) of the same report; a line counts as covered when `ci > 0`. Result: 43/43 changed executable lines covered (100%), branches on changed lines 14/14 (100%). (SecurityConfiguration 2/2, AndroidAssetLinks 33/33 lines 14/14 branches, AssetLinksController 6/6, PasskeyRelyingParties 2/2.)

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: deploy-assetlinks; base d026fa58453d71cceae56be0a10aff88dfc98e0d; HEAD d38ea3748b56af33ec605deea86eacc4ab287ac8; commits 3e2f9b84b, 362116997, d38ea3748 as given by the parent and confirmed by `git log d026fa584..HEAD` (exactly these three).
- Source/test snapshot identifier (including uncommitted inputs): HEAD d38ea3748; tree `server/src/main/.../core/auth/passkey` 2370abeeace7c7bf88e6a75e4a35d9259c387900, `server/src/test/.../core/auth/passkey` fae1b6b166148d9daf4599c2eb62dd102ef8da68. No uncommitted source/test changes (only `reviews/deploy-assetlinks/review.md` was modified in the working tree, not a measured input).
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/api/AndroidAssetLinks.kt` (added), `.../passkey/api/AssetLinksController.kt` (added), `.../passkey/api/PasskeyRelyingParties.kt` (modified), `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/infra/SecurityConfiguration.kt` (modified). Excluded: `deploy/.env.example`, `deploy/README.md`, `deploy/docker-compose.yml`, `docs/ARCHITECTURE.md` (configuration/documentation, not instrumentable); test files. No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (version as configured in `server/build.gradle.kts`, Java 21.0.12.1); `./gradlew test jacocoTestReport` in `server/`; full server test suite.
- Run start/completion time and exit status: started about 2026-10-26T06:41:30+01:00, report written 2026-10-26T06:49:12+01:00; BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, SHA-256 f8734378843a775379b6901d682346b89f9c049074963d9d3e4d6b720ab4233d.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The test task executed (not up-to-date) because the previous run used a filter; HEAD, trees and working tree status were the same before and after.
- Final run after latest test additions and production fixes: yes (no tests added; measured on d38ea3748, which includes the CR-2 fix).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. "Statements" are JaCoCo instructions.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/api/AndroidAssetLinks.kt` | 246/246 (100%) | 14/14 (100%) | 15/15 (100%) | 33/33 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/api/AssetLinksController.kt` | 21/21 (100%) | N/A (0 branches) | 2/2 (100%) | 6/6 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/passkey/api/PasskeyRelyingParties.kt` | 196/205 (95.6%) | 21/22 (95.5%) | 3/3 (100%) | 30/30 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/infra/SecurityConfiguration.kt` | 361/363 (99.4%) | 2/4 (50%) | 11/11 (100%) | 50/50 (100%) |
| Aggregate (summed counters, all 4 scoped files measured, none missing) | 824/835 (98.7%) | 37/40 (92.5%) | 31/31 (100%) | 119/119 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `PasskeyRelyingParties.kt:50` (1 of 2 branches, 9 instructions: `requireNotNull(requestOrigin.host)` failure) | Pre-existing code, not changed by this task; a servlet request always has a host | Justified gap, outside task scope |
| `SecurityConfiguration.kt:141` (2 of 4 branches: CSRF token attribute absent / null token) | Pre-existing `CsrfCookieFilter`, not changed by this task | Justified gap, outside task scope |

## Unable to test

- Rendering of `deploy/docker-compose.yml` (`docker compose config`) to prove the two env vars reach the server container: not run, the command was not permitted in this session. The file was checked by reading: both `COCKPIT_AUTH_PASSKEY_ANDROID_FINGERPRINTS` and `COCKPIT_AUTH_PASSKEY_ANDROID_PACKAGE` (default `ch.sonensei.cockpit`) are forwarded. The server-side env binding is tested. Follow-up: the parent can run `docker compose -f deploy/docker-compose.yml --env-file deploy/.env.example config` for AC2 evidence.
- Real Credential Manager / Google Digital Asset Links verifier against a deployed host: needs a device and a public HTTPS host; not testable here.
- Documentation parts of AC2/AC3 (README, ARCHITECTURE.md): not testable, checked by reading; they match the code.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. No additional tests were necessary: the existing tests cover all three criteria, the recorded assumptions and the review findings CR-1/CR-2, and every changed executable line and branch is covered.
