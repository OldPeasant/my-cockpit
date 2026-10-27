# Code Review

Date: 2026-10-27

Task: ci-oom-and-test-reports - CI: give the Docker server build its heap settings; make failing tests visible

Scope: `git diff f03bd63fc..6e19bbfc0` (one task commit). Committed changes only; the two uncommitted, deliberately broken tests in the working tree (`TestLibrariesSmokeTest.kt`, `CorrespondenceQueryTest.kt`) are the parent's temporary demo for criterion 2 and are not reviewed.

Requirements:
- tasks/ci-oom-and-test-reports.md (acceptance criteria and notes)
- AGENTS.md (project notes, evidence rules)
- Existing conventions in `.github/workflows/ci.yml`, `Dockerfile`, `.dockerignore`, `scripts/check.sh`

Git range:
- Branch: main
- HEAD: 6e19bbfc0
- Base/start: f03bd63fc50fd95676cbcd3ea7966a864fcaaee9
- Commits reviewed: 1 (6e19bbfc0 "ci: Docker server build gets gradle.properties; failing tests print in log; upload test reports")

Files changed: `Dockerfile`, `server/build.gradle.kts`, `android/app/build.gradle.kts`, `.github/workflows/ci.yml`.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Commands run (read-only)

- `git diff f03bd63fc..6e19bbfc0` -> 4 files changed, as listed above.
- `cat .dockerignore` -> context is `*` with `!server/` re-included; only `server/build/`, `server/.gradle/`, `server/.kotlin/` and `**/.env` excluded below it, so `server/gradle.properties` is in the build context.
- `cat server/gradle.properties` -> `org.gradle.jvmargs=-Xmx2g ...`, `kotlin.daemon.jvmargs=-Xmx3g`; no secrets.
- `python3 -I -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml'))"` -> parses; jobs check-server, check-android and integration each end with an "Upload test reports" step. `actionlint` is not installed here.
- `grep -n "name: test-reports" .github/workflows/ci.yml` -> artifact names test-reports-server, test-reports-android, test-reports-integration; the only other artifact is cockpit-android. All unique.
- Parent logs: `.tmp/podman-build.log` had reached `[2/3] STEP 5/8: RUN ./gradlew --no-daemon --quiet dependencies` (the COPY step with `server/gradle.properties` succeeded, step 3/8); `.tmp/check-server-broken.log` and `.tmp/check-android-broken.log` held only the step headers. All three runs were still in progress when this review was written, so this review has no results from them.

## Analysis

Dockerfile: `server/gradle.properties` is copied in the same layer as the wrapper and build scripts, before the first `./gradlew` call, so both the `dependencies` and the `bootJar` steps read the heap settings. With `--no-daemon`, Gradle starts a single-use daemon with `org.gradle.jvmargs` when they differ from the client JVM, and the Kotlin daemon gets `-Xmx3g`. Putting the file in the cached layer is right: a heap change invalidates the dependency cache, which is what should happen. The comment also matches the Dockerfile's existing style.

Gradle testLogging: inside `testLogging { ... }` the receiver is `TestLoggingContainer`, which extends `TestLogging`. Its own setters delegate to the lifecycle level, and `quiet` resolves to `TestLoggingContainer.getQuiet()`, the closest implicit receiver. No other receiver in scope (`Test`, the script) has a `quiet` member, so nothing shadows it. `listOf(this, quiet).forEach { it.events(FAILED); it.exceptionFormat = FULL }` therefore sets both levels. When Gradle logs test events it uses the `TestLogging` for the current log level, so under `--quiet` the quiet-level settings apply. Passing tests stay silent because only `FAILED` is enabled at either level. Showing exceptions, causes and stack traces is on by default, and `FULL` adds the assertion message and the full trace (filtered by the default `TRUNCATE` filter, which still includes the test method frame). The `configureEach` block over all `Test` tasks covers `test`, `integrationTest` and `updateOpenApi` in the server, and the AGP unit-test tasks (`AndroidUnitTest` extends `Test`) in android. Indentation follows each file (tabs in server, 4 spaces in android). The imports are used.

Upload steps: they use `if: failure()`, as the criterion says, and `if-no-files-found: ignore`, so a ktlint-only failure doesn't add a second error. Artifact paths are resolved relative to `GITHUB_WORKSPACE`. The integration job's `working-directory: server` applies only to its `run` step and doesn't affect a `uses` step, so `server/build/...` is the correct path there. `integrationTest` writes to `server/build/test-results/integrationTest` and `server/build/reports/tests/integrationTest`, which are inside the uploaded directories. `actions/upload-artifact@v4` matches the version the android-apk job already uses. The four directories match the criterion exactly. The header comment documents the artifact naming.

Scope: `scripts/check.sh` is in the task's touches but was correctly left unchanged (`--quiet` stays as the notes require). No flaky test was fixed, which is out of scope. No dependencies were added.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Notes for the parent worker (not findings)

- Criteria 1, 2 and 4 depend on runs that were still going during this review (podman `--no-cache` build, the two broken-test `check.sh` runs, the full `sh scripts/check.sh`). Record their actual results with `tasks.py verify`. For criterion 2, the log should show the failing class, method, assertion message and stack trace, and no lines for passing tests. Revert the two demo edits before committing.
- `actionlint` is not available here. The YAML parse check is the fallback the criterion allows.

## Review conclusion

No material issues found.
