# Dependency decisions

- Java 21 baseline, locally 21.0.12.1. Maven's host default is Java 27; scripts explicitly select 21. Compiler release is 21.
- Spring Boot **3.5.16**, pinned parent/BOM. [Official requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html) support Java 17–25. Avoid Boot 4 migration complexity for the first Batch 5 integration. Security/Batch/JDBC versions come from this one BOM. Maven Central artifact existence verified before build.
- JasperReports **7.0.3**, core and PDF modules pinned identically. [Official documentation](https://jasperreports.sourceforge.net/README.html) documents v7's incompatible template serialization and split exporters. Only matching v7 fixtures are accepted. No automatic acceptance of v6 JRXML.
- Existing Next.js **16.4.0**, React/React DOM **19.3.0** preserved; local bundled Next guides for page, route and authentication read before changes. TypeScript strict mode retained. package-lock.json pins the full npm graph.
- Go module language **1.26.0**, local compiler **1.27.2**; [Go release history](https://go.dev/doc/devel/release). No third-party Go dependency in Phase 0.
- PostgreSQL container **17.6**, Keycloak **26.7.3**; local-only infrastructure. [Keycloak downloads](https://www.keycloak.org/downloads.html). Production image digests, patch freshness and vulnerability scanning remain release gates.
- Playwright **1.58.2**, PDFBox **3.0.5** for verification.

2026-10-11 npm audit: five high findings in the inherited development ESLint chain (braces, micromatch, fast-glob, @next/eslint-plugin-next, eslint-config-next). Automated remediation proposes an incompatible major downgrade; unresolved release gate. This is not evidence of a clean dependency scan. Java/container scanning remains pending.

## Implemented delivery and verification dependencies

- AWS SDK Go v2 S3 **1.114.3**, config **1.32.10**; GCS storage **1.69.0**; pgx **5.11.0**. Selected module versions were resolved before implementation; `go.mod` and `go.sum` pin the full graph. [Official AWS examples](https://docs.aws.amazon.com/sdk-for-go/v2/developer-guide/go_s3_code_examples.html) and [GCS precondition guidance](https://docs.cloud.google.com/storage/docs/samples/storage-upload-file) informed the adapters.
- S3Mock **4.10.0**, fake-gcs-server **1.54.0** are explicitly local test emulators. Their provider contracts passed; this does not establish cloud IAM, encryption or billing behavior. [S3Mock documentation](https://github.com/adobe/S3Mock), [GCS emulator documentation](https://github.com/fsouza/fake-gcs-server).
- Jasper's **7.0.3 JDT module** is also pinned: needed for compilation inside a Spring Boot executable JAR.
- Maven wrapper **3.3.4** pins Maven **3.9.11** and was used successfully for the final Java test run.
- OpenAPI TypeScript **7.10.1**, Prettier **3.6.2**, axe-core Playwright **4.11.0** are pinned development tools. Generated API type drift is checked in CI.

## Cloud controls and format increment

GCS IAM **1.12.0**, already resolved transitively, is now a direct pinned dependency for context-bounded IAM Credentials SignBlob calls. [Official signing requirements](https://docs.cloud.google.com/storage/docs/samples/storage-generate-signed-url-v4) inform the explicit service-account configuration. Actual IAM signing remains unverified without cloud credentials. XLSX and CSV exporters were confirmed in the installed Jasper **7.0.3 core** JAR; no POI or additional exporter dependency was required. Version-specific output and formula-handling tests pass; see FORMAT_SUPPORT.md.

2026-10-12 DOCX/HTML increment: no dependency changes. Inspected Jasper 7.0.3 core bytecode signatures for JRDocxExporter, HtmlExporter, HtmlResourceHandler and configuration classes; verified actual exports on Java 21. Official API references and compatibility caveats are recorded in ADR-004.

## YAML boundary

SnakeYAML **2.4**, already managed/resolved by Spring Boot 3.5.16, is now a direct exact-version dependency. The installed LoaderOptions/Yaml/event API was inspected and executable parser tests run on Java 21. No browser dependency was added. See [ADR-005](adr/005-declarative-yaml.md) for official references, scalar policy and resource bounds. Existing vulnerability/production release gates remain unchanged.

## Trusted-template registration

No dependency changes. Java 21 JAXP hardening and pinned JasperReports 7.0.3 compile/fill/export were exercised with retained registered source and a cold compiled cache. Official API/security references and the distinction between XML protections and executable-code trust are recorded in [ADR-008](adr/008-trusted-template-registration.md). Browser verification used the installed Playwright 1.58.2 CLI directly after the local `.bin` launcher was missing; no runner upgrade was adopted.
