ALTER TABLE report_definition ADD COLUMN enabled boolean NOT NULL DEFAULT true;
ALTER TABLE report_definition ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
CREATE TABLE report_definition_version (
 definition_id text NOT NULL REFERENCES report_definition(id),
 version integer NOT NULL CHECK(version > 0),
 name text NOT NULL, description text NOT NULL, template_resource text NOT NULL,
 enabled boolean NOT NULL, recorded_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(definition_id,version)
);
INSERT INTO report_definition_version(definition_id,version,name,description,template_resource,enabled)
 SELECT id,version,name,description,template_resource,enabled FROM report_definition;
CREATE FUNCTION record_definition_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND (NEW.id<>OLD.id OR NEW.version<>OLD.version+1) THEN
  RAISE EXCEPTION 'Definition updates require the next version';
 END IF;
 INSERT INTO report_definition_version(definition_id,version,name,description,template_resource,enabled)
 VALUES(NEW.id,NEW.version,NEW.name,NEW.description,NEW.template_resource,NEW.enabled);
 RETURN NEW;
END $$;
CREATE TRIGGER definition_version_insert AFTER INSERT OR UPDATE ON report_definition
 FOR EACH ROW EXECUTE FUNCTION record_definition_version();
CREATE FUNCTION protect_definition_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Definition history is append-only'; END $$;
CREATE TRIGGER definition_history_guard BEFORE UPDATE OR DELETE ON report_definition_version
 FOR EACH ROW EXECUTE FUNCTION protect_definition_history();
