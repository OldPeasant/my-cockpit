# Code Review

Date: 2026-10-25

Task: backup-contrib-bookmarks - Backup contributor: bookmarks

Scope: task commits e7a98bbe0 (implementation) and aa9c52e01 (extra test, test report) on top of the task base ca5137e38 (`git merge-base HEAD origin/main`); committed changes only.

Requirements:
- tasks/backup-contrib-bookmarks.md (acceptance criteria and the recorded Netscape mirror assumption)
- SPEC.md §8.4 (bookmarks), §10.2 (backup layout: `data/bookmarks.jsonl`, `readable/bookmarks.html` in Netscape format), §14
- AGENTS.md project notes, docs/QUALITY.md
- Conventions of sibling contributors (contacts/backup/ContactsBackupContributor.kt) and the `BackupFixture` contract (server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupFixture.kt)
- Bookmarks schema (db/migration/bookmarks/V202610211800__bookmarks_model.sql), `Bookmark` entity, `SearchIndexer.indexBookmark`, `BookmarkMetadataWorker`

Git range:
- Branch: main
- HEAD: aa9c52e01f34cc847f27a0346116bcdd32708a58
- Base/start: ca5137e388acaf68185583e7329f09cb4d85b1d8
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review notes (no findings)

- Criterion 1 (`data/bookmarks.jsonl`; `readable/bookmarks.html` in Netscape format, parse test): `export` writes every
  row of `bookmark` (Trash included) ordered by id, with all schema columns (id, created_at, updated_at, version,
  deleted_at, url, title, description, favicon, read_later, read_at, snapshot_file_id, metadata_status,
  metadata_fetch) and tags sorted in code for deterministic output. The mirror is a flat Netscape file with the
  standard DOCTYPE/META/TITLE/H1/DL header, entries ordered `created_at desc, id desc` (matching the `bookmark_list`
  index and the app list), Trash excluded, no file when no active bookmark exists. Attribute values and text are
  HTML-escaped (`&`, `<`, `>`, `"`) and line breaks collapsed, so a description or title cannot inject markup; this is
  covered by an injection test. The behaviour matches the recorded assumption (flat, newest first, no Trash, comma in
  tag -> space, TOREAD for unread read-later, ICON_URI). The parse test walks the jsoup-parsed DL as Firefox's
  `BookmarkHTMLUtils` does (DT > A attributes, following DD as description), which is the evidence the criterion asks
  for ("parse test").
- Criterion 2 (round trip): `BookmarksBackupFixture` is a plain class in the module's test package with
  `contributor = BookmarksBackupContributor.ID`, seeding a full bookmark (unicode, HTML characters, comma tag, favicon,
  snapshot file, multi-line description, FETCHED), a minimal one, a read one (FAILED), a PENDING one and a trashed one.
  It saves through the repository to avoid triggering metadata fetches that could mutate rows during comparison,
  which is a sound choice. The parent reports `*BackupRoundTripTest` 1/1 passing.
- Restore inserts rows with JDBC, preserving ids, timestamps, version and metadata state (consistent with the
  contacts contributor and the reason given in KDoc), re-inserts tags, and re-indexes only non-trashed bookmarks.
  The detached `Bookmark` built for indexing sets url, title, description and tags, which are exactly the fields
  `indexBookmark` reads (`displayTitle` derives from title/url). The KDoc claim that PENDING fetches are requeued at
  startup is backed by `BookmarkMetadataWorker`'s `ApplicationReadyEvent` listener.
- Architecture: the contributor lives in `bookmarks.backup`, uses the shared `core.backupcontrib` helpers and the
  streaming `queryForStream` pattern of sibling contributors; no cross-module coupling beyond the bookmarks domain
  and the search indexer. Changes stay within the task's `touches`.
- Security/privacy: no secrets or logging added; test data is fictitious (`example.test` hosts).
- Tests: 7 contributor tests cover all-fields export, empty export, the Netscape parse (ordering, Trash exclusion,
  timestamps, tags, icon, TOREAD, description), blank title/description fallback, escaping and single-line entries,
  no mirror when everything is trashed, exact row/tag restore incl. Trash and PENDING state, and search re-indexing
  (active indexed, trashed not). Validation reported by the parent: focused tests 7/7 and 1/1 pass, `./gradlew check`
  BUILD SUCCESSFUL (not re-run by this reviewer, which only inspects read-only).

## Review conclusion

No material issues found.
