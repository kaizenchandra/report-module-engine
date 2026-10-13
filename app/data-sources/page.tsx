import Link from "next/link";
import { core, type Identity } from "@/lib/api";
import type { components } from "@/lib/api.generated";
import SourceForm from "./source-form";
import JdbcForm from "./jdbc-form";
export default async function Sources({
  searchParams,
}: {
  searchParams: Promise<{ page?: string }>;
}) {
  const query = await searchParams;
  const page = /^\d{1,5}$/.test(query.page ?? "")
    ? Math.min(Number(query.page), 10000)
    : 0;
  const [sources, user] = await Promise.all([
    core<components["schemas"]["StructuredSource"][]>(
      `/data-sources?page=${page}`,
    ),
    core<Identity>("/me"),
  ]);
  if (!sources.data || !user.data)
    return (
      <section className="panel" role="alert">
        <h1>Data sources unavailable</h1>
        <p>Sign in or retry when the service is available.</p>
        <Link href="/">Return to overview</Link>
      </section>
    );
  const admin = user.data.roles.includes("ROLE_REPORT_ADMIN");
  const [jdbc, imports] = admin
    ? await Promise.all([
        core<components["schemas"]["JdbcProfile"]>("/jdbc-source"),
        core<components["schemas"]["JdbcImport"][]>(
          `/jdbc-source/imports?page=${page}`,
        ),
      ])
    : [null, null];
  return (
    <>
      <p className="eyebrow">REPORTING INPUTS</p>
      <h1>Data sources</h1>
      <p>
        Immutable structured datasets. Definitions select an exact content
        fingerprint, so later registration cannot change an accepted job&apos;s
        rows. Administrators can also import snapshots from the
        deployment-configured PostgreSQL sales adapter.
      </p>
      <section className="panel">
        <h2>Bundled sample</h2>
        <p>
          <code>sample-v1</code> · Three synthetic sales rows · Always available
        </p>
      </section>
      {admin ? (
        <SourceForm identity={user.data} />
      ) : (
        <p>Only administrators can register data sources.</p>
      )}
      {admin &&
        (jdbc?.data ? (
          <JdbcForm identity={user.data} profile={jdbc.data} />
        ) : (
          <p role="alert">JDBC profile unavailable. Reload to retry.</p>
        ))}
      {admin && (
        <section className="panel">
          <h2>JDBC import history</h2>
          <p>
            Import completion times are UTC. Repeated imports retain separate
            provenance; identical row contents share a fingerprint.
          </p>
          {!imports?.data ? (
            <p role="alert">Import history unavailable. Reload to retry.</p>
          ) : imports.data.length === 0 ? (
            <p>No JDBC imports on this page.</p>
          ) : (
            <ul>
              {imports.data.map((item) => (
                <li key={item.id}>
                  {item.category} · {new Date(item.created_at).toISOString()} ·{" "}
                  <Link
                    href={`/definitions?dataSource=data:${item.source_sha256}`}
                  >
                    <code>data:{item.source_sha256}</code>
                  </Link>
                </li>
              ))}
            </ul>
          )}
          <nav aria-label="JDBC import pages">
            {page > 0 && (
              <Link href={`?page=${page - 1}`}>Newer JDBC imports</Link>
            )}{" "}
            {imports?.data?.length === 20 && (
              <Link href={`?page=${page + 1}`}>Older JDBC imports</Link>
            )}
          </nav>
        </section>
      )}
      <h2>Registered structured sources</h2>
      {sources.data.length === 0 && <p>No registered sources on this page.</p>}
      {sources.data.map((s) => (
        <article className="panel" key={s.sha256}>
          <h3>{s.name}</h3>
          <p>
            {s.row_count} rows · {new Date(s.created_at).toISOString()}
          </p>
          <p>
            <code>data:{s.sha256}</code>
          </p>
          {admin && (
            <Link
              className="button"
              href={`/definitions?dataSource=data:${s.sha256}`}
            >
              Use this data source
            </Link>
          )}
        </article>
      ))}
      <nav aria-label="Data source pages">
        {page > 0 && (
          <Link href={`?page=${page - 1}`}>Previous source page</Link>
        )}{" "}
        {sources.data.length === 20 && (
          <Link href={`?page=${page + 1}`}>Older sources</Link>
        )}
      </nav>
    </>
  );
}
