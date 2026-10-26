# Test Report

Date: 2026-10-26

Task: deploy-oauth-env - Pass COCKPIT_OAUTH_PUBLICURL to the server in deploy compose

Scope: base 50c8dd501b49c559e1f65ce14d26901180aab9e6 (claim commit). The parent named task commit 0fbcd7ce;
during this run the clone was rebased and the task commit is now 6cca17e2faa597e50ceb50ff1dea4fd86bc3f4ad (HEAD, branch main).
`git diff 0fbcd7ce 6cca17e2 -- deploy` is empty, so the tested files are byte-identical. Commits between base and HEAD
other than 6cca17e2 touch only `tasks/` (verify/heartbeat). Task-owned production files: `deploy/docker-compose.yml`,
`deploy/.env.example`. Uncommitted inputs: none in the tested files (`git status`: only untracked `reviews/deploy-oauth-env/`).

Requirements:
- tasks/deploy-oauth-env.md acceptance criteria (AC1, AC2)
- docs/oauth-setup.md (`cockpit.oauth.public-url`, default `https://${COCKPIT_DOMAIN}`), server/src/main/resources/application.properties line 22, OAuthProperties.kt
- docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md, AGENTS.md
- Parent instructions: add a test only if it fits an existing convention for deploy config tests

## Test files changed

None.

## Tests added or updated

Count: 0

Why no automated test was added: the repository has no place for deploy/compose config tests. `tests/` holds
only TFP tooling tests (`test_quality_gate.py`, `test_check_sh.sh`, `test_publish.sh`, ...), and
`scripts/check.sh` (`.taskcheck`) runs only the TFP gate tests and the server/web/android suites; nothing in it
runs `docker compose`. Earlier deploy work (reviews/p0-deploy/tests.md) also validated `deploy/` files by manual
runs rather than an automated test. Adding a compose test would mean a new test category that needs Docker in
`.taskcheck` and CI, which is a project decision outside this task. The behavior was instead checked with a
repeatable scratch script, `.tmp/tw-cfg.sh` (Git-ignored), that renders the compose config with fictitious env files.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: compose passes `COCKPIT_OAUTH_PUBLICURL` to the server; unset = `https://${COCKPIT_DOMAIN}` | scripted compose render, cases "unset", "empty" | `sh .tmp/tw-cfg.sh` (`docker compose -f deploy/docker-compose.yml --env-file <fixture> config --format json`, Docker Compose v5.6.1, COCKPIT_DOMAIN=cockpit.example.org) | Pass: unset -> `https://cockpit.example.org`; empty value -> `https://cockpit.example.org` (`:-` treats empty as unset) |
| AC1: `.env.example` documents it next to GOOGLE_*/MS_* | read `deploy/.env.example` lines 23-32; render with `.env.example` itself as env file (plus fictitious secrets) | same script, case ".env.example" | Pass: documented in the OAuth block directly above GOOGLE_CLIENT_ID; the commented-out line yields the default `https://cockpit.example.org` (exit 0) |
| AC2: with `COCKPIT_OAUTH_PUBLICURL=https://example.test:8443` the value appears in the server environment | case "value" | same script | Pass: server `COCKPIT_OAUTH_PUBLICURL='https://example.test:8443'`, `COCKPIT_DOMAIN='cockpit.example.org'` |
| AC2: without it the server falls back to the default | case "unset" | same script | Pass (see AC1) |
| Extra: value passed through verbatim (trailing slash) | case "trailing slash" | same script | Pass: `https://cockpit.example.org/` passed unchanged; the server strips it in `OAuthProperties.redirectUri` (`trimEnd('/')`) |
| Env var reaches `cockpit.oauth.public-url` in the running server | none | none | Not run: needs a started stack; relies on Spring relaxed binding (`COCKPIT_OAUTH_PUBLICURL` -> `cockpit.oauth.publicurl`, the canonical form of `public-url`), which is framework behavior, not task code |

## Focused test execution

Command:

`sh .tmp/tw-cfg.sh`

Result:

Pass: all five render cases exit 0 with the expected server values (listed above). A sixth step in the script that tried to merge
`deploy/docker-compose.e2e.yml` as an overlay was a mistake in the script: that file is a standalone e2e stack (own `name`, own
server environment), not an override of the deploy compose, so it is unaffected by this task and its output is not counted.

## Full test execution

Command:

`sh scripts/check.sh` (2026-10-26T01:41:16+01:00 to 01:41:25+01:00, log in `.tmp/tw-check.log`)

Result:

Fail (environment, not task-related): the `tfp` part passed (20 tests OK); the `server` part stopped immediately with
`ERROR: JAVA_HOME is set to an invalid directory: /usr/lib/jvm/java-21-openjdk` in this sub-agent's shell, so web and android did
not run. The task changes no server, web or android code. The parent must run `.taskcheck` in its own environment.

## .taskcheck

Result:

Fail in this sub-agent's environment (invalid JAVA_HOME, see above); not a result about the task. The parent must run it.

## Bugs found

None.

## Coverage

Status: not applicable. The task changes only `deploy/docker-compose.yml` and `deploy/.env.example`, which are configuration
files with no executable code under any configured coverage provider (JaCoCo for server/android, Vitest v8 for web,
docs/ARCHITECTURE.md "Coverage"). No coverage command was run, and no percentage is claimed.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no provider applies to compose/env files).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: deploy-oauth-env; base 50c8dd501b49c559e1f65ce14d26901180aab9e6; HEAD 6cca17e2faa597e50ceb50ff1dea4fd86bc3f4ad (rebased copy of 0fbcd7ce, identical `deploy/` content); scope from the parent and `git log`/`git diff --stat` 0fbcd7ce..HEAD (only `tasks/` besides the task commit).
- Source/test snapshot identifier (including uncommitted inputs): sha256 deploy/docker-compose.yml 1318cfc5595f4a3ca9daf84cddc4ed37cab2135bc9ec539b79d0ad7bf0ec24b7; deploy/.env.example 9c9196c9921143eddf07bc6e014fe07cbb8b10e02eb65509d10c4b78cf26d0da; both committed and clean.
- Exact included files; renamed/deleted/excluded files and reasons: none measured. Excluded: `deploy/docker-compose.yml`, `deploy/.env.example` (configuration, no coverage provider). No renames or deletions.
- Provider/version, command, working directory, and test selection: none (not applicable). Behavior check: Docker Compose v5.6.1, `sh .tmp/tw-cfg.sh` in the clone root.
- Run start/completion time and exit status: compose checks run 2026-10-26 about 01:40 +01:00, every render exit 0.
- Generated report paths and report hashes/run identifier: none (no coverage report generated).
- Evidence outputs are fresh and inputs stayed unchanged: yes, rendered from the committed files with the hashes above.
- Final run after latest test additions and production fixes: yes (no fixes since 0fbcd7ce/6cca17e2).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `deploy/docker-compose.yml` | N/A | N/A | N/A | N/A |
| `deploy/.env.example` | N/A | N/A | N/A | N/A |

No aggregate: no file in scope is measurable.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `deploy/docker-compose.yml:36` | AC1/AC2: value passed, default when unset/empty | Checked by `.tmp/tw-cfg.sh` render cases; no automated test (no existing convention, see above) |
| `deploy/.env.example:24-27` | AC1: documentation | Read and rendered as env file |

## Unable to test

- End-to-end binding in a running server (env var -> `cockpit.oauth.public-url` -> redirect URI with port 8443): needs the
  deploy stack started with real config; not run. Low risk (standard Spring relaxed binding, documented in docs/oauth-setup.md). No follow-up needed unless the parent wants live evidence.
- `.taskcheck` server/web/android parts: blocked by an invalid JAVA_HOME in this sub-agent's environment; the parent must run it.
- An automated compose-config regression test: no existing convention or Docker step in `.taskcheck`; would need a project decision (separate task), not part of this task.

## Summary

No additional tests were necessary for the reviewed scope. Both acceptance criteria were checked with repeatable
`docker compose config` renders (value, unset, empty, trailing slash, `.env.example` as-is) and pass; no production defects found.
Coverage is not applicable (config-only change). The parent still has to run `.taskcheck` (it failed here only on JAVA_HOME).
