-- Alternate deployment mapping used by local verification. Existing defaults stay compatible.
CREATE SCHEMA IF NOT EXISTS "Reporting";
CREATE OR REPLACE VIEW "Reporting"."SalesInput" WITH (security_invoker=true) AS
 SELECT product AS "Description", quantity AS "Units", amount AS "Net",
        category AS "Segment", id AS "Sequence"
 FROM public.reporting_sales;
GRANT USAGE ON SCHEMA "Reporting" TO report_reader;
GRANT SELECT ON "Reporting"."SalesInput" TO report_reader;
