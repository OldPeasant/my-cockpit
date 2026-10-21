# Code Review

Date: 2026-10-21

Task: p2-smoke - Phase 2 demo script incl. restore on a fresh install

Scope: second review pass of the final code, committed changes `git diff 8275cd09..19092c54` (web/e2e/phase2/demo.spec.ts, web/e2e/phase2/zip.ts, web/e2e/support/run-e2e.mjs, deploy/docker-compose.e2e.yml, web/e2e/README.md), with focus on the changes since the first pass (`git diff f48fdacc..19092c54`): the test-writer's `expectDemoData` and readable image link checks, and the fixer's changes for CR-1 and CR-2 of the first pass. No uncommitted changes.

Requirements:
- tasks/p2-smoke.md (acceptance criteria and recorded assumptions)
- SPEC.md §13.2 Phase 2 (steps 1-5)
- docs/ARCHITECTURE.md, AGENTS.md (project notes, conventions), docs/QUALITY.md
- reviews/p2-smoke/fixes.md, reviews/p2-smoke/tests.md (claims checked against the code)

Git range:
- Branch: main
- HEAD: 19092c54e76f4a20dfd2c219a910e94d9616b13e
- Base/start: 8275cd090f113d81f2ebd8175fd98ff526a4e992
- Commits reviewed: 6 (809cfc69, 1008fecf, 6057656f, f48fdacc, 0ed7cd0a, 19092c54)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.

Findings from the first pass, checked in the final code:
- **CR-1 (step 5 works only once per `server-fresh`): resolved.** `demo.spec.ts:303-312` now checks that `/api/v1/setup/status` returns OK, reads `setupRequired` and calls `test.skip` with the recreate command when the server was already used. It does not reset through `/api/test/reset`, so the "fresh install, new volumes" meaning of criterion 1 is kept. The `try/finally` around it still closes the contexts when the step is skipped. In the normal `npm run e2e` path, `run-e2e.mjs` runs `down -v` before `up`, so `server-fresh` is always fresh and step 5 always runs. The README section (`web/e2e/README.md:33-42`) matches the runner: the project name is printed through the `E2E_KEEP=1` down command, and the image tag `localhost/<project>:latest` and the `E2E_FRESH_PORT` variable match `run-e2e.mjs:43-52` and the compose file. The fixer did not run the skip path itself (it says so in fixes.md). The condition is a single expression on the value the earlier assertion checked, so this is acceptable.
- **CR-2 (substring locator): resolved.** `report.getByText('not included', { exact: true })` (`demo.spec.ts:324`) matches only the Secrets fact (`backup-settings.html:146`) and no longer matches module warnings like "External calendar events are not included".

Test-writer additions, checked:
- `expectDemoData(before)` (`demo.spec.ts:582-641`) asserts that the snapshot step 5 compares is not trivially empty: settings, contexts, area, projects, tasks, logbook, trash, checklist order, a note with a `cockpit-file:` image, files and their hashes, and exactly six link endpoints. The values match the data seeded in steps 0-3.
- The readable image loop (`demo.spec.ts:277-283`) resolves every `![..](..)` link in every `readable/**/*.md` relative to its file, URL-decoded, against the zip entries. The existing `images[0]` check makes sure there is at least one image, so the loop cannot pass with nothing to check. Together they cover criterion 2.

Acceptance criteria:
- Criteria 1 and 2 are covered by the code as described in the first pass and above.
- Criterion 3 and the e2e run depend on the reported `npm --prefix web run e2e -- --project=phase-demos` run (21 passed, step 5 executed). `python tasks.py verify p2-smoke` still lists all three criteria as unverified, so the parent worker must record the evidence (command, stack, result) before `done`.
