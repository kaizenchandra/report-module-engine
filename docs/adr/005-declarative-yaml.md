# ADR-005: Restricted YAML and a shared declarative boundary

Accepted for implementation, 2026-10-12. Scope: CFG-01, JOB-01, API-01, SEC-01, UI-02.

`POST /api/v1/jobs` accepts `application/json` and `application/yaml`, both as UTF-8 and both bound to the existing versioned ReportRequest. `application/yaml` follows [RFC 9512](https://www.rfc-editor.org/rfc/rfc9512.html); deprecated YAML MIME aliases and arbitrary `+yaml` types are not enabled. Version identifiers, definition IDs and references remain strings. Quote `schemaVersion: '1'` or `'2'` in YAML. No orchestration, persistence or Go payload shape changes are required.

SnakeYAML **2.4** was already pinned by Spring Boot **3.5.16** dependency management. It is now an explicit 2.4 dependency. LoaderOptions and parser APIs were inspected in the installed JAR and checked against [upstream source](https://github.com/snakeyaml/snakeyaml/blob/master/src/main/java/org/yaml/snakeyaml/LoaderOptions.java). Java 21 remains the baseline. There is no new browser dependency or JavaScript YAML parser. This compatibility verification is not a vulnerability scan or a claim that no newer patch exists.

Core parses YAML events and composes nodes, then explicitly builds ordinary maps, lists and scalar values. It never constructs user-named Java objects. Before composition it rejects aliases, anchors and explicit tags, including standard tags, and limits event count and depth. Maps require string keys; duplicate and merge keys are rejected during traversal. No includes, URL fetches, environment expansion or secret substitution occur. This is an intentionally restricted configuration representation, not a general-purpose YAML processor.

Scalar resolution follows JSON-like forms: lowercase `true`/`false`, decimal integers/floats and null (`null`, `~`, or an empty value). Unquoted dates, yes/no, on/off and leading-zero numbers remain strings. Quoted values stay strings. Non-finite numeric notation is not accepted as a numeric value. The DTO and bean validator still determine field types and valid values; scalar coercions are disabled, including Jackson's separate numeric/boolean-to-string coercion rules.

Both encodings reject unknown/ignored fields, duplicate keys, trailing documents/values and wrong scalar types. The parser checks version-specific field presence before DTO binding: explicit null cannot smuggle a v2 property into v1 or `destinationRef` into v2. JSON Schema describes the version variants; Java adds mapping/resource rules. Previously tolerated out-of-schema coercions are now rejected. Valid v1 requests retain their existing canonical DTO serialization and idempotency fingerprint.

| Bound                          | Enforcement                                                             |
| ------------------------------ | ----------------------------------------------------------------------- |
| 64 KiB UTF-8 body              | API POST/PATCH/PUT filter; defensive codec check                        |
| 16 collection nesting levels   | JSON stream constraints; YAML event/composition/node traversal          |
| 4,096 scalar/string characters | YAML scalar events; JSON string stream constraints                      |
| 128 numeric characters         | YAML numeric conversion; JSON number stream constraints                 |
| 2,048 YAML parser events       | Before node composition                                                 |
| One document/object            | JSON trailing-token rejection; YAML document counter and root-map check |

Parser errors return a fixed ProblemDetail without source lines, offending values or parser exception text. Bean validation returns field paths and fixed constraint messages. Conversion and submission use the same codec and validator. Normalization does not resolve resource versions; destination/version resolution still occurs atomically at durable acceptance.

`POST /api/v1/configurations/convert?target=json|yaml` accepts either encoding and returns `{configuration, mediaType, request}` with `Cache-Control: no-store`. It requires the same user/service authorization and session CSRF as job submission. It validates syntax and the DTO, but creates no job, artifact or audit resource and does not assert that a destination exists. Conversion discards comments and formatting; it does not add unrequested policy defaults.

The frontend uses this endpoint to switch between the form, JSON and YAML. It keeps malformed drafts intact, displays backend validation errors and disables editing during conversion/submission. Submission first normalizes the typed request, then sends the original selected encoding to the durable API. The mounted form keeps one idempotency key per canonical typed request, so whitespace, comments, property order and encoding changes do not create another job after a lost acknowledgement. Array ordering and explicit default/omission differences are not normalized away. Reloading the page still loses the in-memory key; API consumers must durably retain their own key for cross-session recovery. Conversion timeout is 10 seconds and submission timeout is 15 seconds; a timeout is never treated as proof that acceptance failed.

Tests cover malicious YAML features, duplicate keys, scalar coercion, excessive bounds, UTF-8 errors, exact DTO round-trips, version-specific null bypasses, CSRF, no durable conversion side effects, same-key JSON/YAML acceptance and conflicting requests. Browser tests exercise real CSV generation and cloud integrity after YAML input, plus an actual accepted HTTP response deliberately withheld from the browser followed by equivalent YAML retry. This is lost-acknowledgement testing, not a storage-loss or network-partition certification.
