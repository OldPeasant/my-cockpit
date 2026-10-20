# Code Review

Date: 2026-10-20

Task: gtd-capture-api - Quick capture endpoint and syntax parser (re-review of the final code)

Scope: all task commits f36994ac..ef78f079 (f36994ac, 683024fc, a7488dc4, b130fb98, 19c0d41e, ef78f079), merged at HEAD 25d551a6; `git diff 08890ec1..HEAD`, committed changes only. The untracked test file the concurrent test-writer is adding (`CaptureSideEffectWebTests.kt`) is not part of this review.

Requirements:
- tasks/gtd-capture-api.md acceptance criteria #1-#5 and the five recorded assumptions in its Notes
- SPEC.md §4.2 (UUIDv7, ETag, Problem Details), §4.3 (links), §4.5 (quick capture), §5.4 (API sketch), §9.3 (offline capture)
- AGENTS.md project notes (module boundaries, generated files, logging), docs/ARCHITECTURE.md conventions
- Prior review (CR-1..CR-5) and reviews/gtd-capture-api/fixes.md

Git range:
- Branch: main
- HEAD: 25d551a6
- Base/start: 08890ec1b5dfe3cea622bac699bfad9e8647d282
- Commits reviewed: 6 task commits (plus the merge commit 25d551a6)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Resolution of the prior findings

- CR-1 (body lines rewritten): resolved. `splitLines` keeps the lines after the title verbatim (only leading/trailing blank lines dropped, CRLF to LF); `normalizeLine` runs on the title line only, in both `parse` and `plain` (`CaptureParser.kt:105-148`). Covered by `body lines keep their indentation, tabs and inner blank lines`, `plain splits lines but never parses and keeps the body verbatim` and the web test `body lines are stored verbatim, with and without parsing`.
- CR-2 (tokens parsed in the body): resolved. `TOKEN.replace` runs only on the title line (`CaptureParser.kt:75-77`); a title line of only recognized tokens takes the next line, unparsed, as the title. KDoc, the `CapturePreviewResponse` schema, `api/openapi.yaml` and the generated web model all say "Only the title line ... is parsed" (19c0d41e). Covered by parser and web tests, including `a later line never sets project, contexts, due date or waiting, even when the title line has none`.
- CR-3 (concurrent duplicate id untested): resolved. `a concurrent capture with the same id returns 200 with the winner's task and links no files again` (CaptureWebTests.kt:230) forces the race with a held transaction and asserts 200, one task row and one file link; fixes.md records a mutation check showing the test reaches the fallback branch.
- CR-4 (trailing punctuation removed): resolved. A recognized token is replaced by its trailing punctuation or by the whitespace before it (`CaptureParser.kt:96-102`). Covered by `trailing punctuation is not part of a token and stays in the title` and `several trailing punctuation marks are stripped from the value and stay in the title`.
- CR-5 (relative dates in offline captures): accepted as an assumption (tasks/gtd-capture-api.md Notes, 22:05 entry), no code change; the parent decided this. Not reported again.

Criteria check, from the code (no evidence is recorded in `tasks.py verify` yet; the parent still has to record it):
- #1 met in code: 201 + ETag + Location for a new capture to INBOX (WAITING with `^`); a repeated id returns 200 with the existing task, unchanged, without linking files again, also in the race path; trashed id 409; non-UUIDv7 id 400 through `TaskService.create`. Tests in CaptureWebTests and CaptureEdgeCaseWebTests.
- #2 met in code: `CaptureParser` is a pure `object` with no I/O, `today` and the candidates are passed in; tested by CaptureParserTests and CaptureParserEdgeCaseTests; `/capture/preview` is read-only and returns the parse result.
- #3 met in code: file ids are de-duplicated, checked against the FILE resolver (unknown or trashed gives 400 `fileIds`) and linked through core `LinkService.create` in the capture transaction.
- #4 / #5: not executed by this reviewer (a test-writer is running in the same clone); the parent must run and record them. The contract in `api/openapi.yaml` and `web/src/app/api/generated/` matches the DTOs, including the 19c0d41e description change.
- Architecture: gtd depends only on core `LinkService`/`ItemResolverRegistry`/`SettingsService`, not on the files module; one repository method added in `gtd.infra`; no migration needed; no logging of capture text or notes; Problem Details through the existing `InvalidRequestException`/`ConflictException`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-6 | A `null` element in `fileIds` gives a server error instead of a 400**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/CaptureDtos.kt:29`
    - Category: Correctness
    - Problem: `fileIds: List<UUID>?` has no element constraint, and the Kotlin Jackson module (no strict null checks are configured in `server/src/main`) lets `{"text":"x","fileIds":[null]}` through as a list containing `null`. `CaptureService.capture` then passes it to `ItemResolver.exists(id: UUID)` (`CaptureService.kt:58`), whose non-null parameter check throws a `NullPointerException`, i.e. an unhandled error rather than a Problem Details 400. `capture rejects malformed bodies` (CaptureEdgeCaseWebTests.kt:360) covers `"not-a-uuid"` but not `null`. The same pattern exists in the older `TaskRequest.contextIds`, so this is consistent with existing code, but the capture endpoint is the one fed by client retry queues.
    - Why it matters: A malformed client payload should get a 400 the client can show, not a 5xx that a retry queue (SPEC.md §9.3) would retry forever.
    - Suggested fix: Annotate the elements (`List<@NotNull UUID>?` with `@field:Valid`/type-use constraint) or reject `null` elements in `toCommand()` with `InvalidRequestException("fileIds", ...)`, and add the `[null]` case to `capture rejects malformed bodies`.

- [ ] **CR-7 | The request schema does not describe how the title is chosen**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/CaptureDtos.kt:15`
    - Category: Quality
    - Problem: The `CaptureRequest` description says "The first line of `text` becomes the title". The code uses the first non-blank line, and with `parse` a title line of only recognized tokens makes the next non-blank line the title (`CaptureParser.kt:107-111`). Neither the request nor the preview schema mentions this fallback, so web and Android clients reading `api/openapi.yaml` see a different rule than the server applies.
    - Why it matters: Small contract drift; client authors (web capture dialog, Android share) may build a preview or validation on the documented rule.
    - Suggested fix: Say "the first non-blank line" and mention the token-only fallback in the `CaptureRequest`/`CapturePreviewResponse` descriptions, then regenerate `api/openapi.yaml` and the web client.

## Review conclusion

No material issues found. The prior findings CR-1 to CR-4 are resolved in the code and covered by tests, CR-5 is recorded as an assumption. CR-6 and CR-7 are low-priority and optional. The parent still has to run and record criteria #4 and #5 (`./gradlew test --tests 'ch.sonensei.cockpit.gtd.*'`, `OpenApiContractTest` without the flag) and `.taskcheck`.
