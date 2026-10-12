# ADR-003: Versioned mappings and independent outcome aggregation

Accepted for implementation, 2026-10-12. Scope: CFG-01, ART-02, DEL-01/02, OUT-01, JOB-02, UI-01/02.

Configuration version 1 keeps its six fields, one output and one required destination. Its canonical JSON fingerprint is unchanged so a lost acknowledgement can still recover an earlier accepted job. Version 2 uses one to five distinct PDF/XLSX/CSV/DOCX/HTML outputs (extended by ADR-004), an optional `optionalOutputs` subset, and one to six explicit delivery mappings. Each mapping selects a registered destination, one or more selected formats, and a required flag. At least one output must be required; every output must have a mapping, and each required output must have a required delivery. Required deliveries cannot depend on optional outputs. Duplicate destination/format pairs and aliases that resolve to the same physical provider/bucket/prefix/format are rejected. Unknown and ignored JSON properties are rejected. JSON Schema describes shape; Java additionally checks cross-field and resolved-resource constraints.

At acceptance, core resolves all destination versions under shared locks in sorted reference order. A transaction commits the immutable configuration, planned output rows, job, idempotency association and audit event. Version 2 snapshots all destinations; subsequent edits cannot redirect an accepted delivery. Resource authorization remains server-side. No new credential or endpoint inputs are introduced.

V11 adds job foreign keys to artifacts and delivery tasks, backfills existing rows, and removes the former assumption that these identifiers equal the job identifier. Existing version 1 IDs remain unchanged. Each version 2 artifact has a distinct UUID; each destination task has another UUID. The object key uses the artifact UUID and format extension. Uniqueness is scoped to provider, bucket and key, allowing one artifact at the same key in different providers. Go's task fields and fingerprint encoding are unchanged, but validation now accepts distinct delivery/artifact UUIDs and binds the key to the artifact ID. Go still owns only upload execution and receipts, never report aggregation.

The Batch identifying parameter and job name remain unchanged. The render tasklet processes bounded outputs sequentially, atomically stages each complete file, and publishes metadata and delivery tasks only after checking the job's cancellation state, deadline and render fence. The tasklet transaction commits all output results together. An export or staging failure records `EXPORT_FAILED` for that output and does not discard successful formats. No delivery task is created for an unavailable output; its intended mappings remain in the immutable configuration. Failed required outputs still allow successful outputs to be delivered. A crash before commit regenerates all outputs in that tasklet, with orphan cleanup for unreferenced files. There is no arbitrary-page resume or per-output user retry in this increment. Each export currently performs a separate fill of the same deterministic fixture; general data-source snapshot consistency is still pending.

## Aggregation

Every receipt locks the parent job before its delivery row, the same lock order used by cancellation. All mutations and aggregation commit in one transaction. Concurrent receipts for different deliveries cannot miss each other's outcomes. Identical terminal receipt replay is a no-op; changed terminal receipts are rejected.

| Condition after rendering                                                         | Domain outcome                                                                             |
| --------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| Any output or created delivery is pending                                         | DELIVERING                                                                                 |
| All work settled; any required output unavailable or required delivery unverified | FAILED                                                                                     |
| All required work succeeded; any optional output/delivery failed                  | PARTIAL_SUCCESS                                                                            |
| All requested outputs available and all deliveries verified                       | SUCCEEDED                                                                                  |
| Cancellation committed before a terminal aggregate                                | CANCELLED; pending deliveries cancelled; late receipts acknowledged without changing state |

Final aggregation exposes `REQUIRED_WORK_FAILED` or `OPTIONAL_WORK_FAILED`; output and delivery diagnostics retain the specific failure. Existing verified artifacts remain downloadable after partial or failed outcomes. Deadline expiry remains a failure and cancels pending deliveries. Cancellation cannot retract an upload already completed or in flight. Notifications remain unimplemented and cannot affect these outcomes.

Owner-scoped endpoints select artifacts and deliveries explicitly: `GET /jobs/{id}/outputs/{artifactId}` and `POST /jobs/{id}/deliveries/{deliveryId}/download-links`. Legacy convenience endpoints require exactly one artifact/delivery and otherwise return 409. Signed-link deduplication is per delivery and cannot return another destination's link.

The connected form and JSON editor share both request versions. The UI shows each output policy/status, each delivery's format/policy/receipt, and attempts identified by delivery ID plus attempt number. Switching from JSON to the form refuses unrepresentable fields instead of silently deleting them.

## Rollout and limits

Stop admission and rendering before upgrading. Upgrade Go and core together before admitting version 2 requests; older Go workers require identical artifact/delivery IDs. Existing durable v1 inbox payloads remain compatible. V11 changes foreign-key ownership, so rolling old core binaries against it is unsupported. Back up databases and staging before migration. Rollback requires a coordinated restore or forward fix; never edit applied migrations, discard accepted jobs, or run old workers against queued v2 jobs.

Bounds remain 20 active jobs per subject, 15-minute domain deadline, five outputs of at most 10 MiB each and at most 30 delivery tasks per job. There is one renderer and one sequential delivery worker per database. Fair scheduling, throughput guarantees, data-source snapshots, user reprocessing, per-output retries and full Phase 3 administration remain open. Provider tests use local emulators, not real cloud IAM or durability.
