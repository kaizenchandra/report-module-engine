"use client";
import type { components } from "@/lib/api.generated";
export type MultipleRequest = components["schemas"]["ReportRequestV2"];
type Destination = components["schemas"]["Destination"];
const formats = ["PDF", "XLSX", "CSV", "DOCX", "HTML"] as const;

export default function MultipleOutputs({
  value,
  onChange,
  destinations,
}: {
  value: MultipleRequest;
  onChange: (value: MultipleRequest) => void;
  destinations: Destination[];
}) {
  function output(format: (typeof formats)[number], policy: string) {
    const selected = formats.filter((f) =>
      f === format ? policy !== "off" : value.outputs.includes(f),
    );
    onChange({
      ...value,
      outputs: selected,
      optionalOutputs: selected.filter((f) =>
        f === format
          ? policy === "optional"
          : value.optionalOutputs?.includes(f),
      ),
      deliveries: value.deliveries.map((d) => ({
        ...d,
        outputs: d.outputs.filter((f) => selected.includes(f)),
      })),
    });
  }
  function mapping(
    index: number,
    patch: Partial<MultipleRequest["deliveries"][number]>,
  ) {
    onChange({
      ...value,
      deliveries: value.deliveries.map((d, i) =>
        i === index ? { ...d, ...patch } : d,
      ),
    });
  }
  return (
    <>
      <fieldset>
        <legend>Outputs and required outcomes</legend>
        {formats.map((format) => (
          <label key={format}>
            {format} output
            <select
              value={
                !value.outputs.includes(format)
                  ? "off"
                  : value.optionalOutputs?.includes(format)
                    ? "optional"
                    : "required"
              }
              onChange={(e) => output(format, e.target.value)}
            >
              <option value="off">Not requested</option>
              <option value="required">Required</option>
              <option value="optional">Optional</option>
            </select>
          </label>
        ))}
      </fieldset>
      <p className="muted">
        Select at least one required output. Every output needs a delivery
        mapping; each required output needs a required delivery. An optional
        output can only have optional deliveries.
      </p>
      {value.deliveries.map((d, index) => (
        <fieldset key={index}>
          <legend>Delivery mapping {index + 1}</legend>
          <label>
            Destination for mapping {index + 1}
            <select
              value={d.destinationRef}
              onChange={(e) =>
                mapping(index, { destinationRef: e.target.value })
              }
            >
              {destinations.map((destination) => (
                <option key={destination.id} value={destination.id}>
                  {destination.id} ({destination.provider})
                </option>
              ))}
            </select>
          </label>
          {value.outputs.map((format) => (
            <label key={format} className="check-label">
              <input
                type="checkbox"
                checked={d.outputs.includes(format)}
                onChange={(e) =>
                  mapping(index, {
                    outputs: e.target.checked
                      ? [...d.outputs, format]
                      : d.outputs.filter((f) => f !== format),
                  })
                }
              />
              {format} to mapping {index + 1}
            </label>
          ))}
          <label className="check-label">
            <input
              type="checkbox"
              checked={d.required}
              onChange={(e) => mapping(index, { required: e.target.checked })}
            />
            Required delivery {index + 1}
          </label>
          <button
            type="button"
            className="secondary"
            disabled={value.deliveries.length === 1}
            onClick={() =>
              onChange({
                ...value,
                deliveries: value.deliveries.filter((_, i) => i !== index),
              })
            }
          >
            Remove mapping {index + 1}
          </button>
        </fieldset>
      ))}
      <button
        type="button"
        className="secondary"
        disabled={value.deliveries.length >= 6}
        onClick={() =>
          onChange({
            ...value,
            deliveries: [
              ...value.deliveries,
              {
                destinationRef:
                  destinations.find(
                    (d) =>
                      !value.deliveries.some((m) => m.destinationRef === d.id),
                  )?.id ??
                  destinations[0]?.id ??
                  "",
                outputs: [...value.outputs],
                required: false,
              },
            ],
          })
        }
      >
        Add delivery mapping
      </button>
      <p className="muted">
        The job waits for every export and delivery. Required failures produce
        FAILED; only optional failures produce PARTIAL_SUCCESS. Verified
        artifacts remain available in either case.
      </p>
    </>
  );
}

// Checks editor-to-form representability only; authoritative validation is in Java.
export function editableRequest(
  value: unknown,
  destinations: Destination[],
  definitions: components["schemas"]["Definition"][],
): value is components["schemas"]["ReportRequest"] {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const v = value as Record<string, unknown>;
  const hasOnly = (o: object, keys: string[]) =>
    Object.keys(o).every((k) => keys.includes(k));
  const outputList = (v: unknown): v is MultipleRequest["outputs"] =>
    Array.isArray(v) &&
    v.every((f) => formats.includes(f)) &&
    new Set(v).size === v.length;
  const destination = (v: unknown) =>
    typeof v === "string" && destinations.some((d) => d.id === v);
  if (
    !definitions.some(
      (d) => d.id === v.definitionId && d.version === v.definitionVersion,
    ) ||
    !v.parameters ||
    typeof v.parameters !== "object" ||
    Array.isArray(v.parameters) ||
    !hasOnly(v.parameters, ["title", "locale", "timeZone"]) ||
    typeof (v.parameters as { title?: unknown }).title !== "string" ||
    !outputList(v.outputs)
  )
    return false;
  if (v.schemaVersion === "1")
    return (
      hasOnly(v, [
        "schemaVersion",
        "definitionId",
        "definitionVersion",
        "parameters",
        "outputs",
        "destinationRef",
      ]) &&
      v.outputs.length === 1 &&
      destination(v.destinationRef)
    );
  return (
    v.schemaVersion === "2" &&
    hasOnly(v, [
      "schemaVersion",
      "definitionId",
      "definitionVersion",
      "parameters",
      "outputs",
      "optionalOutputs",
      "deliveries",
    ]) &&
    (v.optionalOutputs === undefined ||
      (outputList(v.optionalOutputs) &&
        v.optionalOutputs.every((f) => (v.outputs as string[]).includes(f)))) &&
    Array.isArray(v.deliveries) &&
    v.deliveries.length > 0 &&
    v.deliveries.every(
      (d) =>
        d &&
        typeof d === "object" &&
        hasOnly(d, ["destinationRef", "outputs", "required"]) &&
        destination(d.destinationRef) &&
        outputList(d.outputs) &&
        d.outputs.every((f: string) => (v.outputs as string[]).includes(f)) &&
        typeof d.required === "boolean",
    )
  );
}
