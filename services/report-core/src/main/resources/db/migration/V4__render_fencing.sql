ALTER TABLE report_job ADD COLUMN render_epoch bigint NOT NULL DEFAULT 0;
ALTER TABLE report_artifact ADD COLUMN storage_key text;
UPDATE report_artifact SET storage_key=id::text || '.pdf';
ALTER TABLE report_artifact ALTER COLUMN storage_key SET NOT NULL;
ALTER TABLE report_artifact ADD CONSTRAINT safe_storage_key CHECK(storage_key ~ '^[0-9a-f-]{36}(\.[0-9]+)?\.pdf$');
