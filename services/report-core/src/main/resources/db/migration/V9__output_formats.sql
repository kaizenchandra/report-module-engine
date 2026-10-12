ALTER TABLE report_artifact ADD COLUMN format text NOT NULL DEFAULT 'PDF' CHECK(format IN ('PDF','XLSX','CSV'));
