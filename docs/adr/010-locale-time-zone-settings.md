# ADR-010: Explicit report locale and time zone

Status: Accepted for this Phase 3 increment. This does not implement interface translation, arbitrary parameter schemas or currency conversion.

## Contract and compatibility

Request parameters now optionally include locale (`en-US`, `de-DE`, `fr-FR`) and timeZone (`UTC`, `Asia/Kolkata`, `America/New_York`, `Europe/Berlin`). These deliberately bounded values are validated by the core and reflected in OpenAPI/JSON Schema and form controls. Omitted/null settings resolve to en-US and UTC. Explicit unsupported values fail validation rather than silently falling back to a default zone.

Null optional fields are omitted from canonical parameter serialization. A legacy title-only request therefore retains its exact JSON fingerprint and can recover an existing idempotent job. Explicit settings form part of the fingerprint: an explicit default and an omitted default may have different keys/fingerprints, even though they produce the same formatting. Form controls distinguish the platform default from an explicitly selected value and preserve that choice through JSON/YAML conversion. Reprocessing copies the original settings with the request.

Acceptance stores resolved locale and timezone in immutable job configuration. Batch passes those saved settings to Jasper's REPORT_LOCALE and REPORT_TIME_ZONE; worker machine defaults are not used. Java ZoneId validation precedes TimeZone conversion, avoiding silent GMT fallback. Defaults retain the existing renderer route and legacy behavior. Registered sources and registered templates use the same settings-aware renderer.

## Actual effects and limitations

Locale controls template fields that use locale-aware numeric/date formatting. Time zone controls template date formatting. The template must use Jasper formatting or explicitly consume the standard parameters; a Java expression that hard-codes its own locale or zone can intentionally override formatting because authors are trusted.

Static text, language labels, currency units and branding remain template-owned. In particular, the immutable bundled example contains its original static USD/UTC demonstration caption and no date field. Selecting a zone does not rewrite that caption or introduce a date. Use a reviewed template with date fields and suitable labels for time-zone-specific presentation. There is no currency conversion, resource-bundle translation, custom date-pattern editor or arbitrary locale/zone support in this increment. XLSX retains typed numeric cells; spreadsheet-viewer formatting can affect their presentation. Exact output bytes or tzdb behavior across runtime upgrades are not promised.

Tests verify German decimal formatting in generated CSV and a registered date expression at the US spring daylight-saving boundary (2024-03-10T07:30Z becomes 03:30 -04:00 in New York). Browser verification exercises the persisted locale/zone through form → JSON → YAML → form → Batch → delivery and checks generated number text. The application interface remains English; operational timestamps remain explicitly UTC.

## Rollout

No database migration, dependency or Go change is needed. Upgrade all core workers before allowing the frontend/API to submit the new optional fields: older workers cannot deserialize them. Existing accepted jobs without settings retain en-US/UTC. Keep all renderer instances on a tested Java/Jasper/tzdb runtime during an execution window; verify retained templates before runtime upgrades. This remains local verification, not production localization certification.
