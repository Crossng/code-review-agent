alter table patch_record
    add column generation_mode varchar(100) not null default 'UNKNOWN';
