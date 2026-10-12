-- Forward-only upgrade. Existing IDs and receipts remain valid.
ALTER TABLE report_artifact ADD COLUMN job_id uuid REFERENCES report_job(id);
UPDATE report_artifact SET job_id=id;
ALTER TABLE report_artifact ALTER COLUMN job_id SET NOT NULL;
ALTER TABLE report_artifact DROP CONSTRAINT report_artifact_id_fkey;
ALTER TABLE report_artifact ADD CONSTRAINT artifact_job_format UNIQUE(job_id,format);
ALTER TABLE delivery_outbox ADD COLUMN job_id uuid REFERENCES report_job(id);
UPDATE delivery_outbox SET job_id=id;
ALTER TABLE delivery_outbox ALTER COLUMN job_id SET NOT NULL;
ALTER TABLE delivery_outbox DROP CONSTRAINT delivery_outbox_id_fkey;
ALTER TABLE delivery_outbox ADD COLUMN required boolean NOT NULL DEFAULT true;
ALTER TABLE delivery_outbox DROP CONSTRAINT delivery_outbox_object_key_key;
ALTER TABLE delivery_outbox ADD CONSTRAINT delivery_object UNIQUE(provider,bucket,object_key);
CREATE INDEX delivery_job ON delivery_outbox(job_id);
CREATE TABLE report_output (
  job_id uuid NOT NULL REFERENCES report_job(id), format text NOT NULL CHECK(format IN ('PDF','XLSX','CSV')),
  required boolean NOT NULL, status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','AVAILABLE','FAILED','CANCELLED')),
  failure_code text, PRIMARY KEY(job_id,format)
);
INSERT INTO report_output(job_id,format,required,status)
SELECT j.id, f.value, true, CASE WHEN a.id IS NOT NULL THEN 'AVAILABLE'
  WHEN j.status='CANCELLED' THEN 'CANCELLED' WHEN j.status='FAILED' THEN 'FAILED' ELSE 'PENDING' END
FROM report_job j CROSS JOIN LATERAL jsonb_array_elements_text(j.resolved_config->'request'->'outputs') f
LEFT JOIN report_artifact a ON a.job_id=j.id AND a.format=f.value;
