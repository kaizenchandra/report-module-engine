"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
import type { components } from "@/lib/api.generated";
export default function TemplateUpload({
  identity,
  maxBytes,
  policy,
}: {
  identity: components["schemas"]["Identity"];
  maxBytes: number;
  policy: components["schemas"]["TemplatePolicy"];
}) {
  const router = useRouter();
  const [name, setName] = useState("");
  const [source, setSource] = useState("");
  const [assets, setAssets] = useState<Record<string, string>>();
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
  async function imageFiles(e: React.ChangeEvent<HTMLInputElement>) {
    const files = Array.from(e.target.files ?? []);
    setAssets(undefined);
    setError("");
    if (!files.length) return;
    if (
      files.length > (policy.maxAssetCount ?? 4) ||
      files.some((f) => f.size > (policy.maxAssetBytes ?? 8192)) ||
      files.reduce((n, f) => n + f.size, 0) >
        (policy.maxTotalAssetBytes ?? 16384)
    ) {
      setError(
        "Template assets exceed the displayed file count or byte limits.",
      );
      return;
    }
    if (
      files.some(
        (f) => !/^[a-z][a-z0-9-]{0,31}\.(png|jrtx|jrxml|ttf)$/.test(f.name),
      ) ||
      new Set(files.map((f) => f.name)).size !== files.length
    ) {
      setError(
        "Use unique lowercase filenames such as logo.png, brand.jrtx, subreport-row.jrxml or digits.ttf (letters, digits and hyphens).",
      );
      return;
    }
    setBusy(true);
    try {
      const entries = await Promise.all(
        files.map(async (f) => {
          const bytes = new Uint8Array(await f.arrayBuffer());
          return [
            `assets/${f.name}`,
            btoa(String.fromCharCode(...bytes)),
          ] as const;
        }),
      );
      setAssets(Object.fromEntries(entries));
    } catch {
      setError("Could not read template asset files. Select them again.");
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
        assets,
      };
      const body = JSON.stringify(request);
      if (new TextEncoder().encode(body).length > 65536)
        throw new Error(
          "JRXML and encoded assets exceed the 64 KiB registration request limit.",
        );
      const response = await fetch("/backend/api/v1/templates", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          [identity.csrfHeader]: identity.csrfToken,
        },
        body,
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
        <label>
          Template assets (PNG / JRTX / JRXML / TTF, optional)
          <input
            type="file"
            multiple
            accept=".png,.jrtx,.jrxml,.ttf,image/png,application/xml,text/xml"
            onChange={imageFiles}
          />
        </label>
        <p className="muted">
          Up to {policy.maxAssetCount ?? 4} PNG/JRTX/JRXML/TTF files,{" "}
          {policy.maxAssetBytes ?? 8192} bytes each,{" "}
          {policy.maxTotalAssetBytes ?? 16384} bytes total, and PNG dimensions
          up to {policy.maxAssetDimension ?? 512} pixels. PNG image expressions
          must be a quoted path such as &quot;assets/logo.png&quot;. Every
          referenced image must be supplied; unused images are rejected. At most{" "}
          {policy.maxImageElements ?? 8} image elements may reference these
          assets. JRTX imports use a quoted assets/brand.jrtx template
          declaration and may supply at most eight named constant styles,
          including inline styles. JRXML subreports use a quoted path such as
          &quot;assets/subreport-row.jrxml&quot; in a detail band or directly in
          a summary section. Each report may contain at most{" "}
          {policy.maxSubreportsPerReport ?? 4} subreport elements, with at most{" "}
          {policy.maxSubreportDepth ?? 2} dependency levels and{" "}
          {policy.maxExpandedSubreports ?? 8} expanded subreport instances along
          the graph. Detail components receive the current row; summary
          components receive all rows supplied to their immediate parent report.
          Each receives matching parent parameters and a fresh data cursor.
          Cycles, deeper graphs and custom data bindings are rejected. TTF
          subsets may contain at most {policy.maxFontGlyphs ?? 256} glyphs and
          must permit unrestricted embedding. Use a fontName such as
          assets/digits.ttf. Every character rendered with that font must be
          included. PDF embeds the font; HTML packages it. XLSX/DOCX use a
          family hint and may substitute locally.
        </p>
        {assets && (
          <ul aria-label="Selected template assets">
            {Object.keys(assets)
              .sort()
              .map((name) => (
                <li key={name}>{name}</li>
              ))}
          </ul>
        )}
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
          JasperReports 7 Java templates only, up to {maxBytes.toLocaleString()}{" "}
          UTF-8 bytes. Declare TITLE as java.lang.String. Up to eight P_
          parameters may use String, Integer, BigDecimal, Boolean or Date;
          report submissions must supply each value. Optional fields: product
          (String), quantity (Integer), amount (BigDecimal). Uses the selected
          immutable sales data source. Queries, resource bundles, properties and
          other font formats are unsupported.
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
