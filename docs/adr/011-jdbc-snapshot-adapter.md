# ADR-011: Deployment-owned PostgreSQL snapshot adapter

Status: Implemented; local verification is recorded in VERIFICATION.md. Phase 3 remains open.

## Decision and scope

Add `postgres-sales-v1`, a bounded PostgreSQL JDBC source adapter behind the administrator-only `sales-jdbc` deployment profile. This is an explicit **snapshot import before job submission**, not live JDBC execution by a queued job. Administrators test the connection, select a sales category, and register immutable rows. The existing definition versions, acceptance snapshots, Batch rendering, Go delivery and artifact access use the resulting `data:<sha256>` reference unchanged. Consumer applications still do not orchestrate jobs or uploads.

The fixed query reads `public.reporting_sales(product text, quantity integer, amount numeric)`, binds category with `PreparedStatement.setString`, orders by a unique `id` and fetches at most 501 rows to detect overflow. `id` and `category` must exist; the local fixture supplies a category/id index. No browser-provided SQL, host, URL, driver, credential or property is accepted. Arbitrary schemas, joins, custom queries, per-report JDBC parameters, subreport connections, live refresh, additional JDBC vendors and general ETL are unsupported.

## Identity and boundaries

Profile host/port/database/username/password/SSL mode are deployment environment inputs. Empty host disables the capability. The host and database syntax cannot inject JDBC URL options. Passwords resolve only in core memory and are absent from application tables, Batch metadata, API responses and browser bundles. The API returns profile ID, adapter version and configured state only. Credentials can be supplied from a deployment secret into `REPORT_JDBC_PASSWORD`; a cloud secret-manager resolver is not implemented.

Production defaults to `verify-full`; the synthetic loopback fixture explicitly uses `disable`. Provision only CONNECT, schema USAGE and SELECT on the approved source table/view. `Connection.setReadOnly(true)` is additional protection, not a substitute for database grants. Deployment operators control network egress and trusted endpoints; this is not a general SSRF-safe arbitrary database connector. Administrators may import data that reporting users can subsequently render through definitions, matching the existing workspace-wide source policy. Tenant/source-specific ACLs remain open.

## Snapshot and persistence semantics

A dedicated source connection uses a read-only REPEATABLE READ transaction. The query sees a PostgreSQL snapshot. The connection is rolled back and closed after retrieval, before opening the core write transaction. Structured validation rejects empty data, more than 500 rows, null/missing values, incompatible types, malformed text, invalid numeric ranges/scale and more than 32,768 canonical UTF-8 bytes. Deterministic row order and normalized decimals produce the existing content hash.

One core transaction stores/deduplicates the immutable source, appends a V18 `source_import` provenance row, and appends audit events. Provenance includes adapter version, profile reference, non-sensitive category, actor and import completion time. It is not a database transaction ID or exact snapshot timestamp, and does not retain endpoint or credentials. Data source rows are never modified by subsequent imports or changes to the remote table. Accepted jobs never re-read JDBC, including retries and reprocessing of a definition bound to the same snapshot.

Imports without an idempotency key query current rows and append separate provenance. [ADR-016](016-idempotent-jdbc-imports.md) adds optional actor-scoped keyed recovery, including same-result retries after source changes or a lost acknowledgement. The form reuses one key for unchanged fields while mounted. Identical content still reuses the source and its original display name. Job-submission idempotency remains unchanged. The source transaction and core transaction are not a distributed transaction; a failed persistence operation leaves no accepted import but may be retried against current source data.

## Resource and failure policy

At most two JDBC operations per core process; excess calls receive 429 without waiting. Connections are short-lived. Driver connection/login/socket/cancellation limits are 3/5/5/1 seconds, statement query timeout is 3 seconds, and maximum driver result buffer is 256 KiB. Each statement has a 501-row maximum; malformed oversized server values can fail with the generic source error. SQL/connection failures return a fixed 503 without vendor exception details. No automatic import retries occur. Browser timeout is 15 seconds and does not imply server cancellation or rollback of an already committed import. DNS, network partitions and pathological servers still need production failure testing; no strict end-to-end latency SLA is claimed.

The connection test compiles and runs a zero-row query for the required table/columns and read permission. It does not certify actual row validity, write restrictions, source freshness, production TLS or every failure mode. Successful checks and imports are audited; failed checks expose redacted errors. The UI presents success only after backend confirmation.

## Migration, local infrastructure and compatibility

V18 adds append-only import provenance and a history index; existing source/job rows and prior migrations are unchanged. Old application binaries can still render imported structured sources, although they lack JDBC administration. Roll back application code while retaining V18 and its data. Go owns no new records and needs no change.

Compose adds a separate PostgreSQL 17.6 synthetic input database on loopback port 5434. Its bootstrap SQL creates `report_reader` with SELECT only, read-only default transactions and a statement timeout. `scripts/local-env.sh` adds missing random reader/bootstrap passwords and local profile defaults without replacing existing values. Container bootstrap runs only for an empty input volume; changing environment passwords does not rotate an existing role. Never delete retained volumes to rotate credentials.

The adapter reuses Boot 3.5.16's pinned pgJDBC 42.7.11 and Java 21. [pgJDBC connection properties](https://jdbc.postgresql.org/documentation/use/) document the timeout/read-only/buffer settings; [PostgreSQL 17 isolation documentation](https://www.postgresql.org/docs/17/transaction-iso.html) defines the source snapshot. The [driver download page](https://jdbc.postgresql.org/download/) confirms Java 8+ compatibility and lists newer patch versions. Patch/security evaluation remains a release gate, not a claim that the inherited pin is the newest release.

ADR-019 subsequently adds definition-driven per-job capture through the same bounded adapter. Explicit data: imports retain their original fixed-snapshot semantics.
