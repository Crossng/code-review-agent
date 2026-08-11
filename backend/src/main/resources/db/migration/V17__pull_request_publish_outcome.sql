alter table pull_request_record
  add column publish_outcome varchar(64) not null default 'LOCAL_DRAFT_READY';

update pull_request_record
set publish_outcome = case
  when status = 'OPEN' then 'REMOTE_CREATED'
  when status = 'FAILED' then 'REMOTE_FAILED'
  else 'LOCAL_DRAFT_READY'
end;
