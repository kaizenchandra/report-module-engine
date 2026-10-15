# ADR-016: Durable JDBC import acknowledgement recovery

Status: Accepted for a bounded Phase 3 increment. JDBC remains a snapshot import before submission, not live per-job execution.

## Request and response contract

Administrator `POST /api/v1/jdbc-source/imports` optionally accepts `Idempotency-Key`, with 8–128 ASCII letters, digits, underscores or hyphens. The key is scoped to the authenticated actor. A fixed ordered selector containing exact name, category, deployment profile ID and adapter version determines the request SHA-256. Neither current source rows nor deployment credentials enter that selector.

Same actor/key and selector recover the first committed snapshot and provenance import. A changed name/category conflicts with 409 before another JDBC read. A different actor or key starts another import. Requests without a key keep the earlier behavior: read current rows, deduplicate source content and append separate provenance on each call.

The response retains the source metadata fields and adds `importId`, the immutable provenance UUID. Status 201 means new structured content; 200 means existing content or recovered keyed result. `Idempotency-Replayed: true` identifies recovery; it is false for a new/unkeyed import. The original display name remains attached to deduplicated source content, as before.

## Transaction and concurrency semantics

A keyed request first checks the core ledger. An existing matching committed result returns without contacting JDBC, including when source data, permissions or availability have changed. Otherwise the deployment-owned adapter reads its bounded, read-only repeatable-read snapshot and closes that source connection before opening the core write transaction.

The write transaction acquires an actor/key advisory lock and checks the ledger again. If another request committed first, the losing read is discarded and the existing result returns. Otherwise the transaction registers/deduplicates immutable rows, appends import provenance and audit, and inserts the request-result ledger row. All these writes commit together. V21's primary key protects actor/key uniqueness, its import foreign key retains linkage, and its append-only trigger prevents changing/deleting recovery records.

Concurrent requests can perform separate source reads before a winner commits; the guarantee is one published import per actor/key, not exactly one remote query. A failed read or rolled-back core persistence operation leaves no accepted keyed result, so retry may read current source rows. A committed result survives a lost response and is recovered from the ledger. The source and core transactions remain independent; no distributed transaction, live refresh, automatic server retry or production end-to-end latency guarantee is introduced.

## UI, retention and rollout

The snapshot form creates one key for its exact name/category and reuses it after an interrupted acknowledgement while the form stays mounted. Connection checks do not consume or replace that pending key. Changing fields or reloading starts a new import. The browser does not persist keys across reloads; API callers can retain their own keys for later recovery. Normal job-submission idempotency and immutable snapshot rendering remain unchanged.

V21 adds core-owned `jdbc_import_request` without changing earlier migrations. Preserve it with source/import/audit data for recovery; keys have no automatic expiry in this increment. Deploy capable core workers before the frontend. An older core ignores this new header and can create another import, so mixed old/new import workers cannot provide the keyed guarantee. Preserve all committed records when rolling back.

Existing administrator permission, CSRF, source profile/row/byte limits and redacted errors remain in force. This is actor-scoped command recovery, not tenant/source ACL enforcement. Retention/holds and production restoration/partition exercises remain later governance/release work. No dependency or Go delivery change is required.
