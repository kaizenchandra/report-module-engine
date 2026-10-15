# Requirements and acceptance traceability

Each family below retains the full corresponding scope in the implementation request. The listed implementation is the current evidence, not a claim that every member of a family is complete.

| ID      | Requirement family                                                | Phase | Implementation / acceptance                                                                                         |
| ------- | ----------------------------------------------------------------- | ----- | ------------------------------------------------------------------------------------------------------------------- |
| ARC-01  | Java owns domain; Go owns delivery; Next owns UI                  | 0     | Separate service roots, ADR-001                                                                                     |
| SEC-01  | OIDC session, OAuth bearer, role enforcement, CSRF                | 0–5   | SecurityConfig; browser foundation spec; object checks required at each domain API                                  |
| CFG-01  | Versioned strict JSON/YAML contract and policy precedence         | 1,3   | contracts; strict JSON/YAML codec; versioned shape and mapping validation; broader resource precedence remains in 3 |
| TPL-01  | Trusted templates, fingerprints, dependency closure, versions     | 1,3   | Versioned trusted JRXML, immutable PNG/JRTX/JRXML closure, bounded child/grandchild graphs, manifest/hash pins and original archives; small embedded TTF subsets; custom bindings/deeper graphs and broader font packs remain open                                                                 |
| DATA-01 | Structured and JDBC sources, typed parameters, binding, deadlines | 1,3   | V16 typed rows, V18 JDBC imports, per-job JDBC capture with immutable retries/reprocessing, typed P_ parameters and locale/time-zone rendering; general JDBC/render-time reads and arbitrary schemas remain open                                                        |
| JOB-01  | Durable acceptance, idempotency, lost acknowledgements            | 1     | PostgreSQL transaction and duplicate/conflict tests                                                                 |
| JOB-02  | State, attempts, fencing, cancellation, deadlines, restart        | 1,2,5 | Batch recovery and linked reprocessing with concurrent idempotency, ownership, fresh-resource and real PDF tests; broader fault tests remain                                                          |
| ART-01  | PDF output, retained artifacts and integrity                      | 1     | Jasper compile/fill/export and PDF text tests                                                                       |
| ART-02  | XLSX, CSV, HTML, DOCX, output policies                            | 3     | Exporters verified individually; bounded CSV/XLSX options including a UTF-8 BOM and worksheet names; HTML attachment-only                                                   |
| DEL-01  | Authenticated durable handoff, independent ownership              | 1     | Delivery contract, Java outbox / Go inbox                                                                           |
| DEL-02  | S3 + GCS, conflict, checksums, reconciliation, links              | 1,2   | First S3 emulator; provider contracts and real cloud evidence separate                                              |
| OUT-01  | Separate output/delivery/notification outcomes                    | 1–4   | Aggregation truth table in architecture ADR                                                                         |
| API-01  | Versioned API, pagination, errors, polling, correlation           | 0–3   | OpenAPI; bounded polling; advanced filters in 3                                                                     |
| UI-01   | Connected shell/catalog/job submission/list/detail/download       | 0,1   | Server-rendered authenticated views and browser tests                                                               |
| UI-02   | Templates/sources/destinations/editor/ops administration          | 2–4   | Ship only functional settings                                                                                       |
| GOV-01  | Scheduling/DST, batch, fairness, tenant controls, approvals       | 4     | Explicitly unsupported until gate passes                                                                            |
| GOV-02  | Retention/holds/deletion/audit/notifications                      | 1,4   | Append-only audit foundation; governance in 4                                                                       |
| OPS-01  | CI, deploy, observability, restoration/security/performance       | 0,5   | Baseline CI; production exercises in 5                                                                              |

## Proposed measurable targets (not approved commitments)

| Target            | Proposal / bounded workload                                                                | Approved objective     |
| ----------------- | ------------------------------------------------------------------------------------------ | ---------------------- |
| NFR-SUBMIT        | p95 <500 ms, 10 submissions/s, excluding identity provider                                 | TBD-SUBMISSION         |
| NFR-QUEUE         | p95 <5 s when capacity available                                                           | TBD-QUEUE              |
| NFR-RENDER        | p95 <10 s for 1,000-row PDF, <=10 MiB                                                      | TBD-GENERATION         |
| NFR-UPLOAD        | p95 <15 s for 10 MiB in same cloud region                                                  | TBD-UPLOAD             |
| NFR-E2E           | p95 <30 s for reference workload                                                           | TBD-END-TO-END         |
| NFR-CAPACITY      | Start with 1 renderer/core instance, 2 upload slots; benchmark before scaling              | TBD-THROUGHPUT         |
| NFR-DURABILITY    | Acknowledge only committed PostgreSQL records; no in-memory acceptance                     | TBD-RPO                |
| NFR-RECOVERY      | Proposed RPO 5 min and RTO 60 min; restore exercise required                               | TBD-RTO                |
| NFR-AVAILABILITY  | Fail admission closed when database unavailable; retain accepted work                      | TBD-AVAILABILITY       |
| NFR-INTEGRITY     | SHA-256 byte verification and meaningful PDF text checks                                   | TBD-RENDER-CORRECTNESS |
| NFR-ACCESSIBILITY | WCAG 2.2 AA target; keyboard and automated checks required                                 | TBD-A11Y               |
| NFR-COST          | Proposed staging retention 7 days, private cloud lifecycle 30 days; holds override cleanup | TBD-RETENTION-BUDGET   |

Locale en-US and UTC initially; no promise of tenant fairness, horizontal scale, source snapshots or arbitrary-size reports until tested.

## Phase 2 implementation trace (2026-10-12)

| Requirement                         | Code                                                                                 | Verification                                                                                                |
| ----------------------------------- | ------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------- |
| CFG-01 / UI-02 destination versions | DestinationController, V6, destinations editor/history, API-driven submission select | BatchRecoveryTest destination snapshot/version/path/permission cases; browser history and permission denial |
| DEL-01 / JOB-02 attempt evidence    | Go attempts.go/002, Java DeliveryController/V7, job monitor                          | DurableReceiptRecovery; BatchRecoveryTest stale projection; browser verified attempt                        |
| DEL-02 allowlisted delivery         | Go Worker provider selection and persisted bucket checks                             | Alias/custom prefix recovery against S3Mock; changed-bucket rejection                                       |
| SEC-01 / DEL-02 receipt integrity   | DeliveryController full receipt comparison                                           | Identical receipt replay succeeds; changed version fails 409                                                |
| DEL-02 temporary-file lifecycle     | Go scratch.go                                                                        | Active/unrelated/symlink preservation; stale upload removal; private mode enforcement                       |

That increment left connection checks, signing and staging cleanup open; the following increment implemented and locally verified them. Broader partition/restore verification remains Phase 5 work, and real-cloud IAM remains unverified.

## Phase 2 gate and Phase 3 format trace

| Requirement                              | Code                                                                                         | Executed evidence                                                                                                                |
| ---------------------------------------- | -------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| DEL-02 / SEC-01 bounded cloud operations | CloudOperationController/V8, Go controls.go/003, cloud_operations.go, CloudOperation UI      | Owner/admin/CSRF/expiry/result tests; durable callback replay; SDK signing tests; real OIDC administrator checks and S3 download |
| DEL-02 / JOB-02 process recovery         | Go crash_test.go / recovery_test.go                                                          | Separate worker exits after verified upload; replacement reconciles provider object and durable inbox                            |
| DEL-02 staging cleanup                   | ArtifactCleanup                                                                              | Real PostgreSQL test preserves referenced/active/recent/unrelated files and symlinks                                             |
| ART-02 / CFG-01 single-format exports    | ReportFormat, Renderer, ReportBatch, V9/V10, schema, Go formats.go, submission and output UI | CSV formula cases; real workbook structure/numeric cells; real Batch persistence; CSV/XLSX browser content and cloud checksum    |
| UI-02 operator workflow                  | Seeded local OIDC operator, destination editor/check/history UI                              | Browser registration, checks against both emulators, version edit/history, axe                                                   |

Phase 2: Verified locally; real cloud enforcement remains unverified. Phase 3: In Progress. Version 2 supports multiple outputs/destinations and partial outcomes. Template/resource administration, data adapters, preview/settings and reprocessing remain required before the Phase 3 exit gate can pass.

## Phase 3 mapping increment

| Requirement        | Code                                                                   | Verification                                                                                                                                                |
| ------------------ | ---------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| CFG-01 / JOB-01    | ReportRequest v1/v2, JobService snapshots, schema/OpenAPI              | MultiOutputTest: legacy fingerprint, duplicate acceptance, conflicting fingerprint, duplicate physical targets, unknown/ignored fields and invalid mappings |
| ART-02 / OUT-01    | V11 report_output, ReportBatch, JobOutcomes                            | Real PostgreSQL/Batch, optional export fault injection with intact real PDF, required/optional delivery outcomes, concurrent receipts and exact replay      |
| DEL-01/02 / SEC-01 | Explicit artifact/delivery IDs, owner-scoped routes, Go key validation | Go task compatibility, provider/recovery suite, artifact owner isolation, explicit signing route                                                            |
| JOB-02             | Job lock before delivery lock; cancellation updates all pending tasks  | MultiOutputTest: simultaneous receipts and cancellation followed by late receipts                                                                           |
| UI-01/02           | MultipleOutputs form, JSON round-trip, output/delivery monitoring      | Three-format/both-provider browser journey and automated accessibility                                                                                      |

Failure injection in MultiOutputTest uses a renderer spy for one CSV export and authenticated synthetic receipts; successful PDF generation and PostgreSQL/Batch state are real. Browser tests separately exercise real Java–Go callbacks and both cloud emulators. No real provider test claim is implied.

## Phase 3 document/HTML increment

| Requirement     | Code                                                                                 | Verification                                                                                                                                          |
| --------------- | ------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| ART-02 / SEC-01 | Renderer, HtmlPackage, V12                                                           | DocumentFormatsTest: real DOCX content/relationships, HTML escaped text and CSP, actual generated image closure, missing/path/conflict/size rejection |
| CFG-01 / ART-02 | ReportRequest and OpenAPI/schema five-format bounds, ReportFormat compound extension | BatchRecoveryTest: real Batch persistence and MIME/storage constraints for DOCX/HTML                                                                  |
| DEL-01/02       | Go format allowlist, MIME selection and signing                                      | Both provider contracts for five types; rejected bare HTML/ZIP; signed package filenames and MIME                                                     |
| UI-01/02        | Single/multiple format controls, HTML package guidance, downloads                    | Browser DOCX/HTML content and cloud checksums; five-format mappings and accessibility                                                                 |

DOCX has structural/content verification, not a Word/LibreOffice visual-layout certification. HTML is a downloadable archive, not a trusted-origin browser preview. Arbitrary fonts/resources and untrusted templates remain unsupported.

## Phase 3 YAML trace

| Requirement     | Implementation                                                             | Verification                                                                                                                                |
| --------------- | -------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| CFG-01 / SEC-01 | ConfigurationCodec, exact scalar and versioned field checks                | ConfigurationCodecTest: round-trips, duplicates, anchors/aliases/tags/merge keys, depth/size, UTF-8, wrong types and explicit-null bypasses |
| JOB-01 / API-01 | Shared JSON/YAML submission, canonical DTO fingerprint                     | MultiOutputTest: same-key cross-format recovery and conflicting request; unchanged valid v1 serialization                                   |
| UI-02 / SEC-01  | ConfigurationController, form/JSON/YAML editor, mounted-form canonical key | No-write conversion, CSRF, safe errors; browser YAML output/cloud integrity and withheld-ACK recovery with one execution                    |

See ADR-005 for media types, parser bounds, unsupported YAML features, no-resource-resolution conversion semantics and compatibility decisions.

## Phase 3 job-history increment

| Requirement | Implementation | Verification |
| --- | --- | --- |
| API-01 / SEC-01 | Owner-bound exact definition/status and UTC date predicates; allowlisted sort; repeatable-read count/page; V17 indexes | JobPersistenceTest: real PostgreSQL date boundaries, tied timestamps, disjoint pages, owner isolation, invalid dates/ranges/sort and injection-like input |
| UI-01/02 | Server-rendered Jobs filters, URL persistence, reset and stable pagination links | Chromium history workflow: shareable URL reload, page preservation, empty/error/reset states, API rejection and axe checks |

Broader catalog search, global operator search, cursor snapshots and large-history performance validation remain open. This increment does not implement tenant governance or scheduling.

## Phase 3 JDBC snapshot increment

| Requirement | Implementation | Verification |
| --- | --- | --- |
| DATA-01 / CFG-01 | JdbcSalesAdapter: deployment-owned PostgreSQL profile, parameter binding, read-only repeatable-read snapshot, type/range/size limits and timeouts | JdbcSourceTest: real SELECT-only identity, rejected writes, bound/injection-like category, empty/null/fractional/oversized inputs, query-lock timeout and recovery |
| TPL-01 / JOB-01 | SourceRegistry shares immutable structured storage; V18 source_import provenance and audit; existing definition/acceptance/Batch path | Changed remote rows cannot alter old snapshot; deduplication, append-only provenance and actual Batch CSV content |
| SEC-01 / UI-01/02 | Administrator/CSRF protected check/import/history; no browser SQL/credentials; connected source-to-definition flow | Java permission/redaction tests; full browser connection/import/definition/CSV/Go delivery and axe coverage |

Initial JDBC support is snapshot import before job submission. Live per-job execution, general adapters/field schemas, subreport sources, tenant ACLs and production TLS/secret-manager integration remain open DATA-01 scope.

## Phase 3 definition-catalog increment

| Requirement | Implementation | Verification |
| --- | --- | --- |
| TPL-01 / API-01 | DefinitionCatalogController, V19, OpenAPI DefinitionPage; current ID/name/description literal search, lifecycle filter, deterministic order and snapshot-consistent count | MultiOutputTest: 23 real PostgreSQL definitions, tied names, 20/1 enabled pages, disabled ordering, literal `%`/`_`, current version after revision, invalid filters and preserved legacy array contract |
| SEC-01 / UI-01/02 | Authenticated catalog GET and server-rendered filters/shareable pages; template/source draft references preserved | Unauthorized/role-denied tests; Chromium search/page/reload/order/empty/error/reset workflow and axe checks; sample-report discovery through search |

Legacy definition-array consumers and the submission selector remain unpaginated. Full-text search, historical-version search, tenant filtering and large-catalog benchmarks are not implemented by this increment.

## Phase 3 retained PDF preview increment

| Requirement | Implementation | Verification |
| --- | --- | --- |
| TPL-01 / ART-01 / SEC-01 | PdfPreviewController/PdfPreview use owned, retained, SHA-256-checked PDF output; bounded PNG rasterization; no new generation path | MultiOutputTest: actual Batch PDF and nonblank PNG pixels, owner/admin isolation, cross-job IDs, bad page, CSV rejection, integrity and missing-file errors, unchanged attempt/delivery counts |
| ART-02 / UI-01/02 | Job detail preview with page controls, visible limits, loading/errors/retry/close and authorized download retained | PdfPreviewTest: first-20/page-size limits, malformed PDF and symlink rejection; Chromium multi-page image, injected preview interruption followed by real retry, unchanged execution/artifact/delivery state and unrelated-user rejection |

Preview-before-submission, arbitrary uploaded PDFs, other preview formats, hard parser isolation and report-document accessibility are not implemented. Trusted-template policy and normal delivery requirements remain in force.

## Phase 3 output-options increment

| Requirement | Implementation | Verification |
| --- | --- | --- |
| CFG-01 / ART-02 | Optional nonempty outputOptions for both versions; strict types/format membership; snapshotted CSV delimiter and XLSX worksheet splitting | OutputOptionsTest: delimiter-aware quoting, formula protection, real multi-page workbook counts, JSON/YAML round-trip, null/type/unknown-property rejection and unchanged legacy serialization |
| JOB-01 / UI-01/02 | Settings participate in idempotency; selected-format form controls and editor restoration | MultiOutputTest: duplicate recovery, changed-option conflict, accepted snapshot and real Batch CSV; browser form/editor/YAML/validation/axe/download/cloud byte checks |

PDF encryption, workbook names/protection and arbitrary exporter properties remain open ART-02 scope. No migration, delivery-protocol or dependency change.

## Phase 3 typed template parameter increment

| Requirement | Implementation | Verification |
| --- | --- | --- |
| DATA-01 / CFG-01 / TPL-01 | Five bounded scalar types, exact immutable-template name/type binding, sorted canonical values and date-zone conversion | TypedParameterTest: actual PDF/CSV/XLSX values, literal formula text, date zone, JSON/YAML equivalence, range/name/type/count/null rejection and legacy serialization |
| JOB-01 / JOB-02 | Accepted values retained in request snapshots; idempotency recovery/conflict; pinned schemas across revisions and reprocessing | TemplateRegistrationTest: real PostgreSQL/Batch output, exact-version authenticated discovery, rejected acceptance without inserted jobs, revision stability and linked reprocessing (terminal state synthetic because dispatch is disabled) |
| UI-01/02 / SEC-01 | Exact-version metadata-driven form, stale-fetch cancellation, unavailable/retry state and editor representability checks | Chromium template registration → definition → typed fields → JSON/YAML → real CSV/Go/S3-emulator delivery; injected metadata interruption, retained typed false/decimal scale/date and axe |

Optional/null/collection parameters, arbitrary classes/schemas, live JDBC parameter binding and template dependencies remain open. No migration, dependency or Go change.

## Phase 3 PNG dependency increment

| Requirement | Implementation | Verification |
| --- | --- | --- |
| TPL-01 / CFG-01 | Complete bounded named PNG closure, source/assets manifest fingerprint, V20 immutable rows and read-time integrity | TemplateAssetsTest: actual images in PDF/XLSX/DOCX/HTML, cold compiler, archive bytes, changed bytes/names, legacy hash, malformed paths/expressions/files/dimensions/count/total budgets |
| JOB-01/02 / SEC-01 | Atomic registration/deduplication, pinned accepted content across definition revisions, admin-only archives | TemplateRegistrationTest: real PostgreSQL/Batch old-red/new-blue image pixels, UPDATE/DELETE guards, controlled corruption rejection, no rows after missing closure and archive role checks |
| UI-01/02 / ART-02 | PNG file selection and limits, source/image version registration, complete archive download and generated HTML assets | Chromium: missing-image recovery, selected files/axe, exact source/image archive bytes, actual generated PNG pixel and successful Go/S3-emulator output byte equality |

Subreport/style/font bundles, arbitrary ZIP uploads and production parser/compiler isolation remain open. No dependency or Go change; V20 is additive.

## Phase 3 JDBC import recovery increment

| Requirement | Implementation | Verification |
| --- | --- | --- |
| DATA-01 / JOB-01 / CFG-01 | Optional actor-scoped import keys, ordered selector fingerprints, V21 immutable result ledger and additive importId/replay header | JdbcSourceTest: original result after changed rows and revoked permission, changed-selector conflict, invalid key, actor isolation, unchanged keyless imports and ledger immutability |
| JOB-01 / SEC-01 | Source read closes before core transaction; advisory-lock recheck; rows/provenance/audit/key publish atomically | Two real JDBC reads of different source rows converge to one published import; core transaction inactivity during reads; injected persistence failure rolls back all four record families |
| UI-02 | Mounted-form pending key preserves acknowledgement retries; changed fields create new keys | Chromium injected post-commit acknowledgement loss recovers the same key/import ID/source hash and one history entry, with axe coverage |

This increment improves snapshot import recovery. Live/general JDBC, tenant ACLs and lifecycle/retention policy remain open; no dependency or Go change.

## Phase 3 named style dependency increment

| Requirement | Implementation | Verification |
| --- | --- | --- |
| TPL-01 / CFG-01 | Complete bounded PNG/JRTX closure, constant style inheritance validation/flattening, v2 content domain and V22 asset-name extension | TemplateStylesTest: actual PDF red pixels, HTML color, cold cache, combined PNG/styles, byte-sensitive revisions, legacy PNG hash, missing/duplicate/cyclic/external/oversized style rejection |
| JOB-01/02 | Immutable source/style versions survive current-definition edits | TemplateRegistrationTest: actual PostgreSQL/Batch old-red/new-blue rendering and exact original style archive bytes |
| UI-02 / ART-02 / SEC-01 | Mixed asset selection, same trusted/admin/CSRF policy, complete archive download and normal generation | Chromium missing-style recovery, selected filenames/axe, source/style archive equality and styled HTML/Go/S3-emulator byte verification |

Conditional styles, font bundles, subreports and live/general JDBC remain open. No dependency or Go change.

## Phase 3 row-scoped subreport increment

| Requirement | Implementation | Verification |
| --- | --- | --- |
| TPL-01 / CFG-01 | Complete PNG/JRTX/JRXML closure, one-level detail components, matching parent parameter types, current-row binding, v3 identity and V23 | TemplateSubreportsTest: five-format rendering, current-row/CSV behavior, typed child parameters with actual PNG/style pixels, revisions and rejected missing/unused/nested/external/custom/over-limit/invalid UTF-8 dependencies |
| JOB-01/02 / ART-02 | Accepted jobs pin original parent and child bytes; archives retain original files | TemplateRegistrationTest: actual PostgreSQL/Batch original/revised child output across definition edits and exact original child archive bytes |
| UI-02 / SEC-01 | JRXML asset selection under existing trusted/admin/CSRF policy, complete archives and normal generation | Chromium missing-child recovery, selected filenames/axe, original parent/child archive equality, child-row HTML and exact Go/S3-emulator delivery bytes |

General/nested subreports, custom datasets/bindings, fonts and live/general JDBC remain open. No dependency or Go change.

## Phase 3 JDBC-backed job admission increment

| Requirement | Implementation | Verification |
| --- | --- | --- |
| DATA-01 / CFG-01 | Versioned jdbc:sales-jdbc category selectors; bounded fixed read outside core transactions; immutable row/import capture at admission | JdbcSourceTest: real read-only PostgreSQL rows, changed-source captures, transaction-boundary assertion and source-unavailable rejection |
| JOB-01/02 | Preflight and final resource/quota/key validation; atomic rows/import/audit/job/output publication; replay and reprocessing preserve accepted data/import | JdbcSourceTest: two differing concurrent reads publish one job/import, changed-request 409, offline replay/reprocessing, retirement during the read and injected failure after job/output insertion with complete rollback |
| UI-02 / ART-02 / SEC-01 | Admin definition setup from category without importing now; unchanged job requests, ownership and deployment-owned connection policy | Chromium setup/axe, no pre-job import, lost-acknowledgement same job/key/import recovery, correct category CSV, exact Go/S3-emulator bytes and reprocessing without another import |

General JDBC profiles/queries, render-time live reads, additional field schemas and tenant policies remain open. V23 remains current; no new migration, dependency or Go contract change.

## Phase 3 bounded nested subreport increment

| Requirement | Implementation | Verification |
| --- | --- | --- |
| TPL-01 / CFG-01 | Complete acyclic child/grandchild closure; depth 2, four references per document and eight expanded instances per row; immediate-parent typed parameter checks; v4 nested identity | TemplateNestedSubreportsTest: all formats, exact eight-instance graph and rejected excessive fan-out/depth, cycles, missing grandchildren and undeclared intermediate parameters; unchanged flat v3 manifest |
| DATA-01 / JOB-01/02 | Fresh current-row binding at every level; generated defaults isolated; typed values/locale/time zone and child assets preserved; descendant versions pinned | Actual leaf PDF/CSV rows, DATE/locale/time-zone formatting, red style/blue PNG pixels; PostgreSQL/Batch original/revised grandchild output and exact original archive bytes |
| UI-02 / ART-02 / SEC-01 | Advertised graph policy and upload guidance under unchanged trusted/admin/CSRF controls; complete original archives and normal output delivery | Chromium missing-closure recovery, two-file selection/axe, policy values, original parent/child/grandchild archives, real grandchild HTML and identical retained/Go/S3-emulator bytes |

General/custom subreport bindings, deeper graphs, fonts and general JDBC remain open. No migration, dependency or Go contract change; V23 remains current.

## Phase 3 small TrueType dependency increment

| Requirement | Implementation | Verification |
| --- | --- | --- |
| TPL-01 / CFG-01 / SEC-01 | Complete TTF/JRTX/nested closure; bounded regular TrueType subsets, embedding flags and names; v5 content identity, V24 asset-name extension and per-fill contexts | TemplateFontsTest: malformed/truncated/oversized, missing/unused/external fonts, restricted embedding flags and unsupported glyph rejection; same-name regular/wide/original isolation |
| ART-01/02 / DATA-01 | Embedded PDF, exact HTML TTF/CSS package closure and relative paths, no script tags; office family hints and existing CSV protection | Actual embedded font streams/widths/text, exact HTML font bytes/CSS targets, Office XML names, blue PNG/font combination and JRTX font use in a grandchild |
| JOB-01/02 / UI-02 | Immutable font revisions, original archives and functional upload/output workflows | PostgreSQL/Batch original/wide metrics across definition edits and exact original TTF archive; Chromium missing-font recovery/axe, original bundle bytes, verified delivery and actual font loading from a separate HTTP origin |

Full font packs, automatic input subsetting, linked weight/style families, broad script/office visual fidelity, general JDBC and custom subreport bindings remain open. No new dependency or Go contract change.

## Phase 3 dataset summary subreport increment

| Requirement | Implementation | Verification |
| --- | --- | --- |
| TPL-01 / CFG-01 / SEC-01 | Direct summary-section components with server-generated full immediate-parent collection binding; unchanged depth/closure/expansion limits, v6 identity and trusted-code controls | TemplateDatasetSubreportsTest: exact manifest, independent sibling cursors, nested dataset/row scopes, rejected custom bindings/internal declarations |
| DATA-01 / ART-01/02 / JOB-01/02 | Accepted rows only; fresh child datasource, shared CSV protection, immutable source pins | All five exports, exact PDF/CSV counts, empty-data behavior, PostgreSQL/Batch old/new child output after definition edits and exact original archive |
| UI-02 / ART-02 | Upload guidance, complete source archives and connected generation/delivery | Chromium dataset-summary workflow with a row-scoped grandchild, closure recovery/axe, original files, exact HTML row counts and retained/Go/S3-emulator byte equality |

General child queries/connections, custom mappings/datasets and deeper graphs remain open. No migration, dependency or Go contract change.

## Phase 3 XLSX worksheet-name increment

| Requirement | Implementation | Verification |
| --- | --- | --- |
| ART-02 / CFG-01 | Optional bounded ASCII XLSX base name, deterministic page suffixes within 31 characters; same validation in both request versions and JSON/YAML | WorksheetNamesTest: actual combined/split workbook XML, more than nine pages, collision-prone/31-character names, rejected invalid names/types/formats, exact legacy serialization |
| JOB-01 / JOB-02 | Accepted output name retained; canonical idempotency comparison and normal Batch rendering | PostgreSQL duplicate recovery, changed-name conflict, accepted configuration and actual named XLSX artifact |
| UI-01/02 / ART-02 | Conditional name control, editor restoration/error recovery and no deselected-format option leakage | Chromium form/JSON/YAML/validation/axe, exact workbook XML and literal formula-like text, SHA-256 and retained/Go/S3-emulator bytes |

Workbook protection, arbitrary exporter properties, broader naming policies and Office visual certification remain open. No migration, dependency or Go contract change.

## Phase 3 CSV byte-order-mark increment

| Requirement | Implementation | Verification |
| --- | --- | --- |
| ART-02 / CFG-01 | Optional CSV-only boolean BOM flag; fixed UTF-8 and existing CSV protections | CsvBomTest: exact three-byte prefix and unchanged payload for all delimiters, Unicode/quoting/formula/CRLF checks, both JSON/YAML wire versions, strict types/formats and legacy serialization |
| JOB-01 / JOB-02 | Accepted flag retained, canonical idempotency and complete-artifact hashing | PostgreSQL/Batch retained true, same-key recovery, changed-flag conflict, actual Unicode CSV prefix/content and SHA-256 |
| UI-01/02 / ART-02 | Conditional default/true/false control, editor recovery and no deselected-format leakage | Chromium explicit false and true YAML restoration, malformed boolean recovery/axe, exact BOM/content and retained/Go/S3-emulator bytes; default CSV and XLSX regression retained |

Other encodings/custom record delimiters, arbitrary exporter properties and external spreadsheet encoding-detection certification remain open. No migration, dependency or Go contract change.
