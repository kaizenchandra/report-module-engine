import Link from "next/link";
import { core, type Definition, type Identity } from "@/lib/api";
import DefinitionForm from "./definition-form";
export default async function Definitions({
  searchParams,
}: {
  searchParams: Promise<{ template?: string; dataSource?: string }>;
}) {
  const query = await searchParams;
  const reference =
    query.template && /^sha256:[0-9a-f]{64}$/.test(query.template)
      ? query.template
      : undefined;
  const sourceReference =
    query.dataSource && /^data:[0-9a-f]{64}$/.test(query.dataSource)
      ? query.dataSource
      : undefined;
  const [result, user] = await Promise.all([
    core<Definition[]>("/definitions"),
    core<Identity>("/me"),
  ]);
  return (
    <>
      <p className="eyebrow">REPORTING RESOURCES</p>
      <h1>Report catalog</h1>
      <p className="intro">
        Versioned definitions approved for this workspace.
      </p>
      {!result.data ? (
        <section className="panel" role="alert">
          <h2>
            {result.status === 401 ? "Sign in required" : "Catalog unavailable"}
          </h2>
          <Link href="/">Return to overview</Link>
        </section>
      ) : result.data.length === 0 ? (
        <p>No report definitions are registered.</p>
      ) : (
        result.data.map((d) => (
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
