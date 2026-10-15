# ADR-023: Bounded XLSX worksheet names

Status: Accepted for a bounded Phase 3 output-policy increment. Workbook protection, arbitrary exporter properties and broader naming policies remain open.

## Contract

Both request versions accept optional `outputOptions.xlsxSheetName` when XLSX is selected. The setting is a base name, independent of `xlsxOnePagePerSheet`; omitting it preserves the template/platform default. Explicit nulls, nonstrings and empty names are rejected through the shared JSON/YAML codec. A name-only options object is valid. Applying the option without XLSX is rejected, including multi-output requests.

This increment deliberately admits a bounded ASCII subset: 1–31 characters, starting with a letter or digit, followed by letters, digits, spaces, periods, underscores or hyphens, with no trailing space. History is reserved case-insensitively by policy. Punctuation outside that set, controls, formulas, apostrophes and non-ASCII names are rejected rather than silently rewritten. OpenAPI, JSON Schema, generated types and server validation describe the same subset. The form explains it and returns server validation errors while retaining the draft.

The first worksheet uses the exact chosen name. When report pages are split into worksheets, later pages use `Name (2)`, `Name (3)` and so on. The base is shortened as needed to keep each resulting name within 31 characters, including multi-digit suffixes. Parentheses occur only in generated suffixes, avoiding collisions with admitted base names, including long names already ending in a number. Numeric cell detection and formula protection retain their current behavior. This setting does not add arbitrary sheet layouts, row-overflow policies or workbook protection.

## Durability and compatibility

The optional property participates in canonical request serialization, durable accepted snapshots and idempotency comparison. Retrying an identical key/request recovers the same job; changing the name under that key conflicts. Accepted configuration drives normal Batch rendering and linked reprocessing. Omitted properties remain absent, so legacy requests and existing output-options serialization retain their fingerprints. No request version, migration, runtime dependency or Go delivery contract changes; V24 remains current.

The form exposes the field only with XLSX selected. Form/JSON/YAML transitions retain the value. Deselecting XLSX omits its settings from the request; restoring a declarative request replaces option state with that request's values, as with the existing layout/delimiter controls. Deploy capable core workers before exposing or submitting the new property; older codecs reject it as unknown. Preserve accepted configurations and drain/preserve affected pending jobs before rollback to an older worker.

## Verification

WorksheetNamesTest reads actual XLSX workbook XML for combined and split layouts, normal/one-character/31-character/collision-prone names and more than nine report pages. It checks exact bounded unique names and numbered suffixes. Both request versions round-trip through JSON/YAML; invalid names, scalar types, explicit nulls and incompatible formats fail. Legacy option serialization remains exact. PostgreSQL/Batch integration verifies immutable accepted names, same-key recovery, changed-name conflicts and actual workbook output.

Chromium exercises invalid-name recovery in the editor, form/JSON/YAML restoration, format deselection, accessibility, real named workbook XML and literal formula-like title text without worksheet formulas. Download SHA-256 and retained/Go/S3-emulator byte equality are checked. These are local structural/content checks, not Excel/LibreOffice visual round-trip certification. See [the configured export example](../../examples/request-output-options.json).
