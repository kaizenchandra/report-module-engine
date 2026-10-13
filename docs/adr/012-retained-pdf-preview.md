# ADR-012: Owner-authorized previews of retained PDFs

Status: Implemented; verification evidence is in VERIFICATION.md.

## Decision

Preview is a read operation on an artifact already created through normal authenticated submission, immutable configuration resolution, Spring Batch/Jasper rendering and delivery orchestration. There is no second renderer job or synchronous JRXML-preview shortcut. A user generates a PDF through the normal form/editor, opens its job detail and selects Preview PDF. Preview does not change job outcomes, attempts, cloud deliveries, or retention policy. Preview is available when a PDF artifact exists, independent of eventual delivery success; cancellation does not delete it.

Core exposes owner-authorized metadata and zero-based page endpoints under `/api/v1/jobs/{id}/outputs/{artifactId}/preview`. Ownership is checked before artifact lookup, including for administrators; an unrelated artifact ID cannot be attached to another job. Each request rereads the retained file with symlink following disabled, bounds bytes and verifies its saved size and SHA-256. Non-PDF outputs return 415, missing artifacts 404, removed files 410 and failed integrity checks 409. Malformed/unsupported pages return 422. No public or cloud URL is used for preview.

PDFBox renders individual pages to PNG. The app receives only raster images; it does not embed PDF viewers or uploaded HTML in its trusted origin. Responses use no-store, PNG content type and nosniff. The client fetches with the normal same-origin session, creates a temporary blob URL and revokes it when changing pages, closing or unmounting. Pending client requests are aborted on navigation/close. A 15-second browser timeout exposes retry rather than implying job failure. Retrying a preview only repeats these reads.

## Limits and trust

Input is restricted to retained, integrity-checked PDFs emitted by this platform's trusted-template policy. There is no PDF-upload API. Up to two preview operations per core process are admitted; excess operations receive 429. At most 10 MiB input, the first 20 pages, 96 DPI, 2000 pixels per side, two million pixels per page and four MiB encoded PNG are supported. Metadata reports the document's actual page count and the accessible preview range. Other formats, full-document thumbnails, preview-before-submission, password-protected PDFs and PDF/A verification are unsupported.

These bounds are not a complete parser sandbox, memory guarantee or CPU deadline. PDFBox's [security model](https://pdfbox.apache.org/security.html) explicitly distinguishes parsing robustness/resource consumption from its security guarantees. Trusted-template execution remains trusted code. Before supporting arbitrary uploaded PDFs or untrusted template output, add a separately isolated rendering process with enforced memory/CPU/time limits. Production load, pathological documents, distributed fairness and hard interruption remain release work. A client disconnect does not forcibly interrupt in-process PDFBox rendering.

Raster preview has no selectable text or document semantics for assistive technology. The UI labels the page, supplies keyboard-operable navigation/error recovery and retains the authorized PDF download. Automated UI accessibility checks do not certify the accessibility of report contents.

## Dependency and lifecycle

PDFBox 3.0.5 was already pinned for report-content tests; it is now an explicit compile/runtime dependency, with Java 21 unchanged. The official [3.0 migration guide](https://pdfbox.apache.org/3.0/migration.html) documents Loader and the byte-array reader. [Apache rendering guidance](https://github.com/apache/pdfbox-docs/blob/master/content/3.0/faq.md) informed single-page rendering, subsampling, bounded DPI and prompt document/image disposal. No embedded-file extraction code or examples module is included. The official security page was reviewed; this is not a completed dependency/container vulnerability scan or a latest-patch claim.

No database migration, persisted preview record, new artifact handoff or Go change is needed. PNGs are not retained on disk. Rollback removes the new endpoints/UI without changing stored jobs or artifacts. Existing download contracts and request fingerprints are unchanged.
