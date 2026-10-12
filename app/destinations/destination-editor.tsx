"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
import type { components } from "@/lib/api.generated";
type Destination = components["schemas"]["Destination"];
export default function DestinationEditor({
  destination,
  token,
  header,
}: {
  destination?: Destination;
  token: string;
  header: string;
}) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError("");
    setMessage("");
    const form = event.currentTarget;
    const fields = new FormData(form);
    const configuration = {
      provider: fields.get("provider"),
      bucket: fields.get("bucket"),
      prefix: fields.get("prefix"),
      enabled: fields.get("enabled") === "on",
    };
    const body = destination
      ? { expectedVersion: destination.version, configuration }
      : { id: fields.get("id"), configuration };
    try {
      const response = await fetch(
        `/backend/api/v1/destinations${destination ? `/${destination.id}` : ""}`,
        {
          method: destination ? "PUT" : "POST",
          headers: { "Content-Type": "application/json", [header]: token },
          body: JSON.stringify(body),
        },
      );
      const result = await response.json();
      if (!response.ok)
        throw new Error(
          result.errors
            ?.map(
              (e: { field: string; message: string }) =>
                `${e.field}: ${e.message}`,
            )
            .join("; ") ||
            result.detail ||
            `Save rejected (${response.status})`,
        );
      setMessage(
        `Saved version ${result.version}. Accepted jobs retain their original destination.`,
      );
      if (!destination) form.reset();
      router.refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Save failed");
    } finally {
      setBusy(false);
    }
  }
  return (
    <details>
      <summary>
        {destination ? "Edit configuration" : "Register a destination"}
      </summary>
      <form onSubmit={save} aria-busy={busy}>
        {!destination && (
          <label>
            Reference ID
            <input
              name="id"
              required
              pattern="[a-z][a-z0-9-]{2,63}"
              maxLength={64}
              placeholder="finance-reports"
            />
          </label>
        )}
        <label>
          Provider
          <select name="provider" defaultValue={destination?.provider ?? "S3"}>
            <option value="S3">AWS S3</option>
            <option value="GCS">Google Cloud Storage</option>
          </select>
        </label>
        <label>
          Bucket
          <input
            name="bucket"
            required
            defaultValue={destination?.bucket ?? ""}
            maxLength={63}
          />
        </label>
        <label>
          Object prefix
          <input
            name="prefix"
            defaultValue={destination?.prefix ?? "reports/"}
            pattern="([A-Za-z0-9_-]+/)*"
            maxLength={200}
          />
        </label>
        <p className="muted">
          Use slash-terminated path segments containing letters, digits,
          underscores or hyphens. Empty prefix uses the bucket root. Only
          deployment-allowlisted buckets are accepted.
        </p>
        <label>
          <input
            type="checkbox"
            name="enabled"
            defaultChecked={destination?.enabled ?? true}
          />{" "}
          Accept new submissions
        </label>
        <button disabled={busy}>{busy ? "Saving…" : "Save destination"}</button>
        {error && <p role="alert">{error}</p>}
        {message && <p role="status">{message}</p>}
      </form>
    </details>
  );
}
