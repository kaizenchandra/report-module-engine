"use client";
import { useRef, useState } from "react";
import { useRouter } from "next/navigation";
import type { components } from "@/lib/api.generated";
export default function JdbcForm({
  identity,
  profile,
}: {
  identity: components["schemas"]["Identity"];
  profile: components["schemas"]["JdbcProfile"];
}) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [checked, setChecked] = useState("");
  const [name, setName] = useState("");
  const pending = useRef<{ canonical: string; key: string } | null>(null);
  const [category, setCategory] = useState("stationery");
  async function perform(check: boolean) {
    setBusy(true);
    setError("");
    setChecked("");
    try {
      const request: components["schemas"]["JdbcImportRequest"] = {
        name,
        category,
      };
      const canonical = JSON.stringify(request);
      if (
        !check &&
        (!pending.current || pending.current.canonical !== canonical)
      )
        pending.current = { canonical, key: crypto.randomUUID() };
      const response = await fetch(
        `/backend/api/v1/jdbc-source/${check ? "check" : "imports"}`,
        {
          method: "POST",
          headers: {
            [identity.csrfHeader]: identity.csrfToken,
            ...(check
              ? {}
              : {
                  "Content-Type": "application/json",
                  "Idempotency-Key": pending.current!.key,
                }),
          },
          ...(check ? {} : { body: JSON.stringify(request) }),
          signal: AbortSignal.timeout(15000),
        },
      );
      const result = await response.json();
      if (!response.ok)
        throw new Error(
          result.detail ?? `JDBC request rejected (${response.status})`,
        );
      if (check)
        setChecked(
          `Connection and supported columns verified at ${result.checkedAt}. Import validates actual rows.`,
        );
      else router.push(`/definitions?dataSource=data:${result.sha256}`);
    } catch (e) {
      setError(
        e instanceof Error && e.name !== "TimeoutError"
          ? e.message
          : "No acknowledgement received. Retry unchanged fields to recover the accepted snapshot while this page remains open.",
      );
    } finally {
      setBusy(false);
    }
  }
  return (
    <form
      className="panel"
      aria-busy={busy}
      onSubmit={(e) => {
        e.preventDefault();
        void perform(false);
      }}
    >
      <h2>Import PostgreSQL snapshot</h2>
      <p>
        Deployment profile: {profile.id} · {profile.adapter}. Connection
        settings, table/column mapping and credentials are managed by the
        operator outside this application. Reports receive product, quantity and
        amount fields. Each category requires nonnull, unique integral ordering
        keys.
      </p>
      {profile.mappingSha256 && (
        <p>
          Current mapping:{" "}
          <code aria-label="JDBC mapping fingerprint">
            sha256:{profile.mappingSha256}
          </code>
        </p>
      )}
      {!profile.configured && (
        <p>
          JDBC profile is not configured. Ask the deployment operator to enable
          it.
        </p>
      )}
      <fieldset disabled={busy || !profile.configured}>
        <legend>JDBC snapshot configuration</legend>
        <button type="button" onClick={() => void perform(true)}>
          Test JDBC connection
        </button>
        <label>
          Snapshot name
          <input
            required
            maxLength={100}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </label>
        <label>
          Sales category
          <input
            required
            maxLength={40}
            pattern="[a-zA-Z0-9][a-zA-Z0-9 _\-]*"
            value={category}
            onChange={(e) => setCategory(e.target.value)}
          />
        </label>
        <p>
          The fixed sales query selects this category. Local synthetic
          categories: stationery and equipment. Imports require 1–500 valid rows
          and at most 32,768 canonical bytes. Empty data is rejected.
        </p>
        <p>
          Each import reads the source now and retains immutable rows.
          Definitions using a data: reference keep that snapshot. Retrying
          unchanged fields recovers the first accepted snapshot while this page
          stays open. Changing fields or reloading starts a new import.
        </p>
        <p>
          Alternatively, create a JDBC-backed definition. Each new job reads
          current rows before acceptance and pins them for rendering, retries
          and reprocessing. This action opens definition setup without reading
          JDBC or importing rows now.
        </p>
        <button
          type="button"
          disabled={!/^[a-zA-Z0-9][a-zA-Z0-9 _-]{0,39}$/.test(category)}
          onClick={() =>
            router.push(
              `/definitions?dataSource=${encodeURIComponent(`jdbc:sales-jdbc:${category}`)}`,
            )
          }
        >
          Use JDBC for new jobs
        </button>
        <button type="submit">
          {busy ? "Contacting JDBC source…" : "Import snapshot"}
        </button>
      </fieldset>
      {checked && <p role="status">{checked}</p>}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
    </form>
  );
}
