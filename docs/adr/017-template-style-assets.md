# ADR-017: Bounded named JRTX style dependencies

Status: Accepted for a Phase 3 increment. Subreport/font dependencies and arbitrary archive import remain open.

## Style file contract

Trusted template bundles may now include `assets/<name>.jrtx` alongside PNG assets through the existing canonical-base64 registration map. Filenames retain the lowercase slug rules. The shared limits remain four files, 8 KiB per file and 16 KiB total, within the existing 64 KiB JSON registration limit. V22 widens only the immutable asset-name constraint; it does not modify previously applied migrations or PNG content.

A JRXML style import is one root-level literal declaration:

```xml
<template><![CDATA["assets/brand.jrtx"]]></template>
```

The original source may reference imported styles through an element's `style` attribute. A JRTX file contains a namespace-free `jasperTemplate` root and named, empty `style` elements with constant attributes:

```xml
<jasperTemplate>
  <style name="Base" fontName="SansSerif" fontSize="18" bold="true"/>
  <style name="Accent" style="Base" forecolor="#ff0000"/>
</jasperTemplate>
```

Supported attributes are name, parent style, logical fontName, fontSize, bold, italic, underline, strikeThrough, forecolor, backcolor and mode. Names/parent names use 1–32 ASCII letters/digits/underscores/hyphens starting with a letter. Fonts remain SansSerif/Serif/Monospaced; sizes must be finite numbers from 6 to 48; boolean flags use true/false; colors use six-digit hexadecimal RGB; mode is Opaque or Transparent.

A style-dependent bundle permits at most eight named styles across imported files and inline JRXML styles. Inline styles in such bundles follow the same constant-attribute policy. Duplicate names, duplicate file imports, missing parents, inheritance cycles, unknown element style references and missing/unused resource files are rejected. Conditional styles, default-style flags, nested templates, style expressions, resource properties, namespaces, custom fonts and other child elements are unsupported. Existing templates without JRTX imports retain their previous inline-style behavior.

The existing hardened XML parser rejects DOCTYPE and external entities/schema access and applies its depth/attribute limits. Style bytes must round-trip as UTF-8. These checks do not sandbox the trusted Java expressions elsewhere in the report.

## Expansion, fingerprints and archives

Before compilation, the complete source and asset closure is validated. The renderer removes external declarations, resolves parent styles in memory and flattens effective attributes into inline named styles. This makes rendering independent of style declaration order; the installed Jasper 7.0.3 fill path otherwise requires parent styles to precede dependents. No filesystem or network style resolver is installed.

Bundles containing JRTX use the same sorted manifest recipe as ADR-015 but with domain line `template-style-assets-v2` followed by LF. Every source/asset byte hash and name participates. PNG-only bundles keep the exact `template-png-assets-v1` domain, and standalone templates keep raw-source SHA-256. Changed style bytes therefore create a new reference without invalidating prior references. Expanded effective source drives compile-cache identity; changes to effective style attributes cannot reuse stale compiled styles.

Stored and downloaded source/style bytes remain original. Administrator-only bundle archives contain report.jrxml and the exact named PNG/JRTX files. Accepted job snapshots pin the complete content fingerprint, so definition revisions cannot change an accepted job's styles. Normal locale/time-zone, typed parameter, output policy, owner access and Go delivery paths remain applicable.

## UI and rollout

Templates accepts PNG and JRTX files together, shows selected filenames and links complete bundle download/definition registration. Uploads still require a reviewed template, REPORT_ADMIN, CSRF and the deployment trust flag. Backend closure/style validation is authoritative. The example is [style-assets.jrxml](../../examples/style-assets.jrxml) with [brand.jrtx](../../examples/brand.jrtx).

Deploy V22 and capable core workers before admitting style bundles or the frontend update; older workers cannot interpret these references. Preserve asset/source rows and accepted jobs during rollback and backups. No dependency, Java/Jasper version or Go contract change. Font bundles, subreports, general JDBC, tenant policy, parser/compiler isolation and production release validation remain open.

ADR-018 subsequently adds row-scoped subreports. In those bundles, the eight-style limit applies independently to each style-dependent parent/child document, and JRXML-bearing fingerprints use v3.

ADR-021 subsequently permits a fontName reference to a bounded TTF asset in constant styles, with font closure and per-fill context resolution. Font-bearing bundles use the v5 domain.
