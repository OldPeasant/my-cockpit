-- Resumable chunked uploads (SPEC.md §8.2): one row per upload in progress. The chunks live on disk under the data
-- directory, uploads/<id>/<n> (files.infra.ChunkStore); completing the upload turns them into a file and deletes the row.
-- folder_id has no foreign key: a purged folder must not be blocked by an abandoned upload; completing checks it.

create table upload_session (
    id         uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version    bigint      not null,
    folder_id  uuid,
    name       text        not null,
    mime       text        not null,
    size       bigint      not null,
    chunk_size integer     not null,
    constraint upload_session_size check (size >= 0),
    constraint upload_session_chunk_size check (chunk_size > 0)
);

create index upload_session_created on upload_session (created_at);
