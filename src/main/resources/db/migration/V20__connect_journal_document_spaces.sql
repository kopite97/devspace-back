ALTER TABLE journals ADD COLUMN document_space_id UUID;
UPDATE journals SET document_space_id = gen_random_uuid();
INSERT INTO document_spaces(id,created_at,updated_at)
SELECT document_space_id,created_at,updated_at FROM journals;
ALTER TABLE journals
    ALTER COLUMN document_space_id SET NOT NULL,
    ADD CONSTRAINT uq_journal_document_space UNIQUE(document_space_id),
    ADD CONSTRAINT fk_journal_document_space FOREIGN KEY(document_space_id) REFERENCES document_spaces(id) ON DELETE RESTRICT;
