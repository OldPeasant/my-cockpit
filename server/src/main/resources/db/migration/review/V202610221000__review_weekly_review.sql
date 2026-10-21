-- Weekly reviews (SPEC.md §8.6). step is the wizard step the user is on, by name. A review with completed_at null is
-- the open one: there is at most one, so a review is always resumed, never started twice.
create table weekly_review (
    id           uuid primary key,
    created_at   timestamptz not null,
    updated_at   timestamptz not null,
    version      bigint      not null,
    started_at   timestamptz not null,
    completed_at timestamptz,
    step         text        not null,
    constraint weekly_review_step check (
        step in ('GET_CLEAR', 'PAST_CALENDAR', 'UPCOMING_CALENDAR', 'WAITING_FOR', 'PROJECTS', 'NEXT_ACTIONS', 'SOMEDAY',
                 'REFLECTION')
    )
);

create unique index weekly_review_one_open on weekly_review ((true)) where completed_at is null;
create index weekly_review_completed on weekly_review (completed_at desc, id desc) where completed_at is not null;
