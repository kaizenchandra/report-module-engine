# Requirements and acceptance traceability

Each family below retains the full corresponding scope in the implementation request. The listed implementation is the current evidence, not a claim that every member of a family is complete.

| ID      | Requirement family                                                | Phase | Implementation / acceptance                                                                                         |
| ------- | ----------------------------------------------------------------- | ----- | ------------------------------------------------------------------------------------------------------------------- |
| ARC-01  | Java owns domain; Go owns delivery; Next owns UI                  | 0     | Separate service roots, ADR-001                                                                                     |
| SEC-01  | OIDC session, OAuth bearer, role enforcement, CSRF                | 0–5   | SecurityConfig; browser foundation spec; object checks required at each domain API                                  |
| CFG-01  | Versioned strict JSON/YAML contract and policy precedence         | 1,3   | contracts; strict JSON/YAML codec; versioned shape and mapping validation; broader resource precedence remains in 3 |
| TPL-01  | Trusted templates, fingerprints, dependency closure, versions     | 1,3   | V13 definitions + V15 immutable trusted standalone JRXML, compilation, hash pinning and cache tests; dependency bundles remain open                                                                 |
| DATA-01 | Structured and JDBC sources, typed parameters, binding, deadlines | 1,3   | V16 immutable typed inputs plus validated locale/time-zone rendering and browser tests; JDBC/arbitrary schemas remain open                                                        |
| JOB-01  | Durable acceptance, idempotency, lost acknowledgements            | 1     | PostgreSQL transaction and duplicate/conflict tests                                                                 |
| JOB-02  | State, attempts, fencing, cancellation, deadlines, restart        | 1,2,5 | Batch recovery and linked reprocessing with concurrent idempotency, ownership, fresh-resource and real PDF tests; broader fault tests remain                                                          |
| ART-01  | PDF output, retained artifacts and integrity                      | 1     | Jasper compile/fill/export and PDF text tests                                                                       |
| ART-02  | XLSX, CSV, HTML, DOCX, output policies                            | 3     | Exporters verified individually; HTML isolated or attachment-only                                                   |
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
