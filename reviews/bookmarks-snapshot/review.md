# Code Review

Date: 2026-10-25

Task: bookmarks-snapshot - Readable snapshots

Scope: task commits `a116ea33e..125489c20` (committed changes only; working tree clean)

Requirements:
- `tasks/bookmarks-snapshot.md` acceptance criteria 1-2 and its two recorded assumptions (POST /{id}/snapshot with If-Match, synchronous fetch with the `cockpit.bookmarks.fetch.*` limits, 502 + `errorCode`, HTML file in Attachments, no images, CSP, previous snapshot to Trash)
- SPEC.md §8.4 (readable snapshot), §4.2 (Problem Details, ETag/If-Match)
- docs/ARCHITECTURE.md (conventions, packages), AGENTS.md project notes (logging, generated files)
- Existing code: `BookmarkService`, `BookmarkMetadataWorker`, `PageFetcher`, `FilesService`, `ProblemDetailsAdvice`

Git range:
- Branch: main
- HEAD: 125489c20
- Base/start: a116ea33e37c47a2460a3b6ee97ec95c6e65d7bb
- Commits reviewed: 2 (2424773cf implementation, 125489c20 edge-case tests and test report)

Summary: 0 P0, 0 P1, 1 P2, 2 P3

Checked without findings: sanitization (jsoup `Cleaner` with a tag/attribute safelist, http/https/mailto only, title and source inserted as text nodes, CSP and no-referrer meta); SSRF (reuses `PageFetcher`, same checks as the metadata worker); transactions (fetch outside a transaction, `open-in-view=false`, URL re-checked in the write transaction, concurrent writes end in an optimistic-lock 409 and the blob is removed on rollback by `FilesService.storeFile`); logging (bookmark id plus fixed `PageFetchError` messages or exception class names only, no URL or page content); Problem Details for 502 with `errorCode`; If-Match checked before the fetch; file name validation compatible with `FileNames.validate`; `api/openapi.yaml` and the generated web client match the controller. Both acceptance criteria are covered by tests (46 tests in `ch.sonensei.cockpit.bookmarks.snapshot.*`, run by the test-writer against Postgres via Testcontainers).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Page-wide wrappers are removed before scoring and nothing falls back, so some pages end as NO_CONTENT or lose their article**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/ReadableExtractor.kt:59` (`NEVER_CONTENT` includes `form`, line 180-182) and `:61-64` (removal of "unlikely" class/id elements)
    - Category: Correctness
    - Problem: Before any scoring, `mainContent` removes every `<form>` in the body and every element whose class or id matches `UNLIKELY` without matching `MAYBE`. Both removals take the whole subtree with them. `KEEP_TAGS` protects only an `article`/`main` element itself, not its ancestors. A page whose content sits inside such a wrapper loses all of it. Two examples: ASP.NET WebForms pages wrap the whole body in `<form id="aspnetForm">`, and layouts use wrappers like `<div class="layout has-sidebar"><article>...</article></div>`. The snapshot is then empty (`readable.text.isEmpty()` gives 502 `NO_CONTENT`) or contains only what was outside the wrapper. Mozilla's Readability, which the criterion names as the model, handles this. It runs the extraction again without the "strip unlikely" flag when the result is too short, and it removes forms only conditionally inside the chosen article. No test covers a wrapper ancestor. `an article element is never dropped because of its class` checks only the article element itself.
    - Why it matters: Criterion 1 requires extracting the main content. On these page layouts "Save snapshot" fails or saves the wrong part, and the user gets no hint why.
    - Suggested fix: If the first pass yields no or very little text, run `mainContent` again on a fresh parse without the unlikely-class removal (and without dropping `form` wrappers). Or skip removing an element that contains an `article`/`main` or most of the body's text. Add an extractor test for a `<form>` wrapper and for a wrapper with an unlikely class around the article.

## P3 Low

- [ ] **CR-2 | Snapshots are silently truncated at the metadata fetch limit**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/BookmarkSnapshotService.kt:73` (with `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/BookmarkFetchProperties.kt:14`, `maxSize` default 1MB, documented as "the metadata is in its head")
    - Category: Correctness
    - Problem: The snapshot reuses `PageFetcher` with the metadata limit. A page longer than `max-size` (inline scripts and JSON often push modern article pages past 1 MB) is cut off. jsoup parses the partial document and the saved copy ends mid-article. `FetchedPage` does not say whether the body was truncated, so neither the file nor the API response shows that the copy is incomplete. The assumption records that the limits are reused, but not this effect.
    - Why it matters: The user trusts the snapshot as the readable copy of the page and may learn only later that it is incomplete.
    - Suggested fix: Mention the truncation in the recorded assumption so a human can decide. Possible follow-ups are a separate, larger snapshot size limit or a truncation flag on `FetchedPage` (both outside this task's `touches`, so a follow-up task).

- [ ] **CR-3 | The snapshot service duplicates BookmarkService's change bookkeeping**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/BookmarkSnapshotService.kt:86-97`
    - Category: Architecture
    - Problem: The write transaction saves the bookmark through `BookmarkRepository` and repeats by hand what `BookmarkService.changed` does (`Hibernate.initialize(tags)`, `indexer.indexBookmark`, `changes.publish(... UPDATED)`). The `BookmarkService` KDoc says that every change of a bookmark re-indexes it and publishes a change event. This is now a second write path that has to be kept in sync by hand.
    - Why it matters: If `BookmarkService` later adds a step to every bookmark change (for example link-index upkeep or another event), the snapshot path will silently skip it.
    - Suggested fix: Add a small `BookmarkService` method (for example `attachSnapshot(id, expectedUrl, store: () -> UUID)`) that runs the URL check, the update and `changed` in one place. This would be a follow-up if `bookmarks/domain` must stay outside this task's `touches`. Otherwise, add a comment pointing to `BookmarkService.changed`.

## Review conclusion

Material issues found; fix before completing the task. CR-1 (P2) affects criterion 1 for a class of real-world pages. The parent worker decides whether to fix it now or record it as a follow-up. CR-2 and CR-3 are low priority.
