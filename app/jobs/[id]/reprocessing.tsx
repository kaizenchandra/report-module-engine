"use client";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import type { components } from "@/lib/api.generated";
type JobPage = components["schemas"]["JobPage"];
export default function Reprocessing({
  id,
  csrfHeader,
  csrfToken,
}: {
  id: string;
  csrfHeader: string;
  csrfToken: string;
}) {
  const router = useRouter();
  const key = useRef<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [page, setPage] = useState(0);
  const [refresh, setRefresh] = useState(0);
  const [history, setHistory] = useState<JobPage | null>(null);
  const [historyError, setHistoryError] = useState("");
  useEffect(() => {
    const controller = new AbortController();
    async function load() {
      try {
        const response = await fetch(
          `/backend/api/v1/jobs/${id}/reprocessings?page=${page}`,
          {
            cache: "no-store",
            signal: AbortSignal.any([
              controller.signal,
              AbortSignal.timeout(10000),
            ]),
          },
        );
        if (!response.ok)
          throw new Error(
            "Reprocessing history is unavailable. Refresh to retry.",
          );
        const result = await response.json();
        if (!controller.signal.aborted) setHistory(result);
      } catch (e) {
        if (!controller.signal.aborted)
          setHistoryError(
            e instanceof Error ? e.message : "History unavailable",
          );
      }
    }
    void load();
    return () => controller.abort();
  }, [id, page, refresh]);
  function reload() {
    setHistory(null);
    setHistoryError("");
    setRefresh((n) => n + 1);
  }
  function changePage(next: number) {
    setHistory(null);
    setHistoryError("");
    setPage(next);
  }
  async function reprocess() {
    setBusy(true);
    setError("");
    key.current ??= crypto.randomUUID();
    try {
      const response = await fetch(`/backend/api/v1/jobs/${id}/reprocess`, {
        method: "POST",
        headers: { [csrfHeader]: csrfToken, "Idempotency-Key": key.current },
        signal: AbortSignal.timeout(15000),
      });
      const result = await response.json();
      if (!response.ok)
        throw new Error(
          result.detail ?? `Reprocessing rejected (${response.status})`,
        );
      router.push(`/jobs/${result.id}`);
    } catch (e) {
      setError(
        e instanceof Error
          ? e.message
          : "Reprocessing unavailable. Retry to recover the acknowledgement.",
      );
      reload();
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="panel" aria-label="Reprocessing">
      <h2>Reprocess report</h2>
      <p>
        Create a new linked job using the original parameters, formats, mappings
        and exact definition version. The definition must still be enabled.
        Destinations are resolved again using their current settings.
      </p>
      <p>
        This regenerates every output and uploads new objects. Existing jobs and
        delivered objects remain unchanged. Delivery retries already run
        independently within each job.
      </p>
      <button disabled={busy} onClick={reprocess}>
        {busy ? "Accepting reprocessing…" : "Reprocess as new job"}
      </button>
      <p className="muted">
        If acknowledgement is lost, retrying this action on this page reuses the
        same idempotency key. Check linked executions before starting another
        request after reloading.
      </p>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      <h3>Linked reprocessing executions</h3>
      {historyError ? (
        <p className="error" role="alert">
          {historyError}
        </p>
      ) : !history ? (
        <p>Loading linked executions…</p>
      ) : history.items.length === 0 ? (
        <p>No reprocessing executions on this page.</p>
      ) : (
        <ul>
          {history.items.map((job) => (
            <li key={job.id}>
              <Link href={`/jobs/${job.id}`}>{job.id}</Link> · {job.status} ·{" "}
              {new Date(job.created_at).toISOString()}
            </li>
          ))}
        </ul>
      )}
      <div className="actions">
        <button className="secondary" onClick={reload}>
          Refresh linked executions
        </button>
        {page > 0 && (
          <button className="secondary" onClick={() => changePage(page - 1)}>
            Previous linked executions
          </button>
        )}
        {history && (page + 1) * history.pageSize < history.total && (
          <button className="secondary" onClick={() => changePage(page + 1)}>
            Older linked executions
          </button>
        )}
      </div>
    </section>
  );
}
