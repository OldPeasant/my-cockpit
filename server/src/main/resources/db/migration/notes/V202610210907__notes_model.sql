-- Notes (SPEC.md §8.1): Markdown notes with tags, pins and the last versions of their content.

-- title is optional: clients show the first line of the body when it is null.
create table note (
    id         uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version    bigint      not null,
    deleted_at timestamptz,
    title      text,
    body_md    text        not null default '',
    pinned     boolean     not null default false,
    archived   boolean     not null default false
);

-- The notes list: pinned first, then most recently changed.
create index note_list on note (archived, pinned desc, updated_at desc, id desc) where deleted_at is null;

-- A note has each tag once regardless of case; the service reuses the spelling other notes already have.
create table note_tag (
    note_id uuid not null references note (id) on delete cascade,
    tag     text not null,
    primary key (note_id, tag)
);

create index note_tag_tag on note_tag (lower(tag));

-- Earlier versions of a note's content, numbered per note; the service keeps the newest 20.
-- saved_at is when that content was last saved, created_at when it was replaced.
create table note_revision (
    id         uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version    bigint      not null,
    note_id    uuid        not null references note (id) on delete cascade,
    number     integer     not null,
    title      text,
    body_md    text        not null,
    saved_at   timestamptz not null,
    constraint note_revision_number unique (note_id, number)
);
