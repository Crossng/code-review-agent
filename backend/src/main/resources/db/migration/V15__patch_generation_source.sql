alter table patch_record
    add column generation_provider varchar(100) not null default 'UNKNOWN',
    add column generation_model varchar(255);
