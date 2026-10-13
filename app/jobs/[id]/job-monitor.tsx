"use client";
import Link from "next/link";
import Reprocessing from "./reprocessing";
import PdfPreview from "./pdf-preview";
import CloudOperation from "@/app/components/cloud-operation";
import { useEffect, useState } from "react";
import type { components } from "@/lib/api.generated";
type Job = components["schemas"]["Job"];
export default function JobMonitor({
  initial,
  csrfHeader,
  csrfToken,
}: {
  initial: Job;
  csrfHeader: string;
  csrfToken: string;
}) {
  const [job, setJob] = useState(initial);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const terminal = [
    "SUCCEEDED",
    "PARTIAL_SUCCESS",
    "FAILED",
    "CANCELLED",
  ].includes(job.status);
  useEffect(() => {
    if (terminal) return;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    async function refresh() {
      try {
        const response = await fetch(`/backend/api/v1/jobs/${initial.id}`, {
          signal: controller.signal,
          cache: "no-store",
        });
        if (!response.ok)
          throw new Error(
            `Status unavailable (${response.status}). Reconnecting…`,
          );
        setJob(await response.json());
        setError("");
      } catch (e) {
        if (!controller.signal.aborted)
          setError(
            e instanceof Error ? e.message : "Connection lost. Reconnecting…",
          );
      } finally {
        if (!controller.signal.aborted) timer = setTimeout(refresh, 2000);
      }
    }
    timer = setTimeout(refresh, 1000);
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [initial.id, terminal]);
  async function cancel() {
    setBusy(true);
    try {
      const response = await fetch(`/backend/api/v1/jobs/${job.id}/cancel`, {
        method: "POST",
        headers: { [csrfHeader]: csrfToken },
      });
      if (!response.ok)
        throw new Error(
          "Cancellation was not accepted. The job may already be terminal; refresh its status.",
        );
      const updated = await fetch(`/backend/api/v1/jobs/${job.id}`);
      if (updated.ok) setJob(await updated.json());
    } catch (e) {
      setError(e instanceof Error ? e.message : "Cancellation failed");
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <section className="panel">
        <div className="title-row">
          <h2>Execution status</h2>
          <span className="badge" role="status">
            {job.status}
          </span>
        </div>
        <dl>
          {job.parent_job_id && (
            <>
              <dt>Reprocessed from</dt>
              <dd>
                <Link href={`/jobs/${job.parent_job_id}`}>
                  {job.parent_job_id}
                </Link>
              </dd>
            </>
          )}
          {job.deadline && (
            <>
              <dt>Deadline (UTC)</dt>
              <dd>{new Date(job.deadline).toISOString()}</dd>
            </>
          )}
          <dt>Current stage</dt>
          <dd>{job.stage}</dd>
          <dt>Render attempts</dt>
          <dd>{job.attempt}</dd>
          <dt>Batch execution</dt>
          <dd>{job.batch_execution_id ?? "Not recorded yet"}</dd>
          <dt>Last update (UTC)</dt>
          <dd>{new Date(job.updated_at).toISOString()}</dd>
        </dl>
        {!terminal && (
          <>
            <p className="muted">
              Status refreshes automatically. Generation progress is reported by
              stage.
            </p>
            <button className="secondary" disabled={busy} onClick={cancel}>
              {busy ? "Cancelling…" : "Cancel job"}
            </button>
            <p className="muted">
              Cancellation does not delete objects that have already been
              delivered.
            </p>
          </>
        )}
        {job.failure_code && (
          <p role="alert" className="error">
            Failure: {job.failure_code}
          </p>
        )}
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
      </section>
      <section className="panel">
        <h2>Execution attempts</h2>
        {job.executions?.length ? (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Execution</th>
                  <th>Status</th>
                  <th>Started (UTC)</th>
                  <th>Exit code</th>
                </tr>
              </thead>
              <tbody>
                {job.executions.map((e) => (
                  <tr key={e.job_execution_id}>
                    <td>{e.job_execution_id}</td>
                    <td>{e.status}</td>
                    <td>{e.start_time ?? "Not started"}</td>
                    <td>{e.exit_code ?? "Pending"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p>No Batch execution has started.</p>
        )}
      </section>
      <section className="panel">
        <h2>Outputs</h2>
        {job.outputs?.map((o) => (
          <p key={o.format}>
            {o.format} · {o.required ? "Required" : "Optional"} · {o.status}
            {o.failure_code ? ` · ${o.failure_code}` : ""}
          </p>
        ))}
        {job.artifacts?.length ? (
          job.artifacts.map((a) => (
            <div key={a.id}>
              <p>
                {a.format === "HTML" ? "HTML package (ZIP)" : a.format} ·{" "}
                {a.size_bytes.toLocaleString()} bytes
              </p>
              <p className="muted">
                SHA-256: <code>{a.sha256}</code>
              </p>
              <a
                className="button"
                href={`/backend/api/v1/jobs/${job.id}/outputs/${a.id}`}
              >
                Download {a.format}
              </a>
              {a.format === "PDF" && (
                <PdfPreview jobId={job.id} artifactId={a.id} />
              )}
            </div>
          ))
        ) : (
          <p>No artifact is available yet.</p>
        )}
      </section>
      <section className="panel">
        <h2>Delivery</h2>
        <p className="muted">
          Each delivery has independent retries. Outputs that fail export cannot
          be delivered; their requested mappings remain in the resolved
          configuration.
        </p>
        {job.deliveryAttempts?.length ? (
          <div className="table-scroll">
            <table>
              <caption>Delivery attempts · UTC</caption>
              <thead>
                <tr>
                  <th>Delivery</th>
                  <th>Attempt</th>
                  <th>Operation</th>
                  <th>Status</th>
                  <th>Started</th>
                  <th>Ended</th>
                  <th>Diagnostic</th>
                </tr>
              </thead>
              <tbody>
                {job.deliveryAttempts.map((a) => (
                  <tr key={`${a.delivery_id}-${a.number}`}>
                    <td>
                      {
                        job.deliveries?.find((d) => d.id === a.delivery_id)
                          ?.destination_ref
                      }{" "}
                      ·{" "}
                      {
                        job.deliveries?.find((d) => d.id === a.delivery_id)
                          ?.format
                      }
                    </td>
                    <td>{a.number}</td>
                    <td>{a.phase}</td>
                    <td>{a.status}</td>
                    <td>{a.started_at}</td>
                    <td>{a.ended_at ?? "In progress"}</td>
                    <td>{a.code}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="muted">No delivery attempt has been reported.</p>
        )}

        {job.deliveries?.length ? (
          job.deliveries.map((d) => (
            <div key={d.id}>
              <p>
                <strong>{d.destination_ref}</strong> · {d.format} ·{" "}
                {d.required ? "Required" : "Optional"} · {d.status}
              </p>
              <p>
                <code>{d.object_key}</code>
              </p>
              {d.status === "VERIFIED" && (
                <CloudOperation
                  endpoint={`/jobs/${job.id}/deliveries/${d.id}/download-links`}
                  label="Create temporary cloud link"
                  token={csrfToken}
                  header={csrfHeader}
                />
              )}
              {d.receipt && (
                <pre>{JSON.stringify(JSON.parse(d.receipt), null, 2)}</pre>
              )}
            </div>
          ))
        ) : (
          <p>Delivery starts after a complete artifact is retained.</p>
        )}
      </section>
      {terminal && (
        <Reprocessing
          id={job.id}
          csrfHeader={csrfHeader}
          csrfToken={csrfToken}
        />
      )}
      <details className="panel">
        <summary>Immutable resolved configuration</summary>
        <pre>
          {job.configuration
            ? JSON.stringify(JSON.parse(job.configuration), null, 2)
            : "Unavailable"}
        </pre>
      </details>
    </>
  );
}
