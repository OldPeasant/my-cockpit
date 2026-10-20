-- GTD data model (SPEC.md §5.2): areas of focus, contexts, projects, tasks, their contexts and checklist items.
-- Enum columns store the Kotlin enum names (ch.sonensei.cockpit.gtd.domain).

create table area (
    id         uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version    bigint      not null,
    title      text        not null,
    position   integer     not null default 0
);

create index area_position on area (position, id);

-- Contexts are @-tags (@home, @phone); names are unique regardless of case.
create table context (
    id         uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version    bigint      not null,
    name       text        not null,
    color      text,
    position   integer     not null default 0
);

create unique index context_name on context (lower(name));
create index context_position on context (position, id);

create table project (
    id             uuid primary key,
    created_at     timestamptz not null,
    updated_at     timestamptz not null,
    version        bigint      not null,
    deleted_at     timestamptz,
    title          text        not null,
    description_md text,
    area_id        uuid references area (id) on delete set null,
    status         text        not null,
    next_task_id   uuid,
    due_date       date,
    review_date    date,
    completed_at   timestamptz,
    constraint project_status check (status in ('ACTIVE', 'ON_HOLD', 'SOMEDAY', 'COMPLETED', 'DROPPED'))
);

create index project_status on project (status) where deleted_at is null;
create index project_area on project (area_id) where deleted_at is null;
create index project_due_date on project (due_date) where deleted_at is null and due_date is not null;

-- waiting_for_contact_id points into the contacts module, so it is no foreign key (like link item ids).
create table task (
    id                     uuid primary key,
    created_at             timestamptz not null,
    updated_at             timestamptz not null,
    version                bigint      not null,
    deleted_at             timestamptz,
    title                  text        not null,
    notes_md               text,
    list                   text        not null,
    project_id             uuid references project (id) on delete set null,
    position               integer     not null default 0,
    energy                 text,
    creativity             text,
    time_estimate_min      integer,
    due_date               date,
    due_time               time,
    scheduled_date         date,
    tickler_date           date,
    waiting_for_contact_id uuid,
    waiting_for_text       text,
    waiting_since          date,
    recurrence_rule        text,
    recurrence_mode        text,
    completed_at           timestamptz,
    constraint task_list check (list in ('INBOX', 'NEXT', 'WAITING', 'SOMEDAY', 'SCHEDULED')),
    constraint task_energy check (energy in ('LOW', 'MEDIUM', 'HIGH')),
    constraint task_creativity check (creativity in ('ROUTINE', 'MIXED', 'CREATIVE')),
    constraint task_recurrence_mode check (recurrence_mode in ('FIXED', 'AFTER_COMPLETION')),
    constraint task_time_estimate check (time_estimate_min >= 0),
    constraint task_due_time_needs_date check (due_time is null or due_date is not null)
);

-- Open (not done, not deleted) tasks by list, and the project's ordered task list.
create index task_open_list on task (list, position, id) where deleted_at is null and completed_at is null;
create index task_project on task (project_id, position, id) where deleted_at is null;
create index task_due_date on task (due_date) where deleted_at is null and completed_at is null and due_date is not null;
create index task_scheduled_date on task (scheduled_date)
    where deleted_at is null and completed_at is null and scheduled_date is not null;
create index task_tickler_date on task (tickler_date)
    where deleted_at is null and completed_at is null and tickler_date is not null;
-- Logbook: done tasks, newest first.
create index task_completed_at on task (completed_at desc) where deleted_at is null and completed_at is not null;

alter table project
    add constraint project_next_task foreign key (next_task_id) references task (id) on delete set null;

create table task_context (
    task_id    uuid not null references task (id) on delete cascade,
    context_id uuid not null references context (id) on delete cascade,
    primary key (task_id, context_id)
);

create index task_context_context on task_context (context_id);

create table checklist_item (
    id         uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version    bigint      not null,
    task_id    uuid        not null references task (id) on delete cascade,
    text       text        not null,
    done       boolean     not null default false,
    position   integer     not null default 0
);

create index checklist_item_task on checklist_item (task_id, position, id);
