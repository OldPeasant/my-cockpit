-- Test classpath only: table for the entity fixture of ch.sonensei.cockpit.core.persistence tests.
-- It shows the column layout every module table follows (BaseEntity + SoftDeletableEntity).
create table test_persistence_fixture (
    id         uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version    bigint      not null,
    deleted_at timestamptz,
    title      text        not null
);
