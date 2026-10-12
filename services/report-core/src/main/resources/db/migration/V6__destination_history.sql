CREATE TABLE destination_version (
 destination_id text NOT NULL REFERENCES destination(id), version integer NOT NULL,
 provider text NOT NULL, bucket text NOT NULL, prefix text NOT NULL, enabled boolean NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(destination_id,version)
);
INSERT INTO destination_version(destination_id,version,provider,bucket,prefix,enabled)
 SELECT id,version,provider,bucket,prefix,enabled FROM destination;
CREATE FUNCTION record_destination_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO destination_version(destination_id,version,provider,bucket,prefix,enabled)
 VALUES(NEW.id,NEW.version,NEW.provider,NEW.bucket,NEW.prefix,NEW.enabled);
 RETURN NEW;
END $$;
CREATE TRIGGER destination_version_insert AFTER INSERT OR UPDATE ON destination
 FOR EACH ROW EXECUTE FUNCTION record_destination_version();
CREATE FUNCTION protect_destination_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Destination history is append-only'; END $$;
CREATE TRIGGER destination_history_guard BEFORE UPDATE OR DELETE ON destination_version
 FOR EACH ROW EXECUTE FUNCTION protect_destination_history();
