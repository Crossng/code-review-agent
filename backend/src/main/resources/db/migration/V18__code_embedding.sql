create table code_embedding (
    id bigserial primary key,
    chunk_id bigint not null references code_chunk(id) on delete cascade,
    project_id bigint not null references project(id) on delete cascade,
    embedding_provider varchar(100) not null,
    embedding_model varchar(255) not null,
    embedding_dimension int not null check (embedding_dimension > 0),
    content_sha256 varchar(64) not null,
    embedding vector not null,
    created_at timestamp with time zone not null,
    unique (chunk_id, embedding_provider, embedding_model)
);

create index idx_code_embedding_project_model
    on code_embedding(project_id, embedding_provider, embedding_model, embedding_dimension);
