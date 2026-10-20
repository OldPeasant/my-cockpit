-- Reminders for any item (SPEC.md §4.6). The item is not a foreign key: it lives in another module's table;
-- reminders of a permanently deleted item are removed through ItemDeletedEvent.
-- fired_at is set once, in the transaction that fires the reminder, so it never fires twice (also across restarts).
create table reminder (
    id          uuid primary key,
    created_at  timestamptz not null,
    updated_at  timestamptz not null,
    version     bigint      not null,
    item_type   text        not null,
    item_id     uuid        not null,
    remind_at   timestamptz not null,
    channel     text        not null,
    fired_at    timestamptz,
    constraint reminder_channel check (channel in ('NOTIFICATION'))
);

-- The scheduler and the Android sync read pending reminders by time.
create index reminder_pending on reminder (remind_at) where fired_at is null;
create index reminder_item on reminder (item_type, item_id);
