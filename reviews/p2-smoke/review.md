# Code Review

Date: 2026-10-21

Task: p2-smoke - Phase 2 demo script incl. restore on a fresh install

Scope: committed changes `git diff 8275cd09..HEAD` (web/e2e/phase2/demo.spec.ts, web/e2e/phase2/zip.ts, web/e2e/support/run-e2e.mjs, deploy/docker-compose.e2e.yml, web/e2e/README.md). An uncommitted working-tree edit of web/e2e/phase2/demo.spec.ts (an extra `expectDemoData` call and a check of every image link in `readable/`, apparently from the parallel test-writer run) was seen but is not part of the reviewed scope.

Requirements:
- tasks/p2-smoke.md (acceptance criteria and recorded assumptions)
- SPEC.md §13.2 Phase 2 (steps 1-5)
- docs/ARCHITECTURE.md, AGENTS.md (project notes, conventions), docs/QUALITY.md
- Parent instructions: compose works with docker compose and podman-compose, fictitious test data, robustness/flakiness of the spec, correctness of the zip reader

Git range:
- Branch: main
- HEAD: f48fdacc23ef040961b8f1589a22207be498917f
- Base/start: 8275cd090f113d81f2ebd8175fd98ff526a4e992
- Commits reviewed: 4 (809cfc69, 1008fecf, 6057656f, f48fdacc)

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Step 5 works only once per `server-fresh`: re-running against a kept stack always fails**
    - File: `web/e2e/phase2/demo.spec.ts:295-302`, `web/e2e/README.md:21-31`
    - Category: Tests
    - Problem: Step 5 asserts `setupRequired: true` on `E2E_FRESH_BASE_URL` and then runs the setup (expects 201), but nothing ever returns `server-fresh` to a fresh state. After a run, it holds the restored owner and data (`afterAll` resets only the main server). The README documents `E2E_KEEP=1` (keep the stack) and `E2E_BASE_URL` + `E2E_FRESH_BASE_URL` (run against an already running stack) as supported workflows. In either case, the second run of `phase-demos` always fails step 5 at line 296. The same happens with `--repeat-each`. The README does not mention that the fresh server can be used only once.
    - Why it matters: The documented "keep the stack and re-run" workflow, which is the usual way to debug a failing phase demo, gives a false failure in phase 2 every time. Someone debugging it could take this for a restore regression.
    - Suggested fix: Pick one, kept small. (a) Document in the README and the `E2E_FRESH_BASE_URL` row that `server-fresh` can be used once, and give the command that recreates it, e.g. `<compose> -p <project> -f deploy/docker-compose.e2e.yml up -d --force-recreate -V server-fresh postgres-fresh`. (b) Skip step 5 with a clear message when `setupRequired` is already false. Avoid calling `/api/test/reset` on `server-fresh` as a workaround: it would leave the old `/data` volume in place, so it would no longer be the "fresh install with new volumes" that the criterion asks for.

## P3 Low

- [ ] **CR-2 | `getByText('not included')` matches substrings and may break once modules add restore warnings**
    - File: `web/e2e/phase2/demo.spec.ts:308`
    - Category: Tests
    - Problem: `report.getByText('not included')` matches by substring by default. Today it finds only the "Secrets: not included" `<dd>` (backup-settings.html:146), because the server only sends the version-mismatch warning (RestoreService.kt:133-138). Module contributors add their own warnings through `restoreWarnings`, though, and the web unit test already uses "External calendar events are not included" as its example warning (backup-settings.spec.ts:485). Once a warning like that appears in the report, the locator matches two elements and Playwright's strict mode fails the step.
    - Why it matters: The phase 2 regression run (which every later phase also runs) can start failing because of an unrelated module's warning text.
    - Suggested fix: Scope the locator to the Secrets fact, e.g. `report.getByText('not included', { exact: true })`, or locate the `dd` that follows the "Secrets" `dt`.

## Review conclusion

Material issues found; fix before completing the task.

Checked and found no problems:
- **Acceptance criteria 1 and 2:** steps 1-5 are covered, and step 5 restores the downloaded zip into a separate server with its own tmpfs Postgres and anonymous `/data` volume, then compares a broad API snapshot (settings, contexts, areas, projects, tasks, logbook, checklists, notes, the recursive drive tree with downloaded content hashes, the links of every item, trash). `readable/` tasks, projects and notes are checked, and the note's image link is resolved inside the zip. The assumptions (TOTP re-enrollment checked via the API, restore through the UI, seeding through the API, a second server in the stack) are recorded in the task.
- **Zip reader:** the central directory and local header offsets (flags +8, method +10, compressed size +20, name, extra and comment lengths +28/+30/+32, local offset +42, local name and extra lengths +26/+28) are correct. The EOCD search is bounded, and data descriptors are handled because sizes come from the central directory.
- **Compose file:** the `x-` extension plus YAML merge keys are standard compose syntax, which PyYAML also supports. All secrets are labelled fictitious and the test data is fictitious.
- **Runner:** `run-e2e.mjs` waits for both servers and includes `server-fresh` in the logs on failure.

Criterion 3 ("Phase 0-1 specs still pass") rests on the parent's reported `npm --prefix web run e2e -- --project=phase-demos` run (21 passed). No `tasks.py verify` evidence was recorded at review time, so the parent still has to record it.
