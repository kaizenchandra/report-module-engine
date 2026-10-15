# ADR-008: Trusted, content-addressed standalone JRXML

Status: Accepted for a bounded Phase 3 increment. Named PNG dependencies and bounded JRTX styles are supported by [ADR-015](015-template-png-assets.md) and [ADR-017](017-template-style-assets.md); subreport/font bundles and untrusted execution remain unsupported.

## Trust policy

JRXML can execute Java expressions with the core process's privileges. Registration requires REPORT_ADMIN, session CSRF, a true trustedCodeAcknowledged field, and `REPORT_TRUSTED_TEMPLATE_UPLOADS_ENABLED=true`. That deployment flag defaults to false. Enabling it explicitly trusts every REPORT_ADMIN identity as a code author; use separate environments or a dedicated future author role if destination/definition administrators must not have this privilege. The generated loopback fixture enables it for the synthetic operator account. Neither XML validation, role checks nor the acknowledgement is an execution sandbox. Do not enable it for untrusted authors. Isolation, hard CPU/memory execution limits and policy review are prerequisites for any future untrusted-template capability.

Registration compiles synchronously before persistence. One registration compiler slot per core instance rejects concurrent authoring requests with 429. The renderer still serializes local rendering/compilation; an upload can wait for that lock. The HTTP client timeout does not forcibly interrupt Java compilation. Source/count limits bound inputs and cache size, not arbitrary trusted expression execution time. General enterprise authoring quotas and isolated compiler processes remain future work.

## Source and compatibility contract

The current contract accepts standalone JasperReports 7 Java templates using the existing synthetic sample-v1 adapter: one required declared parameter TITLE/java.lang.String, plus up to eight bounded typed P_ parameters as specified in [ADR-014](014-typed-template-parameters.md); optional fields product/java.lang.String, quantity/java.lang.Integer, amount/java.math.BigDecimal. Standard text, static text, line, rectangle, ellipse, frame and break elements are supported, plus bounded named PNG image elements under ADR-015. Layout, inline styles, branding and pagination remain template-owned. Platform locale en-US and UTC remain fixed.

Source is at most 32,768 UTF-8 bytes, inside the existing 65,536-byte JSON request limit. XML validation uses the Java 21 default DOM factory, explicitly enabled secure processing, denied external DTD/schema access, disabled external entities and XInclude, rejected DOCTYPE, depth 32 and attribute count 64. At most 1,000 descendant elements are accepted. The compatibility check rejects external styles/templates, subreports, arbitrary images/components, datasets/queries, scriptlets, resource bundles, Jasper property overrides and custom font names. Only platform SansSerif/Serif/Monospaced font names are allowed. Dependencies are rejected, never silently ignored or fetched. Trusted Java expressions can still perform arbitrary operations; these structural checks are not a security boundary against an author.

The runtime retains pinned JasperReports 7.0.3 and Java 21. No new dependency was introduced. [Jasper's compilation API](https://jasperreports.sourceforge.net/api/net/sf/jasperreports/engine/JasperCompileManager.html) describes compilation from source streams; compatibility with the pinned release was verified by actual compilation/filling/export tests. Parser settings follow the [Java 21 JAXP security guide](https://docs.oracle.com/en/java/javase/21/security/java-api-xml-processing-jaxp-security-guide.html). The public Jasper API documentation currently describes a later 7.0.x release; it is not evidence of upgrading this repository.

## Immutable references and storage

V15 adds core-owned report_template. The row contains bounded JRXML text, its SHA-256, first-registration display name, compiler version, author subject and timestamp. UPDATE/DELETE guards make content append-only; audit registration and source insert commit together. Report binaries remain in the durable staging path, not this table. PostgreSQL storage makes registered source available to another worker or a fresh compiler cache; include this table in core backups.

A registered reference is `sha256:<64 lowercase hexadecimal characters>`. Same bytes return existing metadata with 200; new bytes compile and create a row with 201. The first display name is retained for duplicate bytes. Changing even whitespace produces a new content version. This is content versioning, not a named template-family lifecycle. The catalog displays revisions by fingerprint and creation date. Registration does not execute a report preview or create a job.

Definitions retain their existing templateResource field, now accepting the bundled sample path or an existing immutable registered reference. Unknown references and arbitrary URLs/paths fail validation. Acceptance resolves the source fingerprint into immutable job configuration. Batch reads the pinned reference from that saved definition, fetches retained bytes, verifies their SHA-256 and compiles/fills/exports them. It does not read the mutable definition head. Legacy jobs without a definition snapshot still use the bundled sample hash check.

Compiled registered templates use a process-local access-order cache capped at 16 fingerprints. Changed source naturally gets another key; eviction and process restart cause recompilation from PostgreSQL. Compiled Java objects are never deserialized from submitted files or stored in Batch metadata. The bundled-template cache is preserved separately. A later compiler/runtime upgrade must validate retained templates before rollout; compiler_version records registration provenance, not a promise of byte-identical exports across runtime upgrades.

## UI, source access and delivery

Templates provides policy state, a bundled example, upload with explicit trust acknowledgement, compilation errors, paginated content revisions and exact references. Successful registration links to definition registration with the reference filled in. Definition editors can select the bundled path or paste an existing fingerprint. Job submission and output delivery follow the normal authenticated Batch/core-to-Go path. Go is unchanged and receives report artifacts only.

Registered source download is administrator-only, attachment-only, no-store and nosniff. Read-only reporting users can view metadata and the bundled synthetic example. The application never renders JRXML or exported HTML into its trusted origin. Error responses omit source lines, expression values and stack traces.

## Rollout and remaining work

Deploy V15/core before the frontend. Keep upload policy disabled until administrators have been explicitly trusted as code authors; disabling registration later does not delete source or stop execution of already registered templates. Retiring a definition remains the admission control for that definition. No template deletion/holds or per-template ACL exists yet; single-workspace resource access remains in effect.

Subreport/image/font/style/bundle dependency archives, custom/JDBC datasets, arbitrary parameter schemas, inline consumer JRXML, template-family version heads, draft approval, isolated untrusted compilation and production resource benchmarks remain open. A fresh Renderer test proves cold-cache recompilation; this increment does not claim an actual OS worker-crash test for templates or real-cloud verification.

Formatting update: requests can now override the former fixed locale/time-zone defaults within the bounded contract in [ADR-010](010-locale-time-zone-settings.md).
