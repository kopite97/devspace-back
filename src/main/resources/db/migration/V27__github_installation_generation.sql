CREATE TABLE github_installation_epochs (
    installation_id BIGINT PRIMARY KEY CHECK(installation_id>0),
    generation BIGINT NOT NULL DEFAULT 1 CHECK(generation>0)
);
INSERT INTO github_installation_epochs(installation_id) SELECT DISTINCT installation_id FROM github_repository_links;
