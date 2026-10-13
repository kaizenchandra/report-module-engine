CREATE TABLE source_import (
 id uuid PRIMARY KEY,
 source_sha256 text NOT NULL REFERENCES structured_source(sha256),
 profile_ref text NOT NULL CHECK(profile_ref='sales-jdbc'),
 adapter_version text NOT NULL CHECK(adapter_version='postgres-sales-v1'),
 category varchar(40) NOT NULL,
 created_by text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX source_import_history ON source_import(created_at,id);
CREATE TRIGGER immutable_source_import BEFORE UPDATE OR DELETE ON source_import
 FOR EACH ROW EXECUTE FUNCTION reject_audit_change();
