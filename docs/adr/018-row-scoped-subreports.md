# ADR-018: Immutable row-scoped JRXML subreports

Status: Accepted for a bounded Phase 3 increment. General/nested subreports, fonts and live/general JDBC remain open.

## Contract

The existing trusted-admin registration map now accepts canonical-base64 `assets/<slug>.jrxml` alongside PNG/JRTX. All files share the existing four-file, 8 KiB each, 16 KiB total limits; the parent remains at most 32 KiB and the JSON request at most 64 KiB. V23 widens the immutable asset-name constraint without editing earlier migrations.

A parent may contain at most four direct detail-band subreport elements:

```xml
<detail><band height="25">
  <element kind="subreport" x="0" y="0" width="515" height="25">
    <expression><![CDATA["assets/subreport-row.jrxml"]]></expression>
  </element>
</band></detail>
```

The expression must be a quoted literal with a safe named JRXML path. It is the element's only child. Connection/data-source expressions, parameter maps/overrides, return values, nested elements, external paths and subreports in other bands are rejected. The parent must declare all three standard sales fields. Each child receives a fresh one-row data source containing the current product, quantity and amount. This is a row component, not an independent query or full-dataset report.

Child JRXML must pass the same hardened UTF-8/XML/trusted-Java validation, declare TITLE, and use the standard sales fields. Its P_ parameter declarations must be a subset of the parent's with identical types. The engine copies parent fill parameters (including locale, time zone and converted/CSV-safe typed values) and passes the current already CSV-safe row. Parameters are not supplied separately by consumers. Child reports cannot contain another subreport; cycles and deeper graphs are therefore rejected.

PNG/JRTX dependencies referenced only by children are permitted. Closure is checked across parent and distinct children: every uploaded file must be used, and every reference must be supplied. At most eight image elements may exist across these source documents. Named styles are resolved separately for each report, with eight combined imported/inline constant styles per style-dependent report and independent style namespaces. Prior standalone/PNG/JRTX behavior and fingerprints remain compatible.

## Compilation and identity

The service expands each child's styles/images in memory, compiles it and embeds the resulting service-generated JasperReport in an internal default parameter of the parent. No serialized report upload is accepted. Embedded Base64 is limited to 256 KiB per compiled child and split into bounded Java string literals; compiler failures still reject registration. A synchronized 16-entry LRU keyed by expanded child-source SHA avoids repeated compilation and keeps parent compile input stable while cached. Parent compilation retains its existing bounded cache. On restart the immutable XML/assets provide everything needed to rebuild both levels without resource lookup.

Original files remain unchanged in PostgreSQL and administrator bundle downloads. Bundles containing JRXML dependencies use `template-subreport-assets-v3` followed by LF, parent SHA and sorted asset-name:SHA lines, following ADR-015's recipe. Standalone raw hashes, PNG v1 and style v2 domains are unchanged. Job snapshots pin this full fingerprint; changed child bytes require a new version, and editing a definition cannot alter accepted jobs. The existing asset hash checks and append-only triggers apply to children.

Trust remains unchanged: administrator Java expressions run with service privileges; validation is compatibility and resource validation, not a sandbox. No runtime filesystem/network report resolver is introduced. Existing job quotas, dataset bounds, artifact limits, owner access, exporters and Go delivery apply.

## Rollout

Deploy V23 and capable core workers before exposing JRXML asset selection or accepting child-dependent definitions. Older workers cannot validate or render these bundles. Back up original parent and all child/style/image rows with accepted snapshots; drain or preserve pending executions before rollback. There is no dependency, Jasper version or Go contract change.

The example parent is [subreport-assets.jrxml](../../examples/subreport-assets.jrxml), uploaded with [subreport-row.jrxml](../../examples/subreport-row.jrxml). Nested subreports, arbitrary datasets/bindings, fonts, live/general JDBC and production release verification remain open.

ADR-020 subsequently permits bounded child/grandchild graphs, adds full-path cycle/depth/fan-out validation and a v4 domain for nested content. Flat bundles keep their exact v3 identity.
