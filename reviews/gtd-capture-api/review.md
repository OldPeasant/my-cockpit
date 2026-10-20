# Code Review

Date: 2026-10-20

Task: gtd-capture-api - Quick capture endpoint and syntax parser

Scope: task commits 3c67be38 (code + tests) and 67fef4a0 (regenerated OpenAPI contract and web client) on top of base b657f97a (`git diff b657f97a..67fef4a0`), committed changes only

Requirements:
- tasks/gtd-capture-api.md acceptance criteria and the four recorded assumptions in its Notes
- SPEC.md §4.2 (UUIDv7, ETag, Problem Details), §4.3 (links), §4.5 (quick capture), §5.4 (API sketch), §9.3 (offline capture)
- AGENTS.md project notes (module boundaries, generated files, conventions)
- Existing conventions in TaskController/TaskService (idempotent create, race handling), LinkService, FileItemResolver

Git range:
- Branch: main
- HEAD: 67fef4a0
- Base/start: b657f97aef465ebcbcb633b105ee58e0635f4200
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 3 P2, 2 P3

Criteria check (from the code; no evidence recorded yet in `tasks.py verify`, that is the parent's step):
- #1 met in code: `POST /api/v1/capture` creates INBOX (WAITING with `^`), 201 + ETag + Location; repeated id returns 200 with the existing task, no duplicate, no re-linking of files; trashed id gives 409; non-UUIDv7 id gives 400 (via `TaskService.create`). Tested in CaptureWebTests.
- #2 met in code: `CaptureParser` is a pure object, unit-tested; `#project` case-insensitive, unknown kept as text; `@context`; `!today|!tomorrow|!YYYY-MM-DD`; `^waiting`; `/capture/preview` returns the parse result read-only.
- #3 met in code: file ids are de-duplicated, validated against the FILE resolver (unknown/trashed -> 400 `fileIds`) and linked through `LinkService.create` in the same transaction.
- #5: `api/openapi.yaml` and `web/src/app/api/generated/` contain both operations and the new schemas; consistent with the DTOs.
- Module boundaries respected: gtd uses only core `LinkService`/`ItemResolverRegistry`, not the files module. One transaction per capture; preview and the race fallback are read-only. Authentication required (test present).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Body lines of the captured text are rewritten: indentation stripped and whitespace collapsed, also with `parse: false`**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/CaptureParser.kt:115`
    - Category: Correctness
    - Problem: `splitTitle` runs `replace(Regex("[ \t]+"), " ").trim()` on every line, not just the title line, and the result of the remaining lines becomes `extraNotes`, which is put in front of the Markdown `notesMd`. `plain()` (used for `parse: false`) uses the same function. A shared text such as `"Title\n    code block\n  - nested item\nA\tB"` ends up in the notes as `"code block\n- nested item\nA B"`.
    - Why it matters: The recorded assumption says the rest of the text goes into the notes "so long shared text is not lost". Android share and offline capture (§4.5, §9.3) send long shared text through this path. Markdown indentation (code blocks, nested lists) and tabs are silently changed in stored user content, and `parse: false` ("keeps the text as it is") does not keep it.
    - Suggested fix: Normalize whitespace only on the title line. Keep the remaining lines verbatim, at most dropping leading/trailing blank lines. Add a test with indented and tab-containing body lines for both `parse` and `plain`.

- [ ] **CR-2 | Capture tokens are interpreted and removed in the body lines (notes), not only in the title line**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/CaptureParser.kt:71`
    - Category: Correctness
    - Problem: `TOKEN.replace(text)` runs over the whole multi-line text before it is split into title and notes. The lookbehind `\s` also matches a newline. In the rest of the text, any `^word` after whitespace moves the task to WAITING and sets `waitingFor`, `!today`/`!2026-..` sets the due date, and `@ctx`/`#project` tokens that name existing items are removed. Example: `"Read article\nsee footnote ^1 and ask @home"` gives a WAITING task with waitingFor `"1"`, and `@home` is stripped from the notes. Neither the task's assumptions nor the KDoc of `ParsedCapture` ("title is the first line of the text without the recognized tokens, extraNotes the rest of the text") say that tokens in the body count. No test covers a token on a later line.
    - Why it matters: Shared web or email text often contains `@name`, `#tag`, `^` or `!` sequences. With `parse` defaulting to true, such a capture can land on the wrong list with a wrong due date, and its notes lose text. The user does not see this, because only the title line is visible in the capture field.
    - Suggested fix: Parse tokens only in the first non-blank line, and pass the remaining lines through unchanged. If body parsing is intended, record it as an assumption, make sure the notes keep the text, and add tests either way.

- [ ] **CR-3 | The concurrent duplicate-id path of `POST /capture` is untested**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/CaptureController.kt:66`
    - Category: Tests
    - Problem: The `catch (DataIntegrityViolationException)` branch, which answers a racing repeat of the same client id through `captures.existing(id)`, has no test. CaptureWebTests only covers sequential repeats, which return early through `existingCreate`. The same branch in TaskController is also untested, so nothing covers this behavior.
    - Why it matters: SPEC.md §9.3 makes capture the target of a WorkManager retry queue. A retry while the first request is still running is the realistic case where idempotency can break, and the result would be a 500 or a duplicate instead of 200.
    - Suggested fix: Add a Testcontainers test that sends two captures with the same UUIDv7 in parallel (for example two threads and a latch, or by calling the service from two transactions). Assert one 201, one 200 with the same task, one task row, and file links only once.

## P3 Low

- [ ] **CR-4 | Trailing punctuation is removed together with a recognized token**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/CaptureParser.kt:89`
    - Category: Correctness
    - Problem: The token's value is matched without its trailing `.,;:!?)`, but the replacement removes the whole match (`match.value`), including that punctuation. `"Buy tiles (for #garden)"` becomes the title `"Buy tiles (for"`, and `"Call @phone, then"` loses its comma.
    - Why it matters: The titles that result are a little broken. The KDoc says the punctuation is "not part of its value", so a reader expects it to stay in the text.
    - Suggested fix: Replace a recognized token with the stripped suffix (`match.value.removePrefix(sigil + value)`), not with `""`, and adjust the test `trailing punctuation is not part of a token`.

- [ ] **CR-5 | `!today`/`!tomorrow` in an offline capture resolve to the upload date, not the capture date**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/CaptureService.kt:95`
    - Category: Correctness
    - Problem: Relative due dates are resolved with the server clock when the request arrives. An Android capture queued offline (SPEC.md §9.3, which stores `created_at` locally) and uploaded the next day gets a due date one day later than the user meant. The request has no way to carry the capture time, and no assumption records this behavior.
    - Why it matters: Offline capture is a main use of this endpoint. The shifted due dates are silent and only happen across a day boundary.
    - Suggested fix: Either record the behavior as an assumption, or accept an optional client capture timestamp or date (for example `capturedAt`) and use it as "today" when it is present. Another option is for the Android client to resolve relative tokens before it queues the capture.

## Review conclusion

Material issues found; fix before completing the task.
