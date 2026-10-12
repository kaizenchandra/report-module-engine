"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
import type { components } from "@/lib/api.generated";
export default function SourceForm({
  identity,
}: {
  identity: components["schemas"]["Identity"];
}) {
  const router = useRouter();
  const [name, setName] = useState("");
  const [rows, setRows] = useState(
    '[\n  {"product": "Indigo ledger", "quantity": 4, "amount": 34.25}\n]',
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      const response = await fetch("/backend/api/v1/data-sources", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          [identity.csrfHeader]: identity.csrfToken,
        },
        body: `{"name":${JSON.stringify(name)},"rows":${rows}}`,
        signal: AbortSignal.timeout(10000),
      });
      const result = await response.json();
      if (!response.ok)
        throw new Error(
          result.detail ?? `Registration rejected (${response.status})`,
        );
      router.push(`/definitions?dataSource=data:${result.sha256}`);
    } catch (e) {
      setError(
        e instanceof Error
          ? e.message
          : "Registration failed. Retry identical rows to recover an existing registration.",
      );
    } finally {
      setBusy(false);
    }
  }
  return (
    <form className="panel" onSubmit={submit} aria-busy={busy}>
      <h2>Register structured data</h2>
      <fieldset disabled={busy}>
        <legend>Structured source configuration</legend>
        <label>
          Source name
          <input
            required
            maxLength={100}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </label>
        <label>
          Rows (JSON)
          <textarea
            required
            rows={12}
            spellCheck={false}
            value={rows}
            onChange={(e) => setRows(e.target.value)}
          />
        </label>
        <p className="muted">
          1–500 rows, at most 32,768 canonical UTF-8 bytes. Each row requires
          product (nonblank text, up to 100 characters), quantity (integer
          0–1,000,000) and amount (number with at most two decimal places,
          absolute maximum 999,999,999.99). Null, missing, extra and duplicate
          fields are rejected. Empty data is rejected. CSV text is exported
          literally to prevent spreadsheet formulas.
        </p>
        <p>
          Data is retained immutably in this workspace. Reporting users can
          generate definitions that reference it. Use synthetic data for this
          local environment.
        </p>
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        <button type="submit">
          {busy
            ? "Validating and registering…"
            : "Validate and register source"}
        </button>
      </fieldset>
    </form>
  );
}
