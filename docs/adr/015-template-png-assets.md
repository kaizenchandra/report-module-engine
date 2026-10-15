# ADR-015: Immutable named PNG template dependencies

Status: Accepted for a bounded Phase 3 increment. Subreport/style/font bundles and arbitrary archive uploads remain unsupported.

## Registration and resource closure

Trusted template registration optionally supplies `assets`, a map of `assets/<name>.png` paths to canonical base64 strings. Omission or null means no dependencies; an explicit empty map is rejected. Names use lowercase letters, digits and hyphens, start with a letter and contain at most 32 filename characters before `.png`. No directories beyond the fixed `assets/` prefix, traversal, external URLs, SVG, JPEG or arbitrary files are admitted.

A template may contain at most eight declared image elements referencing at most four distinct PNG files. Each file is at most 8 KiB, total decoded assets at most 16 KiB, and each image dimension at most 512 pixels. Registration retains the existing 32 KiB UTF-8 JRXML and 64 KiB complete JSON request limits, including base64/JSON overhead. A PNG signature check, reader dimension check and actual ImageIO decode validate the files before persistence; metadata is ignored during that validation. These are input bounds, not process isolation or production decoder certification.

An image element uses an exact quoted literal expression, for example:

```xml
<element kind="image" x="440" y="0" width="60" height="35">
  <expression><![CDATA["assets/logo.png"]]></expression>
</element>
```

Lazy images, non-Error image fallback modes and arbitrary image expressions are rejected. The set of referenced names must exactly equal the supplied map keys. Missing and unused files fail registration before any row commits. Reusing one named image in multiple elements is supported within the declared-element limit.

JRXML remains reviewed executable Java code under the existing trusted-administrator deployment flag. The image-expression restriction does not sandbox other Java expressions. Existing element, field, typed-parameter and XML parser policies remain applicable; no generic filesystem/network resource resolver is introduced.

## Fingerprints, persistence and execution

Standalone templates retain the existing raw-JRXML SHA-256 fingerprint. For templates with assets, the fingerprint is SHA-256 of this UTF-8 manifest:

```text
template-png-assets-v1\n
<raw JRXML SHA-256>\n
<asset name>:<raw asset SHA-256>\n
...
```

The first line and each subsequent line end with one actual LF; the notation above shows the delimiters. Asset lines sort by ASCII name. The template reference remains `sha256:<fingerprint>`. Changed source, asset bytes or names produce another reference; map ordering does not. Duplicate registration recovers existing content with 200 and preserves its original name.

V20 adds `report_template_asset`, owned by core, with a parent reference, safe name, asset SHA-256 and bounded bytea. Parent and asset rows plus the registration audit commit in one transaction. UPDATE/DELETE triggers protect both source and assets. Read-time checks verify individual image hashes, complete closure and the complete manifest fingerprint before returning source or rendering. Preserve both tables in core backups.

For compilation/filling, validated image expressions become in-memory ByteArrayInputStream expressions containing their pinned base64 data. The stored/downloaded JRXML is unchanged. The expanded source determines the renderer's bounded compile-cache key, so changed assets cannot reuse a prior compiled image. Accepted job snapshots retain the selected content fingerprint; Batch loads that complete version, checks it against the snapshot, and regenerates it on recovery. Definition edits do not change accepted images. Go still receives only generated report artifacts through the existing delivery contract.

## UI and downloads

Trusted administrators select PNG files alongside JRXML in Templates. The form checks filename/count/byte limits, displays selected names, checks the complete encoded request size and submits through the existing CSRF-protected registration path. Backend validation remains authoritative for PNG bytes, dimensions and closure. Content lists display image counts and link the complete version to definition registration.

Administrator-only `GET /api/v1/templates/{sha}/bundle` returns an attachment ZIP containing original `report.jrxml` and the exact named image files. Entry names are allowlisted and timestamps fixed; the service generates this archive and never extracts uploaded ZIPs. Downloads are no-store and nosniff. Registered JRXML download remains available but cannot by itself carry a template's image closure; its raw byte hash differs from a bundled template's manifest fingerprint. Reporting users can see metadata, not registered source or bundle bytes.

HTML export remains an attachment-only ZIP. Its existing resource handler packages the actually generated image resources, with its separate 10 MiB/128-resource limits and CSP. This feature does not add HTML rendering on the application origin or independently deliver uploaded images.

## Rollout and remaining work

Deploy V20 and capable core workers before admitting templates with PNG assets or updating the frontend. Preserve pending jobs and all pinned rows before rollback; older renderers cannot resolve asset fingerprints. No migration checksum, dependency version or Go contract changes. Java 21/JasperReports 7.0.3 remain pinned.

Broader ZIP import, dependency graphs for subreports/styles/fonts, JDBC subreport bindings, tenant ACLs, deletion/holds, hard compiler/parser isolation and production load/security/restore verification remain open. Limits on declared image elements do not impose a new hard bound on repeated printed pages; normal artifact/output limits and the trusted-template execution boundary still apply.
