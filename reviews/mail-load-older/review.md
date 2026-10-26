# Code Review

Date: 2026-10-26

Task: mail-load-older - Load older mail on demand (API for MailHeaderSyncService.loadOlder)

Scope: `git diff 5e7f4c304cd0527f9d3444a0c55441034da74a5c..fd88c00df2b702e7bc2bdac7cd32c1590ce9da64 -- server api web` (generated `api/openapi.yaml` and `web/src/app/api/generated/` checked for shape only, not line by line). Committed changes only.

Requirements:
- `tasks/mail-load-older.md` acceptance criteria and the two recorded assumptions (endpoint `POST /api/v1/mail/folders/{id}/load-older?limit=` 1..200, default 100; in-memory `hasOlder` semantics, false for unified and account-Inbox lists)
- SPEC.md §7.2 (headers cached for the sync window, older loaded on demand), §4.2 (Problem Details)
- `AGENTS.md` project notes (module boundaries, generated files, logging rules), `docs/QUALITY.md`
- Existing conventions in `mail/api` (`MailConnectionAdvice`, `NotFoundException`, `InvalidRequestException`, `@Schema` usage for response DTOs)

Git range:
- Branch: main
- HEAD: fd88c00df2b702e7bc2bdac7cd32c1590ce9da64
- Base/start: 5e7f4c304cd0527f9d3444a0c55441034da74a5c
- Commits reviewed: 6 (049f4201d, 4cf6187e1, 03bda52db, 4fd023cc3, bcb202369, fd88c00df)
- Uncommitted changes: only the untracked `reviews/mail-load-older/` reports (not reviewed as code)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Completeness is recorded with a UIDVALIDITY read after the sync lock was released**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailOlderService.kt:49`
    - Category: Correctness
    - Problem: `MailOlderService.loadOlder` calls `sync.loadOlder(...)`, which holds the per-folder lock only for its own duration. The service then reads `folders.findById(folderId)?.uidValidity` outside that lock. If a scheduled `syncFolder` runs between the two and sees a UIDVALIDITY change, it does a full sync that caches only the sync window again and stores the new UIDVALIDITY. A `hasMore=false` result for the old UIDVALIDITY is then recorded as complete for the new one, and `hasOlder` stays false for that folder until the next UIDVALIDITY change or a restart, although older mail is no longer cached.
    - Why it matters: In that case clients would never offer "load older" for the folder. The window is very small and UIDVALIDITY changes are rare, so the impact is low. The class KDoc says completeness is tied to "the UIDVALIDITY it had then", but this sequence breaks that guarantee.
    - Suggested fix: Have `MailHeaderSyncService.loadOlder` return the UIDVALIDITY it worked with, for example as a field of `OlderMessagesResult`, and record that value. The change is in `mail/sync`, outside this task's `touches`, so it could go into a follow-up task. If it stays as is, record it as a known limitation, for example in the existing `hasOlder` assumption.

## Notes (no finding)

- AC1: `POST /folders/{id}/load-older` validates `limit`, returning a 400 Problem for out-of-range or non-numeric values. An unknown folder (`NotFoundException` from the sync service) and a folder gone from the server (`RemoteFolderNotFoundException` mapped to `NotFoundException`) both return 404. `MailConnectionException` returns 502, or 503 with `POOL_EXHAUSTED`, through `MailConnectionAdvice`. The endpoint is CSRF-protected like other POSTs. The response is `{loaded, hasMore}`.
- AC2: `hasOlder` is set only when `folderId != null && nextCursor == null`, so `MailFolderRepository` is queried at most once per last page. It matches the recorded assumption. Unified and account-Inbox lists return `false`.
- AC3: `MailLoadOlderGreenMailTests` is `@ExternalTest`. It loads older mail in two steps and walks the list past the window, then checks `hasOlder` turns false. It also covers a folder fully inside the window, 404 for unknown and deleted folders, 400 for out-of-range limits, 403 without CSRF, and 502 when the server is down. The unit tests (`MailLoadOlderControllerTests`, `MailOlderServiceTests`) cover the boundaries and the completeness state.
- AC4: The OpenAPI path, `MailLoadOlderResponse` and `hasOlder` match the controller and DTOs. The generated web client contains `loadOlderMail` and the new models. Non-null response fields are not marked `required`, which matches the existing DTO convention in this controller.
- `python tasks.py verify mail-load-older` still shows all four criteria unrecorded. The parent says it ran the evidence, but it still has to record it with `tasks.py verify` before running `done`.

## Review conclusion

No material issues found.
