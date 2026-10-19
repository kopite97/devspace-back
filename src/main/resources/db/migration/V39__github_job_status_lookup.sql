CREATE INDEX ix_github_jobs_link_kind_created ON github_sync_jobs(link_id,kind,created_at DESC,id DESC);
