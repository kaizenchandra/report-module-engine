# ADR-020: Bounded nested row-scoped subreports

Status: Accepted for a Phase 3 increment. General bindings, deeper graphs, fonts and additional data-source schemas remain open.

## Graph contract

The row-scoped JRXML dependency contract from ADR-018 now permits a child to reference a grandchild. All references remain direct detail-band elements with a single safe quoted assets/<slug>.jrxml expression. Every level receives the current product/quantity/amount row and matching declared parameters. There are no independent queries, connections, dataset overrides or return values.

The parent is depth zero. A child is depth one and a grandchild depth two; depth three is rejected. Each source document permits at most four subreport elements. The fully expanded graph permits at most eight subreport instances per original sales row, counting every reference along every path, including repeated/shared children. For example, two references to a section that contains three references to a leaf cost 2 + (2 × 3) = 8 and are accepted. Three sections with two leaves cost 3 + (3 × 2) = 9 and are rejected. These limits apply before compilation and rendering.

Validation walks every expanded path, maintaining an active path set to reject self/indirect cycles and a global expansion counter to reject excessive fan-out. Shared descendants are allowed after their prior path completes. Every edge checks that child P_ declarations are a type-identical subset of the immediate parent's declarations. An intermediate report must explicitly declare a parameter needed by its grandchild. Every report that supplies rows to another must declare all three standard sales fields.

The complete PNG/JRTX/JRXML closure must be supplied; missing or unused files fail registration. The existing four-file, 8 KiB each, 16 KiB total, 32 KiB parent and 64 KiB request limits remain. Image elements are counted once per distinct source document, with eight total across the closure. Styles retain independent report namespaces and at most eight combined imported/inline styles per style-dependent report. Hardened XML/UTF-8 validation and trusted-administrator policy are unchanged.

## Compilation and parameter isolation

Expansion recursively compiles descendants before their parents, embedding only service-generated compiled reports as internal defaults. The existing 256 KiB encoded compiled-child limit and 16-entry LRU remain. No serialized report upload or runtime filesystem/network resolver is introduced.

Each generated subreport binding copies the parent fill parameter map and removes internal R_SUBREPORT_ keys before passing it on. This is necessary because every report declares its own generated defaults using the same reserved internal namespace. Inheriting the parent's compiled report under that name would override a child's default and could repeatedly execute the wrong report. Consumer declarations cannot use this namespace. TITLE, typed P_ values and Jasper context/locale/time-zone parameters remain available, and the original parent map is not mutated. Every level receives a fresh one-row data source; CSV text values stay protected once at the initial fill.

## Identity and durable execution

A bundle with a subreport element inside any JRXML asset uses the domain line template-nested-subreport-assets-v4 followed by LF, then the existing parent hash and sorted asset-name:hash manifest recipe. Descendant byte changes create a new immutable version. Flat JRXML bundles retain their exact v3 fingerprints, style-only v2 and PNG-only v1 references remain unchanged, and standalone source still uses raw SHA-256. Existing flat content rebuilds with the new parameter-isolation helper but keeps its identity and behavior.

Accepted jobs continue to pin the full original source/asset fingerprint. Changing a grandchild through a new template/definition version cannot change an earlier job. Administrator archives retain original parent, child, grandchild, style and image bytes. No new migration, request schema version, dependency or Go contract is required; V23 remains current.

## Policy, examples and rollout

GET /templates/policy advertises maxSubreportDepth=2, maxSubreportsPerReport=4 and maxExpandedSubreports=8. These are additive optional fields in OpenAPI. The upload UI displays the policy limits, and backend graph validation is authoritative.

Upload [nested-subreports.jrxml](../../examples/nested-subreports.jrxml) with [nested-section.jrxml](../../examples/nested-section.jrxml) and [subreport-row.jrxml](../../examples/subreport-row.jrxml). Deploy all capable core workers before admitting v4 bundles or exposing the updated frontend. Older workers reject these nested dependencies. Preserve pending jobs and their entire original closure during rollback/backup; no production restore certification is claimed. General/custom bindings, deeper graphs, fonts, general JDBC and production isolation/release work remain open.
