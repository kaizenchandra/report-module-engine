ALTER TABLE report_artifact DROP CONSTRAINT safe_storage_key;
ALTER TABLE report_artifact ADD CONSTRAINT safe_storage_key
 CHECK (storage_key ~ '^[0-9a-f-]{36}(\.[0-9]+)?\.(pdf|xlsx|csv)$'
        AND right(storage_key,length(format)+1)='.'||lower(format));
