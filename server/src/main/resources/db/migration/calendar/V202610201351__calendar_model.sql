-- Calendar data model (SPEC.md §6.1, §6.2): sources, their calendars and events.
-- Enum columns store the Kotlin enum names (ch.sonensei.cockpit.calendar.model).

-- config holds the non-secret provider settings (CalDAV URL, ICS feed URL, account e-mail, ...) as a JSON object;
-- credentials is the provider secret (OAuth refresh token, CalDAV password), encrypted by EncryptedSecretConverter.
create table calendar_source (
    id          uuid primary key,
    created_at  timestamptz not null,
    updated_at  timestamptz not null,
    version     bigint      not null,
    type        text        not null,
    name        text        not null,
    config      jsonb       not null default '{}'::jsonb,
    credentials text,
    constraint calendar_source_type check (type in ('LOCAL', 'GOOGLE', 'MICROSOFT', 'CALDAV', 'ICS_FEED')),
    constraint calendar_source_config_object check (jsonb_typeof(config) = 'object')
);

-- external_id is the provider's calendar id (null for LOCAL calendars). At most one calendar is the default for new
-- events.
create table calendar (
    id            uuid primary key,
    created_at    timestamptz not null,
    updated_at    timestamptz not null,
    version       bigint      not null,
    source_id     uuid        not null references calendar_source (id) on delete cascade,
    name          text        not null,
    color         text,
    visible       boolean     not null default true,
    read_only     boolean     not null default false,
    is_default    boolean     not null default false,
    external_id   text,
    external_etag text
);

create index calendar_by_source on calendar (source_id, id);
create unique index calendar_external_id on calendar (source_id, external_id) where external_id is not null;
create unique index calendar_single_default on calendar (is_default) where is_default;

-- Timed events store instants (start_at/end_at, UTC) plus the IANA zone id they were planned in, so recurrences
-- keep their wall-clock time across DST; all-day events store local dates with an exclusive end_date (iCalendar
-- DTEND semantics). A recurring series is a master row with rrule; its exceptions (moved/changed/cancelled
-- occurrences) are override rows with parent_event_id and the original start of the occurrence they replace
-- (recurrence_id_at for timed series, recurrence_id_date for all-day series). Excluded occurrences (EXDATE) are
-- rows of calendar_event_exdate in the same representation.
-- attendees is a JSON array of {email, name, status}; reminder_minutes the minutes before start of each reminder.
create table calendar_event (
    id                 uuid primary key,
    created_at         timestamptz not null,
    updated_at         timestamptz not null,
    version            bigint      not null,
    deleted_at         timestamptz,
    calendar_id        uuid        not null references calendar (id) on delete cascade,
    title              text        not null,
    description        text,
    location           text,
    all_day            boolean     not null,
    start_at           timestamptz,
    end_at             timestamptz,
    time_zone          text,
    start_date         date,
    end_date           date,
    rrule              text,
    parent_event_id    uuid references calendar_event (id) on delete cascade,
    recurrence_id_at   timestamptz,
    recurrence_id_date date,
    attendees          jsonb       not null default '[]'::jsonb,
    reminder_minutes   integer[]   not null default '{}',
    status             text        not null,
    transparency       text        not null,
    conference_url     text,
    external_id        text,
    external_etag      text,
    sync_state         text        not null,
    constraint calendar_event_status check (status in ('CONFIRMED', 'TENTATIVE', 'CANCELLED')),
    constraint calendar_event_transparency check (transparency in ('OPAQUE', 'TRANSPARENT')),
    constraint calendar_event_sync_state check (sync_state in ('LOCAL', 'SYNCED', 'PENDING', 'ERROR')),
    constraint calendar_event_attendees_array check (jsonb_typeof(attendees) = 'array'),
    constraint calendar_event_timed check (
        all_day or (start_at is not null and end_at is not null and time_zone is not null and end_at >= start_at
            and start_date is null and end_date is null)),
    constraint calendar_event_all_day check (
        not all_day or (start_date is not null and end_date is not null and end_date > start_date
            and start_at is null and end_at is null)),
    constraint calendar_event_override check (
        (parent_event_id is null and recurrence_id_at is null and recurrence_id_date is null)
            or (parent_event_id is not null and num_nonnulls(recurrence_id_at, recurrence_id_date) = 1
                and rrule is null))
);

create index calendar_event_calendar_start on calendar_event (calendar_id, start_at) where deleted_at is null;
create index calendar_event_calendar_start_date on calendar_event (calendar_id, start_date)
    where deleted_at is null and all_day;
create index calendar_event_recurring on calendar_event (calendar_id) where deleted_at is null and rrule is not null;
create index calendar_event_parent on calendar_event (parent_event_id) where parent_event_id is not null;
create unique index calendar_event_override_occurrence on calendar_event (parent_event_id, recurrence_id_at, recurrence_id_date)
    nulls not distinct where parent_event_id is not null;
-- external_id is unique per calendar among masters and single events only. Overrides are identified by
-- (parent_event_id, recurrence id): iCalendar overrides (RECURRENCE-ID) share their master's UID.
create unique index calendar_event_external_id on calendar_event (calendar_id, external_id)
    where external_id is not null and parent_event_id is null;

create table calendar_event_exdate (
    event_id           uuid not null references calendar_event (id) on delete cascade,
    occurrence_at      timestamptz,
    occurrence_date    date,
    constraint calendar_event_exdate_one check (num_nonnulls(occurrence_at, occurrence_date) = 1)
);

create unique index calendar_event_exdate_unique on calendar_event_exdate (event_id, occurrence_at, occurrence_date)
    nulls not distinct;
