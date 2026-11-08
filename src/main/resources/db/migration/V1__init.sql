-- Baseline migration: proves Flyway runs; real tables come with the features.
create table app_meta (
    meta_key   varchar(100) primary key,
    meta_value varchar(255) not null
);

insert into app_meta (meta_key, meta_value) values ('schema_origin', 'ticketflow');
