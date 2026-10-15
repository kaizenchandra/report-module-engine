# ADR-021: Small immutable TrueType font subsets

Status: Accepted for a bounded Phase 3 increment. Full font packs, automatic source-font subsetting, linked weight/style families and broad script/office visual certification remain open.

## Asset and validation contract

Trusted bundles may include assets/<slug>.ttf through the existing canonical-base64 asset map. Use a literal fontName="assets/digits.ttf" on a JRXML element or constant JRTX style. Font references participate in complete closure, including styles and nested descendants: every referenced file must exist and unused files fail registration. V24 widens the immutable asset-name constraint; prior migrations are unchanged.

Existing limits remain four files, 8 KiB each, 16 KiB total, a 32 KiB parent and a 64 KiB JSON request. This intentionally supports small pre-subset fonts, rather than typical full font installations. A font must have the TrueType 0x00010000 signature, a bounded directory of at most 32 unique in-bounds/aligned tables, 2–256 glyphs including .notdef, a Unicode cmap and OS/2.fsType=0. FontBox and Java's TrueType loader both validate it. Family names use 1–64 ASCII letters/digits/spaces/underscores/hyphens starting with a letter; PostScript names use the same bounds without spaces. Collections, CFF/OTF containers, variable/color/bitmap-specific tables and other font formats are rejected. The embedding flag check is a technical file-policy check.

After fill, text using a bundled font must be covered by its character map (line/tab controls excepted). Missing characters fail generation before export/persistence, rather than silently producing replacement boxes. Subsets must include formatting characters for the chosen locale as well as their data text. Shared-fill coverage also includes the literal apostrophe used by CSV text protection when such a font is applied to a TEXT field. Registration cannot prove coverage for future parameters or rows. Each file is an independent font family/face; multi-face family configuration and automatic subsetting of uploaded full fonts are not implemented.

## Scoped resolution and identity

The in-memory expansion replaces font paths with a family alias derived from the exact font SHA-256. Server-generated root report properties retain the bounded font programs for the compiled report. Uploaded resource properties remain forbidden. Original JRXML, JRTX and TTF bytes are never rewritten in storage or administrator archives.

Each fill/export gets a JasperReportsContext with only its bundle's font families and an exact in-memory font repository. Descendants share that fill context. The service does not register fonts globally or install a deployment font extension; two versions with the same internal font name remain isolated through distinct content aliases. Java/Jasper may use their normal internal font-loader resources; no caller-controlled filesystem/network font lookup is introduced.

Font-bearing bundles use template-font-assets-v5 followed by LF, then the existing parent hash and sorted asset-name:hash manifest. This domain takes precedence over nested v4, flat v3, style v2 and PNG v1. All non-font domains and standalone hashes remain unchanged. Accepted job snapshots pin the full font/source/asset fingerprint. New font bytes require a new template version and cannot change an earlier accepted job. The shared compiled-report caches remain bounded; no new runtime dependency is required because FontBox is already supplied by the existing PDFBox dependency.

## Export behavior

PDF uses the scoped TrueType family with Identity-H encoding and embedding enabled. The PDF exporter can further subset the supplied font; retained PDFs are verified to contain embedded font programs and different regular/wide numeric metrics.

HTML packages contain the exact original TTF and Jasper-generated font CSS with hashed safe filenames. CSS font URLs resolve relative to their stylesheet under assets/. Image and font resources share existing closure/count/byte budgets. The package CSP permits its own stylesheet/font files, and the native legacy IE font-refresh script is removed; remaining script tags reject export. HTML remains a downloadable attachment, never an application-origin preview. Chromium loading is verified from a separate static HTTP origin; direct file:// viewer policy and other browser behavior are not certified.

XLSX and DOCX carry the font's original family name as a hint, without installing/embedding the font for the receiving office application. Clients may substitute a local font; Office visual round-trip fidelity remains unverified. CSV retains its existing literal-text protection and carries no visual font data.

## Examples and rollout

Upload [font-assets.jrxml](../../examples/font-assets.jrxml) with [digits.ttf](../../examples/digits.ttf). The original ReportDigits seven-segment fixture covers digits, space, comma, period and hyphen. [digits-wide.ttf](../../examples/digits-wide.ttf) has the same family name with wider metrics to test isolation. Both are generated without third-party glyphs by [the standard-library script](../../scripts/generate-digit-font.py), carry a CC0 notice and can be regenerated from the repository root. The generator follows the [OpenType table format](https://learn.microsoft.com/en-us/typography/opentype/spec/otff).

Deploy V24 and all capable core workers before enabling font-bearing definitions or the updated frontend. Older workers cannot validate these names/manifests. Back up original font/source/style/descendant rows with accepted snapshots, and drain/preserve pending jobs before rollback. No Go contract, request schema version or production restore certification changes. General font packs/script coverage, general/custom subreport bindings, general JDBC and later governance/release work remain open.
