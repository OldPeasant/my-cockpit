-- Calendar sync state (SPEC.md §6.2). Kept apart from calendar_source/calendar so a sync run does not change their
-- version (ETag) and they stay plain user data.

-- One row per external source, created by its first sync: when it was last attempted, when it last succeeded and the
-- error of the last attempt (null after a successful one). The message is safe to show (no secrets, no event content).
create table calendar_source_sync (
    source_id       uuid primary key references calendar_source (id) on delete cascade,
    last_attempt_at timestamptz not null,
    last_success_at timestamptz,
    last_error      text
);

-- The provider's incremental sync cursor of one calendar (Google syncToken, Graph deltaLink, CalDAV sync-token), as
-- the adapter returned it. No row: the next fetch is a full one.
create table calendar_sync_cursor (
    calendar_id uuid primary key references calendar (id) on delete cascade,
    cursor      text not null
);
