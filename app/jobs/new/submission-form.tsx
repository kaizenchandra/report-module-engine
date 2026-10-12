"use client";
import { useRef, useState } from "react";
import { useRouter } from "next/navigation";
import type { components } from "@/lib/api.generated";
import MultipleOutputs, {
  editableRequest,
  type MultipleRequest,
} from "./multiple-outputs";
type Request = components["schemas"]["ReportRequest"];
export default function SubmissionForm({
  destinations,
  definitions,
  initialDefinition,
  csrfToken,
  csrfHeader,
}: {
  definitions: components["schemas"]["Definition"][];
  initialDefinition: components["schemas"]["Definition"];
  destinations: components["schemas"]["Destination"][];
  csrfToken: string;
  csrfHeader: string;
}) {
  const router = useRouter();
  const [definition, setDefinition] = useState(initialDefinition);
  const [format, setFormat] = useState<Request["outputs"][number]>("PDF");
  const [locale, setLocale] =
    useState<Request["parameters"]["locale"]>(undefined);
  const [timeZone, setTimeZone] =
    useState<Request["parameters"]["timeZone"]>(undefined);
  const [title, setTitle] = useState("Quarterly sales review");
  const [destination, setDestination] = useState(
    destinations.find((d) => d.id === "local-s3")?.id ??
      destinations[0]?.id ??
      "",
  );
  const [multi, setMulti] = useState(false);
  const [multiple, setMultiple] = useState<MultipleRequest>({
    schemaVersion: "2",
    definitionId: definition.id,
    definitionVersion: definition.version,
    parameters: { title: "Quarterly sales review" },
    outputs: ["PDF"],
    optionalOutputs: [],
    deliveries: [
      { destinationRef: destination, outputs: ["PDF"], required: true },
    ],
  });
  const [advanced, setAdvanced] = useState(false);
  const [syntax, setSyntax] = useState<"json" | "yaml">("json");
  const [configuration, setConfiguration] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const pending = useRef<{ canonical: string; key: string } | null>(null);
  const request = (): Request =>
    multi
      ? {
          ...multiple,
          definitionId: definition.id,
          definitionVersion: definition.version,
          parameters: { title, locale, timeZone },
        }
      : {
          schemaVersion: "1",
          definitionId: definition.id,
          definitionVersion: definition.version,
          parameters: { title, locale, timeZone },
          outputs: [format],
          destinationRef: destination,
        };
  function responseError(
    result: {
      errors?: { field: string; message: string }[];
      detail?: string;
      title?: string;
    },
    status: number,
  ) {
    return (
      result.errors?.map((e) => `${e.field}: ${e.message}`).join("; ") ||
      result.detail ||
      result.title ||
      `Request rejected (${status})`
    );
  }
  async function convert(
    body: string,
    source: "json" | "yaml",
    target: "json" | "yaml",
  ): Promise<components["schemas"]["ConfigurationConversion"]> {
    const response = await fetch(
      `/backend/api/v1/configurations/convert?target=${target}`,
      {
        method: "POST",
        headers: {
          "Content-Type": `application/${source}`,
          [csrfHeader]: csrfToken,
        },
        body,
        signal: AbortSignal.timeout(10000),
      },
    );
    const result = await response.json();
    if (!response.ok) throw new Error(responseError(result, response.status));
    return result;
  }
  async function changeSyntax(target: "json" | "yaml") {
    if (target === syntax) return;
    setBusy(true);
    setError("");
    try {
      const result = await convert(configuration, syntax, target);
      setConfiguration(result.configuration);
      setSyntax(target);
    } catch (e) {
      setError(
        e instanceof Error
          ? e.message
          : "Conversion failed. Your draft is unchanged.",
      );
    } finally {
      setBusy(false);
    }
  }
  async function toggle() {
    if (!advanced) {
      setSyntax("json");
      setConfiguration(JSON.stringify(request(), null, 2));
      setAdvanced(true);
      setError("");
      return;
    }
    setBusy(true);
    setError("");
    try {
      const { request: parsed } = await convert(configuration, syntax, "json");
      if (!editableRequest(parsed, destinations, definitions)) {
        setError(
          "This configuration cannot be represented by the form. Keep it in the editor or choose enabled destinations.",
        );
        return;
      }
      setDefinition(
        definitions.find(
          (d) =>
            d.id === parsed.definitionId &&
            d.version === parsed.definitionVersion,
        )!,
      );
      setTitle(parsed.parameters.title);
      setLocale(parsed.parameters.locale);
      setTimeZone(parsed.parameters.timeZone);
      setMulti(parsed.schemaVersion === "2");
      if (parsed.schemaVersion === "2") setMultiple(parsed);
      else {
        setFormat(parsed.outputs[0]);
        setDestination(parsed.destinationRef);
      }
      setAdvanced(false);
    } catch (e) {
      setError(
        e instanceof Error
          ? e.message
          : "Conversion failed. Your draft is unchanged.",
      );
    } finally {
      setBusy(false);
    }
  }
  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    setBusy(true);
    try {
      const body = advanced ? configuration : JSON.stringify(request());
      const source = advanced ? syntax : "json";
      // Server normalization preserves an idempotency key across whitespace and JSON/YAML edits.
      const normalized = await convert(body, source, "json");
      const canonical = JSON.stringify(normalized.request);
      if (!pending.current || pending.current.canonical !== canonical)
        pending.current = { canonical, key: crypto.randomUUID() };
      const response = await fetch("/backend/api/v1/jobs", {
        method: "POST",
        headers: {
          "Content-Type": `application/${source}`,
          [csrfHeader]: csrfToken,
          "Idempotency-Key": pending.current.key,
        },
        body,
        signal: AbortSignal.timeout(15000),
      });
      const result = await response.json();
      if (!response.ok) {
        throw new Error(responseError(result, response.status));
      }
      router.push(`/jobs/${result.id}`);
    } catch (e) {
      setError(
        e instanceof SyntaxError
          ? "The service returned an unreadable response. Retry to recover the acknowledgement."
          : e instanceof Error
            ? e.message
            : "Submission failed. Retry with the same request to recover its acknowledgement.",
      );
    } finally {
      setBusy(false);
    }
  }
  return (
    <form className="panel" onSubmit={submit} aria-busy={busy}>
      <h2>Configure report</h2>
      <p>
        {definition.name} · Version {definition.version}
      </p>
      <fieldset disabled={busy} className="configuration-fields">
        <legend className="sr-only">Report configuration</legend>
        <button type="button" className="secondary" onClick={toggle}>
          {advanced ? "Use parameter form" : "Edit JSON / YAML"}
        </button>
        {advanced && (
          <label>
            Configuration syntax
            <select
              value={syntax}
              onChange={(e) => changeSyntax(e.target.value as "json" | "yaml")}
            >
              <option value="json">JSON</option>
              <option value="yaml">YAML</option>
            </select>
          </label>
        )}
        {advanced && (
          <p className="muted">
            Conversion validates structure without accepting a job. Comments and
            formatting are discarded when converting. YAML requires one document
            with quoted schemaVersion; anchors, aliases, tags and merge keys are
            disabled.
          </p>
        )}
        {advanced ? (
          <label>
            Declarative configuration
            <textarea
              rows={17}
              value={configuration}
              onChange={(e) => setConfiguration(e.target.value)}
              spellCheck={false}
            />
          </label>
        ) : (
          <label>
            Report title
            <input
              required
              maxLength={100}
              value={title}
              onChange={(e) => setTitle(e.target.value)}
            />
          </label>
        )}
        {!advanced && (
          <label>
            Report definition
            <select
              value={`${definition.id}:${definition.version}`}
              onChange={(e) =>
                setDefinition(
                  definitions.find(
                    (d) => `${d.id}:${d.version}` === e.target.value,
                  )!,
                )
              }
            >
              {definitions.map((d) => (
                <option
                  key={`${d.id}:${d.version}`}
                  value={`${d.id}:${d.version}`}
                >
                  {d.name} · Version {d.version}
                </option>
              ))}
            </select>
          </label>
        )}
        {!advanced && (
          <>
            <label>
              Report locale
              <select
                value={locale ?? ""}
                onChange={(e) =>
                  setLocale(
                    (e.target.value ||
                      undefined) as Request["parameters"]["locale"],
                  )
                }
              >
                <option value="">Platform default (en-US)</option>
                <option value="en-US">English (United States)</option>
                <option value="de-DE">German (Germany)</option>
                <option value="fr-FR">French (France)</option>
              </select>
            </label>
            <label>
              Report time zone
              <select
                value={timeZone ?? ""}
                onChange={(e) =>
                  setTimeZone(
                    (e.target.value ||
                      undefined) as Request["parameters"]["timeZone"],
                  )
                }
              >
                <option value="">Platform default (UTC)</option>
                <option value="UTC">UTC</option>
                <option value="Asia/Kolkata">Asia/Kolkata</option>
                <option value="America/New_York">America/New_York</option>
                <option value="Europe/Berlin">Europe/Berlin</option>
              </select>
            </label>
          </>
        )}
        {!advanced && (
          <label className="check-label">
            <input
              type="checkbox"
              checked={multi}
              onChange={(e) => {
                setMulti(e.target.checked);
                if (e.target.checked)
                  setMultiple({
                    ...multiple,
                    outputs: [format],
                    optionalOutputs: [],
                    deliveries: [
                      {
                        destinationRef: destination,
                        outputs: [format],
                        required: true,
                      },
                    ],
                  });
              }}
            />
            Multiple outputs and destinations
          </label>
        )}
        {!advanced && multi ? (
          <MultipleOutputs
            value={multiple}
            onChange={setMultiple}
            destinations={destinations}
          />
        ) : (
          !advanced && (
            <>
              <label>
                Output format
                <select
                  value={format}
                  disabled={advanced}
                  onChange={(e) =>
                    setFormat(e.target.value as Request["outputs"][number])
                  }
                >
                  <option value="PDF">PDF</option>
                  <option value="XLSX">Excel workbook (XLSX)</option>
                  <option value="CSV">CSV (UTF-8)</option>
                  <option value="DOCX">Word document (DOCX)</option>
                  <option value="HTML">HTML package (ZIP)</option>
                </select>
              </label>
              <p className="muted">
                One output per request. CSV exports enforce literal text for
                user input to prevent spreadsheet formulas.
              </p>
              <label>
                Cloud destination
                <select
                  value={destination}
                  onChange={(e) => setDestination(e.target.value)}
                  disabled={advanced}
                >
                  {destinations.map((d) => (
                    <option key={d.id} value={d.id}>
                      {d.id} ({d.provider})
                    </option>
                  ))}
                </select>
              </label>
            </>
          )
        )}
        <p className="muted">
          HTML downloads contain index.html and generated assets in a ZIP; open
          it after extracting locally. The application does not render report
          HTML. Locale and time zone apply to format-aware number/date fields.
          Static template labels and currency units remain template-owned. Uses
          the definition’s pinned data source. The server validates forms, JSON
          and YAML with the same contract.
        </p>
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        {destinations.length === 0 && (
          <p role="status">
            No enabled destinations are available. Contact an administrator.
          </p>
        )}
        <button type="submit" disabled={busy || destinations.length === 0}>
          {busy ? "Submitting…" : "Submit report"}
        </button>
        <p className="muted">
          Retries of the same validated request reuse their idempotency key,
          including equivalent JSON/YAML and whitespace changes.
        </p>
      </fieldset>
    </form>
  );
}
