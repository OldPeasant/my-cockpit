---
title: CI: give the Docker server build its heap settings; make failing tests visible
status: in_progress
owner: dell26/agent-1
deps: []
touches: [Dockerfile,.github/workflows/ci.yml,scripts/check.sh,server/build.gradle.kts,android/app/build.gradle.kts]
prio: 1
lease_until: 2026-10-27T19:02:04Z
---
## Goal
Most CI runs on main fail for two reasons that have nothing to do with the task that was pushed:

1. **Docker image build OOM.** `docker build .` fails in `:compileKotlin` with `java.lang.OutOfMemoryError: GC
   overhead limit exceeded` (runs 40419253981, 40416450692, 40408896958, 40377163689, 40369735213). Commit
   1e2a38813 raised the heap in `server/gradle.properties` (`org.gradle.jvmargs=-Xmx2g`,
   `kotlin.daemon.jvmargs=-Xmx3g`), but the Dockerfile server stage never copies `server/gradle.properties`,
   so the image build still compiles with the default 512 MB heap.
2. **Flaky tests we cannot name.** "Fast check (server)" / "Fast check (android)" fail with exactly
   `N tests completed, 1 failed` (runs 40408896958, 40355857784, 40343574242, 40333228291, 40323181451,
   40318646083), but `scripts/check.sh` runs Gradle with `--quiet` and CI uploads no test reports, so the
   failing test's name and stack trace are lost. Make them visible so each flaky test can get its own
   `fix-flaky-*` task. Fixing the flaky tests themselves is out of scope.

## Acceptance criteria
- [ ] The Dockerfile server stage copies `server/gradle.properties` before the Gradle steps, and
  `docker build -t cockpit-server:ci .` (the command CI runs; `podman build` on Podman machines) succeeds
  locally with `--no-cache`
- [ ] A failing server or android unit test prints its class, method and exception (with the assertion
  message and stack trace) in the job log, while `sh scripts/check.sh` stays quiet otherwise (no per-test
  output for passing tests); shown by temporarily breaking one test in each part and running
  `sh scripts/check.sh server` / `sh scripts/check.sh android` (revert the broken tests before committing)
- [ ] `.github/workflows/ci.yml` uploads the test reports (`server/build/reports/tests/`,
  `server/build/test-results/`, `android/app/build/reports/tests/`, `android/app/build/test-results/`) as
  artifacts when the fast check (server/android) or integration job fails (`if: failure()`), and the
  workflow is valid (e.g. `actionlint`, or `python -c "import yaml; yaml.safe_load(open(...))"` if actionlint
  is unavailable)
- [ ] `sh scripts/check.sh` passes

## Notes
- Diagnosis from `gh run list` / `gh run view --log-failed` on 2026-10-27.
- Gradle: `tasks.withType<Test> { testLogging { events("failed"); exceptionFormat = TestExceptionFormat.FULL } }`
  is one option; keep `--quiet` for everything else. Note that `--quiet` suppresses lifecycle-level test
  logging, so the settings may have to go on the quiet level (`testLogging.quiet { ... }`); check it in the log.
