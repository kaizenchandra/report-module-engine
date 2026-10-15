# ADR-022: Dataset-scoped summary subreports

Status: Accepted for a bounded Phase 3 increment. Arbitrary parameter/field mappings, child queries/connections, dataset runs and deeper graphs remain open.

## Binding contract

Trusted JRXML may place a literal asset subreport directly in a JasperReports 7 summary section, for example `<summary height="25"><element kind="subreport" x="0" y="0" width="515" height="25"><expression>"assets/subreport-row.jrxml"</expression></element></summary>`. Detail components retain the existing detail/band shape and current-row binding. Other locations, uploaded datasource/connection expressions and caller-declared internal parameters remain rejected.

A summary component receives the complete row collection supplied to its immediate parent report. At the root this is the accepted immutable dataset, including per-job JDBC captures; rendering introduces no new source query. A summary inside a row-scoped child sees that child's one row. A summary inside a dataset-scoped child sees that child's full collection. Each component receives a new JRMapCollectionDataSource; siblings cannot consume each other's cursor or inherit the parent's exhausted cursor. Summary-only parents need not declare sales fields. Detail parents must still declare the three supported fields.

The root renderer supplies an internal R_REPORT_ROWS parameter after CSV text normalization. Generated child parameter maps replace this value with the exact collection passed to that child, remove inherited compiled-subreport defaults and retain TITLE, matching typed parameters, locale and time zone. Uploaded code cannot declare R_REPORT_ROWS through the registration contract. CSV product/typed text protection therefore survives both binding modes without applying it twice. Empty collections never fall back to sample data; Jasper's normal no-data behavior still applies, and this increment does not add an empty-data layout policy.

## Bounds and identity

Existing complete closure, administrator acknowledgement, original-byte archives and immutable database storage remain unchanged. Both modes share at most four elements per document, depth two, and eight expanded subreport elements across the dependency graph. Cycles and excessive expansion are rejected on every path, including shared descendants. A summary never resets to the root dataset after entering a row component, preventing nested summaries from multiplying the entire dataset for every root row. Existing asset/source/request/compiled-cache limits still apply. No arbitrary query, collection expression, field renaming or independent child dataset is admitted.

Any bundle containing a summary subreport in the root or a descendant uses `template-dataset-subreport-assets-v6` followed by LF and the existing parent-hash/sorted asset-name:hash manifest. This takes precedence over font v5 and earlier domains. Bundles without summary components keep all existing identities. Accepted jobs pin the complete original source closure, and subsequent definition edits cannot change their child rows or source versions.

## Rollout and verification

No migration, dependency, request-version or Go contract change is needed; V24 remains current. Deploy all capable core workers before registering these bundles or exposing the upload guidance. Older workers cannot validate v6 summaries. Preserve original parent/child/style/image/font bytes and accepted datasets, and drain or preserve pending jobs before rollback.

Upload [dataset-subreport.jrxml](../../examples/dataset-subreport.jrxml) with [subreport-row.jrxml](../../examples/subreport-row.jrxml). Tests verify all five exports, exact sibling row counts, both nested binding combinations, empty datasets, formula-safe CSV text, rejection of custom bindings/internal parameters, the v6 manifest, PostgreSQL/Batch version pins and original archives. Browser coverage registers a dataset-summary parent with a row-scoped grandchild, verifies the closure/archive, checks each HTML row appears once and compares retained output to Go/S3-emulator delivery. These are local results; general bindings and production release validation remain open.
