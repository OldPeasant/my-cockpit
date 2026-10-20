# Code Review

Date: 2026-10-20

Task: p0-smoke - Phase 0 demo script + README

Scope: `git diff 4a7b7d684dbffcaf760d8c22dd2cb8fe08b26b2a..3fd33d03aa3eed625a56bf514bbe745f824e22d7` (committed changes only; working tree clean)

Requirements:
- `tasks/p0-smoke.md` acceptance criteria (1: phase0 spec covers demo steps 1-5 and passes against the e2e stack; 2: README content; 3: defects fixed or recorded)
- SPEC.md §13.2 (Phase 0 demo script), §4.7 (settings / theme)
- docs/ARCHITECTURE.md (Run, Install, Testing), AGENTS.md project notes, web/e2e/README.md
- Parent instructions (known: step 1 checks `/actuator/health` on the Caddy-less e2e stack; the initial bundle budget warning exists on the base)

Git range:
- Branch: main
- HEAD: 3fd33d03aa3eed625a56bf514bbe745f824e22d7
- Base/start: 4a7b7d684dbffcaf760d8c22dd2cb8fe08b26b2a
- Commits reviewed: 2 (ccd075ca, 3fd33d03)

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Demo step 1 is only partly automated, and no assumption records the gap**
    - File: `web/e2e/phase0/demo.spec.ts:35`
    - Category: Tests
    - Problem: SPEC.md §13.2 Phase 0 step 1 says `docker compose up` on a clean machine with a generated `.env` starts caddy, server and postgres, and `/actuator/health` is UP *behind the proxy*. The spec only calls `GET /actuator/health` on the e2e stack. `deploy/docker-compose.e2e.yml` has no Caddy service, so nothing checks the proxy, the production compose file or `gen-env.sh`. The criterion's "passes against the e2e stack" allows this, but `python tasks.py assumptions` shows no p0-smoke entry recording the narrower coverage.
    - Why it matters: Readers of the board will take the Phase 0 demo as fully automated. A broken Caddyfile or production compose file would not fail it, and SPEC.md §13.2 makes this script the regression test for every later phase.
    - Suggested fix: Record a `tasks.py assume p0-smoke` entry saying step 1 is checked on the e2e stack (server + Postgres, no Caddy), and that the proxy / production-compose part stays manual or belongs with step 6 (H). Also note the reduced scope in the evidence for criterion 1. If automation is wanted, add a follow-up task for a production-compose smoke check.

## P3 Low

- [ ] **CR-2 | A failing phase demo silently skips every other e2e spec**
    - File: `web/playwright.config.ts:37`
    - Category: Quality
    - Problem: `chromium` now has `dependencies: ['phase-demos']`. When a dependency project fails, Playwright skips its dependent projects, so one failing demo step (for example a theme or palette regression) reports every feature spec as "did not run". web/e2e/README.md explains the ordering and `--no-deps`, but not that a demo failure hides the results of all the other specs.
    - Why it matters: The more phase scripts accumulate (each later phase adds one), the less a single run tells you, and finding the cause takes longer. The ordering itself is justified: resets, the global backoff and the clock shift must not run in parallel with other specs.
    - Suggested fix: Add one sentence to web/e2e/README.md: a failing demo skips the other specs, so rerun with `--no-deps` to see their results. Keep the dependency.

## Notes (no finding)

- The theme fix is sound. `THEME_PREFERENCE` defaults to `ThemeService.setMode` (local only). `provideSettingsSync()`, which is provided on the shell route in `app.routes.ts:34`, overrides it with `SettingsService.update({ theme })`. That gives an optimistic apply, a serialized PATCH with If-Match, rollback on error, and the error shown by the global handler. `SettingsService.load` already drops a stale login load while a save is in flight, and the unit test "keeps the chosen theme when the login load answers after the change was sent" covers this. The rejection is swallowed, so there is no unhandled promise.
- The changes outside the task's `touches` (`web/src/app/shell/*`, `features/settings/settings.providers.ts`, `web/playwright.config.ts`, `web/e2e/README.md`) are the defect fix that criterion 3 allows, plus the wiring the phase project needs.
- README.md meets criterion 2. It has a short description, a 5-command dev quick start that matches ARCHITECTURE.md "Run" (dev profile, Postgres container, `COCKPIT_MASTER_KEY`), a deploy pointer that matches deploy/README.md (`gen-env.sh`, `docker compose -f deploy/docker-compose.yml up -d --build`), and links to SPEC.md, ARCHITECTURE.md, TEAM.md and AGENTS.md. All of them exist.
- Test data is fictitious (`alex.example`). No secrets or credentials appear in the diff.
- No `tasks.py verify` evidence is recorded yet for the three criteria. reviews/p0-smoke/tests.md reports `npm run e2e` (podman-compose e2e stack, projects `phase-demos` then `chromium`) -> 7 passed. The parent still has to record this and the README read-through.

## Review conclusion

No material issues found. CR-1 (record the assumption about step 1's narrower scope) and CR-2 (one README sentence) are small, non-blocking follow-ups.
