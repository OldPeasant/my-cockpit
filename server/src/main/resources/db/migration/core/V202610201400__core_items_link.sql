-- Links between any two items of any module (SPEC.md §4.3). Item ids are not foreign keys: the ends live in other
-- modules' tables; links of a permanently deleted item are removed through ItemDeletedEvent.
-- origin: MANUAL links come from the API, SYNC links are owned by their source (e.g. inline note links) and are
-- replaced by LinkService.sync. The two origins are independent: a pair can have a MANUAL link and SYNC links in
-- both directions at the same time; LinkService.linksOf shows each linked item once.
create table link (
    id          uuid primary key,
    created_at  timestamptz not null,
    updated_at  timestamptz not null,
    version     bigint      not null,
    source_type text        not null,
    source_id   uuid        not null,
    target_type text        not null,
    target_id   uuid        not null,
    label       text,
    origin      text        not null,
    constraint link_origin check (origin in ('MANUAL', 'SYNC')),
    constraint link_not_self check (source_type <> target_type or source_id <> target_id)
);

-- Manual links are bidirectional in presentation, so a pair of items has at most one, in either direction.
create unique index link_manual_pair on link (
    least(source_type || ':' || source_id, target_type || ':' || target_id),
    greatest(source_type || ':' || source_id, target_type || ':' || target_id)
) where origin = 'MANUAL';
-- Each source owns at most one sync link per target; the other end may own its own sync link back.
create unique index link_sync_pair on link (source_type, source_id, target_type, target_id) where origin = 'SYNC';
create index link_source on link (source_type, source_id);
create index link_target on link (target_type, target_id);
