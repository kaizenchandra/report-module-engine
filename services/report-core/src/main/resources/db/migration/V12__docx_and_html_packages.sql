ALTER TABLE report_artifact DROP CONSTRAINT report_artifact_format_check;
ALTER TABLE report_artifact ADD CONSTRAINT report_artifact_format_check CHECK(format IN ('PDF','XLSX','CSV','DOCX','HTML'));
ALTER TABLE report_output DROP CONSTRAINT report_output_format_check;
ALTER TABLE report_output ADD CONSTRAINT report_output_format_check CHECK(format IN ('PDF','XLSX','CSV','DOCX','HTML'));
ALTER TABLE report_artifact DROP CONSTRAINT safe_storage_key;
ALTER TABLE report_artifact ADD CONSTRAINT safe_storage_key CHECK (
  storage_key ~ '^[0-9a-f-]{36}(\.[0-9]+)?\.(pdf|xlsx|csv|docx|html\.zip)$'
  AND ((format='HTML' AND right(storage_key,9)='.html.zip')
    OR (format<>'HTML' AND right(storage_key,length(format)+1)='.'||lower(format)))
);
