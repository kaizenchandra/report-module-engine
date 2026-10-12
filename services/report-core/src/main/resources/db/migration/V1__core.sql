CREATE TABLE report_definition (
  id text PRIMARY KEY, version integer NOT NULL CHECK (version > 0),
  name text NOT NULL, description text NOT NULL, template_resource text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO report_definition(id,version,name,description,template_resource)
VALUES ('sales-summary',1,'Sales summary','Deterministic demonstration dataset. Trusted platform template.','templates/sales-summary.jrxml');
CREATE TABLE report_job (
  id uuid PRIMARY KEY, owner_subject text NOT NULL,
  idempotency_key varchar(128) NOT NULL, request_hash char(64) NOT NULL,
  resolved_config jsonb NOT NULL, status text NOT NULL CHECK (status IN ('QUEUED','RUNNING','DELIVERING','SUCCEEDED','PARTIAL_SUCCESS','FAILED','CANCELLED')),
  stage text NOT NULL, attempt integer NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  deadline timestamptz NOT NULL DEFAULT (now() + interval '15 minutes'),
  failure_code text, batch_execution_id bigint, parent_job_id uuid REFERENCES report_job(id),
  UNIQUE(owner_subject,idempotency_key)
);
CREATE INDEX report_job_queue ON report_job(status,created_at);
CREATE TABLE audit_event (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, actor text NOT NULL,
  action text NOT NULL, resource_id text NOT NULL, occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE FUNCTION reject_audit_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Audit records are append-only'; END $$;
CREATE TRIGGER immutable_audit BEFORE UPDATE OR DELETE ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_audit_change();
