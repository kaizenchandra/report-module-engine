ALTER TABLE report_template_asset DROP CONSTRAINT report_template_asset_name_check;
ALTER TABLE report_template_asset ADD CONSTRAINT report_template_asset_name_check
 CHECK(name ~ '^assets/[a-z][a-z0-9-]{0,31}\.(png|jrtx)$');
