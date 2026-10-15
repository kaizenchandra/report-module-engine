CREATE TABLE report_template_asset (
 template_sha256 text NOT NULL REFERENCES report_template(sha256),
 name text NOT NULL CHECK(name ~ '^assets/[a-z][a-z0-9-]{0,31}\.png$'),
 sha256 text NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
 content bytea NOT NULL CHECK(octet_length(content) BETWEEN 8 AND 8192),
 PRIMARY KEY(template_sha256,name)
);
CREATE TRIGGER template_asset_guard BEFORE UPDATE OR DELETE ON report_template_asset
 FOR EACH ROW EXECUTE FUNCTION protect_template_source();
