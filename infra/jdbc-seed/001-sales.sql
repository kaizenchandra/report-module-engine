\getenv reader_password REPORT_JDBC_PASSWORD
CREATE ROLE report_reader LOGIN PASSWORD :'reader_password';
REVOKE ALL ON DATABASE report_input FROM PUBLIC;
GRANT CONNECT ON DATABASE report_input TO report_reader;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE TABLE public.reporting_sales (
 id bigint PRIMARY KEY, category varchar(40) NOT NULL,
 product varchar(100) NOT NULL, quantity integer NOT NULL, amount numeric(11,2) NOT NULL
);
CREATE INDEX sales_category ON public.reporting_sales(category,id);
INSERT INTO public.reporting_sales VALUES
 (1,'stationery','JDBC amber journal',7,84.25),
 (2,'stationery','JDBC cobalt pencil',12,18.00),
 (3,'equipment','JDBC desk lamp',2,96.50);
GRANT USAGE ON SCHEMA public TO report_reader;
GRANT SELECT ON public.reporting_sales TO report_reader;
ALTER ROLE report_reader SET default_transaction_read_only=on;
ALTER ROLE report_reader SET statement_timeout='3s';
