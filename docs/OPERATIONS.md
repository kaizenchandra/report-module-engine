# Local operations and release gates

## Configuration and ownership

The root `.env.local` is ignored and contains random local credentials. `scripts/local-env.sh` creates it only when absent. Existing environments need newly introduced variables added explicitly; never overwrite user values. `.env.example` lists names without secrets. Java owns `report_core` and Flyway's V1–V5 plus Batch tables. Go owns the separate `delivery` database and its checksummed migration. Neither has credentials for the other's database. Development database users own their databases; production must separate migration ownership from restricted runtime grants and revoke DDL/TRUNCATE/audit mutation privileges.

Core uses DATABASE_URL/USER/PASSWORD, OIDC_ISSUER/CLIENT_SECRET, APP_ORIGIN, COOKIE_SECURE, PORT and ARTIFACT_ROOT. Go uses DELIVERY_DATABASE_URL, CORE_URL, DELIVERY_TOKEN_URL, DELIVERY_CLIENT_SECRET, S3_BUCKET, AWS_REGION, AWS credential chain, optional S3_ENDPOINT, GCS_BUCKET and Google Application Default Credentials. Local script sets STORAGE_EMULATOR_HOST only for the emulator. Production must unset emulator endpoints and use workload identities instead of static keys. Registry bucket snapshots must match Go's deployment allowlist; mismatch fails closed.

Local UI: http://localhost:3005. Core: http://localhost:8085/backend. Identity: http://localhost:8180. Delivery health: http://localhost:8090/health/live. S3Mock: localhost:9000. GCS emulator: localhost:4443. Emulators intentionally do not enforce production cloud authorization; all exposed emulator/database ports bind to loopback. Keycloak start-dev stores synthetic identity data ephemerally; recreating it invalidates sessions and may assign new subject IDs. Do not recreate it to simulate a mere application worker restart.

## Artifact handling

Core persists PDF bytes under ARTIFACT_ROOT with directory permissions 0700 and private temporary files, flushes bytes and atomically renames before metadata/outbox commit. Each render fence has a different filename. Mount a persistent volume at `/artifacts` when using the core container. Go transfers through an authenticated stream and private bounded temporary file, never a shared path. Both provider adapters verify stored bytes and refuse overwrites. S3 requests AES256 server-side encryption; emulator behavior does not prove encryption. GCS uses provider-managed encryption defaults. Customer-managed keys are not exposed yet.

Retained outputs currently remain indefinitely. Automatic deletion, retention holds, cloud lifecycle coordination and crash-orphan cleanup are **not implemented**. Operators must monitor disk usage and must not delete files associated with nonterminal jobs or pending deliveries. Retention enforcement is a Phase 4 release gate. No seven-day cleanup promise is made. Core volume loss can cause 410 and delivery failure. Durable volume backup and restore must be coordinated with PostgreSQL snapshots before production.

## Failure diagnosis

- 401: expired/absent session or invalid service issuer/audience. Sign in again or check service identity configuration. Never log tokens.
- 403: missing role or CSRF token. Browser mutations require the synchronizer token from `/api/v1/me`.
- 409 submission: same subject/key with different request; retry the original payload or use a new key for new intent.
- 429: subject has 20 active jobs. Wait for completion/cancellation.
- RENDER_FAILED/BATCH_LAUNCH_FAILED: inspect protected operator logs and execution identifiers. No public stack traces.
- DELIVERY FAILED: receipt code reports integrity/conflict/exhausted upload. Do not overwrite a conflicting cloud object automatically.
- Core unavailable: accepted work remains in PostgreSQL; queue polling resumes when dependencies return.
- Worker interruption: core uses one advisory-lock renderer across instances, persisted fences and bounded restart attempts. Go uses one advisory-lock worker and a persisted upload budget. These are bounded single-worker implementations, not a claim of horizontally scalable fair scheduling.

Cancel is cooperative. It does not delete completed uploads. Upload may win a race with cancellation; the domain remains cancelled and no deletion is implied. A 15-minute job deadline prevents starting expired work; expiry also stops pending delivery dispatch. Rendering can finish before its deadline is checked; arbitrary untrusted or expensive templates remain unsupported.

## Startup and shutdown

Wait for the OIDC discovery endpoint before launching Java. For repeat builds, stop the application or copy the packaged JAR to a separate runtime path before starting it. Do not overwrite a running archive. SIGTERM triggers graceful HTTP shutdown; Go cancels worker I/O and waits for worker exit. Retain Compose named volumes. Never use `down -v` as routine shutdown.

## Migration / rollback

Flyway validates immutable migration checksums. Go validates its applied migration hash. Never edit an applied migration. Back up both databases plus retained core artifacts before upgrade; apply additive migrations first. Application rollback is allowed only when the earlier binary tolerates the current schema and payload contract. Destination snapshots and epoch filenames introduced in V4/V5 require the corresponding core/worker versions. Do not roll back binaries across these changes without a tested data migration. Current fixtures use Jasper v7 syntax; conversion of v6 templates is not automatic.

## Production criteria still open

Business SLO approval, measured throughput/latency, real cloud IAM/KMS/network tests, durable multi-instance staging, multi-tenant fairness, request-rate limits, isolated execution for untrusted templates, OIDC provider logout policy, persisted sessions, security/container scans, accessibility audit, secrets rotation, backup/restore exercise, RTO/RPO measurement, dashboards/alerts, retention holds/deletion, and rollout/rollback exercise. No SLA or production-readiness claim is made.

## Destination and upload maintenance

REPORT_ADMIN can register and edit references under Cloud destinations. Buckets must match the core and Go `S3_BUCKET` / `GCS_BUCKET` deployment allowlists. Configure the same values in both services. Accepted jobs retain their provider, bucket, prefix and resource version; edits only affect later submissions. Disable a destination instead of deleting history. PUT requires the last observed expectedVersion and returns 409 on concurrent edits. The legacy enable/disable PATCH is an explicit last-writer action and also records a new version. History starts with the version present when V6 is applied; it cannot reconstruct earlier edits.

Go chooses adapters by provider and checks the immutable bucket before every pending task. Changing an allowed bucket can leave old pending tasks unable to deliver; drain them before deployment changes. It never silently sends their data to the newly configured bucket. Failure remains visible in worker diagnostics until recovery or the core job deadline.

Optional `DELIVERY_SCRATCH_ROOT` defaults to a `report-engine-delivery` directory under the operating system temp root. It must be a real private 0700 directory, not a symlink. The worker removes only its matching regular upload files older than 24 hours at startup and hourly. Uploads have a 60-second operation deadline. This is crash scratch cleanup, not report retention: referenced core artifacts are still retained, and core orphan cleanup/holds/cloud lifecycle administration are pending.

Delivery attempt projections can lag while core is unavailable. The Go database remains authoritative for delivery attempts; core acknowledges the full snapshot before accepting the terminal receipt. Back up both databases independently with coordinated recovery procedures (restoration verification remains Phase 5).

## Checks, temporary links and signing identities

Use **Check bucket access** as REPORT_ADMIN to test metadata access for the displayed destination version. This does not test uploads/encryption and does not automatically enable a destination. Requests persist and return their status through the owner-scoped cloud-operation API. A recent unexpired result can be reused for five minutes; editing the destination creates a separate check identity.

Job owners can request a temporary cloud link only after delivery verification. The platform fixes expiry at acceptance (maximum five minutes); queueing and retries reduce the remaining lifetime. AWS signing uses the delivery workload's credentials. GCS requires `GCS_SIGNING_SERVICE_ACCOUNT`, Application Default Credentials with `iam.serviceAccounts.signBlob` on that signer, the IAM Credentials API enabled, and object-read permission for the signer identity. No service-account private key is required. Configure `S3_BUCKET` / `GCS_BUCKET` identically in core and Go. Metadata checks require additional bucket metadata/list permission; their failure does not prove an otherwise valid write identity cannot upload.

Keep core and delivery clocks synchronized. Signed credentials can expire earlier than the displayed link deadline. Use S3 bucket versioning when links must pin a specific immutable version; GCS links are generation-bound. Real-cloud IAM/encryption/signature tests still require dedicated test buckets and authorized identities.

Generated local settings enable `REPORT_ALLOW_EMULATOR_LINKS=true`; production defaults to false. The interface labels emulator checks/links and does not claim cloud enforcement. `GCS_SIGNING_SERVICE_ACCOUNT` is rejected at startup when `STORAGE_EMULATOR_HOST` is configured. Avoid logging URLs or capturing browser traces/screenshots containing links. The test configuration disables those artifacts.

Core staging sweep runs hourly after a one-minute startup delay, under the renderer's connection lock. It deletes only matching regular unreferenced files older than 24 hours and preserves active jobs, referenced artifacts and symlinks. Its scan cost grows with directory size; production-scale cleanup throughput still requires Phase 5 benchmarks. This does not implement retention holds or delete delivered cloud objects.

## Local operator fixture and upgrade

`operator` has REPORT_ADMIN and REPORT_USER and uses the generated `REPORT_ADMIN_TEST_PASSWORD`. `reporter` remains REPORT_USER. Both are synthetic loopback fixtures, never production identities. Re-running `scripts/local-env.sh` preserves existing values and adds missing local settings. Existing Keycloak containers do not import additions into an already present realm: fixture refresh requires a planned local container recreation. Preserve the reporter subject ID first if existing local job ownership must remain accessible; this workspace's migration did so. Do not apply the local realm import to a production identity service.

Worker ownership locks are scoped to the PostgreSQL database/schema holding their records, allowing isolated test schemas to coexist with the running local worker. Delivery tokens rejected with 401 are refreshed on the next request; mutating calls are not blindly replayed inside the HTTP client.

## Multiple-output rollout

Core V11 is a forward migration with backfills for existing artifacts, deliveries and output states. Stop admission/rendering and coordinate Go/core replacement before enabling configuration v2; older workers assume job, artifact and delivery identifiers match. See [ADR-003](adr/003-multiple-output-outcomes.md). A rollback to old binaries requires a coordinated restore of both databases and staging, or a forward compatibility fix. Do not downgrade only one service against v2 work. No backup/restore exercise has yet been performed.

For partial outcomes, inspect `outputs` and `deliveries` separately. Failed exports have no cloud task; their requested mappings are retained in resolved configuration. `OPTIONAL_WORK_FAILED` preserves required success and available artifacts. `REQUIRED_WORK_FAILED` identifies a failed aggregate; individual receipts/output codes show the cause. Use the artifact-ID download and delivery-ID signing endpoints for multi-output jobs. Notification failures are not part of aggregation; notifications remain unavailable.

## Static HTML and DOCX formats

V12 adds `.docx` and `.html.zip` staging constraints. Upgrade core and Go before accepting these formats; old Go versions reject their object keys. No Go database migration is required and persisted payload fingerprints remain compatible. HTML ZIPs contain generated files only and must be downloaded/extracted outside the application origin. Do not configure a reverse proxy to expose staging files or extracted HTML as application static assets. Browser tests verify attachment MIME/disposition, content escaping and object integrity; Word desktop layout fidelity remains unverified. See ADR-004 and `examples/request-all-formats.json`.

## JSON/YAML admission

Deploy the new core before the YAML-enabled frontend; the latter requires `/configurations/convert` for normalization. No schema or Go rollout is needed. Both media types use the same durable acceptance transaction and fingerprint. Strict scalar checks reject formerly coerced out-of-schema values; send a quoted YAML schemaVersion, integer definitionVersion and real booleans. Conversion has no resource-resolution guarantee. Invalid input errors intentionally omit source lines and values; operators should use correlation IDs without copying sensitive parameters into logs. See ADR-005 for limits and lost-acknowledgement semantics.

## Definition version rollout

Deploy core V13 before the definition-admin frontend. Catalog → Details and version history exposes immutable revisions; an operator can register definitions and publish metadata revisions against the bundled trusted template. Publishing requires the version currently displayed; 409 means reload before editing again. Disabling acceptance blocks new requests for every version of that definition, while accepted jobs and equivalent idempotency retries remain valid. Re-enabling creates another version; versions recorded as disabled cannot themselves be submitted. Do not edit history directly. The current single-workspace policy permits all reporting users to view definitions, with owner-only jobs. No custom JRXML upload or configurable source is enabled by this feature. See [ADR-006](adr/006-definition-versions.md).

## Reprocessing terminal jobs

Owners can use **Reprocess as new job** from a terminal job's detail page. This generates all outputs again with a new deadline and new cloud object keys; the original results remain available. The exact definition version must still be enabled, and registered destinations use their current settings at new acceptance. Review those settings before reprocessing when destinations have changed. This action does not resume an old Batch execution or retry an upload in place.

On an acknowledgement timeout, retry from the same page to reuse its idempotency key, or inspect **Linked reprocessing executions**. After reloading, inspect that list before starting another action. The public API supports deterministic recovery by reusing the original key and parent URL. A key reused for another parent or plain submission returns 409. A full active-job quota does not prevent recovery of an already accepted reprocessing request. Deploy V14/core before enabling the frontend action; no Go deployment change is needed. See [ADR-007](adr/007-linked-reprocessing.md).

## Trusted JRXML registration

`REPORT_TRUSTED_TEMPLATE_UPLOADS_ENABLED` defaults to false. Set it true only when **all REPORT_ADMIN identities are trusted to execute Java code with the core service's privileges**. Generated loopback configuration enables the feature for the synthetic operator fixture. Preserve an explicit false setting when refreshing local environment files. Uploaded JRXML is executable code; XML checks and application permissions are not an isolation boundary.

Templates → Register trusted JRXML validates/compiles a standalone JasperReports 7 file and returns its immutable `sha256:` reference. The bundled example documents the currently supported TITLE parameter and sample-v1 fields. Unsupported images, subreports, queries, bundles, property overrides and custom fonts are rejected. Revised source creates a new fingerprint; publish a new definition version to use it. Existing accepted jobs retain their original source. Identical-source retries return the first registered metadata, so a lost registration acknowledgement need not create duplicates.

V15 source rows belong in core PostgreSQL backups. Compiled cache entries are disposable; workers recompile retained source on cache miss. Registration can return 429 when the local compiler slot is occupied. An HTTP timeout does not forcibly interrupt a compiler or trusted expression; hard execution isolation/limits remain a production gap. The upload switch controls registration only; disabling it preserves existing template execution. No Go rollout is required. See [ADR-008](adr/008-trusted-template-registration.md).

## Structured source registration and upgrade

Administrators can register small immutable JSON datasets from **Data sources**, then bind the returned `data:` fingerprint to a definition. Keep uploads within 500 rows and 32 KiB canonical rows (64 KiB request). Empty, malformed and coercible values fail registration rather than being skipped. Amounts normalize to two decimals; equivalent rows deduplicate regardless of JSON field order. Changed rows create a new reference and require publishing a new definition version. Accepted jobs retain their old data.

V16 requires a coordinated core upgrade: stop old renderer instances before accepting registered-source jobs. Old binaries render sample rows instead of interpreting the new data binding, so mixed old/new core workers or a downgrade with pending registered-source work are unsafe. Upgrade all core workers, then frontend, then admit traffic. Go has no migration or code change. Back up structured_source with core PostgreSQL; do not delete immutable rows referenced by job/definition history. General retention, tenant isolation, JDBC credentials/connections and large datasets remain unsupported. See [ADR-009](adr/009-structured-data-snapshots.md).

## Report locale and time zone

The submission form and JSON/YAML parameters support locale en-US/de-DE/fr-FR and timeZone UTC/Asia/Kolkata/America/New_York/Europe/Berlin. Defaults remain en-US/UTC and old idempotency fingerprints are preserved when settings are omitted. The accepted configuration shows the resolved values; reprocessing retains the request settings. Unsupported values are rejected.

Settings affect format-aware Jasper fields, not static captions, currency units or hard-coded expressions. The bundled sample has a static USD/UTC caption and no date field; use a trusted date-aware template for zone-specific presentation. Operations timestamps remain UTC. Upgrade all core workers before admitting these request fields; no database or Go rollout is needed. See [ADR-010](adr/010-locale-time-zone-settings.md).
