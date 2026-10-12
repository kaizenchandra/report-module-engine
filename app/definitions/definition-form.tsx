"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
import type { components } from "@/lib/api.generated";
type Definition = components["schemas"]["Definition"];
export default function DefinitionForm({
  current,
  identity,
  templateReference,
  sourceReference,
}: {
  current?: Definition;
  templateReference?: string;
  sourceReference?: string;
  identity: components["schemas"]["Identity"];
}) {
  const router = useRouter();
  const [template, setTemplate] = useState(
    current?.template_resource ??
      templateReference ??
      "templates/sales-summary.jrxml",
  );
  const [source, setSource] = useState(
    current?.data_source_ref ?? sourceReference ?? "sample-v1",
  );
  const [id, setId] = useState(current?.id ?? "");
  const [name, setName] = useState(current?.name ?? "");
  const [description, setDescription] = useState(current?.description ?? "");
  const [enabled, setEnabled] = useState(current?.enabled ?? true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      const configuration: components["schemas"]["DefinitionConfiguration"] = {
        name,
        description,
        enabled,
        templateResource: template,
        dataSourceRef: source,
      };
      const response = await fetch(
        `/backend/api/v1/definitions${current ? `/${current.id}` : ""}`,
        {
          method: current ? "PUT" : "POST",
          headers: {
            "Content-Type": "application/json",
            [identity.csrfHeader]: identity.csrfToken,
          },
          body: JSON.stringify(
            current
              ? { expectedVersion: current.version, configuration }
              : { id, configuration },
          ),
          signal: AbortSignal.timeout(10000),
        },
      );
      const result = await response.json();
      if (!response.ok)
        throw new Error(result.detail ?? `Save failed (${response.status})`);
      router.push(`/definitions/${result.id}`);
      router.refresh();
    } catch (e) {
      setError(
        e instanceof Error
          ? e.message
          : "Save failed. Reload to check whether it was saved before retrying.",
      );
    } finally {
      setBusy(false);
    }
  }
  return (
    <form className="panel" onSubmit={submit} aria-busy={busy}>
      <h2>{current ? "Publish next version" : "Register definition"}</h2>
      <p>
        Uses a bundled or registered trusted template and structured dataset.
        Copy exact references from Templates and Data sources.
      </p>
      <fieldset disabled={busy}>
        <legend>Definition configuration</legend>
        {!current && (
          <label>
            Definition ID
            <input
              required
              pattern="[a-z][a-z0-9-]{2,63}"
              maxLength={64}
              value={id}
              onChange={(e) => setId(e.target.value)}
            />
          </label>
        )}
        <label>
          Definition name
          <input
            required
            maxLength={100}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </label>
        <label>
          Description
          <textarea
            maxLength={1000}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
          />
        </label>
        <label>
          Template reference
          <input
            required
            value={template}
            pattern="templates/sales-summary\.jrxml|sha256:[0-9a-f]{64}"
            onChange={(e) => setTemplate(e.target.value)}
          />
        </label>
        <label>
          Data source reference
          <input
            required
            value={source}
            pattern="sample-v1|data:[0-9a-f]{64}"
            onChange={(e) => setSource(e.target.value)}
          />
        </label>
        <label className="check-label">
          <input
            type="checkbox"
            checked={enabled}
            onChange={(e) => setEnabled(e.target.checked)}
          />
          Accept new jobs
        </label>
        <p className="muted">
          Disabling blocks new submissions for all versions. Accepted jobs
          continue with their saved configuration. Changes preserve version
          history.
        </p>
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        <button type="submit">
          {busy
            ? "Saving…"
            : current
              ? "Publish version"
              : "Register definition"}
        </button>
      </fieldset>
    </form>
  );
}
