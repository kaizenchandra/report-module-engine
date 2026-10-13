# Phase status

Status vocabulary: Not Started, In Progress, Implemented, Verified, Blocked.
A phase is Verified only after its exit gate passes. Individual components may be verified earlier.

| Phase                    | Status      | Gate / evidence                                                                                                                                                                                                   |
| ------------------------ | ----------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 0 Foundation             | Verified    | Java 21 package/test, Go test, Next lint/build passed. Two Chromium tests passed: anonymous rejection and real Keycloak login → PostgreSQL catalog. Core/Go/Next started locally. CI/assets not yet run remotely. |
| 1 PDF reporting          | Verified    | Four Chromium tests pass, including real Batch/Jasper PDF → Go → S3Mock → authorized download and SHA-256 match. Java PDFBox content test and real PostgreSQL integration tests pass.                             |
| 2 Resilient dual cloud   | Verified    | Local provider contracts, admin OIDC checks, bounded signing construction, durable callback replay, real worker-process crash recovery and retained-artifact retry.                                               |
| 3 Administration/formats | In Progress | Five formats, JSON/YAML, versioned definitions, trusted JRXML, reprocessing and immutable structured sources and JDBC snapshot import implemented; live JDBC, dependencies and settings remain open.                                                             |
| 4 Governance             | Not Started | Scheduling, permissions, quotas and lifecycle integration tests.                                                                                                                                                  |
| 5 Release validation     | Not Started | Benchmarks, failure injection, security, restore and operations evidence.                                                                                                                                         |

No production readiness or SLA is claimed. No real cloud resources have been provisioned.

## Current boundary

Phase 2's local exit gate is met. Both cloud adapters pass emulator contracts; an actual Go subprocess exits after verified upload and recovers through PostgreSQL and the retained provider object. Durable cloud operations provide destination metadata checks and bounded signed-link construction, with owner authorization, fixed expiry, callback replay and no URL logging. Real OIDC administrator browser coverage verifies registration, version changes and checks against both emulators. Core and Go orphan cleanup preserve referenced/active files.

Real-cloud IAM, KMS, provider expiry enforcement and production networking remain unverified. GCS IAM signing is implemented but cannot be exercised against a real signing account without supplied infrastructure; RSA construction is tested locally. The GCS emulator deliberately reports signing unavailable. This is **Verified local functionality**, not production certification. Network partitions, load, restore and broader failure exercises remain Phase 5 work.

Phase 3 now implements configuration v2 for multiple PDF/XLSX/CSV/DOCX/HTML outputs and destination mappings, required/optional aggregation, per-output metadata, independent delivery retries and connected form/JSON/YAML editor/monitor workflows. Version 1 remains compatible. See [format support](FORMAT_SUPPORT.md). Trusted standalone template registration and linked reprocessing are verified in the increments below. Immutable typed structured sources are also verified below. Still open: template dependency bundles, live JDBC/additional field schemas and preview/settings. Phases 4–5 have not started; scheduling, tenants, approvals, webhooks, retention/holds and production exercises remain open.

Previous gate verification: 14 Java tests passed using the Maven wrapper; Go unit/provider/recovery suites passed with race detection and vet; 10 Chromium tests passed, including automated accessibility on implemented screens and mobile overflow/sign-out checks. See [verification ledger](VERIFICATION.md) for scope and limitations. Existing `.idea` changes were left untouched.

## 2026-10-12 increment

Scope: DEL-01/02, CFG-01, JOB-02, UI-02, SEC-01. Added core migrations V6/V7 and Go migration 002 without changing previous migration checksums. Accepted jobs preserve destination versions across edits. Tests cover stale edits, unauthorized buckets/prefixes, append-only history, unknown references, late STARTED projection replay, changed terminal receipt rejection, durable attempts, changed deployment buckets, and conservative scratch cleanup. This earlier increment left Phase 2 open; the subsequent cloud-control and process-recovery increment met its local gate.

## Cloud controls and first Phase 3 increment

Scope: DEL-02, SEC-01, JOB-02, UI-02, ART-02, CFG-01. Added core V8–V10, Go 003, cloud-operation contracts and UI, local operator OIDC fixture, real process-exit recovery test, core staging cleanup, and single-format XLSX/CSV export. No original migration was changed. Dependency selection remains Java 21/Jasper 7.0.3; GCS IAM 1.12.0 is now an explicit Go dependency. Real-cloud credentials were not supplied or used.

## Phase 3 multiple-output increment

Status: Verified locally for this increment; full Phase 3 remains In Progress. Evidence: 20 Java tests, Go race/provider/recovery and vet, 11 Chromium tests (38.6 seconds), strict frontend build/lint, contract drift check, and V11 upgrade of existing local data. Scope: CFG-01, JOB-01/02, ART-02, DEL-01/02, OUT-01, SEC-01, UI-01/02. V11 backfills explicit job relationships and planned outputs without changing old migrations or v1 fingerprints. Go accepts independent delivery/artifact IDs without changing inbox payload serialization. Required/optional aggregation waits for all work and preserves verified artifacts. ADR-003 records lock ordering, restart behavior, bounded workload and rollout constraints. Dependencies and Java 21 baseline are unchanged. Full Phase 3 remains In Progress.

## Phase 3 DOCX and HTML increment

Status: Verified locally for this increment: 24 Java tests, Go race/provider/recovery tests and vet, 13 Chromium tests in 47.2 seconds, frontend build/lint/TypeScript and contract checks. V12 upgraded the existing local database. Scope: ART-02, CFG-01, DEL-01/02, SEC-01, UI-01/02. V12 extends formats and staging constraints. Jasper emits real DOCX and static HTML ZIP packages with bounded generated assets, escaped text and attachment-only access. Core/Go contracts, signed filenames, form/editor, monitoring and cleanup recognize the new formats. Java 21/Jasper 7.0.3 remain unchanged. Full Phase 3 remains In Progress; template/source administration, YAML, preview/settings and reprocessing are still open.

## Phase 3 YAML increment

Status: Verified locally for this increment: 29 Java tests, 15 Chromium tests in 59.7 seconds, frontend build/lint/TypeScript and contract checks. Lost-acknowledgement recovery across JSON/YAML is verified with one actual accepted job and one render execution. Scope: CFG-01, JOB-01, API-01, SEC-01, UI-02. Core now accepts restricted YAML and strict JSON through a common parser/validator; the authenticated converter powers form/editor switching and semantic idempotency in the browser. SnakeYAML 2.4 is explicitly pinned to the existing Boot-managed version. No migration or Go change is needed. Full Phase 3 remains In Progress: templates/dependencies, configurable data sources, preview/settings and reprocessing are still open.

## Phase 3 definition version increment

Status: Verified locally for this increment; full Phase 3 remains In Progress. Scope: TPL-01 (definition metadata lifecycle only), CFG-01, JOB-01, SEC-01, UI-01/02. V13 adds append-only definition versions. Administrators can register definitions, publish revisions with optimistic concurrency, and disable admission. The catalog/detail/history and form/editor use exact versions. Acceptance snapshots the selected definition, rejects unknown/disabled versions, and preserves idempotent retries and execution after retirement.

Evidence: 31 Java tests passed, 16 Chromium tests passed in 58.4 seconds, followed by a passing targeted browser test after the final form-remount change. Production frontend build, lint, TypeScript and contract checks passed; V13 upgraded the existing local database. Go was unchanged and was exercised through real core-to-Go emulator delivery. Definitions still use the bundled trusted JRXML and synthetic dataset. Custom template uploads/dependency bundles, configurable data sources, preview/settings, reprocessing and the remaining Phase 3 exit gate are open.

## Phase 3 linked reprocessing increment

Status: Verified locally for this increment; full Phase 3 remains In Progress. Scope: JOB-01/02, CFG-01, SEC-01, UI-01/02. Owners can reprocess terminal jobs into new linked executions with fresh deadlines, current resource validation, independent Batch instances and new artifacts/delivery objects. Reprocessing is idempotent across concurrent requests and lost acknowledgements. Job detail includes parent links and paginated, owner-scoped reprocessing history. V14 adds a lineage index and self-parent guard; Go is unchanged.

Evidence: **36 Java tests and 17 Chromium tests passed** (browser suite: 1.2 minutes), with production frontend build, lint, TypeScript and generated-contract checks. V14 upgraded the existing local database. Browser verification drops a real acceptance acknowledgement, recovers one child execution, checks its verified S3-emulator delivery, and proves the parent artifact remains unchanged. Remaining Phase 3 scope: custom JRXML uploads/dependency bundles, configurable structured/JDBC sources, schema-driven parameters, preview/localization/output settings and broader catalog/history filters. Phases 4–5 remain Not Started.

## Phase 3 trusted standalone JRXML increment

Status: Verified locally for this increment; full Phase 3 remains In Progress. Scope: TPL-01, CFG-01, SEC-01, ART-01/02, UI-01/02. V15 stores immutable, content-addressed JRXML in core PostgreSQL. Opt-in trusted administrators can upload standalone source, validate/compile it, download retained source, and bind exact fingerprints to definition versions. Accepted jobs verify their saved source hash; a bounded compiled cache can be rebuilt from retained bytes. The Templates/catalog/submission workflow is connected to real Batch/Jasper and Go delivery.

Evidence: **41 Java tests and 18 Chromium tests passed** (full browser run: 1.3 minutes), plus frontend production build, lint, TypeScript and contract checks. V15 upgraded the existing local database. The browser verifies rejected invalid source, actual uploaded branding in generated CSV, verified S3-emulator delivery and exact source retrieval. Java tests verify PDF contents after definition revision and cold-cache recompilation. Go is unchanged. Registration defaults off; generated local fixtures explicitly enable it. This supports reviewed executable code, not untrusted execution. Dependencies, source adapters, arbitrary parameter schemas, preview/localization/output settings and broader catalog filtering remain open Phase 3 work.

## Phase 3 immutable structured-data increment

Status: Verified locally for this increment; full Phase 3 remains In Progress. Scope: DATA-01, CFG-01, TPL-01, SEC-01, ART-02, UI-01/02. V16 registers bounded, typed, immutable JSON sales rows by content fingerprint and binds sources to definition versions. Acceptance and Batch retain the exact data snapshot; old jobs continue using the bundled fixture. Connected source catalog/editor, definition bindings, submission and export use real persisted input. Empty/malformed data is rejected and CSV text is made literal. No JDBC or connection-testing capability is claimed.

Evidence: **44 Java tests and 19 Chromium tests passed** (browser suite: 1.3 minutes), plus frontend production build, lint, TypeScript and contract drift checks. V16 upgraded the existing local database. Tests verify pinned real PDF contents across definition changes, canonical deduplication, strict types, CSV/XLSX formula handling, and actual registered rows delivered through Go to the GCS emulator. Go was unchanged. Remaining Phase 3 work includes JDBC, additional schema/adapter support, dependency bundles, preview/localization/output settings and broader filtering. Phases 4–5 remain Not Started.

## Phase 3 locale/time-zone increment

Status: Verified locally for this increment; full Phase 3 remains In Progress. Scope: CFG-01, DATA-01, ART-02, UI-01/02. Requests optionally select supported locale/timeZone settings; acceptance snapshots the resolved values and Batch passes them to Jasper. Form and JSON/YAML editor preserve settings while legacy title-only request fingerprints remain unchanged. Static template captions and currency units are not rewritten.

Evidence: **46 Java tests and 20 Chromium tests passed** (browser suite: 1.6 minutes), with frontend production build, lint, TypeScript and contract checks. Java verifies German numeric formatting and the New York daylight-saving offset for a fixed instant. Browser verification checks invalid-locale rejection, form/editor round-trips, persisted settings, generated German decimal text and verified emulator delivery. No database migration, dependency or Go change. Remaining Phase 3 work: JDBC, dependency bundles, broader parameters/adapters, preview, advanced output settings and filtering. Phases 4–5 remain Not Started.

## Phase 3 job-history increment — 2026-10-13

Status: Verified locally for this increment; full Phase 3 remains In Progress. Scope: API-01, SEC-01, UI-01/02. Job history now filters by accepted definition ID, status and UTC date interval, with allowlisted newest/oldest sorting, stable timestamp/UUID ties and shareable frontend URLs. Owner isolation is enforced in every predicate. Count and rows share a repeatable-read snapshot; cross-request pagination is explicitly not a frozen snapshot. V17 adds owner/history indexes without modifying prior migrations.

Evidence: **47 Java tests and 21 Chromium tests passed** (browser suite: 1.5 minutes), plus production frontend build, lint, TypeScript, generated-contract and whitespace checks. V17 upgraded the existing local database. Browser regression exercised real Batch/Jasper, Go and both cloud emulators. No dependency, credential or Go changes. Remaining Phase 3 work includes JDBC, dependency bundles, broader schemas/adapters, preview, advanced output settings and catalog filtering. Phases 4–5 remain Not Started.

## Phase 3 PostgreSQL snapshot increment — 2026-10-13

Status: Verified locally for this increment; full Phase 3 remains In Progress. Scope: DATA-01, CFG-01, TPL-01, SEC-01, UI-01/02. A deployment-owned PostgreSQL JDBC adapter provides administrator connection checks, fixed parameterized category selection, bounded immutable snapshot import, append-only provenance, and a connected definition/report workflow. V18 adds provenance. A separate local source database uses a SELECT-only reader. This implements JDBC snapshot ingestion before job submission; live per-job queries, general schema mapping and subreport connections remain open.

Evidence: **51 Java tests and 22 Chromium tests passed** (browser suite: 1.6 minutes), plus frontend production build, lint, TypeScript, contract and whitespace checks. Java verifies real PostgreSQL permissions, timeout recovery, invalid rows, binding, snapshot stability and actual Batch output. Browser verifies connection check, empty-category rejection, snapshot → definition → generated CSV → verified Go/GCS-emulator delivery. V18 upgraded existing local data. Java 21 and dependency pins are unchanged. Generated local configuration gained missing JDBC fixture settings and random credentials; existing values were preserved. Phase 4–5 remain Not Started. Remaining Phase 3 scope includes live/general JDBC adapters, dependency bundles, broader parameters, preview, advanced output settings and catalog search.

## Phase 3 definition-catalog increment — 2026-10-13

Status: Verified locally for this increment; full Phase 3 remains In Progress. Scope: TPL-01, API-01, SEC-01, UI-01/02. The catalog now supports literal ID/name/description search, enabled/disabled filtering, stable name/recent-update sorting, snapshot-consistent counts and 20-row pages. Connected frontend URLs preserve search and template/source creation references. The separate catalog endpoint preserves existing definition IDs and legacy list consumers. V19 adds ordering indexes without changing older migrations.

Evidence: **53 Java tests and 23 Chromium tests passed** (browser suite: 1.7 minutes), plus frontend production build, lint, TypeScript, contract and whitespace checks. V19 upgraded the retained local database. Browser regression exercised OIDC, JDBC snapshot import, Batch/Jasper, Go and both cloud emulators. No dependency, Go or environment changes in this increment. Remaining Phase 3 scope includes live/general JDBC, dependency bundles, broader parameters, preview and advanced output settings. The legacy submission selector remains unpaginated; large-catalog benchmarks and tenant controls are not claimed. Phases 4–5 remain Not Started.

## Phase 3 retained PDF preview increment — 2026-10-13

Status: Verified locally for this increment; full Phase 3 remains In Progress. Scope: TPL-01, ART-01/02, SEC-01, UI-01/02. Job details now provide owner-authorized, integrity-checked PNG previews of retained PDFs with page navigation, resource limits, loading/error/retry states and cleanup of client blob URLs. This uses normal generated artifacts; it does not create another Batch execution, upload, or preview-before-submission path. Other output formats remain download-only. PDFBox 3.0.5 moves from test-only to compile/runtime scope; Java 21 remains the baseline.

Evidence: **55 Java tests and 24 Chromium tests passed** (browser suite: 1.9 minutes), plus frontend production build, lint, TypeScript, generated-contract and whitespace checks. Tests verify real rendered pixels, ownership including administrator isolation, cross-job references, integrity/missing-file errors, malformed input, page/dimension bounds and unchanged attempts/deliveries. Browser coverage includes multi-page navigation, a clearly injected preview interruption followed by real recovery, authorization from a second identity and axe checks. No migration, environment or Go change. Phase 3 remains open for live/general JDBC, dependency bundles, broader parameters and advanced output settings; hard parser isolation and production validation remain release work. Phases 4–5 remain Not Started.
