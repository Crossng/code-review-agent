alter table pull_request_record
    add column base_branch varchar(100),
    add column target_branch varchar(100),
    add column commit_sha varchar(80),
    add column commit_message text;

create index idx_pull_request_record_commit_sha on pull_request_record(commit_sha);
