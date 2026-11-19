create table processed_event(event_id uuid primary key,processed_at timestamptz not null default clock_timestamp());
create table file_reference(
 file_id uuid primary key,order_id uuid not null references corporate_order(id),
 relation_type varchar(32) not null check(relation_type in ('SPECIFICATION','DISCREPANCY_ACT')),
 file_version bigint not null check(file_version>=0),deleted boolean not null
);
create index ix_file_reference_order on file_reference(order_id,file_id) where not deleted;
