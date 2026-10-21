-- Journal entries (SPEC.md §8.5): one Markdown entry per local date, with an optional mood (1-5) and tags.
-- Images are cockpit-file:{fileId} references to files in /Attachments/Journal.
create table journal_entry (
    id         uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version    bigint      not null,
    deleted_at timestamptz,
    entry_date date        not null,
    body_md    text        not null default '',
    mood       integer,
    constraint journal_entry_mood check (mood between 1 and 5)
);

-- One entry per date; entries in the Trash do not count (a restore is refused while the date has another entry).
create unique index journal_entry_date on journal_entry (entry_date) where deleted_at is null;

-- An entry has each tag once regardless of case.
create table journal_entry_tag (
    entry_id uuid not null references journal_entry (id) on delete cascade,
    tag      text not null,
    primary key (entry_id, tag)
);

create index journal_entry_tag_tag on journal_entry_tag (lower(tag));
