# ADR-013: Bounded CSV and XLSX output options

Status: Accepted for local development.

## Contract and defaults

Both request versions optionally accept a nonempty `outputOptions` object:

- `csvDelimiter`: `COMMA`, `SEMICOLON` or `TAB`, only when CSV is selected.
- `xlsxOnePagePerSheet`: a boolean, only when XLSX is selected. True creates one worksheet per Jasper page; false explicitly combines pages in one worksheet.

Unknown properties, explicit nulls, empty objects, scalar coercion and options for absent formats are rejected. The form, strict JSON and restricted YAML use the same backend validation. The standalone JSON Schema also expresses format membership; OpenAPI describes the object and backend enforces membership.

Omitted options do not change legacy request serialization, fingerprints or exporter behavior. An explicit option participates in the canonical request fingerprint, including an explicit default or false value. Reusing an idempotency key with changed options returns a conflict. The accepted request retains the options in `resolved_config.request`; rendering retries and linked reprocessing read that request. No database migration is needed.

The parameter form shows controls for selected formats and serializes only their applicable options. JSON/YAML conversion and returning to the form restore the options in the validated draft. Returning from a request that omits an option resets that control to its default.

## Export policy

Jasper 7.0.3's installed `SimpleCsvExporterConfiguration.setFieldDelimiter` and `SimpleXlsxReportConfiguration.setOnePagePerSheet` APIs were inspected and exercised. CSV remains UTF-8 with CRLF records and mandatory formula escaping; callers cannot disable these protections. Delimiter-aware quoting remains exporter-owned. XLSX keeps typed numeric cells, with consumer text supplied as strings. The layout option applies to Jasper's generated pages, not a row-count partition.

Only the selected exporter consumes its option. Existing artifact limits, MIME types, extensions, delivery keys and immutable checksums remain applicable. Core binaries must understand the new request field before admitting it; old workers reject new requests. The Go delivery contract is unchanged because settings affect artifact bytes, not transport metadata.

Worksheet names are added by [ADR-023](023-xlsx-worksheet-names.md). PDF encryption, workbook protection, other CSV record/encoding customization and arbitrary exporter properties remain open scope. These settings do not establish document accessibility or production performance guarantees.

[ADR-024](024-csv-utf8-byte-order-mark.md) adds an optional UTF-8 BOM while retaining the fixed encoding, CRLF and mandatory formula protections.
