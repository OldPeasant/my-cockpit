-- Files (web drive, SPEC.md §8.2): a folder tree and file metadata. Blob contents live on disk under
-- ${COCKPIT_DATA_DIR}/blobs/<storage_key> (files.infra.BlobStore). A null parent_id / folder_id is the drive root.

create table folder (
    id         uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version    bigint      not null,
    deleted_at timestamptz,
    parent_id  uuid references folder (id),
    name       text        not null,
    -- Auto-managed folders (/Attachments and its children) carry their SpecialFolder name; they cannot be deleted or renamed.
    special    text,
    constraint folder_name_not_blank check (length(trim(name)) > 0),
    constraint folder_special check (special in ('ATTACHMENTS', 'TASKS', 'NOTES', 'MAIL', 'JOURNAL'))
);

-- Names are unique per parent among folders not in the Trash, regardless of case; the root counts as one parent.
create unique index folder_name on folder (parent_id, lower(name)) nulls not distinct where deleted_at is null;
create unique index folder_special_unique on folder (special) where special is not null;
create index folder_parent on folder (parent_id);

create table file (
    id          uuid primary key,
    created_at  timestamptz not null,
    updated_at  timestamptz not null,
    version     bigint      not null,
    deleted_at  timestamptz,
    folder_id   uuid references folder (id),
    name        text        not null,
    mime        text        not null,
    size        bigint      not null,
    sha256      text        not null,
    storage_key text        not null,
    constraint file_name_not_blank check (length(trim(name)) > 0),
    constraint file_size check (size >= 0),
    constraint file_sha256 check (sha256 ~ '^[0-9a-f]{64}$')
);

create unique index file_storage_key on file (storage_key);
create index file_folder on file (folder_id);
