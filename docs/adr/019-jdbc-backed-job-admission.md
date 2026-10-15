# ADR-019: Capture JDBC rows at job admission

Status: Accepted for a bounded Phase 3 increment. General JDBC profiles/queries, render-time live reads and additional field schemas remain open.

## Definition and request contract

An administrator may publish a definition with `dataSourceRef: "jdbc:sales-jdbc:stationery"`. The suffix is a 1–40 character category beginning with a letter/digit and containing only ASCII letters, digits, spaces, underscores or hyphens. The immutable definition version pins this selector and the deployment-owned `sales-jdbc` / `postgres-sales-v1` adapter. Publishing validates syntax without contacting JDBC. The adapter must be configured and available when a new job needs rows.

The existing report request, JSON/YAML conversion and request hash remain unchanged. Consumers select a definition/version; they cannot submit JDBC URLs, credentials, SQL, properties or a category override. Existing sample-v1 and data: definitions retain their behavior and fingerprints. A new job key intentionally captures current rows; the same actor/key/request recovers the first committed job and snapshot, even if JDBC has changed or become unavailable. A different request under that key returns 409 before reading JDBC.

This captures data before acceptance, not at render time. There is no distributed transaction or shared wall-clock point between the source and core databases. A source change after the read cannot alter the accepted job.

## Two-stage admission

For a first submission, a short core transaction performs the existing actor/key lookup, quota check and definition/template/parameter/output/destination validation. It locks mutable heads/destinations only for this transaction. Static data submissions commit the job in this one transaction as before.

For a JDBC definition, preflight returns a validated category and releases all core locks. The deployment adapter reads outside the core transaction. Its existing two-operation concurrency bound, read-only repeatable-read connection, fixed prepared sales query, query/connect/socket deadlines, 501-row detection buffer and 500-row/structured-field limits apply. Core source publication additionally enforces 32 KiB of canonical rows. Source SQL/credentials are not returned in errors.

A second core transaction repeats actor/key lookup and every admission check under the existing actor serialization/shared-resource locks. It then publishes canonical immutable rows, a source_import provenance record, source/import audit records, the job, outputs and acceptance audit atomically. The resolved configuration contains the original JDBC definition selector, a concrete `datasetVersion: "data:<sha256>"` and `sourceImportId`. No job is acknowledged until this transaction commits. Failed reads or final validation/publication leave no partial snapshot/provenance/job records. The job idempotency ledger is report_job; JDBC-backed jobs do not create manual-import jdbc_import_request ledger rows.

Concurrent unresolved submissions may read different source snapshots. The final actor/key check publishes one job and import; the other candidate rows are discarded. Different keys capture independently, with separate provenance even when identical canonical rows deduplicate to the same structured_source. Definition retirement, destination changes and quota consumption during the external read are revalidated before acceptance. An external-read failure can still return an error while another concurrent request commits; a subsequent retry recovers that committed result.

## Rendering and reprocessing

Rendering, all formats, delivery retries and worker restarts use the accepted data: fingerprint and never contact JDBC. Reprocessing copies the parent's concrete datasetVersion/sourceImportId, validates retained row integrity and matching import/category provenance, and creates no new JDBC read or import. It retains the existing terminal-parent, ownership and enabled-definition/destination admission rules. To capture fresh source data, submit a new job with a new key rather than reprocessing.

Sources and provenance remain append-only under existing database guards. Backup/restore must retain source_import and structured_source with accepted job configurations. The current retention model does not expire these records. No new migration, external dependency, request schema version or Go contract is required; V23 remains the latest core migration.

## UI and rollout

Data sources offers **Use JDBC for new jobs** for the selected category, opening definition setup without requiring a snapshot name or importing rows. Definition setup accepts/displays the selector and explains capture/retry/reprocess semantics. Definition details and report submission explain when rows are captured. Explicit **Import snapshot** remains available for administrators wanting a fixed data: definition.

Deploy all capable core workers before publishing JDBC-backed definitions or updating the frontend. Older workers reject these source references and cannot reconstruct reprocessing provenance. During rollback, block new admission and drain/preserve accepted executions and their concrete snapshots. Deployment profile endpoints and credentials remain operator-owned; changing the deployment profile can affect future captures, never accepted rows. No production rollback/restore, tenant ACL or general query-isolation certification is claimed.
