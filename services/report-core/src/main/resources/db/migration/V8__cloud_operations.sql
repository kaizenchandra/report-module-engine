CREATE TABLE cloud_operation (
 id uuid PRIMARY KEY, owner_subject text NOT NULL, kind text NOT NULL CHECK(kind IN ('CHECK','SIGN')),
 resource_id text NOT NULL, resource_version integer, payload jsonb NOT NULL,
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','SUCCEEDED','FAILED','EXPIRED')),
 result jsonb, expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX cloud_operation_pending ON cloud_operation(created_at) WHERE status='PENDING';
CREATE INDEX cloud_operation_owner ON cloud_operation(owner_subject,created_at);
