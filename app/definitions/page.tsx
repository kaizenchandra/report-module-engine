import Link from "next/link";
import type { components } from "@/lib/api.generated";
import { core, type Identity } from "@/lib/api";
import DefinitionForm from "./definition-form";
export default async function Definitions({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const raw = await searchParams;
  const query = Object.fromEntries(
    Object.entries(raw).map(([key, value]) => [
      key,
      Array.isArray(value) ? value[0] : value,
    ]),
  );
  const page = Number(query.page ?? 0);
  const filters = new URLSearchParams({ page: String(page) });
  for (const key of ["q", "status", "sort"])
    if (query[key]) filters.set(key, query[key]);
  const reference =
    query.template && /^sha256:[0-9a-f]{64}$/.test(query.template)
      ? query.template
      : undefined;
  const sourceReference =
    query.dataSource &&
    /^(data:[0-9a-f]{64}|jdbc:sales-jdbc:[a-zA-Z0-9][a-zA-Z0-9 _-]{0,39})$/.test(
      query.dataSource,
    )
      ? query.dataSource
      : undefined;
  const [result, user] = await Promise.all([
    core<components["schemas"]["DefinitionPage"]>(
      `/definition-catalog?${filters}`,
    ),
    core<Identity>("/me"),
  ]);
  const navigation = new URLSearchParams(filters);
  const context = new URLSearchParams();
  if (reference) {
    navigation.set("template", reference);
    context.set("template", reference);
  }
  if (sourceReference) {
    navigation.set("dataSource", sourceReference);
    context.set("dataSource", sourceReference);
  }
  return (
    <>
      <p className="eyebrow">REPORTING RESOURCES</p>
      <h1>Report catalog</h1>
      <p className="intro">
        Versioned definitions approved for this workspace.
      </p>
      <form
        key={filters.toString()}
        className="filters"
        aria-label="Catalog filters"
      >
        {reference && <input type="hidden" name="template" value={reference} />}
        {sourceReference && (
          <input type="hidden" name="dataSource" value={sourceReference} />
        )}
        <label>
          Search definitions
          <input
            type="search"
            name="q"
            maxLength={100}
            defaultValue={query.q ?? ""}
            placeholder="ID, name or description"
          />
        </label>
        <label>
          Definition status
          <select name="status" defaultValue={query.status ?? "all"}>
            <option value="all">All definitions</option>
            <option value="enabled">Enabled</option>
            <option value="disabled">Disabled</option>
          </select>
        </label>
        <label>
          Catalog order
          <select name="sort" defaultValue={query.sort ?? "name"}>
            <option value="name">Name A–Z</option>
            <option value="updated">Recently updated</option>
          </select>
        </label>
        <button>Search catalog</button>
        <Link href={`/definitions${context.size ? `?${context}` : ""}`}>
          Clear catalog filters
        </Link>
      </form>
      {!result.data ? (
        <section className="panel" role="alert">
          <h2>
            {result.status === 401 ? "Sign in required" : "Catalog unavailable"}
          </h2>
          <p>Check the filters or reload to retry.</p>
          <Link href="/">Return to overview</Link>
        </section>
      ) : result.data.items.length === 0 ? (
        <p>No report definitions match these filters.</p>
      ) : (
        result.data.items.map((d) => (
          <article className="panel" key={d.id}>
            <span className="badge">Version {d.version}</span>
            <h2>{d.name}</h2>
            <p>{d.description}</p>
            <p>
              <code>{d.id}</code>
            </p>
            <Link href={`/definitions/${d.id}`}>
              Details and version history
            </Link>
            {d.enabled && (
              <Link
                className="button"
                href={`/jobs/new?definition=${d.id}&version=${d.version}`}
              >
                Generate report →
              </Link>
            )}
            {!d.enabled && <p>Disabled for new submissions</p>}
          </article>
        ))
      )}
      {result.data && (
        <nav aria-label="Catalog pages">
          <p>{result.data.total} matching definitions</p>
          {page > 0 && (
            <Link
              href={`/definitions?${new URLSearchParams({ ...Object.fromEntries(navigation), page: String(page - 1) })}`}
            >
              Previous catalog page
            </Link>
          )}{" "}
          {(page + 1) * result.data.pageSize < result.data.total && (
            <Link
              href={`/definitions?${new URLSearchParams({ ...Object.fromEntries(navigation), page: String(page + 1) })}`}
            >
              Next catalog page
            </Link>
          )}
        </nav>
      )}
      {user.data?.roles.includes("ROLE_REPORT_ADMIN") && (
        <DefinitionForm
          key={`${reference ?? "bundled"}:${sourceReference ?? "sample"}`}
          sourceReference={sourceReference}
          identity={user.data}
          templateReference={reference}
        />
      )}
    </>
  );
}
