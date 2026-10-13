# Enterprise Report Engine

Implementation in phases. Read [phase status](docs/PHASE_STATUS.md) for verified capabilities and remaining work. This is a local development implementation, not a production release.

## Start locally

Requirements: Java 21, Node 24, Go >=1.26, Docker Compose. The supplied wrapper pins Maven 3.9.11. The root directory is the existing Next.js application. No paid resources are created.

```sh
./scripts/local-env.sh
docker compose --env-file .env.local -f infra/compose.yml up -d
npm ci
```

Wait for `http://localhost:8180/realms/reporting/.well-known/openid-configuration` to respond, then run each application in a separate terminal:

```sh
./scripts/core.sh
```

```sh
npm run dev
```

```sh
./scripts/delivery.sh
```

Open http://localhost:3005 and sign in as `reporter` using `REPORT_TEST_PASSWORD` from your ignored `.env.local`. The password is random local test data. Do not copy it into logs, screenshots, source, or tickets. The browser uses a Spring-managed session; no tokens enter browser JavaScript. HTTP and insecure cookies are explicit loopback-only development exceptions.

## Verify

```sh
npm run lint && npm run build
(cd services/report-core && ./mvnw verify)
(cd services/delivery-go && go test -race ./... && go vet ./...)
npx playwright install chromium
set -a; . ./.env.local; set +a
npx playwright test
```

On macOS explicitly select Java 21 for Maven: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.

Provider and durable recovery tests: `./scripts/delivery-tests.sh` (requires running local dependencies). These use S3Mock and fake-gcs-server, not actual AWS/GCP.

Health: core `/backend/actuator/health`; delivery `http://localhost:8090/health/live`. Stop dependencies with `docker compose --env-file .env.local -f infra/compose.yml stop`. Preserve volumes to retain accepted jobs. `down -v` destroys local state and must be an explicit operator choice.

## Repository

- `app`, `lib`: Next.js server-rendered UI and transport client; preserved starter layout.
- `services/report-core`: Java domain service; Flyway owns core and Batch migrations.
- `services/delivery-go`: Go delivery process; independent database ownership.
- `contracts`, `examples`: versioned requests and deterministic report fixtures.
- `infra`, `scripts`: local infrastructure, container recipes and startup helpers.
- `tests/e2e`: real browser workflows.
- `docs`: [requirements](docs/REQUIREMENTS.md), [architecture](docs/adr/001-service-and-security-boundaries.md), [dependency decisions](docs/DEPENDENCIES.md).

Dockerfiles are deployment build assets; image builds, production rollout, TLS, workload identities, backup/restore and vulnerability gates must be verified before release. No production deployment is included.

Current verified local scope: OIDC user/operator sessions; durable PDF, XLSX, CSV, DOCX and packaged HTML jobs; Spring Batch rendering/recovery; Go delivery to both emulators; destination checks; bounded signing; process-exit recovery; authenticated output retrieval. See [verification evidence](docs/VERIFICATION.md), [format support](docs/FORMAT_SUPPORT.md) and [operational limits](docs/OPERATIONS.md). Phase 2 is locally verified. Phase 3 now includes version 2 multi-output/destination mappings and required/optional aggregation; Phases 4–5 have not started.

Destination administration now supports REPORT_ADMIN registration and versioned configuration edits within deployment-controlled bucket allowlists. Users can inspect history and select enabled references during submission. Accepted jobs retain their original destination snapshot. Job details show durable delivery-attempt evidence; see [the internal delivery contract](contracts/INTERNAL_DELIVERY.md) and [operations guide](docs/OPERATIONS.md). The seeded `reporter` deliberately has no administrator permission. The local `operator` fixture uses the generated `REPORT_ADMIN_TEST_PASSWORD`; never use these development identities in production. GCS signing requires a configured cloud IAM signer and explicitly reports unavailable on the emulator.

Declarative submission supports UTF-8 JSON and restricted YAML with the same versioned contract. In **New report**, choose **Edit JSON / YAML** and select the syntax. Examples: [single-output YAML](examples/request.yaml), [five-format YAML](examples/request-all-formats.yaml). Conversion validates structure without creating a job; resource versions resolve at acceptance. See [parser and idempotency policy](docs/adr/005-declarative-yaml.md).

## Definition administration

Sign in as the local `operator` with `REPORT_ADMIN_TEST_PASSWORD` to register a definition from the report catalog, publish metadata revisions, or disable new submissions. Details expose immutable version history and exact-version generation links. Definitions use the bundled sample or an immutable registered trusted JRXML reference with the bundled sample or registered structured rows. A deployment-owned PostgreSQL adapter can import immutable snapshots; live JDBC execution and template dependency bundles are still pending. See [definition version semantics](docs/adr/006-definition-versions.md).

Owners can use **Reprocess as new job** on terminal executions. It creates a linked execution, revalidates current resources, and regenerates outputs without altering the parent results. Reuse the same idempotency key to recover a lost API acknowledgement; the browser does this while the action page remains mounted. See [reprocessing semantics](docs/adr/007-linked-reprocessing.md).

The Templates screen supports standalone JRXML registration for trusted administrators when `REPORT_TRUSTED_TEMPLATE_UPLOADS_ENABLED=true` (generated local fixture only; default false). Uploads execute trusted Java expressions and are not sandboxed. Source is retained by SHA-256 in PostgreSQL and selected by immutable definition versions. See [trusted-template policy and limits](docs/adr/008-trusted-template-registration.md).

Administrators can register bounded JSON datasets from **Data sources** and bind immutable `data:` references to definition versions. Sources support typed product/quantity/amount rows; empty or malformed data is rejected. JDBC connections and arbitrary field schemas remain open. See [structured source semantics and upgrade requirements](docs/adr/009-structured-data-snapshots.md).

Report requests also support bounded locale/time-zone settings through the form or JSON/YAML; see [localized example](examples/request-localized.json) and [formatting semantics](docs/adr/010-locale-time-zone-settings.md). Defaults remain en-US/UTC, and static template labels are unchanged.

Job history supports exact definition/status filters, UTC date intervals and newest/oldest ordering. Filters survive URL sharing and pagination. See [history semantics and migration guidance](docs/OPERATIONS.md#job-history-filtering).

Data sources now includes a PostgreSQL connection check and bounded snapshot import for administrators. The local fixture provides synthetic `stationery` and `equipment` categories. No SQL or credentials enter browser code. See [JDBC scope and security](docs/adr/011-jdbc-snapshot-adapter.md).

The Report catalog now searches current definition IDs, names and descriptions, with enabled/disabled filters, stable sorting and shareable pages. Template/source selections survive catalog navigation. The existing definition-list API remains compatible; see [catalog semantics](docs/OPERATIONS.md#definition-catalog-search).

Job details can preview retained PDF pages through owner-authorized PNG endpoints. Preview uses the normal generated artifact, checks its integrity and does not create another execution or upload. See [preview limits](docs/adr/012-retained-pdf-preview.md).
