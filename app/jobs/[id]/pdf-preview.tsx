"use client";
import { useEffect, useState } from "react";
import type { components } from "@/lib/api.generated";
export default function PdfPreview({
  jobId,
  artifactId,
}: {
  jobId: string;
  artifactId: string;
}) {
  const [open, setOpen] = useState(false);
  const [metadata, setMetadata] =
    useState<components["schemas"]["PdfPreview"]>();
  const [page, setPage] = useState(0);
  const [image, setImage] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [retry, setRetry] = useState(0);
  const base = `/backend/api/v1/jobs/${jobId}/outputs/${artifactId}/preview`;
  function begin() {
    setBusy(true);
    setImage("");
    setError("");
  }
  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    let objectUrl = "";
    const signal = AbortSignal.any([
      controller.signal,
      AbortSignal.timeout(15000),
    ]);
    async function load() {
      try {
        const info = await fetch(base, {
          signal,
          cache: "no-store",
        });
        const details = await info.json();
        if (!info.ok)
          throw new Error(
            details.detail ?? `Preview unavailable (${info.status})`,
          );
        if (controller.signal.aborted) return;
        setMetadata(details);
        const result = await fetch(`${base}/pages/${page}`, {
          signal,
          cache: "no-store",
        });
        if (!result.ok) {
          const problem = await result.json();
          throw new Error(
            problem.detail ?? `Page unavailable (${result.status})`,
          );
        }
        const blob = await result.blob();
        if (controller.signal.aborted) return;
        objectUrl = URL.createObjectURL(blob);
        setImage(objectUrl);
      } catch (e) {
        if (!controller.signal.aborted)
          setError(e instanceof Error ? e.message : "Preview unavailable");
      } finally {
        if (!controller.signal.aborted) setBusy(false);
      }
    }
    void load();
    return () => {
      controller.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [open, page, base, retry]);
  return (
    <div className="pdf-preview">
      <button
        type="button"
        className="secondary"
        aria-expanded={open}
        aria-controls={`preview-${artifactId}`}
        onClick={() => {
          begin();
          setMetadata(undefined);
          setOpen(!open);
          setPage(0);
        }}
      >
        {open ? "Close PDF preview" : "Preview PDF"}
      </button>
      {open && (
        <section
          id={`preview-${artifactId}`}
          aria-label="PDF page preview"
          aria-busy={busy}
        >
          <h3>PDF page preview</h3>
          <p>
            Raster preview of the retained output. Download the PDF for
            selectable text and the full document. Previewing does not submit or
            deliver another report.
          </p>
          {metadata && (
            <>
              <p>
                Page {page + 1} of {metadata.pageCount} · {metadata.dpi} DPI
                {metadata.previewPages < metadata.pageCount
                  ? ` · First ${metadata.previewPages} pages available here`
                  : ""}
              </p>
              <div className="title-row">
                <button
                  type="button"
                  disabled={busy || page === 0}
                  onClick={() => {
                    begin();
                    setPage(page - 1);
                  }}
                >
                  Previous PDF page
                </button>
                <button
                  type="button"
                  disabled={busy || page + 1 >= metadata.previewPages}
                  onClick={() => {
                    begin();
                    setPage(page + 1);
                  }}
                >
                  Next PDF page
                </button>
              </div>
            </>
          )}
          {busy && <p role="status">Loading PDF page…</p>}
          {error && (
            <p role="alert" className="error">
              {error}
            </p>
          )}
          {error && (
            <button
              type="button"
              onClick={() => {
                begin();
                setRetry(retry + 1);
              }}
            >
              Retry PDF preview
            </button>
          )}
          {image && (
            <figure>
              {/* Authenticated blob URLs must not pass through the Next image optimizer. */}
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={image}
                alt={`Report PDF preview, page ${page + 1} of ${metadata?.pageCount}`}
                onError={() => {
                  setImage("");
                  setError(
                    "Preview image could not be displayed. Retry or download the PDF.",
                  );
                }}
              />
            </figure>
          )}
        </section>
      )}
    </div>
  );
}
