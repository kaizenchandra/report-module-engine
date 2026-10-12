# ADR-007: Linked user-requested reprocessing

Status: Accepted for the current Phase 3 increment.

## Semantics

An owner may reprocess a terminal SUCCEEDED, PARTIAL_SUCCESS, FAILED or CANCELLED job through `POST /api/v1/jobs/{id}/reprocess`. The endpoint requires a valid Idempotency-Key and CSRF protection for the session; it accepts no body or overrides. An administrator cannot reprocess another subject's job. Non-owned parents return 404 before state diagnostics. Active parents return 409.

Reprocessing creates a new domain job with parent_job_id, a fresh 15-minute deadline, fresh output/delivery identifiers, and a new Spring Batch JobInstance identified by the new job ID. It copies the parent's declarative request, including the exact definition version, parameters, output requirements and destination mappings. Normal admission validates the currently enabled definition/version and destination references. Destination configuration is resolved anew at acceptance; changed prefixes/buckets may therefore produce a different destination snapshot. The UI states this before the action. This is not a bit-for-bit historical replay or a source-supported data snapshot guarantee. Only the synthetic sample dataset and bundled trusted template currently exist.

The core regenerates every requested output. Existing artifacts, provider objects, receipts and parent job outcome are untouched. A failed delivery's bounded Go retries remain independent and reuse retained artifacts. No new Go orchestration or delivery retry endpoint is introduced. Cancellation of the child does not cancel or delete its parent's results.

## Durability and idempotency

Normal submission and reprocessing use the same owner-scoped idempotency namespace and per-owner PostgreSQL transaction lock. Plain submission fingerprint serialization is unchanged. Reprocessing prefixes the canonical request with `reprocess:{parentUUID}:` before SHA-256, preventing a key from silently referring to plain submission or another parent's execution. Concurrent equivalent requests return one child. A distinct key denotes a distinct intentional execution.

Ownership and terminal-parent validation precede lookup; idempotency lookup then precedes quota and mutable resource validation. Thus a lost acknowledgement can be recovered after a destination or definition is disabled, or the owner reaches the active-job quota. A fresh key must satisfy current admission rules. Parent lookup, child insert, planned outputs and audit inserts execute in one core transaction. `JOB_ACCEPTED` and `JOB_REPROCESSED` audit events reference the new job, whose persisted parent link supplies lineage. Restart recovery retains the child ID and therefore restarts that child's Batch instance; it never turns recovery into a further user-requested reprocessing job.

Current idempotency records last as long as their jobs; no expiry/retention policy has been implemented. The browser keeps its action key while mounted and reuses it after timeout/network failure. After a reload the owner should inspect linked executions before intentionally starting another action; browser key persistence across reloads is not claimed.

## Persistence and UI

The existing parent_job_id foreign key stores lineage. V14 adds a partial owner/parent/time index and rejects self-parent links. A bounded owner-authorized `GET /jobs/{id}/reprocessings?page=N` returns 20 children at a time, newest first. Job details expose parent_job_id and deadline, with a parent link, reprocess action, loading/error/retry states and paginated linked children. History uses explicit refresh; job execution monitoring retains its normal polling behavior. Job-monitor components remount by job ID to prevent parent state leaking into client navigation to a child.

V14 is additive, requires no Go migration, and does not rewrite previous migration checksums. The new core must precede the new frontend. An old core can read the schema but cannot expose the reprocessing operation; hide the UI if rolling back. Full restoration and rollback exercises remain Phase 5 work.

## Verification

PostgreSQL integration tests cover concurrent duplicate requests, cross-operation and cross-parent conflicts, owner/CSRF checks, active-state rejection, unknown body rejection, quota recovery, history pagination, fresh resource resolution, retirement, new deadlines and real Batch instance/PDF content. Browser verification deliberately drops the first successful acknowledgement, retries with the same key, and checks a single linked execution, a distinct artifact and object key, verified emulator delivery, preserved parent bytes and accessible navigation. Real AWS/GCP behavior and production load are outside this increment's evidence.
