create table repository_snapshot (
    id bigserial primary key,
    project_id bigint not null references project(id) on delete cascade,
    branch varchar(100) not null,
    commit_sha varchar(80) not null,
    file_count int not null,
    java_file_count int not null,
    created_at timestamp with time zone not null
);

create index idx_repository_snapshot_project on repository_snapshot(project_id, created_at);

create table code_file (
    id bigserial primary key,
    project_id bigint not null references project(id) on delete cascade,
    snapshot_id bigint not null references repository_snapshot(id) on delete cascade,
    path text not null,
    language varchar(50) not null,
    sha256 varchar(64) not null,
    size_bytes int not null,
    created_at timestamp with time zone not null
);

create index idx_code_file_project_path on code_file(project_id, path);
create index idx_code_file_snapshot on code_file(snapshot_id);

create table code_symbol (
    id bigserial primary key,
    project_id bigint not null references project(id) on delete cascade,
    code_file_id bigint not null references code_file(id) on delete cascade,
    symbol_type varchar(50) not null,
    name varchar(255) not null,
    qualified_name text not null,
    annotations jsonb,
    start_line int,
    end_line int
);

create index idx_code_symbol_project_type on code_symbol(project_id, symbol_type);
create index idx_code_symbol_qualified_name on code_symbol(qualified_name);

