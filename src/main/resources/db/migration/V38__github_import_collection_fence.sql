ALTER TABLE github_issue_snapshots ADD COLUMN collection_version BIGINT NOT NULL DEFAULT 1 CHECK(collection_version>0);
ALTER TABLE github_task_job_items ADD COLUMN required_collection_version BIGINT NOT NULL DEFAULT 1 CHECK(required_collection_version>0);
CREATE INDEX ix_github_task_source_freshness ON github_task_sources(link_id,last_applied_at DESC);
