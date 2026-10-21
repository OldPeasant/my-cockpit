-- Habits (SPEC.md §8.5). schedule DAILY: due every day; TIMES_PER_WEEK: on times_per_week days of a week, any days;
-- WEEKDAYS: on the days in weekdays (JSON array of java.time.DayOfWeek names). A day counts as done when its check
-- reaches target_count. reminder_time is a local time in the owner's time zone; the module keeps one pending reminder
-- (core table reminder) per habit, for its next due day.
create table habit (
    id             uuid primary key,
    created_at     timestamptz not null,
    updated_at     timestamptz not null,
    version        bigint      not null,
    name           text        not null,
    schedule       text        not null,
    times_per_week integer,
    weekdays       jsonb       not null default '[]',
    target_count   integer     not null default 1,
    reminder_time  time,
    archived       boolean     not null default false,
    constraint habit_schedule check (schedule in ('DAILY', 'TIMES_PER_WEEK', 'WEEKDAYS')),
    constraint habit_times_per_week check (
        (schedule = 'TIMES_PER_WEEK') = (times_per_week is not null) and coalesce(times_per_week between 1 and 7, true)
    ),
    constraint habit_target_count check (target_count >= 1)
);

create index habit_name on habit (lower(name), id);

-- How often a habit was done on a local date. A count of 0 is no row.
create table habit_check (
    id         uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version    bigint      not null,
    habit_id   uuid        not null references habit (id) on delete cascade,
    check_date date        not null,
    count      integer     not null,
    constraint habit_check_count check (count >= 1),
    constraint habit_check_unique unique (habit_id, check_date)
);
