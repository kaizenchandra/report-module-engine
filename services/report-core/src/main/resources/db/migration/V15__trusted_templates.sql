CREATE TABLE report_template (
 sha256 text PRIMARY KEY CHECK(sha256 ~ '^[0-9a-f]{64}$'),
 name text NOT NULL CHECK(length(name) BETWEEN 1 AND 100),
 jrxml text NOT NULL CHECK(octet_length(jrxml) BETWEEN 1 AND 32768),
 compiler_version text NOT NULL,
 created_by text NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE FUNCTION protect_template_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Template content is immutable'; END $$;
CREATE TRIGGER template_source_guard BEFORE UPDATE OR DELETE ON report_template
 FOR EACH ROW EXECUTE FUNCTION protect_template_source();
