# ADR-024: Optional CSV UTF-8 byte-order mark

Status: Accepted for a bounded Phase 3 output-policy increment. Other character encodings, custom record delimiters and arbitrary exporter properties remain open.

## Contract and rendering

Both request versions accept optional boolean `outputOptions.csvIncludeBom` only when CSV is selected. True adds the UTF-8 byte-order mark (`EF BB BF`) at the start of the CSV artifact. False explicitly omits it; omission preserves existing platform behavior, currently no BOM. A BOM-only options object is valid, including explicit false. Nulls, quoted booleans, numbers, arrays and objects are rejected through the shared strict JSON/YAML codec. The standalone JSON Schema also enforces selected-format membership.

Renderer uses the installed JasperReports 7.0.3 `SimpleCsvExporterConfiguration.setWriteBOM` option with its existing UTF-8 writer. No encoding autodetection or post-export transformation is introduced. CSV delimiter selection, CRLF records, enclosure/quoting and mandatory formula protection remain unchanged. Tests compare BOM-enabled and disabled files byte for byte: the enabled file has exactly the three-byte prefix and an otherwise identical payload, including accented/Japanese text, quotes and formula-like cells, for all three delimiters.

Artifact metadata, hashes, size limits and Go delivery apply to the complete exported bytes, including the prefix. The MIME type, filename extension and delivery contract are unchanged. The option does not affect PDF, XLSX, DOCX or HTML output.

## Durability and compatibility

The option is retained in accepted configuration and participates in canonical request idempotency. Identical key/request retries recover the accepted job; changing the flag conflicts. Explicit false and omission remain distinct request choices, even though their current CSV bytes match. Rendering retries and linked reprocessing use the retained request. Omitted fields remain absent from serialized legacy output options, preserving existing fingerprints.

The form exposes platform default, include and exclude choices only while CSV is selected. It preserves true/false through form/JSON/YAML transitions, rejects malformed editor values without losing the draft and omits CSV options after deselection. Returning from an editor request replaces state with only that request's options.

Deploy capable core workers before exposing the control or submitting the new field; older codecs reject it. Preserve accepted configurations and drain/preserve affected pending jobs before rollback. No request-version, migration, dependency or Go contract changes; V24 remains current. See [the configured export example](../../examples/request-output-options.json).

## Verification

CsvBomTest verifies exact prefix/payload bytes, Unicode, three delimiters, quoting/CRLF/formula protection, BOM-only objects, both wire versions, strict scalar/format validation and legacy serialization. PostgreSQL/Batch verifies retained true, identical-key recovery, changed-flag conflict, actual Unicode CSV bytes and the hash of the complete artifact.

Chromium verifies explicit false in the form/editor, invalid quoted-boolean recovery, true/false YAML restoration, format deselection, accessibility, real Unicode CSV prefix/content, retained settings and exact download/Go/S3-emulator bytes. The full suite retains non-BOM CSV and named-XLSX regression coverage. These are local byte/content checks; they do not certify every external spreadsheet application's encoding detection.
