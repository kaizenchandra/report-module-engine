CREATE TABLE structured_source (
 sha256 text PRIMARY KEY CHECK(sha256 ~ '^[0-9a-f]{64}$'),
 name text NOT NULL, rows_json text NOT NULL CHECK(octet_length(rows_json) BETWEEN 1 AND 32768),
 row_count integer NOT NULL CHECK(row_count BETWEEN 1 AND 500),
 created_by text NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE FUNCTION protect_structured_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Structured source content is immutable'; END $$;
CREATE TRIGGER structured_source_guard BEFORE UPDATE OR DELETE ON structured_source
 FOR EACH ROW EXECUTE FUNCTION protect_structured_source();
ALTER TABLE report_definition ADD COLUMN data_source_ref text NOT NULL DEFAULT 'sample-v1';
ALTER TABLE report_definition_version ADD COLUMN data_source_ref text NOT NULL DEFAULT 'sample-v1';
CREATE OR REPLACE FUNCTION record_definition_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND (NEW.id<>OLD.id OR NEW.version<>OLD.version+1) THEN
  RAISE EXCEPTION 'Definition updates require the next version';
 END IF;
 INSERT INTO report_definition_version(definition_id,version,name,description,template_resource,enabled,data_source_ref)
 VALUES(NEW.id,NEW.version,NEW.name,NEW.description,NEW.template_resource,NEW.enabled,NEW.data_source_ref);
 RETURN NEW;
END $$;
