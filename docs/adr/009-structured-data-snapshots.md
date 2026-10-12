# ADR-009: Bounded immutable structured-data snapshots

Status: Accepted for the current Phase 3 increment. JDBC, arbitrary field schemas and general ETL remain open.

## Input contract and policy

Administrators register UTF-8 JSON through `POST /api/v1/data-sources`. A source contains a display name and 1–500 rows with exactly product (nonblank String, maximum 100 UTF-16 code units), quantity (JSON integer, 0–1,000,000), and amount (JSON number, absolute maximum 999,999,999.99, at most two fractional decimal places after removing trailing zeroes). Numeric strings, fractional quantities, nulls, missing/extra fields, duplicate object keys, trailing documents, malformed UTF-8 and invalid Unicode product strings are rejected. Amounts retain BigDecimal precision and normalize to scale two; quantity becomes Integer. Product controls are rejected. Row order and duplicate rows are preserved; field order and equivalent numeric amount representations are normalized.

The existing request limit is 65,536 bytes. Canonical rows are limited to 32,768 UTF-8 bytes. Parser nesting is capped at 16, numeric tokens at 32 characters and strings at 1,000 characters before field validation. Empty data policy is REJECT at registration, with a 400 response and no durable source. There is no implicit null substitution, malformed-row skipping or type coercion. The UI and backend show the same limits; the backend is authoritative.

This deliberately supports small structured inputs, not bulk relational storage or ETL. No cloud/database credentials, URLs, SQL, field-mapping scripts or external connection settings are accepted by this API. Registration is REPORT_ADMIN and CSRF protected. Reporting users can read source metadata and generate authorized workspace definitions bound to a source. The current single-workspace policy has no per-source tenant ACL; do not use sensitive data in the local fixture. There is no public raw-row retrieval endpoint.

## Durable snapshot and definition versioning

V16 creates core-owned structured_source containing canonical JSON text, SHA-256, row count, first name, author and timestamp. Source bytes are small input data, not report artifacts or Batch metadata. UPDATE/DELETE guards make the snapshot immutable. Source registration and DATA_SOURCE_REGISTERED audit commit together. Identical canonical rows return existing metadata (200), preserving the first name; new content returns 201. The immutable reference is `data:<sha256>`.

V16 adds data_source_ref to definition heads and immutable versions, backfilling old rows by the column default `sample-v1`. The version trigger records new bindings alongside template references. Omitting dataSourceRef on registration/replacement selects sample-v1 for compatibility; null currently has the same default behavior. Definition replacement is a full configuration replacement, so clients preserving a registered source must send its reference. Missing registered sources fail before definition publication.

Acceptance resolves the exact definition version and validates its immutable source. It records the data reference in the saved definition and datasetVersion. Batch loads that reference, verifies the canonical stored bytes against SHA-256, and supplies typed maps to Jasper. Source rows are not copied into job JSON or execution context. A new source or definition revision cannot alter accepted rows. Legacy jobs retain sample-v1 behavior. Reprocessing uses the original exact definition version and consequently the same retained source snapshot, while destinations retain their existing fresh-resolution policy.

## Rendering and extension boundary

The renderer accepts typed rows through renderData for either the bundled or registered trusted template. The current shared row schema matches the template validator's field contract. CSV product strings are prefixed with an apostrophe, alongside the existing title protection and Jasper formula escaping. XLSX uses typed String/Integer/BigDecimal fields; tests verify no formula elements and numeric amount cells. A trusted template author can deliberately transform values through executable expressions; the platform is not a sandbox for those expressions.

A future JDBC adapter must produce the same renderer input boundary or a separately versioned schema, define bound parameters and credentials, and persist any asynchronous source references before admission. It must implement query deadlines, cancellation and supported snapshot semantics rather than implying JDBC data is an immutable snapshot. This increment adds no placeholder JDBC handler, connection-test success response or generic ETL pipeline.

## UI and rollout

Data sources provides a server-backed catalog, strict JSON row editor, validation errors, empty/loading/permission states and pagination. Registration links to definition creation with the immutable reference filled in. Definition history and accepted job configuration expose the binding. Generated results use the existing owner authorization and core-to-Go artifact/delivery path; Go is unchanged.

Stop old core rendering instances before accepting registered-source jobs. An older core ignores the new binding and renders sample data, so a rolling mixed-version deployment or core downgrade with pending registered-source jobs is unsafe. Upgrade all core workers/V16, then the frontend, then enable traffic. A rollback requires draining affected jobs or a coordinated restore/forward fix; schema-only rollback is insufficient. No production rollout has occurred. Include structured_source in PostgreSQL backups and preserve referenced records; retention/deletion/holds are not implemented.

## Verification boundary

PostgreSQL/MVC tests exercise strict validation, authorization/CSRF, canonical deduplication, immutable guards, missing references and real Batch PDF contents pinned across definition updates. Export checks cover literal CSV text and XLSX formula absence/numeric cells. Browser coverage registers rows, binds a definition, generates a report and checks actual data and verified emulator delivery. Local tests use synthetic fixtures. JDBC connectivity, production data volumes, data-retention governance and real-cloud behavior remain unverified.

Formatting update: requests can now override the former fixed locale/time-zone defaults within the bounded contract in [ADR-010](010-locale-time-zone-settings.md).
