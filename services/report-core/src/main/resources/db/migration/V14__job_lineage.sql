CREATE INDEX report_job_lineage ON report_job(owner_subject,parent_job_id,created_at DESC,id)
 WHERE parent_job_id IS NOT NULL;
ALTER TABLE report_job ADD CONSTRAINT job_not_own_parent CHECK(parent_job_id IS NULL OR parent_job_id<>id);
