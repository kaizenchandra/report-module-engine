"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
import type { components } from "@/lib/api.generated";
export default function TemplateUpload({
  identity,
  maxBytes,
}: {
  identity: components["schemas"]["Identity"];
  maxBytes: number;
}) {
  const router = useRouter();
  const [name, setName] = useState("");
  const [source, setSource] = useState("");
  const [trusted, setTrusted] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function file(e: React.ChangeEvent<HTMLInputElement>) {
    const selected = e.target.files?.[0];
    setSource("");
    setError("");
    if (!selected) return;
    if (selected.size > maxBytes) {
      setError(`Source must be at most ${maxBytes} UTF-8 bytes.`);
      return;
    }
    setBusy(true);
    try {
      setSource(
        new TextDecoder("utf-8", { fatal: true }).decode(
          await selected.arrayBuffer(),
        ),
      );
    } catch {
      setError("Could not read UTF-8 JRXML source.");
    } finally {
      setBusy(false);
    }
  }
  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      const request: components["schemas"]["TemplateRegistration"] = {
        name,
        jrxml: source,
        trustedCodeAcknowledged: true,
      };
      const response = await fetch("/backend/api/v1/templates", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          [identity.csrfHeader]: identity.csrfToken,
        },
        body: JSON.stringify(request),
        signal: AbortSignal.timeout(30000),
      });
      const result = await response.json();
      if (!response.ok)
        throw new Error(
          result.detail ?? `Registration failed (${response.status})`,
        );
      router.push(`/definitions?template=sha256:${result.sha256}`);
    } catch (e) {
      setError(
        e instanceof Error
          ? e.message
          : "Registration failed. Retrying identical source recovers an existing registration.",
      );
    } finally {
      setBusy(false);
    }
  }
  return (
    <form className="panel" onSubmit={submit} aria-busy={busy}>
      <h2>Register trusted JRXML</h2>
      <p>
        Templates execute Java code with the core service&apos;s privileges.
        Upload only code reviewed by a trusted administrator. Validation and
        compilation do not provide a sandbox.
      </p>
      <fieldset disabled={busy}>
        <legend>Trusted template registration</legend>
        <label>
          Template name
          <input
            required
            maxLength={100}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </label>
        <label>
          JRXML file
          <input
            type="file"
            required
            accept=".jrxml,application/xml,text/xml"
            onChange={file}
          />
        </label>
        <label className="check-label">
          <input
            required
            type="checkbox"
            checked={trusted}
            onChange={(e) => setTrusted(e.target.checked)}
          />
          I reviewed this executable template and trust its code.
        </label>
        <p className="muted">
          Standalone JasperReports 7 Java templates only, up to{" "}
          {maxBytes.toLocaleString()} UTF-8 bytes. Declare TITLE as
          java.lang.String; optional fields: product (String), quantity
          (Integer), amount (BigDecimal). Uses synthetic sample-v1 data.
          Subreports, images, queries, bundles, properties and custom fonts are
          not supported in this increment.
        </p>
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        <button type="submit" disabled={!source || !trusted}>
          {busy
            ? "Validating and compiling…"
            : "Validate and register template"}
        </button>
      </fieldset>
    </form>
  );
}
