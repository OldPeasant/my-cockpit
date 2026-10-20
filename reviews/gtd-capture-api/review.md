# Code Review

Date: 2026-10-20

Task: gtd-capture-api - Quick capture endpoint and syntax parser (final review of the whole task scope)

Scope: all task commits `git diff b977d6d6..HEAD` (11 commits, e9c861cf..4b34bc64), committed changes only. The focus is on the commits after the previous review (470710c3): 72b3b374 (CaptureSideEffectWebTests), 0644d390 (CR-7 schema descriptions, regenerated contract and web models, CR-6 regression test) and the report commits f3ac29db/4b34bc64. All production files in the task were read again in full.

Requirements:
- tasks/gtd-capture-api.md acceptance criteria #1-#5 and the five recorded assumptions in its Notes
- SPEC.md §4.2 (UUIDv7, ETag, Problem Details), §4.3 (links), §4.5 (quick capture), §5.4 (API sketch), §9.3 (offline capture)
- AGENTS.md project notes (module boundaries, generated files, logging), docs/ARCHITECTURE.md conventions, docs/QUALITY.md
- Previous review (CR-1..CR-7) and reviews/gtd-capture-api/fixes.md (rounds 1 and 2)

Git range:
- Branch: main
- HEAD: 4b34bc64
- Base/start: b977d6d64b6475ca55082dd46a72b0681b01556c
- Commits reviewed: 11

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Resolution of the prior findings

- CR-1 to CR-4: still resolved in the current code (title-line-only parsing in `CaptureParser.parse`/`plain`, body kept verbatim by `splitLines`, punctuation handling at `CaptureParser.kt:96-102`, race fallback in `CaptureController.kt:63-70` with its test). No regressions in 72b3b374 or 0644d390, which change only tests, schema descriptions and generated files.
- CR-5: accepted as an assumption (tasks/gtd-capture-api.md Notes, 22:05 entry). Not reported again.
- CR-6 (`null` element in `fileIds`): resolved, the finding was wrong. The server uses Jackson 3 (`tools.jackson.module:jackson-module-kotlin`, Spring Boot 4.1.1, `server/build.gradle.kts:5,35`). Its Kotlin module rejects the `null` element of the non-null `List<UUID>` while it reads the request, so the service is never reached. `capture rejects malformed bodies` (`CaptureEdgeCaseWebTests.kt:362-366`) now asserts a 400 with `application/problem+json` for `{"text":"x","fileIds":[null]}` and checks that no task was created. The test passes in my run below, so the original NPE path does not happen. The fixer did not leave dead validation code behind.
- CR-7 (title rule missing from the schema): resolved. The `CaptureRequest` description (`CaptureDtos.kt:13-18`) now says "first non-blank line" and that the syntax applies to the title line. It also explains the token-only fallback. `CapturePreviewResponse` (`CaptureDtos.kt:57-65`) has the same fallback sentence. Both match `CaptureParser.kt:105-111`. `api/openapi.yaml` and `web/src/app/api/generated/models/capture-request.ts`/`capture-preview-response.ts` contain exactly these descriptions and no other changes.

## Acceptance criteria check

- #1 met: `POST /api/v1/capture` returns 201 with ETag and Location for a new INBOX task (WAITING with `^`). A repeated id returns 200 with the existing task, unchanged and without linking files again. This also holds in the concurrent-insert fallback. A trashed id gives 409, a non-UUIDv7 id gives 400. Covered by CaptureWebTests and CaptureEdgeCaseWebTests.
- #2 met: `CaptureParser` is a pure `object` without I/O. The date and the candidate projects/contexts are passed in. It handles `#project` (case-insensitive, unknown stays text), `@context`, `!today|!tomorrow|!YYYY-MM-DD` and `^waiting`. `/capture/preview` is read-only and returns the parse result. Covered by CaptureParserTests and CaptureParserEdgeCaseTests. CaptureSideEffectWebTests also shows that unknown tokens create no project or context.
- #3 met: file ids are de-duplicated, checked through the FILE resolver and linked with core `LinkService.create` in the capture transaction (`CaptureService.kt:56-76`). CaptureSideEffectWebTests shows the link through `/api/v1/links` from both sides. It also shows that a capture rejected after the file check leaves no task and no link.
- #4: I ran `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest'` at HEAD 4b34bc64 against Testcontainers Postgres. Result: BUILD SUCCESSFUL, 318 tests, 0 failures, 0 errors, 0 skipped. The run included all five capture suites and `OpenApiContractTest`.
- #5: in the same run, `OpenApiContractTest` passed without `UPDATE_OPENAPI`, and the worktree stayed clean afterwards, so `api/openapi.yaml` matches the code.
- Evidence: `python tasks.py verify gtd-capture-api` shows nothing recorded yet for #1-#5. This is not a defect in the code. The parent still has to record the evidence before `done`.

Architecture, security and quality: gtd depends only on core `LinkService`, `ItemResolverRegistry` and `SettingsService`, not on the files module. Only the side-effect test uses `FilesService`, as a test fixture. The task adds one derived query in `gtd.infra` and needs no migration. Capture text and notes are not logged. Errors use the existing Problem Details exceptions. The generated files were regenerated, not edited by hand. The new tests use fictitious data.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. CR-1 to CR-4 and CR-7 are resolved, CR-6 was invalid and is now covered by a regression test, and CR-5 is recorded as an assumption. Before `done`, the parent still has to record the criterion evidence with `tasks.py verify`, run `.taskcheck` and write `quality.json`.
