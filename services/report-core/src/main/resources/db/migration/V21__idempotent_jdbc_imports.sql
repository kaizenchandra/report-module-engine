CREATE TABLE jdbc_import_request (
 actor text NOT NULL,
 idempotency_key text NOT NULL CHECK(idempotency_key ~ '^[a-zA-Z0-9_-]{8,128}$'),
 request_hash text NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
 import_id uuid NOT NULL UNIQUE REFERENCES source_import(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(actor,idempotency_key)
);
CREATE TRIGGER immutable_jdbc_import_request BEFORE UPDATE OR DELETE ON jdbc_import_request
 FOR EACH ROW EXECUTE FUNCTION reject_audit_change();
