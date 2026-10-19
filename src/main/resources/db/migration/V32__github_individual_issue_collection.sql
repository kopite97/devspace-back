CREATE UNIQUE INDEX uq_github_issue_job_active ON github_sync_jobs(link_id,stage) WHERE kind='issue' AND state IN ('queued','running');
ALTER TABLE github_sync_replays ADD COLUMN operation TEXT NOT NULL DEFAULT 'collect';
