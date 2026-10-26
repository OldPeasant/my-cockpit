-- Text extracted from file contents for full-text search (SPEC.md §4.4), by files.fulltext.FileTextIndexer.
-- A cache: rebuilt from the blobs, so not backed up. One row per file the job has looked at; sha256 is the content
-- digest the row was extracted from (a file whose digest differs is extracted again).
create table file_text (
    file_id      uuid primary key references file (id) on delete cascade,
    sha256       text        not null,
    -- EXTRACTED: text holds the (possibly truncated) text; SKIPPED: no text type or too large; FAILED: unreadable.
    status       text        not null,
    text         text,
    extracted_at timestamptz not null,
    constraint file_text_status check (status in ('EXTRACTED', 'SKIPPED', 'FAILED'))
);
