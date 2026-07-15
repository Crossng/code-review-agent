create table code_chunk (
    id bigserial primary key,
    project_id bigint not null references project(id) on delete cascade,
    code_file_id bigint not null references code_file(id) on delete cascade,
    symbol_id bigint references code_symbol(id) on delete cascade,
    chunk_type varchar(50) not null,
    content text not null,
    summary text,
    start_line int,
    end_line int,
    created_at timestamp with time zone not null
);

create index idx_code_chunk_project on code_chunk(project_id);
create index idx_code_chunk_file on code_chunk(code_file_id);
create index idx_code_chunk_symbol on code_chunk(symbol_id);

