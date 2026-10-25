# Test Report

Date: 2026-10-25

Task: deploy-passkey-env - Forward COCKPIT_AUTH_PASSKEY_* to the server in deploy

Scope: second pass on the final revision. Base `c309e711af1498faadf41fa30512692361824e46`, HEAD
`2dc672c02116ba8a591220f71600fb3ecdd3e9ed`, task commits `git log c309e711..HEAD -- deploy docs`:
`727052f9` (the original change, first tested as `939dae22`/`32e2ee2d`) and `2dc672c0` (fix for review finding
CR-1: `deploy/README.md` now uses `apksigner verify --print-certs` and a new regex). Base and commits were given
by the parent worker. `git diff --name-only c309e711..HEAD` lists only `deploy/.env.example`, `deploy/README.md`,
`deploy/docker-compose.yml` and `docs/ARCHITECTURE.md`. No production source code (server/web/android) changed.
The working tree had no tracked changes; only `reviews/deploy-passkey-env/` is untracked.

Requirements:
- `tasks/deploy-passkey-env.md` (acceptance criteria and the recorded RP_NAME assumption)
- `reviews/deploy-passkey-env/review.md` (CR-1) and `fixes.md`
- `docs/ARCHITECTURE.md` (Environment variables, Coverage), `AGENTS.md`

## Test files changed

- None in the repository. There is no test harness for compose files or deploy docs (`scripts/check.sh` runs
  the TFP gate tests, server, web and android only), and adding one would be new test infrastructure. The
  criteria were checked with repeatable commands and Git-ignored scratch scripts:
  - `.tmp/tw-dpe/check_readme_filter.py` (new, this pass): extracts the exact `python3 -c` filter from the
    README and checks it, see below.
  - `.tmp/fix-dpe/test_readme_cmd.py` (written by the fixer, rerun here).
  - `.tmp/dpe/set.env`, `.tmp/dpe/absent.env` (from the first pass) and `deploy/.env.example`.

## Tests added or updated

Count: 0 repository tests. 1 scratch validation script (8 checks), see above.

Sample data (all fictitious): `set.env` has `COCKPIT_DOMAIN=cockpit.example.org`, placeholder secrets
`change-me`, `COCKPIT_AUTH_PASSKEY_RP_ID=example.org`,
`COCKPIT_AUTH_PASSKEY_ORIGINS=https://cockpit.example.org,android:apk-key-hash:FAKE_test_hash-0123456789abcdefghijklmnopq`,
`COCKPIT_AUTH_PASSKEY_RP_NAME=Test Cockpit`. `absent.env` has only `COCKPIT_DOMAIN`, `COCKPIT_MASTER_KEY` and
`POSTGRES_PASSWORD`. Certificate digests in the scripts are SHA-256 hashes of made-up strings.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1/AC2: compose forwards RP_ID / ORIGINS / RP_NAME to the server; set values pass through unchanged | compose config, set values | `docker compose -f deploy/docker-compose.yml --env-file .tmp/dpe/set.env config` (exit 0) -> `services.server.environment`: `COCKPIT_AUTH_PASSKEY_ORIGINS: https://cockpit.example.org,android:apk-key-hash:FAKE_test_hash-0123456789abcdefghijklmnopq`, `COCKPIT_AUTH_PASSKEY_RP_ID: example.org`, `COCKPIT_AUTH_PASSKEY_RP_NAME: Test Cockpit`; caddy and postgres do not get them | Pass |
| AC1: empty in `.env` -> RP_ID/ORIGINS empty, RP_NAME `My Cockpit` (recorded assumption) | compose config, `.env.example` | `docker compose -f deploy/docker-compose.yml --env-file deploy/.env.example config --format json` (exit 0) -> server: `ORIGINS ""`, `RP_ID ""`, `RP_NAME "My Cockpit"` | Pass |
| AC1: variables missing from an older `.env` -> same defaults, no warnings | compose config, variables absent | `docker compose -f deploy/docker-compose.yml --env-file .tmp/dpe/absent.env config` (exit 0) -> server: `ORIGINS ""`, `RP_ID ""`, `RP_NAME My Cockpit`; no unset-variable warning | Pass |
| AC1: `.env.example` and README use only the placeholder Android hash | `deploy/README.md uses only the placeholder hash`, `deploy/.env.example uses only the placeholder hash` | `python3 -I .tmp/tw-dpe/check_readme_filter.py deploy/README.md .tmp/tw-dpe` -> the only `android:apk-key-hash:` value in both files is `REPLACE_WITH_APK_KEY_HASH` | Pass |
| AC1 (README, CR-1 fix): the documented command gives the base64url SHA-256 (no padding) of the signing cert from `apksigner verify --print-certs` | `apksigner -v --print-certs (pubkey digest + Source Stamp + warnings)`; `apksigner --print-certs, uppercase hex, warning first` | same command -> both print the expected 43-character value (`HFPebjvI9Jq6gh1VBYtYkLiYr2IAgYrKMjW0aI34YLU`, computed independently with `hashlib`/`base64`). The verbose sample has scheme lines, `Signer #1 certificate SHA-256 digest`, then `Signer #1 public key SHA-256 digest`, a `Source Stamp Signer certificate SHA-256 digest` and two META-INF `WARNING` lines; the first match is the certificate digest | Pass |
| AC1 (README): the same filter reads `keytool -list -v` | `keytool -list -v, 2-cert chain, SHA256withRSA decoy` | same command -> picks the leaf certificate's `SHA256: AA:BB:...` (Certificate[1]), not the CA's or the `SHA256withRSA` line | Pass |
| README filter encoding: url-safe alphabet, padding stripped | `known-answer vector (url-safe alphabet, padding stripped)` | same command; digest `FB FF BF` x10 + `FB FF` -> `-_-_..._8` (43 chars), worked out by hand without the `base64` module | Pass |
| README line works as written in a shell (quoting) | `README line verbatim in sh (shell quoting)` | same command; runs the README line verbatim in `sh -c` with `apksigner` replaced by a shell function printing the verbose sample -> expected hash, exit 0 | Pass |
| README filter on input without a certificate (for example keytool on a v2-only APK) prints no wrong hash | `no digest in input fails loudly, prints no hash` | same command; input `Not a signed jar file` -> exit 1, empty stdout, `AttributeError` on stderr | Pass (fails loudly; see "Unable to test") |
| Fixer's regression script for CR-1 | `apksigner`, `keytool -list -v` | `python3 -I .tmp/fix-dpe/test_readme_cmd.py deploy/README.md .tmp/fix-dpe` -> both `OK` (`_XI0BlgGtTeqVuY9vSPSGqzzKUfSGd2e2wp8tr2uvw0`) | Pass |
| Real `apksigner` on a real APK, real `keytool -list -v` on a real keystore | none | not run | Not run: no Android SDK in reach of this clone (listing it was denied earlier), no APK built, and creating a keystore with `keytool -genkeypair` was denied in the first pass. The samples follow the documented output formats |
| RP_NAME needs a non-empty compose default (Spring takes an empty env var literally) | code reading | `PasskeyRelyingParties.kt:33` `@Value("\${cockpit.auth.passkey.rp-name:My Cockpit}")`, `name = rpName` (line 49) with no blank check; RP_ID/ORIGINS are trimmed/filtered | Not run: checked by reading the code (first pass, unchanged since); a Spring test would be a server change outside this deploy-only task |

## Focused test execution

Command:

`docker compose -f deploy/docker-compose.yml --env-file {deploy/.env.example | .tmp/dpe/set.env | .tmp/dpe/absent.env} config [--format json]`;
`python3 -I .tmp/tw-dpe/check_readme_filter.py deploy/README.md .tmp/tw-dpe` (8/8 OK);
`python3 -I .tmp/fix-dpe/test_readme_cmd.py deploy/README.md .tmp/fix-dpe` (2/2 OK).
Docker Compose 2.32.4-desktop.1 (`config` needs no running daemon), Python 3.13.2.

Result:

Pass

## Full test execution

Command:

`sh scripts/check.sh` (log `.tmp/tw-dpe/check.log`)

Result:

Fail (exit 1), for environment reasons only. Run on HEAD `2dc672c0`, 2026-10-25 from about 23:25 +0100:
- tfp: 20 tests OK.
- server: `2645 tests completed, 369 failed`. All 175 failing suites in `server/build/test-results/test/*.xml`
  contain `Could not find a valid Docker environment` or an ApplicationContext load failure that follows from it
  (`grep -l -E "<failure"` and `grep -l -E "Could not find a valid Docker environment|ApplicationContext failure threshold|Failed to load ApplicationContext"`
  both give 175 files). `docker info` -> "Cannot connect to the Docker daemon". The task changes no server code.
- web and android: not reached, because `scripts/check.sh` stops after the server failure. In the first pass,
  `sh scripts/check.sh web android` gave web 3463 tests passed and android `SDK location not found` (no Android
  SDK). The fix since then changed only `deploy/README.md`, which neither part reads.

## .taskcheck

Result:

Fail (environment): `.taskcheck` = `sh scripts/check.sh`, see "Full test execution". It fails on Testcontainers
(Docker daemon down) and, per the first pass, on the missing Android SDK. Not caused by this task. `tasks.py done`
needs a machine with Docker and the Android SDK (or `COCKPIT_NO_ANDROID=1`).

## Bugs found

None.

## Coverage

Status: not applicable. The task changes no executable production code, only `deploy/docker-compose.yml`
(configuration), `deploy/.env.example`, `deploy/README.md` and `docs/ARCHITECTURE.md`. The fix commit `2dc672c0`
changes only `deploy/README.md`. None of the configured providers (JaCoCo for server/android, Vitest v8 for web;
`docs/ARCHITECTURE.md` "Coverage") instruments these files.

Metric: whole-file coverage of task-changed production files: N/A (no production files in scope; this is not 100%).
Changed-line coverage: not measured (no executable changed lines).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `deploy-passkey-env`, base
  `c309e711af1498faadf41fa30512692361824e46`, HEAD `2dc672c02116ba8a591220f71600fb3ecdd3e9ed`, commits `727052f9`,
  `2dc672c0` (`git log c309e711..HEAD -- deploy docs`), supplied by the parent worker.
- Source/test snapshot identifier (including uncommitted inputs): no tracked changes; working-tree blobs equal HEAD:
  `deploy/docker-compose.yml` 0e2dc59b, `deploy/.env.example` 397aeb65, `deploy/README.md` 5f38dd9b,
  `docs/ARCHITECTURE.md` 0b069453. Scratch inputs in `.tmp/dpe/`, `.tmp/fix-dpe/`, `.tmp/tw-dpe/` (Git-ignored).
- Exact included files; renamed/deleted/excluded files and reasons: `git diff --name-only c309e711..HEAD` gives the
  four files above, all config or docs, so all are excluded from coverage. No renames or deletions.
- Provider/version, command, working directory, and test selection: none run, nothing to measure.
- Run start/completion time and exit status: N/A.
- Generated report paths and report hashes/run identifier: N/A.
- Evidence outputs are fresh and inputs stayed unchanged: all compose and filter outputs above were produced in
  this pass (2026-10-25, from 23:22 +0100) on HEAD `2dc672c0`.
- Final run after latest test additions and production fixes: yes (after fix commit `2dc672c0`).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| none (no production code changed) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `deploy/docker-compose.yml:45-50` | forwarding and defaults | No coverage tool applies; checked with `docker compose config` (set, empty, absent) above |
| `deploy/README.md:72-79` | APK hash command | No coverage tool applies; checked with `.tmp/tw-dpe/check_readme_filter.py` and the fixer's script above |

## Unable to test

- Real `apksigner verify --print-certs` on a signed APK and real `keytool -list -v` on a keystore: no Android SDK
  or APK available here, and keystore creation was denied. Low risk: tested on samples in the documented formats,
  including verbose mode and a Source Stamp signer. Optional follow-up once a signed APK exists
  (e.g. `and-apk-distribution`).
- Input without a certificate digest makes the filter exit 1 with a Python `AttributeError` traceback instead of
  a readable message. It prints no wrong hash, so this is not a defect against the criteria; noted for the parent
  only as a usability detail.
- Running the stack (`docker compose up`) and a real Android passkey login: no Docker daemon, no device. Not
  required by the acceptance criteria.
- Spring's empty-env behaviour for RP_NAME: checked by reading the code only (see table).
- Full `.taskcheck`: environment blockers, see above.

## Summary

No additional tests were necessary for the reviewed scope. On the final revision `2dc672c0`, both acceptance
criteria pass with `docker compose config` (set, empty and absent values), and the README's `apksigner` command and
filter give the correct base64url hash for apksigner (plain and verbose, with warnings and a Source Stamp signer)
and `keytool -list -v` output. No production defects were found. `.taskcheck` fails here only because the Docker
daemon is down and the Android SDK is missing.
