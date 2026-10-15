import type { components } from "@/lib/api.generated";
type Declaration = components["schemas"]["TemplateParameter"];
type Values = NonNullable<
  components["schemas"]["ReportRequest"]["parameters"]["values"]
>;

export default function TypedParameters({
  declarations,
  values,
  onChange,
}: {
  declarations: Declaration[];
  values: Values | undefined;
  onChange: (values: Values | undefined) => void;
}) {
  function update(parameter: Declaration, raw: string) {
    const next: Values = { ...values };
    if (raw === "" && parameter.type !== "TEXT") delete next[parameter.name];
    else
      next[parameter.name] = {
        type: parameter.type,
        value:
          parameter.type === "INTEGER"
            ? Number(raw)
            : parameter.type === "BOOLEAN"
              ? raw === "true"
              : raw,
      } as Values[string];
    onChange(Object.keys(next).length ? next : undefined);
  }
  if (!declarations.length) return null;
  return (
    <fieldset>
      <legend>Template parameters</legend>
      <p className="muted">
        Every declared parameter needs a value. Decimal values retain exact
        precision; dates use the report time zone. Text is protected from
        spreadsheet formulas in CSV.
      </p>
      {declarations.map((parameter) => (
        <label key={parameter.name}>
          {parameter.name} ({parameter.type})
          {parameter.type === "BOOLEAN" ? (
            <select
              required
              value={
                values?.[parameter.name]
                  ? String(values[parameter.name].value)
                  : ""
              }
              onChange={(e) => update(parameter, e.target.value)}
            >
              <option value="">Select a boolean value</option>
              <option value="true">True</option>
              <option value="false">False</option>
            </select>
          ) : (
            <input
              required={parameter.type !== "TEXT"}
              type={
                parameter.type === "DATE"
                  ? "date"
                  : parameter.type === "INTEGER"
                    ? "number"
                    : "text"
              }
              min={
                parameter.type === "DATE"
                  ? "1900-01-01"
                  : parameter.type === "INTEGER"
                    ? -1000000
                    : undefined
              }
              max={
                parameter.type === "DATE"
                  ? "2100-12-31"
                  : parameter.type === "INTEGER"
                    ? 1000000
                    : undefined
              }
              step={parameter.type === "INTEGER" ? 1 : undefined}
              maxLength={
                parameter.type === "TEXT"
                  ? 200
                  : parameter.type === "DECIMAL"
                    ? 18
                    : undefined
              }
              inputMode={parameter.type === "DECIMAL" ? "decimal" : undefined}
              value={
                values?.[parameter.name]
                  ? String(values[parameter.name].value)
                  : ""
              }
              onChange={(e) => update(parameter, e.target.value)}
            />
          )}
        </label>
      ))}
    </fieldset>
  );
}
