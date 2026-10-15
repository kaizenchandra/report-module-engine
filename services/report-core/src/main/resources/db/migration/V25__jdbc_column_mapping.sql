-- Preserve legacy imports with the exact deployment mapping used before this feature.
ALTER TABLE source_import
 ADD COLUMN mapping_json text NOT NULL DEFAULT '{"schema":"public","table":"reporting_sales","productColumn":"product","quantityColumn":"quantity","amountColumn":"amount","categoryColumn":"category","orderColumn":"id"}'
   CHECK(length(mapping_json)<=1024 AND jsonb_typeof(mapping_json::jsonb)='object'),
 ADD COLUMN mapping_sha256 text NOT NULL DEFAULT 'dffce1d3644182c9287859596bfce1d66898f8a1a67d3ab950d8dc578ba5af77'
   CHECK(mapping_sha256 ~ '^[0-9a-f]{64}$'),
 ADD CONSTRAINT source_import_mapping_hash CHECK(mapping_sha256=encode(sha256(convert_to(mapping_json,'UTF8')),'hex'));
ALTER TABLE source_import DROP CONSTRAINT source_import_adapter_version_check;
ALTER TABLE source_import ADD CONSTRAINT source_import_adapter_version_check
 CHECK(adapter_version IN ('postgres-sales-v1','postgres-sales-v2'));
