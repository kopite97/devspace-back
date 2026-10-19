ALTER TABLE github_oauth_states ADD COLUMN account_version BIGINT NOT NULL DEFAULT 0 CHECK(account_version>=0);
