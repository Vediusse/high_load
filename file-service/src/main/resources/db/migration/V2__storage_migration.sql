create table file_storage_migration (
    id varchar(160) primary key,
    object_count bigint not null check (object_count >= 0),
    completed_at timestamptz not null default clock_timestamp()
);
