-- Bookmarks and read-later (SPEC.md §8.4).

-- title, description and favicon are filled from the page (OpenGraph) after a save, unless the user set them;
-- metadata_status tells whether that fetch is still PENDING, FETCHED or FAILED; metadata_fetch counts the requested
-- fetches, so only the result of the latest one is stored. favicon is an absolute http(s) URL.
-- snapshot_file_id points to a readable copy of the page stored in Files (SPEC.md §8.4 "save readable snapshot").
create table bookmark (
    id               uuid primary key,
    created_at       timestamptz not null,
    updated_at       timestamptz not null,
    version          bigint      not null,
    deleted_at       timestamptz,
    url              text        not null,
    title            text,
    description      text,
    favicon          text,
    read_later       boolean     not null default false,
    read_at          timestamptz,
    snapshot_file_id uuid,
    metadata_status  text        not null default 'PENDING',
    metadata_fetch   bigint      not null default 0
);

-- The list (newest first) and the read-later queue.
create index bookmark_list on bookmark (created_at desc, id desc) where deleted_at is null;
create index bookmark_read_later on bookmark (created_at desc, id desc) where deleted_at is null and read_later and read_at is null;

-- A bookmark has each tag once regardless of case; the service reuses the spelling other bookmarks already have.
create table bookmark_tag (
    bookmark_id uuid not null references bookmark (id) on delete cascade,
    tag         text not null,
    primary key (bookmark_id, tag)
);

create index bookmark_tag_tag on bookmark_tag (lower(tag));
