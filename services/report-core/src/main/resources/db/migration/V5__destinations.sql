CREATE TABLE destination (
 id text PRIMARY KEY, provider text NOT NULL CHECK(provider IN ('S3','GCS')),
 bucket text NOT NULL, prefix text NOT NULL, enabled boolean NOT NULL DEFAULT true,
 version integer NOT NULL DEFAULT 1, updated_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO destination(id,provider,bucket,prefix) VALUES
('local-s3','S3','report-results','reports/'),('local-gcs','GCS','report-results','reports/');
ALTER TABLE delivery_outbox ADD COLUMN provider text;
ALTER TABLE delivery_outbox ADD COLUMN bucket text;
UPDATE delivery_outbox SET provider=CASE WHEN destination_ref='local-gcs' THEN 'GCS' ELSE 'S3' END,bucket='report-results';
ALTER TABLE delivery_outbox ALTER COLUMN provider SET NOT NULL;
ALTER TABLE delivery_outbox ALTER COLUMN bucket SET NOT NULL;
