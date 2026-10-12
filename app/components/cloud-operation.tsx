"use client";
import { useEffect, useState } from "react";
import type { components } from "@/lib/api.generated";
type Operation = components["schemas"]["CloudOperation"];
const messages: Record<string, string> = {
  CHECK_OK:
    "Bucket metadata is reachable with the delivery identity. Write permissions and encryption were not tested.",
  SIGN_OK: "A temporary cloud download link is ready.",
  PROVIDER_UNAVAILABLE:
    "The cloud operation failed after bounded retries. Check provider access and worker diagnostics.",
  SIGNING_UNAVAILABLE:
    "A signing identity is not configured. Use the authenticated output download instead.",
  EXPIRED: "This operation has expired. Request a new one.",
  DESTINATION_NOT_ALLOWED:
    "The destination no longer matches the delivery deployment allowlist.",
  OBJECT_CHANGED:
    "The delivered object could not be verified against its receipt. No link was issued.",
};
export default function CloudOperation({
  endpoint,
  label,
  token,
  header,
}: {
  endpoint: string;
  label: string;
  token: string;
  header: string;
}) {
  const [operation, setOperation] = useState<Operation | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => {
    if (!operation || operation.status === "EXPIRED") return;
    const controller = new AbortController();
    const deadline = setTimeout(
      () =>
        setOperation((o) =>
          o ? { ...o, status: "EXPIRED", url: "", code: "EXPIRED" } : o,
        ),
      Math.max(0, new Date(operation.expires_at).getTime() - Date.now()),
    );
    let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      try {
        const response = await fetch(
          `/backend/api/v1/cloud-operations/${operation!.id}`,
          { cache: "no-store", signal: controller.signal },
        );
        if (!response.ok)
          throw new Error(
            `Operation status unavailable (${response.status}). Retry to recover the same request.`,
          );
        const result: Operation = await response.json();
        if (!controller.signal.aborted) {
          setError("");
          setOperation(result);
        }
      } catch (e) {
        if (!controller.signal.aborted) {
          setError(e instanceof Error ? e.message : "Connection lost");
          timer = setTimeout(poll, 2000);
        }
      }
    }
    if (operation.status === "PENDING") timer = setTimeout(poll, 1000);
    return () => {
      controller.abort();
      clearTimeout(timer);
      clearTimeout(deadline);
    };
  }, [operation]);
  async function request() {
    setBusy(true);
    setError("");
    try {
      const response = await fetch(`/backend/api/v1${endpoint}`, {
        method: "POST",
        headers: { [header]: token },
        cache: "no-store",
      });
      const result = await response.json();
      if (!response.ok)
        throw new Error(
          result.detail || `Request rejected (${response.status})`,
        );
      setOperation(result);
    } catch (e) {
      setError(
        e instanceof Error
          ? e.message
          : "Request failed. Retry to recover its acknowledgement.",
      );
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="cloud-operation">
      <button
        type="button"
        className="secondary"
        disabled={busy || operation?.status === "PENDING"}
        onClick={request}
      >
        {busy ? "Requesting…" : label}
      </button>
      {error && <p role="alert">{error}</p>}
      {operation && (
        <>
          {operation.emulated && (
            <p className="muted">
              Local emulator: cloud IAM, signature and expiry enforcement are
              not verified here.
            </p>
          )}
          <p role="status">
            {operation.status === "PENDING"
              ? "Waiting for the delivery service…"
              : messages[operation.code] || operation.status}
          </p>
          {operation.kind === "CHECK" && (
            <p className="muted">
              Destination version {operation.resource_version} · Requested{" "}
              {new Date(operation.created_at).toISOString()}
            </p>
          )}
          {operation.status === "SUCCEEDED" && operation.url && (
            <>
              <a
                href={operation.url}
                rel="noreferrer noopener"
                referrerPolicy="no-referrer"
              >
                Download from cloud
              </a>
              <p className="muted">
                Anyone with this link can download until{" "}
                {new Date(operation.expires_at).toISOString()} (UTC). It may
                expire earlier if its signing credentials expire.
              </p>
            </>
          )}
        </>
      )}
    </div>
  );
}
