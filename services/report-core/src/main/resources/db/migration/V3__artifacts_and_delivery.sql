CREATE TABLE report_artifact (
  id uuid PRIMARY KEY REFERENCES report_job(id), sha256 char(64) NOT NULL,
  size_bytes bigint NOT NULL CHECK (size_bytes > 0), media_type text NOT NULL DEFAULT 'application/pdf',
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE delivery_outbox (
  id uuid PRIMARY KEY REFERENCES report_job(id), artifact_id uuid NOT NULL REFERENCES report_artifact(id),
  destination_ref text NOT NULL, object_key text NOT NULL UNIQUE,
  status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','VERIFIED','FAILED','CANCELLED')),
  receipt jsonb, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX delivery_pending ON delivery_outbox(created_at) WHERE status='PENDING';
