# ADR 002 — Durable cloud controls and format compatibility

Status: accepted for the local Phase 2/3 increment.

Java remains the public authorization and domain boundary. Destination checks and signed-link requests are recorded in a Java-owned outbox; Go consumes them over its existing OAuth-authenticated polling channel. Go owns a checksummed inbox, bounded provider attempts and durable result replay. This avoids introducing a broker or another inbound service authentication channel. No service writes another service's tables.

Controls are read-only provider operations but their outcomes still require durable replay. A result is persisted before acknowledgement; retries preserve the original expiry. Core validates returned results and authorizes every public operation lookup by owner. Bucket checks mean metadata reachability only. A signing failure does not modify a successfully delivered job.

URLs are short-lived bearer capabilities, never audit/log content. Core fixes a five-minute maximum at acceptance; queue time consumes that window. AWS uses SDK SigV4 and GCS uses IAM-backed V4. Only the Go workload sees cloud identities. Local emulator mode is explicit and cannot establish provider expiry enforcement. S3 versioning is required for strong version-pinned links; GCS generation is always pinned.

For the first Phase 3 increment, exactly one PDF/XLSX/CSV output is accepted per job. This preserves the existing job/artifact/outbox identity relation while extending export functionality. Multi-output aggregation requires its own migration and remains open. The generated key extension discriminates the format in the v1 delivery contract, preserving existing canonical PDF payload hashes. Both the application and database constrain extensions.

The existing Batch JobInstance name is retained for restart compatibility; the immutable request selects the exporter. Upgrades involving non-PDF requests require coordinated worker replacement, not mixed old/new binaries. V10 fixes the original staging constraint through a forward migration after integrated browser verification exposed it. Rollback guidance requires draining non-PDF work rather than letting old renderers misinterpret it.
