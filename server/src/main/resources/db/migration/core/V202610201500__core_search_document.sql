-- Global search (SPEC.md §4.4): one row per searchable item of any module, maintained by the module through
-- SearchIndexer. item_id is not a foreign key: the item lives in its module's table.
-- tsv holds the words of title (weight A) and body (weight B) twice: with the 'simple' config (exact words, any
-- language, names) and the 'english' config (stemmed), so a query matches either form.
create table search_document (
    item_type  text        not null,
    item_id    uuid        not null,
    title      text        not null,
    body       text        not null default '',
    updated_at timestamptz not null,
    tsv        tsvector generated always as (
        setweight(to_tsvector('simple'::regconfig, title), 'A') ||
        setweight(to_tsvector('english'::regconfig, title), 'A') ||
        setweight(to_tsvector('simple'::regconfig, body), 'B') ||
        setweight(to_tsvector('english'::regconfig, body), 'B')
    ) stored,
    primary key (item_type, item_id)
);

create index search_document_tsv on search_document using gin (tsv);
-- Typo-tolerant title matching (word_similarity, the <% operator) for short or fuzzy queries.
create index search_document_title_trgm on search_document using gin (title gin_trgm_ops);
