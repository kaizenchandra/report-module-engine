CREATE INDEX definition_catalog_name ON report_definition(lower(name),id);
CREATE INDEX definition_catalog_updated ON report_definition(updated_at,id);
