# ADR-006: Immutable definition versions and admission retirement

Status: Accepted for the current Phase 3 increment.

## Decision

Java owns definition registration, version publication and admission policy. PostgreSQL migration V13 adds append-only definition history and an enabled flag to the current definition head. Each successful administrative update must increment the version by one. A database trigger records history in the same transaction; another rejects history updates/deletes. An optimistic expectedVersion prevents lost updates. Registration conflicts return 409. Mutations and their audit records commit together and require REPORT_ADMIN plus CSRF protection.

An enabled definition admits requests only for an explicitly named, enabled historical version. There is no implicit latest resolution. Acceptance takes a shared lock on the head, checks the historical version, and copies its name, description, version and template reference into the immutable resolved job configuration alongside the existing template SHA-256, dataset, destination snapshots and locale. Retirement updates the head under a conflicting row lock, giving submission versus retirement a transaction boundary. Retirement blocks all new submissions, including old versions; it does not cancel accepted jobs or remove delivered artifacts.

Idempotency lookup precedes current-resource validation. An equivalent retry after retirement still recovers its accepted job. A different request using the same key still conflicts. The request fingerprint serialization is unchanged. Batch renders using the acceptance snapshot and fingerprint without re-reading mutable definition metadata. Changes to definition names/descriptions cannot alter an accepted job.

## Current scope and trust boundary

Only the bundled `templates/sales-summary.jrxml` reference and synthetic `sample-v1` dataset are supported. The backend allowlists that exact template reference. Definition registration is metadata administration, not a JRXML upload API, template execution sandbox or configurable data-source feature. Every version currently has the same title parameter, locale/time zone and export capabilities. The existing compiled-template cache and hash check still apply to the bundled resource. Deployment must retain its expected contents to render outstanding jobs; arbitrary template revisions and durable dependency bundles are still open Phase 3 work.

This is a single workspace: all reporting users can read definitions and submit enabled versions; administrators can publish/disable them. Job ownership remains subject-scoped. No tenant isolation or per-definition ACL is claimed.

## UI and contracts

The catalog links to definition detail/history and an exact-version submission URL. Administrators register definitions, publish metadata revisions, and enable/disable admission. Historical versions have generation links only while both the head and version are enabled. The submission form and JSON/YAML editor use the same definitionId/definitionVersion fields and backend validation. An unavailable pinned URL does not silently submit a different definition. Version history uses pages of 20. The catalog retains its existing list response; general catalog pagination/filtering remains open.

## Verification and rollout

Run Maven verify with Java 21 and PostgreSQL Testcontainers; run the Playwright suite against the local OIDC/PostgreSQL/core/Go/Next stack. Coverage includes authorization, CSRF, arbitrary template-reference rejection, absent versions, stale edits, history immutability, idempotency after retirement, accepted-job rendering after retirement, PDF text, snapshot contents, UI version selection/editor round-trip and cloud receipt integrity. Real cloud verification remains separate.

V13 is additive and does not rewrite prior migrations or existing resolved job configurations. Back up before migrations. Roll back application code only to a version that tolerates the new history trigger: any direct definition update must increment its version. Do not drop the history table to undo an application rollback. Existing binaries that only read the original seed remain compatible; new definition IDs require the new validation code.
