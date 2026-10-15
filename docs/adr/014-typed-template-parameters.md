# ADR-014: Bounded typed parameters for trusted templates

Status: Accepted for local development. Java 21/JasperReports 7.0.3 and the trusted-author policy remain applicable.

## Declaration and request contract

Standalone trusted JRXML must declare `TITLE` as `java.lang.String` and may declare up to eight custom parameters named `P_[A-Z][A-Z0-9_]{0,29}`. Names are unique and case-sensitive. Other user parameter names/classes remain unsupported. Engine-owned parameters cannot be replaced through the custom-value map.

| Wire type | JRXML class | Value and bounds |
| --- | --- | --- |
| TEXT | java.lang.String | String, at most 200 UTF-16 code units, no Unicode control characters; empty string allowed |
| INTEGER | java.lang.Integer | JSON/YAML integer from -1,000,000 to 1,000,000 |
| DECIMAL | java.math.BigDecimal | String matching `-?(0\|[1-9][0-9]{0,11})(\.[0-9]{1,4})?`; no exponent, leading plus or leading zeroes |
| BOOLEAN | java.lang.Boolean | JSON/YAML true or false |
| DATE | java.util.Date | Actual ISO calendar date YYYY-MM-DD with year 1900–2100 |

Both request versions optionally use `parameters.values`, a map from custom names to `{type, value}` objects. The map is nonempty when present, with at most eight entries. Explicit nulls, undeclared object properties, wrong scalar types, out-of-range values and unsupported names/types are rejected. Decimal strings preserve precision and scale; `12.3400` is deliberately distinct from `12.34` because trusted templates can observe that scale. Dates become the start of the calendar day in the accepted report time zone, using Java zone/DST rules; they do not represent a caller-supplied timestamp.

Every custom declaration requires a supplied typed value, even if its JRXML declaration includes a default expression. Admission validates the exact set of names and their types against the selected immutable template source. Missing values, extra names and mismatched types fail before creating a job. Conversion validates wire structure without resolving resources; normal acceptance performs template binding.

Name order is normalized with a sorted immutable map, and supported integer nodes are normalized to Integer representation. Equivalent JSON/YAML and map ordering serialize identically for idempotency. Omitted `values` preserves existing request fingerprints and legacy title-only serialization. Changing a typed value conflicts with a reused key. Decimal string scale and explicit empty text remain part of the request identity.

## Discovery, form and rendering

Authenticated `GET /api/v1/definitions/{id}/versions/{version}/parameters` exposes only the exact template's custom names/types, in name order. Source download remains administrator-only. The endpoint can describe a historical/disabled version; actual admission still enforces the definition and version lifecycle checks.

The submission form loads metadata for its selected exact version, cancels stale requests when the selection changes, blocks form submission while metadata is unavailable and provides retry after failure. Selecting a different definition clears custom values. Text fields default to an explicit empty string; other fields need a value. Editor-to-form conversion rejects supplied names/types that cannot be represented by the selected template's controls. JSON/YAML preserve boolean, integer, decimal-string and date values.

Accepted values live in `resolved_config.request.parameters.values`. Batch rendering and linked reprocessing use that request and the pinned template source. A later definition revision cannot replace an accepted job's schema. Locale/time-zone and output options continue through the same rendering path. CSV prepends an apostrophe to every consumer TEXT parameter and keeps mandatory exporter formula escaping; XLSX receives those values as strings. Trusted Java expressions remain executable code and are not sandboxed by this feature.

No database migration, dependency or delivery-contract change is required. Deploy capable core workers before the frontend and before admitting typed requests or registering templates with custom declarations. Older workers cannot interpret these values/templates. Go continues delivering the generated artifact bytes with existing integrity and MIME rules.

## Current boundary

General schema authoring, nullable/optional parameters, collections, arbitrary Java classes, consumer expressions, live JDBC/subreport bindings and tenant-specific parameter policy remain open. This feature supplies scalar template inputs; it does not implement those data adapters or template dependencies. See [the trusted-author policy](008-trusted-template-registration.md).
