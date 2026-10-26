-- iTIP REPLYs taken (SPEC.md §6.2, RFC 5546 §2.1.5): per attendee of a LOCAL event (event_id: the series master or
-- single event, the iTIP UID) and answered occurrence (recurrence_id_at/recurrence_id_date like calendar_event; both
-- null for an answer to the whole event), the SEQUENCE and DTSTAMP of the newest answer taken. An answer that is older
-- (lower SEQUENCE, or the same SEQUENCE and an older DTSTAMP) arrives late and is ignored. Kept apart from
-- calendar_event.attendees so that edits of the event's attendees never lose it. email is lower case.
create table calendar_itip_reply (
    id                 uuid primary key,
    event_id           uuid        not null references calendar_event (id) on delete cascade,
    recurrence_id_at   timestamptz,
    recurrence_id_date date,
    email              text        not null,
    sequence           integer,
    stamp              timestamptz,
    constraint calendar_itip_reply_occurrence check (recurrence_id_at is null or recurrence_id_date is null)
);

create unique index calendar_itip_reply_attendee
    on calendar_itip_reply (event_id, recurrence_id_at, recurrence_id_date, email) nulls not distinct;
