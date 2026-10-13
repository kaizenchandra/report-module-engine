-- Owner-scoped history; immutable accepted request supplies the definition ID.
CREATE INDEX report_job_owner_history ON report_job(owner_subject,created_at,id);
CREATE INDEX report_job_owner_definition_history ON report_job
 (owner_subject,(resolved_config->'request'->>'definitionId'),created_at,id);
